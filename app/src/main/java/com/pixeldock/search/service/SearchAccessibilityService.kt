package com.pixeldock.search.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.pixeldock.search.data.FeedTriggerMode
import com.pixeldock.search.data.PrefsRepository
import com.pixeldock.search.detector.AccessibilityForegroundState
import com.pixeldock.search.detector.LauncherGestureBus
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

class SearchAccessibilityService : AccessibilityService() {

    private lateinit var prefs: PrefsRepository
    private val lastScrollYByWindow = ConcurrentHashMap<Int, Int>()
    private var cachedLaunchers: Set<String> = emptySet()
    private var lastLauncherRefreshMs = 0L
    private var lastContentScanMs = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = PrefsRepository(this)
        refreshLaunchersIfNeeded()
        AccessibilityForegroundState.isConnected = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return
        val cls = event.className?.toString() ?: ""

        refreshLaunchersIfNeeded()
        val isLauncher = pkg in cachedLaunchers

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                AccessibilityForegroundState.currentWindowClass = cls

                if (pkg == "com.android.systemui") {
                    val isShadeOrRecents = cls.contains("Notification", ignoreCase = true) ||
                        cls.contains("StatusBar", ignoreCase = true) ||
                        cls.contains("Recents", ignoreCase = true) ||
                        cls.contains("Keyguard", ignoreCase = true) ||
                        cls.contains("Bouncer", ignoreCase = true)
                    AccessibilityForegroundState.isSystemOverlay = isShadeOrRecents
                    if (isShadeOrRecents) {
                        AccessibilityForegroundState.isMinusOneActive = false
                        LauncherGestureBus.dispatchForegroundVisibility(
                            shouldShowHomeOverlay = false,
                            isAppDrawer = false,
                            isPopupOrMenu = false
                        )
                    }
                    return
                }

                // Immediate detection for built-in or user-confirmed Minus-One package
                if (LauncherGestureBus.isMinusOnePackageOrClass(
                        pkg, cls, prefs.customMinusOnePackage, prefs.customMinusOneSignature, ""
                    )
                ) {
                    val sig = "$pkg|$cls"
                    AccessibilityForegroundState.isMinusOneActive = true
                    AccessibilityForegroundState.isSystemOverlay = false
                    AccessibilityForegroundState.isAppDrawerOpen = false
                    AccessibilityForegroundState.isLauncherPopupOpen = false
                    AccessibilityForegroundState.currentPageSignature = sig
                    LauncherGestureBus.dispatchPageSnapshot(pkg, cls, sig)
                    LauncherGestureBus.dispatchForegroundVisibility(
                        shouldShowHomeOverlay = false,
                        isAppDrawer = false,
                        isPopupOrMenu = false
                    )
                    if (prefs.feedTriggerMode == FeedTriggerMode.MINUS_ONE_AUTO_REPLACE) {
                        LauncherGestureBus.dispatchMinusOneDetected(pkg)
                    }
                    return
                }

                // Non-launcher app opened -> INSTANT hide (< 16ms)
                if (!isLauncher) {
                    AccessibilityForegroundState.isSystemOverlay = false
                    AccessibilityForegroundState.isMinusOneActive = false
                    AccessibilityForegroundState.isAppDrawerOpen = false
                    AccessibilityForegroundState.isLauncherPopupOpen = false
                    AccessibilityForegroundState.foregroundPackage = pkg
                    val sig = "$pkg|$cls"
                    AccessibilityForegroundState.currentPageSignature = sig
                    LauncherGestureBus.dispatchPageSnapshot(pkg, cls, sig)
                    LauncherGestureBus.dispatchForegroundVisibility(
                        shouldShowHomeOverlay = false,
                        isAppDrawer = false,
                        isPopupOrMenu = false
                    )
                    if (pkg == "com.google.android.googlequicksearchbox") {
                        LauncherGestureBus.dispatchGoogleAppEntered()
                        if (LauncherGestureBus.isAutoClickGoogleLensPending()) {
                            scheduleLensAutoClickRetries()
                        }
                    }
                    return
                }

                // Inside Launcher: inspect immediately for Popup Menu (Fig 1), App Drawer (Fig 3), or Minus-One
                AccessibilityForegroundState.isSystemOverlay = false
                AccessibilityForegroundState.foregroundPackage = pkg
                inspectLauncherHierarchy(pkg, cls, force = true)
            }

            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                if (isLauncher) {
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
                    }
                    inspectLauncherHierarchy(pkg, cls, force = false)
                }
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (isLauncher) {
                    inspectLauncherHierarchy(pkg, cls, force = false)
                } else if (pkg == "com.google.android.googlequicksearchbox" &&
                    LauncherGestureBus.isAutoClickGoogleLensPending()
                ) {
                    tryClickGoogleAppLensButton()
                }
            }
        }
    }

    private val retryHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun scheduleLensAutoClickRetries() {
        tryClickGoogleAppLensButton()
        for (delayMs in longArrayOf(200L, 520L, 950L, 1450L)) {
            retryHandler.postDelayed({
                if (LauncherGestureBus.isAutoClickGoogleLensPending()) {
                    tryClickGoogleAppLensButton()
                }
            }, delayMs)
        }
    }

    private fun tryClickGoogleAppLensButton() {
        val root = try {
            rootInActiveWindow
        } catch (_: Throwable) {
            null
        } ?: return

        try {
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            var visited = 0
            while (queue.isNotEmpty() && visited < 100) {
                val node = queue.removeFirst()
                visited++
                val desc = node.contentDescription?.toString()?.trim().orEmpty()
                val text = node.text?.toString()?.trim().orEmpty()
                val viewId = node.viewIdResourceName?.trim().orEmpty()
                val isLensTarget = desc.contains("智慧鏡頭", ignoreCase = true) ||
                    desc.contains("圖片搜尋", ignoreCase = true) ||
                    desc.contains("相機搜尋", ignoreCase = true) ||
                    desc.contains("以圖搜尋", ignoreCase = true) ||
                    desc.contains("Google 鏡頭", ignoreCase = true) ||
                    desc.contains("智慧镜头", ignoreCase = true) ||
                    desc.contains("拍照搜", ignoreCase = true) ||
                    desc.contains("search with your camera", ignoreCase = true) ||
                    desc.contains("Lens", ignoreCase = true) ||
                    text.equals("智慧鏡頭", ignoreCase = true) ||
                    text.equals("Lens", ignoreCase = true) ||
                    viewId.contains("lens", ignoreCase = true)
                if (isLensTarget) {
                    var clickable: AccessibilityNodeInfo? = node
                    while (clickable != null && !clickable.isClickable) {
                        clickable = clickable.parent
                    }
                    if (clickable != null && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        LauncherGestureBus.consumeAutoClickGoogleLens()
                        break
                    }
                }
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i) ?: continue
                    queue.addLast(child)
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun inspectLauncherHierarchy(pkg: String, cls: String, force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastContentScanMs < 25L) return
        lastContentScanMs = now

        var popupDetected = LauncherGestureBus.isLauncherPopupClassOrId(cls)
        var drawerDetected = LauncherGestureBus.isAppDrawerClass(cls)
        var minusOneDetected = false
        var pageIndicatorText = ""
        val topTokens = ArrayList<String>(4)

        // Check active windows for floating launcher popup menus (e.g., long-press app shortcut menu)
        try {
            val displayMetrics = resources.displayMetrics
            val screenArea = displayMetrics.widthPixels.toLong() * displayMetrics.heightPixels.toLong()
            val rect = Rect()
            for (win in windows) {
                val root = win.root ?: continue
                val winPkg = root.packageName?.toString() ?: ""
                if (winPkg == pkg) {
                    win.getBoundsInScreen(rect)
                    val winArea = rect.width().toLong() * rect.height().toLong()
                    // A smaller overlay window belonging to the launcher is typically a long-press popup or folder
                    if (winArea in 1L until (screenArea * 65L / 100L)) {
                        val title = win.title?.toString() ?: ""
                        if (LauncherGestureBus.isLauncherPopupClassOrId(title) || hasPopupTexts(root)) {
                            popupDetected = true
                        }
                    }
                }
                root.recycle()
                if (popupDetected) break
            }
        } catch (_: Throwable) {
        }

        val activeRoot = try {
            rootInActiveWindow
        } catch (_: Throwable) {
            null
        }

        if (activeRoot != null) {
            try {
                val queue = ArrayDeque<AccessibilityNodeInfo>()
                queue.add(activeRoot)
                var visited = 0
                while (queue.isNotEmpty() && visited < 75) {
                    val node = queue.removeFirst()
                    visited++

                    val text = node.text?.toString()?.trim().orEmpty()
                    val desc = node.contentDescription?.toString()?.trim().orEmpty()
                    val viewId = node.viewIdResourceName?.trim().orEmpty()
                    val nodeCls = node.className?.toString().orEmpty()

                    if (!popupDetected) {
                        if (LauncherGestureBus.isLauncherPopupText(text) ||
                            LauncherGestureBus.isLauncherPopupText(desc) ||
                            LauncherGestureBus.isLauncherPopupClassOrId(viewId) ||
                            LauncherGestureBus.isLauncherPopupClassOrId(nodeCls)
                        ) {
                            popupDetected = true
                        }
                    }

                    if (!drawerDetected) {
                        if (LauncherGestureBus.isAppDrawerNodeText(text) ||
                            LauncherGestureBus.isAppDrawerNodeText(desc) ||
                            LauncherGestureBus.isAppDrawerViewId(viewId)
                        ) {
                            drawerDetected = true
                        }
                    }

                    if (pageIndicatorText.isEmpty() && desc.isNotEmpty() &&
                        (desc.contains("頁") || desc.contains("页") ||
                            desc.contains("主螢幕") || desc.contains("主屏幕") ||
                            desc.contains("Home screen", ignoreCase = true) ||
                            desc.contains("Page ", ignoreCase = true))
                    ) {
                        pageIndicatorText = desc
                    }

                    if (topTokens.size < 3 && text.length in 2..16 && !text.all { it.isDigit() || it == ':' }) {
                        topTokens.add(text)
                    }

                    val childCount = node.childCount
                    for (i in 0 until childCount) {
                        val child = node.getChild(i) ?: continue
                        if (visited + queue.size < 90) {
                            queue.addLast(child)
                        } else {
                            child.recycle()
                        }
                    }
                    if (node !== activeRoot) {
                        node.recycle()
                    }
                }
                while (queue.isNotEmpty()) {
                    val rem = queue.removeFirst()
                    if (rem !== activeRoot) rem.recycle()
                }
            } catch (_: Throwable) {
            } finally {
                try {
                    activeRoot.recycle()
                } catch (_: Throwable) {
                }
            }
        }

        val pageSig = when {
            pageIndicatorText.isNotEmpty() -> "$pkg|$pageIndicatorText"
            topTokens.isNotEmpty() -> "$pkg|${topTokens.joinToString(",")}"
            else -> "$pkg|$cls"
        }

        AccessibilityForegroundState.currentPageSignature = pageSig
        LauncherGestureBus.dispatchPageSnapshot(pkg, cls, pageSig)

        // Always detect OEM / user-confirmed Minus-One screen so the bottom search bar NEVER appears on it
        if (LauncherGestureBus.isMinusOnePackageOrClass(
                pkg,
                cls,
                prefs.customMinusOnePackage,
                prefs.customMinusOneSignature,
                pageSig
            )
        ) {
            minusOneDetected = true
        }

        val confirmedFirstSig = prefs.customFirstPageSignature
        AccessibilityForegroundState.isOnConfirmedFirstPage =
            !minusOneDetected && (confirmedFirstSig.isBlank() || pageSig == confirmedFirstSig)

        val wasDrawer = AccessibilityForegroundState.isAppDrawerOpen
        AccessibilityForegroundState.isLauncherPopupOpen = popupDetected
        AccessibilityForegroundState.isAppDrawerOpen = drawerDetected
        AccessibilityForegroundState.isMinusOneActive = minusOneDetected

        if (minusOneDetected) {
            LauncherGestureBus.dispatchForegroundVisibility(
                shouldShowHomeOverlay = false,
                isAppDrawer = false,
                isPopupOrMenu = false
            )
            if (prefs.feedTriggerMode == FeedTriggerMode.MINUS_ONE_AUTO_REPLACE) {
                LauncherGestureBus.dispatchMinusOneDetected(pkg)
            }
            return
        }

        if (drawerDetected != wasDrawer) {
            LauncherGestureBus.dispatchAppDrawerState(drawerDetected)
        }

        val shouldShow = !popupDetected && !drawerDetected && !AccessibilityForegroundState.isSystemOverlay
        LauncherGestureBus.dispatchForegroundVisibility(
            shouldShowHomeOverlay = shouldShow,
            isAppDrawer = drawerDetected,
            isPopupOrMenu = popupDetected
        )
    }

    private fun hasPopupTexts(root: AccessibilityNodeInfo): Boolean {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var count = 0
        var matched = false
        while (queue.isNotEmpty() && count < 30 && !matched) {
            val n = queue.removeFirst()
            count++
            val t = n.text?.toString().orEmpty()
            val d = n.contentDescription?.toString().orEmpty()
            if (LauncherGestureBus.isLauncherPopupText(t) || LauncherGestureBus.isLauncherPopupText(d)) {
                matched = true
            } else {
                for (i in 0 until n.childCount) {
                    val c = n.getChild(i) ?: continue
                    queue.addLast(c)
                }
            }
            if (n !== root) n.recycle()
        }
        while (queue.isNotEmpty()) {
            val rem = queue.removeFirst()
            if (rem !== root) rem.recycle()
        }
        return matched
    }

    private fun refreshLaunchersIfNeeded() {
        val now = System.currentTimeMillis()
        if (cachedLaunchers.isNotEmpty() && now - lastLauncherRefreshMs < 30_000L) return
        val found = linkedSetOf<String>()
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val homes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(
                homeIntent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
        }
        for (ri in homes) {
            val p = ri.activityInfo?.packageName
            if (!p.isNullOrBlank() && p != "android" && p != "com.android.settings") {
                found.add(p)
            }
        }
        if (::prefs.isInitialized) {
            found.addAll(prefs.extraLauncherPackages)
        }
        cachedLaunchers = found
        lastLauncherRefreshMs = now
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        AccessibilityForegroundState.isConnected = false
        AccessibilityForegroundState.isSystemOverlay = false
        AccessibilityForegroundState.isAppDrawerOpen = false
        AccessibilityForegroundState.isLauncherPopupOpen = false
        AccessibilityForegroundState.isMinusOneActive = false
        lastScrollYByWindow.clear()
        super.onDestroy()
    }
}
