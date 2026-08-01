package com.stock.dividend.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 渐变背景色（参考 NIA GradientColors）。
 *
 * 用于全局背景的微妙渐变，比纯色更高级。亮色用近白渐变，暗色用近黑渐变。
 * 通过 [LocalGradientColors] 在主题内提供，调用方用 [Modifier.background] + verticalGradient 渲染。
 */
@Immutable
data class GradientColors(
    /** 渐变顶部色。 */
    val top: Color = Color.Unspecified,
    /** 渐变底部色。 */
    val bottom: Color = Color.Unspecified,
    /** 容器（卡片/表面）的渐变主色。 */
    val container: Color = Color.Unspecified,
)

val LocalGradientColors = staticCompositionLocalOf { GradientColors() }

/**
 * 背景主题（参考 NIA BackgroundTheme）。
 *
 * 携带背景基色 + tonalElevation，用于统一「带微妙层次的背景」。
 * 比 M3 默认纯色背景更有深度感。
 */
@Immutable
data class BackgroundTheme(
    /** 背景色（通常与 colorScheme.background 一致或极接近）。 */
    val color: Color = Color.Unspecified,
    /** 背景的色调抬升（暗色模式下用色彩混合模拟层次）。 */
    val tonalElevation: Dp = 1.dp,
)

val LocalBackgroundTheme = staticCompositionLocalOf { BackgroundTheme() }

/**
 * 扩展颜色：财务语义色（涨/跌/中性）。
 *
 * **解决的核心问题**：旧代码里 [FinanceGreen] / [FinanceRed] 散落 6 处直接 import，
 * 无法跟随深浅色切换。本类把财务色纳入主题，亮/暗各一套。
 *
 * 新组件（如 [com.stock.dividend.ui.component.AmountText]）应通过
 * [LocalExtendedColors].current.positive / negative 读取，而非裸 import 颜色常量。
 *
 * 旧的 `FinanceGreen` / `FinanceRed` 常量保留兼容（下期逐屏迁移时收敛）。
 */
@Immutable
data class ExtendedColors(
    /** 涨/盈利色（亮色模式）。 */
    val positive: Color = FinanceGreen,
    /** 涨/盈利色容器背景（浅色）。 */
    val positiveContainer: Color = FinanceGreenLight,
    /** 跌/亏损色（亮色模式）。 */
    val negative: Color = FinanceRed,
    /** 跌/亏损色容器背景（浅色）。 */
    val negativeContainer: Color = FinanceRedLight,
    /** 中性色（无变化/数据缺失）。 */
    val neutral: Color = Color.Unspecified,
)

val LocalExtendedColors = staticCompositionLocalOf { ExtendedColors() }
