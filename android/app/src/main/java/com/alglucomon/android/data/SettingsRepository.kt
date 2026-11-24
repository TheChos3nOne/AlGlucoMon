package com.alglucomon.android.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

object SettingsRepository {
    private const val PREFS_NAME = "alglucomon_prefs"
    private const val KEY_SETTINGS = "settings_json"

    private lateinit var prefs: SharedPreferences
    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _settings.value = loadFromPrefs()
    }

    @Synchronized
    fun update(transform: (AppSettings) -> AppSettings): AppSettings {
        val next = transform(_settings.value)
        val sanitized = if (!next.isSignedIn) next.copy(clickThrough = false) else next
        _settings.value = sanitized
        saveToPrefs(sanitized)
        return sanitized
    }

    private fun loadFromPrefs(): AppSettings {
        val raw = prefs.getString(KEY_SETTINGS, null) ?: return AppSettings()
        return try {
            val j = JSONObject(raw)
            val def = AppSettings()
            AppSettings(
                region = DexcomRegion.fromId(j.optString("region", def.region.id)),
                username = j.optString("username", def.username),
                password = j.optString("password", def.password),
                pollIntervalSec = j.optInt("pollIntervalSec", def.pollIntervalSec),
                showBackgroundGraph = j.optBoolean("showBackgroundGraph", def.showBackgroundGraph),
                graphHours = j.optInt("graphHours", def.graphHours).let {
                    if (it == 1 || it == 3 || it == 6) it else 3
                },
                graphOpacity = j.optDouble("graphOpacity", def.graphOpacity.toDouble()).toFloat().coerceIn(0.15f, 0.85f),
                showTargetBand = j.optBoolean("showTargetBand", def.showTargetBand),
                graphStyle = GraphStyle.fromId(j.optString("graphStyle", def.graphStyle.id)),
                unit = GlucoseUnit.fromId(j.optString("unit", def.unit.id)),
                urgentLowThreshold = j.optInt("urgentLowThreshold", def.urgentLowThreshold),
                lowThreshold = j.optInt("lowThreshold", def.lowThreshold),
                highThreshold = j.optInt("highThreshold", def.highThreshold),
                theme = AppTheme.fromId(j.optString("theme", def.theme.id)),
                cardOpacity = j.optDouble("cardOpacity", def.cardOpacity.toDouble()).toFloat().coerceIn(0.3f, 1.0f),
                showDelta = j.optBoolean("showDelta", def.showDelta),
                keepScreenOn = j.optBoolean("keepScreenOn", def.keepScreenOn),
                brightnessMode = BrightnessMode.fromId(j.optString("brightnessMode", def.brightnessMode.id)),
                manualBrightness = j.optDouble("manualBrightness", def.manualBrightness.toDouble()).toFloat().coerceIn(0.03f, 1.0f),
                nightAutoDim = j.optBoolean("nightAutoDim", def.nightAutoDim),
                burnInShift = j.optBoolean("burnInShift", def.burnInShift),
                showClock = j.optBoolean("showClock", def.showClock),
                overlayEnabled = j.optBoolean("overlayEnabled", def.overlayEnabled),
                overlaySize = OverlaySize.fromId(j.optString("overlaySize", def.overlaySize.id)),
                clickThrough = j.optBoolean("clickThrough", def.clickThrough),
                clickThroughOpacity = j.optDouble("clickThroughOpacity", def.clickThroughOpacity.toDouble()).toFloat().coerceIn(0.15f, 0.75f),
                startOnBoot = j.optBoolean("startOnBoot", def.startOnBoot),
                overlayPortraitX = j.optInt("overlayPortraitX", def.overlayPortraitX),
                overlayPortraitY = j.optInt("overlayPortraitY", def.overlayPortraitY),
                overlayLandscapeX = j.optInt("overlayLandscapeX", def.overlayLandscapeX),
                overlayLandscapeY = j.optInt("overlayLandscapeY", def.overlayLandscapeY),
            )
        } catch (_: Exception) {
            AppSettings()
        }
    }

    private fun saveToPrefs(s: AppSettings) {
        val j = JSONObject().apply {
            put("region", s.region.id)
            put("username", s.username)
            put("password", s.password)
            put("pollIntervalSec", s.pollIntervalSec)
            put("showBackgroundGraph", s.showBackgroundGraph)
            put("graphHours", s.graphHours)
            put("graphOpacity", s.graphOpacity.toDouble())
            put("showTargetBand", s.showTargetBand)
            put("graphStyle", s.graphStyle.id)
            put("unit", s.unit.id)
            put("urgentLowThreshold", s.urgentLowThreshold)
            put("lowThreshold", s.lowThreshold)
            put("highThreshold", s.highThreshold)
            put("theme", s.theme.id)
            put("cardOpacity", s.cardOpacity.toDouble())
            put("showDelta", s.showDelta)
            put("keepScreenOn", s.keepScreenOn)
            put("brightnessMode", s.brightnessMode.id)
            put("manualBrightness", s.manualBrightness.toDouble())
            put("nightAutoDim", s.nightAutoDim)
            put("burnInShift", s.burnInShift)
            put("showClock", s.showClock)
            put("overlayEnabled", s.overlayEnabled)
            put("overlaySize", s.overlaySize.id)
            put("clickThrough", s.clickThrough)
            put("clickThroughOpacity", s.clickThroughOpacity.toDouble())
            put("startOnBoot", s.startOnBoot)
            put("overlayPortraitX", s.overlayPortraitX)
            put("overlayPortraitY", s.overlayPortraitY)
            put("overlayLandscapeX", s.overlayLandscapeX)
            put("overlayLandscapeY", s.overlayLandscapeY)
        }
        prefs.edit().putString(KEY_SETTINGS, j.toString()).apply()
    }
}
