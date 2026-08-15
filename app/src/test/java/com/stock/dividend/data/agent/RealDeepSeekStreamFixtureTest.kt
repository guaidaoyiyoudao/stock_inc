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
 * 用**真实 DeepSeek Responses 流式响应结构**（含大量 reasoning_text.delta）回归测试
 * OpenAiCompatibleModel 的 Responses 路径——确保 reasoning 事件不影响最终文本提取。
 * fixture 取自 2026-08-06 真实 curl 抓包（事件序列简化但结构保真）。
 */
class RealDeepSeekStreamFixtureTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }

    private fun model() = OpenAiCompatibleModel(
        config = LlmConfig(baseUrl = server.url("/v1/").toString(), apiKey = "sk-test", model = "deepseek-chat"),
        client = OkHttpClient(),
        useResponsesApi = true,
        includeWebSearch = true,
        effectiveModel = "deepseek-v4-flash",
    )

    @Test
    fun realDeepSeekStream_withReasoningAndOutputText_classifiesThoughtCorrectly() = runTest {
        // 真实结构：created → 大量 reasoning_text.delta → output_text.delta → completed
        val sse = buildString {
            append("event: response.created\ndata: {\"type\":\"response.created\"}\n\n")
            append("event: response.in_progress\ndata: {\"type\":\"response.in_progress\"}\n\n")
            append("event: response.output_item.added\ndata: {\"type\":\"response.output_item.added\",\"item\":{\"type\":\"reasoning\"}}\n\n")
            // reasoning 增量（真实有 45 个，这里取几个代表）
            listOf("我们", "只需要", "一句话", "回复").forEach { d ->
                append("event: response.reasoning_text.delta\ndata: {\"type\":\"response.reasoning_text.delta\",\"delta\":\"$d\"}\n\n")
            }
            append("event: response.reasoning_text.done\ndata: {\"type\":\"response.reasoning_text.done\",\"text\":\"我们只需要一句话回复。\"}\n\n")
            append("event: response.output_item.done\ndata: {\"type\":\"response.output_item.done\",\"item\":{\"type\":\"reasoning\"}}\n\n")
            append("event: response.output_item.added\ndata: {\"type\":\"response.output_item.added\",\"item\":{\"type\":\"message\"}}\n\n")
            // 真正的最终文本增量
            listOf("你好", "！很高兴", "见到你").forEach { d ->
                append("event: response.output_text.delta\ndata: {\"type\":\"response.output_text.delta\",\"delta\":\"$d\"}\n\n")
            }
            append("event: response.output_text.done\ndata: {\"type\":\"response.output_text.done\",\"text\":\"你好！很高兴见到你\"}\n\n")
            append("event: response.output_item.done\ndata: {\"type\":\"response.output_item.done\",\"item\":{\"type\":\"message\"}}\n\n")
            append("event: response.completed\ndata: {\"type\":\"response.completed\",\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"你好！很高兴见到你\"}]}]}\n\n")
        }
        server.enqueue(MockResponse().setBody(sse))
        val responses = model().generateContent(
            LlmRequest(contents = listOf(Content(role = Role.USER, parts = listOf(Part(text = "hi"))))),
            stream = true,
        ).toList()
        val partials = responses.filter { it.partial }
        // reasoning partial → Part(thought=true)；output partial → Part(thought 不为 true)
        val reasoningParts = partials.flatMap { it.content!!.parts }.filter { it.thought == true }
        val outputParts = partials.flatMap { it.content!!.parts }.filter { it.thought != true }
        assertThat(reasoningParts.mapNotNull { it.text }.joinToString("")).isEqualTo("我们只需要一句话回复")
        assertThat(reasoningParts.all { it.thought == true }).isTrue()
        assertThat(outputParts.mapNotNull { it.text }.joinToString("")).isEqualTo("你好！很高兴见到你")
        assertThat(outputParts.all { it.thought != true }).isTrue()
        // 最终事件含完整可见文本（reasoning 不混入）
        val final = responses.last()
        assertThat(final.partial).isFalse()
        assertThat(final.content!!.parts.mapNotNull { it.text }.joinToString("")).isEqualTo("你好！很高兴见到你")
    }

    @Test
    fun realDeepSeekNonStream_withReasoningItem_extractsMessageTextOnly() = runTest {
        // 非流式：output 含 reasoning item + message item，只取 message 的 output_text
        server.enqueue(MockResponse().setBody(
            """{"status":"completed","output":[""" +
            """{"type":"reasoning","content":[{"type":"reasoning_text","text":"内部思考..."}]},""" +
            """{"type":"message","content":[{"type":"output_text","text":"对外回复：你好"}]}""" +
            """]}"""
        ))
        val responses = model().generateContent(
            LlmRequest(contents = listOf(Content(role = Role.USER, parts = listOf(Part(text = "hi"))))),
            stream = false,
        ).toList()
        val final = responses.single()
        val text = final.content!!.parts.mapNotNull { it.text }.joinToString("")
        assertThat(text).isEqualTo("对外回复：你好")
        assertThat(text).doesNotContain("内部思考") // reasoning 不泄漏
    }
}
