package com.stock.dividend.data.repository

import com.stock.dividend.data.local.entity.DividendEntity
import java.time.LocalDate

/**
 * 股息/预测纯计算器。**年度分红口径全 App 统一**（数据平面收敛数据接入，本对象收敛计算口径）：
 * 一律「按除权除息日划分的滚动 12 个月窗口」优先——半年派息股（中期约 11 月、末期约次年 6 月除权）
 * 的完整年度款项跨日历年，按报告期日历年分组会把年度劈半；无可用除权日数据时回退报告期日历年分组
 * （兼容仅预案/数据陈旧）。[latestYearlyCashPerShare]（TTM）与 [calculateAvgCashPerShare]（N 年均）
 * 共享同一窗口锚点，保证「股息率」与「预测收入」两处数字同源一致。
 */
object ForecastCalculator {

    data class ForecastResult(
        val avgCashPerShare: Double,
        val actualYears: Int
    )

    /**
     * 近 [years] 年年均每股分红。优先按**除权日滚动 12 个月窗口**（与 [latestYearlyCashPerShare]
     * 的 TTM 同锚点：窗口 k = `(today-(k+1)年, today-k年]`，降序取 [years] 个、剔除非正）；
     * 无可用除权日（仅预案/数据陈旧）回退报告期日历年分组（原口径）。
     */
    fun calculateAvgCashPerShare(
        dividends: List<DividendEntity>,
        years: Int,
        today: LocalDate = LocalDate.now()
    ): ForecastResult? {
        if (dividends.isEmpty() || years <= 0) return null

        // ① 除权日滚动 12 个月窗口（统一口径；已公布未除权/未来除权日不计入）
        val windowTotals = rollingYearlyTotals(dividends, years, today)
        if (windowTotals.isNotEmpty()) {
            return ForecastResult(
                avgCashPerShare = windowTotals.sum() / windowTotals.size,
                actualYears = windowTotals.size
            )
        }

        // ② 回退：报告期日历年分组（exDate 缺失的陈旧/预案数据）
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
        years: Int,
        today: LocalDate = LocalDate.now()
    ): ForecastResult? {
        if (shares <= 0) return null
        val result = calculateAvgCashPerShare(dividends, years, today) ?: return null
        return result
    }

    /**
     * 最近一年每股现金分红（DPS，TTM）——全 App 股息率口径的基准（getCurrentDividendYield/买入线/
     * 网格锚定/组合诊断/今日简报/K 线股息率网格线均经此）。
     *
     * 最近 12 个月已除权分红合计；无近期除权记录时回退最新报告期日历年合计（兼容：年度一次派息且
     * 除权超 12 个月、仅剩预案记录、数据陈旧等场景）。与 [calculateAvgCashPerShare] 共享窗口实现，
     * 满足不变量：`latestYearlyCashPerShare(d) == calculateAvgCashPerShare(d, 1)?.avgCashPerShare`
     * （有除权数据时）。
     *
     * @param dividends 分红记录（无序容忍）
     * @param today 计算窗口的「今天」，默认系统当前日期（测试注入固定值）
     */
    fun latestYearlyCashPerShare(
        dividends: List<DividendEntity>,
        today: LocalDate = LocalDate.now()
    ): Double? {
        val valid = dividends.filter { it.cashPerShare > 0.0 }
        if (valid.isEmpty()) return null

        // 1) TTM：最近 12 个月已除权分红合计（滚动窗口序列的第一个）
        val ttm = rollingYearlyTotals(valid, years = 1, today).firstOrNull()
        if (ttm != null && ttm > 0.0) return ttm

        // 2) 回退：最新报告期日历年合计（原口径，exDate 缺失/陈旧数据）
        return valid
            .groupBy { it.reportDate.substringBefore("-") }
            .mapValues { (_, records) -> records.sumOf { it.cashPerShare } }
            .entries
            .filter { it.value > 0 }
            .maxByOrNull { it.key }
            ?.value
    }

    /**
     * 按除权除息日划分的滚动 12 个月年度合计（统一口径的共享实现，纯函数）：
     * 窗口 k = `(today-(k+1)年, today-k年]`，k=0 为最近 12 个月（TTM）。返回降序（最新在前）、
     * 仅保留合计 >0 的窗口；空窗口（停派年份）跳过——与日历年分组回退路径「take(years) 后剔零」
     * 语义一致。无任何可解析除权日（仅预案/exDate 全空）返回空表，由调用方回退。
     * 已公布未除权（exDate=null）与未来除权日不计入（属前瞻而非已派）。
     */
    private fun rollingYearlyTotals(
        dividends: List<DividendEntity>,
        years: Int,
        today: LocalDate
    ): List<Double> {
        val exPairs = dividends.mapNotNull { row ->
            row.exDividendDate
                ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                ?.takeIf { !it.isAfter(today) }   // 未来除权日不计入
                ?.let { it to row.cashPerShare }
        }
        if (exPairs.isEmpty()) return emptyList()
        return (0 until years).map { k ->
            val upper = today.minusYears(k.toLong())
            val lower = today.minusYears((k + 1).toLong())
            exPairs.filter { (ex, _) -> ex > lower && ex <= upper }.sumOf { it.second }
        }.filter { it > 0.0 }
    }
}
