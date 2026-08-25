package com.stock.dividend.data.plane

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 分红数据新鲜度记账（数据平面内部使用）：
 * 记录每只股票最后一次「成功拉取」与「尝试拉取」的时间戳，驱动 [MarketDataPlane.ensureDividendsFresh]
 * 的 7 天过期 + 失败 5 分钟退避策略（见 [PlanePolicy]）。
 *
 * 用 SharedPreferences 而非 dividends 表加列——避免 DB 迁移，且该信息本就是可再生的运行时元数据
 * （参照 [com.stock.dividend.data.repository.BondYieldRepository] 的 prefs 缓存先例）。
 */
interface DividendFreshnessStore {
    /**
     * 该股最后一次成功拉取分红的时间戳（epoch ms）；0 = 从未成功。
     * 读取落在 [Dispatchers.IO]（SharedPreferences 首次加载是磁盘 IO，避免阻塞调用方线程）。
     */
    suspend fun lastSuccessAt(stockCode: String): Long

    /** 该股最后一次尝试拉取分红的时间戳（含失败）；0 = 从未尝试。同样走 IO。 */
    suspend fun lastAttemptAt(stockCode: String): Long

    fun markSuccess(stockCode: String, at: Long)

    fun markAttempt(stockCode: String, at: Long)

    /** 清空全部记账（缓存管理清理 dividends 表时联动调用：让下次访问立即重新拉网，不吃退避闭门羹）。 */
    fun clear()
}

/** SharedPreferences 实现。单文件、每股票 2 个 long 键，量级（自选股数百只）下无性能问题。写入用 apply() 异步落盘。 */
@Singleton
class PrefsDividendFreshnessStore @Inject constructor(
    @ApplicationContext private val context: Context
) : DividendFreshnessStore {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override suspend fun lastSuccessAt(stockCode: String): Long = withContext(Dispatchers.IO) {
        prefs.getLong(KEY_SUCCESS + stockCode, 0L)
    }

    override suspend fun lastAttemptAt(stockCode: String): Long = withContext(Dispatchers.IO) {
        prefs.getLong(KEY_ATTEMPT + stockCode, 0L)
    }

    override fun markSuccess(stockCode: String, at: Long) {
        prefs.edit().putLong(KEY_SUCCESS + stockCode, at).apply()
    }

    override fun markAttempt(stockCode: String, at: Long) {
        prefs.edit().putLong(KEY_ATTEMPT + stockCode, at).apply()
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val PREFS_NAME = "market_data_plane"
        const val KEY_SUCCESS = "dividend_success_at_"
        const val KEY_ATTEMPT = "dividend_attempt_at_"
    }
}
