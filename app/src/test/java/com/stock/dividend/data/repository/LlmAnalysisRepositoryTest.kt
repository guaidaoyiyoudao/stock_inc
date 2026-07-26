package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.remote.LlmApi
import com.stock.dividend.data.remote.dto.LlmChatRequest
import com.stock.dividend.data.remote.dto.LlmChatResponse
import com.stock.dividend.data.remote.dto.LlmMessage
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

    private fun repo(config: LlmConfig, api: LlmApi): LlmAnalysisRepository =
        LlmAnalysisRepository(api, TestConfigSource(flowOf(config)))

    @Test
    fun `returns NotConfigured when key missing`() = runTest {
        val r = repo(LlmConfig("https://x/", "", "m"), api { _, _, _ -> resp(""""x"""") })
            .analyze(listOf(stock), emptyMap(), emptyMap(), signals, DividendThresholds())
        assertThat(r).isInstanceOf(LlmAnalysisResult.NotConfigured::class.java)
    }

    @Test
    fun `returns NotConfigured when stocks empty`() = runTest {
        val r = repo(LlmConfig("https://x/", "k", "m"), api { _, _, _ -> resp(""""x"""") })
            .analyze(emptyList(), emptyMap(), emptyMap(), signals, DividendThresholds())
        assertThat(r).isInstanceOf(LlmAnalysisResult.NotConfigured::class.java)
    }

    @Test
    fun `returns Success on valid response`() = runTest {
        val api = api { _, _, _ -> resp("""{"overview":"ok","stockComments":{},"risks":[]}""") }
        val r = repo(LlmConfig("https://api.deepseek.com/v1/", "k", "deepseek-chat"), api)
            .analyze(listOf(stock), emptyMap(), emptyMap(), signals, DividendThresholds())
        assertThat(r).isInstanceOf(LlmAnalysisResult.Success::class.java)
        assertThat((r as LlmAnalysisResult.Success).analysis.overview).isEqualTo("ok")
    }

    @Test
    fun `http 401 maps to API key error`() = runTest {
        val api = api { _, _, _ -> throw httpErr(401) }
        val r = repo(LlmConfig("https://x/", "k", "m"), api).analyze(listOf(stock), emptyMap(), emptyMap(), signals, DividendThresholds())
        assertThat((r as LlmAnalysisResult.Error).message).isEqualTo("API key 无效")
    }

    @Test
    fun `io exception maps to network error`() = runTest {
        val api = api { _, _, _ -> throw java.io.IOException("timeout") }
        val r = repo(LlmConfig("https://x/", "k", "m"), api).analyze(listOf(stock), emptyMap(), emptyMap(), signals, DividendThresholds())
        assertThat((r as LlmAnalysisResult.Error).message).contains("网络")
    }

    private fun resp(content: String) = LlmChatResponse(
        listOf(LlmChatResponse.Choice(LlmMessage("assistant", content)))
    )

    private fun httpErr(code: Int) = HttpException(
        Response.error<Any>(code, "".toResponseBody("application/json".toMediaTypeOrNull()))
    )
}
