package com.stock.dividend.data.repository

import com.stock.dividend.data.local.entity.DividendEntity
import kotlin.math.sqrt

/**
 * 分红深度指标（纯函数，无 Android 依赖，便于单测）。
 *
 * 与 [ForecastCalculator] 同口径：按 reportDate 年份分组、sumOf cashPerShare，过滤 ≤0 的年份。
 * 各字段缺失样本不足时返回 null（不臆造）。
 *
 * @property totalYears          有分红记录的年份数（>0 的年份）。
 * @property consecutiveYears    截至最新年份的连续分红年数（中间断档即停）。
 * @property avgCashPerShare3y   近 3 年每股分红均值（复用 [ForecastCalculator.calculateAvgCashPerShare]）。
 * @property avgCashPerShare5y   近 5 年每股分红均值。
 * @property cagr3y              近 3 年每股分红复合年增长率（%，端点>0 且年数≥2 才计算）。
 * @property stdDev              近 5 年每股分红标准差（衡量波动，元）。
 * @property coefficientOfVariation 近 5 年变异系数（标准差/均值，无量纲衡量稳定性，越小越稳）。
 * @property latestYear          最新一个有分红的年份。
 */
data class DividendMetrics(
    val totalYears: Int,
    val consecutiveYears: Int,
    val avgCashPerShare3y: Double?,
    val avgCashPerShare5y: Double?,
    val cagr3y: Double?,
    val stdDev: Double?,
    val coefficientOfVariation: Double?,
    val latestYear: String?
)

object DividendMetricsCalculator {

    /**
     * 计算分红深度指标。
     *
     * @param dividends 历史分红记录（reportDate + cashPerShare）
     * @return 指标对象；无任何 >0 的分红记录返回 null
     */
    fun calculate(dividends: List<DividendEntity>): DividendMetrics? {
        if (dividends.isEmpty()) return null

        // 年份 → 年度每股分红合计（过滤 ≤0 的年份）
        val yearly: Map<String, Double> = dividends
            .filter { it.reportDate.isNotBlank() && it.cashPerShare > 0.0 }
            .groupBy { it.reportDate.substringBefore("-") }
            .mapValues { (_, records) -> records.sumOf { it.cashPerShare } }
            .filterValues { it > 0.0 }

        if (yearly.isEmpty()) return null

        val sortedYears = yearly.keys.sorted()
        val totalYears = sortedYears.size
        val latestYear = sortedYears.last()
        val consecutiveYears = countConsecutive(sortedYears)

        val avg3y = ForecastCalculator.calculateAvgCashPerShare(dividends, 3)?.avgCashPerShare
        val avg5y = ForecastCalculator.calculateAvgCashPerShare(dividends, 5)?.avgCashPerShare

        val cagr3y = calcCagr(yearly, sortedYears, years = 3)
        val recent5 = sortedYears.takeLast(5).mapNotNull { yearly[it] }
        val stdDev = if (recent5.size >= 2) stddev(recent5) else null
        val cv = if (stdDev != null && avg5y != null && avg5y > 0.0) stdDev / avg5y else null

        return DividendMetrics(
            totalYears = totalYears,
            consecutiveYears = consecutiveYears,
            avgCashPerShare3y = avg3y,
            avgCashPerShare5y = avg5y,
            cagr3y = cagr3y,
            stdDev = stdDev,
            coefficientOfVariation = cv,
            latestYear = latestYear
        )
    }

    /**
     * 从最新年份往回数，连续的年份数（年份连续即 +1，遇断档停）。
     */
    private fun countConsecutive(sortedYears: List<String>): Int {
        if (sortedYears.isEmpty()) return 0
        var count = 1
        var prev = sortedYears.last().toIntOrNull() ?: return 1
        for (i in sortedYears.size - 2 downTo 0) {
            val cur = sortedYears[i].toIntOrNull() ?: break
            if (prev - cur == 1) {
                count++
                prev = cur
            } else break
        }
        return count
    }

    /**
     * CAGR（复合年增长率，%）。
     * 取窗口首末年份的年度分红，复利公式 (end/start)^(1/n) - 1。
     * 要求首末均 >0 且窗口内年数 ≥2。
     */
    private fun calcCagr(
        yearly: Map<String, Double>,
        sortedYears: List<String>,
        years: Int
    ): Double? {
        val window = sortedYears.takeLast(years)
        if (window.size < 2) return null
        val start = yearly[window.first()] ?: return null
        val end = yearly[window.last()] ?: return null
        if (start <= 0.0 || end <= 0.0) return null
        val n = window.last().toInt() - window.first().toInt()
        if (n <= 0) return null
        val ratio = end / start
        if (ratio <= 0.0) return null
        return (Math.pow(ratio, 1.0 / n) - 1) * 100
    }

    private fun stddev(values: List<Double>): Double {
        val n = values.size
        if (n < 2) return 0.0
        val mean = values.sum() / n
        val variance = values.sumOf { (it - mean) * (it - mean) } / (n - 1)
        return sqrt(variance)
    }
}
