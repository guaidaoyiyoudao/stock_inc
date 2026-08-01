package com.stock.dividend.data.agent

import com.google.gson.Gson

/** 解析单行 SSE（`data: {...}`）；非数据行 / `[DONE]` / 解析失败返回 null。纯函数。 */
internal fun parseSseDataLine(line: String, gson: Gson = Gson()): OpenAiSseChunk? {
    val trimmed = line.trim()
    if (!trimmed.startsWith("data:")) return null
    val payload = trimmed.removePrefix("data:").trim()
    if (payload.isEmpty() || payload == "[DONE]") return null
    return runCatching { gson.fromJson(payload, OpenAiSseChunk::class.java) }.getOrNull()
}

/** 流式累积器：文本增量 + tool_calls 按 index 拼接。 */
internal class SseAccumulator {
    private val textBuilder = StringBuilder()
    private val toolCalls = LinkedHashMap<Int, MutableOpenAiToolCall>()

    /** 处理一个 chunk，返回本块新增文本（无则空串）。 */
    fun onChunk(chunk: OpenAiSseChunk): String {
        var delta = ""
        chunk.choices.firstOrNull()?.delta?.let { d ->
            d.content?.let {
                textBuilder.append(it)
                delta = it
            }
            d.toolCalls?.forEach { tc ->
                val acc = toolCalls.getOrPut(tc.index) { MutableOpenAiToolCall() }
                tc.id?.let { acc.id = it }
                tc.function?.name?.let { acc.name += it }
                tc.function?.arguments?.let { acc.arguments.append(it) }
            }
        }
        return delta
    }

    /** 流结束：合成最终完整响应。 */
    fun finish(): OpenAiChatResponse {
        val calls = toolCalls.values.map {
            OpenAiToolCall(
                id = it.id,
                function = OpenAiFunctionCall(name = it.name, arguments = it.arguments.toString())
            )
        }
        return OpenAiChatResponse(
            choices = listOf(
                OpenAiChoice(
                    message = OpenAiMessage(
                        role = "assistant",
                        content = textBuilder.toString().ifEmpty { null },
                        toolCalls = calls.ifEmpty { null }
                    ),
                    finishReason = if (calls.isEmpty()) "stop" else "tool_calls"
                )
            )
        )
    }

    private class MutableOpenAiToolCall {
        var id: String = ""
        var name: String = ""
        val arguments = StringBuilder()
    }
}
