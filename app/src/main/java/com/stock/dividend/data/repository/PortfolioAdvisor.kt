package com.stock.dividend.data.repository

/** 策略参数（先硬编码默认值，后续可做设置项）。 */
data class PortfolioAdvisorConfig(
    val minUpperBandRatio: Double = 0.5,
    val maxAvgDividendYield: Double = 2.0,
    val upperProximityThreshold: Double = 0.9,
    val targetCashPercent: Int = 15,
)

data class PositionControlSignal(
    val triggered: Boolean,
    val upperBandRatio: Double,
    val avgDividendYield: Double,
    val targetCashPercent: Int,
)

data class MultiTimeframeBuySignal(
    val code: String,
    val dailyAtLower: Boolean,
    val weeklyAtLower: Boolean,
    val monthlyBelowMiddle: Boolean,
    val resonant: Boolean,
)

data class PortfolioSignals(
    val positionControl: PositionControlSignal,
    val buySignals: List<MultiTimeframeBuySignal>,
)

/**
 * 组合策略信号（纯函数，无 Android 依赖）。
 *
 * 仓位控制：上轨占比 ≥ [PortfolioAdvisorConfig.minUpperBandRatio] 且
 *   平均股息率 < [PortfolioAdvisorConfig.maxAvgDividendYield] → 触发，建议现金 ≥ targetCashPercent。
 * 三周期共振：日下轨 + 周下轨 + 月中轨以下 同时成立。周线取自 [EvaluatedStock.bollBand]，
 *   日/月取自传入的 band map；任一周期数据缺失则该股跳过。
 */
object PortfolioAdvisor {

    fun evaluate(
        evaluatedStocks: List<EvaluatedStock>,
        dailyBands: Map<String, BollBand?>,
        monthlyBands: Map<String, BollBand?>,
        config: PortfolioAdvisorConfig = PortfolioAdvisorConfig(),
    ): PortfolioSignals {
        val positionControl = computePositionControl(evaluatedStocks, config)
        val buySignals = evaluatedStocks.mapNotNull { stock ->
            val price = stock.currentPrice
            val daily = dailyBands[stock.code]
            val weekly = stock.bollBand
            val monthly = monthlyBands[stock.code]
            if (price == null || price <= 0.0 || daily == null || weekly == null || monthly == null) {
                null
            } else {
                val dailyAtLower = price <= daily.lower
                val weeklyAtLower = price <= weekly.lower
                val monthlyBelowMiddle = price < monthly.middle
                val resonant = dailyAtLower && weeklyAtLower && monthlyBelowMiddle
                if (resonant) MultiTimeframeBuySignal(stock.code, dailyAtLower, weeklyAtLower, monthlyBelowMiddle, resonant)
                else null
            }
        }
        return PortfolioSignals(positionControl, buySignals)
    }

    private fun computePositionControl(
        stocks: List<EvaluatedStock>,
        config: PortfolioAdvisorConfig
    ): PositionControlSignal {
        if (stocks.isEmpty()) return PositionControlSignal(false, 0.0, 0.0, config.targetCashPercent)
        val upperCount = stocks.count { it.priceVsLower.isFinite() && it.priceVsLower >= config.upperProximityThreshold }
        val upperBandRatio = upperCount.toDouble() / stocks.size
        val yields = stocks.mapNotNull { it.dividendYield?.takeIf { y -> y.isFinite() } }
        val avgYield = if (yields.isNotEmpty()) yields.average() else 0.0
        val triggered = upperBandRatio >= config.minUpperBandRatio && avgYield < config.maxAvgDividendYield
        return PositionControlSignal(triggered, upperBandRatio, avgYield, config.targetCashPercent)
    }
}
