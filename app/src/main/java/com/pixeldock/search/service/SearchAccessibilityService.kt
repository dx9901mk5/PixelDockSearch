package com.pixeldock.search.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.pixeldock.search.data.FeedTriggerMode
import com.pixeldock.search.data.PrefsRepository
import com.pixeldock.search.detector.AccessibilityForegroundState
import com.pixeldock.search.detector.LauncherGestureBus
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class SearchAccessibilityService : AccessibilityService() {

    private val prefs by lazy { PrefsRepository(applicationContext) }
    private val lastScrollYByWindow = ConcurrentHashMap<Int, Int>()
    private val retryHandler = Handler(Looper.getMainLooper())
    private val scanExecutor = Executors.newSingleThreadExecutor()
    private val scanInFlight = AtomicBoolean(false)

    @Volatile
    private var cachedLaunchers: Set<String> = emptySet()

    @Volatile
    private var lastLauncherRefreshMs = 0L

    @Volatile
    private var lastContentScanMs = 0L

    private var pendingRescanRunnable: Runnable? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            prefs.accessibilityKeepAlive = true
            refreshLaunchersIfNeeded()
            AccessibilityForegroundState.isConnected = true
        } catch (_: Throwable) {
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            handleAccessibilityEvent(event)
        } catch (_: Throwable) {
            // Never allow an uncaught exception to kill and disable the AccessibilityService
        }
    }

    private fun handleAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return
        val cls = event.className?.toString() ?: ""

        refreshLaunchersIfNeeded()
        val isLauncher = pkg in cachedLaunchers

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                AccessibilityForegroundState.currentWindowClass = cls

                if (pkg == "com.android.systemui") {
                    // Only treat actual expanded Shade / Lockscreen / Recents as system overlay;
                    // NEVER hide on status-bar icon updates or screen-recorder timer ticks!
                    val isShadeOrRecents = cls.contains("NotificationShade", ignoreCase = true) ||
                        cls.contains("ShadeWindow", ignoreCase = true) ||
                        cls.contains("Recents", ignoreCase = true) ||
                        cls.contains("Keyguard", ignoreCase = true) ||
                        cls.contains("Bouncer", ignoreCase = true) ||
                        cls.contains("VolumeDialog", ignoreCase = true)
                    if (isShadeOrRecents) {
                        AccessibilityForegroundState.isSystemOverlay = true
                        AccessibilityForegroundState.isMinusOneActive = false
                        LauncherGestureBus.dispatchForegroundVisibility(
                            shouldShowHomeOverlay = false,
                            isAppDrawer = false,
                            isPopupOrMenu = false
                        )
                    }
                    return
                }

                // Ignore transient overlay/toast/IME/floating-pill windows that do not represent a real foreground app switch
                if (cls.contains("Toast", ignoreCase = true) ||
                    cls.contains("InputMethod", ignoreCase = true) ||
                    pkg == "android" ||
                    pkg.contains("inputmethod", ignoreCase = true) ||
                    pkg.contains("screenrecorder", ignoreCase = true) ||
                    pkg.contains("smartshot", ignoreCase = true) ||
                    pkg.contains("floatingball", ignoreCase = true) ||
                    pkg.contains("originisland", ignoreCase = true) ||
                    pkg.contains("daemonservice", ignoreCase = true) ||
                    pkg.contains("livewallpaper", ignoreCase = true)
                ) {
                    return
                }

                if (LauncherGestureBus.isMinusOnePackageOrClass(
                        pkg,
                        cls,
                        prefs.customMinusOnePackage,
                        prefs.customMinusOneSignature,
                        "",
                        cachedLaunchers
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

                if (!isLauncher) {
                    // Crucial anti-flicker guard: if the actual active focused window is STILL the stock launcher,
                    // this TYPE_WINDOW_STATE_CHANGED came from a non-focused floating pill/banner/service overlay.
                    val activeWindowPkg = try {
                        rootInActiveWindow?.packageName?.toString()
                    } catch (_: Throwable) {
                        null
                    }
                    if (activeWindowPkg != null && activeWindowPkg in cachedLaunchers) {
                        // Still rescan launcher windows so newly opened floating/mini-window apps hide the bar immediately
                        scheduleHierarchyScan(activeWindowPkg, cls, force = true)
                        return
                    }

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

                AccessibilityForegroundState.isSystemOverlay = false
                AccessibilityForegroundState.foregroundPackage = pkg
                scheduleHierarchyScan(pkg, cls, force = true)
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
                    // Ignore tiny 1-17px widget micro-scrolls that cause the bar to twitch on the home screen
                    if (deltaY >= 18) {
                        LauncherGestureBus.dispatchVerticalScroll(deltaY)
                    }
                    scheduleHierarchyScan(pkg, cls, force = false)
                    schedulePostAnimationRescan(pkg, cls)
                }
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (isLauncher) {
                    scheduleHierarchyScan(pkg, cls, force = false)
                    schedulePostAnimationRescan(pkg, cls)
                } else if (pkg == "com.google.android.googlequicksearchbox" &&
                    LauncherGestureBus.isAutoClickGoogleLensPending()
                ) {
                    scanExecutor.execute { tryClickGoogleAppLensButton() }
                }
            }
        }
    }

    private fun scheduleHierarchyScan(pkg: String, cls: String, force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastContentScanMs < 140L) return
        if (!scanInFlight.compareAndSet(false, true)) return
        lastContentScanMs = now
        try {
            scanExecutor.execute {
                try {
                    inspectLauncherHierarchy(pkg, cls)
                } catch (_: Throwable) {
                } finally {
                    scanInFlight.set(false)
                }
            }
        } catch (_: Throwable) {
            scanInFlight.set(false)
        }
    }

    private fun schedulePostAnimationRescan(pkg: String, cls: String) {
        pendingRescanRunnable?.let { retryHandler.removeCallbacks(it) }
        val r = Runnable {
            if (AccessibilityForegroundState.foregroundPackage == pkg) {
                scheduleHierarchyScan(pkg, cls, force = true)
            }
        }
        pendingRescanRunnable = r
        retryHandler.postDelayed(r, 280L)
    }

    private fun scheduleLensAutoClickRetries() {
        scanExecutor.execute { tryClickGoogleAppLensButton() }
        for (delayMs in longArrayOf(220L, 550L, 1000L, 1500L)) {
            retryHandler.postDelayed({
                if (LauncherGestureBus.isAutoClickGoogleLensPending()) {
                    try {
                        scanExecutor.execute { tryClickGoogleAppLensButton() }
                    } catch (_: Throwable) {
                    }
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
            while (queue.isNotEmpty() && visited < 80) {
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

    private fun inspectLauncherHierarchy(pkg: String, cls: String) {
        var popupDetected = LauncherGestureBus.isLauncherPopupClassOrId(cls)
        var drawerDetected = LauncherGestureBus.isAppDrawerClass(cls)
        var minusOneDetected = false
        var floatingAppDetected = false
        var pageIndicatorText = ""
        val topTokens = ArrayList<String>(4)

        // Check active windows for:
        // 1. Other apps in Floating Window / Mini-Window / Freeform / PiP / Split-Screen mode
        // 2. Floating launcher popup menus (long-press shortcut menu)
        // Note: Overlay-style OEM Minus-One sub-windows (e.g. HiBoard type=2) are intentionally NOT
        // treated as hiding triggers here so desktop page swiping stays 100% rock-solid without flicker.
        try {
            val displayMetrics = resources.displayMetrics
            val screenW = displayMetrics.widthPixels
            val screenH = displayMetrics.heightPixels
            val screenArea = screenW.toLong() * screenH.toLong()
            val rect = Rect()
            for (win in windows) {
                val root = win.root ?: continue
                val winPkg = root.packageName?.toString() ?: ""
                val title = win.title?.toString() ?: ""
                if (win.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                    winPkg.isNotBlank() &&
                    winPkg !in cachedLaunchers &&
                    winPkg != packageName &&
                    winPkg != "com.android.systemui" &&
                    winPkg != "android" &&
                    winPkg != "com.vivo.hiboard" &&
                    !winPkg.contains("inputmethod", ignoreCase = true) &&
                    !winPkg.contains("livewallpaper", ignoreCase = true)
                ) {
                    win.getBoundsInScreen(rect)
                    // Visible floating / mini-window / freeform / PiP / split-screen app on screen
                    if (rect.width() >= 120 && rect.height() >= 120 &&
                        rect.right > 0 && rect.left < screenW &&
                        rect.bottom > 0 && rect.top < screenH
                    ) {
                        floatingAppDetected = true
                        break
                    }
                }
                if (winPkg == pkg) {
                    win.getBoundsInScreen(rect)
                    val winArea = rect.width().toLong() * rect.height().toLong()
                    if (winArea in 1L until (screenArea * 65L / 100L)) {
                        if (LauncherGestureBus.isLauncherPopupClassOrId(title) ||
                            hasAnyVisibleTextNode(root, POPUP_QUERIES)
                        ) {
                            popupDetected = true
                        }
                    }
                }
                if (popupDetected) break
            }
        } catch (_: Throwable) {
        }

        val activeRoot = try {
            rootInActiveWindow
        } catch (_: Throwable) {
            null
        }

        // Fast native full-tree text check (NOT capped by BFS limit) with isVisibleToUser verification
        if (activeRoot != null) {
            if (!drawerDetected) {
                drawerDetected = hasAnyVisibleTextNode(activeRoot, DRAWER_QUERIES)
            }
            if (!popupDetected) {
                popupDetected = hasAnyVisibleTextNode(activeRoot, POPUP_QUERIES)
            }
            if (!minusOneDetected) {
                minusOneDetected = hasAnyVisibleTextNode(activeRoot, MINUS_ONE_QUERIES)
            }
        }

        // Only run lightweight BFS (max 35 nodes) for page indicator / signature when neither drawer nor popup is open
        if (activeRoot != null && !drawerDetected && !popupDetected) {
            try {
                val queue = ArrayDeque<AccessibilityNodeInfo>()
                queue.add(activeRoot)
                var visited = 0
                while (queue.isNotEmpty() && visited < 35) {
                    val node = queue.removeFirst()
                    visited++

                    val text = node.text?.toString()?.trim().orEmpty()
                    val desc = node.contentDescription?.toString()?.trim().orEmpty()
                    val viewId = node.viewIdResourceName?.trim().orEmpty()

                    if (!drawerDetected && LauncherGestureBus.isAppDrawerViewId(viewId)) {
                        drawerDetected = true
                        break
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
                        if (visited + queue.size < 45) {
                            queue.addLast(child)
                        }
                    }
                }
            } catch (_: Throwable) {
            }
        }

        val pageSig = when {
            pageIndicatorText.isNotEmpty() -> "$pkg|$pageIndicatorText"
            topTokens.isNotEmpty() -> "$pkg|${topTokens.joinToString(",")}"
            else -> "$pkg|$cls"
        }

        AccessibilityForegroundState.currentPageSignature = pageSig
        LauncherGestureBus.dispatchPageSnapshot(pkg, cls, pageSig)

        if (LauncherGestureBus.isMinusOnePackageOrClass(
                pkg,
                cls,
                prefs.customMinusOnePackage,
                prefs.customMinusOneSignature,
                pageSig,
                cachedLaunchers
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
        AccessibilityForegroundState.isFloatingAppOpen = floatingAppDetected

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

        val shouldShow = !popupDetected &&
            !drawerDetected &&
            !floatingAppDetected &&
            !AccessibilityForegroundState.isSystemOverlay
        LauncherGestureBus.dispatchForegroundVisibility(
            shouldShowHomeOverlay = shouldShow,
            isAppDrawer = drawerDetected,
            isPopupOrMenu = popupDetected
        )
    }

    private fun hasAnyVisibleTextNode(root: AccessibilityNodeInfo, queries: List<String>): Boolean {
        val dm = resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels
        val rect = Rect()
        for (q in queries) {
            val matches = try {
                root.findAccessibilityNodeInfosByText(q)
            } catch (_: Throwable) {
                null
            } ?: continue
            for (node in matches) {
                if (!node.isVisibleToUser) continue
                node.getBoundsInScreen(rect)
                // Strictly verify the node is actually ON-SCREEN (excludes offscreen ViewPager Minus-One sibling on Page 1!)
                if (rect.width() > 0 && rect.height() > 0 &&
                    rect.centerX() in 0 until screenW &&
                    rect.centerY() in 0 until screenH
                ) {
                    return true
                }
            }
        }
        return false
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
        found.addAll(prefs.extraLauncherPackages)
        cachedLaunchers = found
        lastLauncherRefreshMs = now
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        retryHandler.removeCallbacksAndMessages(null)
        scanExecutor.shutdownNow()
        AccessibilityForegroundState.isConnected = false
        AccessibilityForegroundState.isSystemOverlay = false
        AccessibilityForegroundState.isAppDrawerOpen = false
        AccessibilityForegroundState.isLauncherPopupOpen = false
        AccessibilityForegroundState.isMinusOneActive = false
        AccessibilityForegroundState.isFloatingAppOpen = false
        lastScrollYByWindow.clear()
        super.onDestroy()
    }

    companion object {
        private val DRAWER_QUERIES = listOf(
            "全部應用程式",
            "搜尋應用程式",
            "原子元件",
            "全部应用",
            "All apps"
        )
        private val POPUP_QUERIES = listOf(
            "解除安裝",
            "應用程式資訊",
            "圖示樣式",
            "卸载",
            "Uninstall"
        )
        private val MINUS_ONE_QUERIES = listOf(
            "全部早報",
            "全部早报"
        )
    }
}
