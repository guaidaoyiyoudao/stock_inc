package com.stock.dividend.data.repository

import com.stock.dividend.data.local.entity.TransactionEntity

/**
 * 已实现盈亏计算（纯函数，无 Android 依赖，便于单测）。
 *
 * 采用 **先进先出（FIFO）** 口径：卖出时按买入时间顺序逐笔结转成本，
 * `已实现盈亏 = 卖出金额 − 对应批次买入成本`。
 *
 * **为什么用 FIFO 而非 [HoldingCalculator] 的摊薄成本法？**
 * - 摊薄成本法把已实现盈亏「摊进」剩余持仓成本（卖出盈利→均价降），
 *   已实现盈亏被**隐藏**在成本里，无法独立展示；
 * - FIFO 是 A 股个人转让所得的法定计量口径（财税〔2008〕6 号「个人转让股票
 *   按先进先出确定其持股原值」），也是税务/券商「已实现盈亏」的标准口径；
 * - 两者并存：摊薄用于「持仓成本」展示，FIFO 用于「已实现盈亏」展示，互不冲突。
 *
 * 顺序约定：交易按 **date 升序、createdAt 升序** 结转（与 [TransactionDao.observeByStock]
 * 排序一致）；同日按插入顺序。
 *
 * 边界：
 * - 卖出超过已买入数量（卖超/做空）→ 仅对已买入部分结转，多余卖出**忽略**（不计盈亏、不建负仓）；
 * - 无任何匹配买入时盈亏为 0；
 * - BUY 批次剩余股数 0 即结清、不再复用。
 *
 * @property realizedPnl   单笔卖出已实现盈亏（元，卖出金额 − 结转成本）。
 * @property matchedShares 该笔卖出实际结转的股数（≤ 卖出股数；卖超部分为 0 贡献）。
 * @property costBasis     该笔卖出结转的成本（元）。
 */
data class RealizedTrade(
    val sellTxId: Long,
    val realizedPnl: Double,
    val matchedShares: Int,
    val costBasis: Double
) {
    /** 盈亏率（%）= 盈亏 / 结转成本；无匹配成本时为 null。 */
    val pnlRate: Double? get() = if (costBasis > 0.0) realizedPnl / costBasis * 100.0 else null
}

/**
 * 单股已实现盈亏汇总。
 *
 * @property totalRealizedPnl 累计已实现盈亏（元）。
 * @property totalCostBasis    累计结转成本（元，所有已卖出部分对应买入成本之和）。
 * @property totalProceeds     累计卖出收入（元，仅含已结转部分）。
 * @property trades            逐笔已实现盈亏（按卖出发生顺序）。
 */
data class RealizedPnl(
    val totalRealizedPnl: Double,
    val totalCostBasis: Double,
    val totalProceeds: Double,
    val trades: List<RealizedTrade>
) {
    /** 整体盈亏率（%）= 累计盈亏 / 累计结转成本；无结转成本时为 null。 */
    val totalPnlRate: Double?
        get() = if (totalCostBasis > 0.0) totalRealizedPnl / totalCostBasis * 100.0 else null

    companion object {
        val ZERO = RealizedPnl(0.0, 0.0, 0.0, emptyList())
    }
}

object RealizedPnlCalculator {

    /**
     * 计算单只股票的累计已实现盈亏（FIFO 结转）。
     *
     * @param transactions 该股票的全部交易记录（[BUY]/[SELL]），**应已按 date ASC、createdAt ASC 排序**。
     *                      未排序时会先做一次稳定排序，保证结转顺序确定。
     */
    fun calculate(transactions: List<TransactionEntity>): RealizedPnl {
        if (transactions.isEmpty()) return RealizedPnl.ZERO

        // 稳定排序：date ASC、createdAt ASC。保证 FIFO 顺序确定（与 DAO 默认排序一致，
        // 但调用方可能传未排序列表，这里兜底）。
        val sorted = transactions.sortedWith(
            compareBy({ it.date }, { it.createdAt })
        )

        // 待结转的买入批次队列：每批 (剩余股数, 单价)。
        val buyLots = ArrayDeque<Pair<Int, Double>>()
        val trades = mutableListOf<RealizedTrade>()
        var totalPnl = 0.0
        var totalCost = 0.0
        var totalProceeds = 0.0

        for (tx in sorted) {
            if (tx.type == "BUY") {
                if (tx.shares > 0) {
                    buyLots.addLast(tx.shares to tx.price)
                }
                continue
            }
            if (tx.type != "SELL" || tx.shares <= 0) continue

            // FIFO 结转：从队首批次逐笔消耗卖出股数。
            var remaining = tx.shares
            var matchedShares = 0
            var matchedCost = 0.0
            while (remaining > 0 && buyLots.isNotEmpty()) {
                val (lotShares, lotPrice) = buyLots.first()
                val take = minOf(remaining, lotShares)
                matchedCost += take * lotPrice
                matchedShares += take
                remaining -= take
                if (take == lotShares) {
                    buyLots.removeFirst()
                } else {
                    buyLots.removeFirst()
                    buyLots.addFirst((lotShares - take) to lotPrice)
                }
            }
            // 卖超部分（remaining>0）忽略：不建负仓、不计盈亏。
            val proceeds = matchedShares * tx.price
            val pnl = proceeds - matchedCost
            if (matchedShares > 0) {
                trades += RealizedTrade(
                    sellTxId = tx.id,
                    realizedPnl = pnl,
                    matchedShares = matchedShares,
                    costBasis = matchedCost
                )
                totalPnl += pnl
                totalCost += matchedCost
                totalProceeds += proceeds
            }
        }

        return RealizedPnl(
            totalRealizedPnl = totalPnl,
            totalCostBasis = totalCost,
            totalProceeds = totalProceeds,
            trades = trades
        )
    }
}
