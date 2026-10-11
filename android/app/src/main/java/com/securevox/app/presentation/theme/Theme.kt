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

private val LightColorScheme = lightColorScheme(
    primary = VerdigrisDeep,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCFE8E3),
    onPrimaryContainer = Color(0xFF0F3B35),
    secondary = SignalRedDeep,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF7D9D4),
    onSecondaryContainer = Color(0xFF5C1F1B),
    tertiary = VerdigrisDeep,
    onTertiary = Color.White,
    background = Paper,
    onBackground = Ink,
    surface = PaperRaised,
    onSurface = Ink,
    surfaceVariant = PaperSunk,
    onSurfaceVariant = InkSoft,
    outline = Color(0xFF8A9096),
    outlineVariant = Color(0xFFD3D0C8),
    error = SignalRedDeep,
    onError = Color.White
)

private val DarkColorScheme = darkColorScheme(
    primary = Verdigris,
    onPrimary = Color(0xFF0B2B27),
    primaryContainer = Color(0xFF1F4A44),
    onPrimaryContainer = Color(0xFFA8E0D8),
    secondary = SignalRed,
    onSecondary = Color(0xFF3D0F0B),
    secondaryContainer = SignalRedDim,
    onSecondaryContainer = SignalRed,
    tertiary = Verdigris,
    onTertiary = Color(0xFF0B2B27),
    background = Graphite,
    onBackground = Bone,
    surface = GraphiteSurface,
    onSurface = Bone,
    surfaceVariant = GraphiteRaised,
    onSurfaceVariant = Slate,
    outline = SlateDim,
    outlineVariant = Color(0xFF34393D),
    error = SignalRed,
    onError = Color(0xFF3D0F0B)
)

@Composable
fun SecureVoxTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    // The notebook identity is fixed regardless of the device wallpaper.
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
