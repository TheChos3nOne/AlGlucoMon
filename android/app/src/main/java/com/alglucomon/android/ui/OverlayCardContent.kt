package com.alglucomon.android.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alglucomon.android.data.AppSettings
import com.alglucomon.android.data.AppTheme
import com.alglucomon.android.data.GlucoseUiState
import com.alglucomon.android.data.GraphStyle
import com.alglucomon.android.data.OverlaySize
import com.alglucomon.android.data.formatAge
import com.alglucomon.android.data.formatDelta
import com.alglucomon.android.data.formatGlucose
import com.alglucomon.android.data.getGlucoseStatus
import com.alglucomon.android.data.statusColor
import com.alglucomon.android.ui.components.BackgroundGraph
import com.alglucomon.android.ui.components.TrendArrow
import com.alglucomon.android.ui.components.computeGraphLayout
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.roundToInt

@Composable
fun OverlayCardContent(
    settings: AppSettings,
    glucoseState: GlucoseUiState,
    inspectMode: Boolean,
    onExitInspect: () -> Unit,
    onOpenApp: () -> Unit,
) {
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var hoveredIndex by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000L)
            nowMs = System.currentTimeMillis()
        }
    }

    LaunchedEffect(inspectMode) {
        if (!inspectMode) hoveredIndex = null
    }

    val readings = glucoseState.readings
    val latest = readings.lastOrNull()
    val previous = if (readings.size >= 2) readings[readings.size - 2] else null
    val status = remember(latest, settings) {
        if (latest == null) com.alglucomon.android.data.GlucoseStatus.IN_RANGE
        else getGlucoseStatus(latest.value, settings)
    }
    val accent = statusColor(status)
    val isLightTheme = settings.theme == AppTheme.LIGHT_GLASS
    val agePair = latest?.let { formatAge(it.timestamp, nowMs) }
    val deltaText = if (latest != null && previous != null) {
        formatDelta(latest.value - previous.value, settings.unit)
    } else null

    val cardBg = when (settings.theme) {
        AppTheme.LIGHT_GLASS -> Brush.linearGradient(
            listOf(Color(0xFFF8FAFC).copy(alpha = settings.cardOpacity), Color(0xFFF8FAFC).copy(alpha = settings.cardOpacity))
        )
        AppTheme.OLED -> Brush.linearGradient(
            listOf(
                Color(0xFF04060A).copy(alpha = (settings.cardOpacity + 0.08f).coerceAtMost(1f)),
                Color(0xFF04060A).copy(alpha = (settings.cardOpacity + 0.08f).coerceAtMost(1f)),
            )
        )
        AppTheme.ADAPTIVE -> Brush.linearGradient(
            listOf(Color(0xFF0D121C).copy(alpha = settings.cardOpacity), accent.copy(alpha = 0.22f))
        )
        AppTheme.DARK_GLASS -> Brush.linearGradient(
            listOf(Color(0xFF0D121C).copy(alpha = settings.cardOpacity), Color(0xFF0D121C).copy(alpha = settings.cardOpacity))
        )
    }
    val borderColor = when {
        settings.theme == AppTheme.ADAPTIVE -> accent.copy(alpha = 0.42f)
        isLightTheme -> Color(0x240F172A)
        else -> Color(0x24FFFFFF)
    }
    val textColor = if (isLightTheme) Color(0xFF0F172A) else Color(0xFFF8FAFC)
    val subColor = if (isLightTheme) Color(0xFF334155) else Color(0xFFE2E8F0)

    val valueSizeSp = when (settings.overlaySize) {
        OverlaySize.COMPACT -> 34.sp
        OverlaySize.STANDARD -> 44.sp
        OverlaySize.LARGE -> 56.sp
    }
    val arrowSizeDp = when (settings.overlaySize) {
        OverlaySize.COMPACT -> 22.dp
        OverlaySize.STANDARD -> 28.dp
        OverlaySize.LARGE -> 34.dp
    }
    val metaSizeSp = when (settings.overlaySize) {
        OverlaySize.COMPACT -> 10.sp
        OverlaySize.STANDARD -> 11.5.sp
        OverlaySize.LARGE -> 13.sp
    }

    val effectiveAlpha = if (settings.clickThrough && settings.isSignedIn) {
        settings.clickThroughOpacity.coerceIn(0.15f, 0.75f)
    } else {
        1f
    }
    val timeFormatter = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .alpha(effectiveAlpha)
            .clip(RoundedCornerShape(18.dp))
            .background(cardBg)
            .border(1.dp, borderColor, RoundedCornerShape(18.dp)),
    ) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val graphLayout = remember(readings, settings, widthPx, heightPx) {
            computeGraphLayout(readings, settings, widthPx, heightPx)
        }
        val canScrub = inspectMode &&
            settings.showBackgroundGraph &&
            settings.graphStyle == GraphStyle.DOTS &&
            graphLayout.points.size >= 2

        val hoveredPoint = if (canScrub) {
            hoveredIndex?.takeIf { it in graphLayout.points.indices }?.let { graphLayout.points[it] }
        } else null
        val isInspecting = hoveredPoint != null
        val contentAlpha by animateFloatAsState(
            targetValue = if (isInspecting) 0.16f else 1f,
            animationSpec = tween(150),
            label = "overlayContentAlpha",
        )
        val scrubRadiusPx = with(density) { 26.dp.toPx() }

        fun pickNearest(pos: Offset) {
            var bestIdx = -1
            var bestDist = scrubRadiusPx
            graphLayout.points.forEachIndexed { idx, pt ->
                val d = hypot(pt.x - pos.x, pt.y - pos.y)
                if (d <= bestDist) {
                    bestDist = d
                    bestIdx = idx
                }
            }
            hoveredIndex = if (bestIdx >= 0) bestIdx else null
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (canScrub) {
                        Modifier.pointerInput(graphLayout) {
                            detectDragGestures(
                                onDragStart = { pickNearest(it) },
                                onDrag = { change, _ ->
                                    change.consume()
                                    pickNearest(change.position)
                                },
                            )
                        }
                    } else {
                        Modifier
                    }
                )
        ) {
            BackgroundGraph(
                layout = graphLayout,
                settings = settings,
                accentColor = accent,
                isLightTheme = isLightTheme,
                hoveredIndex = hoveredIndex,
                isInspecting = isInspecting || canScrub,
                isFullscreen = false,
                modifier = Modifier.fillMaxSize(),
            )

            if (settings.showBackgroundGraph && readings.size >= 2 && !isInspecting) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    (if (isLightTheme) Color(0xFFF8FAFC) else Color(0xFF090D16)).copy(alpha = 0.50f),
                                    Color.Transparent,
                                ),
                                center = Offset(widthPx * 0.44f, heightPx * 0.52f),
                                radius = widthPx * 0.6f,
                            )
                        )
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(contentAlpha)
                    .padding(horizontal = if (settings.overlaySize == OverlaySize.COMPACT) 12.dp else 16.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                when {
                    latest != null -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = formatGlucose(latest.value, settings.unit),
                                color = if (agePair?.second == true) subColor.copy(alpha = 0.45f) else accent,
                                fontSize = valueSizeSp,
                                fontWeight = FontWeight.Bold,
                                style = TextStyle(
                                    fontFeatureSettings = "tnum",
                                    letterSpacing = (-1).sp,
                                    textDecoration = if (agePair?.second == true) {
                                        TextDecoration.LineThrough
                                    } else {
                                        TextDecoration.None
                                    },
                                ),
                            )
                            TrendArrow(trend = latest.trend, color = accent, size = arrowSizeDp)
                        }

                        if (settings.showDelta || glucoseState.errorMsg != null) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (glucoseState.errorMsg != null) {
                                    Text(
                                        text = glucoseState.errorMsg,
                                        color = Color(0xFFFDA4AF),
                                        fontSize = 10.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                } else {
                                    if (deltaText != null) {
                                        Text(
                                            text = "$deltaText ${settings.unit.id}",
                                            color = subColor.copy(alpha = 0.85f),
                                            fontSize = metaSizeSp,
                                            fontWeight = FontWeight.Medium,
                                            style = TextStyle(fontFeatureSettings = "tnum"),
                                        )
                                    }
                                    if (agePair != null) {
                                        Text(
                                            text = "• ${agePair.first}",
                                            color = if (agePair.second) Color(0xFFFB923C) else subColor.copy(alpha = 0.78f),
                                            fontSize = metaSizeSp,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    !settings.isSignedIn -> {
                        Text(
                            text = "Sign in to Dexcom",
                            color = textColor,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "Tap to open AlGlucoMon",
                            color = subColor.copy(alpha = 0.75f),
                            fontSize = 11.sp,
                            modifier = Modifier.clickable { onOpenApp() },
                        )
                    }

                    glucoseState.isLoading -> {
                        Text("Connecting...", color = textColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text("Dexcom G7 Share", color = subColor.copy(alpha = 0.7f), fontSize = 11.sp)
                    }

                    glucoseState.errorMsg != null -> {
                        Text("Connection issue", color = Color(0xFFFDA4AF), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = glucoseState.errorMsg,
                            color = subColor.copy(alpha = 0.75f),
                            fontSize = 10.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    else -> {
                        Text("No readings yet", color = textColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text("Waiting for Dexcom Share", color = subColor.copy(alpha = 0.7f), fontSize = 10.5.sp)
                    }
                }
            }

            if (inspectMode) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "Open",
                        color = Color(0xFFE2E8F0),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xCC1E293B))
                            .clickable {
                                onExitInspect()
                                onOpenApp()
                            }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                    Text(
                        text = "Done",
                        color = Color(0xFF042F2E),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF2DD4BF))
                            .clickable { onExitInspect() }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }

            if (hoveredPoint != null) {
                val tipTime = timeFormatter.format(Date(hoveredPoint.reading.timestamp))
                val tipX = (hoveredPoint.x - with(density) { 55.dp.toPx() })
                    .coerceIn(6f, (widthPx - with(density) { 125.dp.toPx() }).coerceAtLeast(6f))
                val placeBelow = hoveredPoint.y < heightPx * 0.42f
                val tipY = if (placeBelow) {
                    hoveredPoint.y + with(density) { 8.dp.toPx() }
                } else {
                    (hoveredPoint.y - with(density) { 26.dp.toPx() }).coerceAtLeast(4f)
                }

                Row(
                    modifier = Modifier
                        .offset { IntOffset(tipX.roundToInt(), tipY.roundToInt()) }
                        .clip(RoundedCornerShape(7.dp))
                        .background(if (isLightTheme) Color.White.copy(alpha = 0.94f) else Color(0xEB0A0F1A))
                        .border(1.dp, if (isLightTheme) Color(0x260F172A) else Color(0x33FFFFFF), RoundedCornerShape(7.dp))
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(hoveredPoint.color))
                    Text(
                        text = "${formatGlucose(hoveredPoint.reading.value, settings.unit)} • $tipTime",
                        color = textColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        style = TextStyle(fontFeatureSettings = "tnum"),
                    )
                }
            }
        }
    }
}
