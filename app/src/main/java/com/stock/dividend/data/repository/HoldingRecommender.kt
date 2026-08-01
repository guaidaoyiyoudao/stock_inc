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
 * 1. weekly band/price 无效 → [HoldingAction.INSUFFICIENT_DATA]；
 * 2. SELL：周线 tone=Sell（价格在上轨附近）→ 直接卖，不受股息率/多周期影响；
 * 3. BUY：三周期共振 —— 日下轨 + 周下轨 + 月中轨及以下 同时成立
 *    （price ≤ daily.lower 且 price ≤ weekly.lower 且 price ≤ monthly.middle），
 *    且股息率达 minYield（若提供股息数据）；不满足共振或股息率偏低则按周线 tone 落到持有/观望；
 * 4. 其余（中轨、仅单一周期在下轨等）→ HOLD。
 *
 * 注意：周线 tone 仍用于 UI 卡片落点高亮（[bollTone] / [BollPriceScale]），
 * 但 BUY/SELL 动作已由上面的多周期规则接管。
 */
object HoldingRecommender {

    fun recommend(
        price: Double,
        band: BollBand?,
        latestYearlyDividend: Double?,
        thresholds: DividendThresholds = DividendThresholds(),
        /** 日线 BOLL（一键评估时一并拉取）。null 表示未提供/数据不足。 */
        dailyBand: BollBand? = null,
        /** 月线 BOLL（一键评估时一并拉取）。null 表示未提供/数据不足。 */
        monthlyBand: BollBand? = null
    ): HoldingRecommendation {
        // 周线 band 是评估基准，缺失直接判数据不足（与历史行为一致）
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

        // 卖出：周线上轨附近，最高优先级，不受股息率/多周期影响
        if (tone == BollTone.Sell) {
            return HoldingRecommendation(
                action = HoldingAction.SELL,
                bollTone = tone,
                priceVsLower = priceVsLower,
                dividendYield = yieldPct,
                reasons = reasons
            )
        }

        // 买入：日下轨 + 周下轨 + 月中轨及以下 共振
        val dailyAtLower = dailyBand != null && price <= dailyBand.lower
        val weeklyAtLower = price <= band.lower
        val monthlyAtOrBelowMiddle = monthlyBand != null && price <= monthlyBand.middle
        // 共振三条件中「周下轨」已由 price<=band.lower 给出；日/月任一缺失则视为该周期不成立
        val resonant = dailyAtLower && weeklyAtLower && monthlyAtOrBelowMiddle

        when {
            resonant && yieldPct != null && yieldPct < thresholds.minYieldPercent -> {
                reasons += "三周期共振但股息率偏低 (${formatYield(yieldPct)}%)"
            }
            resonant -> {
                reasons += "日下轨+周下轨+月中轨及以下 三周期共振"
                return HoldingRecommendation(
                    action = HoldingAction.BUY,
                    bollTone = tone,
                    priceVsLower = priceVsLower,
                    dividendYield = yieldPct,
                    reasons = reasons
                )
            }
            // 价格已偏低（周下轨）但日/月未共振 → 提示数据不足或未到共振
            tone == BollTone.Buy && (dailyBand == null || monthlyBand == null) -> {
                reasons += "周线已偏低，但日/月数据不足，暂不给买"
            }
            tone == BollTone.Buy -> {
                reasons += "仅单一周期偏低，未达三周期共振"
            }
        }

        return HoldingRecommendation(
            action = HoldingAction.HOLD,
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
