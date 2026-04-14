package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.local.entity.DividendEntity
import org.junit.Test

class ForecastCalculatorTest {

    private fun makeDividend(reportDate: String, cashPerShare: Double, stockCode: String = "sz.000001") =
        DividendEntity(
            id = "${stockCode}_${reportDate}",
            stockCode = stockCode,
            reportDate = reportDate,
            cashPerShare = cashPerShare
        )

    @Test
    fun `calculateAvgCashPerShare with 3 years of data and request 3 years`() {
        val dividends = listOf(
            makeDividend("2024-12-31", 0.246),
            makeDividend("2023-12-31", 0.216),
            makeDividend("2022-12-31", 0.228)
        )

        val result = ForecastCalculator.calculateAvgCashPerShare(dividends, 3)

        assertThat(result).isNotNull()
        assertThat(result!!.avgCashPerShare).isWithin(0.001).of(0.23)
        assertThat(result.actualYears).isEqualTo(3)
    }

    @Test
    fun `calculateAvgCashPerShare with insufficient data`() {
        val dividends = listOf(
            makeDividend("2024-12-31", 0.246),
            makeDividend("2023-12-31", 0.216)
        )

        val result = ForecastCalculator.calculateAvgCashPerShare(dividends, 5)

        assertThat(result).isNotNull()
        assertThat(result!!.actualYears).isEqualTo(2)
    }

    @Test
    fun `calculateAvgCashPerShare with no data returns null`() {
        val result = ForecastCalculator.calculateAvgCashPerShare(emptyList(), 3)

        assertThat(result).isNull()
    }

    @Test
    fun `calculateAvgCashPerShare deduplicates same year`() {
        val dividends = listOf(
            makeDividend("2024-12-31", 0.246),
            makeDividend("2024-06-30", 0.100),
            makeDividend("2023-12-31", 0.216)
        )

        val result = ForecastCalculator.calculateAvgCashPerShare(dividends, 2)

        assertThat(result).isNotNull()
        // Should take the latest per year: 0.246 for 2024, 0.216 for 2023
        assertThat(result!!.avgCashPerShare).isWithin(0.001).of(0.231)
        assertThat(result.actualYears).isEqualTo(2)
    }

    @Test
    fun `calculateAvgCashPerShare filters out zero cashPerShare`() {
        val dividends = listOf(
            makeDividend("2024-12-31", 0.246),
            makeDividend("2023-12-31", 0.0),
            makeDividend("2022-12-31", 0.228)
        )

        val result = ForecastCalculator.calculateAvgCashPerShare(dividends, 3)

        assertThat(result).isNotNull()
        assertThat(result!!.actualYears).isEqualTo(2)
    }

    @Test
    fun `calculateForecastIncome with valid shares`() {
        val dividends = listOf(
            makeDividend("2024-12-31", 0.246),
            makeDividend("2023-12-31", 0.216)
        )

        val result = ForecastCalculator.calculateForecastIncome(dividends, shares = 1000, years = 3)

        assertThat(result).isNotNull()
        assertThat(result!!.actualYears).isEqualTo(2)
    }

    @Test
    fun `calculateForecastIncome with zero shares returns null`() {
        val dividends = listOf(makeDividend("2024-12-31", 0.246))

        val result = ForecastCalculator.calculateForecastIncome(dividends, shares = 0, years = 1)

        assertThat(result).isNull()
    }

    @Test
    fun `calculateForecastIncome with negative shares returns null`() {
        val dividends = listOf(makeDividend("2024-12-31", 0.246))

        val result = ForecastCalculator.calculateForecastIncome(dividends, shares = -100, years = 1)

        assertThat(result).isNull()
    }

    @Test
    fun `calculateAvgCashPerShare with single year data`() {
        val dividends = listOf(makeDividend("2024-12-31", 0.246))

        val result = ForecastCalculator.calculateAvgCashPerShare(dividends, 1)

        assertThat(result).isNotNull()
        assertThat(result!!.avgCashPerShare).isWithin(0.001).of(0.246)
        assertThat(result.actualYears).isEqualTo(1)
    }

    @Test
    fun `calculateAvgCashPerShare with zero years returns null`() {
        val dividends = listOf(makeDividend("2024-12-31", 0.246))

        val result = ForecastCalculator.calculateAvgCashPerShare(dividends, 0)

        assertThat(result).isNull()
    }
}
