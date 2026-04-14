package com.stock.dividend.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable

private val LightColorScheme = lightColorScheme(
    primary = Jade4,
    onPrimary = Color.White,
    primaryContainer = Jade0,
    onPrimaryContainer = Jade6,

    secondary = Slate7,
    onSecondary = Color.White,
    secondaryContainer = Slate1,
    onSecondaryContainer = Slate8,

    tertiary = Gold4,
    onTertiary = Color.White,
    tertiaryContainer = Gold1,
    onTertiaryContainer = Gold6,

    error = FinanceRed,
    onError = Color.White,
    errorContainer = FinanceRedLight,
    onErrorContainer = FinanceRed,

    background = SurfaceWarm,
    onBackground = Slate8,
    surface = Color.White,
    onSurface = Slate8,
    surfaceVariant = SurfaceCool,
    onSurfaceVariant = Slate5,
    outline = Slate3,
    outlineVariant = Slate2,
)

private val DarkColorScheme = darkColorScheme(
    primary = Jade2,
    onPrimary = Jade6,
    primaryContainer = Jade5,
    onPrimaryContainer = Jade0,

    secondary = Slate3,
    onSecondary = Slate8,
    secondaryContainer = Slate6,
    onSecondaryContainer = Slate1,

    tertiary = Gold2,
    onTertiary = Gold6,
    tertiaryContainer = Gold5,
    onTertiaryContainer = Gold1,

    error = Color(0xFFEF5350),
    onError = Color.White,
    errorContainer = Color(0xFF5C1A1A),
    onErrorContainer = Color(0xFFFFCDD2),

    background = SurfaceDark,
    onBackground = Slate1,
    surface = SurfaceDarkElevated,
    onSurface = Slate1,
    surfaceVariant = Color(0xFF1A2535),
    onSurfaceVariant = Slate4,
    outline = Slate6,
    outlineVariant = Slate7,
)

@Composable
fun StockDividendTheme(
    darkTheme: Boolean = androidx.compose.foundation.isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    // Use custom palette for brand consistency (no dynamic colors)
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = StockTypography,
        content = content
    )
}
