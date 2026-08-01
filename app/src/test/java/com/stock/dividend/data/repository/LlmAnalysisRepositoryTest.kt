package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.remote.LlmApi
import com.stock.dividend.data.remote.dto.LlmChatRequest
import com.stock.dividend.data.remote.dto.LlmChatResponse
import com.stock.dividend.data.remote.dto.LlmMessage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

/** 测试用配置源：直接喂固定 [LlmConfig]，绕开 SharedPreferences。 */
private class TestConfigSource(private val flow: Flow<LlmConfig>) : LlmConfigSource {
    override fun observeConfig(): Flow<LlmConfig> = flow
}

/** suspend 接口不能用 SAM lambda，用匿名对象包一层。 */
private fun api(block: suspend (String, String, LlmChatRequest) -> LlmChatResponse): LlmApi =
    object : LlmApi {
        override suspend fun chatCompletions(url: String, auth: String, body: LlmChatRequest) =
            block(url, auth, body)
    }

class LlmAnalysisRepositoryTest {

    private val stock = EvaluatedStock(
        code = "600036", name = "招行", industry = "银行",
        action = HoldingAction.BUY, priceVsLower = 0.1, dividendYield = 4.0,
        bollBand = null, currentPrice = 10.0, reasons = emptyList()
    )
    private val signals = PortfolioSignals(
        PositionControlSignal(false, 0.0, 0.0, 15), emptyList()
    )
    private val input = PortfolioLlmInput(
        evaluation = listOf(stock),
        dailyBands = emptyMap(),
        monthlyBands = emptyMap(),
        signals = signals,
        thresholds = DividendThresholds()
    )
    private val stockInput = StockLlmInput(
        code = "600036", name = "招行", industry = "银行",
        currentPrice = 10.0, dividendRatePoints = listOf(4.0),
        latestDividendYield = 4.0, forecast = null, buyThreshold = null,
        bollDaily = null, bollWeekly = null, bollMonthly = null, fundamentals = null
    )

    private val cacheStore: LlmAnalysisCacheStore = mockk {
        coEvery { getPortfolio(any()) } returns null
        coEvery { getStock(any()) } returns null
        coEvery { putPortfolio(any(), any(), any()) } just runs
        coEvery { putStock(any(), any(), any()) } just runs
    }

    private fun repo(config: LlmConfig, api: LlmApi): LlmAnalysisRepository =
        LlmAnalysisRepository(api, TestConfigSource(flowOf(config)), cacheStore)

    @Test
    fun `returns NotConfigured when key missing`() = runTest {
        val r = repo(LlmConfig("https://x/", "", "m"), api { _, _, _ -> resp(""""x"""") })
            .analyze(input)
        assertThat(r).isInstanceOf(LlmAnalysisResult.NotConfigured::class.java)
    }

    @Test
    fun `returns NotConfigured when stocks empty`() = runTest {
        val r = repo(LlmConfig("https://x/", "k", "m"), api { _, _, _ -> resp(""""x"""") })
            .analyze(input.copy(evaluation = emptyList()))
        assertThat(r).isInstanceOf(LlmAnalysisResult.NotConfigured::class.java)
    }

    @Test
    fun `returns Success on valid response and writes cache`() = runTest {
        val api = api { _, _, _ -> resp("""{"overview":"ok","stockComments":{},"risks":[]}""") }
        val r = repo(LlmConfig("https://api.deepseek.com/v1/", "k", "deepseek-chat"), api)
            .analyze(input)
        assertThat(r).isInstanceOf(LlmAnalysisResult.Success::class.java)
        assertThat((r as LlmAnalysisResult.Success).analysis.overview).isEqualTo("ok")
        assertThat(r.fromCache).isFalse()
        coVerify { cacheStore.putPortfolio(any(), any(), any()) }
    }

    @Test
    fun `fresh cache hit returns without calling api`() = runTest {
        coEvery { cacheStore.getPortfolio(any()) } returns PortfolioCacheEntry(
            LlmAnalysis("cached", emptyMap(), emptyList()), System.currentTimeMillis()
        )
        var calls = 0
        val api = api { _, _, _ -> calls++; resp("""{"overview":"x"}""") }
        val r = repo(LlmConfig("https://x/", "k", "m"), api).analyze(input)
        assertThat(r).isInstanceOf(LlmAnalysisResult.Success::class.java)
        assertThat((r as LlmAnalysisResult.Success).analysis.overview).isEqualTo("cached")
        assertThat(r.fromCache).isTrue()
        assertThat(calls).isEqualTo(0)
    }

    @Test
    fun `forceRefresh bypasses fresh cache`() = runTest {
        coEvery { cacheStore.getPortfolio(any()) } returns PortfolioCacheEntry(
            LlmAnalysis("cached", emptyMap(), emptyList()), System.currentTimeMillis()
        )
        val api = api { _, _, _ -> resp("""{"overview":"fresh","stockComments":{},"risks":[]}""") }
        val r = repo(LlmConfig("https://x/", "k", "m"), api).analyze(input, forceRefresh = true)
        assertThat((r as LlmAnalysisResult.Success).analysis.overview).isEqualTo("fresh")
        assertThat(r.fromCache).isFalse()
    }

    @Test
    fun `forceRefresh failure falls back to stale cache with notice`() = runTest {
        coEvery { cacheStore.getPortfolio(any()) } returns PortfolioCacheEntry(
            LlmAnalysis("old", emptyMap(), emptyList()), 1L
        )
        val api = api { _, _, _ -> throw java.io.IOException("timeout") }
        val r = repo(LlmConfig("https://x/", "k", "m"), api).analyze(input, forceRefresh = true)
        assertThat(r).isInstanceOf(LlmAnalysisResult.Success::class.java)
        assertThat((r as LlmAnalysisResult.Success).analysis.overview).isEqualTo("old")
        assertThat(r.fromCache).isTrue()
        assertThat(r.notice).contains("刷新失败")
    }

    @Test
    fun `plain failure with no cache maps to network error`() = runTest {
        val api = api { _, _, _ -> throw java.io.IOException("timeout") }
        val r = repo(LlmConfig("https://x/", "k", "m"), api).analyze(input)
        assertThat((r as LlmAnalysisResult.Error).message).contains("网络")
        coVerify(exactly = 0) { cacheStore.putPortfolio(any(), any(), any()) }
    }

    @Test
    fun `http 401 maps to API key error`() = runTest {
        val api = api { _, _, _ -> throw httpErr(401) }
        val r = repo(LlmConfig("https://x/", "k", "m"), api).analyze(input)
        assertThat((r as LlmAnalysisResult.Error).message).isEqualTo("API key 无效")
    }

    @Test
    fun `not configured still returns fresh cache`() = runTest {
        coEvery { cacheStore.getPortfolio(any()) } returns PortfolioCacheEntry(
            LlmAnalysis("cached", emptyMap(), emptyList()), System.currentTimeMillis()
        )
        val r = repo(LlmConfig("", "", ""), api { _, _, _ -> resp(""""x"""") }).analyze(input)
        assertThat(r).isInstanceOf(LlmAnalysisResult.Success::class.java)
        assertThat((r as LlmAnalysisResult.Success).fromCache).isTrue()
    }

    // ===== analyzeStock =====

    @Test
    fun `analyzeStock returns Success and writes stock cache`() = runTest {
        val api = api { _, _, _ -> resp("""{"valuation":"偏低","dividendSustainability":"稳","action":"可关注","risks":[]}""") }
        val r = repo(LlmConfig("https://x/", "k", "m"), api).analyzeStock(stockInput)
        assertThat(r).isInstanceOf(StockLlmAnalysisResult.Success::class.java)
        assertThat((r as StockLlmAnalysisResult.Success).analysis.valuation).isEqualTo("偏低")
        coVerify { cacheStore.putStock(any(), any(), any()) }
    }

    @Test
    fun `analyzeStock cache hit returns without calling api`() = runTest {
        coEvery { cacheStore.getStock(any()) } returns StockCacheEntry(
            StockLlmAnalysis("cached", "", "", emptyList()), System.currentTimeMillis()
        )
        var calls = 0
        val api = api { _, _, _ -> calls++; resp(""""x"""") }
        val r = repo(LlmConfig("https://x/", "k", "m"), api).analyzeStock(stockInput)
        assertThat(r).isInstanceOf(StockLlmAnalysisResult.Success::class.java)
        assertThat((r as StockLlmAnalysisResult.Success).analysis.valuation).isEqualTo("cached")
        assertThat(r.fromCache).isTrue()
        assertThat(calls).isEqualTo(0)
    }

    @Test
    fun `analyzeStock returns NotConfigured when key missing`() = runTest {
        val r = repo(LlmConfig("https://x/", "", "m"), api { _, _, _ -> resp(""""x"""") })
            .analyzeStock(stockInput)
        assertThat(r).isInstanceOf(StockLlmAnalysisResult.NotConfigured::class.java)
    }

    private fun resp(content: String) = LlmChatResponse(
        listOf(LlmChatResponse.Choice(LlmMessage("assistant", content)))
    )

    private fun httpErr(code: Int) = HttpException(
        Response.error<Any>(code, "".toResponseBody("application/json".toMediaTypeOrNull()))
    )
}
