package com.stock.dividend.data.agent

import com.google.gson.Gson
import com.google.gson.JsonObject

/**
 * DeepSeek Responses API 流式 SSE 解析。
 *
 * Responses API 的 SSE 与 Chat Completions 不同——**typed events**：
 * ```
 * event: response.output_text.delta
 * data: {"item_id":"...","delta":"你好"}
 *
 * event: response.completed
 * data: {"id":"...","status":"completed","output":[...]}
 * ```
 * 即 `event:` 和 `data:` 是**两行**，需跨行配对；流以 `response.completed` 结束
 * （没有 `data: [DONE]`）。文本增量在 `delta` 字段。
 *
 * 本解析器有状态：缓存当前 event 类型，遇到 data 行时配对输出。
 */

/** 一个已配对的事件（eventType + data 的 JSON 字符串）。 */
internal data class ResponsesSseEvent(
    val type: String,
    val data: String,
)

/**
 * 跨行 SSE 行解析器：逐行喂数据，配对成功时返回 [ResponsesSseEvent]。
 *
 * - `event: <type>` 行 → 缓存 type；
 * - `data: <json>` 行 → 取出缓存的 type（无则忽略），配对输出，并复位 type；
 * - 空行 → 复位 type（防御性：单个 data 后通常跟空行分隔）；
 * - 其他行（注释 `: keep-alive` 等）→ 忽略。
 *
 * 返回 null 表示「这行不产生完整事件」（需更多行 / 忽略）。
 *
 * 注：DeepSeek Responses 文档未明确是否会有多行 data 拼接（OpenAI 规范允许 `data:` 多行），
 * 当前实测为单行 data；若后续遇到多行，可在此扩展累加。纯函数（对同一实例有状态，但无副作用）。
 */
internal class ResponsesSseLineParser {
    private var pendingEvent: String? = null

    fun feed(line: String): ResponsesSseEvent? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) {
            pendingEvent = null
            return null
        }
        if (trimmed.startsWith("event:")) {
            pendingEvent = trimmed.removePrefix("event:").trim().ifEmpty { null }
            return null
        }
        if (trimmed.startsWith("data:")) {
            val data = trimmed.removePrefix("data:").trim()
            val type = pendingEvent ?: return null // 有 data 无 event：忽略（或可记为 message，这里保守忽略）
            pendingEvent = null
            return if (data.isEmpty()) null else ResponsesSseEvent(type, data)
        }
        // 注释行（:）或其他：忽略，不动 pendingEvent
        return null
    }
}

/** `response.output_text.delta` / `response.reasoning_text.delta` 事件的 data。 */
internal data class ResponsesTextDelta(
    val delta: String? = null,
)

/**
 * 一个文本增量的分类结果：reasoning（模型思考过程，→ Part(thought=true)）
 * 还是 output（最终答案，→ 普通 Part）。UI 据此把思考过程与最终回复分开渲染。
 */
internal sealed interface SseTextDelta {
    val text: String

    /** 思考过程增量（response.reasoning_text.delta）。 */
    data class Reasoning(override val text: String) : SseTextDelta

    /** 最终答案增量（response.output_text.delta）。 */
    data class Output(override val text: String) : SseTextDelta

    /**
     * 一段思考结束信号（response.reasoning_text.done）——模型思考完准备调工具/回复。
     * 不带文本；UI 据此把「思考中…」转圈停掉。多轮时每轮思考结束都会发一次。
     */
    data object ReasoningDone : SseTextDelta {
        override val text: String = ""
    }
}

/**
 * 流式累积器：累积文本增量 + 捕获 `response.completed` 的完整 output。
 *
 * 用法：
 * 1. 用 [ResponsesSseLineParser] 逐行配对出 [ResponsesSseEvent]；
 * 2. 把每个 event 喂给 [onEvent]，返回本块新增文本（无则 null）；
 * 3. 流结束后 [finish] 给出完整 [ResponsesResponse] 供 [toLlmResponse] 解析。
 */
internal class ResponsesSseAccumulator(private val gson: Gson = Gson()) {
    private val textBuilder = StringBuilder()
    private val reasoningBuilder = StringBuilder()
    private var finalOutput: List<JsonObject> = emptyList()
    private var finalStatus: String? = null
    private var failureRawData: String? = null
    /**
     * 流式过程中累积的 function_call（工具调用）。
     * key = output_item 的 item id；value = 拼好的 {type, call_id, name, arguments}。
     * 不依赖 response.completed 的 output（实测流式 completed 只带 message，工具调用须自行累积）。
     */
    private val functionCalls = LinkedHashMap<String, JsonObject>()
    /** arguments 增量累积器：item id → StringBuilder。 */
    private val argsBuilders = LinkedHashMap<String, StringBuilder>()

    /** 处理一个已配对事件，返回文本增量（reasoning 或 output；无则 null）。 */
    fun onEvent(event: ResponsesSseEvent): SseTextDelta? {
        return when {
            // 最终答案增量
            event.type == "response.output_text.delta" -> {
                val delta = parseDelta(event.data) ?: return null
                textBuilder.append(delta)
                SseTextDelta.Output(delta)
            }
            // 思考过程增量（推理模型边想边输出；web_search 时持续 30~60s，展示给用户避免「卡住」错觉）
            event.type == "response.reasoning_text.delta" -> {
                val delta = parseDelta(event.data) ?: return null
                reasoningBuilder.append(delta)
                SseTextDelta.Reasoning(delta)
            }
            // 一段思考结束（response.reasoning_text.done）：通知 UI 停转圈。
            // 注意：多轮工具调用时每轮都有思考，每轮结束都会发此事件。
            event.type == "response.reasoning_text.done" -> SseTextDelta.ReasoningDone
            // 工具调用 item 开始：记录 call_id/name（arguments 由 delta 累积）
            event.type == "response.output_item.added" -> {
                runCatching {
                    val item = gson.fromJson(event.data, JsonObject::class.java)
                        .getAsJsonObject("item")
                    if (item.getSafeString("type") == "function_call") {
                        val id = item.getSafeString("id") ?: return@runCatching
                        functionCalls[id] = JsonObject().apply {
                            addProperty("type", "function_call")
                            addProperty("call_id", item.getSafeString("call_id"))
                            addProperty("name", item.getSafeString("name"))
                            addProperty("arguments", item.getSafeString("arguments").orEmpty())
                        }
                        argsBuilders[id] = StringBuilder()
                    }
                }
                null
            }
            // 工具调用参数增量：按 item_id 拼接 arguments
            event.type == "response.function_call_arguments.delta" -> {
                runCatching {
                    val d = gson.fromJson(event.data, JsonObject::class.java)
                    val id = d.getSafeString("item_id") ?: return@runCatching
                    argsBuilders[id]?.append(d.getSafeString("delta").orEmpty())
                }
                null
            }
            // 工具调用 item 完成：把拼好的 arguments 写回
            event.type == "response.output_item.done" -> {
                runCatching {
                    val item = gson.fromJson(event.data, JsonObject::class.java)
                        .getAsJsonObject("item")
                    if (item.getSafeString("type") == "function_call") {
                        val id = item.getSafeString("id")
                        if (id != null) {
                            val args = argsBuilders[id]?.toString()
                                ?: item.getSafeString("arguments").orEmpty()
                            functionCalls[id] = JsonObject().apply {
                                addProperty("type", "function_call")
                                addProperty("call_id", item.getSafeString("call_id"))
                                addProperty("name", item.getSafeString("name"))
                                addProperty("arguments", args)
                            }
                        }
                    }
                }
                null
            }
            event.type == "response.completed" -> {
                runCatching {
                    val resp = gson.fromJson(event.data, ResponsesResponse::class.java)
                    finalOutput = resp.output
                    finalStatus = resp.status
                }
                null // completed 事件不产文本增量
            }
            // 失败/被截断事件：记下状态，finish() 时据此产出 errorMessage（避免静默「无响应」）
            event.type == "response.failed" || event.type == "response.incomplete" -> {
                runCatching {
                    val resp = gson.fromJson(event.data, ResponsesResponse::class.java)
                    finalStatus = resp.status ?: event.type.removePrefix("response.")
                    failureRawData = event.data
                }
                null
            }
            else -> null // created / in_progress / web_search_call.* 等忽略
        }
    }

    private fun parseDelta(data: String): String? =
        runCatching { gson.fromJson(data, ResponsesTextDelta::class.java).delta }
            .getOrNull()?.takeIf { it.isNotEmpty() }

    /** 流结束：合成完整响应。 */
    fun finish(): ResponsesResponse {
        // 工具调用并入 output（避免被 completed 的 output 覆盖；去重 by item id）
        val mergedOutput = finalOutput.toMutableList()
        if (functionCalls.isNotEmpty()) {
            val existingKeys = finalOutput.mapNotNull { it.getSafeString("id") }.toSet()
            functionCalls.forEach { (id, item) ->
                if (id !in existingKeys) {
                    mergedOutput += item.also { it.addProperty("id", id) }
                }
            }
        }
        // 正常：response.completed 已携带完整 output，直接用
        if (mergedOutput.isNotEmpty()) {
            return ResponsesResponse(status = finalStatus ?: "completed", output = mergedOutput)
        }
        // 兜底：response.completed 没带 output（异常），用累积文本构造一个 message
        return if (textBuilder.isNotEmpty()) {
            ResponsesResponse(
                status = finalStatus ?: "completed",
                output = listOf(buildMessageOutput(textBuilder.toString())),
            )
        } else {
            ResponsesResponse(status = finalStatus ?: "completed", output = emptyList())
        }
    }

    /** 流是否以失败/被截断结束（response.failed / response.incomplete）；非空字符串为可读错误描述。 */
    fun failureMessage(): String? = when {
        failureRawData != null ->
            "DeepSeek Responses 返回失败（${finalStatus ?: "failed"}）：${failureRawData!!.take(200)}"
        else -> null
    }
}

/** 兜底：把纯文本包成 message output item（`{type:"message",content:[{type:"output_text",text:...}]}`）。 */
private fun buildMessageOutput(text: String): JsonObject =
    com.google.gson.JsonParser.parseString(
        """{"type":"message","content":[{"type":"output_text","text":${Gson().toJson(text)}}]}"""
    ).asJsonObject
