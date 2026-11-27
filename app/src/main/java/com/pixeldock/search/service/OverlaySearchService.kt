package com.pixeldock.search.service

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.pixeldock.search.R
import com.pixeldock.search.data.PrefsRepository
import com.pixeldock.search.data.ProfileLayoutConfig
import com.pixeldock.search.data.ThemeMode
import com.pixeldock.search.detector.LauncherDetector
import com.pixeldock.search.launcher.GoogleIntentLauncher
import com.pixeldock.search.model.DisplayProfile
import com.pixeldock.search.shizuku.ShizukuManager
import com.pixeldock.search.ui.MainActivity
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.roundToInt

class OverlaySearchService : Service() {

    private lateinit var prefs: PrefsRepository
    private lateinit var shizukuManager: ShizukuManager
    private lateinit var detector: LauncherDetector
    private lateinit var windowManager: WindowManager
    private lateinit var displayManager: DisplayManager
    private lateinit var keyguardManager: KeyguardManager
    private lateinit var powerManager: PowerManager

    private val mainHandler = Handler(Looper.getMainLooper())
    private val bgExecutor = Executors.newSingleThreadExecutor()

    private var overlayRoot: View? = null
    private var pillContainer: LinearLayout? = null
    private var micButton: ImageView? = null
    private var lensButton: ImageView? = null
    private var calibrationBanner: LinearLayout? = null
    private var calibrationLabel: TextView? = null
    private var calibrationDoneBtn: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var leftEdgeView: View? = null
    private var leftEdgeParams: WindowManager.LayoutParams? = null

    private var isOverlayAttached = false
    private var isOverlayVisible = false
    private var isCalibrationMode = false
    private var isScreenInteractive = true
    private var currentSnapshot: DisplayProfile.DisplayMetricsSnapshot? = null

    private val pollInFlight = AtomicBoolean(false)

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            if (isCalibrationMode) {
                mainHandler.post { setOverlayVisibility(true) }
                mainHandler.postDelayed(this, POLL_INTERVAL_MS)
                return
            }
            if (!isScreenInteractive || keyguardManager.isKeyguardLocked) {
                mainHandler.post { setOverlayVisibility(false) }
                mainHandler.postDelayed(this, IDLE_POLL_INTERVAL_MS)
                return
            }
            if (pollInFlight.compareAndSet(false, true)) {
                bgExecutor.execute {
                    val show = try {
                        detector.isHomeScreenActive()
                    } catch (_: Throwable) {
                        false
                    } finally {
                        pollInFlight.set(false)
                    }
                    mainHandler.post {
                        setOverlayVisibility(show)
                    }
                }
            }
            mainHandler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == android.view.Display.DEFAULT_DISPLAY) {
                mainHandler.post { refreshDisplayProfileAndLayout() }
            }
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    isScreenInteractive = false
                    setOverlayVisibility(false)
                }
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    isScreenInteractive = powerManager.isInteractive
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        prefs = PrefsRepository(this)
        shizukuManager = ShizukuManager(this)
        detector = LauncherDetector(this, prefs, shizukuManager)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        isScreenInteractive = powerManager.isInteractive

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        shizukuManager.start()
        displayManager.registerDisplayListener(displayListener, mainHandler)

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        if (Settings.canDrawOverlays(this)) {
            createAndAttachOverlay()
        }
        mainHandler.post(pollRunnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                prefs.serviceEnabled = false
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_CALIBRATION -> {
                isCalibrationMode = !isCalibrationMode
                applyLayoutAndAppearance()
                if (isCalibrationMode) setOverlayVisibility(true)
            }
            ACTION_ENTER_CALIBRATION -> {
                isCalibrationMode = true
                applyLayoutAndAppearance()
                setOverlayVisibility(true)
            }
            ACTION_REFRESH_LAYOUT -> {
                refreshDisplayProfileAndLayout()
            }
        }
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshDisplayProfileAndLayout()
    }

    override fun onDestroy() {
        isRunning = false
        mainHandler.removeCallbacksAndMessages(null)
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Throwable) {
        }
        try {
            displayManager.unregisterDisplayListener(displayListener)
        } catch (_: Throwable) {
        }
        shizukuManager.stop()
        detachOverlay()
        bgExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    private fun createAndAttachOverlay() {
        if (isOverlayAttached || !Settings.canDrawOverlays(this)) return

        val view = LayoutInflater.from(this).inflate(R.layout.overlay_search_bar, null)
        overlayRoot = view
        pillContainer = view.findViewById(R.id.searchPillContainer)
        micButton = view.findViewById(R.id.btnMic)
        lensButton = view.findViewById(R.id.btnLens)
        calibrationBanner = view.findViewById(R.id.calibrationBanner)
        calibrationLabel = view.findViewById(R.id.tvCalibrationInfo)
        calibrationDoneBtn = view.findViewById(R.id.btnCalibrationDone)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        }
        layoutParams = params

        setupInteractions()
        view.visibility = View.GONE

        try {
            windowManager.addView(view, params)
            isOverlayAttached = true
            isOverlayVisible = false
            attachLeftEdgeView()
            refreshDisplayProfileAndLayout()
        } catch (_: Throwable) {
            isOverlayAttached = false
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachLeftEdgeView() {
        if (leftEdgeView != null) return
        val snapshot = currentSnapshot ?: DisplayProfile.fromContext(this)
        val density = snapshot.density
        val edge = View(this)
        val params = WindowManager.LayoutParams(
            (18 * density).roundToInt(),
            (snapshot.heightPx * (prefs.leftEdgeHeightPercent / 100f)).roundToInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
        }

        var downX = 0f
        var downY = 0f
        var triggered = false
        val triggerThresholdPx = 44f * density

        edge.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    triggered = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!triggered) {
                        val dx = event.rawX - downX
                        val dy = event.rawY - downY
                        if (dx >= triggerThresholdPx && dx > abs(dy) * 1.25f) {
                            triggered = true
                            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            GoogleIntentLauncher.launchGoogleFeed(this)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!triggered) {
                        val dx = event.rawX - downX
                        val dy = event.rawY - downY
                        if (dx >= triggerThresholdPx && dx > abs(dy) * 1.25f) {
                            triggered = true
                            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            GoogleIntentLauncher.launchGoogleFeed(this)
                        }
                    }
                    true
                }
                else -> false
            }
        }

        edge.visibility = View.GONE
        try {
            windowManager.addView(edge, params)
            leftEdgeView = edge
            leftEdgeParams = params
        } catch (_: Throwable) {
        }
    }

    private fun detachOverlay() {
        val view = overlayRoot
        if (isOverlayAttached && view != null) {
            try {
                windowManager.removeViewImmediate(view)
            } catch (_: Throwable) {
            }
        }
        leftEdgeView?.let { edge ->
            try {
                windowManager.removeViewImmediate(edge)
            } catch (_: Throwable) {
            }
        }
        leftEdgeView = null
        leftEdgeParams = null
        overlayRoot = null
        isOverlayAttached = false
        isOverlayVisible = false
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupInteractions() {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var downRawX = 0f
        var downRawY = 0f
        var startBottomDp = 0
        var startXOffsetDp = 0
        var isDragging = false
        var longPressTriggered = false

        val longPressRunnable = Runnable {
            if (prefs.longPressDragEnabled && !isCalibrationMode) {
                longPressTriggered = true
                isCalibrationMode = true
                applyLayoutAndAppearance()
                setOverlayVisibility(true)
            }
        }

        val pill = pillContainer ?: return
        pill.setOnTouchListener { _, event ->
            val snapshot = currentSnapshot ?: DisplayProfile.fromContext(this)
            val density = snapshot.density

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    val cfg = prefs.getLayout(snapshot.profile)
                    startBottomDp = cfg.bottomMarginDp
                    startXOffsetDp = cfg.xOffsetDp
                    isDragging = false
                    longPressTriggered = false
                    if (prefs.longPressDragEnabled && !isCalibrationMode) {
                        mainHandler.postDelayed(longPressRunnable, 480L)
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!isDragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        if (isCalibrationMode) {
                            isDragging = true
                        } else {
                            mainHandler.removeCallbacks(longPressRunnable)
                        }
                    }
                    if (isCalibrationMode && isDragging) {
                        val deltaXdp = (dx / density).roundToInt()
                        val deltaYdp = (-dy / density).roundToInt()
                        var newX = (startXOffsetDp + deltaXdp).coerceIn(-350, 350)
                        // Magnetic snap to center within +-8dp
                        if (abs(newX) <= 8) newX = 0
                        val newBottom = (startBottomDp + deltaYdp).coerceIn(0, 380)

                        val existing = prefs.getLayout(snapshot.profile)
                        val updated = existing.copy(bottomMarginDp = newBottom, xOffsetDp = newX)
                        prefs.saveLayout(snapshot.profile, updated)
                        applyWindowLayout(snapshot, updated)
                        updateCalibrationLabel(snapshot.profile, updated)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    if (!isDragging && !longPressTriggered) {
                        if (isCalibrationMode) {
                            // Tap outside buttons during calibration shows toast/keeps mode
                        } else {
                            GoogleIntentLauncher.launchGoogleSearch(this)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    true
                }
                else -> false
            }
        }

        micButton?.setOnClickListener {
            if (!isCalibrationMode) {
                GoogleIntentLauncher.launchVoiceSearch(this)
            }
        }

        lensButton?.setOnClickListener {
            if (!isCalibrationMode) {
                GoogleIntentLauncher.launchGoogleLens(this)
            }
        }

        calibrationDoneBtn?.setOnClickListener {
            isCalibrationMode = false
            applyLayoutAndAppearance()
        }
    }

    private fun refreshDisplayProfileAndLayout() {
        val snapshot = DisplayProfile.fromContext(this)
        currentSnapshot = snapshot
        if (!isOverlayAttached && Settings.canDrawOverlays(this)) {
            createAndAttachOverlay()
            return
        }
        applyLayoutAndAppearance()
    }

    private fun applyLayoutAndAppearance() {
        val snapshot = currentSnapshot ?: DisplayProfile.fromContext(this).also { currentSnapshot = it }
        val config = prefs.getLayout(snapshot.profile)
        applyWindowLayout(snapshot, config)
        applyPillThemeAndShape(snapshot, config)
        calibrationBanner?.visibility = if (isCalibrationMode) View.VISIBLE else View.GONE
        if (isCalibrationMode) {
            updateCalibrationLabel(snapshot.profile, config)
        }
    }

    private fun applyWindowLayout(
        snapshot: DisplayProfile.DisplayMetricsSnapshot,
        config: ProfileLayoutConfig
    ) {
        val view = overlayRoot ?: return
        val params = layoutParams ?: return
        if (!isOverlayAttached) return

        val density = snapshot.density
        val pillWidthPx = ((snapshot.widthPx * (config.widthPercent / 100f))).roundToInt()
            .coerceAtLeast((160 * density).roundToInt())
        val pillHeightPx = (config.heightDp * density).roundToInt()

        pillContainer?.layoutParams = pillContainer?.layoutParams?.apply {
            width = pillWidthPx
            height = pillHeightPx
        }

        params.width = WindowManager.LayoutParams.WRAP_CONTENT
        params.height = WindowManager.LayoutParams.WRAP_CONTENT
        params.x = (config.xOffsetDp * density).roundToInt()
        params.y = (config.bottomMarginDp * density).roundToInt()

        try {
            windowManager.updateViewLayout(view, params)
        } catch (_: Throwable) {
        }

        val edge = leftEdgeView
        val edgeParams = leftEdgeParams
        if (edge != null && edgeParams != null) {
            edgeParams.width = (18 * density).roundToInt()
            edgeParams.height = (snapshot.heightPx * (prefs.leftEdgeHeightPercent / 100f)).roundToInt()
            try {
                windowManager.updateViewLayout(edge, edgeParams)
            } catch (_: Throwable) {
            }
        }
    }

    private fun applyPillThemeAndShape(
        snapshot: DisplayProfile.DisplayMetricsSnapshot,
        config: ProfileLayoutConfig
    ) {
        val pill = pillContainer ?: return
        val density = snapshot.density
        val cornerRadius = (config.heightDp * density) / 2f

        val isNight = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

        val baseColor = when (prefs.themeMode) {
            ThemeMode.DARK -> Color.parseColor("#2A2B2F")
            ThemeMode.LIGHT -> Color.parseColor("#F1F3F4")
            ThemeMode.DYNAMIC -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val resId = if (isNight) {
                        android.R.color.system_neutral1_800
                    } else {
                        android.R.color.system_neutral1_50
                    }
                    ContextCompat.getColor(this, resId)
                } else {
                    if (isNight) Color.parseColor("#2A2B2F") else Color.parseColor("#F1F3F4")
                }
            }
            ThemeMode.SYSTEM -> if (isNight) Color.parseColor("#2A2B2F") else Color.parseColor("#F1F3F4")
        }

        val alphaInt = ((prefs.bgAlpha / 100f) * 255f).roundToInt().coerceIn(40, 255)
        val fillColor = Color.argb(
            alphaInt,
            Color.red(baseColor),
            Color.green(baseColor),
            Color.blue(baseColor)
        )

        val useLightIcons = when (prefs.themeMode) {
            ThemeMode.DARK -> true
            ThemeMode.LIGHT -> false
            ThemeMode.SYSTEM, ThemeMode.DYNAMIC -> isNight
        }
        val iconTint = if (useLightIcons) Color.parseColor("#E3E3E3") else Color.parseColor("#444746")
        micButton?.setColorFilter(iconTint)
        lensButton?.setColorFilter(iconTint)

        val drawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            this.cornerRadius = cornerRadius
            setColor(fillColor)
            if (isCalibrationMode) {
                setStroke((2 * density).roundToInt(), Color.parseColor("#4285F4"))
            } else {
                val strokeColor = if (useLightIcons) Color.argb(38, 255, 255, 255) else Color.argb(28, 0, 0, 0)
                setStroke((1 * density).roundToInt(), strokeColor)
            }
        }
        pill.background = drawable
        leftEdgeView?.setBackgroundColor(
            if (isCalibrationMode && prefs.leftEdgeFeedEnabled) Color.parseColor("#554285F4")
            else Color.TRANSPARENT
        )
    }

    private fun updateCalibrationLabel(profile: DisplayProfile, config: ProfileLayoutConfig) {
        calibrationLabel?.text = "${profile.displayNameZh}  Y:${config.bottomMarginDp}dp  X:${config.xOffsetDp}dp"
    }

    private fun setOverlayVisibility(visible: Boolean) {
        val view = overlayRoot ?: return
        if (!isOverlayAttached) return
        leftEdgeView?.visibility = if (visible && prefs.leftEdgeFeedEnabled) View.VISIBLE else View.GONE
        if (isOverlayVisible == visible) return
        isOverlayVisible = visible
        view.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "桌面 Google 搜尋列服務",
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = "維持在原廠桌面顯示 Pixel 風格搜尋列"
            setShowBadge(false)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val calibrateIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, OverlaySearchService::class.java).setAction(ACTION_TOGGLE_CALIBRATION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, OverlaySearchService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_search_notif)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("僅在原廠桌面顯示搜尋列")
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, "調整位置", calibrateIntent)
            .addAction(0, "停止", stopIntent)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "pixel_dock_overlay_channel"
        private const val NOTIFICATION_ID = 4101
        private const val POLL_INTERVAL_MS = 280L
        private const val IDLE_POLL_INTERVAL_MS = 1000L

        const val ACTION_STOP = "com.pixeldock.search.ACTION_STOP"
        const val ACTION_TOGGLE_CALIBRATION = "com.pixeldock.search.ACTION_TOGGLE_CALIBRATION"
        const val ACTION_ENTER_CALIBRATION = "com.pixeldock.search.ACTION_ENTER_CALIBRATION"
        const val ACTION_REFRESH_LAYOUT = "com.pixeldock.search.ACTION_REFRESH_LAYOUT"

        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context, action: String? = null) {
            val intent = Intent(context, OverlaySearchService::class.java).apply {
                if (action != null) this.action = action
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlaySearchService::class.java))
        }
    }
}
