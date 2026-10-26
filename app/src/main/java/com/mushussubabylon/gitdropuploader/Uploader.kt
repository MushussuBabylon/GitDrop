package com.mushussubabylon.gitdropuploader

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.ArrayDeque
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * The upload engine. It turns a list of files into commits on one branch while staying inside
 * GitHub's documented limits:
 *
 *  - Small files (<= 1 MB) are grouped and sent with GraphQL `createCommitOnBranch`: ONE request
 *    carries up to 100 files / 6 MB and creates the commit atomically. Sending 300 small files costs
 *    a handful of requests instead of 300, which is what keeps the secondary rate limit quiet.
 *  - Bigger files go through the Git Data API (blob per file, then one tree + commit per group),
 *    at most two at a time and with write requests spaced one second apart.
 *  - Files that already exist in the repository with identical content are not sent again.
 *  - Rate limits are waited out (Retry-After / x-ratelimit-reset), transient errors are retried,
 *    a branch that moved meanwhile is handled, the branch is never force-pushed.
 */
class Uploader(
    private val cfg: EngineConfig,
    token: String,
    private val listener: UploadListener
) {
    private val client = GitHubClient(token, cfg, listener)

    /** Stops the upload as soon as possible (in-flight requests are aborted). */
    fun cancel() = client.cancel()

    fun run(req: UploadRequest): UploadResult = Run(req).execute()

    // -----------------------------------------------------------------------

    private class Head(val sha: String, var tree: String?)
    private class TreeEntry(val path: String, val sha: String)
    private class RemoteBlob(val sha: String, val size: Long)

    private sealed class PackOutcome {
        object Done : PackOutcome()

        /** The request was too big or timed out: try again with smaller packs. */
        object Shrink : PackOutcome()

        /** GraphQL cannot do this one: use the Git Data API instead. */
        class Fallback(val why: String) : PackOutcome()
    }

    private inner class Tracker {
        @Volatile
        var filesTotal = 0

        @Volatile
        var bytesTotal = 0L
        val filesDone = AtomicInteger()
        val bytesDone = AtomicLong()
        private var lastNotify = 0L
        private val samples = ArrayDeque<LongArray>()

        fun setTotals(files: List<UploadFile>) {
            filesTotal = files.size
            bytesTotal = files.sumOf { it.size.coerceAtLeast(0) }
            notifyNow(true)
        }

        fun dropFile(size: Long) {
            filesTotal -= 1
            bytesTotal -= size.coerceAtLeast(0)
            notifyNow(true)
        }

        fun addBytes(n: Int) {
            bytesDone.addAndGet(n.toLong())
            notifyNow(false)
        }

        fun rollback(n: Long) {
            bytesDone.addAndGet(-n)
            notifyNow(true)
        }

        fun addFiles(n: Int) {
            filesDone.addAndGet(n)
            notifyNow(true)
        }

        @Synchronized
        fun notifyNow(force: Boolean) {
            val now = System.currentTimeMillis()
            if (!force && now - lastNotify < 200) return
            lastNotify = now
            val done = bytesDone.get()
            samples.addLast(longArrayOf(now, done))
            while (samples.size > 2) {
                val oldest = samples.peekFirst() ?: break
                if (now - oldest[0] <= 6000) break
                samples.removeFirst()
            }
            val first = samples.peekFirst() ?: return
            val dt = now - first[0]
            val speed = if (dt >= 500) (done - first[1]) * 1000 / dt else 0L
            listener.onProgress(filesDone.get(), filesTotal, done, bytesTotal, speed.coerceAtLeast(0))
        }
    }

    private inner class Run(val req: UploadRequest) {
        val repo = req.repo
        var branch = req.branch
        var head: Head? = null
        val skipped: MutableList<Skipped> = Collections.synchronizedList(ArrayList<Skipped>())
        val commits = ArrayList<CommitInfo>()
        val tempFiles = ArrayList<File>()
        var committedFiles = 0
        val tracker = Tracker()
        var useGraphql = cfg.useGraphql
        var multiCommit = false
        var expectedCommits = 1

        private var emptyBlob: String? = null
        private val emptyLock = Any()

        // ----- driver --------------------------------------------------------

        fun execute(): UploadResult {
            val t0 = System.currentTimeMillis()
            try {
                listener.onPhase(Phase.CHECKING_BRANCH, 0, 0)
                head = resolveHead()

                listener.onPhase(Phase.PREPARING, 0, 0)
                var files = prepare()
                if (files.isEmpty()) {
                    if (skipped.isEmpty()) throw UploadError(ErrorKind.NOTHING_TO_UPLOAD)
                    return finish(t0)
                }
                tracker.setTotals(files)

                if (head == null) files = createFirstCommit(files)
                if (files.isNotEmpty()) files = dropUnchanged(files)
                if (files.isEmpty()) return finish(t0)

                val small = ArrayList<UploadFile>()
                val rest = ArrayList<UploadFile>()
                for (f in files) {
                    if (useGraphql && f.size in 1..cfg.packMaxFileBytes) small.add(f) else rest.add(f)
                }
                expectedCommits = commits.size + countPacks(small) + chunkRest(rest).size
                multiCommit = multiCommit || expectedCommits > 1

                if (small.isNotEmpty()) runPacks(small, rest)
                if (rest.isNotEmpty()) runRest(rest)
                return finish(t0)
            } catch (e: UploadError) {
                e.committedFiles = committedFiles
                throw e
            } catch (e: UploadCancelled) {
                e.committedFiles = committedFiles
                throw e
            } catch (e: JSONException) {
                val err = UploadError(ErrorKind.NETWORK, detail = "Unexpected answer from GitHub", cause = e)
                err.committedFiles = committedFiles
                throw err
            } finally {
                for (f in tempFiles) f.delete()
                client.close()
            }
        }

        private fun finish(t0: Long): UploadResult {
            tracker.notifyNow(true)
            return UploadResult(
                commits.toList(), committedFiles, tracker.bytesDone.get(),
                synchronized(skipped) { ArrayList(skipped) }, branch,
                System.currentTimeMillis() - t0
            )
        }

        // ----- branch --------------------------------------------------------

        /** Returns the branch head, or null when the repository is completely empty. */
        private fun resolveHead(): Head? {
            val ref = client.get("/repos/$repo/git/ref/heads/${GitHubClient.encodePath(branch)}")
            if (ref.ok) {
                val sha = ref.json().getJSONObject("object").getString("sha")
                return Head(sha, treeOfCommit(sha))
            }
            if (ref.code == 409 || ref.message.contains("empty", ignoreCase = true)) return null
            if (ref.code == 404) {
                // "Not found" can also mean "this repository has no branch at all yet".
                val branches = client.get("/repos/$repo/branches?per_page=1")
                if (branches.ok && branches.text.trim() == "[]") return null
                throw UploadError(ErrorKind.BRANCH_NOT_FOUND, 404, repo, branch)
            }
            throw UploadError(ErrorKind.BRANCH_CHECK, ref.code, ref.message)
        }

        private fun treeOfCommit(sha: String): String {
            val c = client.get("/repos/$repo/git/commits/$sha")
            if (!c.ok) throw UploadError(ErrorKind.BRANCH_CHECK, c.code, c.message)
            return c.json().getJSONObject("tree").getString("sha")
        }

        private fun treeOf(h: Head): String {
            h.tree?.let { return it }
            val t = treeOfCommit(h.sha)
            h.tree = t
            return t
        }

        private fun refreshHead() {
            val r = client.get("/repos/$repo/git/ref/heads/${GitHubClient.encodePath(branch)}")
            if (!r.ok) throw UploadError(ErrorKind.BRANCH_CHECK, r.code, r.message)
            head = Head(r.json().getJSONObject("object").getString("sha"), null)
        }

        // ----- preparation ---------------------------------------------------

        /** Cleans paths, drops what GitHub cannot take, and learns the size of every file. */
        private fun prepare(): List<UploadFile> {
            val seen = HashSet<String>()
            val out = ArrayList<UploadFile>()
            for (original in req.files) {
                client.checkCancelled()
                val path = normalizePath(original.path)
                if (path == null) {
                    skipped.add(Skipped(original.path, SkipReason.INVALID_PATH))
                    continue
                }
                if (!seen.add(path)) {
                    skipped.add(Skipped(path, SkipReason.DUPLICATE_PATH))
                    continue
                }
                var f = if (path == original.path) original else original.withPath(path)
                if (f.size > cfg.maxFileBytes) {
                    skipped.add(Skipped(path, SkipReason.TOO_LARGE))
                    continue
                }
                if (f.size < 0) f = stage(f) ?: continue
                out.add(f)
            }
            out.sortBy { it.path }
            return out
        }

        /** Copies a file of unknown size to a temp file so its exact size (and a repeatable stream) is known. */
        private fun stage(f: UploadFile): UploadFile? {
            val tmp = File.createTempFile("gitdrop", ".tmp", req.tempDir)
            tempFiles.add(tmp)
            var n = 0L
            try {
                f.open().use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            client.checkCancelled()
                            val r = input.read(buf)
                            if (r < 0) break
                            n += r
                            if (n > cfg.maxFileBytes) {
                                skipped.add(Skipped(f.path, SkipReason.TOO_LARGE))
                                return null
                            }
                            out.write(buf, 0, r)
                        }
                    }
                }
            } catch (e: UploadCancelled) {
                throw e
            } catch (e: Exception) {
                skipped.add(Skipped(f.path, SkipReason.UNREADABLE))
                return null
            }
            return UploadFile(f.path, n) { FileInputStream(tmp) }
        }

        // ----- empty repository ----------------------------------------------

        /**
         * The Git Data API cannot write into a repository that has no commit at all, but the
         * Contents API can. The smallest file creates the first commit (and the default branch),
         * everything else then follows the normal path.
         */
        private fun createFirstCommit(files: List<UploadFile>): List<UploadFile> {
            listener.onPhase(Phase.FIRST_COMMIT, 0, 0)
            if (files.size > 1) multiCommit = true // the first file gets its own commit, the rest follow
            val candidates = files.sortedBy { it.size }.take(5)
            var lastError: UploadError? = null
            for (first in candidates) {
                val message = fullMessage(partHeadline())
                val body = Base64JsonBody(
                    "{\"message\":" + JSONObject.quote(message) + ",\"content\":\"", first, "\"}"
                )
                val res = try {
                    client.put(
                        "/repos/$repo/contents/${GitHubClient.encodePath(first.path)}", body, opts(first.path)
                    )
                } catch (e: LocalIoException) {
                    skipped.add(Skipped(first.path, SkipReason.UNREADABLE))
                    tracker.dropFile(first.size)
                    continue
                }
                if (!res.ok) {
                    lastError = UploadError(ErrorKind.FIRST_COMMIT, res.code, res.message, first.path)
                    break
                }
                val commit = res.json().getJSONObject("commit")
                val sha = commit.getString("sha")
                val tree = commit.optJSONObject("tree")?.optString("sha", "")?.ifEmpty { null }
                head = Head(sha, tree)
                commits.add(CommitInfo(sha, commit.optString("html_url", "").ifEmpty { commitUrl(sha) }))
                committedFiles += 1
                tracker.addFiles(1)

                // The branch that now exists is the repository's default branch.
                val info = client.get("/repos/$repo")
                if (info.ok) {
                    val d = info.json().optString("default_branch", "")
                    if (d.isNotEmpty()) branch = d
                }
                return files.filter { it !== first }
            }
            throw lastError ?: UploadError(ErrorKind.NOTHING_TO_UPLOAD)
        }

        // ----- skip files that are already up to date ------------------------

        private fun dropUnchanged(files: List<UploadFile>): List<UploadFile> {
            val h = head ?: return files
            if (!cfg.dedupe) return files
            val total = files.sumOf { it.size }
            if (files.size < cfg.dedupeMinFiles && total < cfg.dedupeMinBytes) return files

            listener.onPhase(Phase.COMPARING, 0, files.size)
            val remote = try {
                loadRemoteIndex(treeOf(h), commonDir(files))
            } catch (e: UploadCancelled) {
                throw e
            } catch (e: UploadError) {
                if (e.kind == ErrorKind.RATE_LIMIT) throw e
                null
            } catch (e: IOException) {
                null
            } catch (e: JSONException) {
                null
            } ?: return files
            if (remote.isEmpty()) return files

            val keep = ArrayList<UploadFile>(files.size)
            var checked = 0
            for (f in files) {
                client.checkCancelled()
                val r = remote[f.path]
                var same = false
                if (r != null && r.size == f.size) {
                    same = try {
                        f.open().use { GitHash.blobSha(f.size, it) } == r.sha
                    } catch (e: UploadCancelled) {
                        throw e
                    } catch (e: Exception) {
                        false
                    }
                }
                if (same) {
                    skipped.add(Skipped(f.path, SkipReason.UNCHANGED))
                    tracker.dropFile(f.size)
                } else {
                    keep.add(f)
                }
                checked++
                if (checked % 25 == 0) listener.onPhase(Phase.COMPARING, checked, files.size)
            }
            return keep
        }

        /** Longest folder shared by all destination paths (as path segments). */
        private fun commonDir(files: List<UploadFile>): List<String> {
            var common: List<String>? = null
            for (f in files) {
                val dir = f.path.split('/').dropLast(1)
                common = if (common == null) dir
                else common.zip(dir).takeWhile { it.first == it.second }.map { it.first }
                if (common.isEmpty()) break
            }
            return common ?: emptyList()
        }

        /**
         * Lists the blobs under [dir] in the branch (path -> id and size), or null when the listing
         * is too big or truncated to be trusted. Walking down to [dir] first keeps the final request small.
         */
        private fun loadRemoteIndex(rootTree: String, dir: List<String>): Map<String, RemoteBlob>? {
            var tree = rootTree
            for (seg in dir) {
                val r = client.get("/repos/$repo/git/trees/$tree", CallOptions())
                if (!r.ok) return null
                val j = r.json()
                if (j.optBoolean("truncated", false)) return null
                val arr = j.optJSONArray("tree") ?: return null
                var next: String? = null
                for (i in 0 until arr.length()) {
                    val e = arr.getJSONObject(i)
                    if (e.optString("path") == seg && e.optString("type") == "tree") {
                        next = e.getString("sha")
                        break
                    }
                }
                if (next == null) return emptyMap() // the folder does not exist yet: nothing to compare
                tree = next
            }
            val r = client.get(
                "/repos/$repo/git/trees/$tree?recursive=1",
                CallOptions(maxResponseBytes = cfg.maxRemoteIndexBytes)
            )
            if (!r.ok) return null
            val j = r.json()
            if (j.optBoolean("truncated", false)) return null
            val arr = j.optJSONArray("tree") ?: return null
            val prefix = if (dir.isEmpty()) "" else dir.joinToString("/") + "/"
            val out = HashMap<String, RemoteBlob>(arr.length() * 2)
            for (i in 0 until arr.length()) {
                val e = arr.getJSONObject(i)
                if (e.optString("type") != "blob") continue
                val mode = e.optString("mode")
                if (mode != "100644" && mode != "100755") continue
                out[prefix + e.getString("path")] = RemoteBlob(e.getString("sha"), e.optLong("size", -1))
            }
            return out
        }

        // ----- small files: GraphQL packs ------------------------------------

        private fun countPacks(small: List<UploadFile>): Int {
            if (small.isEmpty()) return 0
            var n = 1
            var bytes = 0L
            var count = 0
            for (f in small) {
                if (count >= cfg.packMaxFiles || (count > 0 && bytes + f.size > cfg.packMaxBytes)) {
                    n++
                    bytes = 0
                    count = 0
                }
                bytes += f.size
                count++
            }
            return n
        }

        private fun takePack(queue: ArrayDeque<UploadFile>, budget: Long, maxFiles: Int): List<UploadFile> {
            val pack = ArrayList<UploadFile>()
            var bytes = 0L
            while (queue.isNotEmpty() && pack.size < maxFiles) {
                val f = queue.peekFirst() ?: break
                if (pack.isNotEmpty() && bytes + f.size > budget) break
                queue.pollFirst()
                pack.add(f)
                bytes += f.size
            }
            return pack
        }

        private fun runPacks(small: List<UploadFile>, rest: MutableList<UploadFile>) {
            val queue = ArrayDeque<UploadFile>(small)
            var budget = cfg.packMaxBytes
            var maxFiles = cfg.packMaxFiles
            var failures = 0
            while (queue.isNotEmpty()) {
                client.checkCancelled()
                if (!useGraphql) {
                    rest.addAll(queue)
                    queue.clear()
                    break
                }
                val pack = takePack(queue, budget, maxFiles)
                listener.onPhase(Phase.UPLOADING, commits.size + 1, maxOf(expectedCommits, commits.size + 1))
                when (sendPack(pack)) {
                    is PackOutcome.Done -> failures = 0
                    is PackOutcome.Shrink -> {
                        if (pack.size == 1) {
                            rest.addAll(pack)
                        } else {
                            budget = maxOf(budget / 2, MIN_PACK_BYTES)
                            maxFiles = maxOf(maxFiles / 2, 1)
                            for (f in pack.asReversed()) queue.addFirst(f)
                            // The same files now need more commits than planned.
                            multiCommit = true
                            expectedCommits = maxOf(expectedCommits, commits.size + 2)
                        }
                    }
                    is PackOutcome.Fallback -> {
                        rest.addAll(pack)
                        failures++
                        if (failures >= 2) useGraphql = false
                    }
                }
            }
        }

        private fun readSmall(f: UploadFile): ByteArray {
            val limit = maxOf(cfg.packMaxFileBytes * 2, 64L * 1024)
            val bos = ByteArrayOutputStream(if (f.size in 1..limit) f.size.toInt() else 8192)
            f.open().use { input ->
                val buf = ByteArray(32 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > limit) throw IOException("file grew while reading")
                    bos.write(buf, 0, n)
                }
            }
            return bos.toByteArray()
        }

        private fun sendPack(pack: List<UploadFile>): PackOutcome {
            val loaded = ArrayList<PackFile>(pack.size)
            for (f in pack) {
                client.checkCancelled()
                try {
                    loaded.add(PackFile(f.path, readSmall(f)))
                } catch (e: UploadCancelled) {
                    throw e
                } catch (e: Exception) {
                    skipped.add(Skipped(f.path, SkipReason.UNREADABLE))
                    tracker.dropFile(f.size)
                }
            }
            if (loaded.isEmpty()) return PackOutcome.Done

            val headline = partHeadline()
            val messageBody = messageBody()
            var stale = 0
            var transient = 0
            val label = loaded[0].path
            while (true) {
                client.checkCancelled()
                val h = head ?: throw IllegalStateException("no branch head")
                val body = GraphqlPackBody(repo, branch, headline, messageBody, h.sha, loaded)
                val resp: GraphqlResponse
                try {
                    resp = client.graphql(body, opts(label, retry = false))
                } catch (e: UploadCancelled) {
                    throw e
                } catch (e: UploadError) {
                    throw e
                } catch (e: IOException) {
                    // The connection broke: the commit may or may not exist. Look before sending it again.
                    val applied = verifyApplied(h.sha, headline)
                    if (applied != null) return packDone(applied, loaded.size)
                    transient++
                    if (transient > cfg.maxRetries) {
                        return if (loaded.size > 1) PackOutcome.Shrink else PackOutcome.Fallback(e.message ?: "network")
                    }
                    listener.onRetry(label)
                    pause(1000L shl (transient - 1).coerceAtMost(3))
                    continue
                }

                val http = resp.http
                if (!http.ok) {
                    val code = http.code
                    return when {
                        code == 413 -> shrinkOrFallback(loaded.size, "HTTP 413")
                        code == 401 || code == 403 -> throw UploadError(ErrorKind.BRANCH_UPDATE, code, http.message)
                        code == 408 || code >= 500 -> {
                            val applied = verifyApplied(h.sha, headline)
                            if (applied != null) packDone(applied, loaded.size)
                            else shrinkOrFallback(loaded.size, "HTTP $code")
                        }
                        else -> PackOutcome.Fallback("HTTP $code: ${http.message}")
                    }
                }

                val commit = resp.json?.optJSONObject("data")?.optJSONObject("createCommitOnBranch")
                    ?.optJSONObject("commit")
                val oid = commit?.optString("oid", "") ?: ""
                if (commit != null && oid.isNotEmpty()) {
                    return packDone(CommitInfo(oid, commit.optString("url", "").ifEmpty { commitUrl(oid) }), loaded.size)
                }

                val text = resp.errorText()
                val lower = text.lowercase()
                when {
                    lower.contains("expected branch to point to") || resp.hasErrorType("STALE_DATA") -> {
                        stale++
                        if (stale > 3) throw UploadError(ErrorKind.BRANCH_UPDATE, 409, text)
                        refreshHead() // somebody pushed meanwhile: continue on top of their commit
                    }
                    isFatalGraphqlError(lower) -> throw UploadError(ErrorKind.BRANCH_UPDATE, 200, text)
                    else -> return PackOutcome.Fallback(text)
                }
            }
        }

        private fun shrinkOrFallback(count: Int, why: String): PackOutcome =
            if (count > 1) PackOutcome.Shrink else PackOutcome.Fallback(why)

        private fun isFatalGraphqlError(lower: String): Boolean = FATAL_GRAPHQL.any { lower.contains(it) }

        private fun packDone(commit: CommitInfo, files: Int): PackOutcome {
            head = Head(commit.sha, null)
            commits.add(commit)
            committedFiles += files
            tracker.addFiles(files)
            return PackOutcome.Done
        }

        /**
         * After a broken connection: did GitHub create our commit anyway? It did if the branch now
         * points to a commit whose parent is the head we sent and whose message is ours.
         */
        private fun verifyApplied(expectedHead: String, headline: String): CommitInfo? {
            try {
                val r = client.get("/repos/$repo/git/ref/heads/${GitHubClient.encodePath(branch)}")
                if (!r.ok) return null
                val sha = r.json().getJSONObject("object").getString("sha")
                if (sha == expectedHead) return null
                val c = client.get("/repos/$repo/git/commits/$sha")
                if (c.ok) {
                    val j = c.json()
                    val parent = j.optJSONArray("parents")?.optJSONObject(0)?.optString("sha", "")
                    if (parent == expectedHead && j.optString("message", "").startsWith(headline)) {
                        return CommitInfo(sha, j.optString("html_url", "").ifEmpty { commitUrl(sha) })
                    }
                }
                head = Head(sha, null) // the branch moved: the next try builds on the new head
            } catch (e: UploadCancelled) {
                throw e
            } catch (e: UploadError) {
                if (e.kind == ErrorKind.RATE_LIMIT) throw e
            } catch (e: Exception) {
            }
            return null
        }

        // ----- bigger files: Git Data API ------------------------------------

        private fun chunkRest(files: List<UploadFile>): List<List<UploadFile>> {
            val out = ArrayList<List<UploadFile>>()
            var cur = ArrayList<UploadFile>()
            var bytes = 0L
            for (f in files) {
                if (cur.isNotEmpty() && (cur.size >= cfg.restGroupMaxFiles || bytes + f.size > cfg.restGroupMaxBytes)) {
                    out.add(cur)
                    cur = ArrayList()
                    bytes = 0
                }
                cur.add(f)
                bytes += f.size
            }
            if (cur.isNotEmpty()) out.add(cur)
            return out
        }

        private fun runRest(files: List<UploadFile>) {
            val groups = chunkRest(files.sortedBy { it.path })
            expectedCommits = maxOf(expectedCommits, commits.size + groups.size)
            multiCommit = multiCommit || expectedCommits > 1
            val workers = minOf(cfg.blobWorkers, files.size).coerceAtLeast(1)
            val pool = Executors.newFixedThreadPool(workers, ThreadFactory { r ->
                Thread(r, "gitdrop-blob").apply { isDaemon = true }
            })
            try {
                // All blob uploads are queued up front: while one group is being committed the
                // workers already send the next group's files.
                val futures = groups.map { g -> g.map { f -> pool.submit(Callable<TreeEntry?> { uploadBlobSafely(f) }) } }
                for ((gi, group) in futures.withIndex()) {
                    listener.onPhase(Phase.UPLOADING, commits.size + 1, maxOf(expectedCommits, commits.size + 1))
                    val entries = ArrayList<TreeEntry>()
                    for (fu in group) {
                        val entry = try {
                            fu.get()
                        } catch (e: ExecutionException) {
                            throw e.cause ?: e
                        } catch (e: InterruptedException) {
                            Thread.currentThread().interrupt()
                            throw UploadCancelled()
                        }
                        if (entry != null) entries.add(entry)
                    }
                    if (entries.isNotEmpty()) {
                        listener.onPhase(Phase.COMMITTING, gi + 1, groups.size)
                        restCommit(entries)
                    }
                }
            } catch (t: Throwable) {
                client.cancel() // stop whatever the workers are still sending
                throw t
            } finally {
                pool.shutdownNow()
            }
        }

        private fun uploadBlobSafely(f: UploadFile): TreeEntry? {
            return try {
                TreeEntry(f.path, uploadBlob(f))
            } catch (e: LocalIoException) {
                skipped.add(Skipped(f.path, SkipReason.UNREADABLE))
                tracker.dropFile(f.size)
                null
            }
        }

        private fun uploadBlob(f: UploadFile): String {
            if (f.size == 0L) {
                // Every empty file is the same blob: create it once.
                synchronized(emptyLock) {
                    emptyBlob?.let { sha ->
                        tracker.addFiles(1)
                        return sha
                    }
                    val sha = postBlob(f)
                    emptyBlob = sha
                    tracker.addFiles(1)
                    return sha
                }
            }
            val sha = postBlob(f)
            tracker.addFiles(1)
            return sha
        }

        private fun postBlob(f: UploadFile): String {
            val body = Base64JsonBody("{\"content\":\"", f, "\",\"encoding\":\"base64\"}")
            val res = client.post("/repos/$repo/git/blobs", body, opts(f.path))
            if (!res.ok) throw UploadError(ErrorKind.UPLOAD, res.code, res.message, f.path)
            return res.json().getString("sha")
        }

        /** tree -> commit -> branch update, rebasing onto the new head if the branch moved meanwhile. */
        private fun restCommit(entries: List<TreeEntry>) {
            var rebases = 0
            while (true) {
                client.checkCancelled()
                val h = head ?: throw IllegalStateException("no branch head")
                val baseTree = treeOf(h)

                val tree = JSONArray()
                for (e in entries) {
                    tree.put(
                        JSONObject().put("path", e.path).put("mode", "100644").put("type", "blob").put("sha", e.sha)
                    )
                }
                val treeJson = JSONObject().put("base_tree", baseTree).put("tree", tree)
                val tr = client.post("/repos/$repo/git/trees", jsonBody(treeJson.toString()), opts("", countBytes = false))
                if (!tr.ok) throw UploadError(ErrorKind.TREE, tr.code, tr.message)
                val newTree = tr.json().getString("sha")

                val commitJson = JSONObject().put("message", fullMessage(partHeadline())).put("tree", newTree)
                    .put("parents", JSONArray().put(h.sha))
                val cr = client.post("/repos/$repo/git/commits", jsonBody(commitJson.toString()), opts("", countBytes = false))
                if (!cr.ok) throw UploadError(ErrorKind.COMMIT, cr.code, cr.message)
                val commitSha = cr.json().getString("sha")
                val url = cr.json().optString("html_url", "").ifEmpty { commitUrl(commitSha) }

                val ur = client.patch(
                    "/repos/$repo/git/refs/heads/${GitHubClient.encodePath(branch)}",
                    jsonBody("{\"sha\":\"$commitSha\",\"force\":false}"), opts("", countBytes = false)
                )
                if (ur.ok) {
                    head = Head(commitSha, newTree)
                    commits.add(CommitInfo(commitSha, url))
                    committedFiles += entries.size
                    return
                }
                if (ur.code == 422 && ur.message.contains("fast forward", ignoreCase = true) && rebases < 3) {
                    rebases++
                    refreshHead() // someone pushed meanwhile: put our commit on top of theirs
                    continue
                }
                throw UploadError(ErrorKind.BRANCH_UPDATE, ur.code, ur.message)
            }
        }

        // ----- helpers -------------------------------------------------------

        /** [countBytes] is false for small bookkeeping requests, so the progress bar only counts file content. */
        private fun opts(label: String, retry: Boolean = true, countBytes: Boolean = true) = CallOptions(
            label = label,
            retryTransient = retry,
            onBytes = { n -> if (countBytes) tracker.addBytes(n) },
            onRollback = { n -> if (countBytes) tracker.rollback(n) }
        )

        private fun pause(ms: Long) {
            var left = ms
            while (left > 0) {
                client.checkCancelled()
                val step = minOf(left, 250L)
                try {
                    Thread.sleep(step)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw UploadCancelled()
                }
                left -= step
            }
        }

        private fun commitUrl(sha: String) = "https://github.com/$repo/commit/$sha"

        private fun messageParts(): Pair<String, String?> = splitMessage(req.message)

        private fun messageBody(): String? = messageParts().second

        /** First line of the commit message, numbered when the upload needs several commits. */
        private fun partHeadline(): String {
            val h = messageParts().first
            return if (multiCommit || commits.isNotEmpty()) "$h (part ${commits.size + 1})" else h
        }

        private fun fullMessage(headline: String): String {
            val b = messageBody()
            return if (b == null) headline else "$headline\n\n$b"
        }
    }

    companion object {
        private const val MIN_PACK_BYTES = 256L * 1024

        private val FATAL_GRAPHQL = listOf(
            "protected branch", "pull request", "status check", "push access", "write access",
            "permission", "not accessible", "forbidden", "saml", "bad credentials", "archived",
            "read-only", "repository rule", "rule violation", "must be signed"
        )

        /** GitHub refuses ".git", "." and ".." as path parts; empty parts are removed. */
        fun normalizePath(raw: String): String? {
            val parts = raw.replace('\\', '/').split('/').filter { it.isNotEmpty() }
            if (parts.isEmpty()) return null
            for (p in parts) {
                if (p == "." || p == ".." || p.equals(".git", ignoreCase = true)) return null
                if (p.any { it.code < 32 }) return null
            }
            return parts.joinToString("/")
        }

        fun splitMessage(message: String): Pair<String, String?> {
            val t = message.trim().replace("\r\n", "\n")
            val idx = t.indexOf('\n')
            if (idx < 0) return Pair(t.ifEmpty { "Upload files" }, null)
            val head = t.substring(0, idx).trim().ifEmpty { "Upload files" }
            val body = t.substring(idx + 1).trim()
            return Pair(head, body.ifEmpty { null })
        }
    }
}

class PackFile(val path: String, val bytes: ByteArray)

/** The GraphQL request for one `createCommitOnBranch` call, streamed without building one giant string. */
class GraphqlPackBody(
    repo: String,
    branch: String,
    headline: String,
    messageBody: String?,
    expectedHead: String,
    private val files: List<PackFile>
) : RequestBody {
    private val head: ByteArray
    private val prefixes: List<ByteArray>
    private val suffix = "\"}".toByteArray(Charsets.UTF_8)
    private val tail = "]}}}}".toByteArray(Charsets.UTF_8)
    override val length: Long
    override val contentType: String get() = "application/json"

    init {
        val sb = StringBuilder()
        sb.append("{\"query\":\"mutation(\$input: CreateCommitOnBranchInput!){")
        sb.append("createCommitOnBranch(input:\$input){commit{oid url}}}\",")
        sb.append("\"variables\":{\"input\":{")
        sb.append("\"branch\":{\"repositoryNameWithOwner\":").append(JSONObject.quote(repo))
        sb.append(",\"branchName\":").append(JSONObject.quote(branch)).append("},")
        sb.append("\"message\":{\"headline\":").append(JSONObject.quote(headline))
        if (!messageBody.isNullOrEmpty()) sb.append(",\"body\":").append(JSONObject.quote(messageBody))
        sb.append("},")
        sb.append("\"expectedHeadOid\":").append(JSONObject.quote(expectedHead)).append(',')
        sb.append("\"fileChanges\":{\"additions\":[")
        head = sb.toString().toByteArray(Charsets.UTF_8)

        prefixes = files.mapIndexed { i, f ->
            ((if (i > 0) "," else "") + "{\"path\":" + JSONObject.quote(f.path) + ",\"contents\":\"")
                .toByteArray(Charsets.UTF_8)
        }
        var len = head.size.toLong() + tail.size
        for ((i, f) in files.withIndex()) {
            len += prefixes[i].size + Base64Out.encodedLength(f.bytes.size.toLong()) + suffix.size
        }
        length = len
    }

    override fun writeTo(out: java.io.OutputStream, progress: (Int) -> Unit) {
        out.write(head)
        for ((i, f) in files.withIndex()) {
            out.write(prefixes[i])
            Base64Out.encode(f.bytes, out)
            out.write(suffix)
            progress(f.bytes.size)
        }
        out.write(tail)
    }
}
