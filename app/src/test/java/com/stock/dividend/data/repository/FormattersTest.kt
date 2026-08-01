package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Locale

class FormattersTest {

    // ── MoneyFormatter.amount ──────────────────────────────────────────

    @Test
    fun `amount formats with thousands separator and 2 decimals`() {
        assertThat(MoneyFormatter.amount(1234.5)).isEqualTo("1,234.50")
        assertThat(MoneyFormatter.amount(1234567.891)).isEqualTo("1,234,567.89")
    }

    @Test
    fun `amount rounds half up`() {
        // 1234.567 → 四舍五入到 1234.57
        assertThat(MoneyFormatter.amount(1234.567)).isEqualTo("1,234.57")
        assertThat(MoneyFormatter.amount(1234.5)).isEqualTo("1,234.50")
    }

    @Test
    fun `amount handles negative`() {
        assertThat(MoneyFormatter.amount(-1234.5)).isEqualTo("-1,234.50")
    }

    @Test
    fun `amount handles zero`() {
        assertThat(MoneyFormatter.amount(0.0)).isEqualTo("0.00")
    }

    @Test
    fun `amount with custom decimals pads zeros`() {
        // 每股派息场景：4 位小数
        assertThat(MoneyFormatter.amount(0.35, decimals = 4)).isEqualTo("0.3500")
        assertThat(MoneyFormatter.amount(1234.5, decimals = 4)).isEqualTo("1,234.5000")
        assertThat(MoneyFormatter.amount(0.0, decimals = 4)).isEqualTo("0.0000")
    }

    @Test
    fun `amount handles non-finite safely`() {
        assertThat(MoneyFormatter.amount(Double.NaN)).isEqualTo("0.00")
        assertThat(MoneyFormatter.amount(Double.POSITIVE_INFINITY)).isEqualTo("0.00")
    }

    // ── MoneyFormatter.withSymbol ──────────────────────────────────────

    @Test
    fun `withSymbol prepends currency symbol`() {
        assertThat(MoneyFormatter.withSymbol(1234.5)).isEqualTo("¥1,234.50")
        assertThat(MoneyFormatter.withSymbol(1234.5, symbol = "$")).isEqualTo("$1,234.50")
    }

    @Test
    fun `withSymbol puts minus sign before symbol for negative`() {
        // 旧 portfolioFormatMoney 的约定："-¥1,234.50"
        assertThat(MoneyFormatter.withSymbol(-1234.5)).isEqualTo("-¥1,234.50")
    }

    @Test
    fun `withSymbol with custom decimals for per-share dividend`() {
        // 每股派息场景：4 位小数
        assertThat(MoneyFormatter.withSymbol(0.35, decimals = 4)).isEqualTo("¥0.3500")
        assertThat(MoneyFormatter.withSymbol(-0.35, decimals = 4)).isEqualTo("-¥0.3500")
    }

    // ── MoneyFormatter.withSign ────────────────────────────────────────

    @Test
    fun `withSign adds plus for positive`() {
        assertThat(MoneyFormatter.withSign(1234.5)).isEqualTo("+¥1,234.50")
    }

    @Test
    fun `withSign adds minus for negative`() {
        assertThat(MoneyFormatter.withSign(-1234.5)).isEqualTo("-¥1,234.50")
    }

    @Test
    fun `withSign omits plus for zero`() {
        // 零不加正号，避免误导（盈亏场景 0 不是盈利）
        assertThat(MoneyFormatter.withSign(0.0)).isEqualTo("¥0.00")
    }

    // ── MoneyFormatter.compact ─────────────────────────────────────────

    @Test
    fun `compact uses wan unit for values at least 10000`() {
        assertThat(MoneyFormatter.compact(12345.0)).isEqualTo("¥1.23万")
    }

    @Test
    fun `compact uses yi unit for hundreds of millions`() {
        assertThat(MoneyFormatter.compact(123_456_789.0)).isEqualTo("¥1.23亿")
    }

    @Test
    fun `compact keeps raw amount when below 10000`() {
        assertThat(MoneyFormatter.compact(9999.0)).isEqualTo("¥9,999.00")
        assertThat(MoneyFormatter.compact(1234.5)).isEqualTo("¥1,234.50")
    }

    @Test
    fun `compact boundary exactly 10000`() {
        assertThat(MoneyFormatter.compact(10000.0)).isEqualTo("¥1.00万")
    }

    @Test
    fun `compact boundary exactly 100 million`() {
        assertThat(MoneyFormatter.compact(100_000_000.0)).isEqualTo("¥1.00亿")
    }

    @Test
    fun `compact handles negative wan`() {
        assertThat(MoneyFormatter.compact(-12345.0)).isEqualTo("-¥1.23万")
    }

    // ── PercentFormatter.percent ───────────────────────────────────────

    @Test
    fun `percent default 2 decimals`() {
        assertThat(PercentFormatter.percent(3.456)).isEqualTo("3.46%")
        assertThat(PercentFormatter.percent(3.4)).isEqualTo("3.40%")
    }

    @Test
    fun `percent with 1 decimal`() {
        assertThat(PercentFormatter.percent(3.456, decimals = 1)).isEqualTo("3.5%")
    }

    @Test
    fun `percent with 0 decimals`() {
        assertThat(PercentFormatter.percent(3.6, decimals = 0)).isEqualTo("4%")
    }

    @Test
    fun `percent handles negative`() {
        assertThat(PercentFormatter.percent(-2.15)).isEqualTo("-2.15%")
    }

    // ── PercentFormatter.fromRatio ─────────────────────────────────────

    @Test
    fun `fromRatio multiplies by 100`() {
        assertThat(PercentFormatter.fromRatio(0.0345, decimals = 1)).isEqualTo("3.5%")
        assertThat(PercentFormatter.fromRatio(0.15, decimals = 0)).isEqualTo("15%")
    }

    @Test
    fun `fromRatio handles zero`() {
        // decimals 默认 1，所以是 "0.0%"
        assertThat(PercentFormatter.fromRatio(0.0)).isEqualTo("0.0%")
        assertThat(PercentFormatter.fromRatio(0.0, decimals = 0)).isEqualTo("0%")
    }

    // ── PercentFormatter.withSign ──────────────────────────────────────

    @Test
    fun `withSign adds plus for positive percent`() {
        assertThat(PercentFormatter.withSign(3.4)).isEqualTo("+3.40%")
    }

    @Test
    fun `withSign adds minus for negative percent`() {
        assertThat(PercentFormatter.withSign(-2.1)).isEqualTo("-2.10%")
    }

    @Test
    fun `withSign omits plus for zero percent`() {
        assertThat(PercentFormatter.withSign(0.0)).isEqualTo("0.00%")
    }

    // ── Locale 稳定性验证（关键：不受系统 locale 影响）──────────────

    @Test
    fun `amount uses dot as decimal separator even under non-US default locale`() {
        // 即使修改默认 Locale，MoneyFormatter 内部强制 Locale.US
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY) // 德国用逗号做小数点
            assertThat(MoneyFormatter.amount(1234.5)).isEqualTo("1,234.50")
            assertThat(PercentFormatter.percent(3.4)).isEqualTo("3.40%")
        } finally {
            Locale.setDefault(original)
        }
    }
}
