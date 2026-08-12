package com.stock.dividend.data.repository

import com.stock.dividend.data.local.dao.DividendDao
import com.stock.dividend.data.local.dao.LlmAnalysisCacheDao
import com.stock.dividend.data.local.entity.LlmAnalysisCacheEntity
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
 * 缓存复用 `llm_analysis_cache` 表，scope = [SCOPE]，key = "today_briefing_yyyy-MM-dd"。
 *
 * 注：后台编排口径略放宽——`latestYearlyDividend` 不在此装配（需按股聚合分红表），
 * 故 BOLL 共振信号仍生效，股息率达线信号缺位；完整两类信号由前台 [TodayViewModel] 装配。
 */
@Singleton
class TodayBriefingCoordinator @Inject constructor(
    private val stockRepository: StockRepository,
    private val marketDataRepository: MarketDataRepository,
    private val gridPlanRepository: GridPlanRepository,
    private val dividendDao: DividendDao,
    private val bondYieldRepository: BondYieldRepository,
    private val llmApi: LlmApi,
    private val llmConfigRepository: LlmConfigRepository,
    private val cacheDao: LlmAnalysisCacheDao,
) {
    suspend fun generateAndCache(date: LocalDate): Boolean = withContext(Dispatchers.IO) {
        val config = llmConfigRepository.snapshot()
        if (!config.isComplete) return@withContext false
        try {
            // 1. 拉数据（各步吞异常返回空，红线 #2）
            val stocks = runCatching { stockRepository.observeAllStocks().first() }.getOrDefault(emptyList())
            val snapshots = runCatching { stockRepository.fetchQuoteSnapshots(stocks) }.getOrDefault(emptyMap())
            val bond = runCatching { bondYieldRepository.fetch10YBondYield() }.getOrDefault(BondYieldRepository.DEFAULT_YIELD)
            val gridPlans = runCatching { gridPlanRepository.observeAll().first() }.getOrDefault(emptyList())
            val dividends = runCatching { dividendDao.getAllWithExDate() }.getOrDefault(emptyList())
            val indices = runCatching { marketDataRepository.fetchIndexQuotes() }.getOrDefault(emptyList())

            // 2. 聚合信号（每只股拉周线 BOLL；后台 Worker 容忍耗时）
            val stockSnapshots = stocks.map { entity ->
                TodayStockSnapshot(
                    code = entity.code,
                    name = entity.name,
                    price = snapshots[entity.code]?.price,
                    weeklyBand = runCatching { stockRepository.fetchBoll(entity.code) }.getOrNull(),
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
            )
            val signals = TodaySignalAggregator.aggregate(input)

            // 3. 组合表现行 + 分红行
            val portfolioLine = buildPortfolioLine(stockSnapshots, snapshots, indices)
            val dividendLine = signals.count { it.type == TodaySignalType.DIVIDEND_COUNTDOWN }
                .takeIf { it > 0 }?.let { "未来30天${it}笔除权" }

            // 4. prompt → LLM → 解析 → 缓存
            val prompt = TodayBriefingPromptBuilder.build(portfolioLine, signals, dividendLine)
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

    private fun cacheKey(date: LocalDate): String = "today_briefing_$date"

    companion object {
        const val SCOPE = "TODAY_BRIEFING"
    }
}
