package com.example.familyarchivegallery

import com.example.familyarchivegallery.data.update.VersionOrder
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionOrderTest {
    @Test
    fun detectsNewerSemanticVersion() {
        assertTrue(VersionOrder.isNewer("v1.1.0", "1.0.9"))
        assertTrue(VersionOrder.isNewer("2.0.0", "1.99.99"))
    }

    @Test
    fun ignoresTagPrefixAndDebugSuffix() {
        assertFalse(VersionOrder.isNewer("v1.0.0", "1.0.0-debug"))
        assertTrue(VersionOrder.isNewer("v1.0.1", "1.0.0-debug"))
    }

    @Test
    fun olderReleaseIsNotAnUpdate() {
        assertFalse(VersionOrder.isNewer("0.9.9", "1.0.0"))
    }
}
