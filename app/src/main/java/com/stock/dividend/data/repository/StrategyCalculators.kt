package com.stock.dividend.data.repository

/**
 * 七个策略计算器（纯函数，无 Android 依赖；统一输出 [StrategyEvaluation]，
 * 经 [StrategyEvaluator] 分发）。文件内聚多计算器（先例：Formatters.kt）。
 *
 * 共同约定：
 * - 恰达阈值计为触发（与 [MaDcaStrategyCalculator] 同语义，含浮点容差）；
 * - 买卖股数一律按 A 股整手（100 股）向下折算；
 * - 买入方向只展示不推送（notifyTier=null）；卖出方向按类型给 HALF/ALL 档
 *   （边沿触发推送语义见 data/notification/StrategyNotifyEvaluator）；
 * - ValueAveraging 超额卖出按定投节奏类处理，不推送（notifyTier=null 为有意行为）。
 */
private const val TIER_HALF = "HALF"
private const val TIER_ALL = "ALL"
private const val DEVIATION_EPS = 1e-9

/** 目标止盈：按摊薄成本涨幅分批卖出（+half 卖一半、+all 清仓）。 */
object TakeProfitStrategyCalculator {

    fun evaluate(
        price: Double,
        avgCost: Double,
        holdingShares: Int,
        params: StrategyParams.TakeProfit
    ): StrategyEvaluation {
        val gain = if (avgCost > 0.0) (price / avgCost - 1.0) * 100.0 else 0.0
        val gainText = (if (gain >= 0) "+" else "") + MoneyFormatter.amount(gain) + "%"
        val metrics = listOf(
            StrategyMetric("现价", MoneyFormatter.amount(price)),
            StrategyMetric("摊薄成本", if (avgCost > 0.0) MoneyFormatter.amount(avgCost) else "—"),
            StrategyMetric("成本涨幅", if (avgCost > 0.0) gainText else "—"),
            StrategyMetric("卖出一半涨幅", "+${MoneyFormatter.amount(params.halfGainPercent)}%"),
            StrategyMetric("清仓涨幅", "+${MoneyFormatter.amount(params.allGainPercent)}%"),
            StrategyMetric("当前持仓", "$holdingShares 股")
        )
        // 无成本/无持仓：涨幅语义不成立，展示持有态（不算数据不足）
        if (avgCost <= 0.0 || holdingShares <= 0) {
            return StrategyEvaluation(
                action = StrategyAction.HOLD,
                headline = if (holdingShares <= 0) "暂无持仓，仅跟踪" else "暂无成本数据",
                metrics = metrics
            )
        }
        return when {
            gain >= params.allGainPercent - DEVIATION_EPS -> StrategyEvaluation(
                action = StrategyAction.SELL_ALL,
                headline = "涨幅 +${MoneyFormatter.amount(gain)}% 达清仓线",
                metrics = metrics,
                sellShares = holdingShares,
                notifyTier = TIER_ALL
            )
            gain >= params.halfGainPercent - DEVIATION_EPS -> StrategyEvaluation(
                action = StrategyAction.SELL_HALF,
                headline = "涨幅 +${MoneyFormatter.amount(gain)}% 达卖出一半线",
                metrics = metrics,
                sellShares = lotShares(holdingShares / 2),
                notifyTier = TIER_HALF
            )
            else -> StrategyEvaluation(
                action = StrategyAction.HOLD,
                headline = "涨幅未达止盈线",
                metrics = metrics
            )
        }
    }
}

/** 股息率带：股息率 ≥ 加仓线提示加仓（金额与买入线同档，加倍买为后续规划）/ ≥ 买入线买入 / ≤ 卖出线清仓。 */
object YieldBandStrategyCalculator {

    fun evaluate(
        price: Double,
        dps: Double?,
        holdingShares: Int,
        buyAmount: Double,
        params: StrategyParams.YieldBand
    ): StrategyEvaluation? {
        if (dps == null || dps <= 0.0 || price <= 0.0) return null
        val yieldPercent = dps / price * 100.0
        val metrics = listOf(
            StrategyMetric("现价", MoneyFormatter.amount(price)),
            StrategyMetric("年每股分红", MoneyFormatter.withSymbol(dps, decimals = 4)),
            StrategyMetric("当前股息率", MoneyFormatter.amount(yieldPercent) + "%"),
            StrategyMetric("当前持仓", "$holdingShares 股"),
            StrategyMetric("买入线", MoneyFormatter.amount(params.buyYieldPercent) + "%"),
            StrategyMetric("加仓线", MoneyFormatter.amount(params.addYieldPercent) + "%"),
            StrategyMetric("卖出线", MoneyFormatter.amount(params.sellYieldPercent) + "%")
        )
        return when {
            yieldPercent >= params.addYieldPercent - DEVIATION_EPS -> buy(
                "股息率 ${MoneyFormatter.amount(yieldPercent)}% 达加仓线", price, buyAmount, metrics
            )
            yieldPercent >= params.buyYieldPercent - DEVIATION_EPS -> buy(
                "股息率 ${MoneyFormatter.amount(yieldPercent)}% 达买入线", price, buyAmount, metrics
            )
            yieldPercent <= params.sellYieldPercent + DEVIATION_EPS -> {
                // 无持仓仅跟踪（与 TakeProfit/DualMa 守卫一致），有持仓才给清仓信号与股数
                // （sellShares 供「卖出 N 股（一键记账）」按钮，恒 0 会导致按钮缺失——2026-08-24 评审修复）
                if (holdingShares <= 0) StrategyEvaluation(
                    action = StrategyAction.HOLD,
                    headline = "股息率 ${MoneyFormatter.amount(yieldPercent)}% 跌破卖出线（暂无持仓，仅跟踪）",
                    metrics = metrics
                ) else StrategyEvaluation(
                    action = StrategyAction.SELL_ALL,
                    headline = "股息率 ${MoneyFormatter.amount(yieldPercent)}% 跌破卖出线",
                    metrics = metrics,
                    sellShares = holdingShares,
                    notifyTier = TIER_ALL
                )
            }
            else -> StrategyEvaluation(
                action = StrategyAction.HOLD,
                headline = "股息率在带内",
                metrics = metrics
            )
        }
    }

    private fun buy(
        headline: String,
        price: Double,
        buyAmount: Double,
        metrics: List<StrategyMetric>
    ) = StrategyEvaluation(
        action = StrategyAction.BUY,
        headline = headline,
        metrics = metrics,
        buyShares = MaDcaStrategyCalculator.dcaBuyShares(buyAmount, price),
        buyAmount = buyAmount
    )
}

/** 双均线趋势：快线在慢线上方=多头（金叉后可持有/买入），下方=死叉卖出/回避。 */
object DualMaStrategyCalculator {

    /** 判定「刚发生交叉」的回看根数（新号提示用，不影响档位语义）。 */
    private const val FRESH_CROSS_BARS = 5

    fun evaluate(
        closes: List<Double>,
        holdingShares: Int,
        params: StrategyParams.DualMa
    ): StrategyEvaluation? {
        if (closes.size < params.slowPeriod + 1) return null
        if (!closes.all { it.isFinite() && it > 0.0 }) return null
        val fast = MaDcaStrategyCalculator.maSeries(closes, params.fastPeriod)
        val slow = MaDcaStrategyCalculator.maSeries(closes, params.slowPeriod)
        val lastIndex = closes.lastIndex
        val fastNow = fast[lastIndex] ?: return null
        val slowNow = slow[lastIndex] ?: return null
        val metrics = listOf(
            StrategyMetric("快线 MA${params.fastPeriod}", MoneyFormatter.amount(fastNow)),
            StrategyMetric("慢线 MA${params.slowPeriod}", MoneyFormatter.amount(slowNow)),
            StrategyMetric("当前持仓", "$holdingShares 股")
        )
        val bullish = fastNow > slowNow
        // 交叉新鲜度：回看 FRESH_CROSS_BARS 根内多空关系是否翻转
        val freshCross = (1..FRESH_CROSS_BARS).any { back ->
            val i = lastIndex - back
            i >= 0 && (fast[i] != null && slow[i] != null) &&
                ((fast[i]!! > slow[i]!!) != bullish)
        }
        return if (bullish) {
            StrategyEvaluation(
                action = StrategyAction.BUY,
                headline = if (freshCross) "金叉：快线刚上穿慢线" else "多头排列（快线在慢线上方）",
                metrics = metrics
            )
        } else if (holdingShares > 0) {
            StrategyEvaluation(
                action = StrategyAction.SELL_ALL,
                headline = if (freshCross) "死叉：快线刚下穿慢线，卖出" else "空头排列，死叉卖出",
                metrics = metrics,
                sellShares = holdingShares,
                notifyTier = TIER_ALL
            )
        } else {
            StrategyEvaluation(
                action = StrategyAction.HOLD,
                headline = "空头排列，观望回避",
                metrics = metrics
            )
        }
    }
}

/** 均线偏离回归：低于均线每 stepPercent 一档低吸（最深 buyLevels 档），回归均线卖出低吸部分。 */
object MaDeviationStrategyCalculator {

    fun evaluate(
        closes: List<Double>,
        currentPrice: Double,
        holdingShares: Int,
        buyAmount: Double,
        params: StrategyParams.MaDeviation
    ): StrategyEvaluation? {
        if (closes.size < params.maPeriod) return null
        if (!currentPrice.isFinite() || currentPrice <= 0.0) return null
        val ma = closes.takeLast(params.maPeriod).average()
        val deviation = (currentPrice / ma - 1.0) * 100.0
        val deviationText = (if (deviation >= 0) "+" else "") + MoneyFormatter.amount(deviation) + "%"
        val metrics = listOf(
            StrategyMetric("现价", MoneyFormatter.amount(currentPrice)),
            StrategyMetric("均线 MA${params.maPeriod}", MoneyFormatter.amount(ma)),
            StrategyMetric("偏离度", deviationText),
            StrategyMetric("步长", "-${MoneyFormatter.amount(params.stepPercent)}%/档 共 ${params.buyLevels} 档"),
            StrategyMetric("当前持仓", "$holdingShares 股")
        )
        return when {
            // 回归均线：卖出低吸部分（按持仓一半整手折算——无法精确拆分低吸仓位，保守提示）；
            // 无持仓仅跟踪（2026-08-24 评审修复：不再推「卖出 0 股」的 HALF 提醒）
            currentPrice >= ma -> {
                if (holdingShares <= 0) StrategyEvaluation(
                    action = StrategyAction.HOLD,
                    headline = "回归均线（暂无持仓，仅跟踪）",
                    metrics = metrics
                ) else StrategyEvaluation(
                    action = StrategyAction.SELL_HALF,
                    headline = "回归均线，卖出低吸部分",
                    metrics = metrics,
                    sellShares = lotShares(holdingShares / 2),
                    notifyTier = TIER_HALF
                )
            }
            deviation <= -params.stepPercent + DEVIATION_EPS -> {
                // 第 k 档 = 偏离 ≤ -k×step%（恰达计触发）：floor 取档，此前 toInt()+1 把恰达边界
                // 算作已过一档、档号系统性偏大 1（2026-08-24 评审修复）
                val level = kotlin.math.floor((-deviation) / params.stepPercent + DEVIATION_EPS).toInt()
                val clamped = level.coerceAtMost(params.buyLevels)
                val atDeepest = level > params.buyLevels
                StrategyEvaluation(
                    action = StrategyAction.BUY,
                    headline = if (atDeepest) {
                        "低于均线 ${MoneyFormatter.amount(-deviation)}%，已达最深第 ${params.buyLevels} 档"
                    } else {
                        "低于均线 ${MoneyFormatter.amount(-deviation)}%，第 $clamped 档低吸"
                    },
                    metrics = metrics,
                    buyShares = MaDcaStrategyCalculator.dcaBuyShares(buyAmount, currentPrice),
                    buyAmount = buyAmount
                )
            }
            else -> StrategyEvaluation(
                action = StrategyAction.HOLD,
                headline = "均线下方但未到第一档",
                metrics = metrics
            )
        }
    }
}

/** 价值平均法：目标市值 = 每期金额 ×（已过月数+1）；缺口补足买入、超额部分卖出。 */
object ValueAveragingStrategyCalculator {

    fun evaluate(
        price: Double,
        holdingShares: Int,
        monthsSinceStart: Long,
        params: StrategyParams.ValueAveraging
    ): StrategyEvaluation? {
        if (!price.isFinite() || price <= 0.0) return null
        val targetValue = params.perPeriodAmount * (monthsSinceStart + 1)
        val marketValue = holdingShares * price
        val metrics = listOf(
            StrategyMetric("现价", MoneyFormatter.amount(price)),
            StrategyMetric("目标市值", MoneyFormatter.withSymbol(targetValue)),
            StrategyMetric("当前市值", MoneyFormatter.withSymbol(marketValue)),
            StrategyMetric("当前持仓", "$holdingShares 股")
        )
        val gap = targetValue - marketValue
        if (gap > 0.0) {
            val shares = MaDcaStrategyCalculator.dcaBuyShares(gap, price)
            return if (shares > 0) StrategyEvaluation(
                action = StrategyAction.BUY,
                headline = "低于目标 ${MoneyFormatter.withSymbol(gap)}，补足买入",
                metrics = metrics,
                buyShares = shares,
                buyAmount = gap
            ) else StrategyEvaluation(
                action = StrategyAction.HOLD,
                headline = "缺口不足一手（${MoneyFormatter.withSymbol(gap)}）",
                metrics = metrics
            )
        }
        val excess = marketValue - targetValue
        val sellShares = lotShares((excess / price).toInt()).coerceAtMost(holdingShares)
        return if (sellShares > 0) StrategyEvaluation(
            action = StrategyAction.SELL_HALF,
            headline = "超出目标 ${MoneyFormatter.withSymbol(excess)}，卖出超额",
            metrics = metrics,
            sellShares = sellShares
        ) else StrategyEvaluation(
            action = StrategyAction.HOLD,
            headline = "市值贴合目标",
            metrics = metrics
        )
    }
}

/** 估值带：PE/PB 绝对阈值低买高卖（百分位需历史累积，暂不支持）。 */
object ValuationBandStrategyCalculator {

    fun evaluate(
        pe: Double?,
        pb: Double?,
        holdingShares: Int,
        params: StrategyParams.ValuationBand
    ): StrategyEvaluation? {
        val value = when (params.metric) {
            StrategyParams.VALUATION_METRIC_PB -> pb?.takeIf { it.isFinite() && it > 0.0 }
            else -> pe?.takeIf { it.isFinite() && it > 0.0 }
        } ?: return null   // 指标数据缺失（如 ETF 无 PE/PB）
        val metrics = listOf(
            StrategyMetric("${params.metric}（当前）", MoneyFormatter.amount(value)),
            StrategyMetric("低估值线", MoneyFormatter.amount(params.lowThreshold)),
            StrategyMetric("高估值线", MoneyFormatter.amount(params.highThreshold)),
            StrategyMetric("当前持仓", "$holdingShares 股")
        )
        return when {
            value <= params.lowThreshold + DEVIATION_EPS -> StrategyEvaluation(
                action = StrategyAction.BUY,
                headline = "${params.metric} ${MoneyFormatter.amount(value)} 处于低估区",
                metrics = metrics
            )
            value >= params.highThreshold - DEVIATION_EPS -> StrategyEvaluation(
                action = StrategyAction.SELL_ALL,
                headline = "${params.metric} ${MoneyFormatter.amount(value)} 处于高估区",
                metrics = metrics,
                sellShares = holdingShares,
                notifyTier = TIER_ALL
            )
            else -> StrategyEvaluation(
                action = StrategyAction.HOLD,
                headline = "${params.metric} 在估值带内",
                metrics = metrics
            )
        }
    }
}

/** 分红再投：近期除权到账金额按现价折整手提示再投入（只展示不推送）。 */
object DividendReinvestStrategyCalculator {

    fun evaluate(
        event: StrategyDividendEvent?,
        price: Double?,
        holdingShares: Int
    ): StrategyEvaluation? {
        if (price == null || !price.isFinite() || price <= 0.0) return null
        if (event == null) {
            return StrategyEvaluation(
                action = StrategyAction.HOLD,
                headline = "近期无除权安排",
                metrics = listOf(StrategyMetric("现价", MoneyFormatter.amount(price)))
            )
        }
        val amount = event.cashPerShare * holdingShares
        val dayText = when {
            event.daysAway <= 0L -> "今日"
            else -> "${event.daysAway} 天后"
        }
        val metrics = listOf(
            StrategyMetric("除权日", "${event.exDate}（$dayText）"),
            StrategyMetric("每股分红", MoneyFormatter.withSymbol(event.cashPerShare, decimals = 4)),
            StrategyMetric("预计到账", if (holdingShares > 0) MoneyFormatter.withSymbol(amount) else "—（无持仓）"),
            StrategyMetric("现价", MoneyFormatter.amount(price))
        )
        return if (holdingShares > 0) StrategyEvaluation(
            action = StrategyAction.BUY,
            headline = "${dayText}除权，到账约 ${MoneyFormatter.withSymbol(amount)} 可再投",
            metrics = metrics,
            buyShares = MaDcaStrategyCalculator.dcaBuyShares(amount, price),
            buyAmount = amount
        ) else StrategyEvaluation(
            action = StrategyAction.HOLD,
            headline = "${dayText}除权（无持仓，仅记录）",
            metrics = metrics
        )
    }
}

/** 股数按整手（100 股）向下折算。 */
private fun lotShares(shares: Int): Int =
    shares / MaDcaStrategyCalculator.LOT_SIZE * MaDcaStrategyCalculator.LOT_SIZE
