package com.stock.dividend.data.repository

import android.graphics.Bitmap
import com.stock.dividend.data.remote.LlmApi
import com.stock.dividend.data.remote.dto.LlmChatRequest
import com.stock.dividend.data.remote.dto.LlmContentPart
import com.stock.dividend.data.remote.dto.LlmImageUrl
import com.stock.dividend.data.remote.dto.LlmMessage
import com.stock.dividend.data.scan.ParsedHoldingRow
import com.stock.dividend.data.scan.bitmapToJpegDataUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

/** 视觉解析结果四态。 */
sealed interface VisionImportResult {
    data class Holdings(val rows: List<ParsedHoldingRow>) : VisionImportResult
    data class Transactions(val rows: List<ParsedTransactionRow>) : VisionImportResult
    data object NotConfigured : VisionImportResult
    data class Error(val message: String) : VisionImportResult
}

/** 一次可重试的失败（内部信号）。 */
private class RetryableFailure(val reason: String)

/**
 * 视觉导入编排：图片 → GLM-4.6V（OpenAI 兼容多模态）→ 结构化行。
 * 照 [ScreenshotStrategyRepository] 的编排模式，但 user 消息是「文本 + image_url」content parts。
 *
 * **自动重试**：对可重试故障（网络错误 / 429 / 5xx / 模型返回格式异常）自动重试
 * [MAX_RETRIES] 次（指数退避 1s/2s/4s/8s/8s），进度经 [onRetry] 回调供 UI 展示
 * 「正在重试 n/5」；401/403 等 key 问题不重试、直接报错。
 */
@Singleton
class VisionImportRepository @Inject constructor(
    private val llmApi: LlmApi,
    private val llmConfigRepository: LlmConfigRepository,
) {

    suspend fun parse(
        bitmap: Bitmap,
        mode: VisionParseMode,
        onRetry: (attempt: Int, maxRetries: Int, reason: String) -> Unit = { _, _, _ -> },
    ): VisionImportResult {
        val config = llmConfigRepository.visionSnapshot()
        if (!config.isComplete) return VisionImportResult.NotConfigured

        val dataUrl = withContext(Dispatchers.IO) { bitmapToJpegDataUrl(bitmap) }
        val url = config.baseUrl.trimEnd('/') + "/chat/completions"
        val request = LlmChatRequest(
            model = config.model,
            messages = listOf(
                LlmMessage("system", VisionImportPromptBuilder.system(mode)),
                LlmMessage(
                    "user",
                    listOf(
                        LlmContentPart(type = "text", text = VisionImportPromptBuilder.USER_TEXT),
                        LlmContentPart(type = "image_url", imageUrl = LlmImageUrl(dataUrl)),
                    )
                )
            ),
            temperature = 0.1, // 数据录入任务，压低随机性
            responseFormat = null, // 视觉模型对 response_format 支持不稳，靠 prompt 约束 JSON
            maxTokens = 4096,
        )

        var retry = 0
        while (true) {
            val failure: RetryableFailure = try {
                val content = llmApi.chatCompletions(url, "Bearer ${config.apiKey}", request).content
                    ?: return VisionImportResult.Error("视觉模型返回为空，请重试")
                when (val parsed = VisionImportParser.parse(content)) {
                    is VisionImportParseResult.Holdings -> return VisionImportResult.Holdings(parsed.rows)
                    is VisionImportParseResult.Transactions -> return VisionImportResult.Transactions(parsed.rows)
                    VisionImportParseResult.Empty -> return VisionImportResult.Error(
                        if (mode == VisionParseMode.HOLDINGS) "未在截图中识别到持仓行，请确认截图来自持仓页"
                        else "未在截图中识别到成交记录，请确认截图来自历史成交页"
                    )
                    VisionImportParseResult.Invalid -> RetryableFailure("模型返回格式异常")
                }
            } catch (e: HttpException) {
                if (isRetryableHttp(e.code())) RetryableFailure(mapHttpError(e.code()))
                else return VisionImportResult.Error(mapHttpError(e.code()))
            } catch (_: Exception) {
                RetryableFailure("网络错误")
            }
            if (retry >= MAX_RETRIES) {
                return VisionImportResult.Error("${failure.reason}，已自动重试 $MAX_RETRIES 次仍失败")
            }
            retry++
            onRetry(retry, MAX_RETRIES, failure.reason)
            delay(backoffMs(retry))
        }
    }

    private fun isRetryableHttp(code: Int): Boolean = code == 429 || code in 500..599

    private fun mapHttpError(code: Int): String = when (code) {
        401, 403 -> "API key 无效，请在设置中检查视觉模型配置"
        429 -> "请求过频"
        in 500..599 -> "视觉模型服务暂不可用"
        else -> "识别失败（HTTP $code），请重试"
    }

    /** 指数退避：1s/2s/4s/8s，封顶 8s。 */
    private fun backoffMs(retry: Int): Long =
        min(1000L shl (retry - 1), 8000L)

    private companion object {
        /** 自动重试次数（不含首次尝试，即最多共请求 6 次）。 */
        const val MAX_RETRIES = 5
    }
}
