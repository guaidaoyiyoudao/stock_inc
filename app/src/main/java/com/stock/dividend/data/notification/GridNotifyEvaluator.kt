package com.stock.dividend.data.notification

import com.stock.dividend.data.local.entity.GridPlanEntity
import com.stock.dividend.data.local.entity.TransactionEntity
import com.stock.dividend.data.repository.GridCalculator
import com.stock.dividend.data.repository.GridType

/**
 * 单条网格到档提醒信号。
 *
 * @property plan       触发提醒的网格计划。
 * @property levelPrice 到达的买入档位价（元）。
 * @property currentPrice 检查时现价（元）。
 * @property shares     该档计划买入股数（可能为 0——资金量太小不足一手）。
 */
data class GridNotifySignal(
    val plan: GridPlanEntity,
    val levelPrice: Double,
    val currentPrice: Double,
    val shares: Int
)

/**
 * 网格到档提醒评估结果。
 *
 * @property signals       需发送的提醒列表。
 * @property clearedPlanIds 需清空提醒状态的计划 id（现价已回升超过上次提醒档位，
 *                          迟滞复位，未来再次跌破该档可重新提醒）。
 * @property notifiedLevels 发出信号的同时需持久化的 plan.id → 已提醒档位价。
 */
data class GridNotifyEvaluation(
    val signals: List<GridNotifySignal> = emptyList(),
    val clearedPlanIds: List<String> = emptyList(),
    val notifiedLevels: Map<String, Double> = emptyMap()
)

/**
 * 网格到档提醒评估器（纯函数，无 Android 依赖，便于单测）。
 *
 * **触发语义（带迟滞的边沿触发，与通知规则的 lastWasAboveThreshold 翻转模式同思路）**：
 * - **到达判定**：现价 ≤ 某档位价即视为到达该档；取「档位价 ≥ 现价」中最便宜的一档
 *   （最深到达档，跳空跌穿多档时只提醒一次）。现价高于买入起点（最贵档）时无到达档。
 * - **每档只提醒一次**：到达档 == [GridPlanEntity.lastNotifiedLevelPrice] 时跳过。
 * - **迟滞复位**：现价回升到上次提醒档位之上 → 清空提醒状态；价格再次跌破该档会重新提醒。
 * - **已执行不唠叨**：到达档已被实际 BUY 交易触发（[GridCalculator.markTriggeredLevels]
 *   同口径命中）时跳过该档。
 * - 开关关闭 / 无现价 / 计划参数非法（validationError）→ 整个计划跳过。
 */
object GridNotifyEvaluator {

    fun evaluate(
        plans: List<GridPlanEntity>,
        prices: Map<String, Double>,
        transactionsByStock: Map<String, List<TransactionEntity>>
    ): GridNotifyEvaluation {
        val signals = mutableListOf<GridNotifySignal>()
        val clearedPlanIds = mutableListOf<String>()
        val notifiedLevels = mutableMapOf<String, Double>()

        for (plan in plans) {
            if (!plan.notifyEnabled) continue
            val price = prices[plan.stockCode]
            if (price == null || price <= 0.0) continue

            val transactions = transactionsByStock[plan.stockCode].orEmpty()
            val result = GridCalculator.markTriggeredLevels(
                GridCalculator.generate(
                    basePrice = plan.basePrice,
                    lowPrice = plan.lowPrice,
                    highPrice = plan.highPrice,
                    grids = plan.grids,
                    totalCapital = plan.totalCapital,
                    currentPrice = price,
                    gridType = GridType.fromRaw(plan.gridType),
                    dps = plan.dpsPerShare
                ),
                transactions
            )
            if (result.validationError != null || result.levels.isEmpty()) continue

            val lastNotified = plan.lastNotifiedLevelPrice
            // 迟滞复位：现价回升到上次提醒档位之上 → 清空状态（价格再次跌破时可重新提醒）
            if (lastNotified != null && price > lastNotified) {
                clearedPlanIds += plan.id
            }

            // 到达判定：档位价 ≥ 现价中最便宜的一档（最深到达档）
            val crossed = result.levels.filter { it.price >= price }.minByOrNull { it.price }
                ?: continue  // 现价高于买入起点，未到达任何档
            if (crossed.price == lastNotified) continue  // 该档已提醒过
            if (crossed.triggered) continue               // 该档已有实际买入，不唠叨

            signals += GridNotifySignal(
                plan = plan,
                levelPrice = crossed.price,
                currentPrice = price,
                shares = crossed.shares
            )
            notifiedLevels[plan.id] = crossed.price
        }

        return GridNotifyEvaluation(
            signals = signals,
            clearedPlanIds = clearedPlanIds,
            notifiedLevels = notifiedLevels
        )
    }
}
