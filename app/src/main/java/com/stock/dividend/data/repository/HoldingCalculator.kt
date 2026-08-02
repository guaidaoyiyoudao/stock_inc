package com.stock.dividend.data.repository

import com.stock.dividend.data.local.entity.TransactionEntity

/**
 * 持仓成本计算（纯函数，无 Android 依赖）。
 *
 * 采用「移动加权平均」：按交易时间顺序逐笔结转成本——
 * - 买入：累加持仓量与总成本，均价随之重算；
 * - 卖出：按当前均价结转成本，**不改变剩余持仓的均价**；
 *   卖出超过当前持仓的部分忽略（不允许负持仓）；
 *   持仓量清零时，成本一并归零（保留 0 而非历史均价）。
 *
 * 与「简单加权平均」（均价恒等于所有买入的加权均价）的差异，体现在
 * 「卖出之后再买入」的场景：旧算法无视中间卖出，新算法会因卖出结转
 * 后的剩余成本而得到不同的新均价。
 *
 * **前置条件**：[transactions] 必须已按时间升序排列
 * （[com.stock.dividend.data.local.dao.TransactionDao] 的查询已保证
 * `ORDER BY date ASC, createdAt ASC`）。
 */
object HoldingCalculator {

    /** 单股持仓快照：总持仓量 + 每股平均成本。 */
    data class Holding(
        val totalShares: Int,
        val avgCostPerShare: Double
    )

    fun calculate(transactions: List<TransactionEntity>): Holding {
        var shares = 0.0      // 当前持仓量（Double 避免整除截断）
        var costBasis = 0.0   // 当前持仓的总成本（= shares × 当前均价）

        for (tx in transactions) {
            if (tx.type == "BUY") {
                shares += tx.shares
                costBasis += tx.price * tx.shares
            } else { // SELL：按当前均价结转，卖超部分忽略
                val sellQty = minOf(tx.shares.toDouble(), shares)
                val avgBefore = if (shares > 0.0) costBasis / shares else 0.0
                costBasis -= sellQty * avgBefore
                shares -= sellQty
                // 清仓：成本归零（保留 0 而非历史均价）
                if (shares <= 0.0) {
                    shares = 0.0
                    costBasis = 0.0
                }
            }
        }

        val avg = if (shares > 0.0) costBasis / shares else 0.0
        return Holding(totalShares = shares.toInt(), avgCostPerShare = avg)
    }
}
