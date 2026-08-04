package com.stock.dividend.data.repository

import com.stock.dividend.data.local.entity.DividendEntity

/**
 * 分红再投资（DRIP）复利模拟（纯函数，无 Android 依赖，便于单测）。
 *
 * 模型：假设一笔初始本金在起始年全额买入（以初始价），此后**每年**把当年每股分红
 * 收到的现金**全部以再投价格**重新买入该股，逐年累加股数。对比「分红再投」与
 * 「现金分红」（不扩股、分红落袋）两条路径的总市值与总收益。
 *
 * **关于再投价格的诚实说明**：
 * 精确 DRIP 需要每个除权日的真实股价，但（1）除权日历史价需按日期对齐 K 线、（2）腾讯 K 线
 * 单次仅约 640 交易日（≈2.5 年），跨多年回溯成本高且易缺。这里采用**可配置的再投价格**
 * （单值）作为简化口径——这是行业常用估值假设，用户可填入成本价/当前价/自定值，
 * 结果是「若再投价保持不变」的复利效果，**不臆造逐日真实价**（宪法原则 III）。
 *
 * 口径约定（与 [ForecastCalculator] 一致）：
 * - 按 [DividendEntity.reportDate] 年份分组、sumOf cashPerShare；
 * - 过滤每股分红 ≤ 0 的年份（特别分红/拆分占位等噪声）；
 * - 模拟窗口 = `[startYear, endYear]`，**仅含有有效分红的年份参与**，无分红年份股数不变。
 *
 * @property initialShares       初始买入股数（initialAmount / initialPrice）。
 * @property finalShares         期末累计股数（含所有再投股数）。
 * @property totalDividendCash   窗口内累计收到的分红现金（元，未再投的原值）。
 * @property reinvestedShares    累计再投新增股数（finalShares − initialShares）。
 * @property yearlyRows          逐年明细（年份/当年每股分红/再投股数/累计股数/当年分红现金）。
 * @property cashPathFinalValue  「现金分红」路径期末市值（股数不变，仅累计分红落袋）。
 * @property dripPathFinalValue  「分红再投」路径期末市值（扩股后总市值，按 endPrice）。
 * @property dripVsCashExcess    再投相对现金路径多出的市值（dripPath − cashPath）。
 */
data class DripYearRow(
    val year: String,
    val cashPerShare: Double,
    val reinvestedShares: Double,
    val cumulativeShares: Double,
    val dividendCash: Double
)

data class DripResult(
    val initialShares: Double,
    val finalShares: Double,
    val reinvestedShares: Double,
    val totalDividendCash: Double,
    val yearlyRows: List<DripYearRow>,
    val cashPathFinalValue: Double,
    val dripPathFinalValue: Double,
    val dripVsCashExcess: Double,
    val startYear: String,
    val endYear: String,
    /** 是否因再投价非正导致无法扩股（此时再投路径退化为现金路径）。 */
    val reinvestDisabled: Boolean
) {
    /** 再投路径相对现金路径的超额收益率（%）；现金路径市值为 0 时为 null。 */
    val dripVsCashExcessRate: Double?
        get() = if (cashPathFinalValue > 0.0) dripVsCashExcess / cashPathFinalValue * 100.0 else null
}

object DripCalculator {

    /**
     * 计算 DRIP 复利模拟。
     *
     * @param dividends     历史分红记录（reportDate + cashPerShare）。
     * @param initialAmount 初始投入金额（元，> 0）。
     * @param initialPrice  初始买入价（元/股，> 0）。
     * @param reinvestPrice 每年分红再投的买入价（元/股，> 0；≤0 则禁用再投，退化为现金路径）。
     * @param endPrice      期末参考价（元/股，> 0），用于折算两条路径的期末市值。
     * @param startYear     模拟起始年份（含，四位字符串如 "2021"）；null 表示取最早有分红年份。
     * @param endYear       模拟结束年份（含）；null 表示取最新有分红年份。
     */
    fun simulate(
        dividends: List<DividendEntity>,
        initialAmount: Double,
        initialPrice: Double,
        reinvestPrice: Double,
        endPrice: Double,
        startYear: String? = null,
        endYear: String? = null
    ): DripResult? {
        if (initialAmount <= 0.0 || initialPrice <= 0.0 || endPrice <= 0.0) return null

        // 年份 → 年度每股分红合计（过滤 ≤0 的年份）
        val yearly: Map<String, Double> = dividends
            .filter { it.reportDate.length >= 4 && it.cashPerShare > 0.0 }
            .groupBy { it.reportDate.substring(0, 4) }
            .mapValues { (_, rows) -> rows.sumOf { it.cashPerShare } }
            .filterValues { it > 0.0 }
            .toSortedMap()

        if (yearly.isEmpty()) return null

        val allYears = yearly.keys.toList()
        val simStart = startYear?.takeIf { it in yearly } ?: allYears.first()
        val simEnd = endYear?.takeIf { it in yearly } ?: allYears.last()
        if (simStart > simEnd) return null

        val window = allYears.filter { it in simStart..simEnd }

        val reinvestEnabled = reinvestPrice > 0.0
        var shares = initialAmount / initialPrice
        val initialShares = shares
        var totalCash = 0.0
        val rows = mutableListOf<DripYearRow>()

        for (year in window) {
            val dps = yearly[year] ?: continue
            val dividendCash = shares * dps
            totalCash += dividendCash
            val reinvested = if (reinvestEnabled) dividendCash / reinvestPrice else 0.0
            shares += reinvested
            rows += DripYearRow(
                year = year,
                cashPerShare = dps,
                reinvestedShares = reinvested,
                cumulativeShares = shares,
                dividendCash = dividendCash
            )
        }

        // 现金路径：股数不变（= 初始股数），市值 = 初始股数 × endPrice + 累计分红现金
        val cashPathValue = initialShares * endPrice + totalCash
        // 再投路径：扩股后总市值。分红全部再投时 = 期末股数 × endPrice（分红已转化为股票）；
        // 再投禁用时退化为现金路径（股数不变，分红落袋），两路径相等。
        val dripPathValue = if (reinvestEnabled) shares * endPrice else cashPathValue

        return DripResult(
            initialShares = initialShares,
            finalShares = shares,
            reinvestedShares = shares - initialShares,
            totalDividendCash = totalCash,
            yearlyRows = rows,
            cashPathFinalValue = cashPathValue,
            dripPathFinalValue = dripPathValue,
            dripVsCashExcess = dripPathValue - cashPathValue,
            startYear = simStart,
            endYear = simEnd,
            reinvestDisabled = !reinvestEnabled
        )
    }
}
