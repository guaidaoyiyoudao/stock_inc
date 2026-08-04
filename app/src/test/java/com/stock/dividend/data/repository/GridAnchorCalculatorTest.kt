package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * [GridAnchorCalculator]（三周期 BOLL + 目标股息率锚定）单测。
 *
 * 规则：买入起点 = min(日 BOLL 下轨, 周 BOLL 下轨, 月 BOLL 中轨)（「中轨及以下」防守型建仓）；
 * 资金用完位 = min(三周期下轨最低者, 目标股息率底)，且必须**严格低于买入起点**
 * （股息底 ≥ 起点说明目标股息率定低/无下行空间 → 锚定失败，提示调高目标股息率）。
 * 参考上界 = 月 BOLL 上轨。
 */
class GridAnchorCalculatorTest {

    private fun band(lower: Double, middle: Double, upper: Double) = BollBand(middle, upper, lower)

    /** 基础场景：日 9/10/11、周 8/10/12、月 9/11/13，股息 0.6，目标 8% → 股息底 7.5。
     *  买入起点 = min(日下轨9, 周下轨8, 月BOLL中轨11) = 8（周下轨）。
     *  技术下轨最低 = min(9, 8, 月下轨9) = 8；资金用完位 = min(8, 7.5) = 7.5。
     *  参考上界 = 月 BOLL 上轨 13。 */
    @Test
    fun `basic anchor start at weekly lower`() {
        val a = GridAnchorCalculator.anchor(
            dailyBand = band(9.0, 10.0, 11.0),
            weeklyBand = band(8.0, 10.0, 12.0),
            monthlyBand = band(9.0, 11.0, 13.0),
            latestYearlyDividend = 0.6,
            targetYieldPercent = 8.0
        )!!
        assertThat(a.basePrice).isEqualTo(8.0)        // min(9, 8, 11) = 8
        assertThat(a.monthlyMiddle).isEqualTo(11.0)   // 月 BOLL 中轨
        assertThat(a.highPrice).isEqualTo(13.0)       // 月 BOLL 上轨
        assertThat(a.lowPrice).isEqualTo(7.5)         // min(8, 7.5) = 7.5
        assertThat(a.dividendFloorPrice).isEqualTo(7.5)
        assertThat(a.targetYieldPercent).isEqualTo(8.0)
    }

    /** 买入起点必须 ≤ 月 BOLL 中轨（「中轨及以下」）：即便日/周下轨高于月线中枢，也取月线中枢。 */
    @Test
    fun `start does not exceed monthly middle`() {
        // 日 12/13/14、周 12.5/13.5/15（下轨都 > 月BOLL中轨 11）→ 起点取月BOLL中轨 11
        val a = GridAnchorCalculator.anchor(
            dailyBand = band(12.0, 13.0, 14.0),
            weeklyBand = band(12.5, 13.5, 15.0),
            monthlyBand = band(10.0, 11.0, 12.0),
            latestYearlyDividend = 0.6,
            targetYieldPercent = 8.0                  // 股息底 7.5 < 起点 11
        )!!
        assertThat(a.basePrice).isEqualTo(11.0)       // min(12, 12.5, 11) = 11（月BOLL中轨）
        assertThat(a.lowPrice).isEqualTo(7.5)
    }

    /** 股息底主导资金用完位：股息底低于技术下轨最低 → 资金用完位取股息底。 */
    @Test
    fun `dividend floor dominates low when below tech lower`() {
        // 技术下轨最低 11（周下轨），股息底 10（目标 6%）→ 资金用完位 10
        val a = GridAnchorCalculator.anchor(
            dailyBand = band(12.0, 13.0, 14.0),
            weeklyBand = band(11.0, 12.0, 13.0),
            monthlyBand = band(11.5, 13.0, 15.0),
            latestYearlyDividend = 0.6,
            targetYieldPercent = 6.0
        )!!
        assertThat(a.lowPrice).isEqualTo(10.0)
        assertThat(a.lowAnchoredByDividend).isTrue()
    }

    /** 目标股息率越高 → 股息底越低（更深安全垫）。 */
    @Test
    fun `higher target yield lowers dividend floor`() {
        val d = band(10.0, 11.0, 12.0)
        val w = band(9.5, 11.0, 12.5)
        val m = band(10.0, 12.0, 14.0)
        val lowYield = GridAnchorCalculator.anchor(d, w, m, 0.4, 5.0)!!     // 股息底 8
        val highYield = GridAnchorCalculator.anchor(d, w, m, 0.4, 7.5)!!    // 股息底 5.33
        assertThat(lowYield.dividendFloorPrice).isEqualTo(8.0)
        assertThat(highYield.dividendFloorPrice).isEqualTo(5.33)
        assertThat(highYield.dividendFloorPrice).isLessThan(lowYield.dividendFloorPrice)
    }

    /** 缺某一周期（如日线数据不足）→ 跳过该周期，其余周期仍可锚定。 */
    @Test
    fun `missing daily band falls back to weekly and monthly`() {
        val a = GridAnchorCalculator.anchor(
            dailyBand = null,
            weeklyBand = band(8.0, 10.0, 12.0),
            monthlyBand = band(9.0, 11.0, 13.0),
            latestYearlyDividend = 0.6,
            targetYieldPercent = 8.0                  // 股息底 7.5 < 起点 8
        )!!
        // 起点 = min(周下轨 8, 月BOLL中轨 11) = 8
        assertThat(a.basePrice).isEqualTo(8.0)
    }

    /** 全部周期缺失 → null。 */
    @Test
    fun `all bands missing returns null`() {
        val a = GridAnchorCalculator.anchor(null, null, null, 0.6, 8.0)
        assertThat(a).isNull()
    }

    /** 缺分红 → null。 */
    @Test
    fun `missing dividend returns null`() {
        val a = GridAnchorCalculator.anchor(band(9.0, 10.0, 11.0), band(8.0, 10.0, 12.0), band(9.0, 11.0, 13.0), 0.0, 8.0)
        assertThat(a).isNull()
    }

    /** 目标股息率非正 → null。 */
    @Test
    fun `nonpositive target yield returns null`() {
        val a = GridAnchorCalculator.anchor(band(9.0, 10.0, 11.0), band(8.0, 10.0, 12.0), band(9.0, 11.0, 13.0), 0.6, 0.0)
        assertThat(a).isNull()
    }

    /** 股息底 ≥ 买入起点（目标股息率定低/无下行空间）→ null，提示调高目标股息率。 */
    @Test
    fun `anchor returns null when dividend floor not below start`() {
        // 起点 = min(11, 10.5, 月BOLL中轨10.5) = 10.5；股息底 12（目标 5%）≥ 起点 → null
        val a = GridAnchorCalculator.anchor(
            dailyBand = band(11.0, 12.0, 13.0),
            weeklyBand = band(10.5, 11.5, 12.5),
            monthlyBand = band(10.6, 10.5, 11.0), // 月BOLL中轨 10.5
            latestYearlyDividend = 0.6,
            targetYieldPercent = 5.0               // 股息底 12
        )
        assertThat(a).isNull()
    }

    /** 完整锚定可直接喂入纯买入 GridCalculator（low<base 即可）。 */
    @Test
    fun `anchor output is consumable by grid calculator`() {
        val a = GridAnchorCalculator.anchor(
            dailyBand = band(9.0, 10.0, 11.0),
            weeklyBand = band(8.0, 10.0, 12.0),
            monthlyBand = band(9.0, 11.0, 13.0),
            latestYearlyDividend = 0.6,
            targetYieldPercent = 8.0
        )!!
        val grid = GridCalculator.generate(
            basePrice = a.basePrice,
            lowPrice = a.lowPrice,
            highPrice = a.highPrice,
            grids = 4,
            totalCapital = 100000.0
        )
        assertThat(grid.validationError).isNull()
        assertThat(grid.levels).isNotEmpty()
        // 纯买入：档位全部为买入
        assertThat(grid.levels.all { it.isBuy }).isTrue()
        assertThat(grid.sellLevels).isEmpty()
    }
}
