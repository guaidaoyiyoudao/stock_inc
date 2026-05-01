package com.stock.dividend.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColorScheme = lightColorScheme(
    primary = Blue5,
    onPrimary = Color.White,
    primaryContainer = Blue0,
    onPrimaryContainer = Blue7,

    secondary = Slate5,
    onSecondary = Color.White,
    secondaryContainer = Slate1,
    onSecondaryContainer = Slate7,

    tertiary = Gold3,
    onTertiary = Color.White,
    tertiaryContainer = Gold1,
    onTertiaryContainer = Gold5,

    error = FinanceRed,
    onError = Color.White,
    errorContainer = FinanceRedLight,
    onErrorContainer = FinanceRed,

    background = SurfaceBackground,
    onBackground = Slate7,
    surface = Color.White,
    onSurface = Slate7,
    surfaceVariant = Slate1,
    onSurfaceVariant = Slate4,
    outline = Slate3,
    outlineVariant = Slate2,
)

private val DarkColorScheme = darkColorScheme(
    primary = Blue3,
    onPrimary = Blue7,
    primaryContainer = Blue6,
    onPrimaryContainer = Blue0,

    secondary = Slate3,
    onSecondary = Slate7,
    secondaryContainer = Slate5,
    onSecondaryContainer = Slate1,

    tertiary = Gold2,
    onTertiary = Gold5,
    tertiaryContainer = Gold4,
    onTertiaryContainer = Gold1,

    error = Color(0xFFEF5350),
    onError = Color.White,
    errorContainer = Color(0xFF5C1A1A),
    onErrorContainer = Color(0xFFFFCDD2),

    background = Color(0xFF0F172A),
    onBackground = Slate1,
    surface = Color(0xFF1E293B),
    onSurface = Slate1,
    surfaceVariant = Color(0xFF1E293B),
    onSurfaceVariant = Slate3,
    outline = Slate5,
    outlineVariant = Slate6,
)

@Composable
fun StockDividendTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = StockTypography,
        shapes = StockShapes,
        content = content
    )
}
