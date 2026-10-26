package com.mushussubabylon.gitdropuploader

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class Base64InTest {

    private fun decodeText(s: String) = String(Base64In.decode(s), Charsets.UTF_8)

    @Test
    fun decodesPaddedAndUnpaddedInput() {
        assertEquals("Hello, world!", decodeText("SGVsbG8sIHdvcmxkIQ=="))
        assertEquals("Hello", decodeText("SGVsbG8="))
        assertEquals("Hello", decodeText("SGVsbG8"))
        assertEquals("", decodeText(""))
    }

    @Test
    fun ignoresLineBreaksLikeGitHubSendsThem() {
        assertEquals("Hello, world!", decodeText("SGVsbG8s\nIHdvcmxk\nIQ==\n"))
    }

    @Test
    fun understandsTheUrlSafeAlphabetToo() {
        val expected = byteArrayOf(0xFB.toByte(), 0xFF.toByte(), 0xBE.toByte())
        assertArrayEquals(expected, Base64In.decode("+/++"))
        assertArrayEquals(expected, Base64In.decode("-_--"))
    }

    @Test
    fun roundTripsWithTheEncoderOfTheJdk() {
        val data = ByteArray(1000) { (it * 31 + 7).toByte() }
        val encoded = java.util.Base64.getMimeEncoder().encodeToString(data)
        assertArrayEquals(data, Base64In.decode(encoded))
    }
}
