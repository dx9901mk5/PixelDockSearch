package com.pixeldock.search.detector

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArraySet

object LauncherGestureBus {

    interface Listener {
        fun onVerticalScroll(deltaY: Int)
        fun onAppDrawerStateChanged(isOpen: Boolean)
        fun onMinusOneScreenDetected(packageName: String)
        fun onForegroundVisibilityChanged(
            shouldShowHomeOverlay: Boolean,
            isAppDrawer: Boolean,
            isPopupOrMenu: Boolean
        )
        fun onCurrentPageSnapshotChanged(pkg: String, cls: String, pageSignature: String)
    }

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val listeners = CopyOnWriteArraySet<Listener>()

    private val minusOnePackages = setOf(
        "com.vivo.hiboard",
        "com.vivo.assistant",
        "com.coloros.assistantscreen",
        "com.heytap.assistantscreen",
        "com.oplus.assistantscreen",
        "com.miui.personalassistant",
        "com.mi.android.globalminusscreen",
        "com.samsung.android.app.spage",
        "com.hihonor.intelligent",
        "com.huawei.intelligent"
    )

    private val minusOneKeywords = listOf(
        "hiboard",
        "assistantscreen",
        "personalassistant",
        "minusscreen",
        "minusone"
    )

    private val drawerKeywords = listOf(
        "allapps",
        "appdrawer",
        "appsview",
        "applist",
        "all_apps",
        "drawer"
    )

    private val drawerNodeTexts = listOf(
        "全部應用程式",
        "全部应用",
        "原子元件",
        "原子组件",
        "搜尋應用程式",
        "搜索应用",
        "應用程式抽屜",
        "应用抽屉",
        "search apps",
        "all apps",
        "app drawer"
    )

    private val drawerViewIdKeywords = listOf(
        "all_apps",
        "allapps",
        "app_drawer",
        "drawer",
        "apps_list",
        "apps_view",
        "alphabet",
        "fast_scroller",
        "search_apps",
        "search_container_all_apps",
        "atom_widget"
    )

    private val launcherPopupTexts = listOf(
        "解除安裝",
        "卸载",
        "應用程式資訊",
        "应用信息",
        "圖示樣式",
        "图标样式",
        "app info",
        "uninstall"
    )

    private val launcherPopupKeywords = listOf(
        "popup",
        "deep_shortcut",
        "deepshortcut",
        "shortcut_popup",
        "action_menu",
        "actionmenu",
        "dialog",
        "folder_content",
        "foldercontent",
        "edit_mode",
        "drag_layer_popup",
        "menu"
    )

    fun register(listener: Listener) {
        listeners.add(listener)
    }

    fun unregister(listener: Listener) {
        listeners.remove(listener)
    }

    fun dispatchVerticalScroll(deltaY: Int) {
        if (listeners.isEmpty()) return
        mainHandler.post {
            for (listener in listeners) {
                listener.onVerticalScroll(deltaY)
            }
        }
    }

    fun dispatchAppDrawerState(isOpen: Boolean) {
        if (listeners.isEmpty()) return
        mainHandler.post {
            for (listener in listeners) {
                listener.onAppDrawerStateChanged(isOpen)
            }
        }
    }

    fun dispatchMinusOneDetected(packageName: String) {
        if (listeners.isEmpty()) return
        mainHandler.post {
            for (listener in listeners) {
                listener.onMinusOneScreenDetected(packageName)
            }
        }
    }

    fun dispatchForegroundVisibility(
        shouldShowHomeOverlay: Boolean,
        isAppDrawer: Boolean,
        isPopupOrMenu: Boolean
    ) {
        if (listeners.isEmpty()) return
        mainHandler.post {
            for (listener in listeners) {
                listener.onForegroundVisibilityChanged(shouldShowHomeOverlay, isAppDrawer, isPopupOrMenu)
            }
        }
    }

    fun dispatchPageSnapshot(pkg: String, cls: String, pageSignature: String) {
        if (listeners.isEmpty()) return
        mainHandler.post {
            for (listener in listeners) {
                listener.onCurrentPageSnapshotChanged(pkg, cls, pageSignature)
            }
        }
    }

    fun isMinusOnePackageOrClass(
        pkg: String,
        cls: String,
        customPkg: String = "",
        customSig: String = "",
        pageSig: String = ""
    ): Boolean {
        val p = pkg.lowercase()
        val c = cls.lowercase()
        if (customSig.isNotBlank() && pageSig.isNotBlank() && pageSig == customSig) {
            return true
        }
        if (customPkg.isNotBlank() && customSig.isBlank() && p == customPkg.lowercase()) {
            return true
        }
        if (p in minusOnePackages) return true
        return minusOneKeywords.any { kw -> p.contains(kw) || c.contains(kw) }
    }

    fun isAppDrawerClass(cls: String): Boolean {
        val c = cls.lowercase()
        return drawerKeywords.any { c.contains(it) }
    }

    fun isAppDrawerNodeText(text: String): Boolean {
        val t = text.trim().lowercase()
        if (t.isEmpty()) return false
        return drawerNodeTexts.any { t == it || t.contains(it) }
    }

    fun isAppDrawerViewId(id: String): Boolean {
        val lower = id.lowercase()
        return drawerViewIdKeywords.any { lower.contains(it) }
    }

    fun isLauncherPopupText(text: String): Boolean {
        val t = text.trim().lowercase()
        if (t.isEmpty()) return false
        return launcherPopupTexts.any { t == it || t.contains(it) }
    }

    fun isLauncherPopupClassOrId(name: String): Boolean {
        val lower = name.lowercase()
        return launcherPopupKeywords.any { lower.contains(it) }
    }
}
