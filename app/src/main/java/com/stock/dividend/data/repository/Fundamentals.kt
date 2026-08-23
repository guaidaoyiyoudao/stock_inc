package com.stock.dividend.data.repository

import com.stock.dividend.data.remote.dto.BalanceSheetResponse
import com.stock.dividend.data.remote.dto.FundamentalResponse

/**
 * 单股基本面（近 N 期）；纯数据，无 Android 依赖，便于单测构造。
 *
 * 各 [periods] 升序（旧→新），便于趋势判断与 prompt/UI 渲染（与 [StockLlmInput.dividendRatePoints] 升序约定一致）。
 */
data class Fundamentals(
    val periods: List<Period>
) {
    /** 单期：某报告期的一组基本面指标。缺失指标为 null。 */
    data class Period(
        val reportDate: String,            // "2024-12-31"
        val roe: Double?,                  // 加权净资产收益率 %
        val debtToAssetRatio: Double?,     // 资产负债率 %
        val revenueYoy: Double?,           // 营收同比 %
        val netProfitYoy: Double?,         // 净利同比 %
        val basicEps: Double? = null,      // 基本每股收益（元）；用于算派息率
        val payoutRatio: Double? = null,   // 派息率 %；由 [enrichPayoutRatio] 填充，初始 null
        val announceYield: Double? = null, // 公告股息率 %（东财按公告日股价算）
        val dividendPlan: String? = null   // 分红方案文本，如「10派3.60元(含税)」
    )
}

/** 趋势方向。样本不足（<2 期）时为 [Insufficient]。 */
enum class FundamentalsTrend { Up, Down, Flat, Insufficient }

/**
 * 主源（扶摇）+ 候补（东财）单期字段级合并：仅回填接收者为 null 的字段
 * （东财补齐 dividendPlan 分红方案 / announceYield 公告股息率 等扶摇没有的字段）。
 * [other] 为 null 原样返回（东财补齐失败不影响主源结果）。
 */
fun Fundamentals.Period.supplementedFrom(other: Fundamentals.Period?): Fundamentals.Period {
    if (other == null || other === this) return this
    return copy(
        roe = roe ?: other.roe,
        debtToAssetRatio = debtToAssetRatio ?: other.debtToAssetRatio,
        revenueYoy = revenueYoy ?: other.revenueYoy,
        netProfitYoy = netProfitYoy ?: other.netProfitYoy,
        basicEps = basicEps ?: other.basicEps,
        announceYield = announceYield ?: other.announceYield,
        dividendPlan = dividendPlan ?: other.dividendPlan
    )
}

/**
 * 主源期次 + 候补期次按报告期合并：同期字段级补齐（[Fundamentals.Period.supplementedFrom]），
 * 候补独有的期次（扶摇窗口未覆盖的旧期）追加；升序返回。payoutRatio 不在此合并
 * （恒由 enrichPayoutRatio 幂等重算）。
 */
fun mergeFundamentalsPeriods(
    primary: List<Fundamentals.Period>,
    supplement: List<Fundamentals.Period>
): List<Fundamentals.Period> {
    if (supplement.isEmpty()) return primary
    val supplementByDate = supplement.associateBy { it.reportDate }
    val merged = primary.map { it.supplementedFrom(supplementByDate[it.reportDate]) }
    val knownDates = primary.map { it.reportDate }.toSet()
    return (merged + supplement.filter { it.reportDate !in knownDates }).sortedBy { it.reportDate }
}

/**
 * DTO → [Fundamentals] 解析（纯函数）。
 *
 * 只解析 ROE / 资产负债率 / 营收同比 / 净利同比；[Fundamentals.Period.payoutRatio] 在此阶段恒为 null，
 * 由 [enrichPayoutRatio] 用股息接口的 EPS_DIV 补全（职责分离，避免 Repository 间交叉注入）。
 *
 * 负债率合并：[FundamentalResponse.Item]（RPT_LICO_FN_CPD）不含负债率字段，
 * 由 [balanceSheetItems]（RPT_DMSK_FN_BALANCE）按报告期对齐补全。
 * 注意两接口报告期格式不同：财务指标 `REPORTDATE`="2026-03-31"，资产负债表 `REPORT_DATE`="2026-03-31 00:00:00"，
 * 故资产负债表日期先 `substringBefore(" ")` 归一化再匹配。
 *
 * @param items              东财财务摘要项（可能乱序，按 reportDate 升序排序后保留最新 [maxN] 期）
 * @param balanceSheetItems  资产负债表项（可选；为空则负债率为 null）
 * @param maxN               最多保留期数，默认 5（见设计文档 §2「拉取期数」）
 * @return 解析结果；items 为空返回 null
 */
object FundamentalsBuilder {
    fun build(
        items: List<FundamentalResponse.Item>,
        balanceSheetItems: List<BalanceSheetResponse.Item> = emptyList(),
        maxN: Int = 5
    ): Fundamentals? {
        if (items.isEmpty()) return null
        // 资产负债表：日期归一化 → reportDate → 负债率（同报告期取第一条，已按 REPORT_DATE 倒序故为最新合并报表）
        val debtByDate: Map<String, Double?> = balanceSheetItems
            .filter { !it.reportDate.isNullOrBlank() }
            .groupBy { it.reportDate!!.substringBefore(" ") }
            .mapValues { (_, group) -> group.firstOrNull()?.debtAssetRatio }

        val sorted = items
            .filter { !it.reportDate.isNullOrBlank() }
            .sortedBy { it.reportDate!! }
            .takeLast(maxN)
        if (sorted.isEmpty()) return null
        return Fundamentals(
            periods = sorted.map { item ->
                // 归一化报告期：实测财务接口 REPORTDATE 带时间后缀（"2024-12-31 00:00:00"），
                // 必须去后缀成纯日期，否则①负债率跨接口合并 key 对不上 ②派息率与分红 reportDate 匹配不上
                // ③UI 期次标签异常。与 DividendRepository.toDateOnlyOrNull 同口径。
                val date = item.reportDate!!.substringBefore(" ").trim()
                Fundamentals.Period(
                    reportDate = date,
                    roe = item.weightedAvgRoe,
                    debtToAssetRatio = item.debtAssetRatio ?: debtByDate[date],
                    revenueYoy = item.revenueYoy,
                    netProfitYoy = item.netProfitYoy,
                    basicEps = item.basicEps,
                    payoutRatio = null,   // 由 enrichPayoutRatio 补全
                    announceYield = item.announceYield,
                    dividendPlan = item.dividendPlan?.takeIf { it.isNotBlank() }
                )
            }
        )
    }
}

/**
 * 用本地分红的**年度合计**补全 [Fundamentals.Period.payoutRatio]（纯函数）。
 *
 * 年度派息率 = 该分红年度每股分红合计 ÷ 该年度年报 BASIC_EPS × 100（仅挂在年报期 12-31 上）。
 *
 * ⚠️ 必须按年度合计而非按报告期单笔（2026-08-20 审计修复）：半年派息股（如中国移动）的
 * 中期+末期若不合计，年报期只匹配到单笔（约低估一半）、真实中期报告期又永远匹配不上——
 * 与 TTM 股息率修复（2026-08-19）同类问题的派息率分支。腾讯源 `nd` 即分红年度、东财源
 * reportDate 含真实报告期，两者按「reportDate 前 4 位年份」分组合计后口径天然统一。
 *
 * - BASIC_EPS 缺失 / 为 0 / 为负 → payoutRatio = null（不臆造）
 * - 非年报期（Q1/Q2/Q3）不挂派息率（半年 EPS 对年度分红会算出约两倍的错误值）→ null
 * - 该年度无分红 → null
 *
 * @param fundamentals          [FundamentalsBuilder.build] 的产物（payoutRatio 字段会被覆盖）
 * @param cashPerShareByYear    分红年度 → 该年每股分红合计（来自已订阅的股息数据）
 */
fun enrichPayoutRatio(
    fundamentals: Fundamentals,
    cashPerShareByYear: Map<Int, Double>
): Fundamentals {
    return fundamentals.copy(
        periods = fundamentals.periods.map { period ->
            period.copy(payoutRatio = computePayoutRatio(period, cashPerShareByYear))
        }
    )
}

private fun computePayoutRatio(
    period: Fundamentals.Period,
    cashPerShareByYear: Map<Int, Double>
): Double? {
    // 仅年报期挂年度派息率；中期 EPS 是半年值，除年度分红会得约两倍错误值
    if (!period.reportDate.endsWith("12-31")) return null
    val eps = period.basicEps
    if (eps == null || eps <= 0.0) return null   // EPS 缺失/为 0/为负，不臆造
    val year = period.reportDate.take(4).toIntOrNull() ?: return null
    val cashPerShare = cashPerShareByYear[year] ?: return null
    if (!cashPerShare.isFinite() || cashPerShare <= 0.0) return null
    return cashPerShare / eps * 100
}

/** 报告期格式化为短标签："2024-12-31" → "24Q4"（纯函数）。 */
fun formatFundamentalsPeriod(reportDate: String): String {
    val parts = reportDate.split("-")
    if (parts.size < 2) return reportDate
    val year = parts[0]
    val month = parts[1]
    val yy = if (year.length >= 4) year.substring(2) else year
    val q = when (month) {
        "03" -> "Q1"; "06" -> "Q2"; "09" -> "Q3"; "12" -> "Q4"
        else -> return "$yy$month"   // 非标准报告期，退化为年月
    }
    return "$yy$q"
}

/**
 * 判断 [fundamentals] 某指标序列的趋势方向（纯函数）。
 *
 * 阈值采用相对口径：首末差占首期绝对值的比例。ROE 等绝对值较大的指标若用固定 ±0.3 阈值（同分红率）会误判。
 *
 * @param selector 取每期某指标的函数
 * @return 样本（有效值）<2 → [Insufficient]；否则按相对变化 ±[relativeThreshold] 判 Up/Down/Flat
 */
fun fundamentalsTrend(
    fundamentals: Fundamentals,
    relativeThreshold: Double = 0.05,
    selector: (Fundamentals.Period) -> Double?
): FundamentalsTrend {
    val series = fundamentals.periods.mapNotNull(selector).filter { it.isFinite() }
    if (series.size < 2) return FundamentalsTrend.Insufficient
    val first = series.first()
    val last = series.last()
    if (first == 0.0) return FundamentalsTrend.Flat
    val delta = (last - first) / kotlin.math.abs(first)
    return when {
        delta > relativeThreshold -> FundamentalsTrend.Up
        delta < -relativeThreshold -> FundamentalsTrend.Down
        else -> FundamentalsTrend.Flat
    }
}
