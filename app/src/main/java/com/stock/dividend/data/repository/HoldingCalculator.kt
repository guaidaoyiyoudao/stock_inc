package com.stock.dividend.data.repository

import com.stock.dividend.data.local.entity.TransactionEntity

/**
 * 持仓成本计算（纯函数，无 Android 依赖）。
 *
 * 采用「摊薄成本法」（同花顺 / 主流券商默认口径）：
 *
 * ```
 * 成本价 = (累计买入金额 − 累计卖出金额) ÷ 当前持仓数量
 * ```
 *
 * 含义：把持有期内已实现盈亏（买卖差额）分摊进剩余持仓成本——
 * - 卖出**盈利** → 卖出总额 > 对应买入成本，摊薄后**均价降低**；
 * - 卖出**亏损** → 摊薄后**均价升高**；
 * - 当前股价等于此成本价时即保本（盈亏均衡）。
 *
 * 与「移动加权平均」的关键区别：摊薄成本法下**单纯卖出也会改变均价**
 * （已实现盈亏直接冲减/增加成本）；移动加权下卖出只结转数量、均价不变。
 *
 * 边界：
 * - 持仓量为 0（全平或卖超）→ 均价归 0，不保留历史值；
 * - 卖超（卖出 > 累计买入）→ 持仓钳到 0、均价归 0。
 */
object HoldingCalculator {

    /** 单股持仓快照：总持仓量 + 每股摊薄成本。 */
    data class Holding(
        val totalShares: Int,
        val avgCostPerShare: Double
    )

    fun calculate(transactions: List<TransactionEntity>): Holding {
        var buyAmount = 0.0    // 累计买入金额 Σ(price × shares)
        var buyShares = 0L
        var sellAmount = 0.0   // 累计卖出金额 Σ(price × shares)
        var sellShares = 0L

        for (tx in transactions) {
            if (tx.type == "BUY") {
                buyAmount += tx.price * tx.shares
                buyShares += tx.shares.toLong()
            } else { // SELL
                sellAmount += tx.price * tx.shares
                sellShares += tx.shares.toLong()
            }
        }

        // 持仓量 = 买入 − 卖出，钳到 0（不允许负持仓）
        val shares = (buyShares - sellShares).coerceAtLeast(0L).toInt()
        // 持仓为 0 → 均价归 0；否则按摊薄公式计算
        val avg = if (shares > 0) (buyAmount - sellAmount) / shares else 0.0
        return Holding(totalShares = shares, avgCostPerShare = avg)
    }
}
