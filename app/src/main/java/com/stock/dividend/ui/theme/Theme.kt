package com.stock.dividend.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
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

@Composable
fun StockDividendTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = true
                isAppearanceLightNavigationBars = true
            }
        }
    }

    MaterialTheme(
        colorScheme = LightColorScheme,
        typography = StockTypography,
        shapes = StockShapes,
        content = content
    )
}
