package com.meshdrop.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The MeshDrop palette: warm paper, ink, and one clay accent.
 *
 * The previous palette was a dark control room — teal on midnight, glow everywhere. It read
 * as a network console, which is exactly what this app is *not* for the person holding the
 * phone: they are standing next to a friend, handing over a photo album. So the surface is
 * paper, the text is ink, and colour is spent on one thing at a time.
 *
 * Three rules keep it honest:
 *
 *  1. **One accent.** [clay] means "this is the thing to tap" or "these are the bytes you are
 *     waiting for". Nothing decorative is ever clay, so a glance always finds the action.
 *  2. **Never colour alone.** Every state that has a colour (delivered, failed, verified) also
 *     has a word and a glyph. The palette is a reinforcement, not the message.
 *  3. **Warm neutrals, not grey.** The whole ramp is tinted towards yellow/red
 *     (R > G > B in every token), which is what makes a screen feel like paper instead of
 *     like a spreadsheet.
 *
 * Contrast: every text token clears WCAG AA on the surface it is used on (ink 15:1, muted
 * 5.2:1, clay-on-paper 5.3:1). The lighter [ember] is for fills, rules and glyphs — never for
 * small text.
 */
data class MeshColors(
    /** The page itself. */
    val canvas: Color,
    /** A raised sheet: cards, the tray, the offer screen. */
    val surface: Color,
    /** A sunken fill: chips, input fields, the inactive track of a bar. */
    val subtle: Color,
    /** Primary text. */
    val ink: Color,
    /** Secondary text, captions, quiet labels. */
    val inkMuted: Color,
    /** Text on a [clay] fill. */
    val onClay: Color,
    /** 1dp separators. Deliberately almost invisible. */
    val hairline: Color,

    /** The single accent. Buttons, the live progress fill, the one number that matters. */
    val clay: Color,
    /** A lighter accent for large glyphs, rules and the head of a progress bar. */
    val ember: Color,
    /** A soft accent wash for selected rows. */
    val clayWash: Color,

    /** Delivered, verified, intact. */
    val success: Color,
    /** Waiting, closing soon, risky but not fatal. */
    val warning: Color,
    /** Failed, rejected, not encrypted. */
    val danger: Color,

    /** Avatar tint per device, chosen deterministically from the device id. */
    val deviceTints: List<Color>,
) {

    /**
     * The Material 3 scheme, so stock components (Switch, TextField, Menu, Snackbar) inherit
     * the same voice without being re-skinned one by one.
     */
    val scheme: androidx.compose.material3.ColorScheme
        get() = if (isLight) {
            androidx.compose.material3.lightColorScheme(
                primary = clay,
                onPrimary = onClay,
                primaryContainer = clayWash,
                onPrimaryContainer = ink,
                secondary = ink,
                onSecondary = canvas,
                secondaryContainer = subtle,
                onSecondaryContainer = ink,
                tertiary = warning,
                onTertiary = canvas,
                background = canvas,
                onBackground = ink,
                surface = surface,
                onSurface = ink,
                surfaceVariant = subtle,
                onSurfaceVariant = inkMuted,
                outline = hairline,
                outlineVariant = hairline,
                error = danger,
                onError = Color.White,
                errorContainer = danger.copy(alpha = 0.12f),
                onErrorContainer = danger,
            )
        } else {
            androidx.compose.material3.darkColorScheme(
                primary = clay,
                onPrimary = onClay,
                primaryContainer = clayWash,
                onPrimaryContainer = ink,
                secondary = ink,
                onSecondary = canvas,
                secondaryContainer = subtle,
                onSecondaryContainer = ink,
                tertiary = warning,
                onTertiary = canvas,
                background = canvas,
                onBackground = ink,
                surface = surface,
                onSurface = ink,
                surfaceVariant = subtle,
                onSurfaceVariant = inkMuted,
                outline = hairline,
                outlineVariant = hairline,
                error = danger,
                onError = Color(0xFF2A0F0B),
                errorContainer = danger.copy(alpha = 0.18f),
                onErrorContainer = danger,
            )
        }

    /** True for [Paper], false for [Ink]. Derived so a new theme cannot forget to say. */
    val isLight: Boolean get() = brightness(canvas) > 0.5f

    private fun brightness(color: Color): Float =
        0.299f * color.red + 0.587f * color.green + 0.114f * color.blue

    companion object {

        /** Daylight: paper, ink, clay. The default. */
        val Paper = MeshColors(
            canvas = Color(0xFFFAF9F5),
            surface = Color(0xFFFFFFFF),
            subtle = Color(0xFFF1EFE7),
            ink = Color(0xFF1A1915),
            inkMuted = Color(0xFF6E6A61),
            onClay = Color(0xFFFFFBF8),
            hairline = Color(0xFFE5E1D6),

            clay = Color(0xFFB04A2A),
            ember = Color(0xFFD97757),
            clayWash = Color(0xFFF6E9E2),

            success = Color(0xFF3D7A4E),
            warning = Color(0xFF8A5A12),
            danger = Color(0xFFA83A2B),

            deviceTints = listOf(
                Color(0xFFB04A2A),
                Color(0xFF3F6577),
                Color(0xFF6B5A8E),
                Color(0xFF3D7A4E),
                Color(0xFF9A6E1F),
                Color(0xFF8A4C63),
            ),
        )

        /** After dark: warm ink, the same clay, no glow. */
        val Ink = MeshColors(
            canvas = Color(0xFF1B1A18),
            surface = Color(0xFF232220),
            subtle = Color(0xFF2C2B28),
            ink = Color(0xFFF3F1EA),
            inkMuted = Color(0xFFA8A49A),
            onClay = Color(0xFF201310),
            hairline = Color(0xFF38362F),

            clay = Color(0xFFE08262),
            ember = Color(0xFFE9A184),
            clayWash = Color(0xFF35251F),

            success = Color(0xFF7FBF95),
            warning = Color(0xFFE0B15C),
            danger = Color(0xFFE08A80),

            deviceTints = listOf(
                Color(0xFFE08262),
                Color(0xFF8FB6C9),
                Color(0xFFAFA0D8),
                Color(0xFF7FBF95),
                Color(0xFFD4B06A),
                Color(0xFFD19BB0),
            ),
        )
    }
}
