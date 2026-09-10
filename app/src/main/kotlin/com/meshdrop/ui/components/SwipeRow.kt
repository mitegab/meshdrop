package com.meshdrop.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

data class SwipeAction(
    val icon: ImageVector,
    val label: String,
    val tint: Color,
    val onClick: () -> Unit,
)

/**
 * A row you can drag left to reveal actions (re-share, delete, move to vault).
 *
 * Written against `pointerInput` rather than a library: it keeps the gesture ours, so the
 * reveal width, snap behaviour and the fact that a tap on an action closes the row are all
 * explicit here.
 */
@Composable
fun SwipeRow(
    actions: List<SwipeAction>,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val revealWidth = 64.dp * actions.size
    val revealPx = remember(revealWidth, density) { with(density) { revealWidth.toPx() } }
    val offset = remember { Animatable(0f) }

    Box(modifier = modifier.clip(RoundedCornerShape(16.dp))) {
        // Actions live behind the row, revealed as it slides.
        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceVariant),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            actions.forEach { action ->
                IconButton(
                    onClick = {
                        scope.launch { offset.animateTo(0f, tween(180)) }
                        action.onClick()
                    },
                    modifier = Modifier.padding(horizontal = 2.dp),
                ) {
                    Icon(
                        imageVector = action.icon,
                        contentDescription = action.label,
                        tint = action.tint,
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .pointerInput(actions.size) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            val shouldOpen = offset.value < -revealPx * 0.4f
                            scope.launch {
                                offset.animateTo(if (shouldOpen) -revealPx else 0f, tween(180))
                            }
                        },
                        onHorizontalDrag = { _, dragAmount ->
                            scope.launch {
                                val target = (offset.value + dragAmount).coerceIn(-revealPx, 0f)
                                offset.snapTo(target)
                            }
                        },
                    )
                },
        ) {
            content()
        }
    }
}
