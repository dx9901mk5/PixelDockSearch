package com.pixeldock.search.launcher

import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.speech.RecognizerIntent
import android.widget.Toast

object GoogleIntentLauncher {

    private const val GOOGLE_APP_PACKAGE = "com.google.android.googlequicksearchbox"
    private const val LENS_PACKAGE = "com.google.ar.lens"

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

    fun launchVoiceSearch(context: Context) {
        val candidates = listOf(
            Intent("android.speech.action.WEB_SEARCH").apply {
                setPackage(GOOGLE_APP_PACKAGE)
            },
            Intent(RecognizerIntent.ACTION_VOICE_SEARCH_HANDS_FREE).apply {
                setPackage(GOOGLE_APP_PACKAGE)
            },
            Intent(RecognizerIntent.ACTION_WEB_SEARCH),
            Intent(Intent.ACTION_VOICE_COMMAND)
        )
        if (!tryStartAny(context, candidates)) {
            Toast.makeText(context, "找不到 Google 語音搜尋", Toast.LENGTH_SHORT).show()
        }
    }

    fun launchGoogleLens(context: Context) {
        val candidates = listOf(
            Intent(Intent.ACTION_VIEW, Uri.parse("googleapp://lens")).apply {
                setPackage(GOOGLE_APP_PACKAGE)
            },
            context.packageManager.getLaunchIntentForPackage(LENS_PACKAGE),
            Intent(Intent.ACTION_MAIN).apply {
                component = ComponentName(LENS_PACKAGE, "com.google.android.apps.lens.MainActivity")
            },
            Intent(Intent.ACTION_VIEW, Uri.parse("https://lens.google.com"))
        )
        if (!tryStartAny(context, candidates)) {
            Toast.makeText(context, "找不到 Google 智慧鏡頭 (Lens)", Toast.LENGTH_SHORT).show()
        }
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
