package com.qbdlx.mobile.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * Resolves the active theme.
 *
 * Dynamic colour is opt-in ([ThemePreset.DYNAMIC]) rather than the default, so the
 * chosen preset is actually the palette the user sees.
 *
 * [accentOverride] lets cover art drive the accent colour, and [glass] makes the
 * surfaces translucent and tinted. Neither has any effect at its neutral value.
 */
@Composable
fun QobuzDlxTheme(
    preset: ThemePreset = ThemePreset.QOBUZ,
    mode: ThemeMode = ThemeMode.SYSTEM,
    cornerScale: Float = AppShapes.DEFAULT_SCALE,
    accentOverride: Color? = null,
    glass: GlassTint = GlassTint.NONE,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val systemDark = isSystemInDarkTheme()

    val dark = when (mode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }

    val dynamicAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    // Wallpaper colours already carry their own surfaces, so the glass treatment
    // is not applied on top of them: it would fight the platform palette.
    val useDynamic = preset == ThemePreset.DYNAMIC && dynamicAvailable && accentOverride == null

    val colorScheme = when {
        useDynamic -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        else -> ThemePalette.schemeFor(
            preset = if (preset == ThemePreset.DYNAMIC) ThemePreset.QOBUZ else preset,
            dark = dark,
            tint = accentOverride?.let { ThemePalette.legibleAccent(it, dark) },
            glass = glass,
        )
    }

    val shapes = AppShapes(AppShapes.clamp(cornerScale))

    CompositionLocalProvider(LocalShapes provides shapes) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = MaterialTheme.typography,
            shapes = shapes.toMaterialShapes(),
            content = content,
        )
    }
}
