package com.pixeldock.search.launcher

import android.app.ActivityOptions
import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

object GoogleIntentLauncher {

    const val GOOGLE_APP_PACKAGE = "com.google.android.googlequicksearchbox"
    private const val LENS_PACKAGE = "com.google.ar.lens"

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
        val noAnimBundle = ActivityOptions.makeCustomAnimation(context, 0, 0).toBundle()
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

    fun launchHomeNoAnim(context: Context): Boolean {
        return try {
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            }
            val noAnimBundle = ActivityOptions.makeCustomAnimation(context, 0, 0).toBundle()
            context.startActivity(homeIntent, noAnimBundle)
            true
        } catch (_: Throwable) {
            false
        }
    }

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

    fun launchGeminiVoice(context: Context): Boolean {
        val candidates = listOf(
            Intent(Intent.ACTION_VOICE_COMMAND),
            Intent(Intent.ACTION_ASSIST),
            context.packageManager.getLaunchIntentForPackage("com.google.android.apps.bard")
        )
        if (tryStartAny(context, candidates)) {
            return true
        }
        Toast.makeText(context, "找不到 Gemini 語音助理，請確認已安裝 Gemini 或 Google App", Toast.LENGTH_SHORT).show()
        return false
    }

    fun isStandaloneLensInstalled(context: Context): Boolean {
        return runCatching {
            context.packageManager.getLaunchIntentForPackage(LENS_PACKAGE) != null
        }.getOrDefault(false)
    }

    fun launchStandaloneLens(context: Context): Boolean {
        val candidates = listOf(
            context.packageManager.getLaunchIntentForPackage(LENS_PACKAGE),
            Intent(Intent.ACTION_MAIN).apply {
                component = ComponentName(
                    LENS_PACKAGE,
                    "com.google.vr.apps.ornament.app.lens.LensLauncherActivity"
                )
            }
        )
        return tryStartAny(context, candidates)
    }

    fun openLensPlayStore(context: Context) {
        Toast.makeText(context, "未安裝 Google 智慧鏡頭 App，正在前往 Play 商店…", Toast.LENGTH_SHORT).show()
        val candidates = listOf(
            Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$LENS_PACKAGE")).apply {
                setPackage("com.android.vending")
            },
            Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$LENS_PACKAGE")),
            Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$LENS_PACKAGE"))
        )
        if (!tryStartAny(context, candidates)) {
            Toast.makeText(context, "請前往 Google Play 商店搜尋安裝「Google 智慧鏡頭」", Toast.LENGTH_LONG).show()
        }
    }

    fun launchVoiceSearch(context: Context) {
        launchGeminiVoice(context)
    }

    fun launchGoogleLens(context: Context): Boolean {
        if (isStandaloneLensInstalled(context)) {
            return launchStandaloneLens(context)
        }
        openLensPlayStore(context)
        return true
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
