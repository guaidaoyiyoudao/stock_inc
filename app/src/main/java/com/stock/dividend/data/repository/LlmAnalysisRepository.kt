package com.stock.dividend.data.repository

import com.stock.dividend.data.remote.LlmApi
import com.stock.dividend.data.remote.dto.LlmChatRequest
import com.stock.dividend.data.remote.dto.LlmMessage
import kotlinx.coroutines.flow.first
import retrofit2.HttpException
import javax.inject.Inject
import javax.inject.Singleton

/** 编排 LLM 解读：读配置 → 构造 prompt → 调用 → 解析。 */
@Singleton
class LlmAnalysisRepository @Inject constructor(
    private val llmApi: LlmApi,
    private val configSource: LlmConfigSource,
) {
    suspend fun analyze(
        evaluatedStocks: List<EvaluatedStock>,
        dailyBands: Map<String, BollBand?>,
        monthlyBands: Map<String, BollBand?>,
        signals: PortfolioSignals,
        thresholds: DividendThresholds,
    ): LlmAnalysisResult {
        if (evaluatedStocks.isEmpty()) return LlmAnalysisResult.NotConfigured
        val config = configSource.observeConfig().first()
        if (!config.isComplete) return LlmAnalysisResult.NotConfigured

        val prompt = LlmPromptBuilder.build(evaluatedStocks, dailyBands, monthlyBands, signals, thresholds)
        val url = config.baseUrl.trimEnd('/') + "/chat/completions"
        val request = LlmChatRequest(
            model = config.model,
            messages = listOf(
                LlmMessage("system", prompt.system),
                LlmMessage("user", prompt.user),
            ),
        )
        return try {
            val content = llmApi.chatCompletions(url, "Bearer ${config.apiKey}", request).content
                ?: return LlmAnalysisResult.Error("LLM 返回为空")
            LlmAnalysisResult.Success(LlmAnalysisParser.parse(content))
        } catch (e: HttpException) {
            LlmAnalysisResult.Error(mapHttpError(e.code()))
        } catch (_: Exception) {
            LlmAnalysisResult.Error("网络错误，请重试")
        }
    }

    private fun mapHttpError(code: Int): String = when (code) {
        401, 403 -> "API key 无效"
        429 -> "请求过频，稍后重试"
        else -> "分析失败，请重试"
    }
}
