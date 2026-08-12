package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.local.dao.DividendDao
import com.stock.dividend.data.local.dao.LlmAnalysisCacheDao
import com.stock.dividend.data.local.entity.LlmAnalysisCacheEntity
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

    private val stockRepository: StockRepository = mockk()
    private val marketDataRepository: MarketDataRepository = mockk()
    private val gridPlanRepository: GridPlanRepository = mockk()
    private val dividendDao: DividendDao = mockk()
    private val bondYieldRepository: BondYieldRepository = mockk()
    private val llmApi: LlmApi = mockk()
    private val llmConfigRepository: LlmConfigRepository = mockk()
    private val cacheDao: LlmAnalysisCacheDao = mockk(relaxed = true)

    private val today = LocalDate.of(2026, 8, 12)

    private fun coordinator() = TodayBriefingCoordinator(
        stockRepository, marketDataRepository, gridPlanRepository, dividendDao,
        bondYieldRepository, llmApi, llmConfigRepository, cacheDao,
    )

    private fun stubEmptyData() {
        coEvery { stockRepository.observeAllStocks() } returns flowOf(emptyList())
        coEvery { stockRepository.fetchQuoteSnapshots(any()) } returns emptyMap()
        coEvery { bondYieldRepository.fetch10YBondYield(any()) } returns 2.6
        coEvery { gridPlanRepository.observeAll() } returns flowOf(emptyList())
        coEvery { dividendDao.getAllWithExDate() } returns emptyList()
        coEvery { marketDataRepository.fetchIndexQuotes() } returns emptyList()
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
}
