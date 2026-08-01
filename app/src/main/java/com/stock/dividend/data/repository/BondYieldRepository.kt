package com.stock.dividend.data.repository

import android.content.Context
import com.stock.dividend.data.remote.BondYieldApi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 10 年期国债到期收益率获取（单位：%）。
 *
 * - 远程：东方财富 `100.GB10Y` 曲线；
 * - 本地缓存：[PREFS_NAME]，缓存有效期 [CACHE_TTL_MS]（24h）；
 * - 失败降级：返回上次缓存或 [DEFAULT_YIELD]。
 */
@Singleton
class BondYieldRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: BondYieldApi
) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val mutex = Mutex()

    @Volatile
    private var memoryCache: Double? = null

    /**
     * 取当前 10Y 国债收益率（%）。优先内存 → 过期内缓存 → 远程 → 旧缓存 → 默认值。
     */
    suspend fun fetch10YBondYield(forceRefresh: Boolean = false): Double = withContext(Dispatchers.IO) {
        memoryCache?.let { if (!forceRefresh) return@withContext it }

        val now = System.currentTimeMillis()
        val cachedAt = prefs.getLong(KEY_UPDATED_AT, 0L)
        val cachedValue = prefs.getString(KEY_YIELD, null)?.toDoubleOrNull()
        if (!forceRefresh && memoryCache == null && cachedValue != null && cachedAt > 0 && now - cachedAt < CACHE_TTL_MS) {
            memoryCache = cachedValue
            return@withContext cachedValue
        }

        val remote = mutex.withLock {
            runCatching {
                // result.data 按日期倒序，首条即最新；10Y 字段值已是「%」单位
                api.getTreasuryYield().result?.data?.firstOrNull()?.yield10Y
            }.getOrNull()
        }
        val value = when {
            remote != null && remote.isFinite() && remote > 0.0 -> remote
            else -> cachedValue?.takeIf { it > 0.0 } ?: DEFAULT_YIELD
        }

        prefs.edit()
            .putString(KEY_YIELD, value.toString())
            .putLong(KEY_UPDATED_AT, now)
            .apply()
        memoryCache = value
        value
    }

    companion object {
        private const val PREFS_NAME = "app_prefs"
        private const val KEY_YIELD = "bond_yield_10y"
        private const val KEY_UPDATED_AT = "bond_yield_10y_updated_at"
        private const val CACHE_TTL_MS = 24L * 60 * 60 * 1000

        /** 远程失败且无缓存时的兜底值（%）。 */
        const val DEFAULT_YIELD = 2.5
    }
}
