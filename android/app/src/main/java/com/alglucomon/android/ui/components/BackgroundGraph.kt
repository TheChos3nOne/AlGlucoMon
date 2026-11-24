package com.alglucomon.android.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.alglucomon.android.data.AppSettings
import com.alglucomon.android.data.GlucoseReading
import com.alglucomon.android.data.GraphStyle
import com.alglucomon.android.data.getGlucoseStatus
import com.alglucomon.android.data.statusColor
import kotlin.math.max
import kotlin.math.min

data class GraphPoint(
    val x: Float,
    val y: Float,
    val reading: GlucoseReading,
    val color: Color,
)

data class GraphLayout(
    val points: List<GraphPoint> = emptyList(),
    val bandTop: Float = 0f,
    val bandBottom: Float = 0f,
    val widthPx: Float = 0f,
    val heightPx: Float = 0f,
)

fun computeGraphLayout(
    readings: List<GlucoseReading>,
    settings: AppSettings,
    widthPx: Float,
    heightPx: Float,
): GraphLayout {
    if (!settings.showBackgroundGraph || readings.size < 2 || widthPx <= 0f || heightPx <= 0f) {
        return GraphLayout(widthPx = widthPx, heightPx = heightPx)
    }

    val latestTs = readings.last().timestamp
    val windowMs = settings.graphHours * 3_600_000L
    val cutoff = latestTs - windowMs
    val visible = readings.filter { it.timestamp >= cutoff }
    val series = if (visible.size >= 2) visible else readings.takeLast(2)

    val minReadingVal = series.minOf { it.value }
    val maxReadingVal = series.maxOf { it.value }
    val rawMin = min(minReadingVal, settings.lowThreshold - 10).toFloat()
    val rawMax = max(maxReadingVal, settings.highThreshold + 15).toFloat()
    val span = max(40f, rawMax - rawMin)
    val yMin = rawMin - span * 0.12f
    val yMax = rawMax + span * 0.14f

    val minTs = series.first().timestamp
    val maxTs = series.last().timestamp
    val tsSpan = max(1L, maxTs - minTs).toFloat()

    val padX = if (settings.graphStyle == GraphStyle.DOTS) widthPx * 0.032f else 0f
    val plotW = (widthPx - padX * 2f).coerceAtLeast(1f)
    val padTop = heightPx * 0.07f
    val padBottom = heightPx * 0.05f
    val plotH = (heightPx - padTop - padBottom).coerceAtLeast(1f)

    fun toY(mgDl: Float): Float {
        val norm = ((mgDl - yMin) / (yMax - yMin)).coerceIn(0f, 1f)
        return heightPx - padBottom - norm * plotH
    }

    val points = series.map { r ->
        val x = padX + ((r.timestamp - minTs).toFloat() / tsSpan) * plotW
        val y = toY(r.value.toFloat())
        val color = statusColor(getGlucoseStatus(r.value, settings))
        GraphPoint(x = x, y = y, reading = r, color = color)
    }

    val bandTop = toY(settings.highThreshold.toFloat())
    val bandBottom = toY(settings.lowThreshold.toFloat())
    return GraphLayout(
        points = points,
        bandTop = bandTop,
        bandBottom = bandBottom,
        widthPx = widthPx,
        heightPx = heightPx,
    )
}

private fun buildSmoothLinePath(points: List<GraphPoint>): Path {
    val path = Path()
    if (points.isEmpty()) return path
    path.moveTo(points[0].x, points[0].y)
    if (points.size == 1) return path
    if (points.size == 2) {
        path.lineTo(points[1].x, points[1].y)
        return path
    }
    for (i in 0 until points.size - 1) {
        val p0 = points[max(0, i - 1)]
        val p1 = points[i]
        val p2 = points[i + 1]
        val p3 = points[min(points.size - 1, i + 2)]

        val cp1x = p1.x + (p2.x - p0.x) / 6f
        val cp1y = p1.y + (p2.y - p0.y) / 6f
        val cp2x = p2.x - (p3.x - p1.x) / 6f
        val cp2y = p2.y - (p3.y - p1.y) / 6f
        path.cubicTo(cp1x, cp1y, cp2x, cp2y, p2.x, p2.y)
    }
    return path
}

@Composable
fun BackgroundGraph(
    layout: GraphLayout,
    settings: AppSettings,
    accentColor: Color,
    isLightTheme: Boolean,
    hoveredIndex: Int?,
    isInspecting: Boolean,
    isFullscreen: Boolean,
    modifier: Modifier = Modifier,
) {
    val targetAlpha = if (isInspecting) max(settings.graphOpacity, 0.92f) else settings.graphOpacity
    val layerAlpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(durationMillis = 180),
        label = "graphAlpha",
    )

    if (!settings.showBackgroundGraph || layout.points.size < 2) return

    Canvas(modifier = modifier) {
        val pts = layout.points
        val w = size.width
        val h = size.height
        val bandTop = layout.bandTop
        val bandBottom = layout.bandBottom
        val bandHeight = (bandBottom - bandTop).coerceAtLeast(0f)

        val guideColor = if (isLightTheme) {
            Color(0xFF0F172A).copy(alpha = 0.14f * layerAlpha)
        } else {
            Color.White.copy(alpha = 0.13f * layerAlpha)
        }
        val bandFill = if (isLightTheme) {
            Color(0xFF0F172A).copy(alpha = 0.05f * layerAlpha)
        } else {
            Color.White.copy(alpha = 0.05f * layerAlpha)
        }
        val dotOutline = if (isLightTheme) {
            Color(0xFFF8FAFC).copy(alpha = 0.85f * layerAlpha)
        } else {
            Color(0xFF090D16).copy(alpha = 0.72f * layerAlpha)
        }
        val hoverRing = if (isLightTheme) Color(0xFF0F172A) else Color.White

        if (settings.showTargetBand && bandHeight > 1f) {
            drawRect(
                color = bandFill,
                topLeft = Offset(0f, bandTop),
                size = Size(w, bandHeight),
            )
            val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
            drawLine(guideColor, Offset(0f, bandTop), Offset(w, bandTop), strokeWidth = 1.2f, pathEffect = dash)
            drawLine(guideColor, Offset(0f, bandBottom), Offset(w, bandBottom), strokeWidth = 1.2f, pathEffect = dash)
        }

        val linePath = buildSmoothLinePath(pts)
        val areaPath = Path().apply {
            addPath(linePath)
            lineTo(pts.last().x, h)
            lineTo(pts.first().x, h)
            close()
        }

        val areaBrush = Brush.verticalGradient(
            0f to accentColor.copy(alpha = 0.56f * layerAlpha * (if (settings.graphStyle == GraphStyle.DOTS) 0.48f else 1f)),
            0.72f to accentColor.copy(alpha = 0.14f * layerAlpha * (if (settings.graphStyle == GraphStyle.DOTS) 0.48f else 1f)),
            1f to Color.Transparent,
            startY = 0f,
            endY = h,
        )
        drawPath(path = areaPath, brush = areaBrush)

        if (settings.graphStyle == GraphStyle.SMOOTH) {
            val strokeW = (if (isFullscreen) 3.4.dp else 2.2.dp).toPx()
            drawPath(
                path = linePath,
                color = accentColor.copy(alpha = 0.25f * layerAlpha),
                style = Stroke(width = strokeW * 2.2f, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
            drawPath(
                path = linePath,
                color = accentColor.copy(alpha = layerAlpha),
                style = Stroke(width = strokeW, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
            val last = pts.last()
            val dotX = min(w - 6f, last.x)
            val dotR = (if (isFullscreen) 4.dp else 2.6.dp).toPx()
            drawCircle(accentColor.copy(alpha = 0.28f * layerAlpha), radius = dotR * 2f, center = Offset(dotX, last.y))
            drawCircle(accentColor.copy(alpha = layerAlpha), radius = dotR, center = Offset(dotX, last.y))
        } else {
            drawPath(
                path = linePath,
                color = accentColor.copy(alpha = 0.22f * layerAlpha),
                style = Stroke(width = (if (isFullscreen) 1.5.dp else 1.dp).toPx()),
            )

            val hoveredPt = hoveredIndex?.takeIf { it in pts.indices }?.let { pts[it] }
            if (hoveredPt != null) {
                drawLine(
                    color = hoveredPt.color.copy(alpha = 0.5f),
                    start = Offset(hoveredPt.x, hoveredPt.y),
                    end = Offset(hoveredPt.x, h),
                    strokeWidth = 1.6f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
                )
            }

            val baseR = (if (isFullscreen) 4.2.dp else 2.5.dp).toPx()
            val latestR = (if (isFullscreen) 5.4.dp else 3.2.dp).toPx()
            val outlineW = (if (isFullscreen) 1.4.dp else 0.9.dp).toPx()

            pts.forEachIndexed { idx, pt ->
                if (idx == hoveredIndex) return@forEachIndexed
                val r = if (idx == pts.lastIndex) latestR else baseR
                drawCircle(
                    color = pt.color.copy(alpha = layerAlpha),
                    radius = r,
                    center = Offset(pt.x, pt.y),
                )
                drawCircle(
                    color = dotOutline,
                    radius = r,
                    center = Offset(pt.x, pt.y),
                    style = Stroke(width = outlineW),
                )
            }

            if (hoveredPt != null) {
                val activeR = (if (isFullscreen) 7.5.dp else 4.6.dp).toPx()
                drawCircle(
                    color = hoveredPt.color.copy(alpha = 0.30f),
                    radius = activeR * 1.75f,
                    center = Offset(hoveredPt.x, hoveredPt.y),
                )
                drawCircle(
                    color = hoveredPt.color,
                    radius = activeR,
                    center = Offset(hoveredPt.x, hoveredPt.y),
                )
                drawCircle(
                    color = hoverRing,
                    radius = activeR,
                    center = Offset(hoveredPt.x, hoveredPt.y),
                    style = Stroke(width = (if (isFullscreen) 2.2.dp else 1.5.dp).toPx()),
                )
            }
        }
    }
}
