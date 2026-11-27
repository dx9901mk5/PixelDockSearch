package com.pixeldock.search.updater

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object GitHubUpdateManager {

    const val REPO_OWNER = "dx9901mk5"
    const val REPO_NAME = "PixelDockSearch"
    const val REPO_URL = "https://github.com/$REPO_OWNER/$REPO_NAME"
    private const val API_LATEST_URL = "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/latest"
    private const val WEB_LATEST_URL = "https://github.com/$REPO_OWNER/$REPO_NAME/releases/latest"

    data class ReleaseInfo(
        val versionName: String,
        val tagName: String,
        val releaseUrl: String,
        val apkUrl: String,
        val apkName: String,
        val apkSizeBytes: Long,
        val notes: String
    )

    fun checkLatestRelease(currentVersion: String): Result<ReleaseInfo?> {
        return runCatching {
            val info = fetchViaApi() ?: fetchViaWebRedirect()
            if (info != null && compareVersions(info.versionName, currentVersion) > 0) {
                info
            } else {
                null
            }
        }
    }

    private fun fetchViaApi(): ReleaseInfo? {
        val conn = (URL(API_LATEST_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "PixelDockSearch-Updater")
        }
        return try {
            val code = conn.responseCode
            if (code == 403 || code == 429) {
                return null
            }
            if (code !in 200..299) {
                throw IllegalStateException("GitHub API HTTP $code")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val tagName = json.optString("tag_name", "")
            val versionName = tagName.removePrefix("v").trim()
            val htmlUrl = json.optString("html_url", WEB_LATEST_URL)
            val notes = json.optString("body", "")
            val assets = json.optJSONArray("assets")

            var apkUrl = ""
            var apkName = "PixelDockSearch-$tagName.apk"
            var apkSize = 0L

            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i) ?: continue
                    val name = asset.optString("name", "")
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        apkName = name
                        apkUrl = asset.optString("browser_download_url", "")
                        apkSize = asset.optLong("size", 0L)
                        break
                    }
                }
            }
            if (apkUrl.isBlank() || versionName.isBlank()) return null

            ReleaseInfo(
                versionName = versionName,
                tagName = tagName,
                releaseUrl = htmlUrl,
                apkUrl = apkUrl,
                apkName = apkName,
                apkSizeBytes = apkSize,
                notes = notes
            )
        } finally {
            conn.disconnect()
        }
    }

    private fun fetchViaWebRedirect(): ReleaseInfo? {
        val conn = (URL(WEB_LATEST_URL).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("User-Agent", "PixelDockSearch-Updater")
        }
        return try {
            val location = conn.getHeaderField("Location") ?: return null
            val tag = location.substringAfterLast("/tag/", "").trim()
            if (tag.isBlank()) return null
            val versionName = tag.removePrefix("v")
            val apkName = "PixelDockSearch-$tag.apk"
            val apkUrl = "https://github.com/$REPO_OWNER/$REPO_NAME/releases/download/$tag/$apkName"
            ReleaseInfo(
                versionName = versionName,
                tagName = tag,
                releaseUrl = location,
                apkUrl = apkUrl,
                apkName = apkName,
                apkSizeBytes = 0L,
                notes = ""
            )
        } finally {
            conn.disconnect()
        }
    }

    fun downloadApk(
        context: Context,
        release: ReleaseInfo,
        onProgress: (downloadedBytes: Long, totalBytes: Long, percent: Int) -> Unit
    ): Result<File> {
        return runCatching {
            val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
            updatesDir.listFiles()?.forEach { old ->
                if (old.isFile && old.name != release.apkName) old.delete()
            }
            val targetFile = File(updatesDir, release.apkName)
            val tempFile = File(updatesDir, "${release.apkName}.part")

            var currentUrl = release.apkUrl
            var conn: HttpURLConnection? = null
            for (hop in 0 until 5) {
                val c = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 12000
                    readTimeout = 30000
                    setRequestProperty("User-Agent", "PixelDockSearch-Updater")
                }
                val code = c.responseCode
                if (code in 300..399) {
                    val next = c.getHeaderField("Location")
                    c.disconnect()
                    if (next.isNullOrBlank()) throw IllegalStateException("Redirect without Location")
                    currentUrl = next
                } else {
                    conn = c
                    break
                }
            }
            val activeConn = conn ?: throw IllegalStateException("Too many redirects")

            try {
                val code = activeConn.responseCode
                if (code !in 200..299) {
                    throw IllegalStateException("Download failed HTTP $code")
                }
                val totalBytes = activeConn.contentLengthLong.takeIf { it > 0 } ?: release.apkSizeBytes
                var downloaded = 0L
                var lastPct = -1

                activeConn.inputStream.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            val pct = if (totalBytes > 0) {
                                ((downloaded * 100L) / totalBytes).toInt().coerceIn(0, 100)
                            } else {
                                -1
                            }
                            if (pct != lastPct || downloaded == totalBytes) {
                                lastPct = pct
                                onProgress(downloaded, totalBytes, pct)
                            }
                        }
                    }
                }
                if (targetFile.exists()) targetFile.delete()
                if (!tempFile.renameTo(targetFile)) {
                    tempFile.copyTo(targetFile, overwrite = true)
                    tempFile.delete()
                }
                targetFile
            } finally {
                activeConn.disconnect()
            }
        }
    }

    fun installApk(context: Context, apkFile: File): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(installIntent)
        return true
    }

    fun compareVersions(remote: String, local: String): Int {
        val rParts = remote.removePrefix("v").split(".", "-").mapNotNull { it.toIntOrNull() }
        val lParts = local.removePrefix("v").split(".", "-").mapNotNull { it.toIntOrNull() }
        val maxLen = maxOf(rParts.size, lParts.size)
        for (i in 0 until maxLen) {
            val r = rParts.getOrElse(i) { 0 }
            val l = lParts.getOrElse(i) { 0 }
            if (r != l) return r.compareTo(l)
        }
        return 0
    }
}
