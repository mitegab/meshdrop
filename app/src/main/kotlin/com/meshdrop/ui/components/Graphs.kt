package com.meshdrop.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import com.meshdrop.core.model.TransportLane
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Hand-drawn telemetry for the Advanced/Network panel.
 *
 * A chart library would add a dependency and a build step for two pictures that are really
 * just "line goes up" and "these two dots are connected"; drawing them on `Canvas` keeps the
 * APK small and lets the failover animation be exactly what we want.
 */

/** Live throughput: filled area + line, autoscaled to the window's peak. */
@Composable
fun ThroughputGraph(
    samples: List<Long>,
    peak: Long,
    modifier: Modifier = Modifier,
    color: Color = Color(0xFF00CBB4),
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        if (samples.isEmpty()) return@Canvas

        val scale = peak.toFloat().coerceAtLeast(1f)
        val stepX = size.width / (samples.size - 1).coerceAtLeast(1).toFloat()

        fun x(index: Int) = index * stepX
        fun y(value: Long) = size.height - (value.toFloat() / scale) * size.height * 0.92f

        // Area
        val fill = Path().apply {
            moveTo(0f, size.height)
            samples.forEachIndexed { index, value -> lineTo(x(index), y(value)) }
            lineTo(size.width, size.height)
            close()
        }
        drawPath(
            path = fill,
            brush = Brush.verticalGradient(
                colors = listOf(color.copy(alpha = 0.35f), color.copy(alpha = 0.02f)),
                startY = 0f,
                endY = size.height,
            ),
        )

        // Line
        val line = Path().apply {
            moveTo(x(0), y(samples.first()))
            samples.forEachIndexed { index, value -> if (index > 0) lineTo(x(index), y(value)) }
        }
        drawPath(path = line, color = color, style = Stroke(width = 2f))

        // Baseline
        drawLine(
            color = Color.White.copy(alpha = 0.10f),
            start = Offset(0f, size.height - 1f),
            end = Offset(size.width, size.height - 1f),
        )
    }
}

/**
 * Route topology: us → (optional relay) → peer.
 *
 * The MVP always draws the direct path; when relay routing lands the same composable renders
 * the extra hop, and [activeLane] decides which line is animated.
 */
@Composable
fun RouteTopology(
    activeLane: TransportLane,
    peerName: String,
    selfName: String,
    modifier: Modifier = Modifier,
    hasRelay: Boolean = false,
    relayName: String? = null,
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val nodeRadius = size.minDimension * 0.10f
        val left = Offset(size.width * 0.18f, size.height * 0.5f)
        val right = Offset(size.width * 0.82f, size.height * 0.5f)
        val middle = Offset(size.width * 0.5f, size.height * 0.22f)

        val lineColor = TransportVisual.of(activeLane).tint
        val idle = Color.White.copy(alpha = 0.18f)

        // Direct link (dimmed when we are going through a relay).
        drawLine(
            color = if (hasRelay) idle else lineColor,
            start = left,
            end = right,
            strokeWidth = if (hasRelay) 2f else 4f,
        )

        if (hasRelay) {
            drawLine(color = lineColor, start = left, end = middle, strokeWidth = 4f)
            drawLine(color = lineColor, start = middle, end = right, strokeWidth = 4f)
            drawCircle(color = Color(0xFFFFB74D), radius = nodeRadius * 0.7f, center = middle)
        }

        drawCircle(color = Color(0xFF00CBB4), radius = nodeRadius, center = left)
        drawCircle(color = Color(0xFF4FC3F7), radius = nodeRadius, center = right)
    }
}

/** Radial "device found" ring used behind a peer avatar that just snapped into place. */
@Composable
fun DeviceHalo(
    modifier: Modifier = Modifier,
    color: Color = Color(0xFF00CBB4),
    segments: Int = 12,
) {
    Canvas(modifier = modifier) {
        val radius = size.minDimension / 2f
        for (index in 0 until segments) {
            val angle = (index.toFloat() / segments) * 2f * PI.toFloat()
            drawCircle(
                color = color.copy(alpha = 0.15f + 0.25f * (index.toFloat() / segments)),
                radius = radius * 0.12f,
                center = Offset(
                    x = size.width / 2f + cos(angle) * radius * 0.8f,
                    y = size.height / 2f + sin(angle) * radius * 0.8f,
                ),
            )
        }
    }
}

/** Tiny legend swatch so graphs and lane strips share one colour story. */
@Composable
fun LaneSwatch(lane: TransportLane, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        drawRect(color = TransportVisual.of(lane).tint, size = Size(size.width, size.height))
    }
}
