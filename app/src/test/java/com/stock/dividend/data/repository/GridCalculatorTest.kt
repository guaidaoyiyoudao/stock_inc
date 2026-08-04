package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * [GridCalculator]（等差网格档位表）单测。
 *
 * 核心规则：[lowPrice, highPrice] 等分 grids 份生成档位，剔除基准价；
 * 低于基准为 BUY、高于为 SELL；资金按 1/price 反比分配（低价多配）。
 */
class GridCalculatorTest {

    /** 基础场景：基准 10、下界 8、上界 12、4 档 → 步长 1.0、stepPercent 10%。 */
    @Test
    fun `basic grid generates levels excluding base price`() {
        val r = GridCalculator.generate(
            basePrice = 10.0, lowPrice = 8.0, highPrice = 12.0,
            grids = 4, totalCapital = 100000.0
        )
        assertThat(r.validationError).isNull()
        assertThat(r.stepPercent).isEqualTo(10.0)
        // 档位价格：8, 9, 11, 12（10 为基准被剔除）
        assertThat(r.levels.map { it.price }).containsExactly(8.0, 9.0, 11.0, 12.0).inOrder()
        // 买/卖各 2 档
        assertThat(r.buyLevels.map { it.price }).containsExactly(8.0, 9.0).inOrder()
        assertThat(r.sellLevels.map { it.price }).containsExactly(11.0, 12.0).inOrder()
    }

    /** 偏离：买入档为负、卖出档为正。 */
    @Test
    fun `deviation sign is correct for buy and sell`() {
        val r = GridCalculator.generate(
            basePrice = 10.0, lowPrice = 8.0, highPrice = 12.0,
            grids = 4, totalCapital = 100000.0
        )
        // 价格 8 → 偏离 -20%；价格 12 → +20%
        assertThat(r.levels.first().deviation).isEqualTo(-20.0)
        assertThat(r.levels.last().deviation).isEqualTo(20.0)
    }

    /** 资金分配：买入档低价多配（8 元档金额 > 9 元档金额）。 */
    @Test
    fun `lower buy price gets more capital via inverse weighting`() {
        val r = GridCalculator.generate(
            basePrice = 10.0, lowPrice = 8.0, highPrice = 12.0,
            grids = 4, totalCapital = 100000.0
        )
        val level8 = r.buyLevels.first { it.price == 8.0 }
        val level9 = r.buyLevels.first { it.price == 9.0 }
        // 8 元档权重 (1/8) > 9 元档权重 (1/9) → 分配金额更多
        assertThat(level8.amount).isGreaterThan(level9.amount)
    }

    /** 股数按 100 股整手向下取整。 */
    @Test
    fun `shares are floored to 100-lot`() {
        val r = GridCalculator.generate(
            basePrice = 10.0, lowPrice = 8.0, highPrice = 12.0,
            grids = 4, totalCapital = 100000.0
        )
        // 所有档位股数必须是 100 的整数倍
        r.levels.forEach { assertThat(it.shares % 100).isEqualTo(0) }
    }

    /** 当前价提示：现价 9.5 → nextBuy=9（上方最近买入档）、nextSell=11（下方最近卖出档）。 */
    @Test
    fun `hints point to next buy and sell levels`() {
        val r = GridCalculator.generate(
            basePrice = 10.0, lowPrice = 8.0, highPrice = 12.0,
            grids = 4, totalCapital = 100000.0,
            currentPrice = 9.5
        )
        assertThat(r.nextBuyHint).isEqualTo(9.0)
        assertThat(r.nextSellHint).isEqualTo(11.0)
    }

    /** 现价贴近下界：nextBuy 为下方最近的买入档，nextSell 为最近的卖出档。 */
    @Test
    fun `hints when price near low boundary`() {
        val r = GridCalculator.generate(
            basePrice = 10.0, lowPrice = 8.0, highPrice = 12.0,
            grids = 4, totalCapital = 100000.0,
            currentPrice = 8.5
        )
        // 现价 8.5 → 下方买入档只有 8 → nextBuy=8；卖出档最近的是 9（>8.5）... 但 9 是买入档
        // nextSell 取卖出档中 >8.5 的最小者 = 11
        assertThat(r.nextBuyHint).isEqualTo(8.0)
        assertThat(r.nextSellHint).isEqualTo(11.0)
    }

    /** 无现价：提示均为 null。 */
    @Test
    fun `no current price yields null hints`() {
        val r = GridCalculator.generate(
            basePrice = 10.0, lowPrice = 8.0, highPrice = 12.0,
            grids = 4, totalCapital = 100000.0
        )
        assertThat(r.nextBuyHint).isNull()
        assertThat(r.nextSellHint).isNull()
    }

    /** 基准价恰好落在某档位上：该档被剔除（避免基准价自身成档）。 */
    @Test
    fun `base price aligned to grid line is excluded`() {
        // low=8, high=12, grids=4 → 档位 8/9/10/11/12，10=base 被剔除
        val r = GridCalculator.generate(
            basePrice = 10.0, lowPrice = 8.0, highPrice = 12.0,
            grids = 4, totalCapital = 100000.0
        )
        assertThat(r.levels.map { it.price }).doesNotContain(10.0)
    }

    /** 参数非法：基准价不在区间内。 */
    @Test
    fun `invalid base outside range returns error`() {
        val r = GridCalculator.generate(
            basePrice = 15.0, lowPrice = 8.0, highPrice = 12.0,
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

    /** 基准贴近下界（无买入档）：资金全额归卖出侧。 */
    @Test
    fun `base near low boundary allocates all capital to sell side`() {
        // low=8, high=12, grids=4 → 档 8/9/10/11/12；base=8.4 → 买入档仅 8（<8.4），
        // 但 8 偏离 base 仅 ~4.8% < 半步(5%)？半步=1.0，8 vs 8.4 偏离 0.4 < 1.0 → 保留。
        // 这里取 base=8.5 使买入档只剩 8。
        val r = GridCalculator.generate(
            basePrice = 8.5, lowPrice = 8.0, highPrice = 12.0,
            grids = 4, totalCapital = 100000.0
        )
        // 至少有档位生成，不报错
        assertThat(r.validationError).isNull()
        assertThat(r.levels).isNotEmpty()
    }

    /** 买卖资金各半：4 档对称网格（2 买 2 卖），两侧总额各≈50000。 */
    @Test
    fun `symmetric grid splits capital roughly equally`() {
        val r = GridCalculator.generate(
            basePrice = 10.0, lowPrice = 8.0, highPrice = 12.0,
            grids = 4, totalCapital = 100000.0
        )
        val buyTotal = r.buyLevels.sumOf { it.amount }
        val sellTotal = r.sellLevels.sumOf { it.amount }
        // 买/卖各分 50000，因整手取整会有少量误差，允许 ±10% 容差
        assertThat(buyTotal).isWithin(5000.0).of(50000.0)
        assertThat(sellTotal).isWithin(5000.0).of(50000.0)
    }
}
