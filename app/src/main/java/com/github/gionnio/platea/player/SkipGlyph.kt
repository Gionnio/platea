package com.github.gionnio.platea.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.github.gionnio.platea.ui.theme.PlateaColors
import com.github.gionnio.platea.ui.theme.PlateaType
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Skip back/forward symbol: an open ring with a filled arrowhead at the top and the seconds inside,
 * drawn with the same thin rounded strokes as the rest of Platea.
 */
@Composable
fun SkipGlyph(
    seconds: Long,
    back: Boolean,
    tint: Color,
    size: Dp,
    textSize: TextUnit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = this.size.minDimension * 0.085f
            val radius = this.size.minDimension / 2 - stroke * 1.6f
            val center = Offset(this.size.width / 2, this.size.height / 2)
            // Opening at the top; forward runs clockwise and ends top-left, back mirrors it
            val start = if (back) -120f else -60f
            val sweep = if (back) -300f else 300f
            drawArc(
                color = tint,
                startAngle = start,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = Size(radius * 2, radius * 2),
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
            val end = Math.toRadians((start + sweep).toDouble())
            val p = Offset(center.x + radius * cos(end).toFloat(), center.y + radius * sin(end).toFloat())
            // Direction of travel at the end of the arc
            val d =
                if (back) {
                    Offset(sin(end).toFloat(), -cos(end).toFloat())
                } else {
                    Offset(-sin(end).toFloat(), cos(end).toFloat())
                }
            val n = Offset(-d.y, d.x)
            val h = stroke * 2.4f
            val head =
                Path().apply {
                    moveTo(p.x + d.x * h, p.y + d.y * h)
                    lineTo(p.x + n.x * h * 0.7f, p.y + n.y * h * 0.7f)
                    lineTo(p.x - n.x * h * 0.7f, p.y - n.y * h * 0.7f)
                    close()
                }
            drawPath(head, tint)
        }
        Text(
            seconds.toString(),
            style = PlateaType.Button.copy(fontSize = textSize, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.02).sp),
            color = tint,
            modifier = Modifier.padding(top = 1.dp),
        )
    }
}

/**
 * Shown while skipping with left/right and the controls hidden: the symbol on a velvet disc at the side of
 * the screen it moves towards, with the total skipped so far. Replaces Wholphin's rotating arrow.
 */
@Composable
fun PlateaSkipIndicator(
    durationMs: Long,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val back = durationMs < 0
    val nudge = remember { Animatable(0f) }
    LaunchedEffect(durationMs) {
        nudge.snapTo(0f)
        nudge.animateTo(if (back) -30f else 30f, tween(160))
        nudge.animateTo(0f, tween(220))
        kotlinx.coroutines.delay(700)
        onFinish()
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(PlateaColors.Ground.copy(alpha = 0.7f)),
            contentAlignment = Alignment.Center,
        ) {
            SkipGlyph(
                seconds = abs(durationMs / 1000),
                back = back,
                tint = PlateaColors.Text,
                size = 40.dp,
                textSize = 11.sp,
                modifier = Modifier.rotate(nudge.value),
            )
        }
    }
}
