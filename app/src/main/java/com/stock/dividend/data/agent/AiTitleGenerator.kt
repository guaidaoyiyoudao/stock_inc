package com.stock.dividend.data.agent

import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.GenerateContentConfig
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.stock.dividend.data.repository.LlmConfig
import com.stock.dividend.di.LlmClient
import kotlinx.coroutines.flow.firstOrNull
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/** 用 LLM 为会话生成标题（复用 OpenAI 兼容配置，独立轻量调用）。 */
@Singleton
class AiTitleGenerator @Inject constructor(
    @LlmClient private val client: OkHttpClient,
) {

    suspend fun generate(config: LlmConfig, userText: String, replyText: String): String? {
        val request = LlmRequest(
            config = GenerateContentConfig(
                systemInstruction = Content(role = Role.SYSTEM, parts = listOf(Part(text = TITLE_PROMPT)))
            ),
            contents = listOf(
                Content(role = Role.USER, parts = listOf(Part(text = userText))),
                Content(role = Role.MODEL, parts = listOf(Part(text = replyText.take(500)))),
            )
        )
        val response = OpenAiCompatibleModel(config, client)
            .generateContent(request, stream = false)
            .firstOrNull() ?: return null
        val raw = response.content?.parts?.mapNotNull { it.text }?.joinToString("")?.trim()
        return raw
            ?.removePrefix("\"")
            ?.removeSuffix("\"")
            ?.removePrefix("'")
            ?.removeSuffix("'")
            ?.take(20)
            ?.takeIf { it.isNotBlank() }
    }

    companion object {
        val TITLE_PROMPT: String =
            "根据下面这段对话生成一个不超过 12 个字的会话标题。直接输出标题本身，不要引号、冒号或解释。"
    }
}
