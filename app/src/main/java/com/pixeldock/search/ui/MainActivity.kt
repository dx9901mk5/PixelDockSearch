package com.pixeldock.search.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pixeldock.search.BuildConfig
import com.pixeldock.search.R
import com.pixeldock.search.data.DetectionMode
import com.pixeldock.search.data.FeedTriggerMode
import com.pixeldock.search.data.PrefsRepository
import com.pixeldock.search.data.ThemeMode
import com.pixeldock.search.databinding.ActivityMainBinding
import com.pixeldock.search.detector.LauncherDetector
import com.pixeldock.search.feed.NativeGoogleFeedBridge
import com.pixeldock.search.model.DisplayProfile
import com.pixeldock.search.service.OverlaySearchService
import com.pixeldock.search.shizuku.ShizukuManager
import com.pixeldock.search.updater.GitHubUpdateManager
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: PrefsRepository
    private lateinit var shizukuManager: ShizukuManager
    private lateinit var detector: LauncherDetector

    private val bgExecutor = Executors.newSingleThreadExecutor()
    private var suppressListeners = false
    private var latestRelease: GitHubUpdateManager.ReleaseInfo? = null
    private var pendingInstallApk: File? = null
    private var isDownloadingUpdate = false

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshStatusUi() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = PrefsRepository(this)
        shizukuManager = ShizukuManager(this)
        detector = LauncherDetector(this, prefs, shizukuManager)

        shizukuManager.start {
            runOnUiThread {
                if (shizukuManager.hasPermission() && !LauncherDetector.hasUsageStatsPermission(this)) {
                    shizukuManager.grantUsageStatsToSelf()
                }
                refreshStatusUi()
            }
        }

        requestNotificationPermissionIfNeeded()
        NativeGoogleFeedBridge.prewarm(this)
        setupListeners()
        syncControlsFromPrefs()
        refreshStatusUi()
        checkForUpdates(autoPrompt = true)
    }

    override fun onResume() {
        super.onResume()
        syncControlsFromPrefs()
        refreshStatusUi()

        val pendingApk = pendingInstallApk
        if (pendingApk != null && pendingApk.exists()) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls()) {
                pendingInstallApk = null
                GitHubUpdateManager.installApk(this, pendingApk)
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        syncControlsFromPrefs()
        refreshStatusUi()
    }

    override fun onDestroy() {
        shizukuManager.stop()
        bgExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun setupListeners() {
        binding.btnOverlayPermission.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }

        binding.btnShizukuPermission.setOnClickListener {
            when {
                !shizukuManager.isAvailable() -> {
                    Toast.makeText(this, "偵測不到 Shizuku，請先啟動 Shizuku App", Toast.LENGTH_SHORT).show()
                }
                shizukuManager.hasPermission() -> {
                    shizukuManager.bindServiceIfNeeded()
                    Toast.makeText(this, "Shizuku 已授權並連線", Toast.LENGTH_SHORT).show()
                }
                else -> {
                    shizukuManager.requestPermission()
                }
            }
        }

        binding.switchMasterService.setOnCheckedChangeListener { _, isChecked ->
            if (suppressListeners) return@setOnCheckedChangeListener
            if (isChecked && !Settings.canDrawOverlays(this)) {
                suppressListeners = true
                binding.switchMasterService.isChecked = false
                suppressListeners = false
                Toast.makeText(this, "請先授予「顯示在其他應用程式上層」權限", Toast.LENGTH_SHORT).show()
                return@setOnCheckedChangeListener
            }
            prefs.serviceEnabled = isChecked
            if (isChecked) {
                OverlaySearchService.start(this)
            } else {
                OverlaySearchService.stop(this)
            }
            refreshStatusUi()
        }

        binding.btnGoHomeCalibrate.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "請先授予懸浮窗權限", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.serviceEnabled = true
            suppressListeners = true
            binding.switchMasterService.isChecked = true
            suppressListeners = false

            OverlaySearchService.start(this, OverlaySearchService.ACTION_ENTER_CALIBRATION)
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(homeIntent)
        }

        binding.btnResetProfile.setOnClickListener {
            val snapshot = DisplayProfile.fromContext(this)
            prefs.resetLayout(snapshot.profile)
            notifyOverlayRefresh()
            refreshStatusUi()
            Toast.makeText(this, "已重設 ${snapshot.profile.displayNameZh}", Toast.LENGTH_SHORT).show()
        }

        binding.rgDetectionMode.setOnCheckedChangeListener { _, checkedId ->
            if (suppressListeners) return@setOnCheckedChangeListener
            prefs.detectionMode = when (checkedId) {
                R.id.rbUsageStats -> DetectionMode.USAGE_STATS
                R.id.rbAccessibility -> DetectionMode.ACCESSIBILITY
                else -> DetectionMode.AUTO_SHIZUKU
            }
            refreshStatusUi()
        }

        binding.btnUsageSettings.setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }

        binding.btnAccessibilitySettings.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.rgThemeMode.setOnCheckedChangeListener { _, checkedId ->
            if (suppressListeners) return@setOnCheckedChangeListener
            prefs.themeMode = when (checkedId) {
                R.id.rbThemeDark -> ThemeMode.DARK
                R.id.rbThemeLight -> ThemeMode.LIGHT
                R.id.rbThemeDynamic -> ThemeMode.DYNAMIC
                else -> ThemeMode.SYSTEM
            }
            notifyOverlayRefresh()
        }

        binding.switchLongPressDrag.setOnCheckedChangeListener { _, isChecked ->
            if (!suppressListeners) prefs.longPressDragEnabled = isChecked
        }

        binding.switchAutoBoot.setOnCheckedChangeListener { _, isChecked ->
            if (!suppressListeners) prefs.autoStartOnBoot = isChecked
        }

        binding.switchLeftEdgeFeed.setOnCheckedChangeListener { _, isChecked ->
            if (!suppressListeners) {
                prefs.leftEdgeFeedEnabled = isChecked
                notifyOverlayRefresh()
            }
        }

        binding.switchBarSwipeFeed.setOnCheckedChangeListener { _, isChecked ->
            if (!suppressListeners) {
                prefs.searchBarSwipeFeedEnabled = isChecked
                notifyOverlayRefresh()
            }
        }

        binding.switchFeedNativeApp.setOnCheckedChangeListener { _, isChecked ->
            if (!suppressListeners) {
                prefs.feedLaunchNativeGoogleApp = isChecked
                notifyOverlayRefresh()
            }
        }

        binding.rgFeedBehavior.setOnCheckedChangeListener { _, checkedId ->
            if (suppressListeners) return@setOnCheckedChangeListener
            prefs.feedTriggerMode = when (checkedId) {
                R.id.rbFeedMinusOneReplace -> FeedTriggerMode.MINUS_ONE_AUTO_REPLACE
                R.id.rbFeedMinusOneEdge -> FeedTriggerMode.MINUS_ONE_EDGE_LAUNCH
                else -> FeedTriggerMode.FIRST_HOME_PAGE
            }
            notifyOverlayRefresh()
            refreshStatusUi()
        }

        binding.btnPickFeedPage.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "請先授予懸浮窗權限", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.serviceEnabled = true
            suppressListeners = true
            binding.switchMasterService.isChecked = true
            suppressListeners = false

            OverlaySearchService.start(this, OverlaySearchService.ACTION_PICK_FEED_PAGE)
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(homeIntent)
        }

        binding.btnCheckUpdate.setOnClickListener {
            checkForUpdates(autoPrompt = true)
        }

        binding.btnDownloadUpdate.setOnClickListener {
            latestRelease?.let { startDownloadAndInstall(it) }
        }

        binding.tvAboutInfo.text = buildString {
            appendLine("PixelDock Search v${BuildConfig.VERSION_NAME}")
            appendLine("開發者 / Developer：dx9901mk5 (dx9901mk5@gmail.com)")
            appendLine("免 Root、不干涉原廠啟動器，常駐 Pixel 風格 Google 搜尋列與預熱 Google Feed，依手機／摺疊／平板獨立記憶定位。採用固定簽章，後續版本可直接覆蓋升級。")
            append("Rootless Pixel-style Google Search Bar & pre-warmed Google Feed on stock launchers with per-form-factor position memory. Signed with a fixed key for seamless overwrite updates.")
        }

        binding.btnOpenGithubRepo.setOnClickListener {
            openExternalUrl(GitHubUpdateManager.REPO_URL)
        }

        binding.btnOpenGithubProfile.setOnClickListener {
            openExternalUrl("https://github.com/${GitHubUpdateManager.REPO_OWNER}")
        }
    }

    private fun checkForUpdates(autoPrompt: Boolean) {
        if (isDownloadingUpdate) return
        binding.tvUpdateStatus.text = "目前版本：v${BuildConfig.VERSION_NAME}（正在檢查 GitHub 最新版本…）"
        binding.btnCheckUpdate.isEnabled = false

        bgExecutor.execute {
            val result = GitHubUpdateManager.checkLatestRelease(BuildConfig.VERSION_NAME)
            runOnUiThread {
                binding.btnCheckUpdate.isEnabled = true
                result.onSuccess { release ->
                    if (release != null) {
                        latestRelease = release
                        binding.tvUpdateStatus.text = "發現新版本：v${release.versionName}（目前為 v${BuildConfig.VERSION_NAME}）"
                        binding.btnDownloadUpdate.visibility = View.VISIBLE
                        if (autoPrompt && !isFinishing && !isDestroyed) {
                            showUpdateDialog(release)
                        }
                    } else {
                        latestRelease = null
                        binding.btnDownloadUpdate.visibility = View.GONE
                        binding.tvUpdateStatus.text = "目前已是最新版本：v${BuildConfig.VERSION_NAME}"
                    }
                }.onFailure { err ->
                    binding.tvUpdateStatus.text = "目前版本：v${BuildConfig.VERSION_NAME}（檢查更新失敗：${err.message ?: "網路錯誤"}）"
                }
            }
        }
    }

    private fun showUpdateDialog(release: GitHubUpdateManager.ReleaseInfo) {
        val summary = release.notes.trim().take(360).ifEmpty {
            "發現新版本 v${release.versionName}，支援固定簽章直接覆蓋升級。"
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("發現新版本 v${release.versionName}")
            .setMessage(summary)
            .setPositiveButton("下載並安裝") { _, _ ->
                startDownloadAndInstall(release)
            }
            .setNeutralButton("前往 GitHub") { _, _ ->
                openExternalUrl(release.releaseUrl)
            }
            .setNegativeButton("稍後", null)
            .show()
    }

    private fun startDownloadAndInstall(release: GitHubUpdateManager.ReleaseInfo) {
        if (isDownloadingUpdate) return
        isDownloadingUpdate = true
        binding.btnCheckUpdate.isEnabled = false
        binding.btnDownloadUpdate.isEnabled = false
        binding.progressUpdateDownload.visibility = View.VISIBLE
        binding.progressUpdateDownload.isIndeterminate = true
        binding.tvDownloadProgress.visibility = View.VISIBLE
        binding.tvDownloadProgress.text = "正在準備下載 ${release.apkName}…"

        bgExecutor.execute {
            val result = GitHubUpdateManager.downloadApk(this, release) { downloaded, total, pct ->
                runOnUiThread {
                    if (pct >= 0 && total > 0) {
                        binding.progressUpdateDownload.isIndeterminate = false
                        binding.progressUpdateDownload.progress = pct
                        val dlMb = String.format(Locale.US, "%.2f", downloaded / (1024f * 1024f))
                        val totalMb = String.format(Locale.US, "%.2f", total / (1024f * 1024f))
                        binding.tvDownloadProgress.text = "下載中 $pct%（$dlMb MB / $totalMb MB）"
                    } else {
                        binding.progressUpdateDownload.isIndeterminate = true
                        val dlMb = String.format(Locale.US, "%.2f", downloaded / (1024f * 1024f))
                        binding.tvDownloadProgress.text = "下載中（已接收 $dlMb MB）"
                    }
                }
            }

            runOnUiThread {
                isDownloadingUpdate = false
                binding.btnCheckUpdate.isEnabled = true
                binding.btnDownloadUpdate.isEnabled = true

                result.onSuccess { apkFile ->
                    binding.progressUpdateDownload.isIndeterminate = false
                    binding.progressUpdateDownload.progress = 100
                    binding.tvDownloadProgress.text = "下載完成，正在啟動安裝程式…"
                    val started = GitHubUpdateManager.installApk(this, apkFile)
                    if (!started) {
                        pendingInstallApk = apkFile
                        Toast.makeText(
                            this,
                            "請允許此應用程式安裝未知應用程式，返回後將自動繼續安裝",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }.onFailure { err ->
                    binding.progressUpdateDownload.visibility = View.GONE
                    binding.tvDownloadProgress.text = "下載失敗：${err.message ?: "請稍後再試"}"
                }
            }
        }
    }

    private fun openExternalUrl(url: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            Toast.makeText(this, "無法開啟連結：$url", Toast.LENGTH_SHORT).show()
        }
    }

    private fun syncControlsFromPrefs() {
        suppressListeners = true
        binding.switchMasterService.isChecked = prefs.serviceEnabled && OverlaySearchService.isRunning

        when (prefs.detectionMode) {
            DetectionMode.AUTO_SHIZUKU -> binding.rgDetectionMode.check(R.id.rbAutoShizuku)
            DetectionMode.USAGE_STATS -> binding.rgDetectionMode.check(R.id.rbUsageStats)
            DetectionMode.ACCESSIBILITY -> binding.rgDetectionMode.check(R.id.rbAccessibility)
        }

        when (prefs.themeMode) {
            ThemeMode.SYSTEM -> binding.rgThemeMode.check(R.id.rbThemeSystem)
            ThemeMode.DARK -> binding.rgThemeMode.check(R.id.rbThemeDark)
            ThemeMode.LIGHT -> binding.rgThemeMode.check(R.id.rbThemeLight)
            ThemeMode.DYNAMIC -> binding.rgThemeMode.check(R.id.rbThemeDynamic)
        }

        binding.switchLongPressDrag.isChecked = prefs.longPressDragEnabled
        binding.switchAutoBoot.isChecked = prefs.autoStartOnBoot
        binding.switchLeftEdgeFeed.isChecked = prefs.leftEdgeFeedEnabled
        binding.switchBarSwipeFeed.isChecked = prefs.searchBarSwipeFeedEnabled
        binding.switchFeedNativeApp.isChecked = prefs.feedLaunchNativeGoogleApp
        when (prefs.feedTriggerMode) {
            FeedTriggerMode.FIRST_HOME_PAGE -> binding.rgFeedBehavior.check(R.id.rbFeedFirstPage)
            FeedTriggerMode.MINUS_ONE_AUTO_REPLACE -> binding.rgFeedBehavior.check(R.id.rbFeedMinusOneReplace)
            FeedTriggerMode.MINUS_ONE_EDGE_LAUNCH -> binding.rgFeedBehavior.check(R.id.rbFeedMinusOneEdge)
        }
        suppressListeners = false
    }

    private fun refreshStatusUi() {
        val snapshot = DisplayProfile.fromContext(this)
        val hasOverlay = Settings.canDrawOverlays(this)
        val shizukuStatus = when {
            !shizukuManager.isAvailable() -> "未啟動"
            shizukuManager.hasPermission() -> "已授權"
            else -> "未授權"
        }
        val defaultLauncher = detector.getDefaultLauncherPackage() ?: "未偵測到"

        binding.tvStatusSummary.text = buildString {
            appendLine("• 服務狀態：${if (OverlaySearchService.isRunning) "運作中" else "已停止"}")
            appendLine("• 懸浮窗權限：${if (hasOverlay) "已授予" else "尚未授予"}")
            appendLine("• Shizuku 狀態：$shizukuStatus")
            appendLine("• 原生 Pixel Feed：${NativeGoogleFeedBridge.statusLabel()}")
            appendLine("• 偵測引擎：${detector.activeEngineLabel()}")
            appendLine("• 原廠桌面：$defaultLauncher")
            append("• 目前型態：${snapshot.profile.displayNameZh} (${snapshot.widthDp}x${snapshot.heightDp} dp)")
        }

        binding.tvFeedTriggerSummary.text = buildString {
            val firstSig = prefs.customFirstPageSignature.ifBlank { "未標記" }
            val minusSig = prefs.customMinusOneSignature.ifBlank {
                prefs.customMinusOnePackage.ifBlank { "自動偵測" }
            }
            val modeText = when (prefs.feedTriggerMode) {
                FeedTriggerMode.FIRST_HOME_PAGE -> "第一首頁左滑啟動"
                FeedTriggerMode.MINUS_ONE_AUTO_REPLACE -> "原廠負一屏直接覆蓋成 Feed"
                FeedTriggerMode.MINUS_ONE_EDGE_LAUNCH -> "原廠負一屏左側再滑出 Feed"
            }
            append("模式：$modeText\n第一首頁：$firstSig ｜ 負一屏（自動隱藏搜尋列）：$minusSig")
        }

        refreshProfilesOverview(snapshot.profile)
    }

    private fun refreshProfilesOverview(activeProfile: DisplayProfile) {
        val text = DisplayProfile.entries.joinToString("\n") { profile ->
            val cfg = prefs.getLayout(profile)
            val marker = if (profile == activeProfile) "▶ " else "   "
            val customTag = if (prefs.hasCustomLayout(profile)) " [已自訂]" else " [預設]"
            "$marker${profile.displayNameZh}$customTag\n    Y=${cfg.bottomMarginDp}dp, X=${cfg.xOffsetDp}dp, 寬=${cfg.widthPercent}%, 高=${cfg.heightDp}dp"
        }
        binding.tvProfilesOverview.text = text
    }

    private fun notifyOverlayRefresh() {
        if (OverlaySearchService.isRunning) {
            OverlaySearchService.start(this, OverlaySearchService.ACTION_REFRESH_LAYOUT)
        }
    }
}
