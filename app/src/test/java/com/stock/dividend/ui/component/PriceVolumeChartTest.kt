package com.stock.dividend.ui.component

import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.repository.KlineBar
import org.junit.Test

class PriceVolumeChartTest {

    private val bars = (1..30).map { day ->
        KlineBar(
            date = "2026-07-%02d".format(day),
            open = 10.0,
            close = 10.0 + day,
            high = 11.0,
            low = 9.0,
            volume = 1000.0
        )
    }

    @Test
    fun `formatAxisDateLabel returns MM-dd for first and last bars`() {
        assertThat(formatAxisDateLabel(bars, 0.0)).isEqualTo("07-01")
        assertThat(formatAxisDateLabel(bars, 29.0)).isEqualTo("07-30")
    }

    @Test
    fun `formatAxisDateLabel never returns empty string for any in-range x`() {
        (0..bars.lastIndex).forEach { x ->
            assertThat(formatAxisDateLabel(bars, x.toDouble())).isNotEmpty()
        }
    }

    @Test
    fun `formatAxisDateLabel falls back to non-empty placeholder for out-of-range x`() {
        assertThat(formatAxisDateLabel(bars, -1.0)).isNotEmpty()
        assertThat(formatAxisDateLabel(bars, 30.0)).isNotEmpty()
        assertThat(formatAxisDateLabel(bars, 1000.0)).isNotEmpty()
    }

    @Test
    fun `formatAxisDateLabel returns placeholder for empty bars`() {
        assertThat(formatAxisDateLabel(emptyList(), 0.0)).isNotEmpty()
    }
}
