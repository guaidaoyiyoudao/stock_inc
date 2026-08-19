package com.stock.dividend.ui.component

import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.repository.DividendYieldLine
import org.junit.Test

/**
 * KlineYieldChart internal 纯函数单测（标签文本构造/日期裁剪，无 Android 依赖）。
 */
class KlineYieldChartTest {

    @Test
    fun `yield line label joins yield percent and price with formatters`() {
        // 股息率 1 位小数 + 价格 2 位千分位：6.5% → ¥9.23
        val label = yieldLineLabelText(DividendYieldLine(yieldPercent = 6.5, price = 9.23, belowCurrent = true))
        assertThat(label).isEqualTo("6.5% ¥9.23")
    }

    @Test
    fun `yield line label formats large price with thousands separator`() {
        // 高价股：1,350.60 需千分位（MoneyFormatter 口径）
        val label = yieldLineLabelText(DividendYieldLine(yieldPercent = 2.0, price = 1350.6, belowCurrent = false))
        assertThat(label).isEqualTo("2.0% ¥1,350.60")
    }

    @Test
    fun `kline date label takes MM-dd from YYYY-MM-DD`() {
        assertThat(klineDateLabel("2026-08-15")).isEqualTo("08-15")
        assertThat(klineDateLabel("2026-07-01")).isEqualTo("07-01")
    }

    @Test
    fun `kline date label never returns empty for malformed dates`() {
        assertThat(klineDateLabel("")).isNotEmpty()
        assertThat(klineDateLabel("2026")).isNotEmpty()
        assertThat(klineDateLabel("2026-")).isNotEmpty()
    }
}
