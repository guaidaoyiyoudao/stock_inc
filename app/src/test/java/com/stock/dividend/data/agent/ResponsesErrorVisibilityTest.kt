package com.stock.dividend.data.agent

import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.repository.LlmConfig
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * 回归测试：Responses API 路径下，服务端返回非预期响应（400 / response.failed / 空输出）
 * 时，错误必须**可见**——不能静默「无响应」。这是 2026-08-06 联网搜索 bug 的防御层。
 */
class ResponsesErrorVisibilityTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }

    private fun responsesModel() = OpenAiCompatibleModel(
        config = LlmConfig(baseUrl = server.url("/v1/").toString(), apiKey = "sk-test", model = "deepseek-chat"),
        client = OkHttpClient(),
        useResponsesApi = true,
        includeWebSearch = true,
        effectiveModel = "deepseek-v4-flash",
    )

    private val userReq = LlmRequest(
        contents = listOf(Content(role = Role.USER, parts = listOf(Part(text = "hi"))))
    )

    @Test
    fun http400_throwsWithBodyDetail() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"model not supported"}}"""))
        val error = runCatching {
            responsesModel().generateContent(userReq, stream = false).toList()
        }
        assertThat(error.isFailure).isTrue()
        assertThat(error.exceptionOrNull()!!.message).contains("HTTP 400")
        assertThat(error.exceptionOrNull()!!.message).contains("model not supported")
    }

    @Test
    fun streaming_responseFailedEvent_throwsReadableError() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                event: response.failed
                data: {"status":"failed","error":{"message":"工具数量超限"}}

                """.trimIndent()
            )
        )
        val error = runCatching {
            responsesModel().generateContent(userReq, stream = true).toList()
        }
        assertThat(error.isFailure).isTrue()
        // response.failed 事件必须转为可读错误，不能静默返回空响应
        assertThat(error.exceptionOrNull()!!.message).contains("失败")
    }

    @Test
    fun emptyOutput_returnsErrorMessageNotSilent() = runTest {
        // 非流式：output 为空 → toLlmResponse 返回 errorMessage，不再静默
        server.enqueue(MockResponse().setBody("""{"status":"completed","output":[]}"""))
        val results = responsesModel().generateContent(userReq, stream = false).toList()
        val final = results.single()
        assertThat(final.errorMessage).isNotNull()
        assertThat(final.content).isNull()
    }
}
