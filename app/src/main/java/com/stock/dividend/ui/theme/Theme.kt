package com.stock.dividend.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

/**
 * 亮色 ColorScheme（保留原有 Clear Sky Finance 映射，仅 background 换成温润近白）。
 */
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

/**
 * 暗色 ColorScheme（新增）。
 *
 * 设计要点：
 * - 背景用带蓝调的近黑 [SurfaceBackgroundDark]，避免 OLED 纯黑疲劳。
 * - 主色用更亮的 [BlueDark5]（保证暗色背景上对比度 ≥ 4.5:1）。
 * - 表面色 [SurfaceElevatedDark] 比背景略亮，形成层次。
 */
private val DarkColorScheme = darkColorScheme(
    primary = BlueDark5,
    onPrimary = BlueDark7,
    primaryContainer = BlueDark1,
    onPrimaryContainer = BlueDark3,

    secondary = SlateDark5,
    onSecondary = SlateDark1,
    secondaryContainer = SlateDark2,
    onSecondaryContainer = SlateDark5,

    tertiary = GoldDark3,
    onTertiary = GoldDark1,
    tertiaryContainer = GoldDark1,
    onTertiaryContainer = GoldDark3,

    error = FinanceRedDark,
    onError = Color.White,
    errorContainer = FinanceRedContainerDark,
    onErrorContainer = FinanceRedDark,

    background = SurfaceBackgroundDark,
    onBackground = SlateDark5,
    surface = SurfaceElevatedDark,
    onSurface = SlateDark5,
    surfaceVariant = SlateDark1,
    onSurfaceVariant = SlateDark4,
    outline = SlateDark4,
    outlineVariant = SlateDark2,
)

/**
 * App 主题入口。
 *
 * - 跟随系统深浅色（[darkTheme] 默认读 [isSystemInDarkTheme]）。
 * - 通过 CompositionLocal 暴露 3 个扩展主题：渐变色 / 背景 / 财务语义色。
 * - 动态调整状态栏/导航栏图标外观（深色背景用浅色图标，反之亦然）。
 *
 * @param darkTheme 是否深色模式，默认跟随系统。
 * @param content 后续 Composable 内容。
 */
@Composable
fun StockDividendTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    // 扩展主题：渐变色（暗色用近黑渐变，亮色用近白渐变）
    val gradientColors = if (darkTheme) {
        GradientColors(
            top = SurfaceBackgroundDark,
            bottom = Color(0xFF0A0F14),  // 比背景更深
            container = SurfaceElevatedDark,
        )
    } else {
        GradientColors(
            top = SurfaceBackground,
            bottom = Color(0xFFF1F5F9),  // Slate1，比背景略沉
            container = Color.White,
        )
    }

    // 扩展主题：背景（含 tonalElevation）
    val backgroundTheme = BackgroundTheme(
        color = colorScheme.background,
        tonalElevation = 2.dp,
    )

    // 扩展主题：财务语义色（亮/暗各一套）
    val extendedColors = if (darkTheme) {
        ExtendedColors(
            positive = FinanceGreenDark,
            positiveContainer = FinanceGreenContainerDark,
            negative = FinanceRedDark,
            negativeContainer = FinanceRedContainerDark,
            neutral = SlateDark4,
        )
    } else {
        ExtendedColors(
            positive = FinanceGreen,
            positiveContainer = FinanceGreenLight,
            negative = FinanceRed,
            negativeContainer = FinanceRedLight,
            neutral = Slate4,
        )
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                // 深色背景 → 浅色图标；浅色背景 → 深色图标
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(
        LocalGradientColors provides gradientColors,
        LocalBackgroundTheme provides backgroundTheme,
        LocalExtendedColors provides extendedColors,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = StockTypography,
            shapes = StockShapes,
            content = content
        )
    }
}
