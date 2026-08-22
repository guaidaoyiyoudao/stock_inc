package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.local.entity.DividendEntity
import org.junit.Test
import java.time.LocalDate

class ForecastCalculatorTest {

    private fun makeDividend(reportDate: String, cashPerShare: Double, stockCode: String = "sz.000001") =
        DividendEntity(
            id = "${stockCode}_${reportDate}",
            stockCode = stockCode,
            reportDate = reportDate,
            cashPerShare = cashPerShare
        )

    /** 带除权日的分红行（TTM 口径测试用）。 */
    private fun makeExDividend(
        reportDate: String,
        exDividendDate: String?,
        cashPerShare: Double
    ) = DividendEntity(
        id = "sh.600941_${exDividendDate ?: reportDate}_$reportDate",
        stockCode = "sh.600941",
        reportDate = reportDate,
        cashPerShare = cashPerShare,
        exDividendDate = exDividendDate
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
    fun `calculateAvgCashPerShare accumulates multiple dividends same year`() {
        val dividends = listOf(
            makeDividend("2024-12-31", 0.246),
            makeDividend("2024-06-30", 0.100),
            makeDividend("2023-12-31", 0.216)
        )

        val result = ForecastCalculator.calculateAvgCashPerShare(dividends, 2)

        assertThat(result).isNotNull()
        // 2024: 0.246 + 0.100 = 0.346, 2023: 0.216, avg = (0.346 + 0.216) / 2
        assertThat(result!!.avgCashPerShare).isWithin(0.001).of(0.281)
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

    // region latestYearlyCashPerShare（TTM 口径：修复半年派息股「只算到一半分红」）

    @Test
    fun `semi annual payer ttm sums interim and final across calendar years`() {
        // 中国移动场景（today=2026-08-19）：2025 中期（2025-11 除权）+ 2025 末期（2026-06 除权）
        // 分属日历年 2025/2026 两组——旧口径取「最新年 2026」只剩末期一半；
        // TTM 窗口 (2025-08-19, 2026-08-19] 恰好覆盖一轮完整派息 → 两笔全计入
        val dividends = listOf(
            makeExDividend(reportDate = "2025-12-31", exDividendDate = "2025-11-12", cashPerShare = 2.3449),
            makeExDividend(reportDate = "2026-06-18", exDividendDate = "2026-06-18", cashPerShare = 2.4900),
            // 更早年份不在窗口内
            makeExDividend(reportDate = "2024-12-31", exDividendDate = "2024-11-14", cashPerShare = 2.2000),
            makeExDividend(reportDate = "2025-06-19", exDividendDate = "2025-06-19", cashPerShare = 2.3000)
        )

        val dps = ForecastCalculator.latestYearlyCashPerShare(
            dividends, today = LocalDate.parse("2026-08-19")
        )

        assertThat(dps).isWithin(1e-9).of(2.3449 + 2.4900)
    }

    @Test
    fun `scheduled future ex date within one year is included in ttm`() {
        // 已定除权日（2026-11-20，实施公告已发布）计入 TTM，未除权预案（exDate=null）不计——
        // 锚点前移至 2026-11-20：窗口 (2025-11-20, 2026-11-20] 含 2026-06 已派 + 2026-11-20 已排期，
        // 2025-11-12（上一轮中期，已滚出 12 个月）不再计入
        val dividends = listOf(
            makeExDividend(reportDate = "2025-12-31", exDividendDate = "2025-11-12", cashPerShare = 2.3449),
            makeExDividend(reportDate = "2026-06-18", exDividendDate = "2026-06-18", cashPerShare = 2.4900),
            makeExDividend(reportDate = "2026-06-30", exDividendDate = null, cashPerShare = 2.5545),
            makeExDividend(reportDate = "2026-06-30", exDividendDate = "2026-11-20", cashPerShare = 2.5545)
        )

        val dps = ForecastCalculator.latestYearlyCashPerShare(
            dividends, today = LocalDate.parse("2026-08-19")
        )

        assertThat(dps).isWithin(1e-9).of(2.4900 + 2.5545)
    }

    @Test
    fun `far future ex date beyond one year is excluded from ttm`() {
        // 遥远未来除权日（2028 年，脏数据/异常占位）不参与锚点、不计入 TTM——护栏防窗口整体漂移
        val dividends = listOf(
            makeExDividend(reportDate = "2025-12-31", exDividendDate = "2025-11-12", cashPerShare = 2.3449),
            makeExDividend(reportDate = "2026-06-18", exDividendDate = "2026-06-18", cashPerShare = 2.4900),
            makeExDividend(reportDate = "2027-12-31", exDividendDate = "2028-06-30", cashPerShare = 9.9999)
        )

        val dps = ForecastCalculator.latestYearlyCashPerShare(
            dividends, today = LocalDate.parse("2026-08-19")
        )

        assertThat(dps).isWithin(1e-9).of(2.3449 + 2.4900)
    }

    @Test
    fun `haier scenario annual dividend scheduled tomorrow completes the ttm`() {
        // 海尔智家实测（东财 RPT_SHAREBONUS_DET，2026-08-20 查询）：
        // 2025 中报 10派2.692 除权 2025-11-07（已派）；2025 年度 10派8.9151 除权 2026-08-21（明天，
        // ASSIGN_PROGRESS=实施分配）——旧口径 TTM 只含中报一半（0.2692），锚点前移后两笔全计
        val dividends = listOf(
            makeExDividend(reportDate = "2023-12-31", exDividendDate = "2024-08-16", cashPerShare = 0.80131),
            makeExDividend(reportDate = "2024-12-31", exDividendDate = "2025-07-25", cashPerShare = 0.96504),
            makeExDividend(reportDate = "2025-06-30", exDividendDate = "2025-11-07", cashPerShare = 0.2692),
            makeExDividend(reportDate = "2025-12-31", exDividendDate = "2026-08-21", cashPerShare = 0.89151)
        )
        val today = LocalDate.parse("2026-08-20")

        val dps = ForecastCalculator.latestYearlyCashPerShare(dividends, today = today)

        // 锚点 2026-08-21：窗口 (2025-08-21, 2026-08-21] = 0.2692 + 0.89151
        assertThat(dps).isWithin(1e-9).of(0.2692 + 0.89151)

        // N 年均共享同一锚点：w1 = (2024-08-21, 2025-08-21] = 0.96504（2024-08-16 已滚出）
        val avg = ForecastCalculator.calculateAvgCashPerShare(dividends, years = 2, today = today)!!
        assertThat(avg.avgCashPerShare).isWithin(1e-9).of((0.2692 + 0.89151 + 0.96504) / 2.0)
        assertThat(avg.actualYears).isEqualTo(2)
    }

    @Test
    fun `no recent ex dates falls back to latest report year group`() {
        // 无 exDate 的历史数据（旧口径兼容）：最新报告年 2024 两笔合计 0.25
        val dividends = listOf(
            makeDividend("2024-06-30", 0.10),
            makeDividend("2024-12-31", 0.15),
            makeDividend("2023-12-31", 0.20)
        )

        val dps = ForecastCalculator.latestYearlyCashPerShare(
            dividends, today = LocalDate.parse("2026-08-19")
        )

        assertThat(dps).isWithin(1e-9).of(0.25)
    }

    @Test
    fun `annual payer with ex date beyond twelve months falls back to report year`() {
        // 年度一次派息：最近除权 2025-06-30（超 12 个月）→ TTM 空，回退最新报告年 2025 合计
        val dividends = listOf(
            makeExDividend(reportDate = "2025-12-31", exDividendDate = "2025-06-30", cashPerShare = 0.50),
            makeExDividend(reportDate = "2024-12-31", exDividendDate = "2024-06-28", cashPerShare = 0.40)
        )

        val dps = ForecastCalculator.latestYearlyCashPerShare(
            dividends, today = LocalDate.parse("2026-08-19")
        )

        assertThat(dps).isWithin(1e-9).of(0.50)
    }

    @Test
    fun `latest yearly cash returns null for empty or non positive rows`() {
        assertThat(
            ForecastCalculator.latestYearlyCashPerShare(emptyList(), today = LocalDate.parse("2026-08-19"))
        ).isNull()
        assertThat(
            ForecastCalculator.latestYearlyCashPerShare(
                listOf(makeDividend("2025-12-31", 0.0)),
                today = LocalDate.parse("2026-08-19")
            )
        ).isNull()
    }

    @Test
    fun `avg over rolling ex date windows for semi annual payer`() {
        // 中国移动 3 个完整派息年度（today=2026-08-19）：
        // w0=(2025-08-19,2026-08-19]={2025-11:2.3449, 2026-06:2.49}=4.8349
        // w1=(2024-08-19,2025-08-19]={2024-11:2.20, 2025-06:2.30}=4.50
        // w2=(2023-08-19,2024-08-19]={2023-11:2.10, 2024-06:2.20}=4.30
        val dividends = listOf(
            makeExDividend("2025-12-31", "2025-11-12", 2.3449),
            makeExDividend("2026-06-18", "2026-06-18", 2.4900),
            makeExDividend("2024-12-31", "2024-11-14", 2.2000),
            makeExDividend("2025-06-19", "2025-06-19", 2.3000),
            makeExDividend("2023-12-31", "2023-11-16", 2.1000),
            makeExDividend("2024-06-20", "2024-06-20", 2.2000)
        )

        val result = ForecastCalculator.calculateAvgCashPerShare(
            dividends, years = 3, today = LocalDate.parse("2026-08-19")
        )

        assertThat(result).isNotNull()
        assertThat(result!!.avgCashPerShare).isWithin(1e-9).of((4.8349 + 4.50 + 4.30) / 3.0)
        assertThat(result.actualYears).isEqualTo(3)

        val twoYears = ForecastCalculator.calculateAvgCashPerShare(
            dividends, years = 2, today = LocalDate.parse("2026-08-19")
        )!!
        assertThat(twoYears.avgCashPerShare).isWithin(1e-9).of((4.8349 + 4.50) / 2.0)
        assertThat(twoYears.actualYears).isEqualTo(2)
    }

    @Test
    fun `avg window path ignores pending plan rows without ex date`() {
        // 仅预案行（exDate=null）不进入窗口：均值仍取 3 个已除权窗口
        val dividends = listOf(
            makeExDividend("2025-12-31", "2025-11-12", 2.3449),
            makeExDividend("2026-06-18", "2026-06-18", 2.4900),
            makeExDividend("2024-12-31", "2024-11-14", 2.2000),
            makeExDividend("2025-06-19", "2025-06-19", 2.3000),
            makeExDividend("2023-12-31", "2023-11-16", 2.1000),
            makeExDividend("2024-06-20", "2024-06-20", 2.2000),
            makeExDividend("2026-06-30", null, 2.5545)
        )

        val result = ForecastCalculator.calculateAvgCashPerShare(
            dividends, years = 3, today = LocalDate.parse("2026-08-19")
        )

        assertThat(result!!.avgCashPerShare).isWithin(1e-9).of((4.8349 + 4.50 + 4.30) / 3.0)
    }

    @Test
    fun `latest yearly cash and one year average share the same window`() {
        // 统一口径不变量：有除权数据时 latest(TTM) == avg(years=1).avgCashPerShare
        // ——「股息率」与「预测收入」两处数字同源，不再出现一边全款一边半款的分裂
        val dividends = listOf(
            makeExDividend("2025-12-31", "2025-11-12", 2.3449),
            makeExDividend("2026-06-18", "2026-06-18", 2.4900),
            makeExDividend("2024-12-31", "2024-11-14", 2.2000)
        )

        val latest = ForecastCalculator.latestYearlyCashPerShare(
            dividends, today = LocalDate.parse("2026-08-19")
        )
        val oneYearAvg = ForecastCalculator.calculateAvgCashPerShare(
            dividends, years = 1, today = LocalDate.parse("2026-08-19")
        )

        assertThat(oneYearAvg).isNotNull()
        assertThat(latest).isWithin(1e-9).of(oneYearAvg!!.avgCashPerShare)
        assertThat(latest).isWithin(1e-9).of(2.3449 + 2.4900)
    }

    // endregion
}
