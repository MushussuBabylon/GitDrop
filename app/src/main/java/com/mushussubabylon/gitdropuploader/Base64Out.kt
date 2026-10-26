package com.mushussubabylon.gitdropuploader

import java.io.OutputStream

/**
 * Streaming Base64 encoder (RFC 4648, no line breaks).
 *
 * It exists so the upload engine does not depend on android.util.Base64OutputStream
 * (Android only) or java.util.Base64 (needs Android 8+): the same code runs on every
 * supported Android version and on a plain JVM, which is how it is unit tested.
 *
 * Call [finish] when all data has been written. It writes the padding and flushes the
 * internal buffer, but does NOT close the underlying stream.
 */
class Base64Out(private val out: OutputStream) : OutputStream() {

    private val pending = IntArray(2)
    private var pendingCount = 0
    private val buf = ByteArray(8192) // always a multiple of 4
    private var bufLen = 0
    private var finished = false

    override fun write(b: Int) {
        write(byteArrayOf(b.toByte()), 0, 1)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        check(!finished) { "stream already finished" }
        var i = off
        val end = off + len

        // Finish a triplet left over from the previous call.
        while (pendingCount != 0 && i < end) {
            if (pendingCount == 1) {
                pending[1] = b[i++].toInt() and 0xFF
                pendingCount = 2
            } else {
                val b2 = b[i++].toInt() and 0xFF
                emit(pending[0], pending[1], b2)
                pendingCount = 0
            }
        }

        // Whole triplets.
        while (end - i >= 3) {
            emit(b[i].toInt() and 0xFF, b[i + 1].toInt() and 0xFF, b[i + 2].toInt() and 0xFF)
            i += 3
        }

        // Keep the 0-2 trailing bytes for the next call (or for finish()).
        while (i < end) {
            pending[pendingCount++] = b[i++].toInt() and 0xFF
        }
    }

    private fun emit(b0: Int, b1: Int, b2: Int) {
        if (bufLen + 4 > buf.size) drain()
        buf[bufLen++] = ALPHABET[b0 ushr 2]
        buf[bufLen++] = ALPHABET[((b0 and 3) shl 4) or (b1 ushr 4)]
        buf[bufLen++] = ALPHABET[((b1 and 15) shl 2) or (b2 ushr 6)]
        buf[bufLen++] = ALPHABET[b2 and 63]
    }

    private fun drain() {
        if (bufLen > 0) {
            out.write(buf, 0, bufLen)
            bufLen = 0
        }
    }

    /** Writes the final padding and flushes everything buffered. Safe to call twice. */
    fun finish() {
        if (finished) return
        finished = true
        if (pendingCount == 1) {
            val b0 = pending[0]
            if (bufLen + 4 > buf.size) drain()
            buf[bufLen++] = ALPHABET[b0 ushr 2]
            buf[bufLen++] = ALPHABET[(b0 and 3) shl 4]
            buf[bufLen++] = PAD
            buf[bufLen++] = PAD
        } else if (pendingCount == 2) {
            val b0 = pending[0]
            val b1 = pending[1]
            if (bufLen + 4 > buf.size) drain()
            buf[bufLen++] = ALPHABET[b0 ushr 2]
            buf[bufLen++] = ALPHABET[((b0 and 3) shl 4) or (b1 ushr 4)]
            buf[bufLen++] = ALPHABET[(b1 and 15) shl 2]
            buf[bufLen++] = PAD
        }
        pendingCount = 0
        drain()
    }

    override fun flush() {
        drain()
        out.flush()
    }

    /** Finishes the encoding but leaves the wrapped stream open. */
    override fun close() {
        finish()
    }

    companion object {
        private val ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toByteArray(Charsets.US_ASCII)
        private const val PAD = '='.code.toByte()

        /** Number of Base64 characters produced for [rawLength] input bytes. */
        fun encodedLength(rawLength: Long): Long = (rawLength + 2) / 3 * 4

        /** Encodes a whole array into [out] (convenience for small in-memory payloads). */
        fun encode(data: ByteArray, out: OutputStream) {
            val enc = Base64Out(out)
            enc.write(data, 0, data.size)
            enc.finish()
        }
    }
}
