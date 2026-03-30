package com.example.testapplication.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val VocabDarkScheme = darkColorScheme(
    primary = AccentBlue,
    onPrimary = Color.Black,
    secondary = AccentOrange,
    onSecondary = Color.Black,
    tertiary = AccentPink,
    background = DarkBackground,
    onBackground = TextPrimary,
    surface = DarkSurface,
    onSurface = TextPrimary,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    outline = TextHint,
    primaryContainer = Color(0xFF1A3A5C),
    onPrimaryContainer = AccentBlue,
    secondaryContainer = Color(0xFF3A2E1A),
    onSecondaryContainer = AccentOrange,
    error = Color(0xFFCF6679),
    onError = Color.Black
)

@Composable
fun TestApplicationTheme(
    content: @Composable () -> Unit
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context as? Activity ?: return@SideEffect
            val window = activity.window
            window.statusBarColor = DarkBackground.toArgb()
            window.navigationBarColor = DarkBackground.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }

    MaterialTheme(
        colorScheme = VocabDarkScheme,
        typography = Typography,
        content = content
    )
}
