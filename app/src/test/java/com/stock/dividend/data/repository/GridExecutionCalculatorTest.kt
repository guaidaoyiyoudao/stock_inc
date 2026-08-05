package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.local.entity.TransactionEntity
import org.junit.Test

/**
 * [GridExecutionCalculator]（网格执行跟踪）单测。
 *
 * 口径与 [GridCalculator.markTriggeredLevels] 一致：命中 = BUY 成交价落在已触发档位的
 * 触发区间（档位价 ± 半步长）。汇总已投入/剩余/股数/浮盈。
 */
class GridExecutionCalculatorTest {

    private fun tx(type: String, price: Double, shares: Int) = TransactionEntity(
        id = 0L, stockCode = "sh.600000", type = type, shares = shares, price = price, date = "2026-01-01"
    )

    /** 基础场景：4 档（8/8.67/9.33/10），总资金 100000，命中两笔买入。 */
    @Test
    fun `tracks invested and remaining from hit transactions`() {
        // 标记 9.33 与 8.67 档为已触发（模拟实际买入 9.4@300、8.7@200）
        val base = GridCalculator.generate(10.0, 8.0, 12.0, 4, 100000.0)
        val marked = GridCalculator.markTriggeredLevels(
            base,
            listOf(tx("BUY", 9.4, 300), tx("BUY", 8.7, 200))
        )
        val exec = GridExecutionCalculator.calculate(marked, 100000.0, marked.let {
            // 用同一批交易（已标记触发）计算执行
            listOf(tx("BUY", 9.4, 300), tx("BUY", 8.7, 200))
        }, currentPrice = 9.0)

        // 已投入 = 9.4×300 + 8.7×200 = 2820 + 1740 = 4560
        assertThat(exec.investedAmount).isEqualTo(4560.0)
        // 剩余 = 100000 − 4560 = 95440
        assertThat(exec.remainingCapital).isEqualTo(95440.0)
        // 已买 500 股
        assertThat(exec.boughtShares).isEqualTo(500)
        // 触发 2/4 档
        assertThat(exec.triggeredCount).isEqualTo(2)
        assertThat(exec.totalLevels).isEqualTo(4)
        assertThat(exec.progressPercent).isEqualTo(50)
        // 均价 = 4560/500 = 9.12
        assertThat(exec.avgBuyPrice).isEqualTo(9.12)
        // 现价 9 → 市值 4500；浮盈 4500 − 4560 = −60
        assertThat(exec.currentValue).isEqualTo(4500.0)
        assertThat(exec.unrealizedPnl).isEqualTo(-60.0)
    }

    /** 无命中交易 → 全空（已投入 0、剩余=总资金）。 */
    @Test
    fun `no hits yields empty execution`() {
        val base = GridCalculator.generate(10.0, 8.0, 12.0, 4, 100000.0)
        val exec = GridExecutionCalculator.calculate(base, 100000.0, emptyList(), currentPrice = 9.0)
        assertThat(exec.investedAmount).isEqualTo(0.0)
        assertThat(exec.remainingCapital).isEqualTo(100000.0)
        assertThat(exec.boughtShares).isEqualTo(0)
        assertThat(exec.triggeredCount).isEqualTo(0)
        assertThat(exec.avgBuyPrice).isNull()
        assertThat(exec.currentValue).isNull()
        assertThat(exec.unrealizedPnl).isNull()
    }

    /** SELL 交易不参与执行汇总（纯买入模型）。 */
    @Test
    fun `sell transactions ignored`() {
        val base = GridCalculator.generate(10.0, 8.0, 12.0, 4, 100000.0)
        val marked = GridCalculator.markTriggeredLevels(base, listOf(tx("BUY", 9.4, 300)))
        // 即便混入一笔价格落在档位区间的 SELL，也不计入
        val exec = GridExecutionCalculator.calculate(
            marked, 100000.0,
            listOf(tx("BUY", 9.4, 300), tx("SELL", 9.4, 500)),
            currentPrice = null
        )
        // 只算 BUY@9.4×300 = 2820
        assertThat(exec.investedAmount).isEqualTo(2820.0)
        assertThat(exec.boughtShares).isEqualTo(300)
    }

    /** 无现价 → 浮盈为 null（市值/盈亏不可算），但已投入/股数仍可算。 */
    @Test
    fun `no current price yields null pnl but valid invested`() {
        val base = GridCalculator.generate(10.0, 8.0, 12.0, 4, 100000.0)
        val marked = GridCalculator.markTriggeredLevels(base, listOf(tx("BUY", 9.4, 300)))
        val exec = GridExecutionCalculator.calculate(marked, 100000.0, listOf(tx("BUY", 9.4, 300)), currentPrice = null)
        assertThat(exec.investedAmount).isEqualTo(2820.0)
        assertThat(exec.boughtShares).isEqualTo(300)
        assertThat(exec.avgBuyPrice).isEqualTo(9.4)
        assertThat(exec.currentValue).isNull()
        assertThat(exec.unrealizedPnl).isNull()
        assertThat(exec.unrealizedPnlRate).isNull()
    }

    /** 浮盈率 = 浮盈 / 已投入 × 100。 */
    @Test
    fun `pnl rate computed from invested`() {
        val base = GridCalculator.generate(10.0, 8.0, 12.0, 4, 100000.0)
        val marked = GridCalculator.markTriggeredLevels(base, listOf(tx("BUY", 9.4, 300)))
        val exec = GridExecutionCalculator.calculate(
            marked, 100000.0, listOf(tx("BUY", 9.4, 300)), currentPrice = 10.0
        )
        // 投入 2820，市值 3000，盈亏 +180，盈率 = 180/2820×100 ≈ 6.38
        assertThat(exec.unrealizedPnl).isEqualTo(180.0)
        assertThat(exec.unrealizedPnlRate).isNotNull()
        assertThat(exec.unrealizedPnlRate!!).isWithin(0.01).of(180.0 / 2820.0 * 100.0)
    }

    /** progressPercent 整数百分比。 */
    @Test
    fun `progress percent is integer`() {
        val base = GridCalculator.generate(10.0, 8.0, 12.0, 4, 100000.0)
        val marked = GridCalculator.markTriggeredLevels(base, listOf(tx("BUY", 9.4, 300)))
        val exec = GridExecutionCalculator.calculate(marked, 100000.0, listOf(tx("BUY", 9.4, 300)), currentPrice = 9.0)
        assertThat(exec.progressPercent).isEqualTo(25)  // 1/4
    }
}
