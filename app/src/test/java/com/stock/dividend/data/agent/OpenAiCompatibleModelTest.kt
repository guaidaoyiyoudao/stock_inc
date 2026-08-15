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

class OpenAiCompatibleModelTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun model() = OpenAiCompatibleModel(
        config = LlmConfig(baseUrl = server.url("/v1/").toString(), apiKey = "sk-test", model = "m-test"),
        client = OkHttpClient()
    )

    private fun responsesModel(
        includeWebSearch: Boolean = true,
        effectiveModel: String = "deepseek-v4-flash",
        // 模拟真实用户预设的 DeepSeek baseUrl（带 /v1/），验证 Responses 路径会去 /v1
        baseUrlPath: String = "/v1/",
    ) = OpenAiCompatibleModel(
        config = LlmConfig(baseUrl = server.url(baseUrlPath).toString(), apiKey = "sk-test", model = "deepseek-chat"),
        client = OkHttpClient(),
        useResponsesApi = true,
        includeWebSearch = includeWebSearch,
        effectiveModel = effectiveModel,
    )

    @Test
    fun nonStreamingRequest_mapsResponseToToolCalls() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"choices":[{"message":{"role":"assistant","tool_calls":[{"id":"c1","type":"function","function":{"name":"add_stock","arguments":"{\"code\":\"600519\"}"}}]},"finish_reason":"tool_calls"}]}"""
            )
        )
        val request = LlmRequest(
            contents = listOf(Content(role = Role.USER, parts = listOf(Part(text = "加自选"))))
        )
        val responses = model().generateContent(request, stream = false).toList()
        val recorded = server.takeRequest()
        assertThat(recorded.getHeader("Authorization")).isEqualTo("Bearer sk-test")
        assertThat(recorded.path).isEqualTo("/v1/chat/completions")
        val body = recorded.body.readUtf8()
        assertThat(body).contains("\"model\":\"m-test\"")
        assertThat(body).contains("\"stream\":false")
        val final = responses.single()
        val fc = final.content!!.parts.mapNotNull { it.functionCall }.single()
        assertThat(fc.name).isEqualTo("add_stock")
        assertThat(fc.args).containsEntry("code", "600519")
    }

    @Test
    fun streamingMode_producesPartialTextAndFinalResponse() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                data: {"choices":[{"delta":{"content":"你"}}]}

                data: {"choices":[{"delta":{"content":"好"}}]}

                data: [DONE]

                """.trimIndent()
            )
        )
        val responses = model().generateContent(
            LlmRequest(contents = listOf(Content(role = Role.USER, parts = listOf(Part(text = "hi"))))),
            stream = true
        ).toList()
        assertThat(
            responses.filter { it.partial }
                .flatMap { it.content!!.parts.mapNotNull { p -> p.text } }
        ).containsExactly("你", "好")
        val final = responses.last()
        assertThat(final.partial).isFalse()
        assertThat(final.content!!.parts.mapNotNull { it.text }.joinToString("")).isEqualTo("你好")
    }

    @Test
    fun non2xxHttp_throwsException() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"bad key"}"""))
        val error = runCatching {
            model().generateContent(
            LlmRequest(contents = listOf(Content(role = Role.USER, parts = listOf(Part(text = "x"))))),
            stream = false
        ).toList()
        }
        assertThat(error.isFailure).isTrue()
    }

    // ── Responses API（/responses）路径 ──
    // 关键：DeepSeek 用户预设 baseUrl 多为 "…/v1/"，但 Responses API 端点是
    // https://api.deepseek.com/responses（不带 /v1），须去 /v1 后再拼，否则 404。

    @Test
    fun responsesApi_stripsV1FromBaseUrlPath() = runTest {
        // 真实场景：baseUrl=.../v1/，Responses 路径应得到 /responses 而非 /v1/responses
        server.enqueue(MockResponse().setBody("""{"output":[{"type":"message","content":[{"type":"output_text","text":"x"}]}]}"""))
        responsesModel().generateContent(
            LlmRequest(contents = listOf(Content(role = Role.USER, parts = listOf(Part(text = "hi"))))),
            stream = false
        ).toList()
        assertThat(server.takeRequest().path).isEqualTo("/responses")
    }

    @Test
    fun responsesApi_worksWithBaseUrlWithoutV1() = runTest {
        // baseUrl 不带 /v1 时（如自定义），Responses 路径同样得到 /responses
        server.enqueue(MockResponse().setBody("""{"output":[{"type":"message","content":[{"type":"output_text","text":"x"}]}]}"""))
        responsesModel(baseUrlPath = "/").generateContent(
            LlmRequest(contents = listOf(Content(role = Role.USER, parts = listOf(Part(text = "hi"))))),
            stream = false
        ).toList()
        assertThat(server.takeRequest().path).isEqualTo("/responses")
    }

    @Test
    fun responsesApi_nonStreamingRequest_mapsOutputToToolCalls() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"output":[{"type":"function_call","call_id":"c1","name":"add_stock","arguments":"{\"code\":\"600519\"}"}]}"""
            )
        )
        val responses = responsesModel().generateContent(
            LlmRequest(contents = listOf(Content(role = Role.USER, parts = listOf(Part(text = "加自选"))))),
            stream = false
        ).toList()
        val recorded = server.takeRequest()
        assertThat(recorded.path).isEqualTo("/responses")
        assertThat(recorded.getHeader("Authorization")).isEqualTo("Bearer sk-test")
        val body = recorded.body.readUtf8()
        assertThat(body).contains("\"model\":\"deepseek-v4-flash\"")
        assertThat(body).contains("\"stream\":false")
        val final = responses.single()
        val fc = final.content!!.parts.mapNotNull { it.functionCall }.single()
        assertThat(fc.name).isEqualTo("add_stock")
        assertThat(fc.args).containsEntry("code", "600519")
    }

    @Test
    fun responsesApi_includesWebSearchTool() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"output":[{"type":"message","content":[{"type":"output_text","text":"ok"}]}]}"""
            )
        )
        responsesModel(includeWebSearch = true).generateContent(
            LlmRequest(contents = listOf(Content(role = Role.USER, parts = listOf(Part(text = "查新闻"))))),
            stream = false
        ).toList()
        val body = server.takeRequest().body.readUtf8()
        assertThat(body).contains("\"type\":\"web_search\"")
    }

    @Test
    fun responsesApi_streamingMode_producesPartialTextAndFinal() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                event: response.output_text.delta
                data: {"delta":"你"}

                event: response.output_text.delta
                data: {"delta":"好"}

                event: response.completed
                data: {"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"你好"}]}]}

                """.trimIndent()
            )
        )
        val responses = responsesModel().generateContent(
            LlmRequest(contents = listOf(Content(role = Role.USER, parts = listOf(Part(text = "hi"))))),
            stream = true
        ).toList()
        assertThat(
            responses.filter { it.partial }
                .flatMap { it.content!!.parts.mapNotNull { p -> p.text } }
        ).containsExactly("你", "好")
        val final = responses.last()
        assertThat(final.partial).isFalse()
        assertThat(final.content!!.parts.mapNotNull { it.text }.joinToString("")).isEqualTo("你好")
    }

    @Test
    fun responsesApi_doesNotTouchChatCompletionsPathWhenDisabled() = runTest {
        // 默认 useResponsesApi=false 的 model() 仍走 /v1/chat/completions
        server.enqueue(
            MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"x"},"finish_reason":"stop"}]}""")
        )
        model().generateContent(
            LlmRequest(contents = listOf(Content(role = Role.USER, parts = listOf(Part(text = "x"))))),
            stream = false
        ).toList()
        assertThat(server.takeRequest().path).isEqualTo("/v1/chat/completions")
    }
}
