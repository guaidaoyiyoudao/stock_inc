package com.stock.dividend.viewmodel

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ExpenseCoverageCalculatorTest {

    @Test
    fun `monthly item annualizes by multiplying by twelve`() {
        val item = CoverageExpenseInput(
            id = 1,
            name = "房租",
            amount = 3000.0,
            period = ExpensePeriod.MONTHLY,
            sortOrder = 0
        )

        val result = ExpenseCoverageCalculator.calculate(50_000.0, listOf(item))

        assertThat(result.totalAnnualExpense).isEqualTo(36_000.0)
        assertThat(result.rows.single().annualAmount).isEqualTo(36_000.0)
    }

    @Test
    fun `yearly item keeps entered amount`() {
        val item = CoverageExpenseInput(
            id = 1,
            name = "保险",
            amount = 6000.0,
            period = ExpensePeriod.YEARLY,
            sortOrder = 0
        )

        val result = ExpenseCoverageCalculator.calculate(50_000.0, listOf(item))

        assertThat(result.totalAnnualExpense).isEqualTo(6000.0)
        assertThat(result.rows.single().annualAmount).isEqualTo(6000.0)
    }

    @Test
    fun `items are covered in sort order with partial row before uncovered rows`() {
        val items = listOf(
            CoverageExpenseInput(2, "餐饮", 18_000.0, ExpensePeriod.YEARLY, sortOrder = 1),
            CoverageExpenseInput(1, "房租", 3000.0, ExpensePeriod.MONTHLY, sortOrder = 0),
            CoverageExpenseInput(3, "交通", 6000.0, ExpensePeriod.YEARLY, sortOrder = 2)
        )

        val result = ExpenseCoverageCalculator.calculate(45_000.0, items)

        assertThat(result.coverageRatio).isWithin(0.0001).of(45_000.0 / 60_000.0)
        assertThat(result.coveredItemCount).isEqualTo(1)
        assertThat(result.currentCoveringItemName).isEqualTo("餐饮")
        assertThat(result.remainingSurplus).isEqualTo(0.0)

        assertThat(result.rows.map { it.name }).containsExactly("房租", "餐饮", "交通").inOrder()
        assertThat(result.rows[0].status).isEqualTo(CoverageStatus.COVERED)
        assertThat(result.rows[0].coveredAmount).isEqualTo(36_000.0)
        assertThat(result.rows[1].status).isEqualTo(CoverageStatus.PARTIAL)
        assertThat(result.rows[1].coveredAmount).isEqualTo(9000.0)
        assertThat(result.rows[1].gapAmount).isEqualTo(9000.0)
        assertThat(result.rows[2].status).isEqualTo(CoverageStatus.UNCOVERED)
    }

    @Test
    fun `surplus is exposed when forecast covers all expenses`() {
        val items = listOf(
            CoverageExpenseInput(1, "房租", 12_000.0, ExpensePeriod.YEARLY, 0)
        )

        val result = ExpenseCoverageCalculator.calculate(20_000.0, items)

        assertThat(result.coverageRatio).isEqualTo(1.0)
        assertThat(result.coveredItemCount).isEqualTo(1)
        assertThat(result.currentCoveringItemName).isNull()
        assertThat(result.remainingSurplus).isEqualTo(8000.0)
        assertThat(result.rows.single().status).isEqualTo(CoverageStatus.COVERED)
    }

    @Test
    fun `no expenses returns empty result and zero ratio`() {
        val result = ExpenseCoverageCalculator.calculate(20_000.0, emptyList())

        assertThat(result.totalAnnualExpense).isEqualTo(0.0)
        assertThat(result.coverageRatio).isEqualTo(0.0)
        assertThat(result.coveredItemCount).isEqualTo(0)
        assertThat(result.rows).isEmpty()
    }
}
