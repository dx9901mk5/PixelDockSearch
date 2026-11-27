package com.pixeldock.search

import com.pixeldock.search.detector.LauncherGestureBus
import com.pixeldock.search.updater.GitHubUpdateManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateAndDetectorTest {

    @Test
    fun `compareVersions detects newer remote version`() {
        assertTrue(GitHubUpdateManager.compareVersions("1.1.0", "1.0.0") > 0)
        assertTrue(GitHubUpdateManager.compareVersions("v1.2.0", "1.1.9") > 0)
        assertTrue(GitHubUpdateManager.compareVersions("2.0.0", "1.9.9") > 0)
    }

    @Test
    fun `compareVersions returns zero or negative for same or older version`() {
        assertTrue(GitHubUpdateManager.compareVersions("1.1.0", "1.1.0") == 0)
        assertTrue(GitHubUpdateManager.compareVersions("1.0.0", "1.1.0") < 0)
    }

    @Test
    fun `isMinusOnePackageOrClass matches OEM minus-one screens`() {
        assertTrue(LauncherGestureBus.isMinusOnePackageOrClass("com.vivo.hiboard", ""))
        assertTrue(LauncherGestureBus.isMinusOnePackageOrClass("com.coloros.assistantscreen", ""))
        assertTrue(LauncherGestureBus.isMinusOnePackageOrClass("com.miui.personalassistant", ""))
        assertTrue(LauncherGestureBus.isMinusOnePackageOrClass("com.samsung.android.app.spage", ""))
        assertFalse(LauncherGestureBus.isMinusOnePackageOrClass("com.bbk.launcher2", "Launcher"))
    }
}
