package com.alglucomon.android.overlay

import android.app.PendingIntent
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.alglucomon.android.MainActivity
import com.alglucomon.android.data.SettingsRepository

class OverlayTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        SettingsRepository.init(this)
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        SettingsRepository.init(this)

        if (!Settings.canDrawOverlays(this)) {
            val intent = MainActivity.createIntent(this, openSettings = true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pi = PendingIntent.getActivity(
                    this,
                    10,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                startActivityAndCollapse(pi)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
            return
        }

        if (OverlayService.isRunning) {
            SettingsRepository.update { it.copy(overlayEnabled = false) }
            OverlayService.stop(this)
        } else {
            SettingsRepository.update { it.copy(overlayEnabled = true) }
            OverlayService.start(this)
        }
        updateTileState()
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        tile.state = if (OverlayService.isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (OverlayService.isRunning) "Overlay On" else "Overlay Off"
        tile.updateTile()
    }
}
