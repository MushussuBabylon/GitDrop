package com.mushussubabylon.gitdropuploader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BranchNamesTest {

    @Test
    fun acceptsNormalNames() {
        for (name in listOf("main", "feature/login", "phone-uploads", "release-1.2", "v1.0.0", "user/feature_x")) {
            assertTrue(name, BranchNames.isValid(name))
        }
    }

    @Test
    fun rejectsNamesGitDoesNotAllow() {
        val bad = listOf(
            "", "has space", "a..b", "/lead", "trail/", "x.lock", "~x", "a:b", "a?b", "a*b", "a[b", "a\\b",
            "@", "-flag", ".hidden", "end.", "a//b", "a@{b", "feature/.hidden", "tab\tname"
        )
        for (name in bad) assertFalse(name, BranchNames.isValid(name))
    }
}
