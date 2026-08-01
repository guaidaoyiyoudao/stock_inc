package com.stock.dividend.data.agent

import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.repository.LlmConfig
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class AiTitleGeneratorTest {
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

    @Test
    fun generate_producesAndParsesTitle() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"choices":[{"message":{"role":"assistant","content":"持仓分析"},"finish_reason":"stop"}]}"""
            )
        )
        val generator = AiTitleGenerator(OkHttpClient())
        val title = generator.generate(
            LlmConfig(baseUrl = server.url("/v1/").toString(), apiKey = "k", model = "m"),
            userText = "我的持仓怎么样",
            replyText = "你持有 2 只股票…"
        )
        assertThat(title).isEqualTo("持仓分析")
        val requestBody = server.takeRequest().body.readUtf8()
        assertThat(requestBody).contains("标题")
        assertThat(requestBody).contains("我的持仓怎么样")
    }

    @Test
    fun emptyResponse_returnsNull() = runTest {
        server.enqueue(MockResponse().setBody("""{"choices":[]}"""))
        val generator = AiTitleGenerator(OkHttpClient())
        val title = generator.generate(
            LlmConfig(baseUrl = server.url("/v1/").toString(), apiKey = "k", model = "m"),
            userText = "x",
            replyText = "y"
        )
        assertThat(title).isNull()
    }
}
