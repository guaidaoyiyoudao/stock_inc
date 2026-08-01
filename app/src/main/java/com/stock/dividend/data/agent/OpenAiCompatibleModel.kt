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
 * ADK Model 适配器：把 ADK LlmRequest 翻译为 OpenAI Chat Completions 请求，
 * 支持非流式与 SSE 流式（含流式 tool_calls 累积），复用用户配置的 OpenAI 兼容端点。
 */
class OpenAiCompatibleModel(
    private val config: LlmConfig,
    private val client: OkHttpClient,
    private val gson: Gson = Gson(),
) : Model {

    override val name: String get() = config.model

    override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> = channelFlow {
        val url = config.baseUrl.trimEnd('/') + "/chat/completions"
        val body = gson.toJson(
            buildOpenAiRequest(request, config.model, gson).copy(stream = stream)
        )
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
                    } else {
                        val json = response.body?.string()
                            ?: throw IOException("请求失败：空响应体")
                        send(toLlmResponse(gson.fromJson(json, OpenAiChatResponse::class.java), gson))
                    }
                }
            }
        } finally {
            cancelHandle.dispose()
        }
    }
}
