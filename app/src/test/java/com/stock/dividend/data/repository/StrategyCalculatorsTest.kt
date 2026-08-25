package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 七个新策略计算器（统一评估模型 [StrategyEvaluation]）+ 调度器 [StrategyEvaluator] 单测。
 * 每个计算器验证：阈值边界（恰达计为触发）、整手折算、数据不足返回 null/降级语义。
 */
class StrategyCalculatorsTest {

    private fun lot(n: Int) = MaDcaStrategyCalculator.LOT_SIZE * n

    // ── 目标止盈 ──

    @Test
    fun `止盈 涨幅达卖半阈值卖一半`() {
        val e = TakeProfitStrategyCalculator.evaluate(
            price = 11.5, avgCost = 10.0, holdingShares = 500,
            params = StrategyParams.TakeProfit(halfGainPercent = 15.0, allGainPercent = 25.0)
        )!!
        assertThat(e.action).isEqualTo(StrategyAction.SELL_HALF)
        assertThat(e.sellShares).isEqualTo(lot(2))
        assertThat(e.notifyTier).isEqualTo("HALF")
    }

    @Test
    fun `止盈 涨幅达清仓阈值全卖 无持仓为持有态`() {
        val all = TakeProfitStrategyCalculator.evaluate(
            price = 12.5, avgCost = 10.0, holdingShares = 700,
            params = StrategyParams.TakeProfit()
        )!!
        assertThat(all.action).isEqualTo(StrategyAction.SELL_ALL)
        assertThat(all.sellShares).isEqualTo(700)
        assertThat(all.notifyTier).isEqualTo("ALL")

        val noHolding = TakeProfitStrategyCalculator.evaluate(
            price = 12.5, avgCost = 10.0, holdingShares = 0,
            params = StrategyParams.TakeProfit()
        )!!
        assertThat(noHolding.action).isEqualTo(StrategyAction.HOLD)
    }

    @Test
    fun `止盈 涨幅不足为持有 无成本为持有`() {
        val hold = TakeProfitStrategyCalculator.evaluate(
            price = 11.0, avgCost = 10.0, holdingShares = 500, params = StrategyParams.TakeProfit()
        )!!
        assertThat(hold.action).isEqualTo(StrategyAction.HOLD)
        val noCost = TakeProfitStrategyCalculator.evaluate(
            price = 11.0, avgCost = 0.0, holdingShares = 500, params = StrategyParams.TakeProfit()
        )!!
        assertThat(noCost.action).isEqualTo(StrategyAction.HOLD)
    }

    // ── 股息率带 ──

    @Test
    fun `股息率带 达加仓线买入 跌破卖出线清仓 带内持有`() {
        val params = StrategyParams.YieldBand(buyYieldPercent = 6.0, addYieldPercent = 6.5, sellYieldPercent = 4.0)
        // 价 10.0 × DPS 0.6 → 息恰 6.0%（== 计为达线）
        val buy = YieldBandStrategyCalculator.evaluate(10.0, 0.6, 300, 1000.0, params)!!
        assertThat(buy.action).isEqualTo(StrategyAction.BUY)
        assertThat(buy.buyShares).isEqualTo(lot(1))   // 1000 ÷ 10 = 100 股
        // 价 10.0 × DPS 0.65 → 息恰 6.5% 达加仓线
        val add = YieldBandStrategyCalculator.evaluate(10.0, 0.65, 300, 1000.0, params)!!
        assertThat(add.action).isEqualTo(StrategyAction.BUY)
        assertThat(add.headline).contains("加仓")
        // 价 10.0 × DPS 0.4 → 息恰 4.0% 跌破卖出线：清仓信号带股数（一键记账按钮依赖 sellShares>0）
        val sell = YieldBandStrategyCalculator.evaluate(10.0, 0.4, 300, 1000.0, params)!!
        assertThat(sell.action).isEqualTo(StrategyAction.SELL_ALL)
        assertThat(sell.sellShares).isEqualTo(300)
        assertThat(sell.notifyTier).isEqualTo("ALL")
        // 无持仓跌破卖出线 → 仅跟踪（不推送 0 股卖出提醒）
        val sellNoHolding = YieldBandStrategyCalculator.evaluate(10.0, 0.4, 0, 1000.0, params)!!
        assertThat(sellNoHolding.action).isEqualTo(StrategyAction.HOLD)
        assertThat(sellNoHolding.notifyTier).isNull()
        // 价 10.0 × DPS 0.5 → 息 5% 带内
        val hold = YieldBandStrategyCalculator.evaluate(10.0, 0.5, 300, 1000.0, params)!!
        assertThat(hold.action).isEqualTo(StrategyAction.HOLD)
    }

    @Test
    fun `股息率带 无分红数据返回 null`() {
        assertThat(
            YieldBandStrategyCalculator.evaluate(8.0, null, 0, 1000.0, StrategyParams.YieldBand())
        ).isNull()
    }

    // ── 双均线 ──

    private val dualMaCloses: List<Double> = buildList {
        // 慢线周期 10、快线 5：先跌后涨构造金叉；末段持续上行使快线在慢线上方
        repeat(12) { add(10.0) }
        repeat(8) { add(8.0) }
        repeat(12) { add(9.5 + it * 0.3) }
    }

    @Test
    fun `双均线 快线在慢线上方为多头 下方为空头`() {
        val params = StrategyParams.DualMa(fastPeriod = 5, slowPeriod = 10)
        val bull = DualMaStrategyCalculator.evaluate(dualMaCloses, 0, params)!!
        assertThat(bull.action).isEqualTo(StrategyAction.BUY)
        assertThat(bull.headline).contains("多头")

        val bearCloses = dualMaCloses.dropLast(6) + listOf(8.0, 8.0, 8.0, 8.0, 8.0, 8.0)
        val bear = DualMaStrategyCalculator.evaluate(bearCloses, 100, params)!!
        assertThat(bear.action).isEqualTo(StrategyAction.SELL_ALL)
        assertThat(bear.sellShares).isEqualTo(100)
        assertThat(bear.notifyTier).isEqualTo("ALL")
        // 空头但无持仓 → 回避（持有态，不推送）
        val bearNoHolding = DualMaStrategyCalculator.evaluate(bearCloses, 0, params)!!
        assertThat(bearNoHolding.action).isEqualTo(StrategyAction.HOLD)
        assertThat(bearNoHolding.notifyTier).isNull()
    }

    @Test
    fun `双均线 收盘价不足返回 null`() {
        assertThat(
            DualMaStrategyCalculator.evaluate(
                List(10) { 10.0 }, 0, StrategyParams.DualMa(fastPeriod = 5, slowPeriod = 20)
            )
        ).isNull()
    }

    // ── 均线突破 ──

    @Test
    fun `均线突破 站上均线买入 跌破均线清仓`() {
        val params = StrategyParams.MaBreakout(maPeriod = 5)
        val closes = listOf(4.0, 4.0, 4.0, 4.0, 4.0)   // MA5 = 4.0
        // 现价 4.2 > MA → 突破买入（买入方向只展示不推送）
        val bull = MaBreakoutStrategyCalculator.evaluate(closes, 4.2, 300, params)!!
        assertThat(bull.action).isEqualTo(StrategyAction.BUY)
        assertThat(bull.notifyTier).isNull()
        // 恰达均线（价 == MA）计为站上（恰达阈值计为触发）
        val exact = MaBreakoutStrategyCalculator.evaluate(closes, 4.0, 300, params)!!
        assertThat(exact.action).isEqualTo(StrategyAction.BUY)
        // 跌破均线且有持仓 → 清仓信号带股数（一键记账按钮依赖 sellShares>0）+ ALL 档推送
        val bear = MaBreakoutStrategyCalculator.evaluate(closes, 3.8, 300, params)!!
        assertThat(bear.action).isEqualTo(StrategyAction.SELL_ALL)
        assertThat(bear.sellShares).isEqualTo(300)
        assertThat(bear.notifyTier).isEqualTo("ALL")
        // 跌破但无持仓 → 观望（不推 0 股卖出提醒，与 DualMa/YieldBand 守卫一致）
        val bearNoHolding = MaBreakoutStrategyCalculator.evaluate(closes, 3.8, 0, params)!!
        assertThat(bearNoHolding.action).isEqualTo(StrategyAction.HOLD)
        assertThat(bearNoHolding.notifyTier).isNull()
    }

    @Test
    fun `均线突破 收盘价不足或现价非法返回 null`() {
        assertThat(
            MaBreakoutStrategyCalculator.evaluate(
                List(4) { 4.0 }, 4.0, 0, StrategyParams.MaBreakout(maPeriod = 5)
            )
        ).isNull()
        assertThat(
            MaBreakoutStrategyCalculator.evaluate(
                List(5) { 4.0 }, 0.0, 0, StrategyParams.MaBreakout(maPeriod = 5)
            )
        ).isNull()
    }

    // ── 均线偏离回归 ──

    private val flatDeviationCloses = List(250) { 10.0 }

    @Test
    fun `偏离回归 低于均线分档买入 回归均线卖出`() {
        val params = StrategyParams.MaDeviation(maPeriod = 250, stepPercent = 5.0, buyLevels = 3)
        // 恰达边界：价 9.5 → 偏离恰 -5% → 第 1 档（恰达阈值计触发；2026-08-24 修复：此前报第 2 档）
        val exact = MaDeviationStrategyCalculator.evaluate(flatDeviationCloses, 9.5, 300, 1000.0, params)!!
        assertThat(exact.action).isEqualTo(StrategyAction.BUY)
        assertThat(exact.headline).contains("第 1 档")
        // 价 9.4 → 偏离 -6% → (-6/5)=1.2 floor → 第 1 档（此前 toInt()+1 误报第 2 档）
        val buy = MaDeviationStrategyCalculator.evaluate(flatDeviationCloses, 9.4, 300, 1000.0, params)!!
        assertThat(buy.action).isEqualTo(StrategyAction.BUY)
        assertThat(buy.headline).contains("第 1 档")
        assertThat(buy.buyShares).isEqualTo(lot(1))   // 1000 ÷ 9.4 ≈ 106 → 100
        assertThat(buy.notifyTier).isNull()           // 买入方向只展示不推送
        // 价 9.0 → 偏离 -10% → 恰达第 2 档
        val second = MaDeviationStrategyCalculator.evaluate(flatDeviationCloses, 9.0, 300, 1000.0, params)!!
        assertThat(second.headline).contains("第 2 档")
        // 价 8.5 → 偏离 -15% → 恰达最深第 3 档（未超出，仍是第 3 档低吸）
        val deepestExact = MaDeviationStrategyCalculator.evaluate(flatDeviationCloses, 8.5, 300, 1000.0, params)!!
        assertThat(deepestExact.headline).contains("第 3 档")
        assertThat(deepestExact.headline).doesNotContain("最深")

        // 价 10.0 = 回归均线 → 卖出低吸部分（一半整手）
        val sell = MaDeviationStrategyCalculator.evaluate(flatDeviationCloses, 10.0, 300, 1000.0, params)!!
        assertThat(sell.action).isEqualTo(StrategyAction.SELL_HALF)
        assertThat(sell.sellShares).isEqualTo(lot(1))
        assertThat(sell.notifyTier).isEqualTo("HALF")
        // 无持仓回归均线 → 仅跟踪（2026-08-24 修复：不再推「卖出 0 股」）
        val sellNoHolding = MaDeviationStrategyCalculator.evaluate(flatDeviationCloses, 10.0, 0, 1000.0, params)!!
        assertThat(sellNoHolding.action).isEqualTo(StrategyAction.HOLD)
        assertThat(sellNoHolding.notifyTier).isNull()

        // 价 9.8 → 偏离 -2% 未到第一档 → 持有
        val hold = MaDeviationStrategyCalculator.evaluate(flatDeviationCloses, 9.8, 300, 1000.0, params)!!
        assertThat(hold.action).isEqualTo(StrategyAction.HOLD)
    }

    @Test
    fun `偏离回归 超过最深档钳制 数据不足 null`() {
        val params = StrategyParams.MaDeviation(maPeriod = 250, stepPercent = 5.0, buyLevels = 3)
        val deep = MaDeviationStrategyCalculator.evaluate(flatDeviationCloses, 8.0, 300, 1000.0, params)!!
        assertThat(deep.action).isEqualTo(StrategyAction.BUY)
        assertThat(deep.headline).contains("最深")

        assertThat(
            MaDeviationStrategyCalculator.evaluate(List(100) { 10.0 }, 9.0, 300, 1000.0, params)
        ).isNull()
    }

    // ── 价值平均法 ──

    @Test
    fun `价值平均 低于目标补足 超出目标卖出超额 贴合持有`() {
        val params = StrategyParams.ValueAveraging(perPeriodAmount = 1000.0)
        // 第 3 期目标 3000；持仓 100 股 × 20 = 2000 → 缺口 1000 → 买 50 股 → 整手 0？ 1000/20=50 → 0 手
        // 改用持仓 100×20=2000 目标 4000：缺口 2000 ÷ 20 = 100 股 → 1 手
        val buy = ValueAveragingStrategyCalculator.evaluate(
            price = 20.0, holdingShares = 100, monthsSinceStart = 3, params = params
        )!!
        // monthsSinceStart=3 → 目标 = 1000 × (3+1) = 4000；市值 2000；缺口 2000 → 100 股
        assertThat(buy.action).isEqualTo(StrategyAction.BUY)
        assertThat(buy.buyShares).isEqualTo(lot(1))

        // 持仓 500 × 20 = 10000 目标 4000 → 超额 6000 ÷ 20 = 300 股 → 3 手
        val sell = ValueAveragingStrategyCalculator.evaluate(
            price = 20.0, holdingShares = 500, monthsSinceStart = 3, params = params
        )!!
        assertThat(sell.action).isEqualTo(StrategyAction.SELL_HALF)
        assertThat(sell.sellShares).isEqualTo(lot(3))
        assertThat(sell.notifyTier).isNull()   // 定投节奏类操作不推送

        // 持仓 200 × 20 = 4000 = 目标 → 持有
        val hold = ValueAveragingStrategyCalculator.evaluate(
            price = 20.0, holdingShares = 200, monthsSinceStart = 3, params = params
        )!!
        assertThat(hold.action).isEqualTo(StrategyAction.HOLD)
    }

    @Test
    fun `价值平均 超额卖出不超过持仓`() {
        val e = ValueAveragingStrategyCalculator.evaluate(
            price = 50.0, holdingShares = 100, monthsSinceStart = 0,
            params = StrategyParams.ValueAveraging(perPeriodAmount = 1000.0)
        )!!
        // 目标 1000；市值 5000 → 超额 4000 ÷ 50 = 80 股 → 0 手（钳制不足一手）
        assertThat(e.action).isEqualTo(StrategyAction.HOLD)
    }

    // ── 估值带 ──

    @Test
    fun `估值带 PE 低买高卖 PB 指标切换 数据缺失 null`() {
        val params = StrategyParams.ValuationBand(metric = "PE", lowThreshold = 8.0, highThreshold = 15.0)
        assertThat(
            ValuationBandStrategyCalculator.evaluate(pe = 7.9, pb = null, holdingShares = 0, params = params)!!.action
        ).isEqualTo(StrategyAction.BUY)
        val sell = ValuationBandStrategyCalculator.evaluate(pe = 15.0, pb = null, holdingShares = 600, params = params)!!
        assertThat(sell.action).isEqualTo(StrategyAction.SELL_ALL)
        assertThat(sell.notifyTier).isEqualTo("ALL")
        assertThat(
            ValuationBandStrategyCalculator.evaluate(pe = 10.0, pb = null, holdingShares = 0, params = params)!!.action
        ).isEqualTo(StrategyAction.HOLD)
        // PB 指标但 PB 数据缺失 → null（ETF 无 PB 常见）
        assertThat(
            ValuationBandStrategyCalculator.evaluate(pe = 10.0, pb = null, holdingShares = 0, params = params.copy(metric = "PB"))
        ).isNull()
    }

    // ── 分红再投 ──

    @Test
    fun `分红再投 到账金额按现价折股 无事件为持有态`() {
        val event = StrategyDividendEvent(exDate = "2026-09-10", daysAway = 5, cashPerShare = 0.25)
        val e = DividendReinvestStrategyCalculator.evaluate(event, price = 2.5, holdingShares = 4000)!!
        assertThat(e.action).isEqualTo(StrategyAction.BUY)
        assertThat(e.buyAmount).isWithin(1e-9).of(1000.0)   // 0.25 × 4000
        assertThat(e.buyShares).isEqualTo(lot(4))           // 1000 ÷ 2.5 = 400
        assertThat(e.notifyTier).isNull()                   // 只展示不推送

        val none = DividendReinvestStrategyCalculator.evaluate(null, price = 2.5, holdingShares = 4000)!!
        assertThat(none.action).isEqualTo(StrategyAction.HOLD)
        assertThat(DividendReinvestStrategyCalculator.evaluate(event, price = null, holdingShares = 4000)).isNull()
    }

    // ── 调度器 ──

    @Test
    fun `调度器 MA_DCA 映射为统一评估`() {
        val plan = com.stock.dividend.data.local.entity.StrategyPlanEntity(
            id = "s1", stockCode = "sh.510880", stockName = "红利ETF", dcaAmount = 1000.0
        )
        val e = StrategyEvaluator.evaluate(plan, StrategyInput(currentPrice = 9.5, closes = flatDeviationCloses))!!
        assertThat(e.action).isEqualTo(StrategyAction.BUY)
        assertThat(e.headline).contains("定投")
        assertThat(e.buyShares).isEqualTo(lot(1))   // 1000 ÷ 9.5 ≈ 105 → 100

        val sell = StrategyEvaluator.evaluate(plan, StrategyInput(currentPrice = 11.5, closes = flatDeviationCloses))!!
        assertThat(sell.action).isEqualTo(StrategyAction.SELL_ALL)
        assertThat(sell.notifyTier).isEqualTo("ALL")
    }

    @Test
    fun `调度器 按类型分发 params 策略`() {
        val plan = com.stock.dividend.data.local.entity.StrategyPlanEntity(
            id = "s2", stockCode = "sh.600036", stockName = "招商银行",
            strategyType = com.stock.dividend.data.local.entity.STRATEGY_TYPE_TAKE_PROFIT,
            params = StrategyParams.encode(StrategyParams.TakeProfit(halfGainPercent = 15.0, allGainPercent = 25.0))
        )
        val e = StrategyEvaluator.evaluate(
            plan, StrategyInput(currentPrice = 12.5, avgCostPerShare = 10.0, holdingShares = 500)
        )!!
        assertThat(e.action).isEqualTo(StrategyAction.SELL_ALL)

        // params 为空 → 回退默认参数（15/25）
        val defaultPlan = plan.copy(params = null)
        val e2 = StrategyEvaluator.evaluate(
            defaultPlan, StrategyInput(currentPrice = 12.5, avgCostPerShare = 10.0, holdingShares = 500)
        )!!
        assertThat(e2.action).isEqualTo(StrategyAction.SELL_ALL)
    }

    @Test
    fun `调度器 各类型所需收盘价根数`() {
        fun planOf(type: String, params: String? = null) =
            com.stock.dividend.data.local.entity.StrategyPlanEntity(
                id = "x", stockCode = "c", stockName = "n", strategyType = type, params = params
            )
        assertThat(
            StrategyEvaluator.requiredCloses(planOf(com.stock.dividend.data.local.entity.STRATEGY_TYPE_MA_DCA))
        ).isEqualTo(250)
        assertThat(
            StrategyEvaluator.requiredCloses(
                planOf(
                    com.stock.dividend.data.local.entity.STRATEGY_TYPE_DUAL_MA,
                    StrategyParams.encode(StrategyParams.DualMa(fastPeriod = 20, slowPeriod = 60))
                )
            )
        ).isEqualTo(62)
        assertThat(
            StrategyEvaluator.requiredCloses(
                planOf(
                    com.stock.dividend.data.local.entity.STRATEGY_TYPE_MA_BREAKOUT,
                    StrategyParams.encode(StrategyParams.MaBreakout(maPeriod = 5))
                )
            )
        ).isEqualTo(5)
        assertThat(
            StrategyEvaluator.requiredCloses(planOf(com.stock.dividend.data.local.entity.STRATEGY_TYPE_TAKE_PROFIT))
        ).isEqualTo(0)
    }
}
