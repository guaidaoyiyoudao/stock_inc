package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.local.entity.TransactionEntity
import org.junit.Test

/**
 * [HoldingCalculator]（移动加权平均）单测。
 *
 * 重点覆盖移动加权与简单加权的真正差异：
 * 「卖出之后再买入」会让新均价随结转后的剩余成本而变化。
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

    @Test
    fun `partial sell keeps average cost unchanged`() {
        val holding = HoldingCalculator.calculate(
            listOf(
                tx(1, "BUY", 100, 10.0, "2026-01-01"),
                tx(2, "BUY", 100, 14.0, "2026-02-01"),
                tx(3, "SELL", 50, 15.0, "2026-03-01") // 卖出价不影响成本
            )
        )
        // 持仓 150；均价仍 12（卖出按均价结转，不改变剩余均价）
        assertThat(holding.totalShares).isEqualTo(150)
        assertThat(holding.avgCostPerShare).isEqualTo(12.0)
    }

    @Test
    fun `full sell zeroes out shares and cost`() {
        val holding = HoldingCalculator.calculate(
            listOf(
                tx(1, "BUY", 100, 10.0, "2026-01-01"),
                tx(2, "SELL", 100, 15.0, "2026-02-01") // 全仓卖出
            )
        )
        // 清仓：持仓 0，成本归 0（不保留历史均价）
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
        // 卖超部分忽略，持仓钳到 0，成本归 0
        assertThat(holding.totalShares).isEqualTo(0)
        assertThat(holding.avgCostPerShare).isEqualTo(0.0)
    }

    /**
     * 核心差异用例：卖出结转后，剩余持仓成本变小；再买入时新均价会
     * 基于结转后的剩余成本重算，而非「全部历史买入的加权均价」。
     *
     * 数据：买100@10、买100@14（均价12）→ 卖100（结转，剩100股成本1200，均价仍12）
     *     → 再买100@16（剩100*12 + 100*16 = 2800 / 200 = 14）
     * 简单加权平均会无视中间的卖出，仍算成 (100*10+100*14+100*16)/300 = 13.33。
     */
    @Test
    fun `buy after sell uses carried-over cost basis`() {
        val holding = HoldingCalculator.calculate(
            listOf(
                tx(1, "BUY", 100, 10.0, "2026-01-01"),
                tx(2, "BUY", 100, 14.0, "2026-02-01"),
                tx(3, "SELL", 100, 15.0, "2026-03-01"),
                tx(4, "BUY", 100, 16.0, "2026-04-01")
            )
        )
        // 移动加权：持仓 200；均价 (100*12 + 100*16)/200 = 14.0
        assertThat(holding.totalShares).isEqualTo(200)
        assertThat(holding.avgCostPerShare).isEqualTo(14.0)
    }

    @Test
    fun `transaction order affects result`() {
        // 同样两笔交易，顺序不同 → 结果不同（验证顺序敏感性）。
        // 先贵买后便宜买 vs 先便宜买后贵买，均价相同，但若夹卖出则不同。
        val data = listOf(
            tx(1, "BUY", 100, 10.0, "2026-01-01"),
            tx(2, "SELL", 50, 0.0, "2026-02-01"),
            tx(3, "BUY", 100, 20.0, "2026-03-01")
        )
        val holding = HoldingCalculator.calculate(data)
        // 卖50后剩50@10=500；再买100@20 → 2500/150 ≈ 16.666...
        assertThat(holding.totalShares).isEqualTo(150)
        assertThat(holding.avgCostPerShare).isWithin(1e-9).of(2500.0 / 150.0)
    }

    @Test
    fun `sell before any buy is ignored`() {
        val holding = HoldingCalculator.calculate(
            listOf(
                tx(1, "SELL", 100, 15.0, "2026-01-01"), // 无持仓时卖出，全忽略
                tx(2, "BUY", 100, 10.0, "2026-02-01")
            )
        )
        // 卖出被忽略，仅剩买入：持仓 100，均价 10
        assertThat(holding.totalShares).isEqualTo(100)
        assertThat(holding.avgCostPerShare).isEqualTo(10.0)
    }

    @Test
    fun `sell with zero price still carries cost`() {
        val holding = HoldingCalculator.calculate(
            listOf(
                tx(1, "BUY", 100, 10.0, "2026-01-01"),
                tx(2, "SELL", 50, 0.0, "2026-02-01") // 卖出价=0（选填），不影响结转
            )
        )
        // 卖出价不参与成本计算，剩 50 股均价仍 10
        assertThat(holding.totalShares).isEqualTo(50)
        assertThat(holding.avgCostPerShare).isEqualTo(10.0)
    }
}
