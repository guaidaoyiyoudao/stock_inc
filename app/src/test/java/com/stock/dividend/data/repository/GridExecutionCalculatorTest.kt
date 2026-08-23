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

    /**
     * 执行偏差：金额加权平均 + 最差值。
     * BUY@9.4×300（命中 9.33 档，偏差 (9.4−9.33)/9.33≈+0.75%）与
     * BUY@8.7×200（命中 8.67 档，偏差 (8.7−8.67)/8.67≈+0.35%）：
     * 金额权重 2820 vs 1740 → 加权平均 ≈ (0.75×2820 + 0.35×1740)/4560 ≈ +0.60%。
     */
    @Test
    fun `deviation stats weighted by amount`() {
        val base = GridCalculator.generate(10.0, 8.0, 12.0, 4, 100000.0)
        val txs = listOf(tx("BUY", 9.4, 300), tx("BUY", 8.7, 200))
        val marked = GridCalculator.markTriggeredLevels(base, txs)
        val exec = GridExecutionCalculator.calculate(marked, 100000.0, txs, currentPrice = 9.0)

        val dev1 = (9.4 - 9.33) / 9.33 * 100.0
        val dev2 = (8.7 - 8.67) / 8.67 * 100.0
        val expectedAvg = (dev1 * 9.4 * 300 + dev2 * 8.7 * 200) / (9.4 * 300 + 8.7 * 200)
        assertThat(exec.avgDeviationPercent).isNotNull()
        assertThat(exec.avgDeviationPercent!!).isWithin(0.05).of(expectedAvg)
        // 最差 = 两笔中更大的正偏差（9.4 那笔）
        assertThat(exec.worstDeviationPercent).isNotNull()
        assertThat(exec.worstDeviationPercent!!).isWithin(0.01).of(maxOf(dev1, dev2))
    }

    /** 无成交 → 偏差均为 null。 */
    @Test
    fun `deviation null without hits`() {
        val base = GridCalculator.generate(10.0, 8.0, 12.0, 4, 100000.0)
        val exec = GridExecutionCalculator.calculate(base, 100000.0, emptyList(), currentPrice = 9.0)
        assertThat(exec.avgDeviationPercent).isNull()
        assertThat(exec.worstDeviationPercent).isNull()
    }

    /** 成交价低于档位价 → 偏差为负（买得更便宜）。 */
    @Test
    fun `negative deviation when bought below level`() {
        val base = GridCalculator.generate(10.0, 8.0, 12.0, 4, 100000.0)
        // 9.2 命中 9.33 档（|9.2−9.33|=0.13 ≤ 半步长 0.335），低于档位价
        val txs = listOf(tx("BUY", 9.2, 300))
        val marked = GridCalculator.markTriggeredLevels(base, txs)
        val exec = GridExecutionCalculator.calculate(marked, 100000.0, txs, currentPrice = 9.0)
        assertThat(exec.avgDeviationPercent).isNotNull()
        assertThat(exec.avgDeviationPercent!!).isLessThan(0.0)
        assertThat(exec.worstDeviationPercent!!).isLessThan(0.0)
    }

    // ── levelFills（逐档成交明细）────────────────────

    /** 两档各一笔成交 → 各自汇总（价/股数/笔数/日期）。 */
    @Test
    fun `levelFills aggregates per level`() {
        val base = GridCalculator.generate(10.0, 8.0, 12.0, 4, 100000.0)
        val txs = listOf(tx("BUY", 9.4, 300), tx("BUY", 8.7, 200))
        val marked = GridCalculator.markTriggeredLevels(base, txs)
        val fills = GridExecutionCalculator.levelFills(marked, txs)
        // 9.33 档：300 股 1 笔；8.67 档：200 股 1 笔
        assertThat(fills.keys).containsExactly(9.33, 8.67)
        assertThat(fills[9.33]!!.shares).isEqualTo(300)
        assertThat(fills[9.33]!!.fills).isEqualTo(1)
        assertThat(fills[8.67]!!.shares).isEqualTo(200)
        assertThat(fills[8.67]!!.lastDate).isEqualTo("2026-01-01")
    }

    /** 同档多笔成交 → 累计股数/笔数，最近一笔的价与日期（按日期排序取末笔）。 */
    @Test
    fun `levelFills merges multiple fills on same level`() {
        val base = GridCalculator.generate(10.0, 8.0, 12.0, 4, 100000.0)
        val txs = listOf(
            TransactionEntity(id = 1L, stockCode = "sh.600000", type = "BUY", shares = 100, price = 9.3, date = "2026-01-10"),
            TransactionEntity(id = 2L, stockCode = "sh.600000", type = "BUY", shares = 200, price = 9.35, date = "2026-02-20")
        )
        val marked = GridCalculator.markTriggeredLevels(base, txs)
        val fills = GridExecutionCalculator.levelFills(marked, txs)
        val fill = fills[9.33]!!
        assertThat(fill.shares).isEqualTo(300)
        assertThat(fill.fills).isEqualTo(2)
        assertThat(fill.price).isEqualTo(9.35)      // 最近一笔
        assertThat(fill.lastDate).isEqualTo("2026-02-20")
    }

    /** 未触发档位（无成交命中）不出现在 fills；SELL 不参与。 */
    @Test
    fun `levelFills skips untriggered levels and sells`() {
        val base = GridCalculator.generate(10.0, 8.0, 12.0, 4, 100000.0)
        val txs = listOf(tx("SELL", 9.4, 300))
        val marked = GridCalculator.markTriggeredLevels(base, txs)
        val fills = GridExecutionCalculator.levelFills(marked, txs)
        assertThat(fills).isEmpty()
    }

    // ── summarizeAmmo（弹药库汇总）──────────────────

    /** 弹药库 = 各计划合计（总资金/已投入/剩余/触发进度）；总资金显式传入（EMPTY 执行会丢资金量）。 */
    @Test
    fun `summarizeAmmo aggregates across plans`() {
        val base1 = GridCalculator.generate(10.0, 8.0, 12.0, 4, 60000.0)
        val tx1 = listOf(tx("BUY", 9.4, 300))
        val marked1 = GridCalculator.markTriggeredLevels(base1, tx1)
        val exec1 = GridExecutionCalculator.calculate(marked1, 60000.0, tx1, currentPrice = 9.0)
        val exec2 = GridExecution.EMPTY  // 第二个计划参数非法 → EMPTY（0 投入）

        val summary = GridExecutionCalculator.summarizeAmmo(
            totalCapitals = listOf(60000.0, 40000.0),
            executions = listOf(exec1, exec2)
        )
        assertThat(summary.planCount).isEqualTo(2)
        assertThat(summary.totalCapital).isEqualTo(100000.0)
        assertThat(summary.investedAmount).isEqualTo(2820.0)         // 9.4×300
        assertThat(summary.remainingCapital).isEqualTo(97180.0)
        assertThat(summary.triggeredLevels).isEqualTo(1)
        assertThat(summary.totalLevels).isEqualTo(4)
        assertThat(summary.progressPercent).isEqualTo(25)
    }

    /** 空列表 → 全零汇总，进度 0（不崩溃）。 */
    @Test
    fun `summarizeAmmo empty yields zeros`() {
        val summary = GridExecutionCalculator.summarizeAmmo(emptyList(), emptyList())
        assertThat(summary.planCount).isEqualTo(0)
        assertThat(summary.totalCapital).isEqualTo(0.0)
        assertThat(summary.progressPercent).isEqualTo(0)
    }

    // ── 波段模式：净投入（买入 − 卖出）/ 弹药回流 / 底仓不变 / 回合与波段利润 ──
    // 两档网格 8/10、DPS=0.5：默认步长 1.25pp → 8 档卖出锚 10.00、10 档 13.33；
    // 资金 100000 → 8 档 6900 股、10 档 4400 股；30% 波段 → 2000/1300，底仓 4900/3100。

    private fun txAt(date: String, type: String, price: Double, shares: Int) = TransactionEntity(
        id = 0L, stockCode = "sh.600000", type = type, shares = shares, price = price, date = date
    )

    private fun swingBase(ratio: Double = 100.0, currentPrice: Double? = null) = GridCalculator.generate(
        10.0, 8.0, 12.0, 2, 100000.0, currentPrice = currentPrice,
        swingMode = true, dps = 0.5, swingRatioPercent = ratio
    )

    /** 完整回合（无底仓口径，买 8.1 × 卖 10.0）：净投入为负（落袋利润回流弹药库），持股归零。 */
    @Test
    fun `swing round trip nets sell proceeds into ammo`() {
        val marked = GridCalculator.markTriggeredLevels(
            swingBase(),
            listOf(txAt("2026-01-01", "BUY", 8.1, 6900), txAt("2026-01-02", "SELL", 10.0, 6900))
        )
        val exec = GridExecutionCalculator.calculate(marked, 100000.0, emptyList(), currentPrice = 8.0)
        // 净投入 = 8.1×6900 − 10.0×6900 = −13110（利润已落袋）
        assertThat(exec.investedAmount).isEqualTo(-13110.0)
        // 剩余弹药 = 100000 + 13150 → 113110（卖出回款回流）
        assertThat(exec.remainingCapital).isEqualTo(113110.0)
        assertThat(exec.boughtShares).isEqualTo(0)
        assertThat(exec.avgBuyPrice).isNull()
        assertThat(exec.roundTrips).isEqualTo(1)
        assertThat(exec.swingProfit).isWithin(0.01).of((10.0 - 8.0) * 6900)
    }

    /** 底仓不变（30% 波段）：卖 2000 股回款，底仓 4900 股仍持有 → 净投入只含底仓。 */
    @Test
    fun `swing partial sell keeps base position invested`() {
        val marked = GridCalculator.markTriggeredLevels(
            swingBase(ratio = 30.0),
            listOf(txAt("2026-01-01", "BUY", 8.1, 6900), txAt("2026-01-02", "SELL", 10.0, 2000))
        )
        val exec = GridExecutionCalculator.calculate(marked, 100000.0, emptyList(), currentPrice = 8.0)
        // 净投入 = 8.1×6900 − 10.0×2000 = 55890 − 20000 = 35890（底仓 4900 股的摊薄成本）
        assertThat(exec.investedAmount).isEqualTo(35890.0)
        assertThat(exec.remainingCapital).isEqualTo(64110.0)
        assertThat(exec.boughtShares).isEqualTo(4900)
        assertThat(exec.avgBuyPrice).isWithin(0.01).of(35890.0 / 4900)
        assertThat(exec.roundTrips).isEqualTo(1)
        assertThat(exec.swingProfit).isWithin(0.01).of((10.0 - 8.0) * 2000)
    }

    /** 弹药库汇总聚合波段回合与计划口径利润。 */
    @Test
    fun `summarizeAmmo aggregates swing round trips`() {
        val marked = GridCalculator.markTriggeredLevels(
            swingBase(),
            listOf(txAt("2026-01-01", "BUY", 8.1, 6900), txAt("2026-01-02", "SELL", 10.0, 6900))
        )
        val exec = GridExecutionCalculator.calculate(marked, 100000.0, emptyList(), currentPrice = null)
        val summary = GridExecutionCalculator.summarizeAmmo(listOf(100000.0), listOf(exec))
        assertThat(summary.roundTrips).isEqualTo(1)
        assertThat(summary.swingProfit).isWithin(0.01).of((10.0 - 8.0) * 6900)
    }
}
