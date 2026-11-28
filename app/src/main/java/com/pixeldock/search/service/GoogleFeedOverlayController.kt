package com.pixeldock.search.service

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.pixeldock.search.data.PrefsRepository
import com.pixeldock.search.launcher.GoogleIntentLauncher
import com.pixeldock.search.model.DisplayProfile
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

class GoogleFeedOverlayController(
    private val context: Context,
    private val windowManager: WindowManager,
    private val prefs: PrefsRepository
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val slideInterpolator = PathInterpolator(0.2f, 0f, 0f, 1f)

    private var rootContainer: SwipeDismissFrameLayout? = null
    private var scrimView: View? = null
    private var cardContainer: FrameLayout? = null
    private var webColumn: LinearLayout? = null
    private var snapshotImageView: ImageView? = null
    private var webView: WebView? = null
    private var progressBar: ProgressBar? = null
    private var windowParams: WindowManager.LayoutParams? = null

    private var cachedSnapshotBitmap: Bitmap? = null
    private var activeAnimator: ValueAnimator? = null
    private var currentProgress: Float = 0f
    private var isAttached = false
    private var isPrewarmed = false
    private var isShowing = false
    private var isWaitingForNativeHandoff = false
    private var returnHomeOnDismiss = false
    private var lastAutoTriggerMs = 0L

    private val handoffDismissFallback = Runnable {
        if (isWaitingForNativeHandoff) {
            finishNativeHandoffCrossFade()
        }
    }

    private inner class SwipeDismissFrameLayout(
        context: Context
    ) : FrameLayout(context) {
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var isDraggingClose = false

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                dismiss()
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            if (!isShowing) return false
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    downY = ev.rawY
                    isDraggingClose = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX
                    val dy = ev.rawY - downY
                    if (dx < -touchSlop && abs(dx) > abs(dy) * 1.35f) {
                        isDraggingClose = true
                        activeAnimator?.cancel()
                        return true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isDraggingClose = false
                }
            }
            return super.onInterceptTouchEvent(ev)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (!isDraggingClose) return super.onTouchEvent(event)
            val widthPx = DisplayProfile.fromContext(context).widthPx.coerceAtLeast(1)
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val progress = (1f + dx / widthPx.toFloat()).coerceIn(0f, 1f)
                    applyDragProgress(progress, widthPx)
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isDraggingClose = false
                    if (currentProgress <= 0.50f) {
                        animateToProgress(0f, widthPx, returnHomeAfterClose = returnHomeOnDismiss)
                    } else {
                        animateToProgress(1f, widthPx, returnHomeAfterClose = false)
                    }
                    return true
                }
            }
            return true
        }
    }

    fun getSnapshotFilePath(): String {
        val dir = context.externalCacheDir ?: context.cacheDir
        return File(dir, SNAPSHOT_FILENAME).absolutePath
    }

    fun reloadSnapshotFromDisk() {
        val file = File(getSnapshotFilePath())
        if (!file.exists() || file.length() < 1024L) return
        try {
            val opts = BitmapFactory.Options().apply {
                inSampleSize = 2
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val bmp = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return
            mainHandler.post {
                val old = cachedSnapshotBitmap
                cachedSnapshotBitmap = bmp
                snapshotImageView?.setImageBitmap(bmp)
                updateCardVisualMode()
                if (old != null && old !== bmp && !old.isRecycled) {
                    old.recycle()
                }
            }
        } catch (_: Throwable) {
        }
    }

    fun prewarm() {
        if (!Settings.canDrawOverlays(context)) return
        reloadSnapshotFromDisk()
        if (isPrewarmed) return
        mainHandler.postDelayed({
            if (!isPrewarmed && Settings.canDrawOverlays(context)) {
                ensureAttached()
                val root = rootContainer
                if (root != null && !isShowing) {
                    setWindowFlags(focusable = false, touchable = false)
                    root.visibility = View.INVISIBLE
                }
                webView?.loadUrl(FEED_URL)
                isPrewarmed = true
                mainHandler.postDelayed({
                    if (!isShowing && currentProgress <= 0.01f) {
                        rootContainer?.visibility = View.GONE
                    }
                }, 4000L)
            }
        }, 900L)
    }

    private fun updateCardVisualMode() {
        val hasSnapshot = cachedSnapshotBitmap != null && cachedSnapshotBitmap?.isRecycled == false
        val showSnapshot = prefs.feedLaunchNativeGoogleApp && hasSnapshot
        snapshotImageView?.visibility = if (showSnapshot) View.VISIBLE else View.GONE
        webColumn?.visibility = if (showSnapshot) View.GONE else View.VISIBLE
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun ensureAttached() {
        if (isAttached || !Settings.canDrawOverlays(context)) return
        val snapshot = DisplayProfile.fromContext(context)
        val density = snapshot.density
        val isNight = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

        val bgColor = if (isNight) Color.parseColor("#18191C") else Color.parseColor("#FFFFFF")
        val headerBgColor = if (isNight) Color.parseColor("#202124") else Color.parseColor("#F1F3F4")
        val textColor = if (isNight) Color.parseColor("#E8EAED") else Color.parseColor("#202124")
        val subTextColor = if (isNight) Color.parseColor("#9AA0A6") else Color.parseColor("#5F6368")

        val root = SwipeDismissFrameLayout(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            visibility = View.GONE
        }

        val scrim = View(context).apply {
            setBackgroundColor(Color.BLACK)
            alpha = 0f
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        val cardCornerPx = 18f * density
        val card = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(bgColor)
                cornerRadii = floatArrayOf(0f, 0f, cardCornerPx, cardCornerPx, cardCornerPx, cardCornerPx, 0f, 0f)
            }
            elevation = 20f * density
            translationX = -snapshot.widthPx.toFloat()
            alpha = 0.45f
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    val radius = if (currentProgress >= 0.99f) 0f else cardCornerPx
                    outline.setRoundRect(-radius.roundToInt(), 0, view.width, view.height, radius)
                }
            }
        }

        // Layer 1: Pre-warmed WebView column (used when no native snapshot exists yet or in WebView mode)
        val webCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        val topPaddingPx = getStatusBarHeightPx()
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(headerBgColor)
            setPadding(
                (16 * density).roundToInt(),
                topPaddingPx + (6 * density).roundToInt(),
                (10 * density).roundToInt(),
                (6 * density).roundToInt()
            )
        }

        val titleView = TextView(context).apply {
            text = "Google 探索"
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            paint.isFakeBoldText = true
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val refreshBtn = makeHeaderAction("重整", subTextColor, density) { webView?.reload() }
        val openAppBtn = makeHeaderAction("Google App", Color.parseColor("#4285F4"), density) {
            dismiss(returnHome = false)
            GoogleIntentLauncher.launchGoogleApp(context)
        }
        val closeBtn = makeHeaderAction("✕", textColor, density) { dismiss() }

        header.addView(titleView)
        header.addView(refreshBtn)
        header.addView(openAppBtn)
        header.addView(closeBtn)

        val bar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (2 * density).roundToInt()
            )
        }

        val wv = WebView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            setBackgroundColor(bgColor)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                cacheMode = WebSettings.LOAD_DEFAULT
                useWideViewPort = true
                loadWithOverviewMode = true
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            }
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                settings.isAlgorithmicDarkeningAllowed = isNight
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                @Suppress("DEPRECATION")
                settings.forceDark = if (isNight) {
                    WebSettings.FORCE_DARK_ON
                } else {
                    WebSettings.FORCE_DARK_OFF
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    return false
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (!isShowing && currentProgress <= 0.01f) {
                        root.visibility = View.GONE
                    }
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    bar.progress = newProgress
                    bar.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
                }
            }
        }

        webCol.addView(header)
        webCol.addView(bar)
        webCol.addView(wv)

        // Layer 2: Real cached Google App snapshot (pixel-identical to native Google App!)
        val snapIv = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            cachedSnapshotBitmap?.let { if (!it.isRecycled) setImageBitmap(it) }
            visibility = View.GONE
        }

        card.addView(webCol)
        card.addView(snapIv)

        root.addView(scrim)
        root.addView(
            card,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        try {
            windowManager.addView(root, params)
            rootContainer = root
            scrimView = scrim
            cardContainer = card
            webColumn = webCol
            snapshotImageView = snapIv
            webView = wv
            progressBar = bar
            windowParams = params
            isAttached = true
            updateCardVisualMode()
        } catch (_: Throwable) {
            isAttached = false
        }
    }

    private fun makeHeaderAction(label: String, color: Int, density: Float, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            text = label
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setPadding(
                (10 * density).roundToInt(),
                (6 * density).roundToInt(),
                (10 * density).roundToInt(),
                (6 * density).roundToInt()
            )
            setOnClickListener { onClick() }
        }
    }

    fun beginInteractiveDrag(screenWidthPx: Int) {
        if (!Settings.canDrawOverlays(context)) return
        ensureAttached()
        updateCardVisualMode()
        if (!isPrewarmed) {
            webView?.loadUrl(FEED_URL)
            isPrewarmed = true
        }
        val root = rootContainer ?: return
        activeAnimator?.cancel()
        mainHandler.removeCallbacks(handoffDismissFallback)
        isWaitingForNativeHandoff = false
        returnHomeOnDismiss = false
        isShowing = false
        setWindowFlags(focusable = false, touchable = false)
        root.alpha = 1f
        root.visibility = View.VISIBLE
        applyDragProgress(0f, screenWidthPx)
    }

    fun updateInteractiveDrag(revealPx: Float, screenWidthPx: Int) {
        if (screenWidthPx <= 0) return
        val progress = (revealPx / screenWidthPx.toFloat()).coerceIn(0f, 1f)
        applyDragProgress(progress, screenWidthPx)
    }

    /**
     * Strict 50% threshold rule:
     * - progress >= 0.50f -> finishes sliding card open, and if feedLaunchNativeGoogleApp is ON,
     *   seamlessly launches the real native Google App underneath and cross-fades straight into it!
     * - progress <  0.50f -> smoothly glides back left and hides (止煞歸位不顯示).
     */
    fun endInteractiveDrag(revealPx: Float, screenWidthPx: Int) {
        val progress = if (screenWidthPx > 0) (revealPx / screenWidthPx.toFloat()).coerceIn(0f, 1f) else 0f
        if (progress >= 0.50f) {
            animateToProgress(1f, screenWidthPx, returnHomeAfterClose = false, durationMs = 185L)
        } else {
            animateToProgress(0f, screenWidthPx, returnHomeAfterClose = false, durationMs = 200L)
        }
    }

    fun slideInFromLeft(returnHomeOnClose: Boolean = true) {
        val now = System.currentTimeMillis()
        if (isShowing || isWaitingForNativeHandoff || now - lastAutoTriggerMs < 1200L) return
        if (!Settings.canDrawOverlays(context)) return
        lastAutoTriggerMs = now

        ensureAttached()
        updateCardVisualMode()
        if (!isPrewarmed) {
            webView?.loadUrl(FEED_URL)
            isPrewarmed = true
        }

        val root = rootContainer ?: return
        val widthPx = DisplayProfile.fromContext(context).widthPx.coerceAtLeast(1)
        returnHomeOnDismiss = returnHomeOnClose

        activeAnimator?.cancel()
        mainHandler.removeCallbacks(handoffDismissFallback)
        root.alpha = 1f
        root.visibility = View.VISIBLE
        applyDragProgress(0f, widthPx)
        animateToProgress(1f, widthPx, returnHomeAfterClose = false, durationMs = 240L)
    }

    fun onNativeGoogleAppReady() {
        if (isWaitingForNativeHandoff) {
            mainHandler.removeCallbacks(handoffDismissFallback)
            mainHandler.postDelayed({ finishNativeHandoffCrossFade() }, 70L)
        }
    }

    private fun finishNativeHandoffCrossFade() {
        isWaitingForNativeHandoff = false
        val root = rootContainer ?: return
        val widthPx = DisplayProfile.fromContext(context).widthPx.coerceAtLeast(1)
        root.animate().cancel()
        root.animate()
            .alpha(0f)
            .setDuration(110L)
            .withEndAction {
                isShowing = false
                setWindowFlags(focusable = false, touchable = false)
                root.visibility = View.GONE
                root.alpha = 1f
                applyDragProgress(0f, widthPx)
            }
            .start()
    }

    fun dismiss(returnHome: Boolean = returnHomeOnDismiss) {
        val root = rootContainer ?: return
        mainHandler.removeCallbacks(handoffDismissFallback)
        isWaitingForNativeHandoff = false
        if (!isShowing && root.visibility != View.VISIBLE) return
        lastAutoTriggerMs = System.currentTimeMillis()
        val widthPx = DisplayProfile.fromContext(context).widthPx.coerceAtLeast(1)
        animateToProgress(0f, widthPx, returnHomeAfterClose = returnHome, durationMs = 210L)
    }

    private fun applyDragProgress(progress: Float, screenWidthPx: Int) {
        val clamped = progress.coerceIn(0f, 1f)
        currentProgress = clamped
        val card = cardContainer ?: return
        val scrim = scrimView

        card.translationX = -screenWidthPx.toFloat() * (1f - clamped)
        card.alpha = (0.45f + 0.55f * clamped).coerceIn(0.45f, 1f)
        scrim?.alpha = 0.55f * clamped
        card.invalidateOutline()
    }

    private fun animateToProgress(
        target: Float,
        screenWidthPx: Int,
        returnHomeAfterClose: Boolean,
        durationMs: Long = 210L
    ) {
        activeAnimator?.cancel()
        val start = currentProgress
        val opening = target >= 0.5f

        // Start launching real Google App right as the card begins its final snap to 100%
        // so Google App is already rendering behind our card with ZERO transition flicker!
        val launchNative = opening && prefs.feedLaunchNativeGoogleApp
        if (launchNative) {
            isWaitingForNativeHandoff = true
            GoogleIntentLauncher.launchGoogleAppNoAnim(context)
        }

        val anim = ValueAnimator.ofFloat(start, target).apply {
            duration = durationMs
            interpolator = slideInterpolator
            addUpdateListener { va ->
                applyDragProgress(va.animatedValue as Float, screenWidthPx)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    activeAnimator = null
                    if (opening) {
                        applyDragProgress(1f, screenWidthPx)
                        if (launchNative) {
                            // Hold card briefly until Google App window draws underneath, then cross-fade cleanly
                            mainHandler.removeCallbacks(handoffDismissFallback)
                            mainHandler.postDelayed(handoffDismissFallback, 240L)
                        } else {
                            isShowing = true
                            setWindowFlags(focusable = true, touchable = true)
                        }
                    } else {
                        isShowing = false
                        isWaitingForNativeHandoff = false
                        setWindowFlags(focusable = false, touchable = false)
                        rootContainer?.visibility = View.GONE
                        applyDragProgress(0f, screenWidthPx)
                        if (returnHomeAfterClose) {
                            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                                addCategory(Intent.CATEGORY_HOME)
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            runCatching { context.startActivity(homeIntent) }
                        }
                    }
                }
            })
        }
        activeAnimator = anim
        anim.start()
    }

    fun isOverlayShowing(): Boolean = isShowing || isWaitingForNativeHandoff

    private fun setWindowFlags(focusable: Boolean, touchable: Boolean) {
        val root = rootContainer ?: return
        val params = windowParams ?: return
        if (!isAttached) return
        var flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        if (!focusable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        if (!touchable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        if (params.flags == flags) return
        params.flags = flags
        try {
            windowManager.updateViewLayout(root, params)
        } catch (_: Throwable) {
        }
    }

    private fun getStatusBarHeightPx(): Int {
        val resId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) context.resources.getDimensionPixelSize(resId) else 0
    }

    fun destroy() {
        activeAnimator?.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        val root = rootContainer
        if (isAttached && root != null) {
            try {
                windowManager.removeViewImmediate(root)
            } catch (_: Throwable) {
            }
        }
        try {
            webView?.destroy()
        } catch (_: Throwable) {
        }
        cachedSnapshotBitmap?.let { if (!it.isRecycled) it.recycle() }
        cachedSnapshotBitmap = null
        webView = null
        rootContainer = null
        scrimView = null
        cardContainer = null
        webColumn = null
        snapshotImageView = null
        isAttached = false
        isPrewarmed = false
        isShowing = false
        isWaitingForNativeHandoff = false
    }

    companion object {
        private const val FEED_URL = "https://www.google.com/"
        private const val SNAPSHOT_FILENAME = "google_app_snap.png"
    }
}
