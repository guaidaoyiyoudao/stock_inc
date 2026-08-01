package com.stock.dividend.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.unit.sp
import com.stock.dividend.R

/**
 * Inter 可变字体（Variable Font）FontFamily。
 *
 * - 单个 `res/font/inter.ttf` 文件承载全部字重（100-900），通过 [FontVariation] 指定。
 * - 子集化后仅含 latin + 货币符号 + tabular numbers（tnum）特性，体积 ~210KB。
 * - 中文不在子集内，会 fallback 到系统字体（这正是想要的：数字/英文用 Inter，中文用系统）。
 *
 * 参考：Now in Android 的 Type.kt 同样用 FontFamily 绑定品牌字体。
 */
@OptIn(ExperimentalTextApi::class)
private val InterFontFamily = FontFamily(
    // Regular (400)
    Font(
        resId = R.font.inter,
        weight = FontWeight.Normal,
        variationSettings = FontVariation.Settings(FontVariation.weight(400)),
    ),
    // Medium (500)
    Font(
        resId = R.font.inter,
        weight = FontWeight.Medium,
        variationSettings = FontVariation.Settings(FontVariation.weight(500)),
    ),
    // SemiBold (600)
    Font(
        resId = R.font.inter,
        weight = FontWeight.SemiBold,
        variationSettings = FontVariation.Settings(FontVariation.weight(600)),
    ),
    // Bold (700)
    Font(
        resId = R.font.inter,
        weight = FontWeight.Bold,
        variationSettings = FontVariation.Settings(FontVariation.weight(700)),
    ),
)

/**
 * 统一的平台样式：去除 Android 默认的顶/底字体内边距（includeFontPadding）。
 *
 * 旧实现未设置此项，导致中英混排时基线偏移、行高视觉重心不稳。
 * NIA 与 Compose 官方文档均推荐显式关闭 includeFontPadding。
 */
private val noFontPadding = PlatformTextStyle(includeFontPadding = false)

/**
 * 统一的行高样式：首行居中对齐，不裁剪。
 *
 * 参考 NIA 的 LineHeightStyle(Alignment=Center, Trim=NoTrim)，
 * 让多行文本视觉重心稳定，避免标题/正文与图标对齐时上下错位。
 */
private val lineHeightStyle = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

/**
 * 辅助：构造绑定 Inter 字体 + 平台样式 + 行高样式的 [TextStyle]。
 * 保持原有字号/字重/行高/字间距数值不变，仅升级字体与排版细节。
 */
private fun interStyle(
    fontWeight: FontWeight,
    fontSize: androidx.compose.ui.unit.TextUnit,
    lineHeight: androidx.compose.ui.unit.TextUnit,
    letterSpacing: androidx.compose.ui.unit.TextUnit = 0.sp,
): TextStyle = TextStyle(
    fontFamily = InterFontFamily,
    fontWeight = fontWeight,
    fontSize = fontSize,
    lineHeight = lineHeight,
    letterSpacing = letterSpacing,
    platformStyle = noFontPadding,
    lineHeightStyle = lineHeightStyle,
)

val StockTypography = Typography(
    headlineLarge = interStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 30.sp,
        lineHeight = 38.sp,
        letterSpacing = (-0.5).sp,
    ),
    headlineMedium = interStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.3).sp,
    ),
    headlineSmall = interStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    ),

    titleLarge = interStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
    ),
    titleMedium = interStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.15.sp,
    ),
    titleSmall = interStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),

    bodyLarge = interStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp,
    ),
    bodyMedium = interStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp,
    ),
    bodySmall = interStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp,
    ),

    labelLarge = interStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    labelMedium = interStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
    ),
    labelSmall = interStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
    ),
)

/**
 * 等宽数字特性（tabular figures / tnum）。
 *
 * 让数字字形宽度一致（如 `1` 和 `8` 等宽），保证金额/百分比列的小数点垂直对齐。
 * 用法：在已有 [TextStyle] 上 `.merge(tabularNumberStyle)`，或直接传给 [androidx.compose.material3.Text] 的 style。
 *
 * 注意：此特性依赖 Inter 字体的 GPOS/GSUB 表中的 `tnum` feature（子集化时已保留）。
 */
val tabularNumberStyle = TextStyle(fontFeatureSettings = "tnum")
