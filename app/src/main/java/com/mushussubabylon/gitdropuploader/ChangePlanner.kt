package com.mushussubabylon.gitdropuploader

import org.json.JSONException
import java.io.ByteArrayOutputStream
import java.io.IOException

// ---------------------------------------------------------------------------
// "Review before upload": compares the selection with the target branch and looks for
// secrets, WITHOUT changing anything on GitHub (read-only requests only). The result feeds the
// review screen; the upload engine still re-checks everything when the upload really runs.
// ---------------------------------------------------------------------------

/** How a file compares with the branch it will be uploaded to. */
enum class FileStatus {
    /** The path does not exist in the branch yet. */
    NEW,

    /** The path exists with different content. */
    CHANGED,

    /** The path exists with exactly the same content: nothing to upload. */
    UNCHANGED,

    /** The branch could not be compared (very large repository, network trouble ...). */
    UNKNOWN
}

/** Whether the branch could be compared with the selection. */
enum class Comparison { DONE, EMPTY_REPOSITORY, UNAVAILABLE }

class ReviewEntry(
    /** Destination path (normalised) and exact size. */
    val file: UploadFile,
    val status: FileStatus,
    val findings: List<SecretFinding>
) {
    val path: String get() = file.path
    val size: Long get() = file.size
    val flagged: Boolean get() = findings.isNotEmpty()
}

/** The app id in the upload differs from the app id of the same file in the repository. */
class IdentityWarning(val path: String, val localId: String, val remoteId: String)

class ReviewResult(
    /** Everything that could be uploaded, sorted by path. */
    val entries: List<ReviewEntry>,
    /** Files that can never be uploaded (bad name, too large, duplicate path, unreadable). */
    val skipped: List<Skipped>,
    val comparison: Comparison,
    /** Text files that were too large (or came after the scan budget ran out) to be checked for secrets. */
    val notScanned: Int,
    val identity: List<IdentityWarning>
)

enum class ReviewStep { CHECKING_BRANCH, COMPARING, ANALYZING }

interface ReviewListener {
    /** Called from the worker thread: post to the UI thread before touching views. */
    fun onStep(step: ReviewStep, current: Int, total: Int)
}

class ChangePlanner(
    token: String,
    private val repo: String,
    private val branch: String,
    private val files: List<UploadFile>,
    private val cfg: EngineConfig = EngineConfig(maxRetries = 3, maxRateWaitSec = 30)
) {
    private val client = GitHubClient(token, cfg, null)
    private val scanner = SecretScanner()

    private class RemoteBlob(val sha: String, val size: Long)

    private class Read(val size: Long, val data: ByteArray?)

    /** Stops the review as soon as possible (in-flight requests are aborted). */
    fun cancel() = client.cancel()

    fun run(listener: ReviewListener): ReviewResult {
        try {
            return execute(listener)
        } catch (e: JSONException) {
            throw UploadError(ErrorKind.NETWORK, detail = "Unexpected answer from GitHub", cause = e)
        } finally {
            client.close()
        }
    }

    // ------------------------------------------------------------------ driver

    private fun execute(listener: ReviewListener): ReviewResult {
        listener.onStep(ReviewStep.CHECKING_BRANCH, 0, 0)
        val rootTree = resolveRootTree() // null: the repository has no commit yet

        // Same path rules as the upload engine, so the review and the upload agree.
        val skipped = ArrayList<Skipped>()
        val seen = HashSet<String>()
        val candidates = ArrayList<UploadFile>()
        for (original in files) {
            client.checkCancelled()
            val path = Uploader.normalizePath(original.path)
            if (path == null) {
                skipped.add(Skipped(original.path, SkipReason.INVALID_PATH))
                continue
            }
            if (!seen.add(path)) {
                skipped.add(Skipped(path, SkipReason.DUPLICATE_PATH))
                continue
            }
            if (original.size > cfg.maxFileBytes) {
                skipped.add(Skipped(path, SkipReason.TOO_LARGE))
                continue
            }
            candidates.add(if (path == original.path) original else original.withPath(path))
        }
        candidates.sortBy { it.path }

        var comparison = Comparison.UNAVAILABLE
        var remote: Map<String, RemoteBlob>? = null
        if (rootTree == null) {
            comparison = Comparison.EMPTY_REPOSITORY
            remote = emptyMap()
        } else if (candidates.isNotEmpty()) {
            listener.onStep(ReviewStep.COMPARING, 0, 0)
            remote = loadRemoteIndex(rootTree, commonDir(candidates))
            if (remote != null) comparison = Comparison.DONE
        }
        val remoteIndex: Map<String, RemoteBlob>? = remote

        val entries = ArrayList<ReviewEntry>(candidates.size)
        var notScanned = 0
        var budget = SCAN_BUDGET_BYTES
        for ((i, f) in candidates.withIndex()) {
            client.checkCancelled()
            if (i % 5 == 0) listener.onStep(ReviewStep.ANALYZING, i, candidates.size)

            val textual = scanner.looksTextual(f.path)
            val read: Read
            try {
                read = readFile(f, textual && budget > 0)
            } catch (e: UploadCancelled) {
                throw e
            } catch (e: Exception) {
                skipped.add(Skipped(f.path, SkipReason.UNREADABLE))
                continue
            }
            if (read.size > cfg.maxFileBytes) {
                skipped.add(Skipped(f.path, SkipReason.TOO_LARGE))
                continue
            }
            val file = if (f.size < 0) f.withSize(read.size) else f
            val data = read.data

            val findings = ArrayList<SecretFinding>(scanner.scanName(file.path))
            if (data != null) {
                budget -= data.size
                findings.addAll(scanner.scanContent(file.path, data))
            } else if (textual) {
                notScanned++
            }

            val blob = remoteIndex?.get(file.path)
            val status: FileStatus = when {
                remoteIndex == null -> FileStatus.UNKNOWN
                blob == null -> FileStatus.NEW
                blob.size != file.size -> FileStatus.CHANGED
                sameContent(file, data, blob.sha) -> FileStatus.UNCHANGED
                else -> FileStatus.CHANGED
            }
            entries.add(ReviewEntry(file, status, findings))
        }
        listener.onStep(ReviewStep.ANALYZING, candidates.size, candidates.size)

        val identity: List<IdentityWarning> =
            if (remoteIndex != null && comparison == Comparison.DONE) checkIdentity(entries, remoteIndex)
            else emptyList()

        return ReviewResult(entries, skipped, comparison, notScanned, identity)
    }

    // ------------------------------------------------------------------ reading files

    /**
     * Reads just enough of a file: its content when it will be scanned and is small, otherwise
     * nothing at all (the size is already known). A file of unknown size is read once to learn it.
     */
    private fun readFile(f: UploadFile, wantContent: Boolean): Read {
        if (f.size == 0L) return Read(0L, ByteArray(0))
        if (f.size > 0) {
            if (!wantContent || f.size > SCAN_MAX_BYTES) return Read(f.size, null)
            return Read(f.size, readExactly(f, f.size))
        }
        var total = 0L
        var keeping = wantContent
        val keep = ByteArrayOutputStream()
        f.open().use { input ->
            val buf = ByteArray(32 * 1024)
            while (true) {
                client.checkCancelled()
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > cfg.maxFileBytes) break // far too large anyway: no need to read on
                if (keeping) {
                    if (total > SCAN_MAX_BYTES) {
                        keeping = false
                        keep.reset()
                    } else {
                        keep.write(buf, 0, n)
                    }
                }
            }
        }
        return Read(total, if (keeping) keep.toByteArray() else null)
    }

    private fun readExactly(f: UploadFile, size: Long): ByteArray {
        val bos = ByteArrayOutputStream(size.toInt())
        f.open().use { input ->
            val buf = ByteArray(32 * 1024)
            var total = 0L
            while (true) {
                client.checkCancelled()
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > size) throw IOException("file grew while reading")
                bos.write(buf, 0, n)
            }
            if (total != size) throw IOException("file shrank while reading")
        }
        return bos.toByteArray()
    }

    private fun readText(f: UploadFile, limit: Int): String {
        val bos = ByteArrayOutputStream()
        f.open().use { input ->
            val buf = ByteArray(16 * 1024)
            while (bos.size() < limit) {
                client.checkCancelled()
                val n = input.read(buf)
                if (n < 0) break
                bos.write(buf, 0, n)
            }
        }
        return String(bos.toByteArray(), Charsets.UTF_8)
    }

    private fun sameContent(f: UploadFile, data: ByteArray?, sha: String): Boolean {
        return try {
            if (data != null) GitHash.blobSha(data) == sha
            else f.open().use { GitHash.blobSha(f.size, it) { client.checkCancelled() } } == sha
        } catch (e: UploadCancelled) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    // ------------------------------------------------------------------ the branch

    /** Tree id of the branch head, or null when the repository has no commit at all. */
    private fun resolveRootTree(): String? {
        val ref = client.get("/repos/$repo/git/ref/heads/${GitHubClient.encodePath(branch)}")
        if (ref.ok) {
            val sha = ref.json().getJSONObject("object").getString("sha")
            val c = client.get("/repos/$repo/git/commits/$sha")
            if (!c.ok) throw UploadError(ErrorKind.BRANCH_CHECK, c.code, c.message)
            return c.json().getJSONObject("tree").getString("sha")
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

    /** The comparison is a nicety: whatever goes wrong here, the review simply shows "unknown". */
    private fun loadRemoteIndex(rootTree: String, dir: List<String>): Map<String, RemoteBlob>? {
        return try {
            fetchIndex(rootTree, dir)
        } catch (e: UploadCancelled) {
            throw e
        } catch (e: UploadError) {
            null
        } catch (e: IOException) {
            null
        } catch (e: JSONException) {
            null
        }
    }

    /**
     * Lists the blobs under [dir] in the branch (path -> id and size), or null when the listing is
     * too big or truncated to be trusted. Walking down to [dir] first keeps the final request small.
     */
    private fun fetchIndex(rootTree: String, dir: List<String>): Map<String, RemoteBlob>? {
        var tree = rootTree
        for (seg in dir) {
            val r = client.get("/repos/$repo/git/trees/$tree")
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
            if (next == null) return emptyMap() // the folder does not exist yet: everything is new
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

    // ------------------------------------------------------------------ project identity

    /**
     * Guards against uploading project A into the repository of project B: when a changed
     * `build.gradle(.kts)` declares another `applicationId` than the repository's copy, warn.
     */
    private fun checkIdentity(entries: List<ReviewEntry>, remote: Map<String, RemoteBlob>): List<IdentityWarning> {
        val out = ArrayList<IdentityWarning>()
        for (e in entries) {
            if (out.size >= MAX_IDENTITY_WARNINGS) break
            if (e.status != FileStatus.CHANGED) continue
            if (!isGradleScript(e.path)) continue
            val blob = remote[e.path] ?: continue
            try {
                val localId = GradleScripts.applicationIdOf(readText(e.file, IDENTITY_MAX_BYTES)) ?: continue
                val remoteId = GradleScripts.applicationIdOf(fetchBlobText(blob.sha) ?: continue) ?: continue
                if (localId != remoteId) out.add(IdentityWarning(e.path, localId, remoteId))
            } catch (ex: UploadCancelled) {
                throw ex
            } catch (ex: Exception) {
                // best effort: a failed check is not a warning
            }
        }
        return out
    }

    private fun isGradleScript(path: String): Boolean {
        val name = path.substringAfterLast('/')
        return name == "build.gradle" || name == "build.gradle.kts"
    }

    private fun fetchBlobText(sha: String): String? {
        val r = client.get("/repos/$repo/git/blobs/$sha", CallOptions(maxResponseBytes = 1L shl 20))
        if (!r.ok) return null
        val j = r.json()
        if (j.optString("encoding") != "base64") return null
        return String(Base64In.decode(j.optString("content", "")), Charsets.UTF_8)
    }

    companion object {
        /** Text files up to this size are read completely and scanned for secrets. */
        const val SCAN_MAX_BYTES = 2L shl 20

        /** Total amount of content scanned per review; later files are reported as "not checked". */
        const val SCAN_BUDGET_BYTES = 256L shl 20

        private const val IDENTITY_MAX_BYTES = 256 * 1024
        private const val MAX_IDENTITY_WARNINGS = 3
    }
}

/** Reads the little of a Gradle script the project check needs. */
object GradleScripts {
    private val APPLICATION_ID = Regex("""\bapplicationId\s*=?\s*["']([A-Za-z0-9_.]+)["']""")

    /** The `applicationId` declared in a Gradle script (Kotlin or Groovy syntax), or null. */
    fun applicationIdOf(script: String): String? =
        APPLICATION_ID.find(script)?.groupValues?.get(1)
}
