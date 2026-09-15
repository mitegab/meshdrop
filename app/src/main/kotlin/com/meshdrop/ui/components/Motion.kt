package com.meshdrop.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.meshdrop.ui.theme.MeshTheme

/**
 * Motion, used as grammar rather than decoration.
 *
 * The old kit animated everything it could: radar rings, orbiting dots, a shimmering chunk
 * assembly bar, a marching dashed route line. All of it was beautiful in a screenshot and
 * none of it survived a second glance, because a screen where five things move has no
 * emphasis left to give.
 *
 * The rules now:
 *
 *  1. **Motion answers "is anything happening?" and nothing else.** A still screen means
 *     nothing is in flight. That is information.
 *  2. **One live indicator per screen, maximum.** [BreathingDot] for "waiting", and
 *     [ProgressLine]'s head glow for "moving". Never both.
 *  3. **Loops are slow and shallow** (2.4s, ±35% alpha). A pulse you notice is a pulse that
 *     makes you anxious.
 *  4. **Nothing load-bearing is animated.** Every animated element has a static text
 *     equivalent, so the app is fully usable with animations off or with a screen reader.
 */

/**
 * A dot that breathes: "I am listening", "waiting for an answer", "looking for phones".
 *
 * The single permitted infinite animation. It is a slow alpha fade with no scale, so it reads
 * as breathing rather than as a blinking alert.
 */
@Composable
fun BreathingDot(
    modifier: Modifier = Modifier,
    color: Color = MeshTheme.colors.success,
    size: Dp = 9.dp,
    /** False freezes it at full strength: a steady dot means "on, but nothing pending". */
    breathing: Boolean = true,
) {
    val transition = rememberInfiniteTransition(label = "breathing")
    val pulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2_400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breathing-alpha",
    )

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(color.copy(alpha = if (breathing) pulse else 1f)),
    )
}

/**
 * The progress bar.
 *
 * A 6dp rule that fills with clay, plus a soft glow hugging the leading edge while bytes are
 * actually moving. The glow is the only thing that says "live": when a transfer stalls, the
 * bar stops moving *and* the glow dies, which is the honest picture of a stalled socket.
 *
 * The fill is animated between reported values (300ms) because throughput reports arrive in
 * chunks and a bar that jumps in steps looks like a bug.
 */
@Composable
fun ProgressLine(
    progress: Float,
    modifier: Modifier = Modifier,
    active: Boolean = true,
    color: Color = MeshTheme.colors.clay,
    height: Dp = 6.dp,
) {
    val smoothed by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 300),
        label = "progress",
    )

    val transition = rememberInfiniteTransition(label = "progress-head")
    val glow by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "progress-glow",
    )

    // Colours are read here, not inside the draw scope: a Canvas lambda is not composable.
    val colors = MeshTheme.colors

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(CircleShape),
    ) {
        val radius = CornerRadius(size.height / 2f, size.height / 2f)
        drawRoundRect(
            color = colors.subtle,
            cornerRadius = radius,
            size = Size(size.width, size.height),
        )

        val filled = size.width * smoothed
        if (filled <= 0f) return@Canvas

        drawRoundRect(
            brush = Brush.horizontalGradient(
                colors = listOf(color.copy(alpha = 0.85f), color),
                startX = 0f,
                endX = filled.coerceAtLeast(size.height),
            ),
            cornerRadius = radius,
            size = Size(filled, size.height),
        )

        if (active && smoothed < 1f) {
            // The head: a short warm smear where the bytes are landing right now.
            val headWidth = size.height * 2.6f
            val start = (filled - headWidth).coerceAtLeast(0f)
            drawRoundRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        Color.Transparent,
                        colors.ember.copy(alpha = glow),
                    ),
                    startX = start,
                    endX = filled,
                ),
                topLeft = Offset(start, 0f),
                size = Size(filled - start, size.height),
                cornerRadius = radius,
            )
        }
    }
}

/**
 * Throughput over the last few seconds, as a bare line.
 *
 * No axes, no fill, no grid: it exists so a stalled transfer is visible as a flat line, and
 * it lives inside the "Details" disclosure where a technical user went looking for it on
 * purpose.
 */
@Composable
fun ThroughputLine(
    samples: List<Long>,
    peak: Long,
    modifier: Modifier = Modifier,
    color: Color = MeshTheme.colors.clay,
) {
    // Read outside the draw scope: a Canvas lambda is not composable.
    val hairline = MeshTheme.colors.hairline
    val strokeColor = color

    Canvas(modifier = modifier.fillMaxWidth().height(40.dp)) {
        if (samples.size < 2) return@Canvas

        val scale = peak.toFloat().coerceAtLeast(1f)
        val stepX = size.width / (samples.size - 1).toFloat()

        val path = Path().apply {
            samples.forEachIndexed { index, value ->
                val x = index * stepX
                val y = size.height - (value.toFloat() / scale) * size.height * 0.88f
                if (index == 0) moveTo(x, y) else lineTo(x, y)
            }
        }

        drawLine(
            color = hairline,
            start = Offset(0f, size.height - 1f),
            end = Offset(size.width, size.height - 1f),
            strokeWidth = 1f,
        )
        drawPath(path = path, color = strokeColor, style = Stroke(width = 2f))
    }
}
