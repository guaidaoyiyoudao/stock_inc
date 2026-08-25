package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.local.dao.LlmAnalysisCacheDao
import com.stock.dividend.data.local.entity.LlmAnalysisCacheEntity
import com.stock.dividend.data.plane.MarketDataPlane
import com.stock.dividend.data.remote.LlmApi
import com.stock.dividend.data.remote.dto.LlmChatResponse
import com.stock.dividend.data.remote.dto.LlmMessage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.LocalDate

class TodayBriefingCoordinatorTest {

    private val marketDataPlane: MarketDataPlane = mockk()
    private val gridPlanRepository: GridPlanRepository = mockk()
    private val strategyPlanRepository: StrategyPlanRepository = mockk()
    private val transactionRepository: TransactionRepository = mockk(relaxed = true)
    // 装配器用真实实现 + 上述 mock 依赖（纯编排，无本地状态，真实例比 mock 更接近生产行为）
    private val strategyInputAssembler = StrategyInputAssembler(marketDataPlane, transactionRepository)
    private val diagnosisAssembler: PortfolioDiagnosisAssembler = mockk()
    private val llmApi: LlmApi = mockk()
    private val llmConfigRepository: LlmConfigRepository = mockk()
    private val cacheDao: LlmAnalysisCacheDao = mockk(relaxed = true)

    private val today = LocalDate.of(2026, 8, 12)

    private fun coordinator() = TodayBriefingCoordinator(
        marketDataPlane, gridPlanRepository, strategyPlanRepository, strategyInputAssembler,
        transactionRepository, diagnosisAssembler, llmApi, llmConfigRepository, cacheDao,
    )

    private fun stubEmptyData() {
        coEvery { marketDataPlane.observeAllStocks() } returns flowOf(emptyList())
        coEvery { marketDataPlane.getQuoteSnapshots(any(), any()) } returns emptyMap()
        coEvery { marketDataPlane.get10YBondYield(any()) } returns 2.6
        coEvery { marketDataPlane.getDps(any()) } returns null
        coEvery { gridPlanRepository.observeAll() } returns flowOf(emptyList())
        coEvery { strategyPlanRepository.observeAll() } returns flowOf(emptyList())
        coEvery { marketDataPlane.getAllDividendsWithExDate() } returns emptyList()
        coEvery { marketDataPlane.getIndexQuotes() } returns emptyList()
        coEvery { marketDataPlane.getIndustryList(any<MarketDataRepository.SortBy>(), any()) } returns emptyList()
        coEvery { diagnosisAssembler.assemble(any(), any()) } returns null
    }

    @Test
    fun notConfigured_returnsFalse_noLlmCall() = runTest {
        coEvery { llmConfigRepository.snapshot() } returns LlmConfig("", "", "")
        val ok = coordinator().generateAndCache(today)
        assertThat(ok).isFalse()
        coVerify(exactly = 0) { llmApi.chatCompletions(any(), any(), any()) }
    }

    @Test
    fun success_writesCache() = runTest {
        coEvery { llmConfigRepository.snapshot() } returns LlmConfig("http://x/v1/", "k", "m")
        stubEmptyData()
        coEvery { llmApi.chatCompletions(any(), any(), any()) } returns
            LlmChatResponse(listOf(LlmChatResponse.Choice(LlmMessage("assistant", "今日平静。"))))
        val ok = coordinator().generateAndCache(today)
        assertThat(ok).isTrue()
        coVerify {
            cacheDao.upsert(match {
                it.scope == "TODAY_BRIEFING" && it.payload == "今日平静。"
            })
        }
    }

    @Test
    fun read_returnsCachedPayload() = runTest {
        coEvery { cacheDao.get(eq("today_briefing_2026-08-12"), eq("TODAY_BRIEFING")) } returns
            LlmAnalysisCacheEntity("today_briefing_2026-08-12", "TODAY_BRIEFING", "缓存的一句话", 0L)
        assertThat(coordinator().read(today)).isEqualTo("缓存的一句话")
    }

    @Test
    fun read_missing_returnsNull() = runTest {
        coEvery { cacheDao.get(any(), any()) } returns null
        assertThat(coordinator().read(today)).isNull()
    }

    @Test
    fun diagnosisAndMarket_feedsIntoPrompt() = runTest {
        coEvery { llmConfigRepository.snapshot() } returns LlmConfig("http://x/v1/", "k", "m")
        stubEmptyData()
        // 体检：股息率 4% vs 国债 3% → 利差 +1pp；单股 CR1 40%
        coEvery { diagnosisAssembler.assemble(any(), any()) } returns PortfolioRiskDiagnosis(
            holdingCount = 1, totalMarketValue = 1000.0,
            industryHhi = null, industryCr3 = null, topIndustries = emptyList(),
            stockHhi = null, stockCr1 = 40.0, stockCr3 = null, topHoldings = emptyList(),
            dividendSourceCr3 = null, fragileDividendWeightPct = null, highPayoutCodes = emptyList(),
            weightedDividendYieldPct = 4.0, bondYield10yPct = 3.0, yieldSpreadPct = 1.0,
            suggestions = emptyList(),
        )
        fun industryItem(name: String, changePct: Double) = MarketListItem(
            code = null, name = name, price = null, changePct = changePct,
            pe = null, pb = null, totalMarketCap = null, turnoverRate = null,
            industry = null, mainNetInflow = null, mainNetInflowPct = null,
            leaderName = null, leaderCode = null, leaderChangePct = null,
        )
        coEvery {
            marketDataPlane.getIndustryList(MarketDataRepository.SortBy.CHANGE, any())
        } returns listOf(industryItem("银行", 2.0), industryItem("煤炭", -1.0))
        coEvery { llmApi.chatCompletions(any(), any(), any()) } returns
            LlmChatResponse(listOf(LlmChatResponse.Choice(LlmMessage("assistant", "ok。"))))

        coordinator().generateAndCache(today)

        // 体检行与市场行均进入 prompt（LLM 据此解读估值锚与板块温度）
        coVerify {
            llmApi.chatCompletions(any(), any(), match { body ->
                // content 为 Any（多模态扩展），文本路径恒为 String
                val prompt = body.messages.first().content as? String ?: ""
                prompt.contains("【组合体检】") && prompt.contains("利差+1.00pp") &&
                    prompt.contains("【市场】") && prompt.contains("领涨板块 银行")
            })
        }
    }
}
