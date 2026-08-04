package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * [GridAnchorCalculator]（网格 BOLL + 目标股息率锚定）单测。
 *
 * 规则：基准=BOLL 中轨、上界=BOLL 上轨、下界=min(BOLL 下轨, 目标股息率底)。
 * 到达目标股息率 = 网格资金用完位。
 */
class GridAnchorCalculatorTest {

    private fun band(lower: Double, middle: Double, upper: Double) = BollBand(middle, upper, lower)

    /** 基础场景：BOLL 8/10/12，股息 0.6，目标股息率 6% → 股息底 10.0。
     *  股息底(10) > BOLL下轨(8) → 下界取 8（技术面主导）。 */
    @Test
    fun `basic anchor with boll lower dominating`() {
        val a = GridAnchorCalculator.anchor(
            band = band(8.0, 10.0, 12.0),
            latestYearlyDividend = 0.6,
            targetYieldPercent = 6.0
        )!!
        assertThat(a.basePrice).isEqualTo(10.0)
        assertThat(a.highPrice).isEqualTo(12.0)
        assertThat(a.targetYieldPercent).isEqualTo(6.0)
        assertThat(a.dividendFloorPrice).isEqualTo(10.0)       // 0.6 / 0.06
        // min(8, 10) = 8
        assertThat(a.lowPrice).isEqualTo(8.0)
        assertThat(a.lowAnchoredByDividend).isFalse()          // BOLL 下轨更低
    }

    /** 目标股息率底主导：股息底低于 BOLL 下轨 → 下界取股息底（价值底主导，资金在此用完）。 */
    @Test
    fun `dividend floor dominates when below boll lower`() {
        // BOLL 11/13/15，股息 0.6，目标 6% → 股息底 10.0 < BOLL下轨 11 → 下界 10
        val a = GridAnchorCalculator.anchor(
            band = band(11.0, 13.0, 15.0),
            latestYearlyDividend = 0.6,
            targetYieldPercent = 6.0
        )!!
        assertThat(a.lowPrice).isEqualTo(10.0)
        assertThat(a.lowAnchoredByDividend).isTrue()
    }

    /** 目标股息率越高 → 股息底越低（要求更深的下跌才把资金用完，安全垫更厚）。 */
    @Test
    fun `higher target yield lowers dividend floor`() {
        val lowYield = GridAnchorCalculator.anchor(band(8.0, 10.0, 12.0), 0.6, 5.0)!!
        val highYield = GridAnchorCalculator.anchor(band(8.0, 10.0, 12.0), 0.6, 7.5)!!
        // 目标 5% → 股息底 12.0；目标 7.5% → 股息底 8.0
        assertThat(lowYield.dividendFloorPrice).isEqualTo(12.0)
        assertThat(highYield.dividendFloorPrice).isEqualTo(8.0)
        assertThat(highYield.dividendFloorPrice).isLessThan(lowYield.dividendFloorPrice)
    }

    /** 缺分红 → null。 */
    @Test
    fun `missing dividend returns null`() {
        val a = GridAnchorCalculator.anchor(band(8.0, 10.0, 12.0), 0.0, 6.0)
        assertThat(a).isNull()
    }

    /** 目标股息率非正 → null。 */
    @Test
    fun `nonpositive target yield returns null`() {
        val a = GridAnchorCalculator.anchor(band(8.0, 10.0, 12.0), 0.6, 0.0)
        assertThat(a).isNull()
    }

    /** BOLL 非正 → null。 */
    @Test
    fun `invalid boll returns null`() {
        val a = GridAnchorCalculator.anchor(band(0.0, 10.0, 12.0), 0.6, 6.0)
        assertThat(a).isNull()
    }

    /** 锚定结果破坏 low < base < high → null。 */
    @Test
    fun `anchor violating low_lt_base returns null`() {
        // BOLL 下轨 10.5 > 中轨 10（异常 BOLL）；股息底 10 = base；low=min(10.5,10)=10 不 < base 10 → null
        val a = GridAnchorCalculator.anchor(band(10.5, 10.0, 12.0), 0.6, 6.0)
        assertThat(a).isNull()
    }

    /** 完整锚定满足 GridCalculator.generate 的 low<base<high 约束（可直接喂入生成档位）。 */
    @Test
    fun `anchor output is consumable by grid calculator`() {
        val a = GridAnchorCalculator.anchor(band(8.0, 10.0, 12.0), 0.6, 6.0)!!
        val grid = GridCalculator.generate(
            basePrice = a.basePrice,
            lowPrice = a.lowPrice,
            highPrice = a.highPrice,
            grids = 4,
            totalCapital = 100000.0
        )
        assertThat(grid.validationError).isNull()
        assertThat(grid.levels).isNotEmpty()
    }

    /** 到达下界时股息率恰好等于目标（资金用完位语义验证）。 */
    @Test
    fun `low boundary yield equals target when dividend floor dominates`() {
        // 股息 0.6，目标 6% → 股息底 10；BOLL 下轨 11 → 下界取股息底 10
        val a = GridAnchorCalculator.anchor(band(11.0, 13.0, 15.0), 0.6, 6.0)!!
        // 下界价 10 对应的股息率 = 0.6/10 = 6% = 目标
        val yieldAtLow = a.latestYearlyDividend / a.lowPrice * 100.0
        assertThat(yieldAtLow).isWithin(0.0001).of(a.targetYieldPercent)
    }
}
