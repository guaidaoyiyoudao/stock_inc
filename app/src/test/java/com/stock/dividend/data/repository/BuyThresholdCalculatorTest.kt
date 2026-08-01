package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BuyThresholdCalculatorTest {

    @Test
    fun `target is bond yield times multiplier`() {
        val s = computeBuyThreshold(
            bondYield10Y = 2.6, multiplier = 2.5,
            latestYearlyCashPerShare = null, currentPrice = null
        )
        assertThat(s.targetYieldPercent).isWithin(1e-9).of(6.5)
        assertThat(s.bondYield10Y).isWithin(1e-9).of(2.6)
        assertThat(s.multiplier).isWithin(1e-9).of(2.5)
        assertThat(s.currentYieldPercent).isNull()
        assertThat(s.reached).isNull()
    }

    @Test
    fun `current yield reaches target when dividend high enough`() {
        // 中国移动近似：年每股分红 4.0，现价 60 → 6.67% ≥ 6.5%
        val s = computeBuyThreshold(
            bondYield10Y = 2.6, multiplier = 2.5,
            latestYearlyCashPerShare = 4.0, currentPrice = 60.0
        )
        assertThat(s.currentYieldPercent).isWithin(1e-9).of(6.666666666)
        assertThat(s.reached).isTrue()
    }

    @Test
    fun `current yield below target`() {
        // 年分红 3.0，现价 60 → 5.0% < 6.5%
        val s = computeBuyThreshold(
            bondYield10Y = 2.6, multiplier = 2.5,
            latestYearlyCashPerShare = 3.0, currentPrice = 60.0
        )
        assertThat(s.reached).isFalse()
    }

    @Test
    fun `missing current price yields null reached`() {
        val s = computeBuyThreshold(
            bondYield10Y = 2.6, multiplier = 2.5,
            latestYearlyCashPerShare = 4.0, currentPrice = null
        )
        assertThat(s.currentYieldPercent).isNull()
        assertThat(s.reached).isNull()
        assertThat(s.targetYieldPercent).isWithin(1e-9).of(6.5)
    }

    @Test
    fun `missing dividend yields null reached`() {
        val s = computeBuyThreshold(
            bondYield10Y = 2.6, multiplier = 2.5,
            latestYearlyCashPerShare = null, currentPrice = 60.0
        )
        assertThat(s.reached).isNull()
    }

    @Test
    fun `zero or negative price is treated as missing`() {
        val s = computeBuyThreshold(
            bondYield10Y = 2.6, multiplier = 2.5,
            latestYearlyCashPerShare = 4.0, currentPrice = 0.0
        )
        assertThat(s.currentYieldPercent).isNull()
        assertThat(s.reached).isNull()
    }

    @Test
    fun `non-positive bond yield is sanitized to zero target`() {
        val s = computeBuyThreshold(
            bondYield10Y = 0.0, multiplier = 2.5,
            latestYearlyCashPerShare = 4.0, currentPrice = 60.0
        )
        assertThat(s.targetYieldPercent).isWithin(1e-9).of(0.0)
        assertThat(s.reached).isTrue() // 6.67% >= 0
    }

    @Test
    fun `non-positive multiplier is sanitized to zero`() {
        val s = computeBuyThreshold(
            bondYield10Y = 2.6, multiplier = -1.0,
            latestYearlyCashPerShare = 4.0, currentPrice = 60.0
        )
        assertThat(s.targetYieldPercent).isWithin(1e-9).of(0.0)
        assertThat(s.multiplier).isWithin(1e-9).of(0.0)
    }

    @Test
    fun `boundary equality counts as reached`() {
        // target 6.5，现价股息率恰好 6.5
        // 6.5 = cash / 100 * 100 → cash=6.5, price=100
        val s = computeBuyThreshold(
            bondYield10Y = 2.6, multiplier = 2.5,
            latestYearlyCashPerShare = 6.5, currentPrice = 100.0
        )
        assertThat(s.currentYieldPercent).isWithin(1e-9).of(6.5)
        assertThat(s.reached).isTrue()
    }
}
