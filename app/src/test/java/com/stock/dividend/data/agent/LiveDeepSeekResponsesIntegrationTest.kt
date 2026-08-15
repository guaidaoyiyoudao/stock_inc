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
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 连真实 DeepSeek Responses API 的集成测试。
 * 需环境变量 DEEPSEEK_API_KEY；无 key 时 assumeTrue 自动 skip（不阻断 CI，不计失败）。
 *
 * 目的：确认 OpenAiCompatibleModel 的 Responses 路径对真实服务器能正常工作，
 * 捕获「测试桩正常但线上异常」的运行时差异。
 */
class LiveDeepSeekResponsesIntegrationTest {

    private val apiKey: String = System.getenv("DEEPSEEK_API_KEY").orEmpty()

    private fun model() = OpenAiCompatibleModel(
        config = LlmConfig(
            baseUrl = "https://api.deepseek.com/v1/",
            apiKey = apiKey,
            model = "deepseek-chat",  // 用户预设值；开启联网后应被 effectiveModel 覆盖
        ),
        client = OkHttpClient.Builder().readTimeout(180, java.util.concurrent.TimeUnit.SECONDS).build(),
        useResponsesApi = true,
        includeWebSearch = true,
        effectiveModel = "deepseek-v4-flash",
    )

    @Test
    fun live_streaming_extractsFinalText() = runTest {
        assumeTrue("需 DEEPSEEK_API_KEY", apiKey.isNotBlank())
        val responses = model().generateContent(
            LlmRequest(
                config = com.google.adk.kt.types.GenerateContentConfig(
                    systemInstruction = Content(role = Role.SYSTEM, parts = listOf(Part(text = "你是助手，一句话回复")))
                ),
                contents = listOf(Content(role = Role.USER, parts = listOf(Part(text = "你好")))
            )),
            stream = true,
        ).toList()
        // 推理模型：partial 中 reasoning 部分（thought=true）与 output 部分（thought≠true）必须正确分类
        val allParts = responses.filter { it.partial }.flatMap { it.content?.parts.orEmpty() }
        val thoughtParts = allParts.filter { it.thought == true }
        val normalParts = allParts.filter { it.thought != true }
        println("[LIVE] thought partials=${thoughtParts.size}, normal partials=${normalParts.size}")
        val final = responses.lastOrNull()
        println("[LIVE] final text='${final?.content?.parts?.mapNotNull { it.text }?.joinToString("")}'")
        println("[LIVE] final errorMessage='${final?.errorMessage}'")
        assertThat(final).isNotNull()
        // 最终事件必须有文本或错误信息（不能是空静默）
        val hasText = final!!.content?.parts?.any { !it.text.isNullOrBlank() } == true
        val hasError = !final.errorMessage.isNullOrBlank()
        assertThat(hasText || hasError).isTrue()
        // v4-flash 是推理模型，必定有 reasoning partial 且带 thought=true（本轮改动的核心）
        assertThat(thoughtParts).isNotEmpty()
        assertThat(thoughtParts.all { it.thought == true }).isTrue()
    }

    @Test
    fun live_webSearch_returnsContent() = runTest {
        assumeTrue("需 DEEPSEEK_API_KEY", apiKey.isNotBlank())
        val responses = model().generateContent(
            LlmRequest(
                config = com.google.adk.kt.types.GenerateContentConfig(
                    systemInstruction = Content(role = Role.SYSTEM, parts = listOf(Part(text = "你是助手")))
                ),
                contents = listOf(Content(role = Role.USER, parts = listOf(Part(text = "今天A股大盘怎么样？简短回答")))
            )),
            stream = false,
        ).toList()
        val final = responses.single()
        println("[LIVE web_search] content='${final.content?.parts?.mapNotNull { it.text }?.joinToString("")?.take(150)}'")
        println("[LIVE web_search] errorMessage='${final.errorMessage}'")
        val hasText = final.content?.parts?.any { !it.text.isNullOrBlank() } == true
        assertThat(hasText).isTrue()
    }
}
