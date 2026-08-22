package com.stock.dividend.data.agent

import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FinishReason
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import com.google.gson.Gson

/** ADK LlmRequest → OpenAI Chat Completions 请求。纯函数。 */
internal fun buildOpenAiRequest(
    llmRequest: LlmRequest,
    modelName: String,
    gson: Gson = Gson(),
): OpenAiChatRequest {
    val messages = mutableListOf<OpenAiMessage>()
    llmRequest.config.systemInstruction?.let { sys ->
        val text = sys.parts.mapNotNull { it.text }.joinToString("\n").trim()
        if (text.isNotEmpty()) messages += OpenAiMessage(role = "system", content = text)
    }
    llmRequest.contents.forEach { content ->
        val text = content.parts.mapNotNull { it.text }.joinToString("")
        val toolCalls = content.parts.mapNotNull { it.functionCall }
        // 仅用户侧携带图片（inlineData → image_url data URL）；模型不产图。
        val imageUrls = if (content.role == Role.USER) {
            content.parts.mapNotNull { it.imageDataUrl() }
        } else emptyList()
        when {
            toolCalls.isNotEmpty() -> messages += OpenAiMessage(
                role = if (content.role == Role.USER) "user" else "assistant",
                content = text.ifEmpty { null },
                toolCalls = toolCalls.map { fc ->
                    OpenAiToolCall(
                        id = fc.id ?: "call_${fc.name}",
                        function = OpenAiFunctionCall(name = fc.name, arguments = gson.toJson(fc.args))
                    )
                }
            )
            // 仅含 functionResponse 的 content 不产生普通消息（纯图片 content 有 imageUrls 兜底，不跳过）
            text.isEmpty() && imageUrls.isEmpty() -> Unit
            content.role == Role.MODEL -> messages += OpenAiMessage(
                role = "assistant", content = text.ifEmpty { null }
            )
            imageUrls.isNotEmpty() -> messages += OpenAiMessage(
                role = "user",
                // 多模态消息 content 必须是数组（text 段 + image_url 段），纯文本仍用字符串以最小改动请求体
                content = buildList {
                    if (text.isNotEmpty()) add(OpenAiContentPart(type = "text", text = text))
                    imageUrls.forEach { add(OpenAiContentPart(type = "image_url", imageUrl = OpenAiImageUrl(it))) }
                }
            )
            else -> messages += OpenAiMessage(role = "user", content = text.ifEmpty { null })
        }
        content.parts.filter { it.functionResponse != null }.forEach { part ->
            val fr = part.functionResponse!!
            messages += OpenAiMessage(
                role = "tool",
                toolCallId = fr.id ?: "call_${fr.name}",
                content = gson.toJson(fr.response)
            )
        }
    }
    val tools = llmRequest.config.tools
        ?.flatMap { it.functionDeclarations.orEmpty() }
        ?.map { fd ->
            OpenAiTool(
                function = OpenAiFunction(
                    name = fd.name,
                    description = fd.description,
                    parameters = fd.parameters?.let { toOpenAiSchema(it) }
                )
            )
        }
        ?.takeIf { it.isNotEmpty() }
    val config = llmRequest.config
    return OpenAiChatRequest(
        model = modelName,
        messages = messages,
        tools = tools,
        temperature = config.temperature?.toDouble(),
        topP = config.topP?.toDouble(),
        maxTokens = config.maxOutputTokens,
        stop = config.stopSequences?.takeIf { it.isNotEmpty() },
    )
}

/** ADK Schema → OpenAI JSON Schema 的 Map 表示。纯函数。 */
internal fun toOpenAiSchema(schema: Schema): Map<String, Any?> = buildMap {
    schema.type?.let { put("type", it.toOpenAiTypeName()) }
    schema.properties?.let { props ->
        put("properties", props.mapValues { (_, v) -> toOpenAiSchema(v) })
    }
    schema.items?.let { put("items", toOpenAiSchema(it)) }
    schema.required?.let { put("required", it) }
    schema.description?.let { put("description", it) }
    schema.enum?.let { put("enum", it) }
}

private fun Type.toOpenAiTypeName(): String = when (this) {
    Type.OBJECT -> "object"
    Type.STRING -> "string"
    Type.NUMBER -> "number"
    Type.INTEGER -> "integer"
    Type.BOOLEAN -> "boolean"
    Type.ARRAY -> "array"
    else -> "string"
}

/** OpenAI 响应 → ADK LlmResponse。纯函数。 */
internal fun toLlmResponse(
    response: OpenAiChatResponse,
    gson: Gson = Gson(),
): LlmResponse {
    val choice = response.choices.firstOrNull()
        ?: return LlmResponse(errorMessage = "模型返回空响应")
    val message = choice.message ?: return LlmResponse(errorMessage = "模型返回空消息")
    val parts = mutableListOf<Part>()
    // content 兼容 String（普通文本）与数组（多模态请求，仅用户侧发送、响应不会出现）
    (message.content as? String)?.takeIf { it.isNotBlank() }?.let { parts += Part(text = it) }
    message.toolCalls?.forEach { tc ->
        val args = runCatching {
            gson.fromJson(tc.function.arguments, Map::class.java) as? Map<String, Any?>
        }.getOrNull().orEmpty()
        parts += Part(
            functionCall = FunctionCall(name = tc.function.name, args = args, id = tc.id)
        )
    }
    return LlmResponse(
        content = if (parts.isEmpty()) null else Content(role = Role.MODEL, parts = parts),
        finishReason = choice.finishReason.toFinishReason(),
    )
}

private fun String?.toFinishReason(): FinishReason? = when (this) {
    "stop", "tool_calls" -> FinishReason.STOP
    "length" -> FinishReason.MAX_TOKENS
    "content_filter" -> FinishReason.SAFETY
    else -> null
}
