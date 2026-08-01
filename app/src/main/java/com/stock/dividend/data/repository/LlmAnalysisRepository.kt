package com.stock.dividend.data.repository

import com.stock.dividend.data.remote.LlmApi
import com.stock.dividend.data.remote.dto.LlmChatRequest
import com.stock.dividend.data.remote.dto.LlmMessage
import kotlinx.coroutines.flow.first
import retrofit2.HttpException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 编排 LLM 解读：读配置 → 构造 prompt → 查缓存（prompt 哈希 key，24h TTL）→ 调用 → 解析 → 写缓存。
 * 组合级 [analyze] 与个股级 [analyzeStock] 共享同一缓存流程。
 */
@Singleton
class LlmAnalysisRepository @Inject constructor(
    private val llmApi: LlmApi,
    private val configSource: LlmConfigSource,
    private val cacheStore: LlmAnalysisCacheStore,
) {
    suspend fun analyze(
        input: PortfolioLlmInput,
        forceRefresh: Boolean = false,
    ): LlmAnalysisResult {
        if (input.evaluation.isEmpty()) return LlmAnalysisResult.NotConfigured
        val prompt = LlmPromptBuilder.build(input)
        val key = LlmCacheKey.of(prompt.system, prompt.user)

        // 始终读缓存：非 forceRefresh 时用于新鲜命中，forceRefresh 失败时用于回退旧值
        val cached = cacheStore.getPortfolio(key)
        if (!forceRefresh && cached != null && isFresh(cached.createdAt)) {
            return LlmAnalysisResult.Success(cached.analysis, cached.createdAt, fromCache = true)
        }

        val config = configSource.observeConfig().first()
        if (!config.isComplete) return LlmAnalysisResult.NotConfigured

        return try {
            val content = call(config, prompt)
            val analysis = LlmAnalysisParser.parse(content)
            cacheStore.putPortfolio(key, analysis, System.currentTimeMillis())
            LlmAnalysisResult.Success(analysis)
        } catch (e: HttpException) {
            fallbackOrError(cached, forceRefresh, mapHttpError(e.code()))
        } catch (_: Exception) {
            fallbackOrError(cached, forceRefresh, "网络错误，请重试")
        }
    }

    suspend fun analyzeStock(
        input: StockLlmInput,
        userStrategies: List<UserStrategyRef> = emptyList(),
        forceRefresh: Boolean = false,
    ): StockLlmAnalysisResult {
        val prompt = StockLlmPromptBuilder.build(input, userStrategies)
        val key = LlmCacheKey.of(prompt.system, prompt.user)

        // 始终读缓存：非 forceRefresh 时用于新鲜命中，forceRefresh 失败时用于回退旧值
        val cached = cacheStore.getStock(key)
        if (!forceRefresh && cached != null && isFresh(cached.createdAt)) {
            return StockLlmAnalysisResult.Success(cached.analysis, cached.createdAt, fromCache = true)
        }

        val config = configSource.observeConfig().first()
        if (!config.isComplete) return StockLlmAnalysisResult.NotConfigured

        return try {
            val content = callStock(config, prompt)
            val analysis = StockLlmAnalysisParser.parse(content)
            cacheStore.putStock(key, analysis, System.currentTimeMillis())
            StockLlmAnalysisResult.Success(analysis)
        } catch (e: HttpException) {
            if (forceRefresh && cached != null) {
                StockLlmAnalysisResult.Success(cached.analysis, cached.createdAt, fromCache = true, notice = REFRESH_FALLBACK_NOTICE)
            } else {
                StockLlmAnalysisResult.Error(mapHttpError(e.code()))
            }
        } catch (_: Exception) {
            if (forceRefresh && cached != null) {
                StockLlmAnalysisResult.Success(cached.analysis, cached.createdAt, fromCache = true, notice = REFRESH_FALLBACK_NOTICE)
            } else {
                StockLlmAnalysisResult.Error("网络错误，请重试")
            }
        }
    }

    private suspend fun call(config: LlmConfig, prompt: LlmPromptBuilder.LlmPrompt): String {
        val url = config.baseUrl.trimEnd('/') + "/chat/completions"
        val request = LlmChatRequest(
            model = config.model,
            messages = listOf(
                LlmMessage("system", prompt.system),
                LlmMessage("user", prompt.user),
            ),
        )
        return llmApi.chatCompletions(url, "Bearer ${config.apiKey}", request).content
            ?: throw IllegalStateException("LLM 返回为空")
    }

    private suspend fun callStock(config: LlmConfig, prompt: StockLlmPromptBuilder.LlmPrompt): String {
        val url = config.baseUrl.trimEnd('/') + "/chat/completions"
        val request = LlmChatRequest(
            model = config.model,
            messages = listOf(
                LlmMessage("system", prompt.system),
                LlmMessage("user", prompt.user),
            ),
        )
        return llmApi.chatCompletions(url, "Bearer ${config.apiKey}", request).content
            ?: throw IllegalStateException("LLM 返回为空")
    }

    /** 组合级失败回退：仅 forceRefresh 时回退旧缓存（带提示），否则原样报错。 */
    private fun fallbackOrError(
        cached: PortfolioCacheEntry?,
        forceRefresh: Boolean,
        errorMessage: String,
    ): LlmAnalysisResult = if (forceRefresh && cached != null) {
        LlmAnalysisResult.Success(cached.analysis, cached.createdAt, fromCache = true, notice = REFRESH_FALLBACK_NOTICE)
    } else {
        LlmAnalysisResult.Error(errorMessage)
    }

    private fun isFresh(createdAt: Long): Boolean =
        System.currentTimeMillis() - createdAt < CACHE_TTL_MS

    private fun mapHttpError(code: Int): String = when (code) {
        401, 403 -> "API key 无效"
        429 -> "请求过频，稍后重试"
        else -> "分析失败，请重试"
    }

    companion object {
        const val CACHE_TTL_MS = 24L * 60 * 60 * 1000
        private const val REFRESH_FALLBACK_NOTICE = "刷新失败，显示上次分析结果"
    }
}
