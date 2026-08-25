package com.stock.dividend.data.repository

import com.stock.dividend.data.local.entity.STRATEGY_TYPE_DIVIDEND_REINVEST
import com.stock.dividend.data.local.entity.STRATEGY_TYPE_DUAL_MA
import com.stock.dividend.data.local.entity.STRATEGY_TYPE_MA_DCA
import com.stock.dividend.data.local.entity.STRATEGY_TYPE_MA_DEVIATION
import com.stock.dividend.data.local.entity.STRATEGY_TYPE_TAKE_PROFIT
import com.stock.dividend.data.local.entity.STRATEGY_TYPE_VALUATION_BAND
import com.stock.dividend.data.local.entity.STRATEGY_TYPE_VALUE_AVERAGING
import com.stock.dividend.data.local.entity.STRATEGY_TYPE_YIELD_BAND
import com.stock.dividend.data.local.entity.StrategyPlanEntity

/**
 * 策略统一信号动作（跨类型收敛，UI 色板/今日页/通知共用）。
 *
 * - [BUY]：买入/定投/加仓窗口（只展示不推送，产品约定）；
 * - [HOLD]：持有/观望；
 * - [SELL_HALF]：部分卖出（卖一半/回归卖出/超额卖出），推送档位 HALF；
 * - [SELL_ALL]：清仓/死叉/跌破卖出线，推送档位 ALL。
 */
enum class StrategyAction { BUY, HOLD, SELL_HALF, SELL_ALL }

/** 策略卡片指标行（label + 已格式化 value）。 */
data class StrategyMetric(val label: String, val value: String)

/**
 * 统一策略评估结果（各类型计算器 / [StrategyEvaluator] 输出）。
 *
 * @param action 当前动作（pill/色板/推送判定用）。
 * @param headline 状态主标签（短句，如「年线定投窗口」「股息率达加仓线」）。
 * @param metrics 展示指标行（现价/均线/偏离度/股息率/成本涨幅等，已格式化）。
 * @param sellShares 建议卖出股数（整手折算；非卖出动作为 0）。
 * @param buyShares 建议买入股数（整手折算；无买入语义为 0）。
 * @param buyAmount 建议买入金额（元；定投/补足口径）。
 * @param notifyTier 推送档位（HALF/ALL；null = 不推送——买入方向按约定只展示）。
 */
data class StrategyEvaluation(
    val action: StrategyAction,
    val headline: String,
    val metrics: List<StrategyMetric> = emptyList(),
    val sellShares: Int = 0,
    val buyShares: Int = 0,
    val buyAmount: Double? = null,
    val notifyTier: String? = null
)

/** 近期除权事件（分红再投策略输入；daysAway ≤0 表示今日/已除权）。 */
data class StrategyDividendEvent(
    val exDate: String,
    val daysAway: Long,
    val cashPerShare: Double
)

/**
 * 策略评估输入（VM/协调器按各计划所需采集后传入；调度器按类型取用，纯函数无 IO）。
 */
data class StrategyInput(
    val currentPrice: Double? = null,
    val closes: List<Double> = emptyList(),
    val dps: Double? = null,
    val holdingShares: Int = 0,
    val avgCostPerShare: Double = 0.0,
    val valuationPeTtm: Double? = null,
    val valuationPbMrq: Double? = null,
    val nextDividend: StrategyDividendEvent? = null,
    /** 建计划至今的整月数（价值平均法目标市值 = 每期金额 × (月数+1)）。 */
    val monthsSinceStart: Long = 0
)

/**
 * 策略调度器（纯函数）：按 [StrategyPlanEntity.strategyType] 分发到对应计算器，
 * 输出统一 [StrategyEvaluation]。数据不足（收盘价/现价/DPS/估值缺失）返回 null。
 */
object StrategyEvaluator {

    /** 策略类型 → 用户可见名称。未知类型回退「自定义策略」。 */
    fun displayName(strategyType: String): String = when (strategyType) {
        STRATEGY_TYPE_MA_DCA -> "年线定投"
        STRATEGY_TYPE_TAKE_PROFIT -> "目标止盈"
        STRATEGY_TYPE_YIELD_BAND -> "股息率带"
        STRATEGY_TYPE_DUAL_MA -> "双均线趋势"
        STRATEGY_TYPE_MA_DEVIATION -> "均线偏离回归"
        STRATEGY_TYPE_VALUE_AVERAGING -> "价值平均法"
        STRATEGY_TYPE_VALUATION_BAND -> "估值带（PE/PB）"
        STRATEGY_TYPE_DIVIDEND_REINVEST -> "分红再投"
        else -> "自定义策略"
    }

    /** 该计划评估所需的日线收盘价根数（K 线采集用；0 = 不需要）。 */
    fun requiredCloses(plan: StrategyPlanEntity): Int = when (plan.strategyType) {
        STRATEGY_TYPE_MA_DCA -> plan.maPeriod
        STRATEGY_TYPE_DUAL_MA -> StrategyParams.decodeDualMa(plan.params).slowPeriod + 2
        STRATEGY_TYPE_MA_DEVIATION -> StrategyParams.decodeMaDeviation(plan.params).maPeriod
        else -> 0
    }

    fun evaluate(plan: StrategyPlanEntity, input: StrategyInput): StrategyEvaluation? {
        return when (plan.strategyType) {
        STRATEGY_TYPE_MA_DCA -> evaluateMaDca(plan, input)
        STRATEGY_TYPE_TAKE_PROFIT -> TakeProfitStrategyCalculator.evaluate(
            price = input.currentPrice ?: return null,
            avgCost = input.avgCostPerShare,
            holdingShares = input.holdingShares,
            params = StrategyParams.decodeTakeProfit(plan.params)
        )
        STRATEGY_TYPE_YIELD_BAND -> YieldBandStrategyCalculator.evaluate(
            price = input.currentPrice ?: return null,
            dps = input.dps,
            holdingShares = input.holdingShares,
            buyAmount = plan.dcaAmount,
            params = StrategyParams.decodeYieldBand(plan.params)
        )
        STRATEGY_TYPE_DUAL_MA -> DualMaStrategyCalculator.evaluate(
            closes = input.closes,
            holdingShares = input.holdingShares,
            params = StrategyParams.decodeDualMa(plan.params)
        )
        STRATEGY_TYPE_MA_DEVIATION -> MaDeviationStrategyCalculator.evaluate(
            closes = input.closes,
            currentPrice = input.currentPrice ?: return null,
            holdingShares = input.holdingShares,
            buyAmount = plan.dcaAmount,
            params = StrategyParams.decodeMaDeviation(plan.params)
        )
        STRATEGY_TYPE_VALUE_AVERAGING -> ValueAveragingStrategyCalculator.evaluate(
            price = input.currentPrice ?: return null,
            holdingShares = input.holdingShares,
            monthsSinceStart = input.monthsSinceStart,
            params = StrategyParams.decodeValueAveraging(plan.params)
        )
        STRATEGY_TYPE_VALUATION_BAND -> ValuationBandStrategyCalculator.evaluate(
            pe = input.valuationPeTtm,
            pb = input.valuationPbMrq,
            holdingShares = input.holdingShares,
            params = StrategyParams.decodeValuationBand(plan.params)
        )
        STRATEGY_TYPE_DIVIDEND_REINVEST -> DividendReinvestStrategyCalculator.evaluate(
            event = input.nextDividend,
            price = input.currentPrice,
            holdingShares = input.holdingShares
        )
        else -> null
        }
    }

    /** MA_DCA 适配：复用既有 [MaDcaStrategyCalculator]（含阈值容差与触发价），映射为统一评估。 */
    private fun evaluateMaDca(plan: StrategyPlanEntity, input: StrategyInput): StrategyEvaluation? {
        val price = input.currentPrice ?: return null
        val e = MaDcaStrategyCalculator.evaluate(
            closes = input.closes,
            currentPrice = price,
            maPeriod = plan.maPeriod,
            sellHalfPercent = plan.sellHalfPercent,
            sellAllPercent = plan.sellAllPercent
        ) ?: return null
        val deviationText = (if (e.deviationPercent >= 0) "+" else "") +
            MoneyFormatter.amount(e.deviationPercent) + "%"
        val metrics = listOf(
            StrategyMetric("现价", MoneyFormatter.amount(price)),
            StrategyMetric("年线 MA${plan.maPeriod}", MoneyFormatter.amount(e.maValue)),
            StrategyMetric("偏离度", deviationText),
            StrategyMetric("卖出一半触发价", MoneyFormatter.amount(e.sellHalfTriggerPrice)),
            StrategyMetric("清仓触发价", MoneyFormatter.amount(e.sellAllTriggerPrice)),
            StrategyMetric("当前持仓", "${input.holdingShares} 股")
        )
        return when (e.signal) {
            MaDcaSignal.DCA_WINDOW -> StrategyEvaluation(
                action = StrategyAction.BUY,
                headline = "年线定投窗口",
                metrics = metrics,
                buyShares = MaDcaStrategyCalculator.dcaBuyShares(plan.dcaAmount, price),
                buyAmount = plan.dcaAmount
            )
            MaDcaSignal.HOLD -> StrategyEvaluation(
                action = StrategyAction.HOLD,
                headline = "年线上方 · 未达卖出阈值",
                metrics = metrics
            )
            MaDcaSignal.SELL_HALF -> StrategyEvaluation(
                action = StrategyAction.SELL_HALF,
                headline = "高于年线 ${trimNum(plan.sellHalfPercent)}% · 卖出一半",
                metrics = metrics,
                sellShares = MaDcaStrategyCalculator.sellSharesFor(e.signal, input.holdingShares),
                notifyTier = "HALF"
            )
            MaDcaSignal.SELL_ALL -> StrategyEvaluation(
                action = StrategyAction.SELL_ALL,
                headline = "高于年线 ${trimNum(plan.sellAllPercent)}% · 清仓",
                metrics = metrics,
                sellShares = input.holdingShares,
                notifyTier = "ALL"
            )
        }
    }

    private fun trimNum(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
}
