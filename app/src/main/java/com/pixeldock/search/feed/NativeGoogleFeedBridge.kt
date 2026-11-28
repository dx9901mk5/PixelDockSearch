package com.pixeldock.search.feed

import android.app.Activity
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import com.google.android.libraries.launcherclient.ILauncherOverlay
import com.google.android.libraries.launcherclient.ILauncherOverlayCallback
import com.pixeldock.search.launcher.GoogleIntentLauncher
import java.lang.ref.WeakReference

/**
 * Connects directly to Google App's DrawerOverlayService (com.android.launcher3.WINDOW_OVERLAY)
 * using the official Pixel Launcher ILauncherOverlay protocol, and coordinates on-demand
 * transparent hosting via GoogleFeedOverlayActivity so the stock launcher remains 100% untouched
 * until the user drags right on our designated Search Bar or Left-Edge strip.
 */
object NativeGoogleFeedBridge {

    private const val ACTION_WINDOW_OVERLAY = "com.android.launcher3.WINDOW_OVERLAY"
    private const val CLIENT_OPTIONS = 7 // overlay enabled (1) | hotword (2) | theme sync (4)

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var overlayService: ILauncherOverlay? = null

    @Volatile
    private var isBound = false

    @Volatile
    private var apiVersion = 1

    @Volatile
    private var overlayStatusFlags = 0

    private var activeHostActivity: WeakReference<GoogleFeedOverlayActivity>? = null
    private var isWindowAttached = false
    private var isDragSessionActive = false
    private var isScrollStartedOnOverlay = false
    private var pendingAutoOpen = false
    private var pendingDragEnded = false
    private var pendingFinalOpen = false
    private var latestDragProgress = 0f
    private var hasReachedVisibleScroll = false

    private val autoCloseCheckRunnable = Runnable {
        if (!isDragSessionActive && !hasReachedVisibleScroll) {
            finishHostActivity()
        }
    }

    private val overlayCallback = object : ILauncherOverlayCallback.Stub() {
        override fun overlayScrollChanged(progress: Float) {
            mainHandler.post {
                if (progress > 0.08f) {
                    hasReachedVisibleScroll = true
                }
                // Once the user finishes swiping left inside Google Discover back to 0f,
                // immediately close the transparent host Activity so focus returns seamlessly to stock home!
                if (!isDragSessionActive && hasReachedVisibleScroll && progress <= 0.001f) {
                    hasReachedVisibleScroll = false
                    finishHostActivity()
                }
            }
        }

        override fun overlayStatusChanged(status: Int) {
            overlayStatusFlags = status
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            overlayService = ILauncherOverlay.Stub.asInterface(service)
            mainHandler.post {
                activeHostActivity?.get()?.let { host ->
                    attachHostWindowIfReady(host)
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            overlayService = null
            isWindowAttached = false
            overlayStatusFlags = 0
        }
    }

    fun prewarm(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        if (overlayService != null && isBound) return
        bindOverlayService(ctx)
    }

    fun isAvailable(): Boolean = overlayService != null

    fun statusLabel(): String = when {
        overlayService != null && (overlayStatusFlags and 1) != 0 -> "已連線 (原生 Pixel Feed 就緒)"
        overlayService != null -> "已綁定 Google App Overlay"
        isBound -> "連線中…"
        else -> "未連線 (使用備援轉場)"
    }

    private fun buildOverlayIntent(context: Context): Intent {
        val uri = Uri.parse("app://${context.packageName}:${Process.myUid()}")
            .buildUpon()
            .appendQueryParameter("v", "7")
            .appendQueryParameter("cv", "9")
            .build()
        return Intent(ACTION_WINDOW_OVERLAY)
            .setPackage(GoogleIntentLauncher.GOOGLE_APP_PACKAGE)
            .setData(uri)
    }

    private fun bindOverlayService(context: Context) {
        val intent = buildOverlayIntent(context)
        try {
            val ri = context.packageManager.resolveService(intent, PackageManager.GET_META_DATA)
            apiVersion = ri?.serviceInfo?.metaData?.getInt("service.api.version", 1) ?: 1
        } catch (_: Throwable) {
            apiVersion = 1
        }
        try {
            val ok = context.bindService(
                intent,
                serviceConnection,
                Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT
            )
            isBound = ok
        } catch (_: Throwable) {
            isBound = false
        }
    }

    fun unbind() {
        val ctx = appContext
        if (ctx != null && isBound) {
            try {
                ctx.unbindService(serviceConnection)
            } catch (_: Throwable) {
            }
        }
        isBound = false
        overlayService = null
        isWindowAttached = false
        finishHostActivity()
    }

    /**
     * Begins a 1:1 native Google Discover drag session by launching our 100% transparent host Activity
     * with ZERO transition animation over the live stock desktop.
     */
    fun beginInteractiveDrag(context: Context): Boolean {
        val ctx = context.applicationContext
        appContext = ctx
        if (overlayService == null) {
            bindOverlayService(ctx)
            return false
        }
        mainHandler.removeCallbacks(autoCloseCheckRunnable)
        isDragSessionActive = true
        isScrollStartedOnOverlay = false
        pendingAutoOpen = false
        pendingDragEnded = false
        pendingFinalOpen = false
        latestDragProgress = 0f
        hasReachedVisibleScroll = false

        val existingHost = activeHostActivity?.get()
        if (existingHost != null && !existingHost.isFinishing && !existingHost.isDestroyed && isWindowAttached) {
            startScrollIfNeeded()
            return true
        }
        launchTransparentHost(ctx)
        return true
    }

    fun updateInteractiveDrag(progress: Float) {
        val clamped = progress.coerceIn(0f, 1f)
        latestDragProgress = clamped
        if (clamped > 0.08f) hasReachedVisibleScroll = true

        val svc = overlayService ?: return
        if (!isWindowAttached) return
        startScrollIfNeeded()
        try {
            svc.onScroll(clamped)
        } catch (_: Throwable) {
        }
    }

    /**
     * Strict 50% threshold rule:
     * - progress >= 0.50f -> commit open to real live Pixel Discover Feed
     * - progress <  0.50f -> glide back to 0.0f and invisibly close the transparent host Activity
     */
    fun endInteractiveDrag(finalProgress: Float) {
        val clamped = finalProgress.coerceIn(0f, 1f)
        latestDragProgress = clamped
        isDragSessionActive = false
        val shouldOpen = clamped >= 0.50f

        if (!isWindowAttached) {
            pendingDragEnded = true
            pendingFinalOpen = shouldOpen
            if (!shouldOpen) {
                mainHandler.postDelayed({ finishHostActivity() }, 180L)
            }
            return
        }
        commitDragResult(shouldOpen, clamped)
    }

    fun openImmediately(context: Context): Boolean {
        val ctx = context.applicationContext
        appContext = ctx
        if (overlayService == null) {
            bindOverlayService(ctx)
            return false
        }
        mainHandler.removeCallbacks(autoCloseCheckRunnable)
        isDragSessionActive = false
        isScrollStartedOnOverlay = false
        pendingAutoOpen = true
        pendingDragEnded = false
        hasReachedVisibleScroll = true

        val existingHost = activeHostActivity?.get()
        if (existingHost != null && !existingHost.isFinishing && !existingHost.isDestroyed && isWindowAttached) {
            try {
                overlayService?.openOverlay(1)
            } catch (_: Throwable) {
            }
            return true
        }
        launchTransparentHost(ctx)
        return true
    }

    private fun launchTransparentHost(context: Context) {
        try {
            val intent = Intent(context, GoogleFeedOverlayActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                )
            }
            val noAnim = ActivityOptions.makeCustomAnimation(context, 0, 0).toBundle()
            context.startActivity(intent, noAnim)
        } catch (_: Throwable) {
        }
    }

    internal fun onHostAttachedToWindow(activity: GoogleFeedOverlayActivity) {
        activeHostActivity = WeakReference(activity)
        attachHostWindowIfReady(activity)
    }

    internal fun onHostResumed(activity: GoogleFeedOverlayActivity) {
        activeHostActivity = WeakReference(activity)
        val svc = overlayService ?: return
        if (isWindowAttached) {
            try {
                if (apiVersion >= 4) svc.setActivityState(3) else svc.onResume()
            } catch (_: Throwable) {
            }
        }
    }

    internal fun onHostPaused() {
        val svc = overlayService ?: return
        if (isWindowAttached) {
            try {
                if (apiVersion >= 4) svc.setActivityState(1) else svc.onPause()
            } catch (_: Throwable) {
            }
        }
    }

    internal fun onHostDetached(activity: GoogleFeedOverlayActivity, changingConfig: Boolean) {
        if (activeHostActivity?.get() === activity) {
            activeHostActivity = null
            isWindowAttached = false
            isScrollStartedOnOverlay = false
            isDragSessionActive = false
            try {
                overlayService?.windowDetached(changingConfig)
            } catch (_: Throwable) {
            }
        }
    }

    internal fun onHostBackPressed() {
        try {
            overlayService?.closeOverlay(1)
        } catch (_: Throwable) {
        }
        mainHandler.postDelayed({ finishHostActivity() }, 220L)
    }

    private fun attachHostWindowIfReady(activity: GoogleFeedOverlayActivity) {
        val svc = overlayService ?: return
        val attrs = activity.window?.attributes ?: return
        if (attrs.token == null) return

        try {
            if (apiVersion < 3) {
                svc.windowAttached(attrs, overlayCallback, CLIENT_OPTIONS)
            } else {
                val bundle = Bundle().apply {
                    putParcelable("layout_params", attrs)
                    putParcelable("configuration", activity.resources.configuration)
                    putInt("client_options", CLIENT_OPTIONS)
                }
                svc.windowAttached2(bundle, overlayCallback)
            }
            if (apiVersion >= 4) {
                svc.setActivityState(3) // started (1) | resumed (2)
            } else {
                svc.onResume()
            }
            isWindowAttached = true
        } catch (_: Throwable) {
            isWindowAttached = false
            finishHostActivity()
            return
        }

        // Flush any drag progress buffered during the ~40ms Activity attach window
        if (pendingAutoOpen) {
            pendingAutoOpen = false
            try {
                svc.openOverlay(1)
            } catch (_: Throwable) {
            }
        } else if (isDragSessionActive) {
            startScrollIfNeeded()
            if (latestDragProgress > 0f) {
                try {
                    svc.onScroll(latestDragProgress)
                } catch (_: Throwable) {
                }
            }
        } else if (pendingDragEnded) {
            pendingDragEnded = false
            commitDragResult(pendingFinalOpen, latestDragProgress)
        }
    }

    private fun startScrollIfNeeded() {
        val svc = overlayService ?: return
        if (!isScrollStartedOnOverlay && isWindowAttached) {
            isScrollStartedOnOverlay = true
            try {
                svc.startScroll()
            } catch (_: Throwable) {
            }
        }
    }

    private fun commitDragResult(shouldOpen: Boolean, progress: Float) {
        val svc = overlayService
        if (svc == null) {
            finishHostActivity()
            return
        }
        try {
            if (shouldOpen) {
                if (isScrollStartedOnOverlay) {
                    svc.onScroll(progress.coerceAtLeast(0.55f))
                    svc.endScroll()
                }
                svc.openOverlay(1)
                hasReachedVisibleScroll = true
            } else {
                if (isScrollStartedOnOverlay) {
                    svc.onScroll(0f)
                    svc.endScroll()
                }
                svc.closeOverlay(1)
                mainHandler.postDelayed({ finishHostActivity() }, 240L)
            }
        } catch (_: Throwable) {
            finishHostActivity()
        }
        isScrollStartedOnOverlay = false
    }

    private fun finishHostActivity() {
        val host = activeHostActivity?.get() ?: return
        if (!host.isFinishing && !host.isDestroyed) {
            host.finishQuietly()
        }
    }
}
