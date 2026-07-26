package com.stock.dividend.data.repository

import kotlin.math.abs

/**
 * 周线 BOLL 带 → 价位 tone。与 [com.stock.dividend.ui.component.BollPriceScale] 共用，
 * 保证评估逻辑和卡片渲染用同一套判断。
 */
enum class BollTone { Buy, Current, Sell }

/**
 * 评估最终建议动作。
 * - [BUY] / [SELL] / [HOLD]：基于 boll 位置 + 股息率门槛得出的结论；
 * - [INSUFFICIENT_DATA]：boll 数据或现价无效，无法评估。
 */
enum class HoldingAction { BUY, SELL, HOLD, INSUFFICIENT_DATA }

/**
 * 评估用的股息率门槛（百分比）。
 * @param minYieldPercent 低于此值时不给"买"（即使 boll 在下轨）。
 * @param boostYieldPercent 高于此值时把中轨附近的"持有"上调为"买"。
 */
data class DividendThresholds(
    val minYieldPercent: Double = DEFAULT_MIN_YIELD,
    val boostYieldPercent: Double = DEFAULT_BOOST_YIELD
) {
    companion object {
        const val DEFAULT_MIN_YIELD = 2.0
        const val DEFAULT_BOOST_YIELD = 5.0
    }
}

/**
 * 单股评估结果（纯数据，UI 据此渲染）。
 */
data class HoldingRecommendation(
    val action: HoldingAction,
    val bollTone: BollTone,
    /** (price - lower) / (upper - lower)：0=下轨, 1=上轨。band 无效时为 NaN。 */
    val priceVsLower: Double,
    /** 股息率 %；latestYearlyDividend 或 price 无效时为 null。 */
    val dividendYield: Double?,
    /** 人话理由（每条 < 30 字），供结果页直接展示。 */
    val reasons: List<String>
)

/**
 * 持仓评估纯函数（无 Android 依赖）。
 *
 * 决策步骤：
 * 1. band/price 无效 → [HoldingAction.INSUFFICIENT_DATA]；
 * 2. 基础 tone 由 [bollTone] 决定（沿用 BollPriceScale 既有逻辑）；
 * 3. 股息率软门槛（仅当 latestYearlyDividend 非空时应用）：
 *    - tone=Buy 且 yield < minYield → 降级 HOLD；
 *    - tone=Current 且 yield ≥ boostYield → 升级 BUY；
 *    - SELL 不受股息率影响。
 */
object HoldingRecommender {

    fun recommend(
        price: Double,
        band: BollBand?,
        latestYearlyDividend: Double?,
        thresholds: DividendThresholds = DividendThresholds()
    ): HoldingRecommendation {
        if (band == null || !price.isFinite() || price <= 0.0) {
            return HoldingRecommendation(
                action = HoldingAction.INSUFFICIENT_DATA,
                bollTone = BollTone.Current,
                priceVsLower = Double.NaN,
                dividendYield = null,
                reasons = listOf(if (band == null) "周线 boll 数据不足" else "无有效现价")
            )
        }
        val tone = bollTone(price, band.upper, band.middle, band.lower)
        val span = (band.upper - band.lower).takeIf { it > 0.0 } ?: 1.0
        val priceVsLower = ((price - band.lower) / span).coerceIn(0.0, 1.0)
        val yieldPct = if (latestYearlyDividend != null && latestYearlyDividend > 0.0) {
            latestYearlyDividend / price * 100.0
        } else null

        val reasons = mutableListOf<String>()
        reasons += bollPositionReason(tone, priceVsLower)

        var action = when (tone) {
            BollTone.Buy -> HoldingAction.BUY
            BollTone.Sell -> HoldingAction.SELL
            BollTone.Current -> HoldingAction.HOLD
        }

        if (yieldPct != null) {
            // 降级：在下轨但股息率偏低
            if (tone == BollTone.Buy && yieldPct < thresholds.minYieldPercent) {
                action = HoldingAction.HOLD
                reasons += "股息率偏低 (${formatYield(yieldPct)}%)"
            }
            // 升级：中轨附近但股息率较高
            else if (tone == BollTone.Current && yieldPct >= thresholds.boostYieldPercent) {
                action = HoldingAction.BUY
                reasons += "股息率较高 (${formatYield(yieldPct)}%)"
            }
        }

        return HoldingRecommendation(
            action = action,
            bollTone = tone,
            priceVsLower = priceVsLower,
            dividendYield = yieldPct,
            reasons = reasons
        )
    }

    /**
     * boll 位置 → tone（与原 BollPriceScale.bollTone 逻辑完全一致，确保评估与卡片渲染同源）。
     * - price <= lower → Buy
     * - price >= upper → Sell
     * - 否则按到中轨的偏离：dev < 0.30 → Current；偏低 → Buy；偏高 → Sell。
     */
    fun bollTone(price: Double, upper: Double, middle: Double, lower: Double): BollTone {
        if (price <= lower) return BollTone.Buy
        if (price >= upper) return BollTone.Sell
        val halfSpan = ((upper - lower) / 2.0).takeIf { it > 0.0 } ?: return BollTone.Current
        val dev = abs(price - middle) / halfSpan
        return when {
            dev < 0.30 -> BollTone.Current
            price < middle -> BollTone.Buy
            else -> BollTone.Sell
        }
    }

    private fun bollPositionReason(tone: BollTone, priceVsLower: Double): String {
        val pct = (priceVsLower * 100).toInt()
        return when (tone) {
            BollTone.Buy -> "价格接近下轨 (${pct}%)"
            BollTone.Sell -> "价格接近上轨 (${pct}%)"
            BollTone.Current -> "价格在中轨附近 (${pct}%)"
        }
    }

    private fun formatYield(y: Double): String = "%.1f".format(y)
}
