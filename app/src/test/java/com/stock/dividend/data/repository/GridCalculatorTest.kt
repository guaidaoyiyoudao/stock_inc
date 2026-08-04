package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * [GridCalculator]（纯买入网格档位表）单测。
 *
 * 核心规则：买入区间 [lowPrice, basePrice] 等分 grids 档（含两端），
 * 档位**全部为买入**（无卖出档）；资金 1/price 反比分配（越便宜买越多）；
 * 下一档 = 现价下方最近的买入档。
 */
class GridCalculatorTest {

    /** 基础场景：买入起点 10、资金用完位 8、4 档 → 档位 8/8.67/9.33/10（含两端等分）。 */
    @Test
    fun `basic grid generates buy levels from low to base`() {
        val r = GridCalculator.generate(
            basePrice = 10.0, lowPrice = 8.0, highPrice = 12.0,
            grids = 4, totalCapital = 100000.0
        )
        assertThat(r.validationError).isNull()
        // 4 档：8、8.67、9.33、10（(10-8)/3 步长）
        assertThat(r.levels).hasSize(4)
        assertThat(r.levels.first().price).isEqualTo(8.0)      // 最便宜档在前
        assertThat(r.levels.last().price).isEqualTo(10.0)      // 买入起点=最贵档
        // 全部为买入
        assertThat(r.buyLevels).hasSize(4)
        assertThat(r.sellLevels).isEmpty()
        assertThat(r.levels.all { it.isBuy }).isTrue()
    }

    /** 卖出档恒为空（纯买入模型，杜绝「买了涨了就卖」）。 */
    @Test
    fun `no sell levels in pure buy model`() {
        val r = GridCalculator.generate(
            basePrice = 10.0, lowPrice = 8.0, highPrice = 12.0,
            grids = 4, totalCapital = 100000.0
        )
        assertThat(r.sellLevels).isEmpty()
        assertThat(r.nextSellHint).isNull()
    }

    /** 资金反比分配：最便宜的档（8 元）买入金额最多。 */
    @Test
    fun `lower price gets more capital via inverse weighting`() {
        val r = GridCalculator.generate(
            basePrice = 10.0, lowPrice = 8.0, highPrice = 12.0,
            grids = 4, totalCapital = 100000.0
        )
        val first = r.levels.first()   // 8 元档
        val last = r.levels.last()     // 10 元档
        assertThat(first.amount).isGreaterThan(last.amount)
    }

    /** 股数按 100 股整手向下取整。 */
    @Test
    fun `shares are floored to 100-lot`() {
        val r = GridCalculator.generate(
            basePrice = 10.0, lowPrice = 8.0, highPrice = 12.0,
            grids = 4, totalCapital = 100000.0
        )
        r.levels.forEach { assertThat(it.shares % 100).isEqualTo(0) }
    }

    /** 下一档提示：现价 9.5 → 下方最近买入档 9.33。 */
    @Test
    fun `next buy hint points to level below current price`() {
        val r = GridCalculator.generate(
            basePrice = 10.0, lowPrice = 8.0, highPrice = 12.0,
            grids = 4, totalCapital = 100000.0,
            currentPrice = 9.5
        )
        // 档位 8/8.67/9.33/10，现价 9.5 下方最近 = 9.33
        assertThat(r.nextBuyHint).isEqualTo(9.33)
        assertThat(r.nextSellHint).isNull()
    }

    /** 现价已跌破资金用完位 → 无下一档（资金已用完）。 */
    @Test
    fun `no next buy hint below floor`() {
        val r = GridCalculator.generate(
            basePrice = 10.0, lowPrice = 8.0, highPrice = 12.0,
            grids = 4, totalCapital = 100000.0,
            currentPrice = 7.5  // 跌破 8 元资金用完位
        )
        assertThat(r.nextBuyHint).isNull()
    }

    /** 无现价 → 无提示。 */
    @Test
    fun `no current price yields null hints`() {
        val r = GridCalculator.generate(
            basePrice = 10.0, lowPrice = 8.0, highPrice = 12.0,
            grids = 4, totalCapital = 100000.0
        )
        assertThat(r.nextBuyHint).isNull()
        assertThat(r.nextSellHint).isNull()
    }

    /** 参考上界仅展示，不参与分档。 */
    @Test
    fun `high price is reference only`() {
        val r = GridCalculator.generate(
            basePrice = 10.0, lowPrice = 8.0, highPrice = 12.0,
            grids = 4, totalCapital = 100000.0
        )
        assertThat(r.highPrice).isEqualTo(12.0)
        // 档位不包含 12（不参与买入分档）
        assertThat(r.levels.map { it.price }).doesNotContain(12.0)
    }

    /** 参数非法：买入起点 ≤ 资金用完位。 */
    @Test
    fun `invalid range returns error`() {
        val r = GridCalculator.generate(
            basePrice = 8.0, lowPrice = 10.0, highPrice = 12.0,
            grids = 4, totalCapital = 100000.0
        )
        assertThat(r.validationError).isNotNull()
        assertThat(r.levels).isEmpty()
    }

    /** 参数非法：档数 < 2。 */
    @Test
    fun `invalid grids returns error`() {
        val r = GridCalculator.generate(
            basePrice = 10.0, lowPrice = 8.0, highPrice = 12.0,
            grids = 1, totalCapital = 100000.0
        )
        assertThat(r.validationError).isNotNull()
    }

    /** 参数非法：价格非正。 */
    @Test
    fun `nonpositive price returns error`() {
        val r = GridCalculator.generate(
            basePrice = 0.0, lowPrice = 8.0, highPrice = 12.0,
            grids = 4, totalCapital = 100000.0
        )
        assertThat(r.validationError).isNotNull()
    }

    /** 资金全部分配到买入档（无卖出侧消耗）。 */
    @Test
    fun `all capital allocated to buy levels`() {
        val r = GridCalculator.generate(
            basePrice = 10.0, lowPrice = 8.0, highPrice = 12.0,
            grids = 4, totalCapital = 100000.0
        )
        val buyTotal = r.buyLevels.sumOf { it.amount }
        // 整手取整有少量误差，允许 ±10% 容差
        assertThat(buyTotal).isWithin(10000.0).of(100000.0)
    }
}
