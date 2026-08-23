package com.stock.dividend.data.notification

import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.local.entity.STRATEGY_TYPE_MA_DCA
import com.stock.dividend.data.local.entity.STRATEGY_TYPE_TAKE_PROFIT
import com.stock.dividend.data.local.entity.StrategyPlanEntity
import com.stock.dividend.data.repository.StrategyAction
import com.stock.dividend.data.repository.StrategyEvaluation
import org.junit.Test

/**
 * [StrategyNotifyEvaluator]（v2，统一评估）单测：档位有序边沿触发——
 * 升级才提醒（null→HALF→ALL）、同档/降级静默、脱离卖出区清空状态可重新提醒。
 */
class StrategyNotifyEvaluatorTest {

    private fun plan(
        id: String = "p1",
        code: String = "510880",
        type: String = STRATEGY_TYPE_MA_DCA,
        lastTier: String? = null
    ) = StrategyPlanEntity(
        id = id,
        stockCode = code,
        stockName = "红利ETF",
        strategyType = type,
        lastNotifiedSellTier = lastTier
    )

    private fun evaluation(tier: String?, sellShares: Int = 200) = StrategyEvaluation(
        action = if (tier == STRATEGY_SELL_TIER_ALL) StrategyAction.SELL_ALL
        else if (tier == STRATEGY_SELL_TIER_HALF) StrategyAction.SELL_HALF
        else StrategyAction.HOLD,
        headline = "测试状态",
        sellShares = sellShares,
        notifyTier = tier
    )

    @Test
    fun `首次到卖半档发出HALF信号并回写状态`() {
        val r = StrategyNotifyEvaluator.evaluate(
            plans = listOf(plan()),
            evaluations = mapOf("p1" to evaluation(STRATEGY_SELL_TIER_HALF))
        )
        assertThat(r.signals).hasSize(1)
        val s = r.signals.first()
        assertThat(s.tier).isEqualTo(STRATEGY_SELL_TIER_HALF)
        assertThat(s.sellShares).isEqualTo(200)
        assertThat(s.strategyTypeName).isEqualTo("年线定投")
        assertThat(s.headline).isEqualTo("测试状态")
        assertThat(r.tierUpdates).containsEntry("p1", STRATEGY_SELL_TIER_HALF)
        assertThat(r.clearedPlanIds).isEmpty()
    }

    @Test
    fun `已提醒HALF仍在HALF档不再提醒`() {
        val r = StrategyNotifyEvaluator.evaluate(
            plans = listOf(plan(lastTier = STRATEGY_SELL_TIER_HALF)),
            evaluations = mapOf("p1" to evaluation(STRATEGY_SELL_TIER_HALF))
        )
        assertThat(r.signals).isEmpty()
        assertThat(r.tierUpdates).isEmpty()
        assertThat(r.clearedPlanIds).isEmpty()
    }

    @Test
    fun `HALF档可升级到ALL再次提醒`() {
        val r = StrategyNotifyEvaluator.evaluate(
            plans = listOf(plan(lastTier = STRATEGY_SELL_TIER_HALF)),
            evaluations = mapOf("p1" to evaluation(STRATEGY_SELL_TIER_ALL, 500))
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
            evaluations = mapOf("p1" to evaluation(STRATEGY_SELL_TIER_ALL))
        )
        assertThat(r.signals).isEmpty()
        assertThat(r.tierUpdates).isEmpty()
        assertThat(r.clearedPlanIds).isEmpty()
    }

    @Test
    fun `ALL回落到HALF档降级静默不回退状态`() {
        val r = StrategyNotifyEvaluator.evaluate(
            plans = listOf(plan(lastTier = STRATEGY_SELL_TIER_ALL)),
            evaluations = mapOf("p1" to evaluation(STRATEGY_SELL_TIER_HALF))
        )
        assertThat(r.signals).isEmpty()
        assertThat(r.tierUpdates).isEmpty()
        assertThat(r.clearedPlanIds).isEmpty()
    }

    @Test
    fun `脱离卖出区清空状态`() {
        val r = StrategyNotifyEvaluator.evaluate(
            plans = listOf(plan(lastTier = STRATEGY_SELL_TIER_ALL)),
            evaluations = mapOf("p1" to evaluation(null))
        )
        assertThat(r.signals).isEmpty()
        assertThat(r.tierUpdates).isEmpty()
        assertThat(r.clearedPlanIds).containsExactly("p1")
    }

    @Test
    fun `清空后再次进入卖出区可重新提醒`() {
        val r = StrategyNotifyEvaluator.evaluate(
            plans = listOf(plan(lastTier = null)),
            evaluations = mapOf("p1" to evaluation(STRATEGY_SELL_TIER_HALF))
        )
        assertThat(r.signals).hasSize(1)
    }

    @Test
    fun `数据不足的计划跳过且不动状态`() {
        val r = StrategyNotifyEvaluator.evaluate(
            plans = listOf(plan(lastTier = STRATEGY_SELL_TIER_HALF)),
            evaluations = emptyMap()
        )
        assertThat(r.signals).isEmpty()
        assertThat(r.tierUpdates).isEmpty()
        assertThat(r.clearedPlanIds).isEmpty()
    }

    @Test
    fun `不同策略类型共用同一档位语义`() {
        val r = StrategyNotifyEvaluator.evaluate(
            plans = listOf(plan(id = "t", type = STRATEGY_TYPE_TAKE_PROFIT)),
            evaluations = mapOf("t" to evaluation(STRATEGY_SELL_TIER_ALL, 700))
        )
        assertThat(r.signals).hasSize(1)
        assertThat(r.signals.first().strategyTypeName).isEqualTo("目标止盈")
        assertThat(r.signals.first().sellShares).isEqualTo(700)
    }

    @Test
    fun `多计划互不影响`() {
        val r = StrategyNotifyEvaluator.evaluate(
            plans = listOf(
                plan(id = "a", lastTier = null),
                plan(id = "b", lastTier = STRATEGY_SELL_TIER_ALL)
            ),
            evaluations = mapOf(
                "a" to evaluation(STRATEGY_SELL_TIER_HALF),
                "b" to evaluation(STRATEGY_SELL_TIER_ALL)
            )
        )
        assertThat(r.signals).hasSize(1)
        assertThat(r.signals.first().planId).isEqualTo("a")
        assertThat(r.tierUpdates).containsEntry("a", STRATEGY_SELL_TIER_HALF)
        assertThat(r.clearedPlanIds).isEmpty()
    }
}
