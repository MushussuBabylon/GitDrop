package com.mushussubabylon.gitdropuploader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GradleScriptsTest {

    @Test
    fun readsTheApplicationIdFromKotlinAndGroovyScripts() {
        assertEquals("com.example.app", GradleScripts.applicationIdOf("applicationId = \"com.example.app\""))
        assertEquals("com.example.app", GradleScripts.applicationIdOf("applicationId 'com.example.app'"))
        assertEquals(
            "com.example.app",
            GradleScripts.applicationIdOf("android {\n    defaultConfig {\n        applicationId = \"com.example.app\"\n    }\n}")
        )
    }

    @Test
    fun ignoresSuffixesAndScriptsWithoutAnApplicationId() {
        assertNull(GradleScripts.applicationIdOf("applicationIdSuffix = \".debug\""))
        assertNull(GradleScripts.applicationIdOf("plugins { id(\"com.android.library\") }"))
        assertNull(GradleScripts.applicationIdOf(""))
    }
}
