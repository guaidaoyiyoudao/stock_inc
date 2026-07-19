package com.stock.dividend.ui.screen

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PortfolioScreenFormatTest {

    @Test
    fun `portfolioFormatMoney formats with thousand separators and two decimals`() {
        assertThat(portfolioFormatMoney(91200.0)).isEqualTo("¥91,200.00")
        assertThat(portfolioFormatMoney(0.0)).isEqualTo("¥0.00")
        assertThat(portfolioFormatMoney(12345.678)).isEqualTo("¥12,345.68")
    }

    @Test
    fun `portfolioFormatSignedPnl adds plus sign for gains and minus for losses`() {
        assertThat(portfolioFormatSignedPnl(15200.0)).isEqualTo("+¥15,200.00")
        assertThat(portfolioFormatSignedPnl(-3200.5)).isEqualTo("-¥3,200.50")
        // zero is not positive, so no leading +
        assertThat(portfolioFormatSignedPnl(0.0)).isEqualTo("¥0.00")
    }

    @Test
    fun `portfolioFormatPercent formats with one decimal`() {
        assertThat(portfolioFormatPercent(30.0)).isEqualTo("30.0%")
        assertThat(portfolioFormatPercent(12.345)).isEqualTo("12.3%")
        assertThat(portfolioFormatPercent(0.0)).isEqualTo("0.0%")
    }
}
