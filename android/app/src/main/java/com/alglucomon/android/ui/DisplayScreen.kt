package com.alglucomon.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.alglucomon.android.data.BrightnessMode
import com.alglucomon.android.data.GlucoseUiState
import com.alglucomon.android.data.GraphStyle
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
fun DisplayScreen(
    settings: AppSettings,
    glucoseState: GlucoseUiState,
    currentLux: Float?,
    effectiveBrightness: Float,
    onOpenSettings: () -> Unit,
    onSwitchToOverlay: () -> Unit,
    onToggleBrightnessMode: () -> Unit,
    onRefreshNow: () -> Unit,
) {
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var controlsVisible by remember { mutableStateOf(false) }
    var hoveredIndex by remember { mutableStateOf<Int?>(null) }
    var shiftStep by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000L)
            nowMs = System.currentTimeMillis()
        }
    }

    LaunchedEffect(settings.burnInShift) {
        if (!settings.burnInShift) {
            shiftStep = 0
            return@LaunchedEffect
        }
        while (true) {
            delay(90_000L)
            shiftStep = (shiftStep + 1) % 8
        }
    }

    LaunchedEffect(controlsVisible) {
        if (controlsVisible) {
            delay(4500L)
            controlsVisible = false
        }
    }

    val shiftOffsets = remember {
        listOf(
            0 to 0, 4 to -3, 6 to 2, 2 to 5,
            -4 to 4, -6 to -1, -3 to -5, 3 to -2,
        )
    }
    val (shiftX, shiftY) = if (settings.burnInShift) shiftOffsets[shiftStep] else 0 to 0

    val readings = glucoseState.readings
    val latest = readings.lastOrNull()
    val previous = if (readings.size >= 2) readings[readings.size - 2] else null

    val status = remember(latest, settings) {
        if (latest == null) com.alglucomon.android.data.GlucoseStatus.IN_RANGE
        else getGlucoseStatus(latest.value, settings)
    }
    val accent = statusColor(status)
    val isLightTheme = settings.theme == AppTheme.LIGHT_GLASS
    val isNightDim = settings.nightAutoDim &&
        settings.brightnessMode == BrightnessMode.AUTO &&
        currentLux != null &&
        currentLux < 6f

    val bgBrush = when (settings.theme) {
        AppTheme.LIGHT_GLASS -> Brush.verticalGradient(listOf(Color(0xFFF1F5F9), Color(0xFFE2E8F0)))
        AppTheme.OLED -> Brush.verticalGradient(listOf(Color(0xFF000000), Color(0xFF000000)))
        AppTheme.ADAPTIVE -> Brush.linearGradient(
            listOf(Color(0xFF070B12), Color(0xFF0C1422), accent.copy(alpha = 0.22f))
        )
        AppTheme.DARK_GLASS -> Brush.verticalGradient(listOf(Color(0xFF080C15), Color(0xFF0F172A)))
    }

    val agePair = latest?.let { formatAge(it.timestamp, nowMs) }
    val deltaText = if (latest != null && previous != null) {
        formatDelta(latest.value - previous.value, settings.unit)
    } else null

    val textPrimary = if (isLightTheme) Color(0xFF0F172A) else Color(0xFFF8FAFC)
    val textSecondary = if (isLightTheme) Color(0xFF334155) else Color(0xFFCBD5E1)
    val timeFormatter = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(bgBrush)
    ) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }

        val graphLayout = remember(readings, settings, widthPx, heightPx) {
            computeGraphLayout(readings, settings, widthPx, heightPx)
        }
        val canScrubDots = settings.showBackgroundGraph &&
            settings.graphStyle == GraphStyle.DOTS &&
            graphLayout.points.size >= 2

        LaunchedEffect(canScrubDots, readings.size) {
            if (!canScrubDots) hoveredIndex = null
        }

        val hoveredPoint = if (canScrubDots) {
            hoveredIndex?.takeIf { it in graphLayout.points.indices }?.let { graphLayout.points[it] }
        } else null

        val isInspecting = hoveredPoint != null
        val contentAlpha by animateFloatAsState(
            targetValue = when {
                isInspecting -> 0.16f
                isNightDim -> 0.72f
                else -> 1f
            },
            animationSpec = tween(160),
            label = "displayContentAlpha",
        )
        val scrimAlpha by animateFloatAsState(
            targetValue = if (isInspecting) 0f else 1f,
            animationSpec = tween(160),
            label = "displayScrimAlpha",
        )

        val valueFontSp = minOf(maxWidth.value * 0.23f, maxHeight.value * 0.34f)
            .coerceIn(68f, 176f)
        val arrowDp = (valueFontSp * 0.56f).coerceIn(36f, 96f).dp
        val metaFontSp = (valueFontSp * 0.18f).coerceIn(15f, 26f)
        val scrubRadiusPx = with(density) { 48.dp.toPx() }

        fun pickNearestDot(offset: Offset) {
            if (!canScrubDots) return
            var bestIdx = -1
            var bestDist = scrubRadiusPx
            graphLayout.points.forEachIndexed { idx, pt ->
                val d = hypot(pt.x - offset.x, pt.y - offset.y)
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
                .pointerInput(canScrubDots) {
                    detectTapGestures(
                        onTap = {
                            if (hoveredIndex != null) {
                                hoveredIndex = null
                            } else {
                                controlsVisible = !controlsVisible
                            }
                        }
                    )
                }
                .pointerInput(canScrubDots, graphLayout) {
                    if (!canScrubDots) return@pointerInput
                    detectDragGestures(
                        onDragStart = { start -> pickNearestDot(start) },
                        onDrag = { change, _ ->
                            change.consume()
                            pickNearestDot(change.position)
                        },
                        onDragEnd = { hoveredIndex = null },
                        onDragCancel = { hoveredIndex = null },
                    )
                }
        ) {
            BackgroundGraph(
                layout = graphLayout,
                settings = settings,
                accentColor = accent,
                isLightTheme = isLightTheme,
                hoveredIndex = hoveredIndex,
                isInspecting = isInspecting,
                isFullscreen = true,
                modifier = Modifier.fillMaxSize(),
            )

            if (settings.showBackgroundGraph && readings.size >= 2) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(scrimAlpha)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    (if (isLightTheme) Color(0xFFF8FAFC) else Color(0xFF070B12)).copy(alpha = 0.55f),
                                    Color.Transparent,
                                ),
                                center = Offset(widthPx * 0.48f, heightPx * 0.50f),
                                radius = maxOf(widthPx, heightPx) * 0.55f,
                            )
                        )
                )
            }

            if (settings.showClock) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .systemBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 14.dp)
                        .alpha(if (isNightDim) 0.55f else 0.78f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (settings.brightnessMode == BrightnessMode.AUTO) {
                        val luxStr = currentLux?.let { "${it.roundToInt()} lx" } ?: "Auto"
                        Text(
                            text = luxStr,
                            color = textSecondary,
                            fontSize = 12.sp,
                        )
                    }
                    Text(
                        text = timeFormatter.format(Date(nowMs)),
                        color = textSecondary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        style = TextStyle(fontFeatureSettings = "tnum"),
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .offset {
                        IntOffset(
                            (shiftX * density.density).roundToInt(),
                            (shiftY * density.density).roundToInt(),
                        )
                    }
                    .alpha(contentAlpha)
                    .padding(horizontal = 28.dp),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    latest != null -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                Text(
                                    text = formatGlucose(latest.value, settings.unit),
                                    color = if (agePair?.second == true) textSecondary.copy(alpha = 0.55f) else accent,
                                    fontSize = valueFontSp.sp,
                                    fontWeight = FontWeight.Bold,
                                    lineHeight = (valueFontSp * 0.96f).sp,
                                    style = TextStyle(
                                        fontFeatureSettings = "tnum",
                                        letterSpacing = (-2).sp,
                                        textDecoration = if (agePair?.second == true) {
                                            TextDecoration.LineThrough
                                        } else {
                                            TextDecoration.None
                                        },
                                    ),
                                )
                                TrendArrow(
                                    trend = latest.trend,
                                    color = accent,
                                    size = arrowDp,
                                )
                            }

                            if (settings.showDelta || glucoseState.errorMsg != null) {
                                Spacer(modifier = Modifier.height(6.dp))
                                if (glucoseState.errorMsg != null) {
                                    Text(
                                        text = glucoseState.errorMsg,
                                        color = Color(0xFFFDA4AF),
                                        fontSize = 14.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                } else {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        if (deltaText != null) {
                                            Text(
                                                text = "$deltaText ${settings.unit.id}",
                                                color = textSecondary,
                                                fontSize = metaFontSp.sp,
                                                fontWeight = FontWeight.Medium,
                                                style = TextStyle(fontFeatureSettings = "tnum"),
                                            )
                                        }
                                        if (agePair != null) {
                                            Text(
                                                text = "• ${agePair.first}",
                                                color = if (agePair.second) Color(0xFFFB923C) else textSecondary,
                                                fontSize = metaFontSp.sp,
                                                fontWeight = if (agePair.second) FontWeight.SemiBold else FontWeight.Medium,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    !settings.isSignedIn -> {
                        DisplayEmptyCard(
                            title = "Sign in to Dexcom",
                            subtitle = "Connect Dexcom G7 Share to start live monitoring",
                            textColor = textPrimary,
                            subColor = textSecondary,
                            buttonLabel = "Sign In",
                            onAction = onOpenSettings,
                        )
                    }

                    glucoseState.isLoading -> {
                        DisplayEmptyCard(
                            title = "Connecting...",
                            subtitle = "Fetching latest Dexcom G7 Share readings",
                            textColor = textPrimary,
                            subColor = textSecondary,
                            dotColor = Color(0xFF38BDF8),
                        )
                    }

                    glucoseState.errorMsg != null -> {
                        DisplayEmptyCard(
                            title = "Connection issue",
                            subtitle = glucoseState.errorMsg,
                            textColor = textPrimary,
                            subColor = textSecondary,
                            dotColor = Color(0xFFFF4D6D),
                            buttonLabel = "Open Settings",
                            onAction = onOpenSettings,
                        )
                    }

                    else -> {
                        DisplayEmptyCard(
                            title = "No readings yet",
                            subtitle = "Waiting for Dexcom G7 Share",
                            textColor = textPrimary,
                            subColor = textSecondary,
                            dotColor = Color(0xFF2DD4BF),
                        )
                    }
                }
            }

            if (hoveredPoint != null) {
                val tipTime = timeFormatter.format(Date(hoveredPoint.reading.timestamp))
                val (tipAge, _) = formatAge(hoveredPoint.reading.timestamp, nowMs)
                val tipX = (hoveredPoint.x - with(density) { 85.dp.toPx() })
                    .coerceIn(with(density) { 12.dp.toPx() }, (widthPx - with(density) { 182.dp.toPx() }).coerceAtLeast(12f))
                val placeBelow = hoveredPoint.y < heightPx * 0.35f
                val tipY = if (placeBelow) {
                    hoveredPoint.y + with(density) { 18.dp.toPx() }
                } else {
                    (hoveredPoint.y - with(density) { 52.dp.toPx() }).coerceAtLeast(with(density) { 16.dp.toPx() })
                }

                Row(
                    modifier = Modifier
                        .offset { IntOffset(tipX.roundToInt(), tipY.roundToInt()) }
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isLightTheme) Color.White.copy(alpha = 0.94f) else Color(0xEB0A0F1A))
                        .border(
                            1.dp,
                            if (isLightTheme) Color(0x260F172A) else Color(0x33FFFFFF),
                            RoundedCornerShape(10.dp),
                        )
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(hoveredPoint.color)
                    )
                    Text(
                        text = "${formatGlucose(hoveredPoint.reading.value, settings.unit)} ${settings.unit.id}",
                        color = textPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        style = TextStyle(fontFeatureSettings = "tnum"),
                    )
                    Text(
                        text = "• $tipTime" + if (tipAge != "now") " ($tipAge ago)" else "",
                        color = textSecondary,
                        fontSize = 13.sp,
                    )
                }
            }

            AnimatedVisibility(
                visible = controlsVisible,
                enter = fadeIn(tween(180)),
                exit = fadeOut(tween(220)),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .systemBarsPadding()
                    .padding(18.dp),
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xE00F172A))
                        .border(1.dp, Color(0x29FFFFFF), RoundedCornerShape(16.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PillButton("Switch to Overlay", Color(0xFF2DD4BF), Color(0xFF042F2E)) {
                        controlsVisible = false
                        onSwitchToOverlay()
                    }
                    PillButton(
                        if (settings.brightnessMode == BrightnessMode.AUTO) {
                            "Brightness: Auto (${(effectiveBrightness * 100).roundToInt()}%)"
                        } else {
                            "Brightness: Manual"
                        },
                        Color(0xFF1E293B),
                        Color(0xFFE2E8F0),
                    ) {
                        onToggleBrightnessMode()
                    }
                    if (settings.isSignedIn) {
                        PillButton("Refresh", Color(0xFF1E293B), Color(0xFFE2E8F0)) {
                            onRefreshNow()
                        }
                    }
                    PillButton("Settings", Color(0xFF0EA5E9), Color(0xFF082F49)) {
                        controlsVisible = false
                        onOpenSettings()
                    }
                }
            }

            if (isNightDim) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.25f))
                )
            }
        }
    }
}

@Composable
private fun PillButton(
    label: String,
    bg: Color,
    fg: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text(text = label, color = fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun DisplayEmptyCard(
    title: String,
    subtitle: String,
    textColor: Color,
    subColor: Color,
    dotColor: Color? = null,
    buttonLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (dotColor != null) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                )
            } else {
                Canvas(modifier = Modifier.size(28.dp)) {
                    val p = androidx.compose.ui.graphics.Path().apply {
                        moveTo(size.width * 0.1f, size.height * 0.55f)
                        lineTo(size.width * 0.32f, size.height * 0.55f)
                        lineTo(size.width * 0.44f, size.height * 0.22f)
                        lineTo(size.width * 0.58f, size.height * 0.82f)
                        lineTo(size.width * 0.70f, size.height * 0.48f)
                        lineTo(size.width * 0.90f, size.height * 0.55f)
                    }
                    drawPath(
                        p,
                        color = Color(0xFF2DD4BF),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(
                            width = 3.dp.toPx(),
                            cap = androidx.compose.ui.graphics.StrokeCap.Round,
                            join = androidx.compose.ui.graphics.StrokeJoin.Round,
                        ),
                    )
                }
            }
            Text(
                text = title,
                color = textColor,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Text(
            text = subtitle,
            color = subColor,
            fontSize = 14.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (buttonLabel != null && onAction != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Button(
                onClick = onAction,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF2DD4BF),
                    contentColor = Color(0xFF042F2E),
                ),
            ) {
                Text(buttonLabel, fontWeight = FontWeight.Bold)
            }
        }
    }
}
