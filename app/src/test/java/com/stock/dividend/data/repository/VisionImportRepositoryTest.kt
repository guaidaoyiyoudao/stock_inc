package com.stock.dividend.data.repository

import android.graphics.Bitmap
import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.remote.LlmApi
import com.stock.dividend.data.remote.dto.LlmChatRequest
import com.stock.dividend.data.remote.dto.LlmChatResponse
import com.stock.dividend.data.remote.dto.LlmContentPart
import com.stock.dividend.data.remote.dto.LlmMessage
import com.stock.dividend.data.scan.ParsedHoldingRow
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

/** 视觉导入编排：fake LlmApi + mock 配置源，覆盖四态映射与自动重试（5 次）。Robolectric 提供 android.util.Base64。 */
@RunWith(RobolectricTestRunner::class)
class VisionImportRepositoryTest {

    private val llmConfigRepository: LlmConfigRepository = mockk()
    private val bitmap: Bitmap = mockk(relaxed = true)

    /** 记录每次请求并按序回放响应/异常；脚本耗尽时回放最后一项（供重试耗尽用例）。 */
    private class FakeLlmApi(script: List<Any>) : LlmApi {
        private val script = ArrayDeque<Any>(script)
        private var last: Any? = null
        val requests = mutableListOf<LlmChatRequest>()
        override suspend fun chatCompletions(
            url: String,
            auth: String,
            body: LlmChatRequest,
        ): LlmChatResponse {
            requests.add(body)
            val next = script.removeFirstOrNull() ?: last ?: error("没有更多 scripted 响应")
            last = next
            return when (next) {
                is LlmChatResponse -> next
                is Throwable -> throw next
                else -> error("不支持的 scripted 类型")
            }
        }
    }

    private fun configured() {
        every { llmConfigRepository.visionSnapshot() } returns LlmConfig(
            baseUrl = "https://open.bigmodel.cn/api/paas/v4/",
            apiKey = "test-key",
            model = "glm-4.6v-flash"
        )
    }

    private fun notConfigured() {
        every { llmConfigRepository.visionSnapshot() } returns LlmConfig(
            baseUrl = "https://open.bigmodel.cn/api/paas/v4/",
            apiKey = "",
            model = "glm-4.6v-flash"
        )
    }

    private fun resp(content: String?): LlmChatResponse = LlmChatResponse(
        choices = if (content == null) emptyList() else listOf(LlmChatResponse.Choice(LlmMessage("assistant", content)))
    )

    private fun httpErr(code: Int): HttpException =
        HttpException(Response.error<LlmChatResponse>(code, ResponseBody.create(null, "")))

    @Test
    fun `holdings success sends multimodal request and parses rows`() = runTest {
        configured()
        val api = FakeLlmApi(
            mutableListOf(
                resp("""{"screenshotType":"HOLDINGS","rows":[{"name":"贵州茅台","code":"600519","shares":100,"costPerShare":1500.5}]}""")
            )
        )
        val repo = VisionImportRepository(api, llmConfigRepository)

        val result = repo.parse(bitmap, VisionParseMode.HOLDINGS)

        assertThat(result).isInstanceOf(VisionImportResult.Holdings::class.java)
        val rows = (result as VisionImportResult.Holdings).rows
        assertThat(rows).containsExactly(ParsedHoldingRow("", "600519", 100, 1500.5, "贵州茅台"))

        // 请求体：system 纯文本 + user 为 content parts 数组（文本 + image_url data URL）
        val request = api.requests.single()
        assertThat(request.messages.first().content).isInstanceOf(String::class.java)
        val userParts = request.messages[1].content as List<*>
        assertThat(userParts).hasSize(2)
        val textPart = userParts[0] as LlmContentPart
        assertThat(textPart.type).isEqualTo("text")
        val imagePart = userParts[1] as LlmContentPart
        assertThat(imagePart.type).isEqualTo("image_url")
        assertThat(imagePart.imageUrl!!.url).startsWith("data:image/jpeg;base64,")
        assertThat(request.responseFormat).isNull() // 视觉请求省略 response_format
        assertThat(request.maxTokens).isEqualTo(4096)
    }

    @Test
    fun `transactions success parses rows`() = runTest {
        configured()
        val api = FakeLlmApi(
            mutableListOf(
                resp("""{"screenshotType":"TRANSACTIONS","rows":[{"name":"贵州茅台","code":"600519","type":"证券买入","shares":100,"price":15.5,"date":"20260801"}]}""")
            )
        )
        val repo = VisionImportRepository(api, llmConfigRepository)

        val result = repo.parse(bitmap, VisionParseMode.TRANSACTIONS)

        assertThat(result).isInstanceOf(VisionImportResult.Transactions::class.java)
        val row = (result as VisionImportResult.Transactions).rows.single()
        assertThat(row.type).isEqualTo("BUY")
        assertThat(row.date).isEqualTo("2026-08-01")
    }

    @Test
    fun `missing key returns NotConfigured`() = runTest {
        notConfigured()
        val api = FakeLlmApi(mutableListOf())
        val repo = VisionImportRepository(api, llmConfigRepository)

        val result = repo.parse(bitmap, VisionParseMode.HOLDINGS)

        assertThat(result).isEqualTo(VisionImportResult.NotConfigured)
        assertThat(api.requests).isEmpty()
    }

    @Test
    fun `http 401 fails immediately without retry`() = runTest {
        configured()
        val api = FakeLlmApi(mutableListOf(httpErr(401), httpErr(401)))
        val repo = VisionImportRepository(api, llmConfigRepository)

        val result = repo.parse(bitmap, VisionParseMode.HOLDINGS)

        assertThat(result).isInstanceOf(VisionImportResult.Error::class.java)
        assertThat((result as VisionImportResult.Error).message).contains("API key")
        assertThat(api.requests).hasSize(1) // 不重试
    }

    @Test
    fun `io error retries then succeeds`() = runTest {
        configured()
        val api = FakeLlmApi(
            mutableListOf(
                IOException("timeout"),
                resp("""{"screenshotType":"HOLDINGS","rows":[{"name":"贵州茅台","code":"600519","shares":100,"costPerShare":1500.0}]}"""),
            )
        )
        val repo = VisionImportRepository(api, llmConfigRepository)
        val retries = mutableListOf<Int>()

        val result = repo.parse(bitmap, VisionParseMode.HOLDINGS) { attempt, max, reason ->
            retries.add(attempt)
            assertThat(max).isEqualTo(5)
            assertThat(reason).isEqualTo("网络错误")
        }

        assertThat(result).isInstanceOf(VisionImportResult.Holdings::class.java)
        assertThat(api.requests).hasSize(2)
        assertThat(retries).containsExactly(1)
    }

    @Test
    fun `io error exhausts after five retries`() = runTest {
        configured()
        val api = FakeLlmApi(mutableListOf(IOException("timeout")))
        val repo = VisionImportRepository(api, llmConfigRepository)
        val retries = mutableListOf<Int>()

        val result = repo.parse(bitmap, VisionParseMode.HOLDINGS) { attempt, _, _ -> retries.add(attempt) }

        assertThat(result).isInstanceOf(VisionImportResult.Error::class.java)
        assertThat((result as VisionImportResult.Error).message).contains("已自动重试 5 次")
        // 首次 + 5 次重试 = 6 次请求
        assertThat(api.requests).hasSize(6)
        assertThat(retries).containsExactly(1, 2, 3, 4, 5).inOrder()
    }

    @Test
    fun `http 5xx is retryable`() = runTest {
        configured()
        val api = FakeLlmApi(
            mutableListOf(
                httpErr(503),
                resp("""{"screenshotType":"TRANSACTIONS","rows":[{"name":"贵州茅台","type":"BUY","shares":100,"price":15.5,"date":"2026-08-01"}]}"""),
            )
        )
        val repo = VisionImportRepository(api, llmConfigRepository)

        val result = repo.parse(bitmap, VisionParseMode.TRANSACTIONS)

        assertThat(result).isInstanceOf(VisionImportResult.Transactions::class.java)
        assertThat(api.requests).hasSize(2)
    }

    @Test
    fun `invalid model output is retried`() = runTest {
        configured()
        val api = FakeLlmApi(
            mutableListOf(
                resp("抱歉，我无法解析"),
                resp("""{"screenshotType":"HOLDINGS","rows":[{"name":"贵州茅台","code":"600519","shares":100,"costPerShare":1500.0}]}"""),
            )
        )
        val repo = VisionImportRepository(api, llmConfigRepository)

        val result = repo.parse(bitmap, VisionParseMode.HOLDINGS)

        assertThat(result).isInstanceOf(VisionImportResult.Holdings::class.java)
        assertThat(api.requests).hasSize(2)
    }

    @Test
    fun `empty rows yields friendly error without retry`() = runTest {
        configured()
        val api = FakeLlmApi(mutableListOf(resp("""{"screenshotType":"HOLDINGS","rows":[]}""")))
        val repo = VisionImportRepository(api, llmConfigRepository)

        val result = repo.parse(bitmap, VisionParseMode.HOLDINGS)

        assertThat(result).isInstanceOf(VisionImportResult.Error::class.java)
        assertThat((result as VisionImportResult.Error).message).contains("未在截图中识别到持仓行")
        assertThat(api.requests).hasSize(1)
    }

    @Test
    fun `null content yields error without retry`() = runTest {
        configured()
        val api = FakeLlmApi(mutableListOf(resp(null)))
        val repo = VisionImportRepository(api, llmConfigRepository)

        val result = repo.parse(bitmap, VisionParseMode.TRANSACTIONS)

        assertThat(result).isInstanceOf(VisionImportResult.Error::class.java)
        assertThat((result as VisionImportResult.Error).message).contains("返回为空")
        assertThat(api.requests).hasSize(1)
    }
}
