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

/** 测试用配置源：直接喂固定 [LlmConfig]，绕开 SharedPreferences。与 LlmAnalysisRepositoryTest 同包，故重命名避免冲突。 */
private class ScreenshotTestConfigSource(private val flow: Flow<LlmConfig>) : LlmConfigSource {
    override fun observeConfig(): Flow<LlmConfig> = flow
}

/** suspend 接口不能用 SAM lambda，用匿名对象包一层。 */
private fun screenshotApi(block: suspend (String, String, LlmChatRequest) -> LlmChatResponse): LlmApi =
    object : LlmApi {
        override suspend fun chatCompletions(url: String, auth: String, body: LlmChatRequest) =
            block(url, auth, body)
    }

class ScreenshotStrategyRepositoryTest {

    private val completeCfg = LlmConfig("https://api.x.com/v1/", "k", "m")
    private val incompleteCfg = LlmConfig("", "", "")

    private fun repo(cfg: LlmConfig, api: LlmApi) =
        ScreenshotStrategyRepository(api, ScreenshotTestConfigSource(flowOf(cfg)))

    @Test
    fun `NotConfigured when config incomplete`() = runTest {
        val r = repo(incompleteCfg, screenshotApi { _, _, _ -> resp("x") }).analyze("text")
        assertThat(r).isEqualTo(ScreenshotStrategyState.NotConfigured)
    }

    @Test
    fun `Success on actionable response`() = runTest {
        val api = screenshotApi { _, _, _ -> resp("""{"isActionable":true,"targetText":"招商银行","direction":"BUY"}""") }
        val r = repo(completeCfg, api).analyze("text")
        assertThat(r).isInstanceOf(ScreenshotStrategyState.Success::class.java)
        assertThat((r as ScreenshotStrategyState.Success).strategy.targetText).isEqualTo("招商银行")
    }

    @Test
    fun `NoStrategy when not actionable`() = runTest {
        val api = screenshotApi { _, _, _ -> resp("""{"isActionable":false}""") }
        val r = repo(completeCfg, api).analyze("text")
        assertThat(r).isInstanceOf(ScreenshotStrategyState.NoStrategy::class.java)
    }

    @Test
    fun `empty content maps to error`() = runTest {
        val api = screenshotApi { _, _, _ -> resp("") }
        val r = repo(completeCfg, api).analyze("text")
        // 空串经 Parser → Failed("") → Error("LLM 响应解析失败，请重试")
        assertThat(r).isInstanceOf(ScreenshotStrategyState.Error::class.java)
    }

    @Test
    fun `null content maps to error`() = runTest {
        val api = screenshotApi { _, _, _ -> LlmChatResponse(emptyList()) }
        val r = repo(completeCfg, api).analyze("text")
        assertThat((r as ScreenshotStrategyState.Error).message).isEqualTo("LLM 返回为空")
    }

    @Test
    fun `http 401 maps to API key error`() = runTest {
        val api = screenshotApi { _, _, _ -> throw httpErr(401) }
        val r = repo(completeCfg, api).analyze("text")
        assertThat((r as ScreenshotStrategyState.Error).message).isEqualTo("API key 无效")
    }

    @Test
    fun `io exception maps to network error`() = runTest {
        val api = screenshotApi { _, _, _ -> throw java.io.IOException("timeout") }
        val r = repo(completeCfg, api).analyze("text")
        assertThat((r as ScreenshotStrategyState.Error).message).contains("网络")
    }

    private fun resp(content: String) = LlmChatResponse(
        listOf(LlmChatResponse.Choice(LlmMessage("assistant", content)))
    )

    private fun httpErr(code: Int) = HttpException(
        Response.error<Any>(code, "".toResponseBody("application/json".toMediaTypeOrNull()))
    )
}
