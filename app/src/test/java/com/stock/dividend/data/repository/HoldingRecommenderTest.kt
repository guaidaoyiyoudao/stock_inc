package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HoldingRecommenderTest {

    // 中轨 10，半带宽 1 → lower=9, upper=11

    @Test
    fun `price below lower returns BUY with high yield`() {
        val band = BollBand(middle = 10.0, upper = 11.0, lower = 9.0)
        val r = HoldingRecommender.recommend(
            price = 8.8, band = band, latestYearlyDividend = 0.50 // ~5.7%
        )
        assertThat(r.action).isEqualTo(HoldingAction.BUY)
        assertThat(r.bollTone).isEqualTo(BollTone.Buy)
        assertThat(r.reasons).isNotEmpty()
    }

    @Test
    fun `price below lower but low yield downgrades to HOLD`() {
        val band = BollBand(middle = 10.0, upper = 11.0, lower = 9.0)
        val r = HoldingRecommender.recommend(
            price = 8.8, band = band, latestYearlyDividend = 0.10 // ~1.1% < 2
        )
        assertThat(r.action).isEqualTo(HoldingAction.HOLD)
        assertThat(r.reasons.any { it.contains("股息率偏低") }).isTrue()
    }

    @Test
    fun `price at upper returns SELL regardless of high yield`() {
        val band = BollBand(middle = 10.0, upper = 11.0, lower = 9.0)
        val r = HoldingRecommender.recommend(
            price = 11.5, band = band, latestYearlyDividend = 1.00 // 高股息率
        )
        assertThat(r.action).isEqualTo(HoldingAction.SELL)
        assertThat(r.bollTone).isEqualTo(BollTone.Sell)
    }

    @Test
    fun `price near middle with low yield returns HOLD`() {
        val band = BollBand(middle = 10.0, upper = 11.0, lower = 9.0)
        val r = HoldingRecommender.recommend(
            price = 10.0, band = band, latestYearlyDividend = 0.10 // ~1%
        )
        assertThat(r.action).isEqualTo(HoldingAction.HOLD)
        assertThat(r.bollTone).isEqualTo(BollTone.Current)
    }

    @Test
    fun `price near middle with high yield upgrades to BUY`() {
        val band = BollBand(middle = 10.0, upper = 11.0, lower = 9.0)
        val r = HoldingRecommender.recommend(
            price = 10.0, band = band, latestYearlyDividend = 0.60 // 6% >= 5
        )
        assertThat(r.action).isEqualTo(HoldingAction.BUY)
        assertThat(r.reasons.any { it.contains("股息率较高") }).isTrue()
    }

    @Test
    fun `null band returns INSUFFICIENT_DATA with boll reason`() {
        val r = HoldingRecommender.recommend(price = 10.0, band = null, latestYearlyDividend = 0.5)
        assertThat(r.action).isEqualTo(HoldingAction.INSUFFICIENT_DATA)
        assertThat(r.reasons.any { it.contains("boll") }).isTrue()
        assertThat(r.dividendYield).isNull()
    }

    @Test
    fun `non-positive price returns INSUFFICIENT_DATA`() {
        val band = BollBand(middle = 10.0, upper = 11.0, lower = 9.0)
        val r = HoldingRecommender.recommend(price = 0.0, band = band, latestYearlyDividend = 0.5)
        assertThat(r.action).isEqualTo(HoldingAction.INSUFFICIENT_DATA)
    }

    @Test
    fun `NaN price returns INSUFFICIENT_DATA`() {
        val band = BollBand(middle = 10.0, upper = 11.0, lower = 9.0)
        val r = HoldingRecommender.recommend(
            price = Double.NaN, band = band, latestYearlyDividend = 0.5
        )
        assertThat(r.action).isEqualTo(HoldingAction.INSUFFICIENT_DATA)
    }

    @Test
    fun `null dividend yield does not apply thresholds and follows pure boll tone`() {
        val band = BollBand(middle = 10.0, upper = 11.0, lower = 9.0)
        // 在下轨：无股息率数据 → 不降级，保持 BUY
        val r = HoldingRecommender.recommend(price = 8.8, band = band, latestYearlyDividend = null)
        assertThat(r.action).isEqualTo(HoldingAction.BUY)
        assertThat(r.dividendYield).isNull()
    }

    @Test
    fun `custom thresholds take effect`() {
        val band = BollBand(middle = 10.0, upper = 11.0, lower = 9.0)
        // yield ~2.5%：默认门槛(min=2)不降级，但 min=3 时降级
        val rDefault = HoldingRecommender.recommend(
            price = 8.8, band = band, latestYearlyDividend = 0.22, // 2.5%
            thresholds = DividendThresholds()
        )
        val rStrict = HoldingRecommender.recommend(
            price = 8.8, band = band, latestYearlyDividend = 0.22,
            thresholds = DividendThresholds(minYieldPercent = 3.0, boostYieldPercent = 6.0)
        )
        assertThat(rDefault.action).isEqualTo(HoldingAction.BUY)
        assertThat(rStrict.action).isEqualTo(HoldingAction.HOLD)
    }

    @Test
    fun `priceVsLower is 0 at lower and 1 at upper`() {
        val band = BollBand(middle = 10.0, upper = 11.0, lower = 9.0)
        val rLower = HoldingRecommender.recommend(price = 9.0, band = band, latestYearlyDividend = null)
        val rUpper = HoldingRecommender.recommend(price = 11.0, band = band, latestYearlyDividend = null)
        assertThat(rLower.priceVsLower).isWithin(1e-9).of(0.0)
        assertThat(rUpper.priceVsLower).isWithin(1e-9).of(1.0)
    }

    // ── bollTone 直接测试（迁移自 BollPriceScale）──────────────────────

    @Test
    fun `bollTone returns Buy at or below lower`() {
        assertThat(HoldingRecommender.bollTone(9.0, 11.0, 10.0, 9.0)).isEqualTo(BollTone.Buy)
        assertThat(HoldingRecommender.bollTone(8.5, 11.0, 10.0, 9.0)).isEqualTo(BollTone.Buy)
    }

    @Test
    fun `bollTone returns Sell at or above upper`() {
        assertThat(HoldingRecommender.bollTone(11.0, 11.0, 10.0, 9.0)).isEqualTo(BollTone.Sell)
        assertThat(HoldingRecommender.bollTone(12.0, 11.0, 10.0, 9.0)).isEqualTo(BollTone.Sell)
    }

    @Test
    fun `bollTone returns Current within 30 percent of middle`() {
        // middle=10, halfSpan=1, 30% 阈值内 = 9.7~10.3
        assertThat(HoldingRecommender.bollTone(10.1, 11.0, 10.0, 9.0)).isEqualTo(BollTone.Current)
    }

    @Test
    fun `bollTone returns Buy when below middle beyond threshold`() {
        // price=9.5: dev=0.5 > 0.30, 偏低 → Buy
        assertThat(HoldingRecommender.bollTone(9.5, 11.0, 10.0, 9.0)).isEqualTo(BollTone.Buy)
    }

    @Test
    fun `bollTone returns Sell when above middle beyond threshold`() {
        assertThat(HoldingRecommender.bollTone(10.5, 11.0, 10.0, 9.0)).isEqualTo(BollTone.Sell)
    }
}
