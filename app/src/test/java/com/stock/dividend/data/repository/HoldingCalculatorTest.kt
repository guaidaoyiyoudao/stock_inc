package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.local.entity.TransactionEntity
import org.junit.Test

/**
 * [HoldingCalculator]（摊薄成本法）单测。
 *
 * 摊薄公式：成本价 = (买入总额 − 卖出总额) / 持仓数量。
 * 核心：卖出盈利→均价降，卖出亏损→均价升，单纯卖出也会改变均价。
 */
class HoldingCalculatorTest {

    private fun tx(id: Long, type: String, shares: Int, price: Double, date: String) =
        TransactionEntity(
            id = id,
            stockCode = "sz.000001",
            type = type,
            shares = shares,
            price = price,
            date = date
        )

    @Test
    fun `empty transactions yields zero holding`() {
        val holding = HoldingCalculator.calculate(emptyList())
        assertThat(holding.totalShares).isEqualTo(0)
        assertThat(holding.avgCostPerShare).isEqualTo(0.0)
    }

    @Test
    fun `pure buy computes weighted average`() {
        val holding = HoldingCalculator.calculate(
            listOf(
                tx(1, "BUY", 100, 10.0, "2026-01-01"),
                tx(2, "BUY", 100, 14.0, "2026-02-01")
            )
        )
        // 持仓 200；均价 (100*10 + 100*14)/200 = 12
        assertThat(holding.totalShares).isEqualTo(200)
        assertThat(holding.avgCostPerShare).isEqualTo(12.0)
    }

    /**
     * 核心场景：卖出盈利 → 均价降低。
     * 这是用户要求「同花顺语义」的关键行为，移动加权平均做不到。
     */
    @Test
    fun `sell at profit lowers average cost`() {
        val holding = HoldingCalculator.calculate(
            listOf(
                tx(1, "BUY", 100, 10.0, "2026-01-01"),
                tx(2, "BUY", 100, 14.0, "2026-02-01"),
                tx(3, "SELL", 50, 15.0, "2026-03-01") // 卖出盈利
            )
        )
        // 摊薄 = (1000 + 1400 − 750) / 150 = 1650 / 150 = 11.0
        assertThat(holding.totalShares).isEqualTo(150)
        assertThat(holding.avgCostPerShare).isEqualTo(11.0)
    }

    /**
     * 富途官方示例，用于锁定公式正确性（跨平台交叉验证）。
     * 参考：富途「成本价介绍」—— 买1000@10、卖500@12 → 摊薄成本 8.0。
     */
    @Test
    fun `futu official example yields 8`() {
        val holding = HoldingCalculator.calculate(
            listOf(
                tx(1, "BUY", 1000, 10.0, "2026-01-01"),
                tx(2, "SELL", 500, 12.0, "2026-02-01")
            )
        )
        // (10000 − 6000) / 500 = 8.0
        assertThat(holding.totalShares).isEqualTo(500)
        assertThat(holding.avgCostPerShare).isEqualTo(8.0)
    }

    /** 对称场景：卖出亏损 → 均价升高。 */
    @Test
    fun `sell at loss raises average cost`() {
        val holding = HoldingCalculator.calculate(
            listOf(
                tx(1, "BUY", 100, 10.0, "2026-01-01"),
                tx(2, "SELL", 50, 8.0, "2026-02-01") // 卖出亏损
            )
        )
        // 摊薄 = (1000 − 400) / 50 = 600 / 50 = 12.0
        assertThat(holding.totalShares).isEqualTo(50)
        assertThat(holding.avgCostPerShare).isEqualTo(12.0)
    }

    @Test
    fun `full sell zeroes out shares and cost`() {
        val holding = HoldingCalculator.calculate(
            listOf(
                tx(1, "BUY", 100, 10.0, "2026-01-01"),
                tx(2, "SELL", 100, 15.0, "2026-02-01") // 全仓卖出（盈利）
            )
        )
        // 持仓 0 → 均价归 0（不保留历史）
        assertThat(holding.totalShares).isEqualTo(0)
        assertThat(holding.avgCostPerShare).isEqualTo(0.0)
    }

    @Test
    fun `sell exceeding holdings is clamped to zero`() {
        val holding = HoldingCalculator.calculate(
            listOf(
                tx(1, "BUY", 100, 10.0, "2026-01-01"),
                tx(2, "SELL", 250, 15.0, "2026-02-01") // 卖超 150 股
            )
        )
        // 卖超部分忽略，持仓钳到 0，均价归 0
        assertThat(holding.totalShares).isEqualTo(0)
        assertThat(holding.avgCostPerShare).isEqualTo(0.0)
    }

    /** 摊薄成本法不依赖交易时间顺序，验证顺序无关性。 */
    @Test
    fun `order independent`() {
        val a = HoldingCalculator.calculate(
            listOf(
                tx(1, "BUY", 100, 10.0, "2026-01-01"),
                tx(2, "SELL", 50, 15.0, "2026-02-01"),
                tx(3, "BUY", 100, 14.0, "2026-03-01")
            )
        )
        val b = HoldingCalculator.calculate(
            listOf(
                tx(3, "BUY", 100, 14.0, "2026-03-01"),
                tx(1, "BUY", 100, 10.0, "2026-01-01"),
                tx(2, "SELL", 50, 15.0, "2026-02-01")
            )
        )
        // 两种顺序结果应一致：买入 2400、卖出 750、持仓 150 → 11.0
        assertThat(a).isEqualTo(b)
        assertThat(a.avgCostPerShare).isEqualTo(11.0)
    }

    @Test
    fun `sell before any buy still dilutes via negative contribution`() {
        // 无买入直接卖出：sellAmount>0、buyAmount=0、持仓=0→均价归 0（钳零兜底）
        val holding = HoldingCalculator.calculate(
            listOf(
                tx(1, "SELL", 100, 15.0, "2026-01-01")
            )
        )
        assertThat(holding.totalShares).isEqualTo(0)
        assertThat(holding.avgCostPerShare).isEqualTo(0.0)
    }
}
