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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.meshdrop.core.model.LaneActivity
import com.meshdrop.core.model.PeerContribution
import kotlin.math.cos
import kotlin.math.sin

/**
 * Motion replaces sentences.
 *
 * Everything here exists so the user understands what the system is doing without reading a
 * word: the radar says "I am looking", the lane strip says "bytes are going through *here*",
 * the assembly bar says "this file is nearly whole".
 */

/** Expanding rings: "discovery is running" without a line of text. */
@Composable
fun RadarPulse(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    ringCount: Int = 3,
) {
    val transition = rememberInfiniteTransition(label = "radar")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2_400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "radar-progress",
    )

    Canvas(modifier = modifier) {
        val radius = size.minDimension / 2f
        val strokeWidth = 2.dp.toPx()

        // Static guide rings give the pulse something to travel across.
        drawCircle(color = color.copy(alpha = 0.10f), radius = radius, style = Stroke(width = strokeWidth / 2))
        drawCircle(color = color.copy(alpha = 0.10f), radius = radius * 0.6f, style = Stroke(width = strokeWidth / 2))

        for (ring in 0 until ringCount) {
            val offset = (progress + ring.toFloat() / ringCount) % 1f
            drawCircle(
                color = color.copy(alpha = (1f - offset) * 0.45f),
                radius = radius * offset,
                style = Stroke(width = strokeWidth),
            )
        }
        drawCircle(color = color, radius = strokeWidth * 1.6f)
    }
}

/**
 * The thin strip under the main progress bar: which lane(s) the bytes are using right now.
 *
 * Widths are proportional to each lane's share of the throughput, so a failover is visible as
 * one colour yielding to another, and the strip flashes amber for a moment when it happens.
 */
@Composable
fun LaneStrip(
    lanes: List<LaneActivity>,
    modifier: Modifier = Modifier,
    justFailedOver: Boolean = false,
    height: Dp = 6.dp,
) {
    val total = lanes.sumOf { it.bytesPerSecond }.coerceAtLeast(1L)
    val failoverFlash by animateFloatAsState(
        targetValue = if (justFailedOver) 1f else 0f,
        animationSpec = tween(durationMillis = 700),
        label = "failover",
    )

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            lanes.forEach { lane ->
                val visual = TransportVisual.of(lane.lane)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(end = 12.dp),
                ) {
                    TransportIcon(
                        lane = lane.lane,
                        modifier = Modifier.size(14.dp),
                        tint = if (lane.active) visual.tint else visual.tint.copy(alpha = 0.35f),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = lane.lane.shortLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (lane.active) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        },
                    )
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            if (justFailedOver) {
                Text(
                    text = "switched lane",
                    style = MaterialTheme.typography.labelSmall,
                    color = androidx.compose.ui.graphics.Color(0xFFFFB74D).copy(alpha = failoverFlash),
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .clip(RoundedCornerShape(50)),
        ) {
            var x = 0f
            val trackColor = Color.White.copy(alpha = 0.08f)
            drawRect(color = trackColor, size = Size(size.width, size.height))

            lanes.forEach { lane ->
                val width = size.width * (lane.bytesPerSecond.toFloat() / total.toFloat())
                val color = TransportVisual.of(lane.lane).tint.copy(alpha = if (lane.active) 0.95f else 0.25f)
                drawRect(color = color, topLeft = Offset(x, 0f), size = Size(width, size.height))
                x += width
            }
        }
    }
}

/**
 * Mini avatars for peers contributing chunks (swarm mode).
 *
 * Capped at four chips: past that the casual user stops being able to read it, so the rest
 * are folded into a "+n" pill.
 */
@Composable
fun PeerChips(
    contributions: List<PeerContribution>,
    modifier: Modifier = Modifier,
) {
    val visible = contributions.sortedByDescending { it.bytesDelivered }
        .take(com.meshdrop.core.model.PeerContribution.MAX_VISIBLE)
    val overflow = contributions.size - visible.size

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        visible.forEach { contribution ->
            val progress by animateFloatAsState(
                targetValue = contribution.progress,
                animationSpec = tween(durationMillis = 400),
                label = "peer-progress",
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(contentAlignment = Alignment.BottomCenter) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(deviceColor(contribution.peerId).copy(alpha = 0.22f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = deviceInitials(contribution.displayName),
                            style = MaterialTheme.typography.labelMedium,
                            color = deviceColor(contribution.peerId),
                        )
                    }
                    if (!contribution.isActive) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                // The sliver: how much of this peer's share has landed.
                Canvas(
                    modifier = Modifier
                        .width(38.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(50)),
                ) {
                    drawRect(color = Color.White.copy(alpha = 0.10f), size = Size(size.width, size.height))
                    drawRect(
                        color = deviceColor(contribution.peerId),
                        size = Size(size.width * progress, size.height),
                    )
                }
            }
        }

        if (overflow > 0) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "+$overflow", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/**
 * A file bar that visually assembles: completed chunks are solid, the head chunk glows.
 * Reads as "the file is becoming whole" rather than "53%".
 */
@Composable
fun AssemblyBar(
    progress: Float,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    chunkCount: Int = 24,
    height: Dp = 10.dp,
) {
    val transition = rememberInfiniteTransition(label = "assembly")
    val shimmer by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 1_600, easing = LinearEasing)),
        label = "shimmer",
    )
    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 350),
        label = "assembly-progress",
    )

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(50)),
    ) {
        val gap = 2.dp.toPx()
        val chunkWidth = (size.width - gap * (chunkCount - 1)) / chunkCount
        val filled = animatedProgress * chunkCount

        for (index in 0 until chunkCount) {
            val startX = index * (chunkWidth + gap)
            val chunkProgress = (filled - index).coerceIn(0f, 1f)
            if (chunkProgress <= 0f) {
                drawRect(
                    color = Color.White.copy(alpha = 0.07f),
                    topLeft = Offset(startX, 0f),
                    size = Size(chunkWidth, size.height),
                )
                continue
            }
            val isHead = chunkProgress < 1f
            val alpha = if (isHead) 0.55f + 0.45f * shimmer else 1f
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(color.copy(alpha = alpha * 0.75f), color.copy(alpha = alpha)),
                    startX = startX,
                    endX = startX + chunkWidth,
                ),
                topLeft = Offset(startX, 0f),
                size = Size(chunkWidth * chunkProgress, size.height),
            )
        }
    }
}

/** Slow orbit used by the transfer header: "this session is alive". */
@Composable
fun OrbitDots(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    dotCount: Int = 3,
) {
    val transition = rememberInfiniteTransition(label = "orbit")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 3_600, easing = LinearEasing)),
        label = "orbit-angle",
    )

    Canvas(modifier = modifier) {
        val radius = size.minDimension / 2f * 0.7f
        val center = Offset(size.width / 2f, size.height / 2f)
        for (index in 0 until dotCount) {
            val radians = Math.toRadians((angle + index * 360.0 / dotCount))
            drawCircle(
                color = color.copy(alpha = 0.35f + 0.65f * (index.toFloat() / dotCount)),
                radius = size.minDimension * 0.055f,
                center = Offset(
                    x = center.x + cos(radians).toFloat() * radius,
                    y = center.y + sin(radians).toFloat() * radius,
                ),
            )
        }
    }
}

/** Dashed connector that animates its phase while a route is being (re)established. */
@Composable
fun AnimatedRouteLine(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    animating: Boolean = true,
) {
    val transition = rememberInfiniteTransition(label = "route")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 40f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 900, easing = LinearEasing)),
        label = "route-phase",
    )

    Canvas(modifier = modifier) {
        val path = Path().apply {
            moveTo(0f, size.height / 2f)
            lineTo(size.width, size.height / 2f)
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(
                width = 2.dp.toPx(),
                pathEffect = if (animating) {
                    PathEffect.dashPathEffect(floatArrayOf(12f, 10f), phase)
                } else {
                    null
                },
            ),
        )
    }
}

/** Small helper used by the peer chips' parent to keep labels to one line. */
@Composable
fun ChipLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
