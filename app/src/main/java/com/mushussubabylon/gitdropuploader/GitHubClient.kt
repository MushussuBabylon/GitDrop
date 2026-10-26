package com.mushussubabylon.gitdropuploader

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpRetryException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

// ---------------------------------------------------------------------------
// HTTP layer. Everything that talks to api.github.com goes through here so the
// rules GitHub documents for well-behaved clients live in exactly one place:
//   * write requests start at least one second apart (secondary rate limits)
//   * Retry-After / x-ratelimit-reset are honoured, never hammered
//   * transient failures are retried with exponential back-off
//   * a stalled connection is aborted instead of hanging forever
//   * the user can cancel at any moment
// ---------------------------------------------------------------------------

/** A request body that can be written more than once (needed for retries). */
interface RequestBody {
    val length: Long
    val contentType: String
    /** [progress] receives the number of payload bytes handed to the socket. */
    fun writeTo(out: OutputStream, progress: (Int) -> Unit)
}

class BytesBody(private val bytes: ByteArray) : RequestBody {
    override val length: Long get() = bytes.size.toLong()
    override val contentType: String get() = "application/json"
    override fun writeTo(out: OutputStream, progress: (Int) -> Unit) {
        out.write(bytes)
        progress(bytes.size)
    }
}

fun jsonBody(text: String): RequestBody = BytesBody(text.toByteArray(Charsets.UTF_8))

/**
 * JSON text wrapped around one Base64-encoded file: `prefix` + base64(file) + `suffix`.
 * The file is streamed from disk, so memory use stays tiny whatever the file size.
 */
class Base64JsonBody(
    prefix: String,
    private val file: UploadFile,
    suffix: String
) : RequestBody {
    private val pre = prefix.toByteArray(Charsets.UTF_8)
    private val suf = suffix.toByteArray(Charsets.UTF_8)

    override val length: Long = pre.size + Base64Out.encodedLength(file.size) + suf.size
    override val contentType: String get() = "application/json"

    override fun writeTo(out: OutputStream, progress: (Int) -> Unit) {
        out.write(pre)
        val enc = Base64Out(out)
        val buf = ByteArray(48 * 1024)
        var total = 0L
        val input: InputStream = try {
            file.open()
        } catch (e: IOException) {
            throw LocalIoException(file.path, e)
        } catch (e: RuntimeException) {
            throw LocalIoException(file.path, e)
        }
        try {
            while (true) {
                val n = try {
                    input.read(buf)
                } catch (e: IOException) {
                    throw LocalIoException(file.path, e)
                }
                if (n < 0) break
                total += n
                if (total > file.size) throw LocalIoException(file.path, IOException("file grew while uploading"))
                enc.write(buf, 0, n)
                progress(n)
            }
        } finally {
            try {
                input.close()
            } catch (_: Exception) {
            }
        }
        if (total != file.size) throw LocalIoException(file.path, IOException("file shrank while uploading"))
        enc.finish()
        out.write(suf)
    }
}

class HttpResult(
    val code: Int,
    val text: String,
    val retryAfterSec: Long?,
    val rateRemaining: Int?,
    val rateResetSec: Long?
) {
    val ok: Boolean get() = code in 200..299

    /** GitHub's own explanation for a failure (the "message" field, plus validation details). */
    val message: String get() = errorMessage(text)

    fun json(): JSONObject = JSONObject(text)

    companion object {
        fun errorMessage(text: String): String {
            val t = text.trim()
            if (t.startsWith("{")) {
                try {
                    val o = JSONObject(t)
                    val m = o.optString("message", "")
                    if (m.isNotEmpty()) {
                        val details = StringBuilder()
                        val errs = o.optJSONArray("errors")
                        if (errs != null) {
                            for (i in 0 until minOf(errs.length(), 3)) {
                                val e = errs.opt(i)
                                val s = when (e) {
                                    is JSONObject -> e.optString("message", "").ifEmpty {
                                        listOf(e.optString("field", ""), e.optString("code", ""))
                                            .filter { it.isNotEmpty() }.joinToString(" ")
                                    }
                                    else -> e?.toString() ?: ""
                                }
                                if (s.isNotEmpty()) {
                                    if (details.isNotEmpty()) details.append("; ")
                                    details.append(s)
                                }
                            }
                        }
                        val full = if (details.isEmpty()) m else "$m ($details)"
                        return full.take(400)
                    }
                } catch (_: Exception) {
                }
            }
            return t.replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim().take(300)
        }
    }
}

class GraphqlResponse(val http: HttpResult, val json: JSONObject?) {
    val errors: JSONArray? get() = json?.optJSONArray("errors")

    fun errorText(): String {
        val errs = errors
        if (errs == null || errs.length() == 0) return if (http.ok) "" else http.message
        val sb = StringBuilder()
        for (i in 0 until minOf(errs.length(), 3)) {
            val m = errs.optJSONObject(i)?.optString("message", "") ?: ""
            if (m.isNotEmpty()) {
                if (sb.isNotEmpty()) sb.append("; ")
                sb.append(m)
            }
        }
        return sb.toString().take(400)
    }

    fun hasErrorType(type: String): Boolean {
        val errs = errors ?: return false
        for (i in 0 until errs.length()) {
            if (errs.optJSONObject(i)?.optString("type", "") == type) return true
        }
        return false
    }
}

class CallOptions(
    /** Shown to the user when a retry happens (usually the file name). */
    val label: String = "",
    /** Retry network errors and 5xx answers. Off for requests whose outcome must be checked by hand. */
    val retryTransient: Boolean = true,
    val readTimeoutMs: Int = 0,
    val maxResponseBytes: Long = 8L shl 20,
    /** Called with the payload bytes written to the socket. */
    val onBytes: (Int) -> Unit = {},
    /** Called when an attempt failed after sending [bytes] payload bytes, so progress can be rolled back. */
    val onRollback: (Long) -> Unit = {}
)

class GitHubClient(
    private val token: String,
    private val cfg: EngineConfig,
    private val listener: UploadListener? = null
) {
    @Volatile
    private var cancelled = false

    @Volatile
    private var closed = false

    private class Active(val conn: HttpURLConnection) {
        @Volatile
        var lastProgress = System.currentTimeMillis()

        @Volatile
        var writing = false
    }

    private val active: MutableSet<Active> = Collections.newSetFromMap(ConcurrentHashMap<Active, Boolean>())

    // pacing of write requests
    private val paceLock = Any()
    private var nextSlotMs = 0L
    private var blockedUntilMs = 0L
    private var secondaryHits = 0

    private var watchdog: Thread? = null

    // ----- cancellation -----------------------------------------------------

    fun cancel() {
        cancelled = true
        for (a in active) {
            try {
                a.conn.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    fun isCancelled(): Boolean = cancelled

    fun checkCancelled() {
        if (cancelled || Thread.currentThread().isInterrupted) throw UploadCancelled()
    }

    fun close() {
        closed = true
    }

    // ----- public request helpers ------------------------------------------

    fun get(path: String, opts: CallOptions = CallOptions()): HttpResult = call("GET", path, null, opts)

    fun post(path: String, body: RequestBody, opts: CallOptions = CallOptions()): HttpResult =
        call("POST", path, body, opts)

    fun patch(path: String, body: RequestBody, opts: CallOptions = CallOptions()): HttpResult =
        call("PATCH", path, body, opts)

    fun put(path: String, body: RequestBody, opts: CallOptions = CallOptions()): HttpResult =
        call("PUT", path, body, opts)

    /**
     * Runs a GraphQL request. Rate limits that GitHub reports inside a normal 200 answer are
     * handled here (wait, then try again); everything else is returned to the caller.
     */
    fun graphql(body: RequestBody, opts: CallOptions): GraphqlResponse {
        var waits = 0
        while (true) {
            val res = call("POST", "/graphql", body, opts)
            if (!res.ok) return GraphqlResponse(res, null)
            val json = try {
                JSONObject(res.text)
            } catch (_: Exception) {
                return GraphqlResponse(res, null)
            }
            val resp = GraphqlResponse(res, json)
            if (resp.hasErrorType("RATE_LIMITED")) {
                waits++
                if (waits > cfg.maxRateWaits) return resp
                val now = System.currentTimeMillis() / 1000
                val resetIn = res.rateResetSec?.let { (it - now).coerceAtLeast(1) + 1 } ?: 60L
                waitForRateLimit(RateLimit(true, resetIn * 1000))
                continue
            }
            return resp
        }
    }

    // ----- core call with retry / rate-limit policy -------------------------

    fun call(method: String, path: String, body: RequestBody?, opts: CallOptions = CallOptions()): HttpResult {
        var transient = 0
        var rateHits = 0
        while (true) {
            checkCancelled()
            awaitSlot(method != "GET")
            var sent = 0L
            val res: HttpResult
            try {
                res = send(method, path, body, opts) { n ->
                    sent += n
                    opts.onBytes(n)
                }
            } catch (e: UploadCancelled) {
                opts.onRollback(sent)
                throw e
            } catch (e: LocalIoException) {
                opts.onRollback(sent)
                throw e
            } catch (e: ResponseTooLarge) {
                throw e
            } catch (e: IOException) {
                opts.onRollback(sent)
                if (cancelled) throw UploadCancelled()
                if (!opts.retryTransient) throw e
                transient++
                if (transient > cfg.maxRetries) {
                    throw UploadError(ErrorKind.NETWORK, detail = e.message ?: e.javaClass.simpleName, cause = e)
                }
                listener?.onRetry(opts.label)
                sleepQuietly(backoffMs(transient))
                continue
            }

            if (res.ok) {
                synchronized(paceLock) { secondaryHits = 0 }
                return res
            }
            opts.onRollback(sent)

            val limit = classifyRateLimit(res)
            if (limit != null) {
                rateHits++
                if (rateHits > cfg.maxRateWaits) return res
                waitForRateLimit(limit)
                continue
            }
            if (opts.retryTransient && (res.code == 408 || res.code >= 500) && transient < cfg.maxRetries) {
                transient++
                listener?.onRetry(opts.label)
                sleepQuietly(backoffMs(transient))
                continue
            }
            return res
        }
    }

    // ----- one HTTP exchange ------------------------------------------------

    private fun send(
        method: String,
        path: String,
        body: RequestBody?,
        opts: CallOptions,
        onBytes: (Int) -> Unit
    ): HttpResult {
        ensureWatchdog()
        val conn = URL(cfg.baseUrl + path).openConnection() as HttpURLConnection
        val act = Active(conn)
        active.add(act)
        try {
            conn.requestMethod = method
            conn.connectTimeout = cfg.connectTimeoutMs
            conn.readTimeout = if (opts.readTimeoutMs > 0) opts.readTimeoutMs else cfg.readTimeoutMs
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            conn.setRequestProperty("User-Agent", cfg.userAgent)
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", body.contentType)
                conn.setFixedLengthStreamingMode(body.length)
                act.writing = true
                act.lastProgress = System.currentTimeMillis()
                conn.outputStream.use { out ->
                    body.writeTo(out) { n ->
                        act.lastProgress = System.currentTimeMillis()
                        onBytes(n)
                        checkCancelled()
                    }
                }
                act.writing = false
            }
            act.lastProgress = System.currentTimeMillis()

            var errorText: String? = null
            val code = try {
                conn.responseCode
            } catch (e: HttpRetryException) {
                // 401/407 while streaming a body: the JDK cannot replay it, but the status is what we need.
                errorText = "{\"message\":\"${(e.reason ?: "").replace("\"", "'")}\"}"
                e.responseCode()
            }

            val retryAfter = conn.getHeaderField("Retry-After")?.trim()?.toLongOrNull()
            val remaining = conn.getHeaderField("x-ratelimit-remaining")?.trim()?.toIntOrNull()
            val reset = conn.getHeaderField("x-ratelimit-reset")?.trim()?.toLongOrNull()

            val text = errorText ?: run {
                val stream: InputStream? = if (code in 200..299) conn.inputStream else conn.errorStream
                readBody(conn, stream, opts.maxResponseBytes)
            }
            return HttpResult(code, text, retryAfter, remaining, reset)
        } catch (e: IOException) {
            act.writing = false
            // Make sure a half-used connection is never reused.
            try {
                conn.disconnect()
            } catch (_: Exception) {
            }
            if (cancelled) throw UploadCancelled()
            throw e
        } finally {
            active.remove(act)
        }
    }

    private fun readBody(conn: HttpURLConnection, stream: InputStream?, limit: Long): String {
        if (stream == null) return ""
        val declared = conn.contentLengthLong
        if (declared > limit) {
            try {
                conn.disconnect()
            } catch (_: Exception) {
            }
            throw ResponseTooLarge()
        }
        return stream.use { s ->
            val bos = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            var total = 0L
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                total += n
                if (total > limit) {
                    try {
                        conn.disconnect()
                    } catch (_: Exception) {
                    }
                    throw ResponseTooLarge()
                }
                bos.write(buf, 0, n)
            }
            String(bos.toByteArray(), Charsets.UTF_8)
        }
    }

    // ----- rate limits ------------------------------------------------------

    class RateLimit(val primary: Boolean, val waitMs: Long)

    private fun classifyRateLimit(res: HttpResult): RateLimit? {
        if (res.code != 403 && res.code != 429) return null
        val msg = res.message.lowercase()
        val nowSec = System.currentTimeMillis() / 1000

        val retryAfter = res.retryAfterSec
        if (retryAfter != null) {
            // "Do not retry until after that many seconds have elapsed."
            return RateLimit(false, retryAfter.coerceAtLeast(1) * 1000 + 500)
        }
        if (res.rateRemaining == 0 && res.rateResetSec != null) {
            val wait = (res.rateResetSec - nowSec).coerceAtLeast(1) + 1
            return RateLimit(true, wait * 1000)
        }
        if (msg.contains("secondary rate limit") || msg.contains("abuse detection") || msg.contains("abuse")) {
            // No Retry-After: GitHub says to wait at least a minute and then back off exponentially.
            val hits = synchronized(paceLock) { ++secondaryHits }
            val wait = 60L shl (hits - 1).coerceIn(0, 2)
            return RateLimit(false, wait * 1000)
        }
        if (msg.contains("rate limit")) return RateLimit(true, 60_000L)
        if (res.code == 429) return RateLimit(false, 60_000L)
        return null
    }

    private fun waitForRateLimit(limit: RateLimit) {
        val nowMs = System.currentTimeMillis()
        if (limit.waitMs > cfg.maxRateWaitSec * 1000) {
            throw UploadError(ErrorKind.RATE_LIMIT, resetEpochSec = (nowMs + limit.waitMs) / 1000)
        }
        synchronized(paceLock) {
            blockedUntilMs = maxOf(blockedUntilMs, nowMs + limit.waitMs)
        }
        val end = nowMs + limit.waitMs
        while (true) {
            val left = end - System.currentTimeMillis()
            if (left <= 0) return
            checkCancelled()
            listener?.onWait((left + 999) / 1000, limit.primary)
            sleepInterruptibly(minOf(left, 1000L))
        }
    }

    /** Spaces write requests apart and keeps everything quiet while a rate limit is active. */
    private fun awaitSlot(mutative: Boolean) {
        while (true) {
            checkCancelled()
            val waitMs: Long
            synchronized(paceLock) {
                val now = System.currentTimeMillis()
                val earliest = if (mutative) maxOf(nextSlotMs, blockedUntilMs) else blockedUntilMs
                if (now >= earliest) {
                    if (mutative) nextSlotMs = now + cfg.mutationSpacingMs
                    return
                }
                waitMs = earliest - now
            }
            sleepInterruptibly(minOf(waitMs, 500L))
        }
    }

    private fun backoffMs(attempt: Int): Long {
        val base = minOf(1000L shl (attempt - 1).coerceIn(0, 4), 8000L)
        return base + (Math.random() * 250).toLong()
    }

    private fun sleepQuietly(ms: Long) {
        var left = ms
        while (left > 0) {
            checkCancelled()
            val step = minOf(left, 250L)
            sleepInterruptibly(step)
            left -= step
        }
    }

    private fun sleepInterruptibly(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw UploadCancelled()
        }
    }

    // ----- stall watchdog ---------------------------------------------------

    private fun ensureWatchdog() {
        if (watchdog != null) return
        synchronized(this) {
            if (watchdog != null) return
            val t = Thread(Runnable {
                while (!closed) {
                    try {
                        Thread.sleep(5000)
                    } catch (_: InterruptedException) {
                        return@Runnable
                    }
                    val now = System.currentTimeMillis()
                    for (a in active) {
                        if (a.writing && now - a.lastProgress > cfg.stallTimeoutMs) {
                            try {
                                a.conn.disconnect()
                            } catch (_: Exception) {
                            }
                        }
                    }
                }
            }, "gitdrop-watchdog")
            t.isDaemon = true
            t.start()
            watchdog = t
        }
    }

    companion object {
        /** Percent-encodes every segment of a repository path or branch name, keeping the slashes. */
        fun encodePath(path: String): String =
            path.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
    }
}
