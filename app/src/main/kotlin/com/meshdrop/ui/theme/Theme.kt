package com.meshdrop.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Corner radii.
 *
 * Small on purpose: a 26dp pill on a phone in 2026 is the visual equivalent of typing in
 * Comic Sans. Hairline-bordered paper wants corners you can see through — 10dp on controls,
 * 16dp on the sheets that hold content, 24dp only on the offer screen, which is meant to feel
 * like a card being slid across a table.
 */
val MeshShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(26.dp),
)

/**
 * The spacing scale, in one place.
 *
 * Material gives you no rhythm, so every screen invents one, and the result is a 14dp gap
 * above a 22dp gap above a 9dp gap. These are the only vertical/horizontal gaps the app is
 * allowed to use. [gutter] is the page margin; [block] is the gap between two sections.
 */
object Space {
    val hair: Dp = 2.dp
    val xs: Dp = 4.dp
    val sm: Dp = 8.dp
    val md: Dp = 12.dp
    val lg: Dp = 16.dp
    val xl: Dp = 24.dp
    val xxl: Dp = 32.dp

    /** Left/right page margin. */
    val gutter: Dp = 20.dp

    /** Between two blocks of content on the same page. */
    val block: Dp = 28.dp

    /** Between a label and the thing it labels. */
    val label: Dp = 6.dp

    /** The height every tappable row must clear, thumb or no thumb. */
    val touchTarget: Dp = 48.dp
}

/** Thickness of every separator in the app. */
val HairlineWidth: Dp = 1.dp

/** The active palette, injected once by [MeshDropTheme]. */
val LocalMeshColors = staticCompositionLocalOf { MeshColors.Paper }

/**
 * The one place a screen reads colour from.
 *
 * Components ask [MeshTheme.colors] rather than `MaterialTheme.colorScheme`, so the app's own
 * vocabulary (hairline, subtle, clay, inkMuted) is always available, while stock Material
 * components still get a coherent scheme underneath.
 */
object MeshTheme {
    val colors: MeshColors
        @Composable
        @ReadOnlyComposable
        get() = LocalMeshColors.current
}

/**
 * MeshDrop's theme.
 *
 * Three deliberate departures from the previous one:
 *
 *  * **Paper first.** The palette follows the system setting, and the light scheme is the one
 *    that got designed first, because most transfers happen in daylight, in a kitchen, with
 *    two people looking at the same screen. The dark scheme is a real second theme (warm
 *    ink), not an inversion.
 *  * **No dynamic colour.** Material You repaints the app into whatever wallpaper the user
 *    has, which is charming for a note-taking app and wrong for this one: clay means "these
 *    bytes, this person", and a green wallpaper would turn the progress bar green for no
 *    reason. The identity is the product here, so it is not delegated.
 *  * **Serif headlines.** See [MeshTypography].
 */
@Composable
fun MeshDropTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) MeshColors.Ink else MeshColors.Paper

    CompositionLocalProvider(LocalMeshColors provides colors) {
        MaterialTheme(
            colorScheme = colors.scheme,
            typography = MeshTypography,
            shapes = MeshShapes,
            content = content,
        )
    }
}
