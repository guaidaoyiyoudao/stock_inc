package com.stock.dividend.data.agent.tools

import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import com.stock.dividend.data.local.entity.StockEntity
import com.stock.dividend.data.plane.MarketDataPlane
import com.stock.dividend.data.repository.DividendMetricsCalculator
import com.stock.dividend.data.repository.GridCalculator
import com.stock.dividend.data.repository.GridExecutionCalculator
import com.stock.dividend.data.repository.GridPlanRepository
import com.stock.dividend.data.repository.GridType
import com.stock.dividend.data.repository.HoldingRecommender
import com.stock.dividend.data.repository.KlinePeriod
import com.stock.dividend.data.repository.MarketDataRepository
import com.stock.dividend.data.repository.NotificationRuleRepository
import com.stock.dividend.data.repository.PortfolioDiagnosisAssembler
import com.stock.dividend.data.repository.PortfolioRiskDiagnoser
import com.stock.dividend.data.repository.TransactionRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first

/**
 * 组合分析工具集（2026-08-15 新增）：
 * - [GetMarketRankingTool]：全市场 A 股榜单（股息率/涨幅/市值/PE/PB/换手排序 + 红利筛选）；
 * - [GetCompareStocksTool]：多股横向对比（默认快照+本地分红深度，deep=true 加三周期 BOLL 评估）；
 * - [GetPortfolioDiagnosisTool]：组合风险全景诊断（集中度/股息可持续性/估值水位，
 *   指标由 [PortfolioRiskDiagnoser] 纯函数产出，结论由程序计算）。
 */
class GetMarketRankingTool(
    private val marketDataPlane: MarketDataPlane,
) : ReadTool(
    name = "get_market_ranking",
    description = "全市场 A 股榜单（约 5500 只沪深个股，按所选维度降序取前 N）：支持按股息率/涨跌幅/总市值/PE/PB/换手率排序，" +
        "可叠加股息率下限与 PE 上限过滤（如「股息率≥5% 且 PE≤10」即红利低估值筛选）。" +
        "⚠️ 过滤只作用于榜单前 200 名候选集，非全市场穷举；停牌/字段缺失的股票会被剔除。",
    parameters = Schema(
        type = Type.OBJECT,
        properties = mapOf(
            "sortBy" to Schema(
                type = Type.STRING,
                description = "可选：排序维度。dividend_yield=股息率%（默认）/ change=当日涨跌幅% / market_cap=总市值 / pe=PE(TTM) / pb=PB / turnover=换手率%"
            ),
            "minDividendYield" to Schema(type = Type.NUMBER, description = "可选：股息率下限（%，含），如 5.0"),
            "maxPe" to Schema(type = Type.NUMBER, description = "可选：PE(TTM) 上限（含），如 10.0"),
            "limit" to Schema(type = Type.INTEGER, description = "可选：返回条数（1-50），默认 20")
        )
    ),
) {
    override suspend fun run(context: ToolContext, args: Map<String, Any>): Any = runCatching {
        val sortBy = when (args.stringArg("sortBy")?.lowercase()) {
            "change" -> MarketDataRepository.RankingSortBy.CHANGE
            "market_cap" -> MarketDataRepository.RankingSortBy.MARKET_CAP
            "pe" -> MarketDataRepository.RankingSortBy.PE
            "pb" -> MarketDataRepository.RankingSortBy.PB
            "turnover" -> MarketDataRepository.RankingSortBy.TURNOVER
            else -> MarketDataRepository.RankingSortBy.DIVIDEND_YIELD
        }
        val minDividendYield = args.doubleArg("minDividendYield")
        val maxPe = args.doubleArg("maxPe")
        val limit = args.intArg("limit")?.coerceIn(1, 50) ?: 20
        val items = marketDataPlane.getMarketRanking(sortBy, minDividendYield, maxPe, limit)
        if (items.isEmpty()) {
            return@runCatching mapOf("error" to "暂无榜单数据（过滤条件可能过严或行情数据获取失败）")
        }
        buildMap<String, Any?> {
            put("items", items.map { it.toRankingMap() })
            if (minDividendYield != null || maxPe != null) {
                put("note", "过滤仅作用于榜单前 200 名候选集（按排序维度），非全市场穷举；字段缺失（停牌/无数据）的股票已被剔除")
            }
        }
    }.getOrElse { e -> mapOf("error" to (e.message ?: "查询失败")) }

    private fun com.stock.dividend.data.repository.MarketListItem.toRankingMap(): Map<String, Any?> =
        buildMap {
            code?.let { put("code", it) }
            name?.let { put("name", it) }
            price?.let { put("currentPrice", it) }
            changePct?.let { put("changePct", it) }
            dividendYield?.let { put("dividendYield", it) }
            pe?.let { put("peTtm", it) }
            pb?.let { put("pb", it) }
            totalMarketCap?.let { put("totalMarketCap", it) }
            turnoverRate?.let { put("turnoverRate", it) }
        }
}

/** 对比行中间结构：快照 + 本地分红深度 + 持仓（deep 时另补 BOLL 评估）。 */
private data class CompareRow(
    val code: String,
    val name: String,
    val entity: StockEntity,
    val price: Double?,
    val dividendYieldPct: Double?,
    val consecutiveYears: Int?,
    val cagr3y: Double?,
    val coefficientOfVariation: Double?,
    val latestYearlyCash: Double?,
    val holding: StockEntity?,
)

class GetCompareStocksTool(
    private val marketDataPlane: MarketDataPlane,
    private val notificationRuleRepository: NotificationRuleRepository,
) : ReadTool(
    name = "compare_stocks",
    description = "多股横向对比（2-8 只）：并排返回现价/涨跌幅/PE/PB/市值、按现价实时计算的股息率、" +
        "分红深度（连续分红年数/3 年股息 CAGR/变异系数）与本地持仓成本盈亏。" +
        "deep=true 时额外逐股拉日/周/月三周期 BOLL 并给出程序计算的 BUY/HOLD/SELL 评估（每股约 3 次请求，较慢）。" +
        "codes 用逗号分隔多个代码或名称（如 600519,000001）。",
    parameters = Schema(
        type = Type.OBJECT,
        properties = mapOf(
            "codes" to Schema(
                type = Type.STRING,
                description = "要对比的股票列表，逗号分隔（2-8 只），元素可为 6 位代码或名称：如 600519,000001,招商银行"
            ),
            "deep" to Schema(
                type = Type.BOOLEAN,
                description = "可选：是否附加三周期 BOLL 评估（默认 false 只返回快照+分红深度）"
            )
        ),
        required = listOf("codes")
    ),
) {
    override suspend fun run(context: ToolContext, args: Map<String, Any>): Any = runCatching {
        val raw = args.stringArg("codes") ?: return@runCatching mapOf("error" to "缺少 codes 参数")
        val deep = args.boolArg("deep") ?: false
        val tokens = raw.split(",", "，", ";", "；").map { it.trim() }.filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return@runCatching mapOf("error" to "codes 参数为空")

        val resolved = tokens.map { it to marketDataPlane.resolveStock(it) }
        val found = resolved.mapNotNull { it.second }
        val notFound = resolved.filter { it.second == null }.map { it.first }
        if (found.isEmpty()) {
            return@runCatching mapOf("error" to "未找到股票：${notFound.joinToString("、")}")
        }
        if (found.size > 8) {
            return@runCatching mapOf("error" to "一次最多对比 8 只，请精简列表（当前 ${found.size} 只）")
        }

        // 快照：单次 ulist 批量请求（平面会话缓存 + 写透 price_cache）
        val entities = found.map { it.toEntity() }
        val snapshots = marketDataPlane.getQuoteSnapshots(entities)

        // 分红深度：dividends 表本地读取 + DPS 经平面自动 ensureFresh（表空时补拉）
        val rows = found.map { s ->
            val dividends = marketDataPlane.getDividends(s.code)
            val metrics = DividendMetricsCalculator.calculate(dividends)
            val yearlyCash = marketDataPlane.getDps(s.code)
            val price = snapshots[s.code]?.price
            val yieldPct = if (price != null && price > 0.0 && yearlyCash != null && yearlyCash > 0.0) {
                yearlyCash / price * 100.0
            } else null
            val holding = marketDataPlane.observeStock(s.code).first()
            CompareRow(
                code = s.code, name = s.name, entity = entities.first { it.code == s.code },
                price = price, dividendYieldPct = yieldPct,
                consecutiveYears = metrics?.consecutiveYears,
                cagr3y = metrics?.cagr3y,
                coefficientOfVariation = metrics?.coefficientOfVariation,
                latestYearlyCash = yearlyCash, holding = holding,
            )
        }

        // deep：三周期 BOLL + 程序评估（与 get_stock_evaluation 同口径；平面内置 Semaphore(3) 限流 + 会话缓存）
        val recommendations = if (deep) {
            val thresholds = notificationRuleRepository.observeEvalThresholds().first()
            coroutineScope {
                rows.map { row ->
                    async {
                        if (row.price == null || row.price <= 0.0) return@async null
                        val weekly = marketDataPlane.getBoll(row.code, KlinePeriod.WEEKLY)
                        val daily = marketDataPlane.getBoll(row.code, KlinePeriod.DAILY)
                        val monthly = marketDataPlane.getBoll(row.code, KlinePeriod.MONTHLY)
                        HoldingRecommender.recommend(
                            price = row.price, band = weekly,
                            latestYearlyDividend = row.latestYearlyCash,
                            thresholds = thresholds, dailyBand = daily, monthlyBand = monthly
                        )
                    }
                }.awaitAll()
            }
        } else null

        mapOf(
            "stocks" to rows.mapIndexed { i, row ->
                buildMap<String, Any?> {
                    put("code", row.code)
                    put("name", row.name)
                    row.price?.let { put("currentPrice", it) }
                    snapshots[row.code]?.changePct?.let { put("changePct", it) }
                    snapshots[row.code]?.pe?.let { put("peTtm", it) }
                    snapshots[row.code]?.pb?.let { put("pb", it) }
                    snapshots[row.code]?.totalMarketCap?.let { put("totalMarketCap", it) }
                    snapshots[row.code]?.turnoverRate?.let { put("turnoverRate", it) }
                    row.dividendYieldPct?.let { put("dividendYield", it) }
                    row.latestYearlyCash?.let { put("latestYearlyCashPerShare", it) }
                    row.consecutiveYears?.let { put("consecutiveYears", it) }
                    row.cagr3y?.let { put("dividendCagr3y", it) }
                    row.coefficientOfVariation?.let { put("dividendCoefficientOfVariation", it) }
                    // 本地持仓（若有）：股数/成本/盈亏
                    val h = row.holding
                    if (h != null && h.shares > 0) {
                        put("shares", h.shares)
                        put("costPerShare", h.costPerShare)
                        if (row.price != null) {
                            put("profit", (row.price - h.costPerShare) * h.shares)
                            if (h.costPerShare > 0) {
                                put("profitPct", (row.price - h.costPerShare) / h.costPerShare * 100.0)
                            }
                        }
                    }
                    // deep 评估（结论由程序计算）
                    recommendations?.get(i)?.let { rec ->
                        put("action", rec.action.name)
                        rec.dividendYield?.let { put("evaluatedDividendYield", it) }
                        put("reasons", rec.reasons)
                    }
                }
            },
            "notFound" to notFound
        )
    }.getOrElse { e -> mapOf("error" to (e.message ?: "查询失败")) }
}

class GetPortfolioDiagnosisTool(
    private val marketDataPlane: MarketDataPlane,
    private val diagnosisAssembler: PortfolioDiagnosisAssembler,
    private val gridPlanRepository: GridPlanRepository,
    private val transactionRepository: TransactionRepository,
) : ReadTool(
    name = "diagnose_portfolio",
    description = "组合风险全景诊断（基于全部持仓 shares>0，现价批量实时刷新）：三类视角——" +
        "①集中度：行业/个股 CR 与 HHI、股息来源前 3 集中度；②股息可持续性：连续分红不足 3 年的仓位占比、派息率超 100% 名单；" +
        "③估值水位：组合加权股息率 vs 10Y 国债利差。并输出规则触发的改善建议。指标全部由程序计算，无需参数。",
    parameters = null,
) {
    override suspend fun run(context: ToolContext, args: Map<String, Any>): Any = runCatching {
        val stocks = marketDataPlane.observeAllStocks().first().filter { it.shares > 0 }
        if (stocks.isEmpty()) return@runCatching mapOf("error" to "没有持仓（shares>0）可诊断")
        val prices = marketDataPlane.fetchFreshPrices(stocks)
        // 装配口径（分红深度/派息率 enrich/缺价跳过）统一收敛在 PortfolioDiagnosisAssembler，
        // 与今日页「组合体检」共用同一份实现
        val d = diagnosisAssembler.assemble(stocks, prices)
            ?: return@runCatching mapOf("error" to "持仓现价均缺失，无法诊断")
        buildMap<String, Any?> {
            put("holdingCount", d.holdingCount)
            put("totalMarketValue", d.totalMarketValue)
            // ① 集中度
            d.industryHhi?.let { put("industryHhi", it) }
            d.industryCr3?.let { put("industryCr3", it) }
            put("topIndustries", d.topIndustries.map { mapOf("name" to it.name, "weightPercent" to it.weightPct) })
            d.stockHhi?.let { put("stockHhi", it) }
            d.stockCr1?.let { put("stockCr1", it) }
            d.stockCr3?.let { put("stockCr3", it) }
            put("topHoldings", d.topHoldings.map { mapOf("name" to it.name, "weightPercent" to it.weightPct) })
            d.dividendSourceCr3?.let { put("dividendSourceCr3", it) }
            // ② 股息可持续性
            d.fragileDividendWeightPct?.let { put("fragileDividendWeightPct", it) }
            if (d.highPayoutCodes.isNotEmpty()) put("highPayoutCodes", d.highPayoutCodes)
            // ③ 估值水位
            d.weightedDividendYieldPct?.let { put("weightedDividendYieldPct", it) }
            d.bondYield10yPct?.let { put("bondYield10y", it) }
            d.yieldSpreadPct?.let { put("yieldSpreadPct", it) }
            // 网格弹药（信息性补充，不改现金比例判定口径）：网格剩余资金本质仍是现金，
            // 但属于「已承诺的分批买入弹药」，解读现金水位时应向用户说明这一属性
            val gridPlans = runCatching { gridPlanRepository.observeAll().first() }.getOrDefault(emptyList())
            if (gridPlans.isNotEmpty()) {
                val txsByStock = runCatching {
                    transactionRepository.getAll().groupBy { it.stockCode }
                }.getOrDefault(emptyMap())
                val uninvested = gridPlans.sumOf { plan ->
                    val planTxs = txsByStock[plan.stockCode].orEmpty()
                    val result = GridCalculator.markTriggeredLevels(
                        GridCalculator.generate(
                            plan.basePrice, plan.lowPrice, plan.highPrice,
                            plan.grids, plan.totalCapital,
                            gridType = GridType.fromRaw(plan.gridType),
                            dps = plan.dpsPerShare
                        ),
                        planTxs
                    )
                    GridExecutionCalculator.calculate(result, plan.totalCapital, planTxs, null).remainingCapital
                }
                put("gridUninvestedCash", uninvested)
                put("gridNote", "网格剩余可投资金（已承诺弹药，本质仍为现金，未计入上述判定口径）")
            }
            // 建议
            put("suggestions", d.suggestions)
        }
    }.getOrElse { e -> mapOf("error" to (e.message ?: "查询失败")) }
}
