package com.pixeldock.search.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.pixeldock.search.detector.AccessibilityForegroundState

class SearchAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        AccessibilityForegroundState.isConnected = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return
        val cls = event.className?.toString() ?: ""

        if (pkg == "com.android.systemui") {
            val isShadeOrRecents = cls.contains("Notification", ignoreCase = true) ||
                cls.contains("StatusBar", ignoreCase = true) ||
                cls.contains("Recents", ignoreCase = true) ||
                cls.contains("Keyguard", ignoreCase = true) ||
                cls.contains("Bouncer", ignoreCase = true)
            AccessibilityForegroundState.isSystemOverlay = isShadeOrRecents
        } else {
            AccessibilityForegroundState.isSystemOverlay = false
            AccessibilityForegroundState.foregroundPackage = pkg
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        AccessibilityForegroundState.isConnected = false
        AccessibilityForegroundState.isSystemOverlay = false
        super.onDestroy()
    }
}
