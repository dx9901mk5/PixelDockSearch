package com.pixeldock.search.launcher

import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

object GoogleIntentLauncher {

    const val GOOGLE_APP_PACKAGE = "com.google.android.googlequicksearchbox"
    private const val LENS_PACKAGE = "com.google.ar.lens"

    /**
     * Left G icon: directly opens the Google App itself.
     */
    fun launchGoogleApp(context: Context) {
        val candidates = listOf(
            context.packageManager.getLaunchIntentForPackage(GOOGLE_APP_PACKAGE),
            Intent(Intent.ACTION_MAIN).apply {
                component = ComponentName(
                    GOOGLE_APP_PACKAGE,
                    "com.google.android.googlequicksearchbox.SearchActivity"
                )
            }
        )
        if (!tryStartAny(context, candidates)) {
            Toast.makeText(context, "找不到 Google 應用程式", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Launches the native Google App with ZERO transition animation so our sliding Feed card
     * can cross-fade seamlessly right into the real live Google App underneath.
     */
    fun launchGoogleAppNoAnim(context: Context): Boolean {
        val candidates = listOf(
            context.packageManager.getLaunchIntentForPackage(GOOGLE_APP_PACKAGE),
            Intent(Intent.ACTION_MAIN).apply {
                component = ComponentName(
                    GOOGLE_APP_PACKAGE,
                    "com.google.android.googlequicksearchbox.SearchActivity"
                )
            }
        )
        val noAnimBundle = android.app.ActivityOptions.makeCustomAnimation(context, 0, 0).toBundle()
        for (intent in candidates) {
            if (intent == null) continue
            try {
                intent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
                context.startActivity(intent, noAnimBundle)
                return true
            } catch (_: Throwable) {
            }
        }
        return false
    }

    /**
     * Middle of the search bar: opens Google text search directly.
     */
    fun launchGoogleSearch(context: Context) {
        val candidates = listOf(
            Intent("android.search.action.GLOBAL_SEARCH").apply {
                setPackage(GOOGLE_APP_PACKAGE)
            },
            Intent(Intent.ACTION_WEB_SEARCH).apply {
                setPackage(GOOGLE_APP_PACKAGE)
                putExtra(SearchManager.QUERY, "")
            },
            context.packageManager.getLaunchIntentForPackage(GOOGLE_APP_PACKAGE),
            Intent(Intent.ACTION_WEB_SEARCH)
        )
        if (!tryStartAny(context, candidates)) {
            Toast.makeText(context, "找不到 Google 搜尋應用程式", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Mic icon: opens Google Speech-to-Text Voice Input (NOT Gemini) and searches the spoken query in Google App.
     */
    fun launchVoiceSearch(context: Context) {
        val bridgeIntent = Intent(context, VoiceSearchBridgeActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        }
        val started = runCatching {
            context.startActivity(bridgeIntent)
            true
        }.getOrDefault(false)

        if (!started) {
            Toast.makeText(context, "找不到 Google 語音輸入服務", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Lens icon: opens Google App's built-in Google Lens using verified non-allowlisted entry points
     * (Shizuku launcher runs first in OverlaySearchService for internal LensActivity).
     */
    fun launchGoogleLens(context: Context): Boolean {
        val candidates = mutableListOf<Intent?>()
        candidates += Intent(Intent.ACTION_VIEW, Uri.parse("googlelens://")).apply {
            setPackage(GOOGLE_APP_PACKAGE)
        }
        candidates += Intent(Intent.ACTION_VIEW, Uri.parse("googlelens://"))
        candidates += context.packageManager.getLaunchIntentForPackage(LENS_PACKAGE)
        candidates += Intent(Intent.ACTION_MAIN).apply {
            component = ComponentName(
                LENS_PACKAGE,
                "com.google.vr.apps.ornament.app.lens.LensLauncherActivity"
            )
        }
        candidates += Intent(Intent.ACTION_VIEW).apply {
            component = ComponentName(
                GOOGLE_APP_PACKAGE,
                "com.google.android.apps.search.lens.LensActivity"
            )
        }

        // Check PackageManager for any genuinely exported non-allowlist Lens activity inside Google App
        runCatching {
            val pkgInfo = context.packageManager.getPackageInfo(
                GOOGLE_APP_PACKAGE,
                android.content.pm.PackageManager.GET_ACTIVITIES
            )
            pkgInfo.activities
                ?.filter {
                    it.exported &&
                        it.name.contains("Lens", ignoreCase = true) &&
                        !it.name.contains("Exported", ignoreCase = true)
                }
                ?.forEach { info ->
                    candidates += Intent(Intent.ACTION_VIEW).apply {
                        component = ComponentName(GOOGLE_APP_PACKAGE, info.name)
                    }
                }
        }

        for (intent in candidates) {
            if (intent == null) continue
            val resolvable = runCatching {
                context.packageManager.resolveActivity(intent, 0) != null
            }.getOrDefault(false)
            if (!resolvable) continue
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                context.startActivity(intent)
                return true
            } catch (_: Throwable) {
            }
        }
        return false
    }

    fun launchGoogleFeed(context: Context) {
        val candidates = listOf(
            Intent(Intent.ACTION_VIEW, Uri.parse("googleapp://discover")).apply {
                setPackage(GOOGLE_APP_PACKAGE)
            },
            context.packageManager.getLaunchIntentForPackage(GOOGLE_APP_PACKAGE),
            Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/"))
        )
        if (!tryStartAny(context, candidates)) {
            Toast.makeText(context, "找不到 Google App (探索 / Feed)", Toast.LENGTH_SHORT).show()
        }
    }

    private fun tryStartAny(context: Context, intents: List<Intent?>): Boolean {
        for (intent in intents) {
            if (intent == null) continue
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                context.startActivity(intent)
                return true
            } catch (_: Throwable) {
            }
        }
        return false
    }
}
