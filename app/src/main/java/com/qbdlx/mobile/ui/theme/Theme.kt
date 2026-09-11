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
 * chosen preset is actually the palette the user sees. [accentOverride] lets the
 * currently open album's cover art drive the accent colour.
 */
@Composable
fun QobuzDlxTheme(
    preset: ThemePreset = ThemePreset.QOBUZ,
    mode: ThemeMode = ThemeMode.SYSTEM,
    cornerScale: Float = AppShapes.DEFAULT_SCALE,
    accentOverride: Color? = null,
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

    val colorScheme = when {
        preset == ThemePreset.DYNAMIC && dynamicAvailable && accentOverride == null ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        else -> ThemePalette.schemeFor(
            preset = if (preset == ThemePreset.DYNAMIC) ThemePreset.QOBUZ else preset,
            dark = dark,
            tint = accentOverride?.let { ThemePalette.legibleAccent(it, dark) },
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
