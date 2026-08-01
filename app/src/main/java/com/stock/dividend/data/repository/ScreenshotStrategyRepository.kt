package com.stock.dividend.data.repository

import com.stock.dividend.data.remote.LlmApi
import com.stock.dividend.data.remote.dto.LlmChatRequest
import com.stock.dividend.data.remote.dto.LlmMessage
import kotlinx.coroutines.flow.first
import retrofit2.HttpException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 编排截图策略提取：读配置 → 构造 prompt → 调用 → 解析 → 映射五态（+ NoStrategy）。
 * 照抄 [LlmAnalysisRepository] 的编排模式，但输入是单一 OCR 字符串、独立 schema。
 */
@Singleton
class ScreenshotStrategyRepository @Inject constructor(
    private val llmApi: LlmApi,
    private val configSource: LlmConfigSource,
) {
    suspend fun analyze(ocrText: String): ScreenshotStrategyState {
        val config = configSource.observeConfig().first()
        if (!config.isComplete) return ScreenshotStrategyState.NotConfigured

        val prompt = ScreenshotStrategyPromptBuilder.build(ocrText)
        val url = config.baseUrl.trimEnd('/') + "/chat/completions"
        val request = LlmChatRequest(
            model = config.model,
            messages = listOf(
                LlmMessage("system", prompt.system),
                LlmMessage("user", prompt.user)
            )
        )
        return try {
            val content = llmApi.chatCompletions(url, "Bearer ${config.apiKey}", request).content
                ?: return ScreenshotStrategyState.Error("LLM 返回为空")
            when (val parsed = ScreenshotStrategyParser.parse(content)) {
                is ScreenshotStrategyParseResult.Actionable -> ScreenshotStrategyState.Success(parsed.strategy)
                ScreenshotStrategyParseResult.NotActionable -> ScreenshotStrategyState.NoStrategy("未识别到可执行的买卖策略")
                is ScreenshotStrategyParseResult.Failed -> ScreenshotStrategyState.Error("LLM 响应解析失败，请重试")
            }
        } catch (e: HttpException) {
            ScreenshotStrategyState.Error(mapHttpError(e.code()))
        } catch (_: Exception) {
            ScreenshotStrategyState.Error("网络错误，请重试")
        }
    }

    private fun mapHttpError(code: Int): String = when (code) {
        401, 403 -> "API key 无效"
        429 -> "请求过频，稍后重试"
        else -> "分析失败，请重试"
    }
}
