package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.local.entity.TransactionEntity
import org.junit.Test

/**
 * [RealizedPnlCalculator]（FIFO 已实现盈亏）单测。
 *
 * 核心规则：卖出按买入时间顺序逐笔结转成本，
 * 已实现盈亏 = 卖出收入 − 结转成本；卖超部分忽略（不计盈亏、不建负仓）。
 */
class RealizedPnlCalculatorTest {

    private fun tx(id: Long, type: String, shares: Int, price: Double, date: String, createdAt: Long = id) =
        TransactionEntity(
            id = id,
            stockCode = "sh.600000",
            type = type,
            shares = shares,
            price = price,
            date = date,
            createdAt = createdAt
        )

    @Test
    fun `empty transactions yields zero realized pnl`() {
        val r = RealizedPnlCalculator.calculate(emptyList())
        assertThat(r).isEqualTo(RealizedPnl.ZERO)
        assertThat(r.totalRealizedPnl).isEqualTo(0.0)
        assertThat(r.totalPnlRate).isNull()
    }

    @Test
    fun `only buys yield zero realized pnl`() {
        val r = RealizedPnlCalculator.calculate(
            listOf(
                tx(1, "BUY", 100, 10.0, "2026-01-01"),
                tx(2, "BUY", 200, 12.0, "2026-02-01")
            )
        )
        // 无卖出 → 无已实现盈亏
        assertThat(r.totalRealizedPnl).isEqualTo(0.0)
        assertThat(r.trades).isEmpty()
    }

    /**
     * 基础 FIFO：买入两批，卖出部分 → 先结转首批成本。
     * 买 100@10、买 100@12 → 卖 150@14。
     * 结转：首批 100 股 @10 = 1000；剩余 50 股取次批 @12 = 600；成本合计 1600。
     * 收入 = 150 × 14 = 2100；盈亏 = 2100 − 1600 = 500。
     */
    @Test
    fun `fifo matches oldest lots first`() {
        val r = RealizedPnlCalculator.calculate(
            listOf(
                tx(1, "BUY", 100, 10.0, "2026-01-01"),
                tx(2, "BUY", 100, 12.0, "2026-02-01"),
                tx(3, "SELL", 150, 14.0, "2026-03-01")
            )
        )
        assertThat(r.totalRealizedPnl).isEqualTo(500.0)
        assertThat(r.totalCostBasis).isEqualTo(1600.0)
        assertThat(r.totalProceeds).isEqualTo(2100.0)
        assertThat(r.totalPnlRate).isWithin(0.0001).of(500.0 / 1600.0 * 100.0)
        assertThat(r.trades).hasSize(1)
        assertThat(r.trades[0].matchedShares).isEqualTo(150)
    }

    /** 卖出亏损：成本高于售价。 */
    @Test
    fun `sell at loss yields negative pnl`() {
        val r = RealizedPnlCalculator.calculate(
            listOf(
                tx(1, "BUY", 100, 10.0, "2026-01-01"),
                tx(2, "SELL", 50, 8.0, "2026-02-01")
            )
        )
        // 结转 50@10 = 500；收入 50×8 = 400；盈亏 = −100
        assertThat(r.totalRealizedPnl).isEqualTo(-100.0)
        assertThat(r.trades[0].realizedPnl).isEqualTo(-100.0)
        assertThat(r.trades[0].pnlRate).isWithin(0.0001).of(-20.0)
    }

    /**
     * 多次卖出累计：首批被部分消耗后，第二批卖出继续从首批剩余结转。
     * 买 100@10 → 卖 30@13（盈利）、再卖 80@11（70 股取首批@10 + 10 股……但只有一批买入 100 股，
     * 第二次卖出 80 时首批剩 70 股，全部结转完）。
     * 卖1：30@10=300 成本，收入 30×13=390，盈亏 +90。
     * 卖2：70@10=700 成本，收入 70×11=770，盈亏 +70。
     * 合计盈亏 +160。
     */
    @Test
    fun `multiple sells accumulate and exhaust oldest lot`() {
        val r = RealizedPnlCalculator.calculate(
            listOf(
                tx(1, "BUY", 100, 10.0, "2026-01-01"),
                tx(2, "SELL", 30, 13.0, "2026-02-01"),
                tx(3, "SELL", 80, 11.0, "2026-03-01")
            )
        )
        assertThat(r.trades).hasSize(2)
        assertThat(r.trades[0].realizedPnl).isEqualTo(90.0)    // 390 − 300
        assertThat(r.trades[1].realizedPnl).isEqualTo(70.0)    // 770 − 700
        assertThat(r.totalRealizedPnl).isEqualTo(160.0)
        assertThat(r.totalCostBasis).isEqualTo(1000.0)          // 全部 100 股结转完毕
        assertThat(r.totalProceeds).isEqualTo(1160.0)
    }

    /**
     * 卖超（卖出 > 累计买入）：多余部分忽略，只对已买入部分计盈亏。
     * 买 100@10 → 卖 250@15：仅结转 100 股，盈亏 = 100×15 − 100×10 = 500。
     */
    @Test
    fun `sell exceeding holdings only matches available shares`() {
        val r = RealizedPnlCalculator.calculate(
            listOf(
                tx(1, "BUY", 100, 10.0, "2026-01-01"),
                tx(2, "SELL", 250, 15.0, "2026-02-01")
            )
        )
        assertThat(r.totalRealizedPnl).isEqualTo(500.0)
        assertThat(r.trades[0].matchedShares).isEqualTo(100)
        assertThat(r.totalCostBasis).isEqualTo(1000.0)
        assertThat(r.totalProceeds).isEqualTo(1500.0)
    }

    /** 全平后再次卖出（无对应买入）→ 卖超忽略，盈亏不变。 */
    @Test
    fun `sell after full close is ignored`() {
        val r = RealizedPnlCalculator.calculate(
            listOf(
                tx(1, "BUY", 100, 10.0, "2026-01-01"),
                tx(2, "SELL", 100, 15.0, "2026-02-01"),
                tx(3, "SELL", 50, 20.0, "2026-03-01") // 无任何剩余买入批次
            )
        )
        // 只有第一笔卖出结转，第二笔忽略
        assertThat(r.trades).hasSize(1)
        assertThat(r.totalRealizedPnl).isEqualTo(500.0)
    }

    /** 卖出早于买入（数据异常/做空场景）：无批次可结转，盈亏为 0。 */
    @Test
    fun `sell before any buy yields zero pnl`() {
        val r = RealizedPnlCalculator.calculate(
            listOf(
                tx(1, "SELL", 100, 15.0, "2026-01-01"),
                tx(2, "BUY", 100, 10.0, "2026-02-01")
            )
        )
        // FIFO：卖出时无买入批次 → 不结转；后续买入不影响已发生的卖出。
        assertThat(r.totalRealizedPnl).isEqualTo(0.0)
        assertThat(r.trades).isEmpty()
    }

    /**
     * 顺序无关性：交易列表顺序不影响结果（calculate 内部会按 date/createdAt 稳定排序）。
     * 这保证从不同 DAO 查询路径（已排序/未排序）传入都能得到一致的 FIFO 结果。
     */
    @Test
    fun `order independent via internal sort`() {
        val chronological = listOf(
            tx(1, "BUY", 100, 10.0, "2026-01-01"),
            tx(2, "BUY", 100, 12.0, "2026-02-01"),
            tx(3, "SELL", 150, 14.0, "2026-03-01")
        )
        val shuffled = listOf(
            chronological[2],
            chronological[0],
            chronological[1]
        )
        val a = RealizedPnlCalculator.calculate(chronological)
        val b = RealizedPnlCalculator.calculate(shuffled)
        assertThat(b.totalRealizedPnl).isEqualTo(a.totalRealizedPnl)
        assertThat(b.totalRealizedPnl).isEqualTo(500.0)
    }

    /**
     * 同日交易按 createdAt 排序结转。
     * 同日买 100@10(createdAt=1) + 买 100@12(createdAt=2)，同日卖 100@15。
     * FIFO 取 createdAt=1 的批次 → 盈亏 = 1500 − 1000 = 500。
     */
    @Test
    fun `same day ties broken by createdAt`() {
        val r = RealizedPnlCalculator.calculate(
            listOf(
                tx(1, "BUY", 100, 10.0, "2026-01-01", createdAt = 1),
                tx(2, "BUY", 100, 12.0, "2026-01-01", createdAt = 2),
                tx(3, "SELL", 100, 15.0, "2026-01-01", createdAt = 3)
            )
        )
        // 同日取先建的批次（@10），盈亏 = 100×15 − 100×10 = 500
        assertThat(r.totalRealizedPnl).isEqualTo(500.0)
        assertThat(r.totalCostBasis).isEqualTo(1000.0)
    }

    /** BUY/SELL 之外的事务类型（如未来 DIVIDEND）不影响已实现盈亏。 */
    @Test
    fun `unknown transaction types are ignored`() {
        val r = RealizedPnlCalculator.calculate(
            listOf(
                tx(1, "BUY", 100, 10.0, "2026-01-01"),
                tx(2, "DIVIDEND", 100, 0.5, "2026-02-01")
            )
        )
        assertThat(r.totalRealizedPnl).isEqualTo(0.0)
        assertThat(r.trades).isEmpty()
    }

    /** 零股卖出（shares=0）不应产生盈亏记录。 */
    @Test
    fun `zero share sell is skipped`() {
        val r = RealizedPnlCalculator.calculate(
            listOf(
                tx(1, "BUY", 100, 10.0, "2026-01-01"),
                tx(2, "SELL", 0, 15.0, "2026-02-01")
            )
        )
        assertThat(r.trades).isEmpty()
        assertThat(r.totalRealizedPnl).isEqualTo(0.0)
    }
}
