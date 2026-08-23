package com.stock.dividend.data.notification

import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.local.entity.STRATEGY_TYPE_MA_DCA
import com.stock.dividend.data.local.entity.StrategyPlanEntity
import com.stock.dividend.data.repository.MaDcaEvaluation
import com.stock.dividend.data.repository.MaDcaSignal
import org.junit.Test

/**
 * [StrategyNotifyEvaluator] 单测：策略卖出阈值提醒的边沿触发语义——
 * 每档只提醒一次（HALF/ALL 去重）、可升级（HALF→ALL）、偏离回落清空状态可重新提醒。
 */
class StrategyNotifyEvaluatorTest {

    private fun plan(
        id: String = "p1",
        code: String = "510880",
        lastTier: String? = null
    ) = StrategyPlanEntity(
        id = id,
        stockCode = code,
        stockName = "红利ETF",
        strategyType = STRATEGY_TYPE_MA_DCA,
        lastNotifiedSellTier = lastTier
    )

    /** dev 为偏离度（%），按语义构造对应信号档。 */
    private fun eval(signal: MaDcaSignal, dev: Double) = MaDcaEvaluation(
        maValue = 10.0,
        deviationPercent = dev,
        signal = signal,
        sellHalfTriggerPrice = 10.75,
        sellAllTriggerPrice = 11.5
    )

    @Test
    fun `首次到卖半档发出HALF信号并回写状态`() {
        val r = StrategyNotifyEvaluator.evaluate(
            plans = listOf(plan()),
            evaluations = mapOf("p1" to eval(MaDcaSignal.SELL_HALF, 7.5)),
            holdingShares = mapOf("510880" to 500)
        )
        assertThat(r.signals).hasSize(1)
        val s = r.signals.first()
        assertThat(s.tier).isEqualTo(STRATEGY_SELL_TIER_HALF)
        assertThat(s.sellShares).isEqualTo(200)
        assertThat(s.triggerPrice).isWithin(1e-9).of(10.75)
        assertThat(s.thresholdPercent).isWithin(1e-9).of(7.5)
        assertThat(r.tierUpdates).containsEntry("p1", STRATEGY_SELL_TIER_HALF)
        assertThat(r.clearedPlanIds).isEmpty()
    }

    @Test
    fun `已提醒HALF仍在HALF档不再提醒`() {
        val r = StrategyNotifyEvaluator.evaluate(
            plans = listOf(plan(lastTier = STRATEGY_SELL_TIER_HALF)),
            evaluations = mapOf("p1" to eval(MaDcaSignal.SELL_HALF, 8.0)),
            holdingShares = mapOf("510880" to 500)
        )
        assertThat(r.signals).isEmpty()
        assertThat(r.tierUpdates).isEmpty()
        assertThat(r.clearedPlanIds).isEmpty()
    }

    @Test
    fun `HALF档可升级到ALL再次提醒`() {
        val r = StrategyNotifyEvaluator.evaluate(
            plans = listOf(plan(lastTier = STRATEGY_SELL_TIER_HALF)),
            evaluations = mapOf("p1" to eval(MaDcaSignal.SELL_ALL, 15.0)),
            holdingShares = mapOf("510880" to 500)
        )
        assertThat(r.signals).hasSize(1)
        assertThat(r.signals.first().tier).isEqualTo(STRATEGY_SELL_TIER_ALL)
        assertThat(r.signals.first().sellShares).isEqualTo(500)
        assertThat(r.tierUpdates).containsEntry("p1", STRATEGY_SELL_TIER_ALL)
    }

    @Test
    fun `已提醒ALL仍在ALL档不再提醒`() {
        val r = StrategyNotifyEvaluator.evaluate(
            plans = listOf(plan(lastTier = STRATEGY_SELL_TIER_ALL)),
            evaluations = mapOf("p1" to eval(MaDcaSignal.SELL_ALL, 16.0)),
            holdingShares = mapOf("510880" to 500)
        )
        assertThat(r.signals).isEmpty()
        assertThat(r.tierUpdates).isEmpty()
        assertThat(r.clearedPlanIds).isEmpty()
    }

    @Test
    fun `偏离回落到卖半阈值以下清空状态`() {
        val r = StrategyNotifyEvaluator.evaluate(
            plans = listOf(plan(lastTier = STRATEGY_SELL_TIER_ALL)),
            evaluations = mapOf("p1" to eval(MaDcaSignal.HOLD, 3.0)),
            holdingShares = emptyMap()
        )
        assertThat(r.signals).isEmpty()
        assertThat(r.tierUpdates).isEmpty()
        assertThat(r.clearedPlanIds).containsExactly("p1")
    }

    @Test
    fun `清空后再次到HALF档可重新提醒`() {
        val r = StrategyNotifyEvaluator.evaluate(
            plans = listOf(plan(lastTier = null)),
            evaluations = mapOf("p1" to eval(MaDcaSignal.SELL_HALF, 7.5)),
            holdingShares = mapOf("510880" to 300)
        )
        assertThat(r.signals).hasSize(1)
        assertThat(r.signals.first().sellShares).isEqualTo(100)
    }

    @Test
    fun `数据不足的计划跳过且不动状态`() {
        val r = StrategyNotifyEvaluator.evaluate(
            plans = listOf(plan(lastTier = STRATEGY_SELL_TIER_HALF)),
            evaluations = emptyMap(),
            holdingShares = emptyMap()
        )
        assertThat(r.signals).isEmpty()
        assertThat(r.tierUpdates).isEmpty()
        assertThat(r.clearedPlanIds).isEmpty()
    }

    @Test
    fun `无持仓时卖出信号股数为零仍提醒`() {
        val r = StrategyNotifyEvaluator.evaluate(
            plans = listOf(plan()),
            evaluations = mapOf("p1" to eval(MaDcaSignal.SELL_ALL, 15.0)),
            holdingShares = emptyMap()
        )
        assertThat(r.signals).hasSize(1)
        assertThat(r.signals.first().sellShares).isEqualTo(0)
    }

    @Test
    fun `多计划互不影响`() {
        val r = StrategyNotifyEvaluator.evaluate(
            plans = listOf(
                plan(id = "a", code = "510880", lastTier = null),
                plan(id = "b", code = "510880", lastTier = STRATEGY_SELL_TIER_ALL)
            ),
            evaluations = mapOf(
                "a" to eval(MaDcaSignal.SELL_HALF, 7.5),
                "b" to eval(MaDcaSignal.SELL_ALL, 15.0)
            ),
            holdingShares = mapOf("510880" to 600)
        )
        assertThat(r.signals).hasSize(1)
        assertThat(r.signals.first().planId).isEqualTo("a")
        assertThat(r.tierUpdates).containsEntry("a", STRATEGY_SELL_TIER_HALF)
        assertThat(r.clearedPlanIds).isEmpty()
    }
}
