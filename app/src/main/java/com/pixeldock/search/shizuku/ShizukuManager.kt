package com.pixeldock.search.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.pixeldock.search.BuildConfig
import rikka.shizuku.Shizuku

class ShizukuManager(private val context: Context) {

    @Volatile
    var taskService: ITaskMonitorService? = null
        private set

    @Volatile
    private var isBinding = false

    private var onStateChanged: (() -> Unit)? = null

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(context.packageName, TaskMonitorUserService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("task_monitor")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            isBinding = false
            taskService = ITaskMonitorService.Stub.asInterface(service)
            onStateChanged?.invoke()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBinding = false
            taskService = null
            onStateChanged?.invoke()
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        if (hasPermission()) {
            bindServiceIfNeeded()
        }
        onStateChanged?.invoke()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        isBinding = false
        taskService = null
        onStateChanged?.invoke()
    }

    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == REQUEST_CODE_SHIZUKU && grantResult == PackageManager.PERMISSION_GRANTED) {
            bindServiceIfNeeded()
        }
        onStateChanged?.invoke()
    }

    fun start(listener: (() -> Unit)? = null) {
        onStateChanged = listener
        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionResultListener)
            if (isAvailable() && hasPermission()) {
                bindServiceIfNeeded()
            }
        } catch (_: Throwable) {
        }
    }

    fun stop() {
        onStateChanged = null
        try {
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
            Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        } catch (_: Throwable) {
        }
        unbindService()
    }

    fun isAvailable(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (_: Throwable) {
            false
        }
    }

    fun hasPermission(): Boolean {
        return try {
            if (!isAvailable()) return false
            if (Shizuku.isPreV11()) false
            else Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
    }

    fun requestPermission() {
        try {
            if (isAvailable() && !Shizuku.isPreV11()) {
                Shizuku.requestPermission(REQUEST_CODE_SHIZUKU)
            }
        } catch (_: Throwable) {
        }
    }

    fun bindServiceIfNeeded() {
        if (taskService != null || isBinding) return
        if (!isAvailable() || !hasPermission()) return
        try {
            isBinding = true
            Shizuku.bindUserService(userServiceArgs, serviceConnection)
        } catch (_: Throwable) {
            isBinding = false
        }
    }

    fun unbindService() {
        try {
            if (taskService != null || isBinding) {
                Shizuku.unbindUserService(userServiceArgs, serviceConnection, true)
            }
        } catch (_: Throwable) {
        } finally {
            isBinding = false
            taskService = null
        }
    }

    fun getForegroundPackage(): String? {
        val svc = taskService ?: run {
            bindServiceIfNeeded()
            return null
        }
        return try {
            svc.foregroundPackage
        } catch (_: Throwable) {
            taskService = null
            bindServiceIfNeeded()
            null
        }
    }

    fun grantUsageStatsToSelf(): Boolean {
        return try {
            taskService?.grantUsageStats(context.packageName) == true
        } catch (_: Throwable) {
            false
        }
    }

    fun launchGoogleAppLens(): Boolean {
        val svc = taskService ?: run {
            bindServiceIfNeeded()
            return false
        }
        return try {
            svc.launchGoogleAppLens()
        } catch (_: Throwable) {
            false
        }
    }

    fun prewarmGoogleApp(): Boolean {
        return try {
            taskService?.prewarmGoogleApp() == true
        } catch (_: Throwable) {
            false
        }
    }

    fun captureScreenToPath(outputPath: String): Boolean {
        return try {
            taskService?.captureScreenToPath(outputPath) == true
        } catch (_: Throwable) {
            false
        }
    }

    fun setTouchCallback(callback: ITouchEventCallback?) {
        try {
            taskService?.setTouchCallback(callback)
        } catch (_: Throwable) {
        }
    }

    companion object {
        private const val REQUEST_CODE_SHIZUKU = 1001
    }
}
