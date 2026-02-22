package com.musicdownloader.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF1DB954),
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF1A7A3A),
    onPrimaryContainer = Color(0xFFB7F5CC),
    secondary = Color(0xFFB3B3B3),
    onSecondary = Color.Black,
    background = Color(0xFF121212),
    onBackground = Color.White,
    surface = Color(0xFF1E1E1E),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF282828),
    onSurfaceVariant = Color(0xFFB3B3B3),
    error = Color(0xFFCF6679),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF1DB954),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB7F5CC),
    onPrimaryContainer = Color(0xFF002110),
    secondary = Color(0xFF535353),
    onSecondary = Color.White,
    background = Color(0xFFF8F8F8),
    onBackground = Color(0xFF1A1A1A),
    surface = Color.White,
    onSurface = Color(0xFF1A1A1A),
    surfaceVariant = Color(0xFFF0F0F0),
    onSurfaceVariant = Color(0xFF535353),
)

@Composable
fun SpotifyMusicPlayerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        content = content
    )
}
