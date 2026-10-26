package com.mushussubabylon.gitdropuploader

import java.io.File
import java.io.IOException
import java.io.InputStream

// ---------------------------------------------------------------------------
// Data types shared by the upload engine (no Android classes in here, so the
// whole engine can be unit tested on a normal JVM).
// ---------------------------------------------------------------------------

/** One file to upload: its final path inside the repository and a way to read its bytes. */
class UploadFile(
    val path: String,
    /** Exact size in bytes, or -1 when the source cannot tell (the engine then copies it to a temp file first). */
    val size: Long,
    private val opener: () -> InputStream
) {
    /** May be called several times (retries); every call must return a fresh stream. */
    fun open(): InputStream = opener()

    fun withPath(newPath: String) = UploadFile(newPath, size, opener)

    /** Same file once its exact size is known (the review reads it to find out). */
    fun withSize(newSize: Long) = UploadFile(path, newSize, opener)

    override fun toString() = "UploadFile($path, $size)"
}

enum class SkipReason {
    /** Bigger than the per-file limit of this upload method. */
    TOO_LARGE,

    /** GitHub cannot store a file under this name (".git", "..", empty names ...). */
    INVALID_PATH,

    /** Two selected files would end up at the same place in the repository. */
    DUPLICATE_PATH,

    /** The file could not be read from the device. */
    UNREADABLE,

    /** Already in the repository with exactly the same content. */
    UNCHANGED
}

class Skipped(val path: String, val reason: SkipReason)

class UploadRequest(
    /** "owner/name" */
    val repo: String,
    val branch: String,
    val message: String,
    val files: List<UploadFile>,
    /** Scratch folder for files whose size is not known in advance. */
    val tempDir: File
)

class CommitInfo(val sha: String, val url: String)

class UploadResult(
    val commits: List<CommitInfo>,
    val filesUploaded: Int,
    val bytesUploaded: Long,
    val skipped: List<Skipped>,
    /** Branch that actually received the commits (differs from the request only for empty repositories). */
    val branch: String,
    val elapsedMs: Long
) {
    fun count(reason: SkipReason): Int = skipped.count { it.reason == reason }
    fun paths(reason: SkipReason): List<String> = skipped.filter { it.reason == reason }.map { it.path }
}

enum class Phase { CHECKING_BRANCH, PREPARING, COMPARING, UPLOADING, COMMITTING, FIRST_COMMIT }

/** Progress callbacks. They are invoked from worker threads: post to the UI thread before touching views. */
interface UploadListener {
    fun onPhase(phase: Phase, current: Int, total: Int) {}
    fun onProgress(filesDone: Int, filesTotal: Int, bytesDone: Long, bytesTotal: Long, bytesPerSec: Long) {}
    fun onWait(secondsLeft: Long, primaryLimit: Boolean) {}
    fun onRetry(what: String) {}
}

enum class ErrorKind {
    NOTHING_TO_UPLOAD,
    BRANCH_NOT_FOUND,
    BRANCH_CHECK,
    FIRST_COMMIT,
    TREE,
    COMMIT,
    BRANCH_UPDATE,
    UPLOAD,
    RATE_LIMIT,
    NETWORK
}

/** A failure the user should be told about. The UI turns it into a localized sentence. */
class UploadError(
    val kind: ErrorKind,
    val code: Int = 0,
    val detail: String = "",
    /** File path, or the branch name for BRANCH_NOT_FOUND. */
    val path: String = "",
    /** For RATE_LIMIT: when GitHub allows requests again (epoch seconds). */
    val resetEpochSec: Long = 0,
    cause: Throwable? = null
) : IOException("$kind $code $detail $path".trim(), cause) {
    /** Files that were already committed to the branch before this error happened. */
    var committedFiles: Int = 0
}

class UploadCancelled : IOException("cancelled") {
    /** Files that were already committed to the branch when the upload was stopped. */
    var committedFiles: Int = 0
}

/** Reading a file on the device failed (as opposed to a network problem). Never retried. */
class LocalIoException(val path: String, cause: Throwable) :
    IOException("cannot read $path: ${cause.message}", cause)

class ResponseTooLarge : IOException("response too large")

/** Tunables. The defaults follow GitHub's documented rate limits. */
class EngineConfig(
    val baseUrl: String = "https://api.github.com",
    val userAgent: String = "GitDrop-Android/1.0",
    /**
     * Minimum time between the START of two write requests (POST/PATCH/PUT). GitHub asks clients
     * to wait at least one second between mutative requests to stay clear of secondary rate limits.
     */
    val mutationSpacingMs: Long = 1000L,
    /** Send small files through one GraphQL commit per batch instead of one REST request per file. */
    val useGraphql: Boolean = true,
    val packMaxFileBytes: Long = 1L shl 20,
    val packMaxBytes: Long = 6L shl 20,
    val packMaxFiles: Int = 100,
    val restGroupMaxFiles: Int = 100,
    val restGroupMaxBytes: Long = 256L shl 20,
    /** GitHub blocks files over 100 MiB; stay clearly below. */
    val maxFileBytes: Long = 95_000_000L,
    val blobWorkers: Int = 2,
    val maxRetries: Int = 3,
    val connectTimeoutMs: Int = 20_000,
    val readTimeoutMs: Int = 120_000,
    /** A request that makes no progress for this long is aborted and retried. */
    val stallTimeoutMs: Long = 45_000L,
    /** Skip files that already exist with identical content. */
    val dedupe: Boolean = true,
    val dedupeMinFiles: Int = 6,
    val dedupeMinBytes: Long = 1L shl 20,
    val maxRemoteIndexBytes: Long = 6L shl 20,
    /** Longest wait for a rate limit to clear. Beyond this the upload stops with a clear message. */
    val maxRateWaitSec: Long = 600L,
    val maxRateWaits: Int = 6
)
