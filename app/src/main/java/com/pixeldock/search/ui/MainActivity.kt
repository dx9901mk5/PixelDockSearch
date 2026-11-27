package com.pixeldock.search.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.pixeldock.search.BuildConfig
import com.pixeldock.search.R
import com.pixeldock.search.data.DetectionMode
import com.pixeldock.search.data.PrefsRepository
import com.pixeldock.search.data.ProfileLayoutConfig
import com.pixeldock.search.data.ThemeMode
import com.pixeldock.search.databinding.ActivityMainBinding
import com.pixeldock.search.detector.LauncherDetector
import com.pixeldock.search.model.DisplayProfile
import com.pixeldock.search.service.OverlaySearchService
import com.pixeldock.search.shizuku.ShizukuManager

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: PrefsRepository
    private lateinit var shizukuManager: ShizukuManager
    private lateinit var detector: LauncherDetector

    private var suppressListeners = false

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
        setupListeners()
        syncControlsFromPrefs()
        refreshStatusUi()
    }

    override fun onResume() {
        super.onResume()
        syncControlsFromPrefs()
        refreshStatusUi()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        syncControlsFromPrefs()
        refreshStatusUi()
    }

    override fun onDestroy() {
        shizukuManager.stop()
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
            syncControlsFromPrefs()
            notifyOverlayRefresh()
            refreshStatusUi()
            Toast.makeText(this, "已重設 ${snapshot.profile.displayNameZh}", Toast.LENGTH_SHORT).show()
        }

        binding.btnCenterX.setOnClickListener {
            binding.sliderXOffset.value = 0f
        }

        val sliderChange: () -> Unit = {
            if (!suppressListeners) {
                val snapshot = DisplayProfile.fromContext(this)
                val updated = ProfileLayoutConfig(
                    bottomMarginDp = binding.sliderBottomMargin.value.toInt(),
                    xOffsetDp = binding.sliderXOffset.value.toInt(),
                    widthPercent = binding.sliderWidthPercent.value.toInt(),
                    heightDp = binding.sliderHeightDp.value.toInt()
                )
                prefs.saveLayout(snapshot.profile, updated)
                prefs.bgAlpha = binding.sliderAlpha.value.toInt()
                updateSliderLabels(updated, prefs.bgAlpha)
                notifyOverlayRefresh()
                refreshProfilesOverview(snapshot.profile)
            }
        }

        binding.sliderBottomMargin.addOnChangeListener { _, _, _ -> sliderChange() }
        binding.sliderXOffset.addOnChangeListener { _, _, _ -> sliderChange() }
        binding.sliderWidthPercent.addOnChangeListener { _, _, _ -> sliderChange() }
        binding.sliderHeightDp.addOnChangeListener { _, _, _ -> sliderChange() }
        binding.sliderAlpha.addOnChangeListener { _, _, _ -> sliderChange() }

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

        binding.tvAboutInfo.text = buildString {
            appendLine("PixelDock Search v${BuildConfig.VERSION_NAME}")
            appendLine("開發者 / Developer：dx9901mk5 (dx9901mk5@gmail.com)")
            appendLine("免 Root、不干涉原廠啟動器，常駐 Pixel 風格 Google 搜尋列與桌面左側 Google 探索手勢，依手機／摺疊／平板獨立記憶定位。採用固定簽章，後續版本可直接覆蓋升級。")
            append("Rootless Pixel-style Google Search Bar & Left-Edge Discover swipe on stock launchers with per-form-factor position memory. Signed with a fixed key for seamless overwrite updates.")
        }

        binding.btnOpenGithubRepo.setOnClickListener {
            openExternalUrl("https://github.com/dx9901mk5/PixelDockSearch")
        }

        binding.btnOpenGithubProfile.setOnClickListener {
            openExternalUrl("https://github.com/dx9901mk5")
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
        val snapshot = DisplayProfile.fromContext(this)
        val layout = prefs.getLayout(snapshot.profile)

        binding.switchMasterService.isChecked = prefs.serviceEnabled && OverlaySearchService.isRunning
        binding.tvActiveProfileTitle.text = "定位微調：${snapshot.profile.displayNameZh}"

        binding.sliderBottomMargin.value = layout.bottomMarginDp.toFloat().coerceIn(0f, 300f)
        binding.sliderXOffset.value = layout.xOffsetDp.toFloat().coerceIn(-200f, 200f)
        binding.sliderWidthPercent.value = layout.widthPercent.toFloat().coerceIn(25f, 98f)
        binding.sliderHeightDp.value = layout.heightDp.toFloat().coerceIn(36f, 64f)
        binding.sliderAlpha.value = prefs.bgAlpha.toFloat().coerceIn(20f, 100f)
        updateSliderLabels(layout, prefs.bgAlpha)

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
        suppressListeners = false
    }

    private fun updateSliderLabels(layout: ProfileLayoutConfig, alpha: Int) {
        binding.tvBottomMarginLabel.text = "底部距離 Y：${layout.bottomMarginDp} dp"
        binding.tvXOffsetLabel.text = "左右偏移 X：${layout.xOffsetDp} dp"
        binding.tvWidthLabel.text = "寬度比例：${layout.widthPercent}%"
        binding.tvHeightLabel.text = "高度：${layout.heightDp} dp"
        binding.tvAlphaLabel.text = "背景不透明度：$alpha%"
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
            appendLine("• 偵測引擎：${detector.activeEngineLabel()}")
            appendLine("• 原廠桌面：$defaultLauncher")
            append("• 目前型態：${snapshot.profile.displayNameZh} (${snapshot.widthDp}x${snapshot.heightDp} dp)")
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
