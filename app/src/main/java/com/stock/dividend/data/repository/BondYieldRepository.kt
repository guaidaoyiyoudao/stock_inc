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
    private val api: BondYieldApi,
    private val errorLogRepository: ErrorLogRepository,
) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val mutex = Mutex()

    @Volatile
    private var memoryCache: Double? = null

    /**
     * 取当前 10Y 国债收益率（%）。优先内存 → 过期内缓存 → 远程 → 旧缓存 → 默认值。
     *
     * ⚠️ **仅成功值写缓存**（2026-08-20 审计修复）：此前失败时把回退值（默认 2.5 / 旧缓存）
     * 也写入 memoryCache 与 prefs——断网冷启动后整个进程存活期都返回假基准（当前 10Y 真实值
     * 约 1.8~2.0，2.5 会系统性抬高买入线），且旧缓存被 updated_at 续期成「新鲜」。
     * 现失败路径不落任何缓存，下次调用自动重试远端。
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
                // result.data 按日期倒序；首行 10Y 为 null（当日尚未更新）时向后扫备选行（pageSize=5）
                api.getTreasuryYield().result?.data
                    ?.firstOrNull { it.yield10Y != null && it.yield10Y > 0.0 }
                    ?.yield10Y
            }.onFailure {
                // 静默失败落日志（设置 → 数据 → 失败日志）：回退旧缓存/默认值属于不可见的精度损失
                errorLogRepository.record(
                    source = "国债收益率",
                    message = "国债收益率获取失败，已回退缓存或默认值",
                    throwable = it,
                )
            }.getOrNull()
        }

        if (remote != null && remote.isFinite() && remote > 0.0) {
            prefs.edit()
                .putString(KEY_YIELD, remote.toString())
                .putLong(KEY_UPDATED_AT, now)
                .apply()
            memoryCache = remote
            return@withContext remote
        }
        // 失败：过期旧缓存回退（不刷 updated_at，保持真实新鲜度）；无缓存用默认值——都不落缓存，下次调用重试远端
        cachedValue?.takeIf { it > 0.0 } ?: DEFAULT_YIELD
    }

    /**
     * 取多期限国债收益率、中美利差与 LPR。返回最新一日的全集（%）。
     * 任一字段网络失败/缺失为 null。仅 10Y 有兜底默认值（[DEFAULT_YIELD]），其余无兜底。
     */
    suspend fun fetchAllYields(): TreasuryYields = withContext(Dispatchers.IO) {
        val item = runCatching {
            api.getTreasuryYield().result?.data?.firstOrNull()
        }.getOrNull()
        val fallback10Y = fetch10YBondYield()
        TreasuryYields(
            date = item?.solarDate?.substringBefore(" "),
            yield2Y = item?.yield2Y?.takeIfFinite(),
            yield5Y = item?.yield5Y?.takeIfFinite(),
            yield10Y = item?.yield10Y?.takeIfFinite() ?: fallback10Y,
            yield30Y = item?.yield30Y?.takeIfFinite(),
            cnUsSpread10Y = item?.cnUsSpread10Y?.takeIfFinite(),
            lpr1Y = item?.lpr1Y?.takeIfFinite(),
            lpr5Y = item?.lpr5Y?.takeIfFinite()
        )
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

/**
 * 多期限国债收益率、中美利差与 LPR 全集（单位：%）。各字段可空，缺失即 null。
 * 仅 [yield10Y] 有兜底默认值（[BondYieldRepository.DEFAULT_YIELD]），其余无兜底。
 */
data class TreasuryYields(
    val date: String?,
    val yield2Y: Double?,
    val yield5Y: Double?,
    val yield10Y: Double?,
    val yield30Y: Double?,
    val cnUsSpread10Y: Double?,
    val lpr1Y: Double?,
    val lpr5Y: Double?
)

// takeIfFinite 统一用 MarketDataRepository.kt 的 internal 扩展（§4.9.5-2 单点封装）
