package com.stock.dividend.data.notification

import com.stock.dividend.data.local.entity.GridLevelWeights
import com.stock.dividend.data.local.entity.GridPlanEntity
import com.stock.dividend.data.local.entity.TransactionEntity
import com.stock.dividend.data.repository.GridCalculator
import com.stock.dividend.data.repository.GridType

/**
 * 单条网格到档提醒信号。
 *
 * @property plan       触发提醒的网格计划。
 * @property levelPrice 到达的档位价（元）。买入信号 = 买入档位价；卖出信号（波段模式）=
 *                      在持档的**卖出锚价**（股息率锚）。
 * @property currentPrice 检查时现价（元）。
 * @property shares     该档计划股数（买入信号：底仓已建的档 = 波段股数，其余 = 全量；
 *                      卖出信号 = 波段股数，底仓不动。可能为 0——资金量太小不足一手）。
 * @property sell       是否卖出方向信号（波段模式：现价涨到在持档的卖出锚，
 *                      提示减仓波段部分）；false = 买入到档（默认）。
 */
data class GridNotifySignal(
    val plan: GridPlanEntity,
    val levelPrice: Double,
    val currentPrice: Double,
    val shares: Int,
    val sell: Boolean = false
)

/**
 * 网格到档提醒评估结果。
 *
 * @property signals       需发送的提醒列表（买入到档 + 波段卖出到档）。
 * @property clearedPlanIds 需清空**买入**提醒状态的计划 id（现价已回升超过上次提醒档位，
 *                          迟滞复位，未来再次跌破该档可重新提醒）。
 * @property notifiedLevels 发出买入信号的同时需持久化的 plan.id → 已提醒档位价。
 * @property clearedSellPlanIds 需清空**卖出**提醒状态的计划 id（波段模式：现价已回落
 *                          到上次提醒的卖出档之下，未来再次涨到可重新提醒）。
 * @property notifiedSellLevels 发出卖出信号的同时需持久化的 plan.id → 已提醒配对卖出价。
 */
data class GridNotifyEvaluation(
    val signals: List<GridNotifySignal> = emptyList(),
    val clearedPlanIds: List<String> = emptyList(),
    val notifiedLevels: Map<String, Double> = emptyMap(),
    val clearedSellPlanIds: List<String> = emptyList(),
    val notifiedSellLevels: Map<String, Double> = emptyMap()
)

/**
 * 网格到档提醒评估器（纯函数，无 Android 依赖，便于单测）。
 *
 * **买入侧触发语义（带迟滞的边沿触发，与通知规则的 lastWasAboveThreshold 翻转模式同思路）**：
 * - **到达判定**：现价 ≤ 某档位价即视为到达该档；取「档位价 ≥ 现价」中最便宜的一档
 *   （最深到达档，跳空跌穿多档时只提醒一次）。现价高于买入起点（最贵档）时无到达档。
 * - **每档只提醒一次**：到达档 == [GridPlanEntity.lastNotifiedLevelPrice] 时跳过。
 * - **迟滞复位**：现价回升到上次提醒档位之上 → 清空提醒状态；价格再次跌破该档会重新提醒。
 * - **在持不唠叨**：到达档的波段部分在持（[GridCalculator.markTriggeredLevels] triggered）
 *   时跳过该档；底仓已建而波段已释放的档会再次提醒（补波段股数，回合语义）。
 * - 开关关闭 / 无现价 / 计划参数非法（validationError）→ 整个计划跳过。
 *
 * **卖出侧触发语义（仅波段模式 swingMode，2026-08-23）**——与买入侧镜像对称：
 * - **到达判定**：现价 ≥ 某在持档（triggered）的**卖出锚**即视为到卖出档；取已到达
 *   目标中最高的一个（最高到达档，跳空涨穿多档时只提醒一次）。
 * - **每档只提醒一次**：到达目标 == [GridPlanEntity.lastNotifiedSellLevelPrice] 时跳过。
 * - **迟滞复位**：现价回落到上次提醒卖出档之下 → 清空卖出提醒状态，再次涨到可重新提醒。
 * - **已卖出不提醒**：该档波段部分卖出后即释放（不再 triggered），目标自动移出候选。
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
        val clearedSellPlanIds = mutableListOf<String>()
        val notifiedSellLevels = mutableMapOf<String, Double>()

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
                    dps = plan.dpsPerShare,
                    levelWeights = GridLevelWeights.parse(plan.levelWeights),
                    swingMode = plan.swingMode,
                    swingStepPercent = plan.swingStepPercent,
                    swingRatioPercent = plan.swingRatioPercent
                ),
                transactions
            )
            if (result.validationError != null || result.levels.isEmpty()) continue

            // ── 卖出侧（波段模式）：现价涨到在持档的卖出锚 → 提示减仓波段部分 ──
            if (result.swingMode) {
                val lastNotifiedSell = plan.lastNotifiedSellLevelPrice
                // 迟滞复位：现价回落到上次提醒卖出档之下 → 清空状态（再次涨到可重新提醒）
                if (lastNotifiedSell != null && price < lastNotifiedSell) {
                    clearedSellPlanIds += plan.id
                }
                // 到达判定：在持档的卖出锚 ≤ 现价中最高的一档（最高到达档）
                val reachedSell = result.levels
                    .filter { it.triggered && it.pairedSellPrice != null && price >= it.pairedSellPrice!! }
                    .maxByOrNull { it.pairedSellPrice!! }
                if (reachedSell != null && reachedSell.pairedSellPrice != lastNotifiedSell) {
                    signals += GridNotifySignal(
                        plan = plan,
                        levelPrice = reachedSell.pairedSellPrice!!,
                        currentPrice = price,
                        shares = reachedSell.swingShares,
                        sell = true
                    )
                    notifiedSellLevels[plan.id] = reachedSell.pairedSellPrice!!
                }
            }

            // ── 买入侧：现价跌到档位价 → 提示低吸 ──
            val lastNotified = plan.lastNotifiedLevelPrice
            // 迟滞复位：现价回升到上次提醒档位之上 → 清空状态（价格再次跌破时可重新提醒）
            if (lastNotified != null && price > lastNotified) {
                clearedPlanIds += plan.id
            }

            // 到达判定：档位价 ≥ 现价中最便宜的一档（最深到达档）
            val crossed = result.levels.filter { it.price >= price }.minByOrNull { it.price }
                ?: continue  // 现价高于买入起点，未到达任何档
            if (crossed.price == lastNotified) continue  // 该档已提醒过
            if (crossed.triggered) continue               // 该档波段在持，不唠叨

            signals += GridNotifySignal(
                plan = plan,
                levelPrice = crossed.price,
                currentPrice = price,
                // 底仓已建的档只补波段股数；全新档买全量
                shares = if (result.swingMode && crossed.baseHeld) crossed.swingShares else crossed.shares
            )
            notifiedLevels[plan.id] = crossed.price
        }

        return GridNotifyEvaluation(
            signals = signals,
            clearedPlanIds = clearedPlanIds,
            notifiedLevels = notifiedLevels,
            clearedSellPlanIds = clearedSellPlanIds,
            notifiedSellLevels = notifiedSellLevels
        )
    }
}
