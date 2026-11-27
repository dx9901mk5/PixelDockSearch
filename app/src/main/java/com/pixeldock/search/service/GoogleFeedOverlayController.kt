package com.pixeldock.search.service

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.pixeldock.search.launcher.GoogleIntentLauncher
import com.pixeldock.search.model.DisplayProfile
import kotlin.math.abs
import kotlin.math.roundToInt

class GoogleFeedOverlayController(
    private val context: Context,
    private val windowManager: WindowManager
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val slideInterpolator = PathInterpolator(0.2f, 0f, 0f, 1f)

    private var rootContainer: DismissibleFrameLayout? = null
    private var cardContainer: LinearLayout? = null
    private var webView: WebView? = null
    private var progressBar: ProgressBar? = null
    private var windowParams: WindowManager.LayoutParams? = null

    private var isAttached = false
    private var isPrewarmed = false
    private var isShowing = false
    private var returnHomeOnDismiss = false
    private var lastAutoTriggerMs = 0L

    private class DismissibleFrameLayout(
        context: Context,
        private val onBack: () -> Unit
    ) : FrameLayout(context) {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                onBack()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
    }

    fun prewarm() {
        if (isPrewarmed || !Settings.canDrawOverlays(context)) return
        mainHandler.postDelayed({
            if (!isPrewarmed && Settings.canDrawOverlays(context)) {
                ensureAttached()
                webView?.loadUrl(FEED_URL)
                isPrewarmed = true
            }
        }, 1200L)
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun ensureAttached() {
        if (isAttached || !Settings.canDrawOverlays(context)) return
        val snapshot = DisplayProfile.fromContext(context)
        val density = snapshot.density
        val isNight = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

        val bgColor = if (isNight) Color.parseColor("#1B1C1F") else Color.parseColor("#FFFFFF")
        val headerBgColor = if (isNight) Color.parseColor("#24262B") else Color.parseColor("#F1F3F4")
        val textColor = if (isNight) Color.parseColor("#E8EAED") else Color.parseColor("#202124")
        val subTextColor = if (isNight) Color.parseColor("#9AA0A6") else Color.parseColor("#5F6368")

        val root = DismissibleFrameLayout(context) { dismiss() }.apply {
            setBackgroundColor(Color.TRANSPARENT)
            visibility = View.GONE
        }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgColor)
            elevation = 16f * density
            translationX = -snapshot.widthPx.toFloat()
        }

        // Top bar
        val topPaddingPx = getStatusBarHeightPx()
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(headerBgColor)
            setPadding(
                (16 * density).roundToInt(),
                topPaddingPx + (8 * density).roundToInt(),
                (12 * density).roundToInt(),
                (8 * density).roundToInt()
            )
        }

        val titleView = TextView(context).apply {
            text = "Google 探索"
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            paint.isFakeBoldText = true
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val refreshBtn = makeHeaderAction("重整", subTextColor, density) {
            webView?.reload()
        }
        val openAppBtn = makeHeaderAction("Google App", Color.parseColor("#4285F4"), density) {
            dismiss(returnHome = false)
            GoogleIntentLauncher.launchGoogleFeed(context)
        }
        val closeBtn = makeHeaderAction("✕", textColor, density) {
            dismiss()
        }

        header.addView(titleView)
        header.addView(refreshBtn)
        header.addView(openAppBtn)
        header.addView(closeBtn)

        // Swipe left on header to dismiss
        var headerDownX = 0f
        header.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    headerDownX = ev.rawX
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - headerDownX
                    if (dx < 0f) {
                        card.translationX = dx
                    }
                    false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val dx = ev.rawX - headerDownX
                    if (dx < -snapshot.widthPx * 0.18f) {
                        dismiss()
                        true
                    } else {
                        card.animate().translationX(0f).setDuration(180L).setInterpolator(slideInterpolator).start()
                        false
                    }
                }
                else -> false
            }
        }

        val bar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (3 * density).roundToInt()
            )
        }

        val wv = WebView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
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
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    bar.progress = newProgress
                    bar.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
                }
            }
        }

        card.addView(header)
        card.addView(bar)
        card.addView(wv)
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
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        try {
            windowManager.addView(root, params)
            rootContainer = root
            cardContainer = card
            webView = wv
            progressBar = bar
            windowParams = params
            isAttached = true
        } catch (_: Throwable) {
            isAttached = false
        }
    }

    private fun makeHeaderAction(label: String, color: Int, density: Float, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            text = label
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
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
        if (!isPrewarmed) {
            webView?.loadUrl(FEED_URL)
            isPrewarmed = true
        }
        val root = rootContainer ?: return
        val card = cardContainer ?: return
        returnHomeOnDismiss = false
        card.animate().cancel()
        card.translationX = -screenWidthPx.toFloat()
        root.visibility = View.VISIBLE
        setFocusable(false)
    }

    fun updateInteractiveDrag(fingerX: Float, screenWidthPx: Int) {
        val card = cardContainer ?: return
        val tx = (-screenWidthPx.toFloat() + fingerX).coerceIn(-screenWidthPx.toFloat(), 0f)
        card.translationX = tx
    }

    fun endInteractiveDrag(fingerX: Float, velocityX: Float, screenWidthPx: Int) {
        val card = cardContainer ?: return
        val shouldOpen = fingerX > screenWidthPx * 0.25f || velocityX > 800f
        if (shouldOpen) {
            isShowing = true
            setFocusable(true)
            card.animate()
                .translationX(0f)
                .setDuration(220L)
                .setInterpolator(slideInterpolator)
                .start()
        } else {
            card.animate()
                .translationX(-screenWidthPx.toFloat())
                .setDuration(180L)
                .setInterpolator(slideInterpolator)
                .withEndAction {
                    isShowing = false
                    rootContainer?.visibility = View.GONE
                    setFocusable(false)
                }
                .start()
        }
    }

    fun slideInFromLeft(returnHomeOnClose: Boolean = true) {
        val now = System.currentTimeMillis()
        if (isShowing || now - lastAutoTriggerMs < 1200L) return
        if (!Settings.canDrawOverlays(context)) return
        lastAutoTriggerMs = now

        ensureAttached()
        if (!isPrewarmed) {
            webView?.loadUrl(FEED_URL)
            isPrewarmed = true
        }

        val root = rootContainer ?: return
        val card = cardContainer ?: return
        val widthPx = DisplayProfile.fromContext(context).widthPx.toFloat()
        returnHomeOnDismiss = returnHomeOnClose

        card.animate().cancel()
        card.translationX = -widthPx
        root.visibility = View.VISIBLE
        isShowing = true
        setFocusable(true)

        card.animate()
            .translationX(0f)
            .setDuration(260L)
            .setInterpolator(slideInterpolator)
            .start()
    }

    fun dismiss(returnHome: Boolean = returnHomeOnDismiss) {
        val root = rootContainer ?: return
        val card = cardContainer ?: return
        if (!isShowing && root.visibility != View.VISIBLE) return

        lastAutoTriggerMs = System.currentTimeMillis()
        val widthPx = DisplayProfile.fromContext(context).widthPx.toFloat()
        isShowing = false
        setFocusable(false)

        card.animate().cancel()
        card.animate()
            .translationX(-widthPx)
            .setDuration(220L)
            .setInterpolator(slideInterpolator)
            .withEndAction {
                root.visibility = View.GONE
                if (returnHome) {
                    val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_HOME)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    runCatching { context.startActivity(homeIntent) }
                }
            }
            .start()
    }

    fun isOverlayShowing(): Boolean = isShowing

    private fun setFocusable(focusable: Boolean) {
        val root = rootContainer ?: return
        val params = windowParams ?: return
        if (!isAttached) return
        params.flags = if (focusable) {
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        }
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
        webView = null
        rootContainer = null
        cardContainer = null
        isAttached = false
        isPrewarmed = false
        isShowing = false
    }

    companion object {
        private const val FEED_URL = "https://www.google.com/"
    }
}
