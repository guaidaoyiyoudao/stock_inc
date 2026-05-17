package com.stock.dividend.ui.screen

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HomeScreenFormatTest {

    @Test
    fun `formatHoldingsTotalMarketValue displays zero when value is null`() {
        assertThat(formatHoldingsTotalMarketValue(null)).isEqualTo("总市值 ¥0.00")
    }

    @Test
    fun `formatHoldingsTotalMarketValue displays formatted amount when value is present`() {
        assertThat(formatHoldingsTotalMarketValue(12345.678)).isEqualTo("总市值 ¥12,345.68")
    }

    @Test
    fun `formatHoldingsTotalMarketValue displays zero when value is zero`() {
        assertThat(formatHoldingsTotalMarketValue(0.0)).isEqualTo("总市值 ¥0.00")
    }
}
