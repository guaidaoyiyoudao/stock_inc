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

        // ① 除权日滚动 12 个月窗口（统一口径；已排期未除权计入、未除权预案不计入，见 rollingYearlyTotals）
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
     * 最近 12 个月已除权分红合计（已排期未除权——实施公告已出、除权日在未来一年内——一并计入，
     * 锚点随前移，见 [rollingYearlyTotals]）；无近期除权记录时回退最新报告期日历年合计（兼容：
     * 年度一次派息且除权超 12 个月、仅剩预案记录、数据陈旧等场景）。与 [calculateAvgCashPerShare]
     * 共享窗口实现，满足不变量：`latestYearlyCashPerShare(d) == calculateAvgCashPerShare(d, 1)?.avgCashPerShare`
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
     * 窗口 k = `(anchor-(k+1)年, anchor-k年]`，k=0 为最近 12 个月（TTM）。返回降序（最新在前）、
     * 仅保留合计 >0 的窗口；空窗口（停派年份）跳过——与日历年分组回退路径「take(years) 后剔零」
     * 语义一致。无任何可解析除权日（仅预案/exDate 全空）返回空表，由调用方回退。
     *
     * **已排期未除权计入**：实施公告已发布、除权日在未来 ≤[SCHEDULED_EX_DATE_MAX_AHEAD_DAYS] 天内
     * 的分红视同已派入窗（如「年度分红明天除权」——金额除权日均已确定，非前瞻臆测）；锚点 anchor
     * = max(today, 入窗记录的最大除权日)，随之前移保证 TTM 窗口恰好罩住最近一轮完整派息。
     * 未除权预案（exDate=null）与遥远未来脏数据（超护栏）不计入。
     */
    private fun rollingYearlyTotals(
        dividends: List<DividendEntity>,
        years: Int,
        today: LocalDate
    ): List<Double> {
        val horizon = today.plusDays(SCHEDULED_EX_DATE_MAX_AHEAD_DAYS)
        val exPairs = dividends.mapNotNull { row ->
            row.exDividendDate
                ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                ?.takeIf { !it.isAfter(horizon) }   // 遥远未来除权日（脏数据）不计入
                // 防御：非正金额不入窗（latestYearlyCashPerShare 路径已预过滤全表，
                // calculateAvgCashPerShare 未过滤——在共享实现处统一，两路径口径严格一致）
                ?.takeIf { row.cashPerShare > 0.0 }
                ?.let { it to row.cashPerShare }
        }
        if (exPairs.isEmpty()) return emptyList()
        val anchor = maxOf(today, exPairs.maxOf { it.first })
        return (0 until years).map { k ->
            val upper = anchor.minusYears(k.toLong())
            val lower = anchor.minusYears((k + 1).toLong())
            exPairs.filter { (ex, _) -> ex > lower && ex <= upper }.sumOf { it.second }
        }.filter { it > 0.0 }
    }

    /** 已排期除权日的最大前视天数（护栏：实施分配的除权日必然在数周内，超此视为脏数据）。 */
    private const val SCHEDULED_EX_DATE_MAX_AHEAD_DAYS = 365L
}
