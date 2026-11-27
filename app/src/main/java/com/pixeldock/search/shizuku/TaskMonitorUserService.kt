package com.pixeldock.search.shizuku

import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import kotlin.system.exitProcess

class TaskMonitorUserService : ITaskMonitorService.Stub() {

    private val componentPattern = Pattern.compile("([a-zA-Z0-9_]+(?:\\.[a-zA-Z0-9_]+)+)/")

    override fun getForegroundPackage(): String {
        var windowOutput = runShell(
            "dumpsys window displays 2>/dev/null | grep -E 'mCurrentFocus|mFocusedApp'"
        )
        if (windowOutput.isBlank()) {
            windowOutput = runShell(
                "dumpsys window windows 2>/dev/null | grep -E 'mCurrentFocus|mFocusedApp'"
            )
        }
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

    override fun launchGoogleAppLens(): Boolean {
        val commands = listOf(
            "am start -n com.google.android.googlequicksearchbox/com.google.android.apps.lens.MainActivity",
            "am start -a android.intent.action.VIEW -n com.google.android.googlequicksearchbox/com.google.android.apps.search.lens.LensActivity",
            "am start -a android.intent.action.VIEW -n com.google.android.googlequicksearchbox/com.google.android.apps.search.lens.LensExportedActivity",
            "am start -a android.intent.action.VIEW -d 'google://lens' com.google.android.googlequicksearchbox",
            "am start -a android.intent.action.VIEW -d 'googleapp://lens' com.google.android.googlequicksearchbox"
        )
        for (cmd in commands) {
            val out = runShell(cmd)
            if (out.contains("Starting:", ignoreCase = true) &&
                !out.contains("Error", ignoreCase = true) &&
                !out.contains("Exception", ignoreCase = true)
            ) {
                return true
            }
        }
        // Dynamic discovery inside Google App package for any Lens activity name
        val discovered = runShell(
            "dumpsys package com.google.android.googlequicksearchbox 2>/dev/null | grep -oE 'com\\.google\\.android\\.googlequicksearchbox/[a-zA-Z0-9._$]*[Ll]ens[a-zA-Z0-9._$]*Activity' | head -n 5"
        )
        for (comp in discovered.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.distinct()) {
            val out = runShell("am start -a android.intent.action.VIEW -n $comp")
            if (out.contains("Starting:", ignoreCase = true) &&
                !out.contains("Error", ignoreCase = true) &&
                !out.contains("Exception", ignoreCase = true)
            ) {
                return true
            }
        }
        return false
    }

    override fun destroy() {
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
            process.waitFor(1500, TimeUnit.MILLISECONDS)
            sb.toString()
        } catch (_: Throwable) {
            ""
        }
    }

    companion object {
        const val SYSTEM_UI_OVERLAY = "SYSTEM_UI_OVERLAY"
    }
}
