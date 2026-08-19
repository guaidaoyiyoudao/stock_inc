package com.stock.dividend.data.repository

import com.stock.dividend.data.local.dao.LlmAnalysisCacheDao
import com.stock.dividend.data.local.entity.LlmAnalysisCacheEntity
import com.stock.dividend.data.plane.MarketDataPlane
import com.stock.dividend.data.remote.LlmApi
import com.stock.dividend.data.remote.dto.LlmChatRequest
import com.stock.dividend.data.remote.dto.LlmMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 今日 AI 简报编排（@Singleton）。
 *
 * - [generateAndCache]：拉数据→聚合信号→构造 prompt→调 LLM→解析→写缓存。失败静默（红线 #2）。
 * - [read]：按日期读缓存；无则 null（UI 据此决定是否渲染 AI 卡）。
 *
 * 喂料四块：组合表现 / 信号 / 分红 + 组合体检（经 [PortfolioDiagnosisAssembler]，与今日页体检卡同源）
 * + 市场板块温度（领涨领跌，与 get_market_sentiment 工具同口径）。后两块缺数据时自动省略。
 *
 * 缓存复用 `llm_analysis_cache` 表，scope = [SCOPE]，key = "today_briefing_yyyy-MM-dd"。
 *
 * 数据获取统一走 [MarketDataPlane]（数据平面）；`latestYearlyDividend` 经平面 getDps 装配
 * （自动 ensureFresh），后台信号与前台 [TodayViewModel] 口径一致（2026-08-18 补齐曾缺位的股息率达线信号）。
 */
@Singleton
class TodayBriefingCoordinator @Inject constructor(
    private val marketDataPlane: MarketDataPlane,
    private val gridPlanRepository: GridPlanRepository,
    private val transactionRepository: TransactionRepository,
    private val diagnosisAssembler: PortfolioDiagnosisAssembler,
    private val llmApi: LlmApi,
    private val llmConfigRepository: LlmConfigRepository,
    private val cacheDao: LlmAnalysisCacheDao,
) {
    suspend fun generateAndCache(date: LocalDate): Boolean = withContext(Dispatchers.IO) {
        val config = llmConfigRepository.snapshot()
        if (!config.isComplete) return@withContext false
        try {
            // 1. 拉数据（数据平面；各步吞异常返回空，红线 #2）
            val stocks = runCatching { marketDataPlane.observeAllStocks().first() }.getOrDefault(emptyList())
            val snapshots = runCatching { marketDataPlane.getQuoteSnapshots(stocks, force = true) }.getOrDefault(emptyMap())
            val bond = runCatching { marketDataPlane.get10YBondYield() }.getOrDefault(BondYieldRepository.DEFAULT_YIELD)
            val gridPlans = runCatching { gridPlanRepository.observeAll().first() }.getOrDefault(emptyList())
            val dividends = runCatching { marketDataPlane.getAllDividendsWithExDate() }.getOrDefault(emptyList())
            val indices = runCatching { marketDataPlane.getIndexQuotes() }.getOrDefault(emptyList())

            // 2. 聚合信号（每只股拉周线 BOLL——平面内置限流/缓存；DPS 经平面自动 ensureFresh）
            val stockSnapshots = stocks.map { entity ->
                TodayStockSnapshot(
                    code = entity.code,
                    name = entity.name,
                    price = snapshots[entity.code]?.price,
                    weeklyBand = runCatching { marketDataPlane.getBoll(entity.code) }.getOrNull(),
                    latestYearlyDividend = runCatching { marketDataPlane.getDps(entity.code) }.getOrNull(),
                    bondYield10Y = bond,
                    buyThresholdMultiplier = entity.buyThresholdMultiplier,
                )
            }
            val input = TodaySignalInput(
                stocks = stockSnapshots,
                gridPlans = gridPlans,
                gridCurrentPrices = snapshots.mapValues { it.value.price ?: 0.0 },
                dividends = dividends,
                today = date,
                // 已买档不再出现在「网格下一档」信号里（每档只买一次）
                gridTransactionsByStock = runCatching { transactionRepository.getAll() }
                    .getOrDefault(emptyList()).groupBy { it.stockCode },
            )
            val signals = TodaySignalAggregator.aggregate(input)

            // 3. 组合表现行 + 分红行 + 体检行 + 市场行（各块缺数据自动省略）
            val portfolioLine = buildPortfolioLine(stockSnapshots, snapshots, indices)
            val dividendLine = signals.count { it.type == TodaySignalType.DIVIDEND_COUNTDOWN }
                .takeIf { it > 0 }?.let { "未来30天${it}笔除权" }
            val diagnosisLine = runCatching {
                val prices = stocks.filter { it.shares > 0 }.mapNotNull { s ->
                    snapshots[s.code]?.price?.takeIf { it > 0.0 }?.let { s.code to it }
                }.toMap()
                diagnosisAssembler.assemble(stocks.filter { it.shares > 0 }, prices)
            }.getOrNull()?.let(::buildDiagnosisLine)
            val marketLine = runCatching {
                marketDataPlane.getIndustryList(MarketDataRepository.SortBy.CHANGE, limit = 30)
            }.getOrDefault(emptyList())
                .takeIf { it.isNotEmpty() }
                ?.let(MarketMoodCalculator::splitGainersLosers)
                ?.takeIf { it.topGainers.isNotEmpty() || it.topLosers.isNotEmpty() }
                ?.let(::buildMarketLine)

            // 4. prompt → LLM → 解析 → 缓存
            val prompt = TodayBriefingPromptBuilder.build(
                portfolioLine, signals, dividendLine,
                diagnosisLine = diagnosisLine,
                marketLine = marketLine,
            )
            val response = llmApi.chatCompletions(
                url = config.baseUrl.removeSuffix("/") + "/chat/completions",
                auth = "Bearer ${config.apiKey}",
                body = LlmChatRequest(
                    model = config.model,
                    messages = listOf(LlmMessage("user", prompt)),
                ),
            )
            val briefing = TodayBriefingParser.parse(response.content.orEmpty())
            cacheDao.upsert(
                LlmAnalysisCacheEntity(
                    cacheKey = cacheKey(date),
                    scope = SCOPE,
                    payload = briefing,
                    createdAt = System.currentTimeMillis(),
                )
            )
            true
        } catch (_: Exception) {
            false // 红线 #2：失败静默
        }
    }

    suspend fun read(date: LocalDate): String? = withContext(Dispatchers.IO) {
        runCatching { cacheDao.get(cacheKey(date), SCOPE)?.payload }.getOrNull()
    }

    /** 组合今日平均涨跌 + 跑赢沪深300（pp）。详细盈亏由前台 VM 算；此处仅喂 LLM 一句概览。 */
    private fun buildPortfolioLine(
        stocks: List<TodayStockSnapshot>,
        snapshots: Map<String, QuoteSnapshot>,
        indices: List<IndexQuote>,
    ): String {
        val avgChange = stocks.mapNotNull { snapshots[it.code]?.changePct }
            .takeIf { it.isNotEmpty() }?.average() ?: 0.0
        val hs300 = indices.firstOrNull { it.code == "000300" }?.changePct
        val beatText = hs300?.let { "（跑赢沪深300 %+.2fpp）".format(avgChange - it) } ?: ""
        return "组合今日 %+.2f%%%s".format(avgChange, beatText)
    }

    /** 体检喂料行：估值锚（股息率/国债/利差）+ 单股集中度。核心字段缺失时返回 null。 */
    private fun buildDiagnosisLine(d: PortfolioRiskDiagnosis): String? {
        val yield = d.weightedDividendYieldPct ?: return null
        return buildString {
            append("加权股息率 %.2f%%".format(yield))
            d.bondYield10yPct?.let { append("，10Y国债 %.2f%%".format(it)) }
            d.yieldSpreadPct?.let { append("，利差%+.2fpp".format(it)) }
            d.stockCr1?.let { append("，单股最大权重 %.0f%%".format(it)) }
        }
    }

    /** 市场喂料行：领涨/领跌板块名（各 Top3）。 */
    private fun buildMarketLine(mood: MarketMood): String {
        val gainers = mood.topGainers.mapNotNull { it.name }.takeIf { it.isNotEmpty() }
        val losers = mood.topLosers.mapNotNull { it.name }.takeIf { it.isNotEmpty() }
        return listOfNotNull(
            gainers?.let { "领涨板块 ${it.joinToString("、")}" },
            losers?.let { "领跌板块 ${it.joinToString("、")}" },
        ).joinToString("；")
    }

    private fun cacheKey(date: LocalDate): String = "today_briefing_$date"

    companion object {
        const val SCOPE = "TODAY_BRIEFING"
    }
}
