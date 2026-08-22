package com.stock.dividend.data.repository

import com.google.gson.Gson
import com.stock.dividend.data.local.dao.FundamentalsCacheDao
import com.stock.dividend.data.local.entity.FundamentalsCacheEntity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 单股基本面缓存编排：新鲜（≤7 天）直接返回；过期/缺失走 [StockRepository.fetchFundamentals]
 * 并写缓存；网络失败回退旧缓存、无缓存则 null。全程吞异常（红线 #2）。
 *
 * 刷新时按报告期 [mergeByReportDate] 合并——历史期次不可变，远端窗口没返回的旧期次从缓存续接，不随刷新丢失。
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
            // 历史期次不可变：远端窗口没返回的旧期次从缓存续接，不随刷新丢失；
            // 同期远端字段为 null（资产负债表子接口失败降级空表）时回退缓存已有值，防字段级回退
            val cachedPeriods = cached?.let { parse(it.payload)?.periods }.orEmpty()
            val merged = Fundamentals(
                mergeByReportDate(cachedPeriods, remote.periods, { it.reportDate }) { r, c ->
                    r.copy(
                        roe = r.roe ?: c.roe,
                        debtToAssetRatio = r.debtToAssetRatio ?: c.debtToAssetRatio,
                        revenueYoy = r.revenueYoy ?: c.revenueYoy,
                        netProfitYoy = r.netProfitYoy ?: c.netProfitYoy,
                        basicEps = r.basicEps ?: c.basicEps,
                        announceYield = r.announceYield ?: c.announceYield,
                        dividendPlan = r.dividendPlan ?: c.dividendPlan
                    )
                }
            )
            runCatching {
                fundamentalsCacheDao.upsert(
                    FundamentalsCacheEntity(
                        stockCode = stockCode,
                        payload = gson.toJson(merged),
                        fetchedAt = System.currentTimeMillis()
                    )
                )
            }
            return merged
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
