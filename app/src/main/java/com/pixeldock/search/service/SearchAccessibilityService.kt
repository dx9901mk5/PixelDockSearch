package com.pixeldock.search.service

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import com.pixeldock.search.detector.AccessibilityForegroundState
import com.pixeldock.search.detector.LauncherGestureBus
import java.util.concurrent.ConcurrentHashMap

class SearchAccessibilityService : AccessibilityService() {

    private val lastScrollYByWindow = ConcurrentHashMap<Int, Int>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        AccessibilityForegroundState.isConnected = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return
        val cls = event.className?.toString() ?: ""

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                if (LauncherGestureBus.isMinusOnePackageOrClass(pkg, cls)) {
                    AccessibilityForegroundState.isMinusOneActive = true
                    AccessibilityForegroundState.isSystemOverlay = false
                    LauncherGestureBus.dispatchMinusOneDetected(pkg)
                } else if (pkg == "com.android.systemui") {
                    val isShadeOrRecents = cls.contains("Notification", ignoreCase = true) ||
                        cls.contains("StatusBar", ignoreCase = true) ||
                        cls.contains("Recents", ignoreCase = true) ||
                        cls.contains("Keyguard", ignoreCase = true) ||
                        cls.contains("Bouncer", ignoreCase = true)
                    AccessibilityForegroundState.isSystemOverlay = isShadeOrRecents
                    AccessibilityForegroundState.isMinusOneActive = false
                } else {
                    AccessibilityForegroundState.isSystemOverlay = false
                    AccessibilityForegroundState.isMinusOneActive = false
                    AccessibilityForegroundState.foregroundPackage = pkg

                    if (LauncherGestureBus.isAppDrawerClass(cls)) {
                        AccessibilityForegroundState.isAppDrawerOpen = true
                        LauncherGestureBus.dispatchAppDrawerState(true)
                    } else if (cls.contains("Launcher", ignoreCase = true) ||
                        cls.contains("Workspace", ignoreCase = true) ||
                        cls.contains("Home", ignoreCase = true)
                    ) {
                        if (AccessibilityForegroundState.isAppDrawerOpen) {
                            AccessibilityForegroundState.isAppDrawerOpen = false
                            LauncherGestureBus.dispatchAppDrawerState(false)
                        }
                    }
                }
            }

            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED &&
                    pkg == AccessibilityForegroundState.foregroundPackage
                ) {
                    val windowId = event.windowId
                    val currentScrollY = event.scrollY
                    val prevY = lastScrollYByWindow.put(windowId, currentScrollY) ?: currentScrollY
                    val deltaY = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && event.scrollDeltaY != 0) {
                        event.scrollDeltaY
                    } else {
                        currentScrollY - prevY
                    }
                    if (deltaY > 0) {
                        LauncherGestureBus.dispatchVerticalScroll(deltaY)
                    } else if (currentScrollY == 0 && deltaY < 0 && AccessibilityForegroundState.isAppDrawerOpen) {
                        AccessibilityForegroundState.isAppDrawerOpen = false
                        LauncherGestureBus.dispatchAppDrawerState(false)
                    }
                }
            }
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        AccessibilityForegroundState.isConnected = false
        AccessibilityForegroundState.isSystemOverlay = false
        AccessibilityForegroundState.isAppDrawerOpen = false
        AccessibilityForegroundState.isMinusOneActive = false
        lastScrollYByWindow.clear()
        super.onDestroy()
    }
}
