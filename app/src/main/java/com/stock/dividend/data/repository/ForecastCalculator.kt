package com.stock.dividend.data.repository

import com.stock.dividend.data.local.entity.DividendEntity

object ForecastCalculator {

    data class ForecastResult(
        val avgCashPerShare: Double,
        val actualYears: Int
    )

    fun calculateAvgCashPerShare(
        dividends: List<DividendEntity>,
        years: Int
    ): ForecastResult? {
        if (dividends.isEmpty() || years <= 0) return null

        val yearlyData = dividends
            .groupBy { it.reportDate.substringBefore("-") }
            .mapValues { (_, records) -> records.sumOf { it.cashPerShare } }
            .entries
            .sortedByDescending { it.key }
            .take(years)
            .filter { it.value > 0 }

        if (yearlyData.isEmpty()) return null

        val avgCashPerShare = yearlyData.sumOf { it.value } / yearlyData.size
        return ForecastResult(avgCashPerShare, yearlyData.size)
    }

    fun calculateForecastIncome(
        dividends: List<DividendEntity>,
        shares: Int,
        years: Int
    ): ForecastResult? {
        if (shares <= 0) return null
        val result = calculateAvgCashPerShare(dividends, years) ?: return null
        return result
    }
}
