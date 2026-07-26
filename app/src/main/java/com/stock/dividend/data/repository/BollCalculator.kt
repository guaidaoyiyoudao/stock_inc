package com.stock.dividend.data.repository

import kotlin.math.sqrt

/**
 * BOLL 带计算结果。
 *
 * @param middle 中轨 = 最近 [period] 根收盘价的简单移动平均（周线 BOLL 取 20 周）。
 * @param upper  上轨 = 中轨 + [stdDevMult] × 样本标准差。
 * @param lower  下轨 = 中轨 - [stdDevMult] × 样本标准差。
 */
data class BollBand(
    val middle: Double,
    val upper: Double,
    val lower: Double,
    val period: Int = DEFAULT_PERIOD,
    val stdDevMult: Double = DEFAULT_STD_DEV_MULT
) {
    companion object {
        const val DEFAULT_PERIOD = 20
        const val DEFAULT_STD_DEV_MULT = 2.0
    }
}

/**
 * BOLL 带纯函数计算（无 Android 依赖，便于单测）。
 *
 * 标准定义：取最近 [period] 根收盘价，中轨=MA，上下轨=MA ± [mult]·σ（样本标准差，分母 n-1）。
 *
 * - 收盘价不足 [period] 根、含非正数或非有限值 → 返回 null（数据不足，不强行算）。
 * - σ=0（价格完全相同）时上下轨=中轨，仍返回有效结果（极端但合法）。
 */
object BollCalculator {

    fun calculate(
        closes: List<Double>,
        period: Int = BollBand.DEFAULT_PERIOD,
        mult: Double = BollBand.DEFAULT_STD_DEV_MULT
    ): BollBand? {
        require(period > 1) { "period must be > 1: $period" }
        require(mult > 0.0) { "stdDevMult must be > 0: $mult" }
        if (closes.size < period) return null

        val window = closes.takeLast(period)
        if (window.any { !it.isFinite() || it <= 0.0 }) return null

        val middle = window.sum() / period
        val variance = window.sumOf { (it - middle) * (it - middle) } / (period - 1)
        val sigma = sqrt(variance)
        return BollBand(
            middle = middle,
            upper = middle + mult * sigma,
            lower = middle - mult * sigma,
            period = period,
            stdDevMult = mult
        )
    }
}
