package com.meshdrop.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColors = darkColorScheme(
    primary = MeshTeal,
    onPrimary = Color(0xFF00201C),
    primaryContainer = MeshTealContainer,
    onPrimaryContainer = Color(0xFF9BF6E8),
    secondary = Color(0xFF9FC6FF),
    onSecondary = Color(0xFF002E5A),
    secondaryContainer = Color(0xFF12314F),
    onSecondaryContainer = Color(0xFFD3E4FF),
    background = Midnight,
    onBackground = Color(0xFFE3EDF6),
    surface = MidnightSurface,
    onSurface = Color(0xFFE3EDF6),
    surfaceVariant = MidnightSurfaceHigh,
    onSurfaceVariant = Color(0xFFA9BCCE),
    outline = Color(0xFF3C5064),
    error = Danger,
    onError = Color(0xFF3B0003),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF00796B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFBDF3EB),
    onPrimaryContainer = Color(0xFF00201C),
    secondary = Color(0xFF0F5C9E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD3E4FF),
    onSecondaryContainer = Color(0xFF002E5A),
    background = Color(0xFFF7FAFC),
    onBackground = Color(0xFF0B1622),
    surface = Color.White,
    onSurface = Color(0xFF0B1622),
    surfaceVariant = Color(0xFFE8EFF5),
    onSurfaceVariant = Color(0xFF465A6B),
    outline = Color(0xFFA9BCCE),
    error = Color(0xFFB3261E),
    onError = Color.White,
)

/** Shared typography scale for both colour schemes. */
val AppTypography = Typography()

$/**
 * MeshDrop is dark-first.
 *
 * This is a systems tool: the screens are dominated by a live network graph, throughput
 * readouts and telemetry, and those read as noise on a white consumer-style background.
 * Material You is still honoured on Android 12+, but the *default* (used whenever dynamic
 * colour is off, including in previews and on older devices) is the midnight palette.
 */
@Composable
fun MeshDropTheme(
    darkTheme: Boolean = true,
    /** Material You is honoured on Android 12+, we fall back to the brand palette elsewhere. */
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colors,
        typography = AppTypography,
        content = content,
    )
}
