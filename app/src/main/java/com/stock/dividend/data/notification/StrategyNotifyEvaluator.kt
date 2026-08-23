package com.stock.dividend.data.notification

import com.stock.dividend.data.local.entity.StrategyPlanEntity
import com.stock.dividend.data.repository.MaDcaEvaluation
import com.stock.dividend.data.repository.MaDcaSignal
import com.stock.dividend.data.repository.MaDcaStrategyCalculator

/** 卖出档标识：卖出一半（与 StrategyPlanEntity.lastNotifiedSellTier 存储值一致）。 */
const val STRATEGY_SELL_TIER_HALF = "HALF"

/** 卖出档标识：全部卖出。 */
const val STRATEGY_SELL_TIER_ALL = "ALL"

/** 单条策略卖出提醒信号（[StrategyNotifyEvaluator] 输出）。 */
data class StrategySellSignal(
    val planId: String,
    val stockCode: String,
    val stockName: String,
    /** 触发档：HALF 卖一半 / ALL 全卖。 */
    val tier: String,
    /** 现价相对年线偏离度（%）。 */
    val deviationPercent: Double,
    /** 该档触发价（卖半/清仓价）。 */
    val triggerPrice: Double,
    /** 该档阈值百分比（%，高于年线的偏离阈值）。 */
    val thresholdPercent: Double,
    /** 建议卖出股数（按持仓折整手；无持仓为 0，仅提示到位）。 */
    val sellShares: Int
)

/**
 * 策略卖出提醒评估结果：待发信号 + 状态回写（发通知成功后由协调器落库）。
 *
 * - [tierUpdates] planId → 新档（HALF/ALL）：本次发出信号、需回写去重状态；
 * - [clearedPlanIds] 偏离回落到卖半阈值以下（含跌破年线）的计划：清空状态，
 *   下次再涨到阈值可重新提醒（迟滞复位，语义同网格到档）。
 */
data class StrategyNotifyEvaluation(
    val signals: List<StrategySellSignal>,
    val tierUpdates: Map<String, String>,
    val clearedPlanIds: List<String>
)

/**
 * 策略卖出阈值提醒评估器（纯函数，无 Android 依赖，模式同 [GridNotifyEvaluator]）。
 *
 * 边沿触发语义：
 * - 每档只提醒一次：已提醒 HALF 且仍在 HALF 档 → 不再提醒；
 * - 可升级：已提醒 HALF 后涨到清仓阈值 → 再发一条 ALL；
 * - 回落复位：偏离回落到卖半阈值以下 → 清空状态（清仓后又跌回年线下方，
 *   再涨到阈值时可重新提醒整轮）；
 * - 评估数据不足（年线算不出）→ 跳过且不动状态。
 */
object StrategyNotifyEvaluator {

    fun evaluate(
        plans: List<StrategyPlanEntity>,
        evaluations: Map<String, MaDcaEvaluation>,
        holdingShares: Map<String, Int>
    ): StrategyNotifyEvaluation {
        val signals = mutableListOf<StrategySellSignal>()
        val tierUpdates = mutableMapOf<String, String>()
        val cleared = mutableListOf<String>()
        for (plan in plans) {
            val evaluation = evaluations[plan.id] ?: continue
            val currentTier = when (evaluation.signal) {
                MaDcaSignal.SELL_HALF -> STRATEGY_SELL_TIER_HALF
                MaDcaSignal.SELL_ALL -> STRATEGY_SELL_TIER_ALL
                else -> null
            }
            val last = plan.lastNotifiedSellTier
            when {
                currentTier == null -> if (last != null) cleared += plan.id
                last == currentTier -> Unit // 同档已提醒过，跳过
                else -> {
                    val shares = MaDcaStrategyCalculator.sellSharesFor(
                        evaluation.signal, holdingShares[plan.stockCode] ?: 0
                    )
                    signals += StrategySellSignal(
                        planId = plan.id,
                        stockCode = plan.stockCode,
                        stockName = plan.stockName,
                        tier = currentTier,
                        deviationPercent = evaluation.deviationPercent,
                        triggerPrice = if (currentTier == STRATEGY_SELL_TIER_ALL) {
                            evaluation.sellAllTriggerPrice
                        } else {
                            evaluation.sellHalfTriggerPrice
                        },
                        thresholdPercent = if (currentTier == STRATEGY_SELL_TIER_ALL) {
                            plan.sellAllPercent
                        } else {
                            plan.sellHalfPercent
                        },
                        sellShares = shares
                    )
                    tierUpdates[plan.id] = currentTier
                }
            }
        }
        return StrategyNotifyEvaluation(
            signals = signals,
            tierUpdates = tierUpdates,
            clearedPlanIds = cleared
        )
    }
}
