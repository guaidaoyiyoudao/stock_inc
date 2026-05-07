package com.stock.dividend.viewmodel

enum class ExpensePeriod {
    MONTHLY,
    YEARLY
}

enum class CoverageStatus {
    COVERED,
    PARTIAL,
    UNCOVERED
}

data class CoverageExpenseInput(
    val id: Long,
    val name: String,
    val amount: Double,
    val period: ExpensePeriod,
    val sortOrder: Int
)

data class ExpenseCoverageRow(
    val id: Long,
    val name: String,
    val amount: Double,
    val period: ExpensePeriod,
    val annualAmount: Double,
    val coveredAmount: Double,
    val gapAmount: Double,
    val status: CoverageStatus,
    val sortOrder: Int
)

data class ExpenseCoverageCalculation(
    val forecastAnnualDividendIncome: Double,
    val totalAnnualExpense: Double,
    val coverageRatio: Double,
    val coveredItemCount: Int,
    val currentCoveringItemName: String?,
    val remainingSurplus: Double,
    val rows: List<ExpenseCoverageRow>
)

object ExpenseCoverageCalculator {
    fun calculate(
        forecastAnnualDividendIncome: Double,
        items: List<CoverageExpenseInput>
    ): ExpenseCoverageCalculation {
        var remainingIncome = forecastAnnualDividendIncome.coerceAtLeast(0.0)
        var currentCoveringItemName: String? = null

        val rows = items
            .sortedWith(compareBy<CoverageExpenseInput> { it.sortOrder }.thenBy { it.id })
            .map { item ->
                val annualAmount = item.annualAmount()
                val coveredAmount = remainingIncome.coerceAtMost(annualAmount)
                val gapAmount = annualAmount - coveredAmount
                val status = when {
                    annualAmount <= 0.0 -> CoverageStatus.COVERED
                    coveredAmount >= annualAmount -> CoverageStatus.COVERED
                    coveredAmount > 0.0 -> CoverageStatus.PARTIAL
                    else -> CoverageStatus.UNCOVERED
                }
                if (status == CoverageStatus.PARTIAL && currentCoveringItemName == null) {
                    currentCoveringItemName = item.name
                }
                remainingIncome = (remainingIncome - coveredAmount).coerceAtLeast(0.0)
                ExpenseCoverageRow(
                    id = item.id,
                    name = item.name,
                    amount = item.amount,
                    period = item.period,
                    annualAmount = annualAmount,
                    coveredAmount = coveredAmount,
                    gapAmount = gapAmount,
                    status = status,
                    sortOrder = item.sortOrder
                )
            }

        val totalAnnualExpense = rows.sumOf { it.annualAmount }
        val coveredItemCount = rows.count { it.status == CoverageStatus.COVERED }
        val ratio = if (totalAnnualExpense > 0.0) {
            (forecastAnnualDividendIncome / totalAnnualExpense).coerceIn(0.0, 1.0)
        } else {
            0.0
        }

        return ExpenseCoverageCalculation(
            forecastAnnualDividendIncome = forecastAnnualDividendIncome,
            totalAnnualExpense = totalAnnualExpense,
            coverageRatio = ratio,
            coveredItemCount = coveredItemCount,
            currentCoveringItemName = currentCoveringItemName,
            remainingSurplus = remainingIncome,
            rows = rows
        )
    }

    private fun CoverageExpenseInput.annualAmount(): Double =
        when (period) {
            ExpensePeriod.MONTHLY -> amount * 12
            ExpensePeriod.YEARLY -> amount
        }
}
