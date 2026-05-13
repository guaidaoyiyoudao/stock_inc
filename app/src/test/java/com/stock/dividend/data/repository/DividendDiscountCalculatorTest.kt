package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.local.entity.DividendEntity
import org.junit.Test

class DividendDiscountCalculatorTest {
    private fun dividend(reportDate: String, cashPerShare: Double) = DividendEntity(
        id = "sz.000001_$reportDate",
        stockCode = "sz.000001",
        reportDate = reportDate,
        cashPerShare = cashPerShare
    )

    @Test
    fun `calculate returns intrinsic value safety price and cash flow rows`() {
        val result = DividendDiscountCalculator.calculate(
            input = DividendDiscountInput(
                dividendBasisPerShare = 2.0,
                dividendGrowthRate = 0.05,
                discountRate = 0.09,
                terminalGrowthRate = 0.02,
                projectionYears = 3,
                marginOfSafety = 0.20,
                currentPrice = 40.0
            )
        )

        assertThat(result.validationError).isNull()
        assertThat(result.cashFlowRows.map { it.year }).containsExactly(1, 2, 3).inOrder()
        assertThat(result.cashFlowRows[0].projectedDividend).isWithin(0.001).of(2.10)
        assertThat(result.intrinsicValuePerShare).isWithin(0.01).of(31.62)
        assertThat(result.safetyBuyPrice).isWithin(0.01).of(25.30)
        assertThat(result.discountOrPremiumPercent).isWithin(0.0001).of(-0.2095)
        assertThat(result.valuationStatus).isEqualTo(DividendValuationStatus.OVERVALUED)
    }

    @Test
    fun `calculate marks undervalued when intrinsic value is above current price`() {
        val result = DividendDiscountCalculator.calculate(
            DividendDiscountInput(2.0, 0.05, 0.09, 0.02, 3, 0.20, 30.0)
        )

        assertThat(result.valuationStatus).isEqualTo(DividendValuationStatus.UNDERVALUED)
        assertThat(result.discountOrPremiumPercent).isGreaterThan(0.0)
    }

    @Test
    fun `calculate omits market comparison when current price is missing`() {
        val result = DividendDiscountCalculator.calculate(
            DividendDiscountInput(2.0, 0.05, 0.09, 0.02, 3, 0.20, null)
        )

        assertThat(result.valuationStatus).isEqualTo(DividendValuationStatus.NO_MARKET_PRICE)
        assertThat(result.discountOrPremiumPercent).isNull()
    }

    @Test
    fun `calculate rejects discount rate less than or equal to terminal growth rate`() {
        val result = DividendDiscountCalculator.calculate(
            DividendDiscountInput(2.0, 0.05, 0.02, 0.02, 10, 0.20, 30.0)
        )

        assertThat(result.validationError).isEqualTo("折现率必须大于终值增长率")
        assertThat(result.cashFlowRows).isEmpty()
    }

    @Test
    fun `calculate clamps projection years to one through thirty`() {
        val low = DividendDiscountCalculator.calculate(
            DividendDiscountInput(2.0, 0.05, 0.09, 0.02, 0, 0.20, null)
        )
        val high = DividendDiscountCalculator.calculate(
            DividendDiscountInput(2.0, 0.05, 0.09, 0.02, 45, 0.20, null)
        )

        assertThat(low.projectionYears).isEqualTo(1)
        assertThat(high.projectionYears).isEqualTo(30)
    }

    @Test
    fun `deriveDividendBasis averages most recent five dividend years`() {
        val result = DividendDiscountCalculator.deriveDividendBasis(
            listOf(
                dividend("2025-12-31", 6.0),
                dividend("2024-12-31", 5.0),
                dividend("2024-06-30", 1.0),
                dividend("2023-12-31", 4.0),
                dividend("2022-12-31", 3.0),
                dividend("2021-12-31", 2.0),
                dividend("2020-12-31", 100.0)
            )
        )

        assertThat(result).isNotNull()
        assertThat(result!!.averageCashPerShare).isWithin(0.001).of(4.2)
        assertThat(result.actualYears).isEqualTo(5)
    }

    @Test
    fun `deriveDividendBasis returns null when no positive dividends exist`() {
        val result = DividendDiscountCalculator.deriveDividendBasis(
            listOf(dividend("2025-12-31", 0.0))
        )

        assertThat(result).isNull()
    }
}
