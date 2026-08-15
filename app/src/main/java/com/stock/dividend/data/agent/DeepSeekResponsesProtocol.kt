package com.stock.dividend.data.agent

import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.FinishReason
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName

/**
 * DeepSeek Responses API（`POST /responses`）协议层。
 *
 * 与 [buildOpenAiRequest]（Chat Completions `/chat/completions`）是**两套独立 API 格式**：
 * - 请求用 `input`（字符串或 input-items 数组）+ `instructions`，而非 `messages` + system；
 * - function 工具声明为 `{type:"function", name, parameters}`（顶层 name）；
 * - 工具历史用 `function_call` / `function_call_output` input item（靠 call_id 配对）；
 * - `web_search` 为服务端内置工具，**仅在 Responses API 可用**。
 *
 * 所有转换均为纯函数，配单测（[DeepSeekResponsesProtocolTest]）。schema 转换复用
 * [toOpenAiSchema]（Responses API 的 function parameters 与 Chat Completions 都是标准 JSON Schema）。
 */

//region ── 请求 DTO ──

/**
 * Responses API 请求体。input 既可字符串也可数组，这里用 [Any]（Gson 按实际类型序列化）。
 * 保持字段名与 DeepSeek/OpenAI Responses API 文档一致（snake_case via SerializedName）。
 */
internal data class ResponsesRequest(
    val model: String,
    val input: Any,
    val instructions: String? = null,
    val tools: List<ResponsesTool>? = null,
    val temperature: Double? = null,
    @SerializedName("top_p") val topP: Double? = null,
    @SerializedName("max_output_tokens") val maxOutputTokens: Int? = null,
    val stop: List<String>? = null,
    val stream: Boolean = false,
)

/** input-items 数组的一个元素（消息）。type 缺省时由 role 推断，这里显式写 "message"。 */
internal data class ResponsesMessageItem(
    val type: String = "message",
    val role: String,
    val content: String,
)

/** function_call input item：模型上一轮发起的工具调用（回放历史时用）。 */
internal data class ResponsesFunctionCallItem(
    val type: String = "function_call",
    @SerializedName("call_id") val callId: String,
    val name: String,
    val arguments: String,
)

/** function_call_output input item：工具执行结果（靠 call_id 与 function_call 配对）。 */
internal data class ResponsesFunctionCallOutputItem(
    val type: String = "function_call_output",
    @SerializedName("call_id") val callId: String,
    val output: String,
)

/** tools 数组元素：function 工具。 */
internal data class ResponsesFunctionTool(
    val type: String = "function",
    val name: String,
    val description: String? = null,
    val parameters: Map<String, Any?>? = null,
) : ResponsesTool

/** tools 数组元素：web_search 内置工具。 */
internal data class ResponsesWebSearchTool(
    val type: String = "web_search",
) : ResponsesTool

/**
 * tools 数组的联合元素。Responses API 的 tools 是异构数组（function / web_search），
 * 两种 item 共享顶层 `type` 判别字段。用 sealed + Gson 直序列化子类字段（每个子类自带 type）。
 */
internal sealed interface ResponsesTool

//endregion

//region ── 响应 DTO ──

/** Responses API 响应（非流式整体 / 流式 response.completed 的 payload）。 */
internal data class ResponsesResponse(
    val id: String? = null,
    val status: String? = null,
    val output: List<JsonObject> = emptyList(),
)

//endregion

//region ── 请求构建：ADK LlmRequest → ResponsesRequest ──

/**
 * 把 ADK [LlmRequest] 翻译为 DeepSeek Responses API 请求。纯函数。
 *
 * @param includeWebSearch 是否在 tools 末尾追加 `{type:"web_search"}`（服务端联网工具）。
 */
internal fun buildResponsesRequest(
    llmRequest: LlmRequest,
    modelName: String,
    includeWebSearch: Boolean = false,
): ResponsesRequest {
    val inputItems = mutableListOf<Any>()

    // systemInstruction 走 instructions 字段（不放入 input）
    val instructions = llmRequest.config.systemInstruction
        ?.parts?.mapNotNull { it.text }?.joinToString("\n")?.trim()
        ?.ifEmpty { null }

    llmRequest.contents.forEach { content ->
        appendContentToInput(content, inputItems)
    }

    val tools = buildList<ResponsesTool> {
        llmRequest.config.tools
            ?.flatMap { it.functionDeclarations.orEmpty() }
            ?.forEach { fd ->
                add(
                    ResponsesFunctionTool(
                        name = fd.name,
                        description = fd.description,
                        parameters = fd.parameters?.let { toOpenAiSchema(it) },
                    )
                )
            }
        if (includeWebSearch) add(ResponsesWebSearchTool())
    }.takeIf { it.isNotEmpty() }

    val config = llmRequest.config
    return ResponsesRequest(
        model = modelName,
        input = inputItems.ifEmpty { "" },
        instructions = instructions,
        tools = tools,
        temperature = config.temperature?.toDouble(),
        topP = config.topP?.toDouble(),
        maxOutputTokens = config.maxOutputTokens,
        stop = config.stopSequences?.takeIf { it.isNotEmpty() },
    )
}

/** 把一个 ADK Content 拆成若干 input item（消息 / function_call / function_call_output）。 */
private fun appendContentToInput(content: Content, out: MutableList<Any>) {
    val text = content.parts.mapNotNull { it.text }.joinToString("")
    val toolCalls = content.parts.mapNotNull { it.functionCall }
    val functionResponses = content.parts.mapNotNull { it.functionResponse }

    // 1) 普通文本消息（仅当有文本，且不是纯 functionResponse content）
    if (text.isNotEmpty()) {
        val role = when (content.role) {
            Role.MODEL -> "assistant"
            Role.SYSTEM -> "system"
            else -> "user"
        }
        out += ResponsesMessageItem(role = role, content = text)
    }

    // 2) 模型发起的工具调用 → function_call item（assistant 侧历史）
    toolCalls.forEach { fc ->
        out += ResponsesFunctionCallItem(
            callId = fc.id ?: "call_${fc.name}",
            name = fc.name,
            arguments = gsonDefault().toJson(fc.args),
        )
    }

    // 3) 工具执行结果 → function_call_output item
    functionResponses.forEach { fr ->
        out += ResponsesFunctionCallOutputItem(
            callId = fr.id ?: "call_${fr.name}",
            output = gsonDefault().toJson(fr.response),
        )
    }
}

/** 共享 Gson 实例（纯函数场景下避免反复 new）。 */
private fun gsonDefault(): Gson = GsonHolder.gson

private object GsonHolder {
    val gson: Gson = Gson()
}

//endregion

//region ── 响应解析：ResponsesResponse（output JSON 数组）→ LlmResponse ──

/**
 * 把 Responses API 的 output 数组解析为 ADK [LlmResponse]。纯函数。
 *
 * - `message` item → 取 content[].text（type=output_text）→ Part(text)；
 * - `function_call` item → name/arguments/call_id → Part(functionCall)；
 * - `web_search_call` / 其他 item → 忽略（不影响 ADK 循环）。
 *
 * arguments 按 JSON 反序列化为 Map；解析失败回退空 Map（与 Chat Completions 路径口径一致）。
 */
internal fun toLlmResponse(
    response: ResponsesResponse,
    gson: Gson = Gson(),
): LlmResponse {
    if (response.output.isEmpty()) return LlmResponse(errorMessage = "模型返回空响应")
    val parts = mutableListOf<Part>()
    response.output.forEach { item ->
        when (item.getSafeString("type")) {
            "message" -> {
                val contentArr = item.getAsJsonArray("content") ?: return@forEach
                contentArr.forEach { elem ->
                    val part = elem.asJsonObject
                    if (part.getSafeString("type") == "output_text") {
                        part.getSafeString("text")?.takeIf { it.isNotBlank() }?.let {
                            parts += Part(text = it)
                        }
                    }
                }
            }
            "function_call" -> {
                val name = item.getSafeString("name") ?: return@forEach
                val args = parseArgs(item.getSafeString("arguments"), gson)
                val callId = item.getSafeString("call_id")
                parts += Part(functionCall = FunctionCall(name = name, args = args, id = callId))
            }
            else -> Unit // web_search_call / reasoning 等忽略
        }
    }
    return LlmResponse(
        content = if (parts.isEmpty()) null else Content(role = Role.MODEL, parts = parts),
        finishReason = inferFinishReason(response.status, parts),
    )
}

internal fun JsonObject.getSafeString(key: String): String? =
    runCatching { get(key)?.takeIf { !it.isJsonNull }?.asString }.getOrNull()

private fun parseArgs(arguments: String?, gson: Gson): Map<String, Any?> =
    runCatching {
        arguments?.let {
            gson.fromJson(it, Map::class.java) as? Map<String, Any?>
        }
    }.getOrNull().orEmpty()

/** status=completed 且无 function_call → STOP；有 function_call 也 → STOP（ADK 不区分 tool_calls）。 */
private fun inferFinishReason(status: String?, parts: List<Part>): FinishReason? {
    if (status == "incomplete") return FinishReason.MAX_TOKENS
    if (status == "failed") return FinishReason.SAFETY
    return FinishReason.STOP
}

//endregion
