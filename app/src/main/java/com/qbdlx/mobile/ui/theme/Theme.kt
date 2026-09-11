package com.qbdlx.mobile.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val QobuzPurple = Color(0xFF6C4CF1)
private val QobuzPurpleLight = Color(0xFFB9A7FF)
private val AccentCyan = Color(0xFF3DD6D0)

private val DarkScheme = darkColorScheme(
    primary = QobuzPurpleLight,
    onPrimary = Color(0xFF1B0F4A),
    primaryContainer = Color(0xFF3A2A8C),
    onPrimaryContainer = Color(0xFFE6DEFF),
    secondary = AccentCyan,
    onSecondary = Color(0xFF00312F),
    background = Color(0xFF0E0B14),
    onBackground = Color(0xFFE7E1F0),
    surface = Color(0xFF16121F),
    onSurface = Color(0xFFE7E1F0),
    surfaceVariant = Color(0xFF241E33),
    onSurfaceVariant = Color(0xFFBFB6D3),
    outline = Color(0xFF5B5273),
    error = Color(0xFFFFB4AB),
)

private val LightScheme = lightColorScheme(
    primary = QobuzPurple,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6DEFF),
    onPrimaryContainer = Color(0xFF1B0F4A),
    secondary = Color(0xFF00857F),
    onSecondary = Color.White,
    background = Color(0xFFFDF8FF),
    onBackground = Color(0xFF1C1B20),
    surface = Color(0xFFFDF8FF),
    onSurface = Color(0xFF1C1B20),
    surfaceVariant = Color(0xFFE8E0F0),
    onSurfaceVariant = Color(0xFF494551),
    outline = Color(0xFF7A757F),
)

@Composable
fun QobuzDlxTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> DarkScheme
        else -> LightScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = MaterialTheme.typography,
        content = content,
    )
}
