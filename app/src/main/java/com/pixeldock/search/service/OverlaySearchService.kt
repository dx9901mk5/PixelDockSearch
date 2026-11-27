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
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
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
import com.pixeldock.search.detector.LauncherGestureBus
import com.pixeldock.search.launcher.GoogleIntentLauncher
import com.pixeldock.search.model.DisplayProfile
import com.pixeldock.search.shizuku.ShizukuManager
import com.pixeldock.search.ui.MainActivity
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.roundToInt

class OverlaySearchService : Service(), LauncherGestureBus.Listener {

    private lateinit var prefs: PrefsRepository
    private lateinit var shizukuManager: ShizukuManager
    private lateinit var detector: LauncherDetector
    private lateinit var windowManager: WindowManager
    private lateinit var displayManager: DisplayManager
    private lateinit var keyguardManager: KeyguardManager
    private lateinit var powerManager: PowerManager
    private lateinit var feedController: GoogleFeedOverlayController

    private val mainHandler = Handler(Looper.getMainLooper())
    private val bgExecutor = Executors.newSingleThreadExecutor()
    private val drawerInterpolator = DecelerateInterpolator(1.6f)

    private var overlayRoot: View? = null
    private var pillContainer: LinearLayout? = null
    private var micButton: ImageView? = null
    private var lensButton: ImageView? = null
    private var calibrationBanner: LinearLayout? = null
    private var calibrationLabel: TextView? = null
    private var btnScaleMinus: TextView? = null
    private var btnScalePlus: TextView? = null
    private var btnCalibrationCenter: TextView? = null
    private var btnCalibrationReset: TextView? = null
    private var calibrationDoneBtn: TextView? = null
    private var handleResizeLeft: TextView? = null
    private var handleResizeRight: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var leftEdgeView: View? = null
    private var leftEdgeParams: WindowManager.LayoutParams? = null

    private var isOverlayAttached = false
    private var isOverlayVisible = false
    private var isCalibrationMode = false
    private var isScreenInteractive = true
    private var isHiddenByDrawer = false
    private var drawerScrollOffsetPx = 0f
    private var currentSnapshot: DisplayProfile.DisplayMetricsSnapshot? = null

    private val pollInFlight = AtomicBoolean(false)

    private val drawerSettleRunnable = Runnable {
        if (!isHiddenByDrawer && drawerScrollOffsetPx > 0f) {
            drawerScrollOffsetPx = 0f
            overlayRoot?.animate()
                ?.translationY(0f)
                ?.alpha(1f)
                ?.setDuration(180L)
                ?.setInterpolator(drawerInterpolator)
                ?.start()
        }
    }

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
                    val minusOne = try {
                        prefs.leftEdgeFeedEnabled && detector.isMinusOneScreenActive()
                    } catch (_: Throwable) {
                        false
                    }
                    val show = if (minusOne) {
                        false
                    } else {
                        try {
                            detector.isHomeScreenActive()
                        } catch (_: Throwable) {
                            false
                        }
                    }
                    pollInFlight.set(false)

                    mainHandler.post {
                        if (minusOne && !feedController.isOverlayShowing()) {
                            feedController.slideInFromLeft(returnHomeOnClose = true)
                        }
                        if (!isHiddenByDrawer) {
                            setOverlayVisibility(show)
                        } else if (!show) {
                            // Left launcher completely
                            isHiddenByDrawer = false
                            drawerScrollOffsetPx = 0f
                            setOverlayVisibility(false)
                        }
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
                    isHiddenByDrawer = false
                    drawerScrollOffsetPx = 0f
                    setOverlayVisibility(false)
                    feedController.dismiss(returnHome = false)
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
        feedController = GoogleFeedOverlayController(this, windowManager)

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        shizukuManager.start()
        displayManager.registerDisplayListener(displayListener, mainHandler)
        LauncherGestureBus.register(this)

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        if (Settings.canDrawOverlays(this)) {
            createAndAttachOverlay()
            if (prefs.leftEdgeFeedEnabled) {
                feedController.prewarm()
            }
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
                isHiddenByDrawer = false
                drawerScrollOffsetPx = 0f
                applyLayoutAndAppearance()
                if (isCalibrationMode) setOverlayVisibility(true)
            }
            ACTION_ENTER_CALIBRATION -> {
                isCalibrationMode = true
                isHiddenByDrawer = false
                drawerScrollOffsetPx = 0f
                applyLayoutAndAppearance()
                setOverlayVisibility(true)
            }
            ACTION_REFRESH_LAYOUT -> {
                refreshDisplayProfileAndLayout()
                if (prefs.leftEdgeFeedEnabled) {
                    feedController.prewarm()
                }
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
        LauncherGestureBus.unregister(this)
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Throwable) {
        }
        try {
            displayManager.unregisterDisplayListener(displayListener)
        } catch (_: Throwable) {
        }
        shizukuManager.stop()
        feedController.destroy()
        detachOverlay()
        bgExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // LauncherGestureBus.Listener
    override fun onVerticalScroll(deltaY: Int) {
        if (isCalibrationMode || !isOverlayAttached || !isOverlayVisible || isHiddenByDrawer) return
        val root = overlayRoot ?: return
        val snapshot = currentSnapshot ?: DisplayProfile.fromContext(this)
        val slideOutDistance = (140f * snapshot.density).coerceAtLeast(180f)

        mainHandler.removeCallbacks(drawerSettleRunnable)
        drawerScrollOffsetPx = (drawerScrollOffsetPx + deltaY * 1.15f).coerceAtLeast(0f)

        root.animate().cancel()
        root.translationY = -drawerScrollOffsetPx
        root.alpha = (1f - drawerScrollOffsetPx / slideOutDistance).coerceIn(0f, 1f)

        if (drawerScrollOffsetPx >= slideOutDistance * 0.42f) {
            isHiddenByDrawer = true
            animateSlideOutUpward(durationMs = 150L)
        } else {
            mainHandler.postDelayed(drawerSettleRunnable, 220L)
        }
    }

    override fun onAppDrawerStateChanged(isOpen: Boolean) {
        if (isCalibrationMode || !isOverlayAttached) return
        mainHandler.removeCallbacks(drawerSettleRunnable)
        if (isOpen) {
            isHiddenByDrawer = true
            animateSlideOutUpward(durationMs = 150L)
        } else {
            isHiddenByDrawer = false
            drawerScrollOffsetPx = 0f
            setOverlayVisibility(true)
        }
    }

    override fun onMinusOneScreenDetected(packageName: String) {
        if (isCalibrationMode || !prefs.leftEdgeFeedEnabled) return
        setOverlayVisibility(false)
        feedController.slideInFromLeft(returnHomeOnClose = true)
    }

    private fun animateSlideOutUpward(durationMs: Long = 180L) {
        val root = overlayRoot ?: return
        val snapshot = currentSnapshot ?: DisplayProfile.fromContext(this)
        val targetY = -(snapshot.heightPx * 0.35f).coerceAtLeast(240f * snapshot.density)
        root.animate().cancel()
        root.animate()
            .translationY(targetY)
            .alpha(0f)
            .setDuration(durationMs)
            .setInterpolator(drawerInterpolator)
            .withEndAction {
                isOverlayVisible = false
                root.visibility = View.GONE
                root.translationY = 0f
                root.alpha = 1f
                leftEdgeView?.visibility = View.GONE
            }
            .start()
    }

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
        btnScaleMinus = view.findViewById(R.id.btnScaleMinus)
        btnScalePlus = view.findViewById(R.id.btnScalePlus)
        btnCalibrationCenter = view.findViewById(R.id.btnCalibrationCenter)
        btnCalibrationReset = view.findViewById(R.id.btnCalibrationReset)
        calibrationDoneBtn = view.findViewById(R.id.btnCalibrationDone)
        handleResizeLeft = view.findViewById(R.id.handleResizeLeft)
        handleResizeRight = view.findViewById(R.id.handleResizeRight)

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
        var draggingFeed = false
        var velocityTracker: VelocityTracker? = null
        val dragStartThreshold = 14f * density

        edge.setOnTouchListener { v, event ->
            val snap = currentSnapshot ?: DisplayProfile.fromContext(this)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    draggingFeed = false
                    velocityTracker?.recycle()
                    velocityTracker = VelocityTracker.obtain().apply { addMovement(event) }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    velocityTracker?.addMovement(event)
                    val dx = event.rawX - downX
                    if (!draggingFeed && dx > dragStartThreshold) {
                        draggingFeed = true
                        v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        feedController.beginInteractiveDrag(snap.widthPx)
                    }
                    if (draggingFeed) {
                        feedController.updateInteractiveDrag(event.rawX, snap.widthPx)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    velocityTracker?.addMovement(event)
                    velocityTracker?.computeCurrentVelocity(1000)
                    val vx = velocityTracker?.xVelocity ?: 0f
                    velocityTracker?.recycle()
                    velocityTracker = null

                    if (draggingFeed) {
                        feedController.endInteractiveDrag(event.rawX, vx, snap.widthPx)
                    }
                    draggingFeed = false
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
        var isSwipingUp = false
        var longPressTriggered = false
        var pillVelocityTracker: VelocityTracker? = null

        val longPressRunnable = Runnable {
            if (prefs.longPressDragEnabled && !isCalibrationMode) {
                longPressTriggered = true
                isCalibrationMode = true
                applyLayoutAndAppearance()
                setOverlayVisibility(true)
            }
        }

        val scaleDetector = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    if (!isCalibrationMode) return false
                    val snapshot = currentSnapshot ?: DisplayProfile.fromContext(this@OverlaySearchService)
                    val cfg = prefs.getLayout(snapshot.profile)
                    val factor = detector.scaleFactor
                    val newWidth = (cfg.widthPercent * factor).roundToInt().coerceIn(25, 98)
                    val newHeight = (cfg.heightDp * (1f + (factor - 1f) * 0.5f)).roundToInt().coerceIn(36, 68)
                    if (newWidth != cfg.widthPercent || newHeight != cfg.heightDp) {
                        val updated = cfg.copy(widthPercent = newWidth, heightDp = newHeight)
                        prefs.saveLayout(snapshot.profile, updated)
                        applyWindowLayout(snapshot, updated)
                        applyPillThemeAndShape(snapshot, updated)
                        updateCalibrationLabel(snapshot.profile, updated)
                    }
                    return true
                }
            }
        )

        val pill = pillContainer ?: return
        pill.setOnTouchListener { _, event ->
            val snapshot = currentSnapshot ?: DisplayProfile.fromContext(this)
            val density = snapshot.density

            if (isCalibrationMode) {
                scaleDetector.onTouchEvent(event)
                if (scaleDetector.isInProgress) {
                    mainHandler.removeCallbacks(longPressRunnable)
                    return@setOnTouchListener true
                }
            }

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    val cfg = prefs.getLayout(snapshot.profile)
                    startBottomDp = cfg.bottomMarginDp
                    startXOffsetDp = cfg.xOffsetDp
                    isDragging = false
                    isSwipingUp = false
                    longPressTriggered = false
                    pillVelocityTracker?.recycle()
                    pillVelocityTracker = VelocityTracker.obtain().apply { addMovement(event) }
                    if (prefs.longPressDragEnabled && !isCalibrationMode) {
                        mainHandler.postDelayed(longPressRunnable, 480L)
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    pillVelocityTracker?.addMovement(event)
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!isDragging && !isSwipingUp && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        mainHandler.removeCallbacks(longPressRunnable)
                        if (isCalibrationMode) {
                            isDragging = true
                        } else if (dy < -touchSlop && abs(dy) > abs(dx) * 1.2f) {
                            isSwipingUp = true
                        }
                    }
                    if (isCalibrationMode && isDragging) {
                        val deltaXdp = (dx / density).roundToInt()
                        val deltaYdp = (-dy / density).roundToInt()
                        var newX = (startXOffsetDp + deltaXdp).coerceIn(-350, 350)
                        if (abs(newX) <= 8) newX = 0
                        val newBottom = (startBottomDp + deltaYdp).coerceIn(0, 380)

                        val existing = prefs.getLayout(snapshot.profile)
                        val updated = existing.copy(bottomMarginDp = newBottom, xOffsetDp = newX)
                        prefs.saveLayout(snapshot.profile, updated)
                        applyWindowLayout(snapshot, updated)
                        updateCalibrationLabel(snapshot.profile, updated)
                    } else if (!isCalibrationMode && isSwipingUp) {
                        val root = overlayRoot
                        val upOffset = (-dy).coerceAtLeast(0f)
                        val fadeRange = 140f * density
                        root?.translationY = -upOffset
                        root?.alpha = (1f - upOffset / fadeRange).coerceIn(0f, 1f)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    pillVelocityTracker?.addMovement(event)
                    pillVelocityTracker?.computeCurrentVelocity(1000)
                    val vy = pillVelocityTracker?.yVelocity ?: 0f
                    pillVelocityTracker?.recycle()
                    pillVelocityTracker = null

                    if (isSwipingUp) {
                        val dy = event.rawY - downRawY
                        if (dy < -48f * density || vy < -600f) {
                            val duration = (180L * (1000f / abs(vy).coerceIn(600f, 2400f))).toLong().coerceIn(80L, 190L)
                            isHiddenByDrawer = true
                            animateSlideOutUpward(duration)
                            mainHandler.postDelayed({
                                isHiddenByDrawer = false
                            }, 1200L)
                        } else {
                            overlayRoot?.animate()?.translationY(0f)?.alpha(1f)?.setDuration(160L)?.start()
                        }
                    } else if (!isDragging && !longPressTriggered) {
                        if (!isCalibrationMode) {
                            GoogleIntentLauncher.launchGoogleSearch(this)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    pillVelocityTracker?.recycle()
                    pillVelocityTracker = null
                    if (isSwipingUp) {
                        overlayRoot?.animate()?.translationY(0f)?.alpha(1f)?.setDuration(160L)?.start()
                    }
                    true
                }
                else -> false
            }
        }

        setupResizeHandle(handleResizeLeft, isLeft = true)
        setupResizeHandle(handleResizeRight, isLeft = false)

        btnScaleMinus?.setOnClickListener { adjustScale(-4, -2) }
        btnScalePlus?.setOnClickListener { adjustScale(4, 2) }

        btnCalibrationCenter?.setOnClickListener {
            val snapshot = currentSnapshot ?: DisplayProfile.fromContext(this)
            val updated = prefs.getLayout(snapshot.profile).copy(xOffsetDp = 0)
            prefs.saveLayout(snapshot.profile, updated)
            applyWindowLayout(snapshot, updated)
            updateCalibrationLabel(snapshot.profile, updated)
        }

        btnCalibrationReset?.setOnClickListener {
            val snapshot = currentSnapshot ?: DisplayProfile.fromContext(this)
            prefs.resetLayout(snapshot.profile)
            applyLayoutAndAppearance()
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

    @SuppressLint("ClickableViewAccessibility")
    private fun setupResizeHandle(handle: TextView?, isLeft: Boolean) {
        handle ?: return
        var downX = 0f
        var startWidthPct = 80
        handle.setOnTouchListener { _, event ->
            if (!isCalibrationMode) return@setOnTouchListener false
            val snapshot = currentSnapshot ?: DisplayProfile.fromContext(this)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    startWidthPct = prefs.getLayout(snapshot.profile).widthPercent
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val signedDx = if (isLeft) -dx else dx
                    val deltaPct = ((signedDx * 2f / snapshot.widthPx) * 100f).roundToInt()
                    val newWidth = (startWidthPct + deltaPct).coerceIn(25, 98)
                    val cfg = prefs.getLayout(snapshot.profile)
                    if (newWidth != cfg.widthPercent) {
                        val updated = cfg.copy(widthPercent = newWidth)
                        prefs.saveLayout(snapshot.profile, updated)
                        applyWindowLayout(snapshot, updated)
                        updateCalibrationLabel(snapshot.profile, updated)
                    }
                    true
                }
                else -> true
            }
        }
    }

    private fun adjustScale(deltaWidthPct: Int, deltaHeightDp: Int) {
        val snapshot = currentSnapshot ?: DisplayProfile.fromContext(this)
        val cfg = prefs.getLayout(snapshot.profile)
        val updated = cfg.copy(
            widthPercent = (cfg.widthPercent + deltaWidthPct).coerceIn(25, 98),
            heightDp = (cfg.heightDp + deltaHeightDp).coerceIn(36, 68)
        )
        prefs.saveLayout(snapshot.profile, updated)
        applyWindowLayout(snapshot, updated)
        applyPillThemeAndShape(snapshot, updated)
        updateCalibrationLabel(snapshot.profile, updated)
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
        handleResizeLeft?.visibility = if (isCalibrationMode) View.VISIBLE else View.GONE
        handleResizeRight?.visibility = if (isCalibrationMode) View.VISIBLE else View.GONE
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
        calibrationLabel?.text = "${profile.displayNameZh} ${config.widthPercent}%×${config.heightDp}dp"
    }

    private fun setOverlayVisibility(visible: Boolean) {
        val view = overlayRoot ?: return
        if (!isOverlayAttached) return
        leftEdgeView?.visibility = if (visible && prefs.leftEdgeFeedEnabled) View.VISIBLE else View.GONE
        if (isOverlayVisible == visible) return
        isOverlayVisible = visible
        if (visible) {
            view.animate().cancel()
            view.translationY = 0f
            view.alpha = 1f
            view.visibility = View.VISIBLE
        } else {
            view.visibility = View.GONE
        }
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
