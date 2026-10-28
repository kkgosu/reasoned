package com.kkgosu.reasoned

import com.kkgosu.reasoned.api.AppUpdates
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdatesTest {
    @Test
    fun releaseTagsCompareNumericallyAndRejectUnsupportedFormats() {
        assertTrue(AppUpdates.isNewer("v1.10.0", "1.9"))
        assertTrue(AppUpdates.isNewer("v2.0.0", "1.99.99"))
        assertTrue(AppUpdates.isNewer("1.0.1", "1.0"))
        assertFalse(AppUpdates.isNewer("v1.0.0", "1.0"))
        assertFalse(AppUpdates.isNewer("v1.0", "1.0.1"))
        for (invalid in listOf(
            "",
            "latest",
            "v1.1.0-beta",
            "1.0.0.1",
            "-1",
            "99999999999999999999"
        )) {
            assertTrue(invalid, runCatching { AppUpdates.isNewer(invalid, "1.0") }.isFailure)
        }
    }
}
