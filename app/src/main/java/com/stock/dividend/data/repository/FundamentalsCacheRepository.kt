package com.stock.dividend.data.repository

import com.google.gson.Gson
import com.stock.dividend.data.local.dao.FundamentalsCacheDao
import com.stock.dividend.data.local.entity.FundamentalsCacheEntity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 单股基本面缓存编排：新鲜（≤7 天）直接返回；过期/缺失走 [StockRepository.fetchFundamentals]
 * 并写缓存；网络失败回退旧缓存、无缓存则 null。全程吞异常（红线 #2）。
 */
@Singleton
class FundamentalsCacheRepository @Inject constructor(
    private val fundamentalsCacheDao: FundamentalsCacheDao,
    private val stockRepository: StockRepository,
) {
    private val gson = Gson()

    suspend fun getFundamentals(stockCode: String, forceRefresh: Boolean = false): Fundamentals? {
        val cached = runCatching { fundamentalsCacheDao.get(stockCode) }.getOrNull()
        if (!forceRefresh && cached != null && isFresh(cached.fetchedAt)) {
            return parse(cached.payload)
        }

        val remote = runCatching { stockRepository.fetchFundamentals(stockCode) }.getOrNull()
        if (remote != null) {
            runCatching {
                fundamentalsCacheDao.upsert(
                    FundamentalsCacheEntity(
                        stockCode = stockCode,
                        payload = gson.toJson(remote),
                        fetchedAt = System.currentTimeMillis()
                    )
                )
            }
            return remote
        }
        return cached?.let { parse(it.payload) }
    }

    private fun parse(payload: String): Fundamentals? =
        runCatching { gson.fromJson(payload, Fundamentals::class.java) }.getOrNull()

    private fun isFresh(fetchedAt: Long): Boolean =
        System.currentTimeMillis() - fetchedAt < CACHE_TTL_MS

    companion object {
        const val CACHE_TTL_MS = 7L * 24 * 60 * 60 * 1000
    }
}
