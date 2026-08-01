package com.stock.dividend.data.repository

import java.util.Locale
import kotlin.math.abs

/**
 * 金额/百分比统一格式化器（纯函数，无 Android 依赖，便于单测）。
 *
 * 设计目标：消灭散落在各 Screen / Card 里的 [formatAmount] / [formatMoney] /
 * [formatCurrency] / [portfolioFormatMoney] 等私有重复实现，统一以下三点：
 * 1. **千分位**：金额一律开启（`1,234.56` 而非 `1234.56`）。
 * 2. **Locale**：一律 [Locale.US]，保证逗号/小数点稳定，不受系统 locale 影响。
 * 3. **小数位**：金额固定 2 位；百分比可指定（默认 2 位）。
 *
 * 参见调研报告：旧实现中 [IncomeSummaryCard] 无千分位、[DividendSummaryCard] 有千分位
 * 的不一致，在本工具上线后统一消除。
 */
object MoneyFormatter {

    /** 默认货币符号（人民币）。 */
    const val DEFAULT_SYMBOL = "¥"

    /**
     * 纯数字格式化（不带符号），固定 2 位小数 + 千分位。
     * - `1234.5` → `"1,234.50"`
     * - `-1234.567` → `"-1,234.57"`（四舍五入）
     */
    fun amount(value: Double, locale: Locale = Locale.US): String =
        amount(value, decimals = 2, locale = locale)

    /**
     * 纯数字格式化（自定义小数位），千分位。
     * - `amount(1234.5, decimals = 4)` → `"1,234.5000"`（每股派息等高精度场景）
     */
    fun amount(value: Double, decimals: Int, locale: Locale = Locale.US): String {
        if (!value.isFinite()) return "0." + "0".repeat(decimals)
        return String.format(locale, "%,.${decimals}f", value)
    }

    /**
     * 带货币符号的金额：`"¥1,234.56"`。
     * 负数符号在货币符号之前：`"-¥1,234.56"`（与旧 [portfolioFormatMoney] 一致）。
     */
    fun withSymbol(value: Double, symbol: String = DEFAULT_SYMBOL, locale: Locale = Locale.US): String =
        withSymbol(value, decimals = 2, symbol = symbol, locale = locale)

    /**
     * 带货币符号的金额（自定义小数位）。
     * - `withSymbol(0.35, decimals = 4)` → `"¥0.3500"`（每股派息）
     */
    fun withSymbol(value: Double, decimals: Int, symbol: String = DEFAULT_SYMBOL, locale: Locale = Locale.US): String {
        if (!value.isFinite()) return "${symbol}0." + "0".repeat(decimals)
        val sign = if (value < 0) "-" else ""
        return "$sign$symbol${String.format(locale, "%,.${decimals}f", abs(value))}"
    }

    /**
     * 带正负号的金额（盈亏场景）：正数前缀 `+`，负数前缀 `-`。
     * - `1234.5` → `"+¥1,234.50"`
     * - `-1234.5` → `"-¥1,234.50"`
     * - `0.0` → `"¥0.00"`（零不加正号，避免误导）
     */
    fun withSign(value: Double, symbol: String = DEFAULT_SYMBOL, locale: Locale = Locale.US): String {
        if (!value.isFinite()) return "${symbol}0.00"
        val sign = when {
            value > 0 -> "+"
            value < 0 -> "-"
            else -> ""
        }
        return "$sign$symbol${String.format(locale, "%,.2f", abs(value))}"
    }

    /**
     * 中文紧凑单位（万/亿），用于 FIRE 进度等大额展示。
     * - `1234.0` → `"¥1,234.00"`
     * - `12345.0` → `"¥1.23万"`
     * - `123456789.0` → `"¥1.23亿"`
     *
     * 阈值：≥ 1 亿用「亿」，≥ 1 万用「万」，否则原样（2 位小数 + 千分位）。
     */
    fun compact(value: Double, symbol: String = DEFAULT_SYMBOL, locale: Locale = Locale.US): String {
        if (!value.isFinite()) return "${symbol}0.00"
        val absV = abs(value)
        val sign = if (value < 0) "-" else ""
        return when {
            absV >= 1_0000_0000 -> "$sign$symbol${String.format(locale, "%.2f", absV / 1_0000_0000)}亿"
            absV >= 1_0000 -> "$sign$symbol${String.format(locale, "%.2f", absV / 1_0000)}万"
            else -> "$sign$symbol${String.format(locale, "%,.2f", absV)}"
        }
    }
}

/**
 * 百分比格式化器（纯函数）。
 *
 * 统一旧实现中小数位不一致问题：股息率多为 `%.2f`，趋势/占比多为 `%.1f`。
 * 本工具通过 [decimals] 参数显式指定，调用方按语义选择。
 */
object PercentFormatter {

    /**
     * 百分比值 → 字符串（值本身就是百分比数字）。
     * - `3.456` (decimals=2) → `"3.46%"`
     * - `3.4` (decimals=1) → `"3.4%"`
     */
    fun percent(value: Double, decimals: Int = 2, locale: Locale = Locale.US): String {
        if (!value.isFinite()) return "0%"
        val pattern = "%.${decimals}f%%"
        return String.format(locale, pattern, value)
    }

    /**
     * 小数比例 → 百分比字符串（自动 ×100）。
     * - `0.0345` (decimals=1) → `"3.5%"`
     * - `0.15` (decimals=0) → `"15%"`
     *
     * 用于「占比 = 部分/整体」这类 0~1 区间的比率展示。
     */
    fun fromRatio(ratio: Double, decimals: Int = 1, locale: Locale = Locale.US): String {
        if (!ratio.isFinite()) return "0%"
        return percent(ratio * 100.0, decimals, locale)
    }

    /**
     * 带正负号的百分比（涨跌场景）：正数前缀 `+`。
     * - `3.4` → `"+3.40%"`
     * - `-2.1` → `"-2.10%"`
     * - `0.0` → `"0.00%"`
     */
    fun withSign(value: Double, decimals: Int = 2, locale: Locale = Locale.US): String {
        if (!value.isFinite()) return "0%"
        val sign = if (value > 0) "+" else ""
        val pattern = "%s%.${decimals}f%%"
        return String.format(locale, pattern, sign, value)
    }
}
