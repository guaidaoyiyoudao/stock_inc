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
}
