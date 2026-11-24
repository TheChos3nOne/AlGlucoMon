package com.alglucomon.android.overlay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.alglucomon.android.data.SettingsRepository

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        SettingsRepository.init(context)
        val s = SettingsRepository.settings.value
        if (s.startOnBoot && s.overlayEnabled && Settings.canDrawOverlays(context)) {
            OverlayService.start(context)
        }
    }
}
