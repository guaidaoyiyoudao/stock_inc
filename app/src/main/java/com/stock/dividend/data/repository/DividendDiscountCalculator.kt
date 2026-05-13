package com.stock.dividend.data.repository

import com.stock.dividend.data.local.entity.DividendEntity
import kotlin.math.pow

data class DividendDiscountInput(
    val dividendBasisPerShare: Double,
    val dividendGrowthRate: Double,
    val discountRate: Double,
    val terminalGrowthRate: Double,
    val projectionYears: Int,
    val marginOfSafety: Double,
    val currentPrice: Double?
)

data class DividendBasisResult(
    val averageCashPerShare: Double,
    val actualYears: Int
)

data class DividendCashFlowRow(
    val year: Int,
    val projectedDividend: Double,
    val discountedDividend: Double
)

enum class DividendValuationStatus {
    UNDERVALUED,
    OVERVALUED,
    FAIR,
    NO_MARKET_PRICE,
    INVALID
}

data class DividendDiscountResult(
    val projectionYears: Int,
    val intrinsicValuePerShare: Double,
    val currentPrice: Double?,
    val discountOrPremiumPercent: Double?,
    val safetyBuyPrice: Double,
    val valuationStatus: DividendValuationStatus,
    val cashFlowRows: List<DividendCashFlowRow>,
    val terminalValue: Double,
    val discountedTerminalValue: Double,
    val validationError: String?
)

object DividendDiscountCalculator {
    fun deriveDividendBasis(dividends: List<DividendEntity>): DividendBasisResult? {
        val yearlyCash = dividends
            .filter { it.cashPerShare > 0.0 && it.reportDate.length >= 4 }
            .groupBy { it.reportDate.substring(0, 4) }
            .mapValues { (_, rows) -> rows.sumOf { it.cashPerShare } }
            .toList()
            .sortedByDescending { it.first }
            .take(5)

        if (yearlyCash.isEmpty()) return null

        return DividendBasisResult(
            averageCashPerShare = yearlyCash.sumOf { it.second } / yearlyCash.size,
            actualYears = yearlyCash.size
        )
    }

    fun calculate(input: DividendDiscountInput): DividendDiscountResult {
        val years = input.projectionYears.coerceIn(1, 30)
        val currentPrice = input.currentPrice?.takeIf { it > 0.0 }

        if (input.discountRate <= input.terminalGrowthRate) {
            return invalid(years, currentPrice, "折现率必须大于终值增长率")
        }
        if (input.dividendBasisPerShare < 0.0) {
            return invalid(years, currentPrice, "股息基准不能为负数")
        }

        val rows = (1..years).map { year ->
            val projectedDividend = input.dividendBasisPerShare * (1.0 + input.dividendGrowthRate).pow(year)
            DividendCashFlowRow(
                year = year,
                projectedDividend = projectedDividend,
                discountedDividend = projectedDividend / (1.0 + input.discountRate).pow(year)
            )
        }
        val finalDividend = rows.last().projectedDividend
        val terminalValue = finalDividend * (1.0 + input.terminalGrowthRate) /
            (input.discountRate - input.terminalGrowthRate)
        val discountedTerminalValue = terminalValue / (1.0 + input.discountRate).pow(years)
        val intrinsicValue = rows.sumOf { it.discountedDividend } + discountedTerminalValue
        val safetyBuyPrice = intrinsicValue * (1.0 - input.marginOfSafety.coerceIn(0.0, 0.5))
        val comparison = currentPrice?.let { (intrinsicValue - it) / it }

        return DividendDiscountResult(
            projectionYears = years,
            intrinsicValuePerShare = intrinsicValue,
            currentPrice = currentPrice,
            discountOrPremiumPercent = comparison,
            safetyBuyPrice = safetyBuyPrice,
            valuationStatus = when {
                comparison == null -> DividendValuationStatus.NO_MARKET_PRICE
                comparison > 0.05 -> DividendValuationStatus.UNDERVALUED
                comparison < -0.05 -> DividendValuationStatus.OVERVALUED
                else -> DividendValuationStatus.FAIR
            },
            cashFlowRows = rows,
            terminalValue = terminalValue,
            discountedTerminalValue = discountedTerminalValue,
            validationError = null
        )
    }

    private fun invalid(
        years: Int,
        currentPrice: Double?,
        message: String
    ) = DividendDiscountResult(
        projectionYears = years,
        intrinsicValuePerShare = 0.0,
        currentPrice = currentPrice,
        discountOrPremiumPercent = null,
        safetyBuyPrice = 0.0,
        valuationStatus = DividendValuationStatus.INVALID,
        cashFlowRows = emptyList(),
        terminalValue = 0.0,
        discountedTerminalValue = 0.0,
        validationError = message
    )
}
