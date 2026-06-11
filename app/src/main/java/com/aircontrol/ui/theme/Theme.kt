package com.aircontrol.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = ElectricBlue,
    onPrimary = NavyBackground,
    primaryContainer = ElectricBlueVariant,
    onPrimaryContainer = TextPrimary,
    secondary = SuccessGreen,
    onSecondary = NavyBackground,
    secondaryContainer = SuccessGreen.copy(alpha = 0.15f),
    onSecondaryContainer = SuccessGreen,
    tertiary = WarningOrange,
    onTertiary = NavyBackground,
    tertiaryContainer = WarningOrange.copy(alpha = 0.15f),
    onTertiaryContainer = WarningOrange,
    error = ErrorRed,
    onError = TextPrimary,
    errorContainer = ErrorRed.copy(alpha = 0.15f),
    onErrorContainer = ErrorRed,
    background = NavyBackground,
    onBackground = TextPrimary,
    surface = DarkSurface,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = TextSecondary,
    outline = OutlineDark,
    outlineVariant = OutlineVariantDark,
    inverseSurface = TextPrimary,
    inverseOnSurface = NavyBackground,
)

private val LightColorScheme = lightColorScheme(
    primary = ElectricBlueVariant,
    onPrimary = TextPrimary,
    primaryContainer = ElectricBlue.copy(alpha = 0.15f),
    onPrimaryContainer = ElectricBlueVariant,
    secondary = SuccessGreen,
    onSecondary = TextPrimary,
    secondaryContainer = SuccessGreen.copy(alpha = 0.15f),
    onSecondaryContainer = SuccessGreen,
    tertiary = WarningOrange,
    onTertiary = TextPrimary,
    tertiaryContainer = WarningOrange.copy(alpha = 0.15f),
    onTertiaryContainer = WarningOrange,
    error = ErrorRed,
    onError = TextPrimary,
    errorContainer = ErrorRed.copy(alpha = 0.15f),
    onErrorContainer = ErrorRed,
    background = NavyBackground,
    onBackground = TextPrimary,
    surface = DarkSurface,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = TextSecondary,
    outline = OutlineDark,
    outlineVariant = OutlineVariantDark,
    inverseSurface = TextPrimary,
    inverseOnSurface = NavyBackground,
)

@Composable
fun AirControlTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
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
            @Suppress("DEPRECATION")
            window.statusBarColor = NavyBackground.toArgb()
            @Suppress("DEPRECATION")
            window.navigationBarColor = NavyBackground.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = false
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AirControlTypography,
        content = content,
    )
}
