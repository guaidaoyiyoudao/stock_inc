package com.stock.dividend.data.repository

import androidx.compose.runtime.Stable

/**
 * 买入阈值判定结果（用于股息率图表叠加阈值线 + 文字提示）。
 *
 * 逻辑：当「现价股息率 ≥ 10Y 国债收益率 × 倍数」时判定为达到买入线。
 * - [currentYieldPercent] 为 null 时（缺现价/缺分红数据）[reached] 也为 null，仅展示买入线数值。
 */
@Stable
data class BuyThresholdStatus(
    /** 10 年期国债到期收益率（%），如 2.6 表示 2.6%。 */
    val bondYield10Y: Double,
    /** 标的专属倍数，如中国移动 2.5。 */
    val multiplier: Double,
    /** 目标买入股息率（%）= bondYield10Y × multiplier。 */
    val targetYieldPercent: Double,
    /** 当前现价股息率（%），数据不全时为 null。 */
    val currentYieldPercent: Double?,
    /** 是否达到买入线；数据不全时为 null。 */
    val reached: Boolean?
)

/**
 * 计算买入阈值判定。纯函数，便于单测。
 *
 * @param bondYield10Y 10Y 国债收益率（%）
 * @param multiplier 标的倍数
 * @param latestYearlyCashPerShare 最近一年每股分红（元），可为空
 * @param currentPrice 现价（元），可为空
 */
fun computeBuyThreshold(
    bondYield10Y: Double,
    multiplier: Double,
    latestYearlyCashPerShare: Double?,
    currentPrice: Double?
): BuyThresholdStatus {
    val safeBond = if (bondYield10Y.isFinite() && bondYield10Y > 0.0) bondYield10Y else 0.0
    val safeMultiplier = when {
        !multiplier.isFinite() || multiplier <= 0.0 -> 0.0
        else -> multiplier
    }
    val target = safeBond * safeMultiplier

    val current = when {
        latestYearlyCashPerShare == null || currentPrice == null ||
                !latestYearlyCashPerShare.isFinite() || !currentPrice.isFinite() ||
                currentPrice <= 0.0 -> null
        else -> latestYearlyCashPerShare / currentPrice * 100.0
    }
    val reached = current?.let { it >= target }
    return BuyThresholdStatus(
        bondYield10Y = safeBond,
        multiplier = safeMultiplier,
        targetYieldPercent = target,
        currentYieldPercent = current,
        reached = reached
    )
}
