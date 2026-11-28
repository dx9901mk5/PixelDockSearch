package com.pixeldock.search.detector

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import com.pixeldock.search.data.DetectionMode
import com.pixeldock.search.data.PrefsRepository
import com.pixeldock.search.shizuku.ShizukuManager
import com.pixeldock.search.shizuku.TaskMonitorUserService

class LauncherDetector(
    private val context: Context,
    private val prefs: PrefsRepository,
    private val shizukuManager: ShizukuManager
) {

    @Volatile
    private var cachedLauncherPackages: Set<String> = emptySet()

    @Volatile
    private var lastRefreshMs: Long = 0L

    @Volatile
    private var lastKnownUsagePackage: String = ""

    @Volatile
    private var lastDrawerCheckMs: Long = 0L

    @Volatile
    private var cachedShizukuDrawerVisible: Boolean = false

    fun getDefaultLauncherPackage(): String? {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolveInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.resolveActivity(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }
        val pkg = resolveInfo?.activityInfo?.packageName
        return if (pkg.isNullOrBlank() || pkg == "android") null else pkg
    }

    fun getLauncherPackages(): Set<String> {
        val now = System.currentTimeMillis()
        if (cachedLauncherPackages.isNotEmpty() && now - lastRefreshMs < 30_000L) {
            return cachedLauncherPackages + prefs.extraLauncherPackages
        }
        val found = linkedSetOf<String>()
        getDefaultLauncherPackage()?.let { found.add(it) }

        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val allHomes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.queryIntentActivities(
                homeIntent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.queryIntentActivities(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
        }
        for (ri in allHomes) {
            val pkg = ri.activityInfo?.packageName
            if (!pkg.isNullOrBlank() && pkg != "android" && pkg != "com.android.settings") {
                found.add(pkg)
            }
        }
        cachedLauncherPackages = found
        lastRefreshMs = now
        return found + prefs.extraLauncherPackages
    }

    fun isHomeScreenActive(): Boolean {
        val launchers = getLauncherPackages()
        if (launchers.isEmpty()) return false

        return when (prefs.detectionMode) {
            DetectionMode.ACCESSIBILITY -> {
                if (AccessibilityForegroundState.isConnected &&
                    !AccessibilityForegroundState.isSystemOverlay &&
                    !AccessibilityForegroundState.isAppDrawerOpen &&
                    !AccessibilityForegroundState.isLauncherPopupOpen &&
                    !AccessibilityForegroundState.isMinusOneActive
                ) {
                    AccessibilityForegroundState.foregroundPackage in launchers
                } else {
                    false
                }
            }
            DetectionMode.USAGE_STATS -> {
                val pkg = queryUsageStatsForeground()
                if (LauncherGestureBus.isMinusOnePackageOrClass(
                        pkg, "", prefs.customMinusOnePackage, prefs.customMinusOneSignature, ""
                    )
                ) return false
                pkg in launchers
            }
            DetectionMode.AUTO_SHIZUKU -> {
                // 1. Accessibility instant state if enabled
                if (AccessibilityForegroundState.isConnected) {
                    if (AccessibilityForegroundState.isSystemOverlay ||
                        AccessibilityForegroundState.isAppDrawerOpen ||
                        AccessibilityForegroundState.isLauncherPopupOpen ||
                        AccessibilityForegroundState.isMinusOneActive
                    ) {
                        return false
                    }
                    val accPkg = AccessibilityForegroundState.foregroundPackage
                    if (accPkg.isNotBlank()) return accPkg in launchers
                }
                // 2. Shizuku UserService
                val shizukuPkg = shizukuManager.getForegroundPackage()
                if (shizukuPkg != null) {
                    if (shizukuPkg == TaskMonitorUserService.SYSTEM_UI_OVERLAY) return false
                    if (LauncherGestureBus.isMinusOnePackageOrClass(
                            shizukuPkg, "", prefs.customMinusOnePackage, prefs.customMinusOneSignature, ""
                        )
                    ) return false
                    if (shizukuPkg.isNotBlank()) {
                        val inLauncher = shizukuPkg in launchers
                        if (!inLauncher) {
                            cachedShizukuDrawerVisible = false
                            return false
                        }
                        val now = System.currentTimeMillis()
                        if (now - lastDrawerCheckMs >= 320L) {
                            lastDrawerCheckMs = now
                            cachedShizukuDrawerVisible = shizukuManager.isLauncherDrawerOrPopupVisible()
                        }
                        return !cachedShizukuDrawerVisible
                    }
                }
                // 3. UsageStats fallback
                if (hasUsageStatsPermission(context)) {
                    val usagePkg = queryUsageStatsForeground()
                    if (LauncherGestureBus.isMinusOnePackageOrClass(
                            usagePkg, "", prefs.customMinusOnePackage, prefs.customMinusOneSignature, ""
                        )
                    ) return false
                    return usagePkg in launchers
                }
                false
            }
        }
    }

    fun isMinusOneScreenActive(): Boolean {
        if (AccessibilityForegroundState.isConnected && AccessibilityForegroundState.isMinusOneActive) {
            return true
        }
        val shizukuPkg = shizukuManager.getForegroundPackage()
        if (!shizukuPkg.isNullOrBlank() && LauncherGestureBus.isMinusOnePackageOrClass(
                shizukuPkg,
                "",
                prefs.customMinusOnePackage,
                prefs.customMinusOneSignature,
                AccessibilityForegroundState.currentPageSignature
            )
        ) {
            return true
        }
        return false
    }

    fun activeEngineLabel(): String {
        return when (prefs.detectionMode) {
            DetectionMode.ACCESSIBILITY ->
                if (AccessibilityForegroundState.isConnected) "無障礙即時偵測" else "無障礙未啟用"
            DetectionMode.USAGE_STATS ->
                if (hasUsageStatsPermission(context)) "使用情況存取 (UsageStats)" else "未授權使用情況存取"
            DetectionMode.AUTO_SHIZUKU -> when {
                AccessibilityForegroundState.isConnected -> "無障礙即時偵測 (優先)"
                shizukuManager.taskService != null -> "Shizuku TaskMonitor (運作中)"
                shizukuManager.isAvailable() && shizukuManager.hasPermission() -> "Shizuku 連線中..."
                hasUsageStatsPermission(context) -> "UsageStats 備援模式"
                else -> "待授權 (請開啟 Shizuku 或使用情況存取)"
            }
        }
    }

    private fun queryUsageStatsForeground(): String {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return ""
        val endTime = System.currentTimeMillis()
        val beginTime = endTime - 8_000L
        return try {
            val events = usm.queryEvents(beginTime, endTime)
            val event = UsageEvents.Event()
            var latestPkg = ""
            var latestTime = 0L
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val isForegroundEvent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    event.eventType == UsageEvents.Event.ACTIVITY_RESUMED
                } else {
                    @Suppress("DEPRECATION")
                    event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
                }
                if (isForegroundEvent && event.timeStamp >= latestTime) {
                    latestTime = event.timeStamp
                    latestPkg = event.packageName ?: ""
                }
            }
            if (latestPkg.isNotBlank()) {
                lastKnownUsagePackage = latestPkg
            }
            lastKnownUsagePackage
        } catch (_: Throwable) {
            lastKnownUsagePackage
        }
    }

    companion object {
        fun hasUsageStatsPermission(context: Context): Boolean {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                )
            }
            return mode == AppOpsManager.MODE_ALLOWED
        }
    }
}

object AccessibilityForegroundState {
    @Volatile
    var isConnected: Boolean = false

    @Volatile
    var foregroundPackage: String = ""

    @Volatile
    var isSystemOverlay: Boolean = false

    @Volatile
    var isAppDrawerOpen: Boolean = false

    @Volatile
    var isLauncherPopupOpen: Boolean = false

    @Volatile
    var isMinusOneActive: Boolean = false

    @Volatile
    var isFloatingAppOpen: Boolean = false

    @Volatile
    var currentWindowClass: String = ""

    @Volatile
    var currentPageSignature: String = ""

    @Volatile
    var isOnConfirmedFirstPage: Boolean = true
}
