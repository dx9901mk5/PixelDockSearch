package com.pixeldock.search.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.pixeldock.search.data.PrefsRepository
import com.pixeldock.search.service.OverlaySearchService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val prefs = PrefsRepository(context)
            if (prefs.serviceEnabled && prefs.autoStartOnBoot && Settings.canDrawOverlays(context)) {
                OverlaySearchService.start(context)
            }
        }
    }
}
