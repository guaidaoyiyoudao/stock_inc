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
 * - [avgDeviationPercent] 执行偏差：实际成交价 vs 命中档位价的金额加权平均偏离（%，
 *   正=成交价高于档位价/买贵了，负=买得更便宜）；无成交为 null。
 * - [worstDeviationPercent] 最差一次执行的偏离（%，最大值；正=买得最贵的一次幅度）。
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
    val unrealizedPnlRate: Double?,
    val avgDeviationPercent: Double? = null,
    val worstDeviationPercent: Double? = null
) {
    /** 执行进度（%，已触发档 / 总档）；无档位为 0。 */
    val progressPercent: Int get() = if (totalLevels > 0) (triggeredCount * 100 / totalLevels) else 0

    companion object {
        val EMPTY = GridExecution(0.0, 0.0, 0, 0, 0, null, null, null, null, null, null)
    }
}

/**
 * 单个已触发档位的实际成交明细汇总（就近档位匹配口径，与偏差统计一致）。
 *
 * @property levelPrice 档位价（元）。
 * @property price      最近一笔实际成交价（元）。
 * @property shares     该档累计买入股数。
 * @property fills      成交笔数。
 * @property lastDate   最近成交日期（yyyy-MM-dd）。
 */
data class GridLevelFill(
    val levelPrice: Double,
    val price: Double,
    val shares: Int,
    val fills: Int,
    val lastDate: String?
)

/**
 * 弹药库汇总：全部网格计划的合计视图（还剩多少子弹）。
 *
 * @property planCount        计划数。
 * @property totalCapital     合计总资金（元）。
 * @property investedAmount   合计已投入（元）。
 * @property remainingCapital 合计剩余可投（元）。
 * @property triggeredLevels  合计已触发档数。
 * @property totalLevels      合计总档数。
 */
data class GridAmmoSummary(
    val planCount: Int,
    val totalCapital: Double,
    val investedAmount: Double,
    val remainingCapital: Double,
    val triggeredLevels: Int,
    val totalLevels: Int
) {
    /** 加权执行进度（%，已触发档/总档）；无档位为 0。 */
    val progressPercent: Int get() = if (totalLevels > 0) triggeredLevels * 100 / totalLevels else 0
}

object GridExecutionCalculator {

    /**
     * 逐档成交明细：每个已触发档位对应的实际买入汇总（档位价 → 最近成交价/累计股数/笔数/日期）。
     *
     * 匹配口径与 [calculate] 的偏差统计一致：BUY 成交价落入某已触发档位的触发区间
     * （档位价 ± 半步长），就近归到价差最小的档位。未触发档位不出现在结果里。
     */
    fun levelFills(
        result: GridResult,
        transactions: List<TransactionEntity>
    ): Map<Double, GridLevelFill> {
        if (result.levels.isEmpty()) return emptyMap()
        val levelPrices = result.levels.map { it.price }
        val halfStep = levelPrices.zipWithNext().minOfOrNull { (a, b) -> (b - a) / 2.0 }
            ?: return emptyMap()
        val triggeredPrices = result.levels.filter { it.triggered }.map { it.price }

        data class Acc(var price: Double, var shares: Int, var fills: Int, var lastDate: String?)
        val byLevel = mutableMapOf<Double, Acc>()
        transactions
            .filter { it.type == "BUY" && it.price > 0.0 }
            .sortedBy { it.date }
            .forEach { tx ->
                val level = triggeredPrices
                    .filter { p -> kotlin.math.abs(tx.price - p) <= halfStep }
                    .minByOrNull { p -> kotlin.math.abs(tx.price - p) }
                    ?: return@forEach
                val acc = byLevel.getOrPut(level) { Acc(tx.price, 0, 0, null) }
                acc.price = tx.price
                acc.shares += tx.shares
                acc.fills += 1
                acc.lastDate = tx.date
            }
        return byLevel.mapValues { (level, acc) ->
            GridLevelFill(
                levelPrice = level,
                price = acc.price,
                shares = acc.shares,
                fills = acc.fills,
                lastDate = acc.lastDate
            )
        }
    }

    /**
     * 弹药库汇总：全部网格计划的合计总资金/已投入/剩余可投/加权触发进度。
     *
     * @param totalCapitals 各计划总资金（与 [executions] 等长、一一对应；显式传入是因为
     *                      参数非法的计划会产生 EMPTY 执行、丢失其资金量）。
     */
    fun summarizeAmmo(
        totalCapitals: List<Double>,
        executions: List<GridExecution>
    ): GridAmmoSummary {
        val totalCapital = totalCapitals.sum()
        val invested = executions.sumOf { it.investedAmount }
        return GridAmmoSummary(
            planCount = executions.size,
            totalCapital = round2(totalCapital),
            investedAmount = round2(invested),
            remainingCapital = round2(totalCapital - invested),
            triggeredLevels = executions.sumOf { it.triggeredCount },
            totalLevels = executions.sumOf { it.totalLevels }
        )
    }

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

        // 执行偏差：每笔命中成交 vs 其最近命中档位价（+ = 成交价高于档位价/买贵了）。
        // 平均按金额加权（大单权重高），最差取历史最大值。
        val deviations = hits.mapNotNull { tx ->
            val level = result.levels
                .filter { it.triggered && kotlin.math.abs(tx.price - it.price) <= halfStep }
                .minByOrNull { kotlin.math.abs(tx.price - it.price) }
            level?.let { (tx.price - it.price) / it.price * 100.0 to tx.price * tx.shares }
        }
        val deviationAmountSum = deviations.sumOf { it.second }
        val avgDeviation = if (deviationAmountSum > 0.0) {
            deviations.sumOf { (d, amount) -> d * amount } / deviationAmountSum
        } else null
        val worstDeviation = deviations.maxOfOrNull { it.first }

        return GridExecution(
            investedAmount = round2(invested),
            remainingCapital = round2(totalCapital - invested),
            boughtShares = shares,
            triggeredCount = triggeredCount,
            totalLevels = totalLevels,
            avgBuyPrice = avg?.let(::round2),
            currentValue = currentVal?.let(::round2),
            unrealizedPnl = pnl?.let(::round2),
            unrealizedPnlRate = pnlRate?.let(::round2),
            avgDeviationPercent = avgDeviation?.let(::round2),
            worstDeviationPercent = worstDeviation?.let(::round2)
        )
    }

    private fun round2(v: Double): Double = kotlin.math.round(v * 100.0) / 100.0
}
