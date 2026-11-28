package com.pixeldock.search.launcher

import android.app.Activity
import android.app.SearchManager
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.widget.Toast

/**
 * Transparent bridge activity that launches Google's Speech-to-Text Voice Input
 * (RecognizerIntent.ACTION_RECOGNIZE_SPEECH) directly—never triggering Gemini—and
 * immediately forwards the recognized query to Google App Search.
 */
class VoiceSearchBridgeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)

        // On Android 14-17, Google's Speech-to-Text lives in com.google.android.tts (GoogleTTSActivity),
        // not com.google.android.googlequicksearchbox! Try TTS first, then Google App, then system default.
        val googleTtsVoiceIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            setPackage("com.google.android.tts")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "請說出要搜尋的內容…")
        }

        val googleAppVoiceIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            setPackage(GoogleIntentLauncher.GOOGLE_APP_PACKAGE)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "請說出要搜尋的內容…")
        }

        val fallbackVoiceIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "請說出要搜尋的內容…")
        }

        val launched = listOf(googleTtsVoiceIntent, googleAppVoiceIntent, fallbackVoiceIntent).any { intent ->
            runCatching {
                @Suppress("DEPRECATION")
                startActivityForResult(intent, REQ_VOICE_SEARCH)
                true
            }.getOrDefault(false)
        }

        if (!launched) {
            Toast.makeText(this, "找不到 Google 語音輸入服務", Toast.LENGTH_SHORT).show()
            finishAndRemoveTask()
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VOICE_SEARCH && resultCode == RESULT_OK) {
            val matches = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val query = matches?.firstOrNull()?.trim().orEmpty()
            if (query.isNotEmpty()) {
                val searchIntent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                    setPackage(GoogleIntentLauncher.GOOGLE_APP_PACKAGE)
                    putExtra(SearchManager.QUERY, query)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                val started = runCatching {
                    startActivity(searchIntent)
                    true
                }.getOrDefault(false)
                if (!started) {
                    runCatching {
                        startActivity(
                            Intent(Intent.ACTION_WEB_SEARCH).apply {
                                putExtra(SearchManager.QUERY, query)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                        )
                    }
                }
            }
        }
        finishAndRemoveTask()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    companion object {
        private const val REQ_VOICE_SEARCH = 4901
    }
}
