/*
 * ONA Launcher 2 (based on Zalith Launcher 2)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/gpl-3.0.txt>.
 */

package com.movtery.zalithlauncher.ui.theme

import androidx.annotation.StringRes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme
import com.movtery.zalithlauncher.R

/**
 * ONA: the look of the whole launcher in one choice. Every style keeps every feature, it only
 * changes colours, corner shapes and text (font, size). ZALITH keeps the colour theme the user
 * picked; CUSTOM uses that colour theme with the user's own corners and text size.
 *
 * @param seed main colour the palette is generated from, null = keep the colour theme setting
 * @param radius corner size of medium shapes (cards), null = Material defaults
 * @param fontScale text size multiplier
 * @param mono monospace text (pixel / terminal look)
 * @param glass see-through containers so the background shows
 * @param pureBlack black surfaces (AMOLED, saves battery)
 * @param dynamic wallpaper colours (Android 12+)
 * @param darkBase forces a dark look built on this background colour (console menus)
 * @param thinText light font weight for body and titles (PS3 menu look)
 * @param cut angled (cut) corners instead of round ones
 */
enum class LauncherStyle(
    @StringRes val titleRes: Int,
    val seed: Color? = null,
    val palette: PaletteStyle = PaletteStyle.TonalSpot,
    val radius: Int? = null,
    val fontScale: Float = 1f,
    val mono: Boolean = false,
    val glass: Boolean = false,
    val pureBlack: Boolean = false,
    val dynamic: Boolean = false,
    val boldTitles: Boolean = false,
    val darkBase: Color? = null,
    val thinText: Boolean = false,
    val cut: Boolean = false,
) {
    ZALITH(R.string.ona_style_zalith),
    /** Desktop game launcher: dark grey panels, green play colour, small corners, compact text */
    PC_LAUNCHER(R.string.ona_style_pc, seed = Color(0xFF3C8527), radius = 4, fontScale = 0.95f,
        darkBase = Color(0xFF1E1F22)),
    /** Modrinth site: dark slate, bright green, round cards */
    MODRINTH(R.string.ona_style_modrinth, seed = Color(0xFF1BD96A), palette = PaletteStyle.Vibrant, radius = 16,
        darkBase = Color(0xFF16181C)),
    /** Console dashboard: dark, green accent, big bold text */
    CONSOLE(R.string.ona_style_console, seed = Color(0xFF107C10), radius = 12, fontScale = 1.12f, boldTitles = true,
        darkBase = Color(0xFF0F1210)),
    MATERIAL_YOU(R.string.ona_style_material_you, radius = 28, dynamic = true),
    GLASS(R.string.ona_style_glass, radius = 24, glass = true),
    /** Old block game menus: stone grey, square buttons, pixel-like monospace text */
    RETRO_PIXEL(R.string.ona_style_retro, seed = Color(0xFF5B8731), palette = PaletteStyle.Fidelity, radius = 0, mono = true,
        boldTitles = true, darkBase = Color(0xFF2B2B2B)),
    AMOLED(R.string.ona_style_amoled, seed = Color(0xFF8AB4F8), radius = 20, pureBlack = true),
    /** Netherrack red on a dark blood background, rough small corners */
    NETHER(R.string.ona_style_nether, seed = Color(0xFFB3261E), palette = PaletteStyle.Vibrant, radius = 6,
        boldTitles = true, darkBase = Color(0xFF1C0907)),
    /** End void: deep purple dark, soft round corners */
    THE_END(R.string.ona_style_end, seed = Color(0xFF8E44AD), palette = PaletteStyle.Expressive, radius = 18,
        darkBase = Color(0xFF120D1C)),
    /** Sea blues, wave-round corners */
    OCEAN(R.string.ona_style_ocean, seed = Color(0xFF0277BD), palette = PaletteStyle.Vibrant, radius = 22),
    /** Warm orange to pink, round */
    SUNSET(R.string.ona_style_sunset, seed = Color(0xFFF57C00), palette = PaletteStyle.Vibrant, radius = 20),
    /** Soft pink blossom, very round petals */
    CHERRY(R.string.ona_style_cherry, seed = Color(0xFFE91E63), palette = PaletteStyle.Expressive, radius = 26, thinText = true),
    /** Neon pink on black, angled cut corners, terminal text */
    CYBERPUNK(R.string.ona_style_cyberpunk, seed = Color(0xFFFF00C8), palette = PaletteStyle.Vibrant, radius = 10, mono = true,
        pureBlack = true, boldTitles = true, cut = true),
    HIGH_CONTRAST(R.string.ona_style_contrast, seed = Color(0xFFFFD600), palette = PaletteStyle.Fidelity, radius = 8, fontScale = 1.08f, boldTitles = true),
    COMPACT(R.string.ona_style_compact, radius = 6, fontScale = 0.88f),
    /** Near black with white text, blue accent, soft rounded cards, large clean titles */
    PS5(R.string.ona_style_ps5, seed = Color(0xFF0070D1), radius = 14, fontScale = 1.06f, boldTitles = true,
        darkBase = Color(0xFF0B0D12)),
    /** Deep blue background, square tiles, bold titles */
    PS4(R.string.ona_style_ps4, seed = Color(0xFF1F6FD0), palette = PaletteStyle.Vibrant, radius = 3, fontScale = 1.04f,
        boldTitles = true, darkBase = Color(0xFF0A2350)),
    /** Dark wave menu: almost no corners, thin light text */
    PS3(R.string.ona_style_ps3, seed = Color(0xFF9FB4C8), palette = PaletteStyle.Neutral, radius = 1, fontScale = 1.02f,
        darkBase = Color(0xFF101828), thinText = true),
    /** New Pixel menus: wallpaper colours, very round, expressive and large */
    PIXEL(R.string.ona_style_pixel, palette = PaletteStyle.Expressive, radius = 32, fontScale = 1.05f,
        dynamic = true, boldTitles = true),
    CUSTOM(R.string.ona_style_custom);

    /** True when the style forces its own dark colours whatever the dark mode setting says */
    val forcesDark: Boolean get() = pureBlack || darkBase != null
}

private fun Color.lighten(amount: Float): Color = Color(
    red = red + (1f - red) * amount,
    green = green + (1f - green) * amount,
    blue = blue + (1f - blue) * amount,
    alpha = alpha
)

/** Colours of [style] on top of [base], the scheme of the user's colour theme */
fun LauncherStyle.colorScheme(base: ColorScheme, dark: Boolean, wallpaper: ColorScheme?): ColorScheme {
    var scheme = when {
        dynamic && wallpaper != null -> wallpaper
        seed != null -> dynamicColorScheme(primary = seed, isDark = dark || forcesDark, style = palette)
        else -> base
    }
    if (pureBlack) {
        scheme = scheme.copy(
            background = Color.Black,
            surface = Color.Black,
            surfaceDim = Color.Black,
            surfaceBright = Color(0xFF161616),
            surfaceContainerLowest = Color.Black,
            surfaceContainerLow = Color(0xFF0A0A0A),
            surfaceContainer = Color(0xFF111111),
            surfaceContainerHigh = Color(0xFF181818),
            surfaceContainerHighest = Color(0xFF202020),
        )
    }
    darkBase?.let { base ->
        scheme = scheme.copy(
            background = base,
            surface = base,
            surfaceDim = base,
            surfaceBright = base.lighten(0.14f),
            surfaceContainerLowest = base,
            surfaceContainerLow = base.lighten(0.04f),
            surfaceContainer = base.lighten(0.07f),
            surfaceContainerHigh = base.lighten(0.10f),
            surfaceContainerHighest = base.lighten(0.14f),
            onBackground = Color(0xFFF2F4F8),
            onSurface = Color(0xFFF2F4F8),
        )
    }
    if (this == LauncherStyle.HIGH_CONTRAST) {
        scheme = if (dark) scheme.copy(
            background = Color.Black, surface = Color.Black, onBackground = Color.White, onSurface = Color.White,
            onSurfaceVariant = Color.White, outline = Color.White
        ) else scheme.copy(
            background = Color.White, surface = Color.White, onBackground = Color.Black, onSurface = Color.Black,
            onSurfaceVariant = Color.Black, outline = Color.Black
        )
    }
    if (glass) {
        // Containers become see-through, menus and dialogs (High / Highest) stay readable
        scheme = scheme.copy(
            surface = scheme.surface.copy(alpha = 0.70f),
            surfaceBright = scheme.surfaceBright.copy(alpha = 0.55f),
            surfaceVariant = scheme.surfaceVariant.copy(alpha = 0.60f),
            surfaceContainerLowest = scheme.surfaceContainerLowest.copy(alpha = 0.55f),
            surfaceContainerLow = scheme.surfaceContainerLow.copy(alpha = 0.60f),
            surfaceContainer = scheme.surfaceContainer.copy(alpha = 0.65f),
        )
    }
    return scheme
}

/** Outer corner of the settings cards (Zalith default 28 dp) */
@androidx.compose.runtime.Composable
fun styleOuterRadius(): androidx.compose.ui.unit.Dp {
    val style = com.movtery.zalithlauncher.setting.AllSettings.launcherStyle.state
    val r = if (style == LauncherStyle.CUSTOM) com.movtery.zalithlauncher.setting.AllSettings.launcherStyleRadius.state
    else style.radius ?: return 28.dp
    return (r * 1.75f).dp
}

/** Corner shapes of the style, null keeps the Material defaults */
fun LauncherStyle.shapes(customRadius: Int): Shapes? {
    val r = (if (this == LauncherStyle.CUSTOM) customRadius else radius ?: return null).coerceAtLeast(0)
    fun shape(value: Float): androidx.compose.foundation.shape.CornerBasedShape =
        if (cut && this != LauncherStyle.CUSTOM) androidx.compose.foundation.shape.CutCornerShape(value.dp)
        else RoundedCornerShape(value.dp)
    return Shapes(
        extraSmall = shape(r * 0.25f),
        small = shape(r * 0.5f),
        medium = shape(r.toFloat()),
        large = shape(r * 1.4f),
        extraLarge = shape(r * 1.75f),
    )
}

/** Text of the style: size, monospace, bold titles */
fun LauncherStyle.typography(base: Typography, customScale: Float, customMono: Boolean): Typography {
    val scale = if (this == LauncherStyle.CUSTOM) customScale else fontScale
    val monospace = if (this == LauncherStyle.CUSTOM) customMono else mono
    if (scale == 1f && !monospace && !boldTitles && !thinText) return base
    fun TextStyle.styled(title: Boolean = false): TextStyle = copy(
        fontSize = if (fontSize.isSpecified) fontSize * scale else fontSize,
        lineHeight = if (lineHeight.isSpecified) lineHeight * scale else lineHeight,
        fontFamily = if (monospace) FontFamily.Monospace else fontFamily,
        fontWeight = when {
            thinText -> FontWeight.Light
            title && boldTitles -> FontWeight.Bold
            else -> fontWeight
        },
    )
    return base.copy(
        displayLarge = base.displayLarge.styled(true),
        displayMedium = base.displayMedium.styled(true),
        displaySmall = base.displaySmall.styled(true),
        headlineLarge = base.headlineLarge.styled(true),
        headlineMedium = base.headlineMedium.styled(true),
        headlineSmall = base.headlineSmall.styled(true),
        titleLarge = base.titleLarge.styled(true),
        titleMedium = base.titleMedium.styled(true),
        titleSmall = base.titleSmall.styled(true),
        bodyLarge = base.bodyLarge.styled(),
        bodyMedium = base.bodyMedium.styled(),
        bodySmall = base.bodySmall.styled(),
        labelLarge = base.labelLarge.styled(),
        labelMedium = base.labelMedium.styled(),
        labelSmall = base.labelSmall.styled(),
    )
}
