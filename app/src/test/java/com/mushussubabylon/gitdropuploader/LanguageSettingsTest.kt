package com.mushussubabylon.gitdropuploader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The language list shown in the app must match the translations that really exist. */
class LanguageSettingsTest {

    private fun resDir(): File =
        listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }

    @Test
    fun everyOfferedLanguageHasItsTranslation() {
        for (l in LanguageSettings.all) {
            if (l.code == "en") continue // English is the default resources
            assertTrue("missing res/values-${l.code}", File(resDir(), "values-${l.code}/strings.xml").isFile)
        }
    }

    @Test
    fun everyTranslationIsOffered() {
        val offered = LanguageSettings.all.map { it.code }.toSet() - "en"
        val translated = resDir().listFiles()!!
            .filter { it.isDirectory && Regex("values-[a-z]{2}").matches(it.name) }
            .map { it.name.removePrefix("values-") }
            .toSet()
        assertEquals(translated, offered)
    }

    @Test
    fun codesAreUnique() {
        val codes = LanguageSettings.all.map { it.code }
        assertEquals(codes.size, codes.toSet().size)
    }
}
