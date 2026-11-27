package com.pixeldock.search.detector

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArraySet

object LauncherGestureBus {

    interface Listener {
        fun onVerticalScroll(deltaY: Int)
        fun onAppDrawerStateChanged(isOpen: Boolean)
        fun onMinusOneScreenDetected(packageName: String)
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

    fun isMinusOnePackageOrClass(pkg: String, cls: String): Boolean {
        val p = pkg.lowercase()
        val c = cls.lowercase()
        if (p in minusOnePackages) return true
        return minusOneKeywords.any { kw -> p.contains(kw) || c.contains(kw) }
    }

    fun isAppDrawerClass(cls: String): Boolean {
        val c = cls.lowercase()
        return drawerKeywords.any { c.contains(it) }
    }
}
