package com.stock.dividend.data.notification

import com.stock.dividend.data.local.entity.StrategyPlanEntity
import com.stock.dividend.data.repository.StrategyEvaluator
import com.stock.dividend.data.repository.StrategyEvaluation

/** 卖出档标识：部分卖出（与 StrategyPlanEntity.lastNotifiedSellTier 存储值一致）。 */
const val STRATEGY_SELL_TIER_HALF = "HALF"

/** 卖出档标识：全部卖出/清仓（单档卖出类策略也用此档）。 */
const val STRATEGY_SELL_TIER_ALL = "ALL"

/**
 * 单条策略卖出提醒信号（[StrategyNotifyEvaluator] 输出）。
 *
 * @param headline 状态主标签（来自统一评估，如「高于年线 7.5% · 卖出一半」）。
 * @param sellShares 建议卖出股数（整手折算；无持仓为 0，仅提示到位）。
 */
data class StrategySellSignal(
    val planId: String,
    val stockCode: String,
    val stockName: String,
    /** 策略类型显示名（如「年线定投」，通知文案用）。 */
    val strategyTypeName: String,
    /** 状态主标签（含阈值信息）。 */
    val headline: String,
    val tier: String,
    val sellShares: Int
)

/**
 * 策略卖出提醒评估结果：待发信号 + 状态回写（发通知成功后由协调器落库）。
 *
 * - [tierUpdates] planId → 新档（HALF/ALL）：本次发出信号、需回写去重状态；
 * - [clearedPlanIds] 信号回落到无推送档（null）的计划：清空状态，下次再触发可重新提醒
 *   （迟滞复位，语义同网格到档）。
 */
data class StrategyNotifyEvaluation(
    val signals: List<StrategySellSignal>,
    val tierUpdates: Map<String, String>,
    val clearedPlanIds: List<String>
)

/**
 * 策略卖出提醒评估器（纯函数，无 Android 依赖，模式同 [GridNotifyEvaluator]；
 * 2026-08-23 v2：输入换统一 [StrategyEvaluation]，适配全部策略类型）。
 *
 * 边沿触发语义（档位有序：null < HALF < ALL）：
 * - **升级才提醒**：当前档严格高于已提醒档 → 发信号（首次进入 HALF、或 HALF 升级 ALL）；
 * - 同档不重复：已提醒 HALF 仍在 HALF 档、已提醒 ALL 仍在 ALL 档 → 不再提醒；
 * - 降级不提醒：已提醒 ALL 回落到 HALF 档（未退出卖出区）→ 静默，不回退状态；
 * - 回落复位：档位回到 null（脱离卖出区）→ 清空状态，下轮再进入可重新提醒整轮；
 * - 评估数据不足（不在 [evaluations] 中）→ 跳过且不动状态。
 */
object StrategyNotifyEvaluator {

    private fun tierRank(tier: String?): Int = when (tier) {
        STRATEGY_SELL_TIER_HALF -> 1
        STRATEGY_SELL_TIER_ALL -> 2
        else -> 0
    }

    fun evaluate(
        plans: List<StrategyPlanEntity>,
        evaluations: Map<String, StrategyEvaluation>
    ): StrategyNotifyEvaluation {
        val signals = mutableListOf<StrategySellSignal>()
        val tierUpdates = mutableMapOf<String, String>()
        val cleared = mutableListOf<String>()
        for (plan in plans) {
            val evaluation = evaluations[plan.id] ?: continue
            val currentTier = evaluation.notifyTier
            val last = plan.lastNotifiedSellTier
            when {
                currentTier == null -> if (last != null) cleared += plan.id
                tierRank(currentTier) > tierRank(last) -> {
                    signals += StrategySellSignal(
                        planId = plan.id,
                        stockCode = plan.stockCode,
                        stockName = plan.stockName,
                        strategyTypeName = StrategyEvaluator.displayName(plan.strategyType),
                        headline = evaluation.headline,
                        tier = currentTier,
                        sellShares = evaluation.sellShares
                    )
                    tierUpdates[plan.id] = currentTier
                }
                // 同档已提醒 / 降级（ALL→HALF 未脱离卖出区）→ 静默
            }
        }
        return StrategyNotifyEvaluation(
            signals = signals,
            tierUpdates = tierUpdates,
            clearedPlanIds = cleared
        )
    }
}
