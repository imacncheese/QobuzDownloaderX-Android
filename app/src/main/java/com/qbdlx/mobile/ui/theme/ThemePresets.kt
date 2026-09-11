package com.qbdlx.mobile.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * Visual themes.
 *
 * A preset only needs to declare a handful of anchor colours; the full Material 3
 * role set is derived in [ThemePalette.schemeFor] so every preset gets consistent
 * containers, outlines and disabled states rather than a half-applied palette.
 *
 * Dynamic colour (Material You) is deliberately **not** the default here: it
 * replaces the brand palette with wallpaper colours, which is what made the app
 * look unstyled on Android 12+. It stays available as an explicit choice.
 */
enum class ThemePreset(
    val id: String,
    val label: String,
    val description: String,
) {
    QOBUZ(
        id = "qobuz",
        label = "Qobuz",
        description = "Purple and cyan, the app's default identity",
    ),
    AMOLED(
        id = "amoled",
        label = "AMOLED Black",
        description = "True-black surfaces that save power on OLED screens",
    ),
    MIDNIGHT(
        id = "midnight",
        label = "Midnight",
        description = "Deep blue-grey, easier on the eyes at night",
    ),
    LIGHT(
        id = "light",
        label = "Daylight",
        description = "Bright, high-contrast palette for daytime use",
    ),
    DYNAMIC(
        id = "dynamic",
        label = "Match wallpaper",
        description = "Material You colours taken from your wallpaper (Android 12+)",
    );

    companion object {
        fun fromId(id: String?): ThemePreset =
            entries.firstOrNull { it.id == id } ?: QOBUZ

        /** Presets that make sense in each mode. */
        val selectable: List<ThemePreset> get() = entries.toList()
    }
}

/** Which mode the app renders in. */
enum class ThemeMode(val id: String, val label: String) {
    SYSTEM("system", "Follow system"),
    DARK("dark", "Dark"),
    LIGHT("light", "Light");

    companion object {
        fun fromId(id: String?): ThemeMode = entries.firstOrNull { it.id == id } ?: SYSTEM
    }
}

/**
 * Derives a complete [ColorScheme] from a few anchor colours.
 *
 * Everything is computed rather than hand-listed so that adding a preset cannot
 * leave a role undefined, and so light/dark stay in step.
 */
object ThemePalette {

    data class Anchors(
        val primary: Color,
        val secondary: Color,
        val background: Color,
        val surface: Color,
        /** Slightly lifted surface used for cards and list rows. */
        val surfaceVariant: Color,
        val onBackground: Color,
        val onSurfaceVariant: Color,
    )

    fun anchorsFor(preset: ThemePreset, dark: Boolean): Anchors = when (preset) {
        ThemePreset.QOBUZ -> if (dark) Anchors(
            primary = Color(0xFFB9A7FF),
            secondary = Color(0xFF3DD6D0),
            background = Color(0xFF0E0B14),
            surface = Color(0xFF16121F),
            surfaceVariant = Color(0xFF241E33),
            onBackground = Color(0xFFE7E1F0),
            onSurfaceVariant = Color(0xFFBFB6D3),
        ) else Anchors(
            primary = Color(0xFF6C4CF1),
            secondary = Color(0xFF00857F),
            background = Color(0xFFFDF8FF),
            surface = Color(0xFFFFFFFF),
            surfaceVariant = Color(0xFFEDE5F7),
            onBackground = Color(0xFF1C1B20),
            onSurfaceVariant = Color(0xFF494551),
        )

        ThemePreset.AMOLED -> if (dark) Anchors(
            primary = Color(0xFFC4B4FF),
            secondary = Color(0xFF5FE3DC),
            background = Color(0xFF000000),
            surface = Color(0xFF000000),
            surfaceVariant = Color(0xFF14121A),
            onBackground = Color(0xFFEDE9F5),
            onSurfaceVariant = Color(0xFFB4ABC7),
        ) else Anchors(
            primary = Color(0xFF5B3FD6),
            secondary = Color(0xFF00726D),
            background = Color(0xFFF7F5FB),
            surface = Color(0xFFFFFFFF),
            surfaceVariant = Color(0xFFE9E4F2),
            onBackground = Color(0xFF141218),
            onSurfaceVariant = Color(0xFF48444F),
        )

        ThemePreset.MIDNIGHT -> if (dark) Anchors(
            primary = Color(0xFF9FC4FF),
            secondary = Color(0xFF8ED4E8),
            background = Color(0xFF0B1220),
            surface = Color(0xFF111A2C),
            surfaceVariant = Color(0xFF1B2740),
            onBackground = Color(0xFFDEE5F5),
            onSurfaceVariant = Color(0xFFA9B6D0),
        ) else Anchors(
            primary = Color(0xFF2B5FC7),
            secondary = Color(0xFF00707F),
            background = Color(0xFFF6F8FE),
            surface = Color(0xFFFFFFFF),
            surfaceVariant = Color(0xFFE2E8F6),
            onBackground = Color(0xFF161C28),
            onSurfaceVariant = Color(0xFF464E5E),
        )

        ThemePreset.LIGHT -> if (dark) Anchors(
            primary = Color(0xFFFFC98A),
            secondary = Color(0xFFFFD3B0),
            background = Color(0xFF1A1512),
            surface = Color(0xFF231C18),
            surfaceVariant = Color(0xFF342A24),
            onBackground = Color(0xFFF5E9E1),
            onSurfaceVariant = Color(0xFFD3BFAF),
        ) else Anchors(
            primary = Color(0xFFB8632A),
            secondary = Color(0xFF8A5A00),
            background = Color(0xFFFFF8F3),
            surface = Color(0xFFFFFFFF),
            surfaceVariant = Color(0xFFF6E7DC),
            onBackground = Color(0xFF241A14),
            onSurfaceVariant = Color(0xFF5A463A),
        )

        // DYNAMIC is resolved from the platform; these anchors are only a
        // fallback for API < 31 where wallpaper colours are unavailable.
        ThemePreset.DYNAMIC -> anchorsFor(ThemePreset.QOBUZ, dark)
    }

    /**
     * Builds the full role set. [tint] optionally overrides the accent roles so
     * album artwork can drive the palette.
     */
    fun schemeFor(
        preset: ThemePreset,
        dark: Boolean,
        tint: Color? = null,
    ): ColorScheme {
        val a = anchorsFor(preset, dark)
        val primary = tint ?: a.primary
        val onPrimary = if (dark) Color(0xFF1B0F3A) else Color.White

        return if (dark) darkColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = lerp(primary, a.surface, 0.62f),
            onPrimaryContainer = lerp(primary, Color.White, 0.72f),
            secondary = a.secondary,
            onSecondary = Color(0xFF00201E),
            secondaryContainer = lerp(a.secondary, a.surface, 0.68f),
            onSecondaryContainer = lerp(a.secondary, Color.White, 0.75f),
            tertiary = lerp(primary, a.secondary, 0.5f),
            background = a.background,
            onBackground = a.onBackground,
            surface = a.surface,
            onSurface = a.onBackground,
            surfaceVariant = a.surfaceVariant,
            onSurfaceVariant = a.onSurfaceVariant,
            surfaceContainerLowest = if (preset == ThemePreset.AMOLED) Color.Black else lerp(a.surface, a.background, 0.45f),
            surfaceContainerLow = a.surface,
            surfaceContainer = lerp(a.surface, a.surfaceVariant, 0.45f),
            surfaceContainerHigh = lerp(a.surface, a.surfaceVariant, 0.75f),
            surfaceContainerHighest = a.surfaceVariant,
            outline = lerp(a.onSurfaceVariant, a.surface, 0.42f),
            outlineVariant = lerp(a.onSurfaceVariant, a.surface, 0.74f),
            error = Color(0xFFFFB4AB),
            onError = Color(0xFF690005),
            errorContainer = Color(0xFF5C1A16),
            onErrorContainer = Color(0xFFFFDAD6),
            scrim = Color.Black,
        ) else lightColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = lerp(primary, Color.White, 0.82f),
            onPrimaryContainer = lerp(primary, Color.Black, 0.55f),
            secondary = a.secondary,
            onSecondary = Color.White,
            secondaryContainer = lerp(a.secondary, Color.White, 0.82f),
            onSecondaryContainer = lerp(a.secondary, Color.Black, 0.6f),
            tertiary = lerp(primary, a.secondary, 0.5f),
            background = a.background,
            onBackground = a.onBackground,
            surface = a.surface,
            onSurface = a.onBackground,
            surfaceVariant = a.surfaceVariant,
            onSurfaceVariant = a.onSurfaceVariant,
            surfaceContainerLowest = Color.White,
            surfaceContainerLow = a.surface,
            surfaceContainer = lerp(a.surface, a.surfaceVariant, 0.5f),
            surfaceContainerHigh = lerp(a.surface, a.surfaceVariant, 0.78f),
            surfaceContainerHighest = a.surfaceVariant,
            outline = lerp(a.onSurfaceVariant, a.surface, 0.35f),
            outlineVariant = lerp(a.onSurfaceVariant, a.surface, 0.7f),
            error = Color(0xFFB3261E),
            onError = Color.White,
            errorContainer = Color(0xFFF9DEDC),
            onErrorContainer = Color(0xFF410E0B),
            scrim = Color.Black,
        )
    }

    /**
     * WCAG relative luminance of a colour, 0 (black) to 1 (white).
     *
     * Uses the sRGB transfer function rather than a plain weighted average:
     * averaging overstates the brightness of mid colours, which made the brand
     * purple look "too dark" on a dark theme and get lightened needlessly.
     */
    fun luminanceOf(color: Color): Float {
        fun channel(c: Float): Float =
            if (c <= 0.03928f) c / 12.92f else Math.pow(((c + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()

        return 0.2126f * channel(color.red) +
            0.7152f * channel(color.green) +
            0.0722f * channel(color.blue)
    }

    /** WCAG contrast ratio between two colours, 1 (identical) to 21 (black/white). */
    fun contrastRatio(a: Color, b: Color): Float {
        val la = luminanceOf(a)
        val lb = luminanceOf(b)
        val lighter = maxOf(la, lb)
        val darker = minOf(la, lb)
        return (lighter + 0.05f) / (darker + 0.05f)
    }

    /** Minimum contrast for an accent used as text or an icon fill. */
    private const val MIN_ACCENT_CONTRAST = 4.5f

    /**
     * Nudges an accent until it is legible against [surface].
     *
     * Artwork- and wallpaper-derived colours are arbitrary, so a dark or washed-out
     * cover could otherwise produce an unreadable accent. Colours that already
     * meet the contrast target are returned untouched, which keeps brand and
     * artwork colours honest instead of always lightening them.
     */
    fun legibleAccent(raw: Color, surface: Color): Color {
        if (contrastRatio(raw, surface) >= MIN_ACCENT_CONTRAST) return raw

        // Move toward white or black, whichever direction the surface allows,
        // and stop as soon as the target is met rather than overshooting.
        val toward = if (luminanceOf(surface) < 0.5f) Color.White else Color.Black
        var best = raw
        for (step in 1..10) {
            val candidate = lerp(raw, toward, step / 10f)
            best = candidate
            if (contrastRatio(candidate, surface) >= MIN_ACCENT_CONTRAST) break
        }
        return best
    }

    /** Convenience overload for the common "tint on the current background" case. */
    fun legibleAccent(raw: Color, dark: Boolean): Color =
        legibleAccent(raw, if (dark) Color(0xFF0E0B14) else Color(0xFFFDF8FF))
}
