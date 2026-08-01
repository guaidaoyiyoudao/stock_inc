package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HoldingRecommenderTest {

    // 周线 middle=10, halfSpan=1 → lower=9, upper=11
    private val weeklyLower = BollBand(middle = 10.0, upper = 11.0, lower = 9.0)
    // 日线 lower=9（与周下轨对齐），便于构造共振
    private val dailyLower = BollBand(middle = 10.0, upper = 11.0, lower = 9.0)
    // 月线 middle=12，price ≤ 12 即「月中轨及以下」
    private val monthlyBand = BollBand(middle = 12.0, upper = 14.0, lower = 10.0)

    // ── 买入：三周期共振 ──────────────────────────────────────────────

    @Test
    fun `resonant daily lower weekly lower monthly at or below middle returns BUY`() {
        // price 8.8: ≤ daily.lower 9 ✓, ≤ weekly.lower 9 ✓, ≤ monthly.middle 12 ✓, yield ~5.7% ≥ 2
        val r = HoldingRecommender.recommend(
            price = 8.8, band = weeklyLower, latestYearlyDividend = 0.50,
            dailyBand = dailyLower, monthlyBand = monthlyBand
        )
        assertThat(r.action).isEqualTo(HoldingAction.BUY)
        assertThat(r.reasons.any { it.contains("三周期共振") }).isTrue()
    }

    @Test
    fun `resonant exactly at monthly middle returns BUY`() {
        // 「及以下」含等号：price = monthly.middle = 12，但需同时 ≤ daily/weekly lower=9 → 取 9.0
        val r = HoldingRecommender.recommend(
            price = 9.0, band = weeklyLower, latestYearlyDividend = 0.50,
            dailyBand = dailyLower, monthlyBand = monthlyBand
        )
        assertThat(r.action).isEqualTo(HoldingAction.BUY)
    }

    @Test
    fun `resonant but low yield downgrades to HOLD`() {
        // yield ~1.1% < 2 → 不给买
        val r = HoldingRecommender.recommend(
            price = 8.8, band = weeklyLower, latestYearlyDividend = 0.10,
            dailyBand = dailyLower, monthlyBand = monthlyBand
        )
        assertThat(r.action).isEqualTo(HoldingAction.HOLD)
        assertThat(r.reasons.any { it.contains("股息率偏低") }).isTrue()
    }

    @Test
    fun `resonant with null dividend still returns BUY`() {
        // 无股息数据时不应用门槛，共振即买（与历史「无 yield 不降级」一致）
        val r = HoldingRecommender.recommend(
            price = 8.8, band = weeklyLower, latestYearlyDividend = null,
            dailyBand = dailyLower, monthlyBand = monthlyBand
        )
        assertThat(r.action).isEqualTo(HoldingAction.BUY)
        assertThat(r.dividendYield).isNull()
    }

    @Test
    fun `weekly lower but daily not at lower returns HOLD`() {
        // price 8.8 ≤ weekly.lower 9，但日线 lower=7 → 8.8 > 7 不在日下轨
        val dailyHigher = BollBand(middle = 9.0, upper = 11.0, lower = 7.0)
        val r = HoldingRecommender.recommend(
            price = 8.8, band = weeklyLower, latestYearlyDividend = 0.50,
            dailyBand = dailyHigher, monthlyBand = monthlyBand
        )
        assertThat(r.action).isEqualTo(HoldingAction.HOLD)
        assertThat(r.reasons.any { it.contains("单一周期偏低") }).isTrue()
    }

    @Test
    fun `weekly lower but monthly above middle returns HOLD`() {
        // price 8.8 ≤ 日/周下轨，但月线 middle=8 → 8.8 > 8 不满足「月中轨及以下」
        val monthlyLow = BollBand(middle = 8.0, upper = 9.0, lower = 7.0)
        val r = HoldingRecommender.recommend(
            price = 8.8, band = weeklyLower, latestYearlyDividend = 0.50,
            dailyBand = dailyLower, monthlyBand = monthlyLow
        )
        assertThat(r.action).isEqualTo(HoldingAction.HOLD)
        assertThat(r.reasons.any { it.contains("单一周期偏低") }).isTrue()
    }

    @Test
    fun `weekly lower but daily monthly missing returns HOLD with hint`() {
        val r = HoldingRecommender.recommend(
            price = 8.8, band = weeklyLower, latestYearlyDividend = 0.50,
            dailyBand = null, monthlyBand = null
        )
        assertThat(r.action).isEqualTo(HoldingAction.HOLD)
        assertThat(r.reasons.any { it.contains("数据不足") }).isTrue()
    }

    @Test
    fun `middle area no longer upgrades to BUY via high yield`() {
        // 旧逻辑：中轨+高股息率→买。新逻辑：买入只走三周期共振，故中轨保持持有。
        val r = HoldingRecommender.recommend(
            price = 10.0, band = weeklyLower, latestYearlyDividend = 0.60, // 6%
            dailyBand = dailyLower, monthlyBand = monthlyBand
        )
        assertThat(r.action).isEqualTo(HoldingAction.HOLD)
    }

    // ── 卖出 / 数据不足（不受多周期影响）──────────────────────────────

    @Test
    fun `price at upper returns SELL regardless of high yield`() {
        val r = HoldingRecommender.recommend(
            price = 11.5, band = weeklyLower, latestYearlyDividend = 1.00,
            dailyBand = dailyLower, monthlyBand = monthlyBand
        )
        assertThat(r.action).isEqualTo(HoldingAction.SELL)
        assertThat(r.bollTone).isEqualTo(BollTone.Sell)
    }

    @Test
    fun `null weekly band returns INSUFFICIENT_DATA with boll reason`() {
        val r = HoldingRecommender.recommend(
            price = 10.0, band = null, latestYearlyDividend = 0.5,
            dailyBand = dailyLower, monthlyBand = monthlyBand
        )
        assertThat(r.action).isEqualTo(HoldingAction.INSUFFICIENT_DATA)
        assertThat(r.reasons.any { it.contains("boll") }).isTrue()
        assertThat(r.dividendYield).isNull()
    }

    @Test
    fun `non-positive price returns INSUFFICIENT_DATA`() {
        val r = HoldingRecommender.recommend(
            price = 0.0, band = weeklyLower, latestYearlyDividend = 0.5,
            dailyBand = dailyLower, monthlyBand = monthlyBand
        )
        assertThat(r.action).isEqualTo(HoldingAction.INSUFFICIENT_DATA)
    }

    @Test
    fun `NaN price returns INSUFFICIENT_DATA`() {
        val r = HoldingRecommender.recommend(
            price = Double.NaN, band = weeklyLower, latestYearlyDividend = 0.5,
            dailyBand = dailyLower, monthlyBand = monthlyBand
        )
        assertThat(r.action).isEqualTo(HoldingAction.INSUFFICIENT_DATA)
    }

    @Test
    fun `custom min yield threshold takes effect on resonance`() {
        // yield ~2.5%：默认门槛(min=2)共振→买，但 min=3 时降级
        val rDefault = HoldingRecommender.recommend(
            price = 8.8, band = weeklyLower, latestYearlyDividend = 0.22,
            thresholds = DividendThresholds(), dailyBand = dailyLower, monthlyBand = monthlyBand
        )
        val rStrict = HoldingRecommender.recommend(
            price = 8.8, band = weeklyLower, latestYearlyDividend = 0.22,
            thresholds = DividendThresholds(minYieldPercent = 3.0, boostYieldPercent = 6.0),
            dailyBand = dailyLower, monthlyBand = monthlyBand
        )
        assertThat(rDefault.action).isEqualTo(HoldingAction.BUY)
        assertThat(rStrict.action).isEqualTo(HoldingAction.HOLD)
    }

    @Test
    fun `priceVsLower is 0 at lower and 1 at upper`() {
        val rLower = HoldingRecommender.recommend(
            price = 9.0, band = weeklyLower, latestYearlyDividend = null,
            dailyBand = dailyLower, monthlyBand = monthlyBand
        )
        val rUpper = HoldingRecommender.recommend(
            price = 11.0, band = weeklyLower, latestYearlyDividend = null,
            dailyBand = dailyLower, monthlyBand = monthlyBand
        )
        assertThat(rLower.priceVsLower).isWithin(1e-9).of(0.0)
        assertThat(rUpper.priceVsLower).isWithin(1e-9).of(1.0)
    }

    // ── bollTone 直接测试（迁移自 BollPriceScale，仅用于卡片落点高亮）──────

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
