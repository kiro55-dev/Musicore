package com.example.musicore

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Dark = darkColorScheme(
    primary = Color(0xFFD0BCFF), onPrimary = Color(0xFF381E72),
    primaryContainer = Color(0xFF4F378B), onPrimaryContainer = Color(0xFFEADDFF),
    secondary = Color(0xFFCCC2DC), onSecondary = Color(0xFF332D41),
    secondaryContainer = Color(0xFF4A4458), onSecondaryContainer = Color(0xFFE8DEF8),
    tertiary = Color(0xFFEFB8C8),
    background = Color(0xFF16121D), onBackground = Color(0xFFE8E0EB),
    surface = Color(0xFF16121D), onSurface = Color(0xFFE8E0EB),
    surfaceVariant = Color(0xFF4A4458), onSurfaceVariant = Color(0xFFCCC4D4),
    outline = Color(0xFF958E9E), surfaceTint = Color(0xFFD0BCFF),
    surfaceContainerLowest = Color(0xFF110D18), surfaceContainerLow = Color(0xFF1E1A26),
    surfaceContainer = Color(0xFF231F2B), surfaceContainerHigh = Color(0xFF2E2936),
    surfaceContainerHighest = Color(0xFF393441),
)

private val Light = lightColorScheme(
    primary = Color(0xFF6750A4), onPrimary = Color.White,
    primaryContainer = Color(0xFFEADDFF), onPrimaryContainer = Color(0xFF21005D),
    secondary = Color(0xFF625B71), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE8DEF8), onSecondaryContainer = Color(0xFF1D192B),
    tertiary = Color(0xFF7D5260),
    background = Color(0xFFFEF7FF), onBackground = Color(0xFF1D1B20),
    surface = Color(0xFFFEF7FF), onSurface = Color(0xFF1D1B20),
    surfaceVariant = Color(0xFFE7E0EC), onSurfaceVariant = Color(0xFF49454F),
    outline = Color(0xFF7A757F), surfaceTint = Color(0xFF6750A4),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF8F1FA),
    surfaceContainer = Color(0xFFF3EDF7), surfaceContainerHigh = Color(0xFFEDE6F2),
    surfaceContainerHighest = Color(0xFFE7E0EC),
)

@Composable
fun MusicoreTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
