package com.stock.dividend.data.repository

import com.google.gson.Gson
import com.stock.dividend.data.local.dao.LlmAnalysisCacheDao
import com.stock.dividend.data.local.entity.LlmAnalysisCacheEntity
import javax.inject.Inject
import javax.inject.Singleton

/** 组合级缓存命中结果。 */
data class PortfolioCacheEntry(val analysis: LlmAnalysis, val createdAt: Long)

/** 个股级缓存命中结果。 */
data class StockCacheEntry(val analysis: StockLlmAnalysis, val createdAt: Long)

/**
 * LLM 解读结果缓存读写（Room + Gson）。只负责序列化与存取，
 * 新鲜判定（24h TTL）与回退策略由 [LlmAnalysisRepository] 统一处理。
 * 缓存写入失败静默跳过（红线 #2）；反序列化失败视为未命中。
 */
@Singleton
class LlmAnalysisCacheStore @Inject constructor(
    private val llmAnalysisCacheDao: LlmAnalysisCacheDao,
) {
    private val gson = Gson()

    suspend fun getPortfolio(cacheKey: String): PortfolioCacheEntry? {
        val entity = runCatching { llmAnalysisCacheDao.get(cacheKey, SCOPE_PORTFOLIO) }.getOrNull() ?: return null
        val analysis = runCatching { gson.fromJson(entity.payload, LlmAnalysis::class.java) }.getOrNull() ?: return null
        return PortfolioCacheEntry(analysis, entity.createdAt)
    }

    suspend fun getStock(cacheKey: String): StockCacheEntry? {
        val entity = runCatching { llmAnalysisCacheDao.get(cacheKey, SCOPE_STOCK) }.getOrNull() ?: return null
        val analysis = runCatching { gson.fromJson(entity.payload, StockLlmAnalysis::class.java) }.getOrNull() ?: return null
        return StockCacheEntry(analysis, entity.createdAt)
    }

    suspend fun putPortfolio(cacheKey: String, analysis: LlmAnalysis, createdAt: Long) {
        put(cacheKey, SCOPE_PORTFOLIO, gson.toJson(analysis), createdAt)
    }

    suspend fun putStock(cacheKey: String, analysis: StockLlmAnalysis, createdAt: Long) {
        put(cacheKey, SCOPE_STOCK, gson.toJson(analysis), createdAt)
    }

    private suspend fun put(cacheKey: String, scope: String, payload: String, createdAt: Long) {
        runCatching {
            llmAnalysisCacheDao.upsert(LlmAnalysisCacheEntity(cacheKey, scope, payload, createdAt))
        }
    }

    companion object {
        const val SCOPE_PORTFOLIO = "PORTFOLIO"
        const val SCOPE_STOCK = "STOCK"
    }
}
