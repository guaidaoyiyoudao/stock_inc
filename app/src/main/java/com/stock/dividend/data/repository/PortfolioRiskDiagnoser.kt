package com.stock.dividend.data.repository

import kotlin.math.roundToInt

/**
 * 组合风险诊断的单只持仓输入（纯数据，无 Android 依赖，便于单测）。
 * 各字段由调用方（Agent 工具层）按既有口径装配：
 * - [marketValue] = 现价 × 股数（fetchFreshPrices 批量刷新后计算）；
 * - [annualDividend] = ForecastCalculator.latestYearlyCashPerShare × 股数（预测年股息金额）；
 * - [dividendYieldPct] = latestYearlyCashPerShare / 现价 × 100（与 get_stock_info 同口径，实时算）；
 * - [payoutRatio] = 基本面派息率（7 天缓存）；
 * - [consecutiveYears] = DividendMetricsCalculator 连续分红年数。
 * 可空字段缺失时诊断指标返回 null，绝不臆造（宪法原则 III）。
 */
data class DiagnoseHolding(
    val code: String,
    val name: String? = null,
    val industry: String? = null,
    val marketValue: Double,
    val annualDividend: Double? = null,
    val dividendYieldPct: Double? = null,
    val consecutiveYears: Int? = null,
    val payoutRatio: Double? = null,
)

/** 前 N 名称 + 权重（%）。 */
data class NameWeight(
    val name: String,
    val weightPct: Double
)

/**
 * 组合风险全景诊断结果。
 * 集中度指标单位：%（HHI 为 0-10000 标度：权重百分数的平方和，单一持仓=10000）。
 * [suggestions] 由规则映射生成中文建议；无规则命中时为单条「组合结构均衡」。
 */
data class PortfolioRiskDiagnosis(
    val holdingCount: Int,
    val totalMarketValue: Double,
    // ① 集中度
    val industryHhi: Double?,
    val industryCr3: Double?,
    val topIndustries: List<NameWeight>,
    val stockHhi: Double?,
    val stockCr1: Double?,
    val stockCr3: Double?,
    val topHoldings: List<NameWeight>,
    val dividendSourceCr3: Double?,
    // ② 股息可持续性
    val fragileDividendWeightPct: Double?,
    val highPayoutCodes: List<String>,
    // ③ 估值水位
    val weightedDividendYieldPct: Double?,
    val bondYield10yPct: Double?,
    val yieldSpreadPct: Double?,
    // 建议（规则触发）
    val suggestions: List<String>,
)

/**
 * 组合风险全景诊断纯函数（无 Android 依赖，配 [PortfolioRiskDiagnoserTest]）。
 *
 * 三类视角：
 * ① 集中度：行业/个股 CR 与 HHI + 股息来源集中度（前 3 大股息贡献占比）；
 * ② 股息可持续性：连续分红不足 3 年（或无记录）的持仓权重 + 派息率超 100% 名单；
 * ③ 估值水位：组合加权股息率 vs 10Y 国债利差。
 */
object PortfolioRiskDiagnoser {

    /** HHI 高集中阈值（0-10000 标度，行业层）。 */
    private const val INDUSTRY_HHI_HIGH = 2500.0

    /** 个股最大权重警示阈值（%）。 */
    private const val STOCK_CR1_WARN = 30.0

    /** 股息来源前 3 集中警示阈值（%）。 */
    private const val DIVIDEND_SOURCE_CR3_WARN = 80.0

    /** 连续分红不足年数（<3 视为脆弱）。 */
    private const val FRAGILE_YEARS = 3

    /** 脆弱分红持仓权重警示阈值（%）。 */
    private const val FRAGILE_WEIGHT_WARN = 40.0

    /** 利差安全边际下限（%）。 */
    private const val SPREAD_THIN = 1.0

    /**
     * @param holdings 全部持仓（shares>0，现价已刷新）
     * @param bondYield10yPct 10Y 国债收益率（%），null=未获取
     * @return 诊断结果；空持仓或总市值为 0 返回 null
     */
    fun diagnose(
        holdings: List<DiagnoseHolding>,
        bondYield10yPct: Double?,
    ): PortfolioRiskDiagnosis? {
        if (holdings.isEmpty()) return null
        val total = holdings.sumOf { it.marketValue }
        if (total <= 0.0) return null

        // ── 权重（%） ──
        val stockWeights: List<Pair<String, Double>> =
            holdings.map { (it.name ?: it.code) to it.marketValue / total * 100.0 }
        val topHoldings = stockWeights.sortedByDescending { it.second }.take(3)
            .map { NameWeight(it.first, it.second) }
        val stockCr1 = stockWeights.maxOf { it.second }
        val stockCr3 = stockWeights.sortedByDescending { it.second }.take(3).sumOf { it.second }
        val stockHhi = stockWeights.sumOf { it.second * it.second }

        // 行业分组（blank → 未分类）
        val byIndustry = holdings.groupBy { it.industry?.trim()?.takeIf { i -> i.isNotEmpty() } ?: "未分类" }
        val industryWeights = byIndustry.map { (industry, list) ->
            industry to list.sumOf { it.marketValue } / total * 100.0
        }
        val topIndustries = industryWeights.sortedByDescending { it.second }.take(3)
            .map { NameWeight(it.first, it.second) }
        val industryCr3 = industryWeights.sortedByDescending { it.second }.take(3).sumOf { it.second }
        val industryHhi = industryWeights.sumOf { it.second * it.second }

        // 股息来源集中度：仅按有股息数据的持仓计算（无数据的剔除，不臆造）
        val dividendTotal = holdings.mapNotNull { it.annualDividend }.filter { it > 0.0 }.sum()
        val dividendSourceCr3 = dividendTotal.takeIf { it > 0.0 }?.let { totalDiv ->
            holdings.mapNotNull { h -> h.annualDividend?.takeIf { it > 0.0 } }
                .sortedDescending()
                .take(3)
                .sum() / totalDiv * 100.0
        }

        // ── 股息可持续性 ──
        val fragileWeight = holdings
            .filter { (it.consecutiveYears ?: 0) < FRAGILE_YEARS }
            .sumOf { it.marketValue } / total * 100.0
        val fragileDividendWeightPct = holdings.takeIf { list ->
            // 全部持仓都无分红记录时不输出（无从谈起脆弱，只提示缺失）
            list.any { it.consecutiveYears != null }
        }?.let { fragileWeight }
        val highPayoutCodes = holdings
            .filter { (it.payoutRatio ?: 0.0) > 100.0 }
            .map { it.code }

        // ── 估值水位 ──
        val weightedYield = dividendTotal.takeIf { it > 0.0 }?.let { it / total * 100.0 }
        val spread = if (weightedYield != null && bondYield10yPct != null) {
            weightedYield - bondYield10yPct
        } else null

        // ── 规则映射 → 建议 ──
        val suggestions = buildSuggestions(
            industryHhi = industryHhi, industryCr3 = industryCr3,
            stockCr1 = stockCr1, topHoldings = topHoldings,
            dividendSourceCr3 = dividendSourceCr3,
            fragileDividendWeightPct = fragileDividendWeightPct,
            highPayoutCodes = highPayoutCodes,
            weightedYield = weightedYield, bondYield10yPct = bondYield10yPct, spread = spread,
        )

        return PortfolioRiskDiagnosis(
            holdingCount = holdings.size,
            totalMarketValue = total,
            industryHhi = round2(industryHhi),
            industryCr3 = round2(industryCr3),
            topIndustries = topIndustries.map { NameWeight(it.name, round2(it.weightPct)) },
            stockHhi = round2(stockHhi),
            stockCr1 = round2(stockCr1),
            stockCr3 = round2(stockCr3),
            topHoldings = topHoldings.map { NameWeight(it.name, round2(it.weightPct)) },
            dividendSourceCr3 = dividendSourceCr3?.let(::round2),
            fragileDividendWeightPct = fragileDividendWeightPct?.let(::round2),
            highPayoutCodes = highPayoutCodes,
            weightedDividendYieldPct = weightedYield?.let(::round2),
            bondYield10yPct = bondYield10yPct,
            yieldSpreadPct = spread?.let(::round2),
            suggestions = suggestions,
        )
    }

    private fun buildSuggestions(
        industryHhi: Double,
        industryCr3: Double,
        stockCr1: Double,
        topHoldings: List<NameWeight>,
        dividendSourceCr3: Double?,
        fragileDividendWeightPct: Double?,
        highPayoutCodes: List<String>,
        weightedYield: Double?,
        bondYield10yPct: Double?,
        spread: Double?,
    ): List<String> {
        val list = mutableListOf<String>()
        if (industryHhi > INDUSTRY_HHI_HIGH) {
            list += "行业高度集中（HHI=${industryHhi.roundToInt()}）：前 3 大行业占 ${industryCr3.roundToInt()}%，建议向弱相关行业分散"
        }
        if (stockCr1 > STOCK_CR1_WARN && topHoldings.isNotEmpty()) {
            list += "单一个股 ${topHoldings.first().name} 权重 ${stockCr1.roundToInt()}% 超 30%，单一公司风险突出，建议控制仓位上限"
        }
        if (dividendSourceCr3 != null && dividendSourceCr3 > DIVIDEND_SOURCE_CR3_WARN) {
            list += "股息来源集中：前 3 大贡献占 ${dividendSourceCr3.roundToInt()}%，任一停发将显著冲击股息现金流"
        }
        if (fragileDividendWeightPct != null && fragileDividendWeightPct > FRAGILE_WEIGHT_WARN) {
            list += "${fragileDividendWeightPct.roundToInt()}% 仓位连续分红不足 3 年（或无记录），股息稳定性偏弱，收息仓位建议优选长分红史标的"
        }
        if (highPayoutCodes.isNotEmpty()) {
            list += "${highPayoutCodes.joinToString("、")} 派息率超 100%，分红可能透支盈利，需关注后续分红可持续性"
        }
        if (spread != null && bondYield10yPct != null && weightedYield != null) {
            if (spread < 0) {
                list += "组合加权股息率 ${fmt(weightedYield)}% 低于 10Y 国债 ${fmt(bondYield10yPct)}%，收息相对无风险利率无优势，建议在更低价位积累"
            } else if (spread < SPREAD_THIN) {
                list += "组合股息率相对 10Y 国债利差仅 ${fmt(spread)}%，安全边际偏薄，追高需谨慎"
            }
        }
        if (list.isEmpty()) list += "组合结构均衡，未触发明显风险规则"
        return list
    }

    private fun round2(v: Double): Double = (v * 100).roundToInt() / 100.0

    private fun fmt(v: Double): String = String.format("%.2f", v)
}
