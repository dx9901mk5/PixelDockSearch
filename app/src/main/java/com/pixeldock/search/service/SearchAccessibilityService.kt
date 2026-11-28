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
                    pkg == "com.vivo.hiboard" ||
                    pkg.contains("inputmethod", ignoreCase = true) ||
                    pkg.contains("screenrecorder", ignoreCase = true) ||
                    pkg.contains("smartshot", ignoreCase = true) ||
                    pkg.contains("floatingball", ignoreCase = true) ||
                    pkg.contains("originisland", ignoreCase = true) ||
                    pkg.contains("daemonservice", ignoreCase = true) ||
                    pkg.contains("livewallpaper", ignoreCase = true) ||
                    pkg.contains("upslide", ignoreCase = true) ||
                    pkg.contains("fanrecent", ignoreCase = true) ||
                    pkg.contains("pixelvoiceime", ignoreCase = true)
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

        val activeRoot = try {
            rootInActiveWindow
        } catch (_: Throwable) {
            null
        }
        // If activeRoot is momentarily null mid-animation while still on launcher, do NOT flip visibility!
        if (activeRoot == null) {
            return
        }

        // Check active windows for:
        // 1. Other apps in Floating Window / Mini-Window / Freeform / PiP / Split-Screen mode (>= 25% W x 20% H)
        // 2. Floating launcher popup menus (must contain real popup text like Uninstall/App Info; never Taskbar/DockPanel)
        try {
            val displayMetrics = resources.displayMetrics
            val screenW = displayMetrics.widthPixels
            val screenH = displayMetrics.heightPixels
            val screenArea = screenW.toLong() * screenH.toLong()
            val minFloatW = (screenW * 0.25f).toInt().coerceAtLeast(180)
            val minFloatH = (screenH * 0.20f).toInt().coerceAtLeast(180)
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
                    !winPkg.contains("livewallpaper", ignoreCase = true) &&
                    !winPkg.contains("upslide", ignoreCase = true) &&
                    !winPkg.contains("fanrecent", ignoreCase = true) &&
                    !winPkg.contains("pixelvoiceime", ignoreCase = true)
                ) {
                    win.getBoundsInScreen(rect)
                    // Only real floating / mini-window / freeform / PiP / split-screen apps (never tiny side-edge tools)
                    if (rect.width() >= minFloatW && rect.height() >= minFloatH &&
                        rect.right > 0 && rect.left < screenW &&
                        rect.bottom > 0 && rect.top < screenH
                    ) {
                        floatingAppDetected = true
                        break
                    }
                }
                if (winPkg == pkg) {
                    if (title.contains("Taskbar", ignoreCase = true) ||
                        title.contains("DockPanel", ignoreCase = true)
                    ) {
                        continue
                    }
                    win.getBoundsInScreen(rect)
                    val winArea = rect.width().toLong() * rect.height().toLong()
                    if (winArea in 1L until (screenArea * 65L / 100L)) {
                        if (hasAnyVisibleTextNode(root, POPUP_QUERIES)) {
                            popupDetected = true
                        }
                    }
                }
                if (popupDetected) break
            }
        } catch (_: Throwable) {
        }

        // Fast native full-tree text check (NOT capped by BFS limit) with strict on-screen bounds verification
        if (!drawerDetected) {
            drawerDetected = hasAnyVisibleTextNode(activeRoot, DRAWER_QUERIES)
        }
        if (!popupDetected) {
            popupDetected = hasAnyVisibleTextNode(activeRoot, POPUP_QUERIES)
        }
        if (!minusOneDetected) {
            minusOneDetected = hasAnyVisibleTextNode(activeRoot, MINUS_ONE_QUERIES)
        }

        var detectedPageIndex = -1
        // Scan up to 140 nodes strictly filtering ON-SCREEN elements for accurate Page 1 vs Page 2/3 detection
        if (!drawerDetected && !popupDetected) {
            try {
                val dm = resources.displayMetrics
                val screenW = dm.widthPixels
                val screenH = dm.heightPixels
                val nodeRect = Rect()
                val queue = ArrayDeque<AccessibilityNodeInfo>()
                queue.add(activeRoot)
                var visited = 0
                while (queue.isNotEmpty() && visited < 140) {
                    val node = queue.removeFirst()
                    visited++

                    val text = node.text?.toString()?.trim().orEmpty()
                    val desc = node.contentDescription?.toString()?.trim().orEmpty()
                    val viewId = node.viewIdResourceName?.trim().orEmpty()

                    if (!drawerDetected && LauncherGestureBus.isAppDrawerViewId(viewId)) {
                        drawerDetected = true
                        break
                    }

                    // Detect page indicator text anywhere in the tree (e.g. "第 1 頁，共 5 頁", "Page 2 of 5", "1/5")
                    if (pageIndicatorText.isEmpty() && desc.isNotEmpty() &&
                        (desc.contains("頁") || desc.contains("页") ||
                            desc.contains("主螢幕") || desc.contains("主屏幕") ||
                            desc.contains("Home screen", ignoreCase = true) ||
                            desc.contains("Page ", ignoreCase = true))
                    ) {
                        pageIndicatorText = desc
                        detectedPageIndex = parsePageIndexFromText(desc)
                    }

                    // Detect page indicator dot row near bottom of screen (child index of selected dot = 0-based page)
                    if (detectedPageIndex < 0 && node.childCount in 2..12) {
                        node.getBoundsInScreen(nodeRect)
                        if (nodeRect.top > (screenH * 0.55f) && nodeRect.height() in 6..(screenH / 10)) {
                            var selectedIdx = -1
                            var selectedCount = 0
                            for (i in 0 until node.childCount) {
                                val c = node.getChild(i) ?: continue
                                if (c.isSelected || c.isChecked) {
                                    selectedIdx = i
                                    selectedCount++
                                }
                            }
                            if (selectedCount == 1 && selectedIdx >= 0) {
                                detectedPageIndex = selectedIdx
                            }
                        }
                    }

                    // Only include strictly ON-SCREEN icon/widget labels in topTokens so Page 1 & Page 2 have distinct signatures!
                    if (topTokens.size < 4 && text.length in 2..16 && !text.all { it.isDigit() || it == ':' } && node.isVisibleToUser) {
                        node.getBoundsInScreen(nodeRect)
                        if (nodeRect.width() > 0 && nodeRect.height() > 0 &&
                            nodeRect.centerX() in 0 until screenW &&
                            nodeRect.centerY() in 0 until (screenH * 0.82f).toInt()
                        ) {
                            topTokens.add(text)
                        }
                    }

                    val childCount = node.childCount
                    for (i in 0 until childCount) {
                        val child = node.getChild(i) ?: continue
                        if (visited + queue.size < 160) {
                            queue.addLast(child)
                        }
                    }
                }
            } catch (_: Throwable) {
            }
        }

        val pageSig = when {
            pageIndicatorText.isNotEmpty() -> "$pkg|$pageIndicatorText"
            detectedPageIndex >= 0 -> "$pkg|page_${detectedPageIndex + 1}"
            topTokens.isNotEmpty() -> "$pkg|${topTokens.joinToString(",")}"
            else -> "$pkg|$cls"
        }

        AccessibilityForegroundState.currentHomePageIndex = detectedPageIndex
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
        AccessibilityForegroundState.isOnConfirmedFirstPage = when {
            minusOneDetected -> false
            confirmedFirstSig.isNotBlank() && !confirmedFirstSig.endsWith("|") -> pageSig == confirmedFirstSig
            detectedPageIndex >= 0 -> detectedPageIndex == 0
            else -> true
        }

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

    private fun parsePageIndexFromText(desc: String): Int {
        val patterns = listOf(
            Regex("第\\s*(\\d+)\\s*[頁页]"),
            Regex("Page\\s+(\\d+)", RegexOption.IGNORE_CASE),
            Regex("\\b(\\d+)\\s*/\\s*\\d+"),
            Regex("\\b(\\d+)\\s+of\\s+\\d+", RegexOption.IGNORE_CASE)
        )
        for (p in patterns) {
            val m = p.find(desc)
            val num = m?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (num != null && num >= 1) return num - 1
        }
        return -1
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
