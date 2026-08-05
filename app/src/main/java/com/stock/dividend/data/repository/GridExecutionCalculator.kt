package com.stock.dividend.data.repository

import com.stock.dividend.data.local.entity.TransactionEntity

/**
 * 网格执行跟踪（纯函数，无 Android 依赖，便于单测）。
 *
 * 把「计划档位」和「实际成交」对照，回答收息投资者最关心的执行问题：
 * **已经投了多少、还剩多少、这套网格跑得怎么样**。
 *
 * - [investedAmount]  已投入金额（元）= 命中档位的实际买入金额之和。
 * - [remainingCapital] 剩余可投资金（元）= 计划总资金 − 已投入。
 * - [boughtShares]     已买入股数（命中档位实际成交股数之和）。
 * - [triggeredCount]   已触发档位数。
 * - [totalLevels]      总档位数。
 * - [avgBuyPrice]      已买入部分的加权均价（元/股）；无成交为 null。
 * - [currentValue]     已买入部分的当前市值（元，= boughtShares × currentPrice）；无现价为 null。
 * - [unrealizedPnl]    已买入部分的浮动盈亏（元，= 当前市值 − 已投入）；无现价为 null。
 *
 * **匹配口径**：与 [GridCalculator.markTriggeredLevels] 一致——某档位被「触发」即该股存在
 * 一笔 BUY 成交价落在该档触发区间（档位价 ± 半步长）。本函数在 [GridResult] 已标记
 * triggered 的基础上，进一步汇总这些命中档位对应的实际成交明细。
 *
 * @property investedAmount  已投入金额（元）。
 * @property remainingCapital 剩余可投资金（元）。
 * @property boughtShares     已买入股数。
 * @property triggeredCount   已触发档位数。
 * @property totalLevels      总档位数。
 * @property avgBuyPrice      加权均价（元/股），无成交为 null。
 * @property currentValue     当前市值（元），无现价为 null。
 * @property unrealizedPnl    浮动盈亏（元），无现价为 null。
 * @property unrealizedPnlRate 浮盈率（%），无现价或已投入为 0 时为 null。
 */
data class GridExecution(
    val investedAmount: Double,
    val remainingCapital: Double,
    val boughtShares: Int,
    val triggeredCount: Int,
    val totalLevels: Int,
    val avgBuyPrice: Double?,
    val currentValue: Double?,
    val unrealizedPnl: Double?,
    val unrealizedPnlRate: Double?
) {
    /** 执行进度（%，已触发档 / 总档）；无档位为 0。 */
    val progressPercent: Int get() = if (totalLevels > 0) (triggeredCount * 100 / totalLevels) else 0

    companion object {
        val EMPTY = GridExecution(0.0, 0.0, 0, 0, 0, null, null, null, null)
    }
}

object GridExecutionCalculator {

    /**
     * 计算网格执行跟踪。
     *
     * @param result       网格计算结果（应已调用 [GridCalculator.markTriggeredLevels] 标记 triggered）。
     * @param totalCapital 计划总资金（元，> 0）。
     * @param transactions 该股票的全部交易记录（内部只取命中触发区间的 BUY）。
     * @param currentPrice 当前价（元），可选；用于浮盈。
     */
    fun calculate(
        result: GridResult,
        totalCapital: Double,
        transactions: List<TransactionEntity>,
        currentPrice: Double?
    ): GridExecution {
        if (result.levels.isEmpty() || totalCapital <= 0.0) return GridExecution.EMPTY

        // 半步长（与 markTriggeredLevels 同口径）
        val levelPrices = result.levels.map { it.price }
        val halfStep = levelPrices.zipWithNext().minOfOrNull { (a, b) -> (b - a) / 2.0 }
            ?: return GridExecution.EMPTY

        val triggeredPrices = result.levels.filter { it.triggered }.map { it.price }.toSet()

        // 命中交易：BUY、价格落在某已触发档位的触发区间
        val hits = transactions.filter { tx ->
            tx.type == "BUY" && tx.price > 0.0 &&
                triggeredPrices.any { p -> kotlin.math.abs(tx.price - p) <= halfStep }
        }

        val invested = hits.sumOf { it.price * it.shares }
        val shares = hits.sumOf { it.shares }
        val triggeredCount = result.levels.count { it.triggered }
        val totalLevels = result.levels.size
        val avg = if (shares > 0) invested / shares else null
        val currentVal = if (shares > 0 && currentPrice != null && currentPrice > 0.0) shares * currentPrice else null
        val pnl = if (currentVal != null) currentVal - invested else null
        val pnlRate = if (pnl != null && invested > 0.0) pnl / invested * 100.0 else null

        return GridExecution(
            investedAmount = round2(invested),
            remainingCapital = round2(totalCapital - invested),
            boughtShares = shares,
            triggeredCount = triggeredCount,
            totalLevels = totalLevels,
            avgBuyPrice = avg?.let(::round2),
            currentValue = currentVal?.let(::round2),
            unrealizedPnl = pnl?.let(::round2),
            unrealizedPnlRate = pnlRate?.let(::round2)
        )
    }

    private fun round2(v: Double): Double = kotlin.math.round(v * 100.0) / 100.0
}
