package com.alglucomon.android.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import com.alglucomon.android.data.TrendDirection

private fun rotationForTrend(trend: TrendDirection): Float? = when (trend) {
    TrendDirection.SINGLE_UP, TrendDirection.DOUBLE_UP -> -90f
    TrendDirection.FORTY_FIVE_UP -> -45f
    TrendDirection.FLAT -> 0f
    TrendDirection.FORTY_FIVE_DOWN -> 45f
    TrendDirection.SINGLE_DOWN, TrendDirection.DOUBLE_DOWN -> 90f
    else -> null
}

@Composable
fun TrendArrow(
    trend: TrendDirection,
    color: Color,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val targetDeg = rotationForTrend(trend)
    val animatedDeg by animateFloatAsState(
        targetValue = targetDeg ?: 0f,
        animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing),
        label = "trendRotation",
    )
    val isDouble = trend == TrendDirection.DOUBLE_UP || trend == TrendDirection.DOUBLE_DOWN

    Canvas(modifier = modifier.size(size)) {
        if (targetDeg == null) {
            val cy = this.size.height / 2f
            val r = this.size.minDimension * 0.08f
            drawCircle(color.copy(alpha = 0.7f), radius = r, center = Offset(this.size.width * 0.34f, cy))
            drawCircle(color.copy(alpha = 0.7f), radius = r, center = Offset(this.size.width * 0.66f, cy))
            return@Canvas
        }

        val w = this.size.width
        val h = this.size.height
        rotate(degrees = animatedDeg, pivot = Offset(w / 2f, h / 2f)) {
            if (isDouble) {
                val strokeW = w * 0.105f
                val offsets = listOf(0.32f, 0.68f)
                for (yFrac in offsets) {
                    val y = h * yFrac
                    val path = Path().apply {
                        moveTo(w * 0.18f, y)
                        lineTo(w * 0.74f, y)
                        moveTo(w * 0.56f, y - h * 0.15f)
                        lineTo(w * 0.74f, y)
                        lineTo(w * 0.56f, y + h * 0.15f)
                    }
                    drawPath(
                        path = path,
                        color = color.copy(alpha = 0.25f),
                        style = Stroke(width = strokeW * 1.9f, cap = StrokeCap.Round, join = StrokeJoin.Round),
                    )
                    drawPath(
                        path = path,
                        color = color,
                        style = Stroke(width = strokeW, cap = StrokeCap.Round, join = StrokeJoin.Round),
                    )
                }
            } else {
                val strokeW = w * 0.12f
                val cy = h / 2f
                val path = Path().apply {
                    moveTo(w * 0.18f, cy)
                    lineTo(w * 0.80f, cy)
                    moveTo(w * 0.55f, h * 0.25f)
                    lineTo(w * 0.80f, cy)
                    lineTo(w * 0.55f, h * 0.75f)
                }
                drawPath(
                    path = path,
                    color = color.copy(alpha = 0.25f),
                    style = Stroke(width = strokeW * 1.9f, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
                drawPath(
                    path = path,
                    color = color,
                    style = Stroke(width = strokeW, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
        }
    }
}
