package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val NeonDarkColorScheme = darkColorScheme(
    primary = NeonCyan,
    onPrimary = Color(0xFF001B20),
    secondary = NeonPink,
    onSecondary = Color(0xFF2C0012),
    tertiary = TextSecondary,
    background = DeepDark,
    onBackground = TextPrimary,
    surface = CardDark,
    onSurface = TextPrimary,
    error = RedError,
    onError = Color.White
)

@Composable
fun MyApplicationTheme(
    accentColor: Color = NeonCyan,
    darkTheme: Boolean = true, // Force dark theme for that immersive neon look
    dynamicColor: Boolean = false, // Use our gorgeous custom scheme instead of dynamic colors
    content: @Composable () -> Unit,
) {
    val dynamicScheme = darkColorScheme(
        primary = accentColor,
        onPrimary = Color(0xFF001B20),
        secondary = NeonPink,
        onSecondary = Color(0xFF2C0012),
        tertiary = TextSecondary,
        background = DeepDark,
        onBackground = TextPrimary,
        surface = CardDark,
        onSurface = TextPrimary,
        error = RedError,
        onError = Color.White
    )

    MaterialTheme(
        colorScheme = dynamicScheme,
        typography = Typography,
        content = content
    )
}
