package com.securevox.app.presentation.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.securevox.app.presentation.settings.ThemeMode

// Light scheme kept for ThemeMode.LIGHT, restyled to the same warm-neutral-on-ink
// logic inverted — Recorder itself has no light mode to match against, so this
// stays close to the prior Material baseline rather than inventing one.
private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF6B5FC7),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3E0FB),
    onPrimaryContainer = Color(0xFF2B2470),
    secondary = Color(0xFFD9776C),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFBE1DD),
    onSecondaryContainer = Color(0xFF5C1F1B),
    tertiary = Color(0xFF7E57C2),
    onTertiary = Color.White,
    background = Color(0xFFFAFAFA),
    onBackground = Color(0xFF1C1C1E),
    surface = Color.White,
    onSurface = Color(0xFF1C1C1E),
    surfaceVariant = Color(0xFFF5F5F5),
    onSurfaceVariant = Color(0xFF49454F),
    error = Color(0xFFD32F2F),
    onError = Color.White
)

// Dark scheme is the actual restyle target: fixed tokens sampled from Google
// Recorder, not Material You dynamic color. Coral is reserved for
// record/live/destructive; lavender for playback/resume/positive progress —
// each used for exactly one meaning throughout the app.
private val DarkColorScheme = darkColorScheme(
    primary = RecorderLavender,
    onPrimary = Color(0xFF1C1A33),
    primaryContainer = Color(0xFF383359),
    onPrimaryContainer = RecorderLavender,
    secondary = RecorderCoral,
    onSecondary = Color(0xFF3D0F0B),
    secondaryContainer = RecorderCoralDim,
    onSecondaryContainer = RecorderCoral,
    tertiary = RecorderLavender,
    onTertiary = Color(0xFF1C1A33),
    background = RecorderInk,
    onBackground = RecorderTextPrimary,
    surface = RecorderSurface,
    onSurface = RecorderTextPrimary,
    surfaceVariant = RecorderSurfaceRaised,
    onSurfaceVariant = RecorderTextSecondary,
    error = RecorderCoral,
    onError = Color(0xFF3D0F0B)
)

@Composable
fun SecureVoxTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    // Recorder's identity is fixed regardless of the device's wallpaper, so this
    // no longer defaults to Material You dynamic color — that made SecureVox only
    // resemble Recorder by coincidence, on whichever wallpaper happened to be set.
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val systemDarkTheme = isSystemInDarkTheme()

    // Determine if we should use dark theme based on ThemeMode
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> systemDarkTheme
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
