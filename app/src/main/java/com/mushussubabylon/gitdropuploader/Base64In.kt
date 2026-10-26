package com.mushussubabylon.gitdropuploader

import java.io.ByteArrayOutputStream

/**
 * Small Base64 decoder (RFC 4648, standard and URL-safe alphabets, line breaks ignored).
 *
 * The counterpart of [Base64Out]: `java.util.Base64` needs Android 8, `android.util.Base64` is
 * Android-only, and this keeps the engine runnable on a plain JVM as well.
 */
object Base64In {

    fun decode(text: String): ByteArray {
        val out = ByteArrayOutputStream(text.length * 3 / 4 + 3)
        var acc = 0
        var bits = 0
        for (ch in text) {
            val v = valueOf(ch)
            if (v == PAD) break // padding: the data ends here
            if (v < 0) continue // line break or other noise
            acc = (acc shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((acc shr bits) and 0xFF)
            }
        }
        return out.toByteArray()
    }

    private const val PAD = -2

    private fun valueOf(c: Char): Int = when (c) {
        in 'A'..'Z' -> c - 'A'
        in 'a'..'z' -> c - 'a' + 26
        in '0'..'9' -> c - '0' + 52
        '+', '-' -> 62
        '/', '_' -> 63
        '=' -> PAD
        else -> -1
    }
}
