package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.local.entity.DividendEntity
import org.junit.Test

class DividendMetricsCalculatorTest {

    private fun makeDividend(reportDate: String, cashPerShare: Double, stockCode: String = "sh.600519") =
        DividendEntity(
            id = "${stockCode}_${reportDate}",
            stockCode = stockCode,
            reportDate = reportDate,
            cashPerShare = cashPerShare
        )

    @Test
    fun `calculate returns null when no dividends`() {
        assertThat(DividendMetricsCalculator.calculate(emptyList())).isNull()
    }

    @Test
    fun `calculate returns null when all cashPerShare le zero`() {
        val dividends = listOf(makeDividend("2024-12-31", 0.0), makeDividend("2023-12-31", -0.1))
        assertThat(DividendMetricsCalculator.calculate(dividends)).isNull()
    }

    @Test
    fun `totalYears counts only positive years and merges same year`() {
        // 2024 有两条记录（同年合并）；2022 为 0 不计
        val dividends = listOf(
            makeDividend("2024-12-31", 0.3),
            makeDividend("2024-06-30", 0.2),
            makeDividend("2023-12-31", 0.25),
            makeDividend("2022-12-31", 0.0)
        )
        val m = DividendMetricsCalculator.calculate(dividends)!!
        assertThat(m.totalYears).isEqualTo(2) // 2023、2024
        assertThat(m.latestYear).isEqualTo("2024")
    }

    @Test
    fun `consecutiveYears counts trailing continuous years`() {
        val dividends = listOf(
            makeDividend("2020-12-31", 0.1),
            makeDividend("2021-12-31", 0.1),
            makeDividend("2022-12-31", 0.1),
            makeDividend("2023-12-31", 0.1),
            makeDividend("2024-12-31", 0.1)
        )
        assertThat(DividendMetricsCalculator.calculate(dividends)!!.consecutiveYears).isEqualTo(5)
    }

    @Test
    fun `consecutiveYears stops at gap`() {
        // 2022 年缺分红（断档），从 2024 往回数只能数到 2023 → 2
        val dividends = listOf(
            makeDividend("2020-12-31", 0.1),
            makeDividend("2021-12-31", 0.1),
            makeDividend("2023-12-31", 0.1),
            makeDividend("2024-12-31", 0.1)
        )
        assertThat(DividendMetricsCalculator.calculate(dividends)!!.consecutiveYears).isEqualTo(2)
    }

    @Test
    fun `cagr3y computed from 3y window endpoints`() {
        // 近 3 年窗口端点：2022=1.0 → 2024=1.21，n=2，CAGR=10%
        val dividends = listOf(
            makeDividend("2022-12-31", 1.0),
            makeDividend("2023-12-31", 1.1),
            makeDividend("2024-12-31", 1.21)
        )
        val cagr = DividendMetricsCalculator.calculate(dividends)!!.cagr3y
        assertThat(cagr).isNotNull()
        assertThat(cagr!!).isWithin(0.01).of(10.0)
    }

    @Test
    fun `cagr3y null when window lt 2 points`() {
        val dividends = listOf(makeDividend("2024-12-31", 1.0))
        assertThat(DividendMetricsCalculator.calculate(dividends)!!.cagr3y).isNull()
    }

    @Test
    fun `avgCashPerShare3y and 5y reuse ForecastCalculator`() {
        val dividends = listOf(
            makeDividend("2024-12-31", 0.3),
            makeDividend("2023-12-31", 0.2),
            makeDividend("2022-12-31", 0.1)
        )
        val m = DividendMetricsCalculator.calculate(dividends)!!
        assertThat(m.avgCashPerShare3y).isWithin(0.001).of(0.2)
        // 不足 5 年时 avg5y 仍返回现有年数的均值
        assertThat(m.avgCashPerShare5y).isWithin(0.001).of(0.2)
    }

    @Test
    fun `stdDev and CV computed when ge 2 samples`() {
        val dividends = listOf(
            makeDividend("2024-12-31", 0.4),
            makeDividend("2023-12-31", 0.3),
            makeDividend("2022-12-31", 0.2),
            makeDividend("2021-12-31", 0.1),
            makeDividend("2020-12-31", 0.5)
        )
        val m = DividendMetricsCalculator.calculate(dividends)!!
        assertThat(m.stdDev).isNotNull()
        assertThat(m.coefficientOfVariation).isNotNull()
        // CV = std/mean，正值
        assertThat(m.coefficientOfVariation!!).isGreaterThan(0.0)
    }

    @Test
    fun `stdDev null when only one sample`() {
        val dividends = listOf(makeDividend("2024-12-31", 0.3))
        val m = DividendMetricsCalculator.calculate(dividends)!!
        assertThat(m.stdDev).isNull()
        assertThat(m.coefficientOfVariation).isNull()
    }
}
