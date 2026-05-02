package com.stock.dividend.ui.theme

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColorScheme = lightColorScheme(
    primary = Blue5,
    onPrimary = Color.White,
    primaryContainer = GlassColors.LightContainer,
    onPrimaryContainer = Blue7,

    secondary = Slate5,
    onSecondary = Color.White,
    secondaryContainer = GlassColors.LightSecondaryContainer,
    onSecondaryContainer = Slate7,

    tertiary = Gold3,
    onTertiary = Color.White,
    tertiaryContainer = Gold1.copy(alpha = 0.7f),
    onTertiaryContainer = Gold5,

    error = FinanceRed,
    onError = Color.White,
    errorContainer = FinanceRedLight.copy(alpha = 0.7f),
    onErrorContainer = FinanceRed,

    background = GlassColors.LightGradientStart,
    onBackground = Slate7,
    surface = GlassColors.LightSurface,
    onSurface = Slate7,
    surfaceVariant = GlassColors.LightSurfaceVariant,
    onSurfaceVariant = Slate4,
    outline = Slate3.copy(alpha = 0.5f),
    outlineVariant = Slate2.copy(alpha = 0.5f),
)

private val DarkColorScheme = darkColorScheme(
    primary = Blue3,
    onPrimary = Blue7,
    primaryContainer = GlassColors.DarkContainer,
    onPrimaryContainer = Blue0,

    secondary = Slate3,
    onSecondary = Slate7,
    secondaryContainer = GlassColors.DarkSecondaryContainer,
    onSecondaryContainer = Slate1,

    tertiary = Gold2,
    onTertiary = Gold5,
    tertiaryContainer = Gold4.copy(alpha = 0.5f),
    onTertiaryContainer = Gold1,

    error = Color(0xFFEF5350),
    onError = Color.White,
    errorContainer = Color(0xFF5C1A1A).copy(alpha = 0.7f),
    onErrorContainer = Color(0xFFFFCDD2),

    background = GlassColors.DarkGradientStart,
    onBackground = Slate1,
    surface = GlassColors.DarkSurface,
    onSurface = Slate1,
    surfaceVariant = GlassColors.DarkSurfaceVariant,
    onSurfaceVariant = Slate3,
    outline = Slate5.copy(alpha = 0.5f),
    outlineVariant = Slate6.copy(alpha = 0.5f),
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

@Composable
fun GradientBackground(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val colors = if (isDark) {
        listOf(GlassColors.DarkGradientStart, GlassColors.DarkGradientEnd)
    } else {
        listOf(GlassColors.LightGradientStart, GlassColors.LightGradientEnd)
    }
    Box(
        modifier = modifier.background(
            brush = Brush.verticalGradient(colors)
        )
    ) {
        content()
    }
}
