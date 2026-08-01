package com.stock.dividend.data.agent

import com.google.gson.annotations.SerializedName

/** OpenAI Chat Completions 请求体（仅 AI Tab 聊天用；分析链路不复用）。 */
internal data class OpenAiChatRequest(
    val model: String,
    val messages: List<OpenAiMessage>,
    val tools: List<OpenAiTool>? = null,
    val temperature: Double? = null,
    @SerializedName("top_p") val topP: Double? = null,
    @SerializedName("max_tokens") val maxTokens: Int? = null,
    val stop: List<String>? = null,
    val stream: Boolean = false,
)

internal data class OpenAiMessage(
    val role: String,
    val content: String? = null,
    @SerializedName("tool_calls") val toolCalls: List<OpenAiToolCall>? = null,
    @SerializedName("tool_call_id") val toolCallId: String? = null,
)

internal data class OpenAiToolCall(
    val id: String,
    val type: String = "function",
    val function: OpenAiFunctionCall,
)

internal data class OpenAiFunctionCall(
    val name: String,
    val arguments: String,
)

internal data class OpenAiTool(
    val type: String = "function",
    val function: OpenAiFunction,
)

internal data class OpenAiFunction(
    val name: String,
    val description: String? = null,
    val parameters: Map<String, Any?>? = null,
)

internal data class OpenAiChatResponse(
    val choices: List<OpenAiChoice> = emptyList(),
)

internal data class OpenAiChoice(
    val message: OpenAiMessage? = null,
    @SerializedName("finish_reason") val finishReason: String? = null,
)

internal data class OpenAiSseChunk(
    val choices: List<OpenAiSseChoice> = emptyList(),
)

internal data class OpenAiSseChoice(
    val delta: OpenAiDelta? = null,
)

internal data class OpenAiDelta(
    val content: String? = null,
    @SerializedName("tool_calls") val toolCalls: List<OpenAiToolCallDelta>? = null,
)

internal data class OpenAiToolCallDelta(
    val index: Int = 0,
    val id: String? = null,
    val function: OpenAiFunctionCallDelta? = null,
)

internal data class OpenAiFunctionCallDelta(
    val name: String? = null,
    val arguments: String? = null,
)
