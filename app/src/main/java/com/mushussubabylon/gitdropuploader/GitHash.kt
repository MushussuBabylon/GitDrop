package com.mushussubabylon.gitdropuploader

import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/**
 * Computes Git object ids locally, exactly like `git hash-object`.
 *
 * A blob id is SHA-1("blob <size>\0" + content). Knowing it lets the uploader skip
 * files that already exist, unchanged, in the repository instead of sending them again.
 */
object GitHash {

    const val EMPTY_BLOB = "e69de29bb2d1d6434b8b29ae775ad8c2e48c5391"

    fun blobSha(data: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-1")
        md.update("blob ${data.size}\u0000".toByteArray(Charsets.US_ASCII))
        md.update(data)
        return hex(md.digest())
    }

    /**
     * Hashes a stream whose length is known up front. Throws if the stream turns out to be
     * shorter or longer than [size] (the file changed while we were reading it).
     */
    fun blobSha(size: Long, input: InputStream, onBytes: ((Int) -> Unit)? = null): String {
        val md = MessageDigest.getInstance("SHA-1")
        md.update("blob $size\u0000".toByteArray(Charsets.US_ASCII))
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > size) throw IOException("file is larger than expected")
            md.update(buf, 0, n)
            onBytes?.invoke(n)
        }
        if (total != size) throw IOException("file is smaller than expected")
        return hex(md.digest())
    }

    private fun hex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 15])
        }
        return sb.toString()
    }

    private const val HEX = "0123456789abcdef"
}
