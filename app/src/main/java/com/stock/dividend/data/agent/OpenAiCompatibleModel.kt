package com.stock.dividend.data.agent

import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.Model
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.gson.Gson
import com.stock.dividend.data.repository.LlmConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * ADK Model 适配器：把 ADK LlmRequest 翻译为 OpenAI 兼容请求，支持非流式与 SSE 流式。
 *
 * 两条互斥的 API 路径，由 [useResponsesApi] 选择：
 * - **Chat Completions**（默认，`/chat/completions`）：messages + tool_calls，web_search 不可用；
 * - **DeepSeek Responses API**（`/responses`）：input 数组 + typed-event SSE，可注入服务端
 *   `web_search` 工具（[includeWebSearch]）。DeepSeek 域名下需用 deepseek-v4-flash 模型。
 *
 * 两条路径的 HTTP/异常/取消逻辑共享；请求构建、响应与 SSE 解析各自独立（单位/格式差异大，
 * 切勿复用，详见 [DeepSeekResponsesProtocol] / [parseSseDataLine]）。
 *
 * @param effectiveModel 实际请求的模型名（默认用 [config.model]；DeepSeek+联网时由工厂
 *   覆盖为 deepseek-v4-flash）。
 */
class OpenAiCompatibleModel(
    private val config: LlmConfig,
    private val client: OkHttpClient,
    private val gson: Gson = Gson(),
    private val useResponsesApi: Boolean = false,
    private val includeWebSearch: Boolean = false,
    private val effectiveModel: String = config.model,
) : Model {

    override val name: String get() = effectiveModel

    override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> = channelFlow {
        val (url, body) = if (useResponsesApi) {
            // DeepSeek Responses API 端点是 https://api.deepseek.com/responses（不带 /v1），
            // 而用户预设的 baseUrl 多为 https://api.deepseek.com/v1/（Chat Completions 兼容路径）。
            // 故 Responses 路径须先去掉末尾 /v1 或 /v1/，再拼 /responses，否则会 404。
            val path = config.baseUrl.trimEnd('/').removeSuffix("/v1") + "/responses"
            val req = buildResponsesRequest(request, effectiveModel, includeWebSearch)
            path to gson.toJson(req.copy(stream = stream))
        } else {
            val path = config.baseUrl.trimEnd('/') + "/chat/completions"
            val req = buildOpenAiRequest(request, effectiveModel, gson)
            path to gson.toJson(req.copy(stream = stream))
        }
        val httpRequest = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        val call = client.newCall(httpRequest)
        val job = currentCoroutineContext().job
        val cancelHandle = job.invokeOnCompletion { call.cancel() }
        try {
            withContext(Dispatchers.IO) {
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        val detail = response.body?.string().orEmpty().take(200)
                        throw IOException("请求失败 HTTP ${response.code}：$detail")
                    }
                    if (stream) {
                        if (useResponsesApi) streamResponses(response) else streamChatCompletions(response)
                    } else {
                        val json = response.body?.string()
                            ?: throw IOException("请求失败：空响应体")
                        val llmResponse = if (useResponsesApi) {
                            toLlmResponse(gson.fromJson(json, ResponsesResponse::class.java), gson)
                        } else {
                            toLlmResponse(gson.fromJson(json, OpenAiChatResponse::class.java), gson)
                        }
                        send(llmResponse)
                    }
                }
            }
        } finally {
            cancelHandle.dispose()
        }
    }

    /** Chat Completions 流式：data-only SSE，[DONE] 结束。作为 ProducerScope 扩展以访问 send。 */
    private suspend fun ProducerScope<LlmResponse>.streamChatCompletions(response: okhttp3.Response) {
        val accumulator = SseAccumulator()
        val source = response.body?.source()
            ?: throw IOException("请求失败：空响应体")
        while (!source.exhausted()) {
            val line = source.readUtf8Line() ?: break
            val chunk = parseSseDataLine(line, gson) ?: continue
            val delta = accumulator.onChunk(chunk)
            if (delta.isNotEmpty()) {
                send(
                    LlmResponse(
                        partial = true,
                        content = Content(role = Role.MODEL, parts = listOf(Part(text = delta)))
                    )
                )
            }
        }
        send(toLlmResponse(accumulator.finish(), gson))
    }

    /** Responses API 流式：typed-event SSE（event: + data: 跨行），response.completed 结束。 */
    private suspend fun ProducerScope<LlmResponse>.streamResponses(response: okhttp3.Response) {
        val lineParser = ResponsesSseLineParser()
        val accumulator = ResponsesSseAccumulator(gson)
        val source = response.body?.source()
            ?: throw IOException("请求失败：空响应体")
        while (!source.exhausted()) {
            val line = source.readUtf8Line() ?: break
            val event = lineParser.feed(line) ?: continue
            val delta = accumulator.onEvent(event) ?: continue
            // reasoning（思考过程）→ Part(thought=true)，output（最终答案）→ 普通 Part。
            // thought 标记让下游把思考过程与最终回复分开渲染（ADK Part 原生字段，标准用法）。
            // ReasoningDone（思考段结束）：推空 thought Part，emitEvent 据此发 ThinkingDone 停转圈。
            // Reasoning（思考增量）→ Part(thought=true)；Output（最终答案）→ 普通 Part。
            val part = when (delta) {
                is SseTextDelta.Reasoning -> Part(text = delta.text, thought = true)
                is SseTextDelta.Output -> Part(text = delta.text)
                SseTextDelta.ReasoningDone -> Part(text = "", thought = true)
            }
            send(
                LlmResponse(
                    partial = true,
                    content = Content(role = Role.MODEL, parts = listOf(part))
                )
            )
        }
        // 流以 response.failed / response.incomplete 结束时，抛出明确错误（避免静默「无响应」）
        accumulator.failureMessage()?.let { throw IOException(it) }
        send(toLlmResponse(accumulator.finish(), gson))
    }
}
