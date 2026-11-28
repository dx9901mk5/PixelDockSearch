package com.pixeldock.search.shizuku

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import kotlin.math.abs
import kotlin.system.exitProcess

class TaskMonitorUserService : ITaskMonitorService.Stub() {

    private val componentPattern = Pattern.compile("([a-zA-Z0-9_]+(?:\\.[a-zA-Z0-9_]+)+)/")
    private val boundsPattern = Pattern.compile("bounds=\"\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]\"")
    private val hexEventPattern = Pattern.compile("0003\\s+(0035|0036|0039)\\s+([0-9a-fA-F]+)")
    private val keyTouchPattern = Pattern.compile("0001\\s+014a\\s+([0-9a-fA-F]+)")

    @Volatile
    private var touchCallback: ITouchEventCallback? = null

    @Volatile
    private var touchThreadRunning = false

    @Volatile
    private var geteventProcess: Process? = null

    override fun getForegroundPackage(): String {
        val windowOutput = getCurrentFocusOutput()
        if (windowOutput.contains("NotificationShade") ||
            windowOutput.contains("StatusBar") ||
            windowOutput.contains("Bouncer") ||
            windowOutput.contains("Keyguard") ||
            windowOutput.contains("Recents") ||
            windowOutput.contains("AllApps") ||
            windowOutput.contains("AppDrawer") ||
            windowOutput.contains("PopupWindow") ||
            windowOutput.contains("DeepShortcut") ||
            windowOutput.contains("ActionMenu") ||
            windowOutput.contains("Folder")
        ) {
            return SYSTEM_UI_OVERLAY
        }

        extractPackage(windowOutput, "mCurrentFocus")?.let { pkg ->
            if (pkg != "com.android.systemui") return pkg
        }
        extractPackage(windowOutput, "mFocusedApp")?.let { pkg ->
            if (pkg != "com.android.systemui") return pkg
        }

        val activityOutput = runShell(
            "dumpsys activity activities 2>/dev/null | grep -E 'topResumedActivity|mResumedActivity'"
        )
        extractPackage(activityOutput, "topResumedActivity")?.let { return it }
        extractPackage(activityOutput, "mResumedActivity")?.let { return it }

        return ""
    }

    override fun grantUsageStats(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        val safePkg = packageName.replace(Regex("[^a-zA-Z0-9._]"), "")
        if (safePkg.isEmpty()) return false
        return try {
            val process = ProcessBuilder("appops", "set", safePkg, "GET_USAGE_STATS", "allow")
                .redirectErrorStream(true)
                .start()
            val finished = process.waitFor(2, TimeUnit.SECONDS)
            finished && process.exitValue() == 0
        } catch (_: Throwable) {
            false
        }
    }

    override fun prewarmGoogleApp(): Boolean {
        return try {
            // Wake Google App process/services in background without stealing foreground focus
            val pid = runShell("pidof com.google.android.googlequicksearchbox:search").trim()
            if (pid.isNotEmpty()) return true
            runShell(
                "am start-service -n com.google.android.googlequicksearchbox/com.google.android.apps.gsa.search.core.service.SearchService 2>/dev/null"
            )
            true
        } catch (_: Throwable) {
            false
        }
    }

    override fun captureScreenToPath(outputPath: String?): Boolean {
        if (outputPath.isNullOrBlank()) return false
        val safePath = outputPath.replace("'", "")
        return try {
            val process = ProcessBuilder("sh", "-c", "screencap -p '$safePath'")
                .redirectErrorStream(true)
                .start()
            val ok = process.waitFor(3, TimeUnit.SECONDS) && process.exitValue() == 0
            if (ok) {
                File(safePath).exists() && File(safePath).length() > 1024L
            } else {
                false
            }
        } catch (_: Throwable) {
            false
        }
    }

    override fun captureScreenJpeg(maxWidth: Int, quality: Int): ByteArray? {
        val tmpPath = "/data/local/tmp/pds_snap.png"
        return try {
            val process = ProcessBuilder("sh", "-c", "screencap -p $tmpPath")
                .redirectErrorStream(true)
                .start()
            val ok = process.waitFor(3, TimeUnit.SECONDS) && process.exitValue() == 0
            val f = File(tmpPath)
            if (!ok || !f.exists() || f.length() < 1024L) return null

            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(tmpPath, boundsOpts)
            val rawW = boundsOpts.outWidth
            if (rawW <= 0) return null

            val targetW = maxWidth.coerceIn(240, 720)
            var sample = 1
            while (rawW / (sample * 2) >= targetW) {
                sample *= 2
            }
            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val bmp = BitmapFactory.decodeFile(tmpPath, decodeOpts) ?: return null
            val baos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(50, 90), baos)
            bmp.recycle()
            runCatching { f.delete() }
            val bytes = baos.toByteArray()
            if (bytes.size in 1024..600_000) bytes else null
        } catch (_: Throwable) {
            null
        }
    }

    override fun isLauncherDrawerOrPopupVisible(): Boolean {
        return try {
            // Check visible App Drawer / Popup views in the top Activity hierarchy (works even when Accessibility is OFF)
            val out = runShell(
                "dumpsys activity top 2>/dev/null | grep -E 'V\\.E\\.|V\\.\\.\\.' | grep -iE 'AllApps|AppDrawer|DrawerView|AppsList|AtomWidget|PopupContainer|DeepShortcut|ActionMenu|全部應用程式|搜尋應用程式|建議的應用程式|原子元件|全部应用|搜索应用' | head -n 4"
            )
            out.isNotBlank()
        } catch (_: Throwable) {
            false
        }
    }

    override fun ensureAccessibilityServiceEnabled(componentName: String?): Boolean {
        if (componentName.isNullOrBlank()) return false
        val safeComp = componentName.replace(Regex("[^a-zA-Z0-9._/$]"), "")
        if (safeComp.isEmpty()) return false
        return try {
            val currentRaw = runShell("settings get secure enabled_accessibility_services").trim()
            val cleaned = if (currentRaw == "null") "" else currentRaw
            val parts = cleaned.split(":").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
            if (safeComp !in parts) {
                parts.add(safeComp)
            }
            val joined = parts.joinToString(":")
            runShell("settings put secure enabled_accessibility_services '$joined'")
            runShell("settings put secure accessibility_enabled 1")
            true
        } catch (_: Throwable) {
            false
        }
    }

    override fun launchGoogleAppLens(): Boolean {
        // Fast direct Lens activity / deep-link launch (zero sleep / zero uiautomator hang).
        val directCommands = listOf(
            "am start -a android.intent.action.VIEW -d 'googleapp://lens' com.google.android.googlequicksearchbox",
            "am start -a com.google.android.googlequicksearchbox.LENS com.google.android.googlequicksearchbox",
            "am start -n com.google.android.googlequicksearchbox/com.google.android.apps.search.lens.LensExportedActivity",
            "am start -n com.google.android.googlequicksearchbox/com.google.android.apps.search.lens.LensActivity",
            "am start -a android.intent.action.VIEW -d 'googlelens://' com.google.android.googlequicksearchbox",
            "am start -n com.google.android.googlequicksearchbox/com.google.android.apps.lens.MainActivity"
        )
        for (cmd in directCommands) {
            val out = runShell(cmd)
            if (isAmStartSuccess(out)) {
                return true
            }
        }
        return false
    }

    private fun findLensButtonCenter(xml: String): Pair<Int, Int>? {
        if (xml.isBlank()) return null
        val keywords = listOf(
            "智慧鏡頭",
            "Google 鏡頭",
            "圖片搜尋",
            "相機搜尋",
            "以圖搜尋",
            "智慧镜头",
            "拍照搜",
            "search with your camera",
            "google lens",
            "lens_icon",
            "lens_button",
            "lens"
        )
        val nodeChunks = xml.split("<node ")
        for (chunk in nodeChunks) {
            val lower = chunk.lowercase()
            if (keywords.any { lower.contains(it.lowercase()) }) {
                val m = boundsPattern.matcher(chunk)
                if (m.find()) {
                    val x1 = m.group(1)?.toIntOrNull() ?: continue
                    val y1 = m.group(2)?.toIntOrNull() ?: continue
                    val x2 = m.group(3)?.toIntOrNull() ?: continue
                    val y2 = m.group(4)?.toIntOrNull() ?: continue
                    val w = x2 - x1
                    val h = y2 - y1
                    // Filter out full-screen containers; target actual icon bounds
                    if (w in 20..360 && h in 20..360) {
                        return Pair((x1 + x2) / 2, (y1 + y2) / 2)
                    }
                }
            }
        }
        return null
    }

    private fun didLensFocusOpen(): Boolean {
        return try {
            Thread.sleep(280L)
            val focus = getCurrentFocusOutput()
            focus.contains("Lens", ignoreCase = true) || focus.contains("com.google.ar.lens", ignoreCase = true)
        } catch (_: Throwable) {
            false
        }
    }

    private fun getCurrentFocusOutput(): String {
        var windowOutput = runShell(
            "dumpsys window displays 2>/dev/null | grep -E 'mCurrentFocus|mFocusedApp'"
        )
        if (windowOutput.isBlank()) {
            windowOutput = runShell(
                "dumpsys window windows 2>/dev/null | grep -E 'mCurrentFocus|mFocusedApp'"
            )
        }
        return windowOutput
    }

    private fun isAmStartSuccess(out: String): Boolean {
        return out.contains("Starting:", ignoreCase = true) &&
            !out.contains("Error", ignoreCase = true) &&
            !out.contains("Exception", ignoreCase = true) &&
            !out.contains("does not exist", ignoreCase = true) &&
            !out.contains("unable to resolve", ignoreCase = true)
    }

    override fun setTouchCallback(callback: ITouchEventCallback?) {
        touchCallback = callback
        if (callback != null && !touchThreadRunning) {
            startGetEventThread()
        } else if (callback == null) {
            stopGetEventThread()
        }
    }

    private fun startGetEventThread() {
        touchThreadRunning = true
        Thread {
            try {
                // Calibrate raw touchscreen digitizer Y axis to real display pixels
                val yScale = computeTouchYScale()
                val proc = ProcessBuilder("sh", "-c", "getevent -q 2>/dev/null")
                    .redirectErrorStream(true)
                    .start()
                geteventProcess = proc

                var fingerDown = false
                var downX = -1
                var downY = -1
                var curX = -1
                var curY = -1
                var trackingUpward = false
                var lastSentPx = 0
                var lastMoveTimeMs = 0L
                var prevYForVel = -1
                var prevVelTimeMs = 0L
                var velocityPxPerSec = 0

                BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                    while (touchThreadRunning) {
                        val line = reader.readLine() ?: break
                        val cb = touchCallback ?: continue

                        val keyMatch = keyTouchPattern.matcher(line)
                        if (keyMatch.find()) {
                            val v = keyMatch.group(1)?.toIntOrNull(16) ?: 0
                            if (v == 1) {
                                fingerDown = true
                                downX = -1
                                downY = -1
                                trackingUpward = false
                                lastSentPx = 0
                                velocityPxPerSec = 0
                            } else if (v == 0 && fingerDown) {
                                if (trackingUpward && downY >= 0 && curY >= 0) {
                                    val upPx = ((downY - curY) * yScale).toInt().coerceAtLeast(0)
                                    runCatching { cb.onUpwardDragEnd(upPx, velocityPxPerSec) }
                                }
                                fingerDown = false
                                trackingUpward = false
                                downX = -1
                                downY = -1
                            }
                            continue
                        }

                        val evMatch = hexEventPattern.matcher(line)
                        if (evMatch.find()) {
                            val code = evMatch.group(1)
                            val rawVal = evMatch.group(2)?.toLongOrNull(16) ?: continue
                            when (code) {
                                "0039" -> { // ABS_MT_TRACKING_ID
                                    if (rawVal == 0xffffffffL) {
                                        if (trackingUpward && downY >= 0 && curY >= 0) {
                                            val upPx = ((downY - curY) * yScale).toInt().coerceAtLeast(0)
                                            runCatching { cb.onUpwardDragEnd(upPx, velocityPxPerSec) }
                                        }
                                        fingerDown = false
                                        trackingUpward = false
                                        downX = -1
                                        downY = -1
                                    } else {
                                        fingerDown = true
                                        downX = -1
                                        downY = -1
                                        trackingUpward = false
                                        lastSentPx = 0
                                        velocityPxPerSec = 0
                                    }
                                }
                                "0035" -> { // ABS_MT_POSITION_X
                                    val x = rawVal.toInt()
                                    if (fingerDown) {
                                        if (downX < 0) downX = x
                                        curX = x
                                    }
                                }
                                "0036" -> { // ABS_MT_POSITION_Y
                                    val y = rawVal.toInt()
                                    if (fingerDown) {
                                        val now = System.currentTimeMillis()
                                        if (downY < 0) {
                                            downY = y
                                            prevYForVel = y
                                            prevVelTimeMs = now
                                        }
                                        curY = y
                                        val dyUp = ((downY - curY) * yScale).toInt()
                                        val dxAbs = if (downX >= 0 && curX >= 0) {
                                            (abs(curX - downX) * yScale).toInt()
                                        } else {
                                            0
                                        }

                                        val dt = now - prevVelTimeMs
                                        if (dt >= 16L) {
                                            velocityPxPerSec = ((((prevYForVel - curY) * yScale) * 1000f) / dt).toInt()
                                            prevYForVel = curY
                                            prevVelTimeMs = now
                                        }

                                        // Only engage when clearly swiping UPWARD (not horizontal page swipe!)
                                        if (!trackingUpward && dyUp > 24 && dyUp > dxAbs * 1.4f) {
                                            trackingUpward = true
                                        }
                                        if (trackingUpward && (now - lastMoveTimeMs >= 14L) && abs(dyUp - lastSentPx) >= 4) {
                                            lastSentPx = dyUp
                                            lastMoveTimeMs = now
                                            runCatching { cb.onUpwardDrag(dyUp.coerceAtLeast(0)) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (_: Throwable) {
            } finally {
                touchThreadRunning = false
                try {
                    geteventProcess?.destroy()
                } catch (_: Throwable) {
                }
                geteventProcess = null
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    private fun computeTouchYScale(): Float {
        return try {
            val sizeOut = runShell("wm size 2>/dev/null")
            val sizeMatch = Pattern.compile("(\\d+)x(\\d+)").matcher(sizeOut)
            var screenH = 0
            while (sizeMatch.find()) {
                val h = sizeMatch.group(2)?.toIntOrNull() ?: 0
                if (h > 0) screenH = h
            }
            val propsOut = runShell("getevent -p 2>/dev/null | grep -E '0036.*max [0-9]+'")
            val maxMatch = Pattern.compile("max\\s+(\\d+)").matcher(propsOut)
            val maxY = if (maxMatch.find()) maxMatch.group(1)?.toIntOrNull() ?: 0 else 0
            if (screenH > 0 && maxY > 0) {
                (screenH.toFloat() / maxY.toFloat()).coerceIn(0.05f, 4f)
            } else {
                1f
            }
        } catch (_: Throwable) {
            1f
        }
    }

    private fun stopGetEventThread() {
        touchThreadRunning = false
        try {
            geteventProcess?.destroy()
        } catch (_: Throwable) {
        }
        geteventProcess = null
    }

    override fun destroy() {
        stopGetEventThread()
        exitProcess(0)
    }

    private fun extractPackage(output: String, marker: String): String? {
        if (output.isBlank()) return null
        for (line in output.lineSequence()) {
            if (!line.contains(marker)) continue
            val matcher = componentPattern.matcher(line)
            if (matcher.find()) {
                return matcher.group(1)
            }
        }
        return null
    }

    private fun runShell(command: String): String {
        return try {
            val process = ProcessBuilder("sh", "-c", command)
                .redirectErrorStream(true)
                .start()
            val sb = StringBuilder()
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                var line = reader.readLine()
                while (line != null) {
                    sb.appendLine(line)
                    line = reader.readLine()
                }
            }
            process.waitFor(2000, TimeUnit.MILLISECONDS)
            sb.toString()
        } catch (_: Throwable) {
            ""
        }
    }

    companion object {
        const val SYSTEM_UI_OVERLAY = "SYSTEM_UI_OVERLAY"
    }
}
