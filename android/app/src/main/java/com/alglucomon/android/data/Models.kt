package com.alglucomon.android.data

import androidx.compose.ui.graphics.Color
import kotlin.math.ln
import kotlin.math.roundToInt

enum class TrendDirection(val code: Int, val label: String) {
    NONE(0, "None"),
    DOUBLE_UP(1, "DoubleUp"),
    SINGLE_UP(2, "SingleUp"),
    FORTY_FIVE_UP(3, "FortyFiveUp"),
    FLAT(4, "Flat"),
    FORTY_FIVE_DOWN(5, "FortyFiveDown"),
    SINGLE_DOWN(6, "SingleDown"),
    DOUBLE_DOWN(7, "DoubleDown"),
    NOT_COMPUTABLE(8, "NotComputable"),
    RATE_OUT_OF_RANGE(9, "RateOutOfRange"),
    UNKNOWN(-1, "Unknown");

    companion object {
        fun fromCode(code: Int): TrendDirection =
            entries.firstOrNull { it.code == code } ?: UNKNOWN

        fun fromLabel(label: String): TrendDirection {
            val clean = label.trim()
            clean.toIntOrNull()?.let { return fromCode(it) }
            return entries.firstOrNull { it.label.equals(clean, ignoreCase = true) } ?: UNKNOWN
        }
    }
}

enum class DexcomRegion(val id: String, val label: String) {
    US("us", "United States (US)"),
    OUS("ous", "Outside US (OUS)"),
    JP("jp", "Japan (JP)");

    companion object {
        fun fromId(id: String): DexcomRegion =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: US
    }
}

enum class GraphStyle(val id: String, val label: String) {
    SMOOTH("smooth", "Smooth curve"),
    DOTS("dots", "Dots");

    companion object {
        fun fromId(id: String): GraphStyle =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: DOTS
    }
}

enum class GlucoseUnit(val id: String) {
    MG_DL("mg/dL"),
    MMOL_L("mmol/L");

    companion object {
        fun fromId(id: String): GlucoseUnit =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: MG_DL
    }
}

enum class AppTheme(val id: String, val label: String) {
    DARK_GLASS("dark-glass", "Dark Glass"),
    ADAPTIVE("adaptive", "Adaptive Range Glow"),
    LIGHT_GLASS("light-glass", "Light Glass"),
    OLED("oled", "OLED Black");

    companion object {
        fun fromId(id: String): AppTheme =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: DARK_GLASS
    }
}

enum class OverlaySize(
    val id: String,
    val label: String,
    val widthDp: Int,
    val heightDp: Int,
) {
    COMPACT("compact", "Compact", 184, 84),
    STANDARD("standard", "Standard", 240, 112),
    LARGE("large", "Large", 300, 138);

    companion object {
        fun fromId(id: String): OverlaySize =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: STANDARD
    }
}

enum class BrightnessMode(val id: String, val label: String) {
    AUTO("auto", "Auto (Light Sensor)"),
    MANUAL("manual", "Manual");

    companion object {
        fun fromId(id: String): BrightnessMode =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: AUTO
    }
}

enum class GlucoseStatus {
    URGENT_LOW,
    LOW,
    IN_RANGE,
    HIGH,
}

data class GlucoseReading(
    val value: Int,
    val trend: TrendDirection,
    val timestamp: Long,
)

data class AppSettings(
    val region: DexcomRegion = DexcomRegion.US,
    val username: String = "",
    val password: String = "",
    val pollIntervalSec: Int = 120,
    val showBackgroundGraph: Boolean = true,
    val graphHours: Int = 3,
    val graphOpacity: Float = 0.45f,
    val showTargetBand: Boolean = true,
    val graphStyle: GraphStyle = GraphStyle.DOTS,
    val unit: GlucoseUnit = GlucoseUnit.MG_DL,
    val urgentLowThreshold: Int = 55,
    val lowThreshold: Int = 70,
    val highThreshold: Int = 180,
    val theme: AppTheme = AppTheme.DARK_GLASS,
    val cardOpacity: Float = 0.88f,
    val showDelta: Boolean = true,
    val keepScreenOn: Boolean = true,
    val brightnessMode: BrightnessMode = BrightnessMode.AUTO,
    val manualBrightness: Float = 0.70f,
    val nightAutoDim: Boolean = true,
    val burnInShift: Boolean = true,
    val showClock: Boolean = true,
    val overlayEnabled: Boolean = false,
    val overlaySize: OverlaySize = OverlaySize.STANDARD,
    val clickThrough: Boolean = false,
    val clickThroughOpacity: Float = 0.55f,
    val startOnBoot: Boolean = false,
    val overlayPortraitX: Int = -1,
    val overlayPortraitY: Int = -1,
    val overlayLandscapeX: Int = -1,
    val overlayLandscapeY: Int = -1,
) {
    val isSignedIn: Boolean
        get() = username.trim().isNotEmpty() && password.isNotEmpty()
}

private const val MMOL_FACTOR = 18.0182

fun formatGlucose(mgDl: Int, unit: GlucoseUnit): String {
    return if (unit == GlucoseUnit.MMOL_L) {
        String.format(java.util.Locale.US, "%.1f", mgDl / MMOL_FACTOR)
    } else {
        mgDl.toString()
    }
}

fun formatDelta(deltaMgDl: Int, unit: GlucoseUnit): String {
    return if (unit == GlucoseUnit.MMOL_L) {
        val value = deltaMgDl / MMOL_FACTOR
        val rounded = if (kotlin.math.abs(value) < 0.05) 0.0 else value
        val sign = when {
            rounded > 0 -> "+"
            rounded < 0 -> "-"
            else -> ""
        }
        String.format(java.util.Locale.US, "%s%.1f", sign, kotlin.math.abs(rounded))
    } else {
        if (deltaMgDl > 0) "+$deltaMgDl" else deltaMgDl.toString()
    }
}

fun getGlucoseStatus(mgDl: Int, settings: AppSettings): GlucoseStatus {
    return when {
        mgDl <= settings.urgentLowThreshold -> GlucoseStatus.URGENT_LOW
        mgDl < settings.lowThreshold -> GlucoseStatus.LOW
        mgDl > settings.highThreshold -> GlucoseStatus.HIGH
        else -> GlucoseStatus.IN_RANGE
    }
}

fun statusColor(status: GlucoseStatus): Color {
    return when (status) {
        GlucoseStatus.URGENT_LOW -> Color(0xFFFF4D6D)
        GlucoseStatus.LOW -> Color(0xFFFB923C)
        GlucoseStatus.HIGH -> Color(0xFFFACC15)
        GlucoseStatus.IN_RANGE -> Color(0xFF2DD4BF)
    }
}

fun formatAge(timestampMs: Long, nowMs: Long): Pair<String, Boolean> {
    val diffMs = (nowMs - timestampMs).coerceAtLeast(0L)
    val diffMin = (diffMs / 60_000L).toInt()
    val label = when {
        diffMin == 0 -> "now"
        diffMin < 60 -> "${diffMin}m"
        else -> "${diffMin / 60}h"
    }
    return label to (diffMs > 10 * 60_000L)
}

fun luxToBrightness(lux: Float): Float {
    if (lux <= 0f) return 0.03f
    val clamped = lux.coerceIn(0.5f, 2000f)
    val norm = (ln(clamped + 1f) / ln(2001f)).coerceIn(0f, 1f)
    val raw = 0.03f + norm * 0.97f
    return ((raw * 100f).roundToInt() / 100f).coerceIn(0.03f, 1.0f)
}
