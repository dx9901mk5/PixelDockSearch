package com.pixeldock.search

import com.pixeldock.search.detector.LauncherGestureBus
import com.pixeldock.search.updater.GitHubUpdateManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateAndDetectorTest {

    @Test
    fun `compareVersions detects newer remote version`() {
        assertTrue(GitHubUpdateManager.compareVersions("1.2.0", "1.1.0") > 0)
        assertTrue(GitHubUpdateManager.compareVersions("v1.2.0", "1.1.9") > 0)
        assertTrue(GitHubUpdateManager.compareVersions("2.0.0", "1.9.9") > 0)
    }

    @Test
    fun `compareVersions returns zero or negative for same or older version`() {
        assertTrue(GitHubUpdateManager.compareVersions("1.2.0", "1.2.0") == 0)
        assertTrue(GitHubUpdateManager.compareVersions("1.1.0", "1.2.0") < 0)
    }

    @Test
    fun `isMinusOnePackageOrClass matches OEM and custom minus-one screens`() {
        assertTrue(LauncherGestureBus.isMinusOnePackageOrClass("com.vivo.hiboard", ""))
        assertTrue(LauncherGestureBus.isMinusOnePackageOrClass("com.coloros.assistantscreen", ""))
        assertTrue(LauncherGestureBus.isMinusOnePackageOrClass("com.miui.personalassistant", ""))
        assertTrue(LauncherGestureBus.isMinusOnePackageOrClass("com.samsung.android.app.spage", ""))
        assertTrue(
            LauncherGestureBus.isMinusOnePackageOrClass(
                pkg = "com.bbk.launcher2",
                cls = "",
                customPkg = "com.bbk.launcher2",
                customSig = "com.bbk.launcher2|智慧桌面",
                pageSig = "com.bbk.launcher2|智慧桌面"
            )
        )
        assertFalse(LauncherGestureBus.isMinusOnePackageOrClass("com.bbk.launcher2", "Launcher"))
    }

    @Test
    fun `app drawer and long-press popup text detectors match OriginOS labels`() {
        assertTrue(LauncherGestureBus.isAppDrawerNodeText("全部應用程式"))
        assertTrue(LauncherGestureBus.isAppDrawerNodeText("原子元件"))
        assertTrue(LauncherGestureBus.isAppDrawerNodeText("搜尋應用程式"))
        assertFalse(LauncherGestureBus.isAppDrawerNodeText("相簿"))

        assertTrue(LauncherGestureBus.isLauncherPopupText("解除安裝"))
        assertTrue(LauncherGestureBus.isLauncherPopupText("應用程式資訊"))
        assertTrue(LauncherGestureBus.isLauncherPopupText("圖示樣式"))
        assertFalse(LauncherGestureBus.isLauncherPopupText("相機"))
    }
}
