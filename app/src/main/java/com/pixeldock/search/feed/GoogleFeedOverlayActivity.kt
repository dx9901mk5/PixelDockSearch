package com.pixeldock.search.feed

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat

/**
 * 100% transparent, no-animation host Activity launched strictly on-demand when the user
 * drags right on our Search Bar or Left-Edge strip. It provides a valid Activity window token
 * to Google App's DrawerOverlayService (ILauncherOverlay) so Google App draws the REAL native
 * Pixel Discover Feed directly over the visible stock launcher, and finishes invisibly the
 * moment the Feed closes back to 0.0f.
 */
class GoogleFeedOverlayActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.TRANSPARENT
        window.addFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        )

        val transparentRoot = View(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener {
                // Tapping exposed transparent desktop while Feed is closed cleanly exits host
                NativeGoogleFeedBridge.onHostBackPressed()
            }
        }
        setContentView(transparentRoot)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                NativeGoogleFeedBridge.onHostBackPressed()
            }
        })
    }

    fun setInteractiveMode(interactive: Boolean) {
        if (interactive) {
            window.clearFlags(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            )
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            )
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        NativeGoogleFeedBridge.onHostAttachedToWindow(this)
    }

    override fun onResume() {
        super.onResume()
        NativeGoogleFeedBridge.onHostResumed(this)
    }

    override fun onPause() {
        super.onPause()
        NativeGoogleFeedBridge.onHostPaused()
    }

    override fun onDetachedFromWindow() {
        NativeGoogleFeedBridge.onHostDetached(this, isChangingConfigurations)
        super.onDetachedFromWindow()
    }

    fun finishQuietly() {
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
