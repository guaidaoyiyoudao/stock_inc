package com.stock.dividend.data.repository

import androidx.room.withTransaction
import com.stock.dividend.data.local.AppDatabase
import com.stock.dividend.data.local.dao.DividendDao
import com.stock.dividend.data.local.dao.FinancialStatementsCacheDao
import com.stock.dividend.data.local.dao.FundamentalsCacheDao
import com.stock.dividend.data.local.dao.KlineCacheDao
import com.stock.dividend.data.local.dao.LlmAnalysisCacheDao
import com.stock.dividend.data.local.dao.PriceCacheDao
import com.stock.dividend.data.local.dao.SearchCacheDao
import com.stock.dividend.data.plane.DividendFreshnessStore
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 可清理的缓存种类。
 *
 * @property permanent     true = 历史不可变数据（已收盘 K 线、已披露财报期次、已实施分红），
 *   本地**永久缓存**：只增量追加、不因过期删除，断网可用（K 线遇除权漂移才全量重建）；
 *   false = 实时/派生数据，随时可重建。
 * @property label         展示名（中文，宪法 Design Standards；VM 的「已清理 X」消息也用它拼接）。
 * @property description   一句话说明数据内容与清理后果。
 */
enum class CacheKind(val permanent: Boolean, val label: String, val description: String) {
    PRICE(false, "实时价格缓存", "行情快照兜底（冷启动/小组件），清理后下次联网自动重建"),
    SEARCH(false, "搜索缓存", "股票搜索结果，清理后再次搜索自动重建"),
    KLINE(true, "K线历史", "前复权 K 线（已收盘部分不可变），用于 BOLL/回测/走势图，清理后按需重新下载"),
    FUNDAMENTALS(true, "财务指标", "主要财务指标（ROE/负债率等），历史报告期不可变、仅新期次追加"),
    STATEMENTS(true, "财务三表", "利润/现金流/资产负债表，历史报告期不可变、仅新期次追加"),
    DIVIDENDS(true, "分红历史", "历年分红记录，永续保留；清理后重新下载"),
    FUYAO(true, "同花顺数据缓存", "交易日历/指数日K/基金持仓·净值/龙虎榜·热榜历史等（历史不可变部分永续保留），断网可看；清理后重新下载"),
    LLM_ANALYSIS(false, "AI 解读缓存", "LLM 评估结果快照（24 小时有效期），过期仅作离线兜底");
}

/** 单类缓存统计（条目数）。 */
data class CacheStats(val kind: CacheKind, val entries: Long)

/**
 * 缓存管理仓库：统计各持久缓存条目数 + 按种类清理（设置 → 数据 → 缓存管理）。
 *
 * 只做全表级清理（用户语义「把这些缓存删掉」），不做单股级删除（YAGNI）。
 * 全程吞异常（红线 #2）：统计失败记 0、清理失败静默——缓存是可再生数据，不值得为它崩溃。
 *
 * 联动语义：
 * - 清 [CacheKind.DIVIDENDS] 同时清 [DividendFreshnessStore] 记账——否则退避时间戳残留，
 *   清空后 5 分钟内的 `getDps` 会吃闭门羹不重新拉网；
 * - 清理后内存会话缓存（行情 10s/BOLL·市场 60s 窗口）由
 *   [com.stock.dividend.data.plane.MarketDataPlane.clearSessionCaches] 联动清空（VM 编排）。
 */
@Singleton
class CacheManagementRepository @Inject constructor(
    private val priceCacheDao: PriceCacheDao,
    private val searchCacheDao: SearchCacheDao,
    private val klineCacheDao: KlineCacheDao,
    private val fundamentalsCacheDao: FundamentalsCacheDao,
    private val financialStatementsCacheDao: FinancialStatementsCacheDao,
    private val llmAnalysisCacheDao: LlmAnalysisCacheDao,
    private val dividendDao: DividendDao,
    private val dividendFreshnessStore: DividendFreshnessStore,
    private val fuyaoCacheDao: com.stock.dividend.data.local.dao.FuyaoCacheDao,
    private val appDatabase: AppDatabase,
) {
    /** 各缓存当前条目数（按 [CacheKind] 声明顺序返回；单个 DAO 统计失败记 0，不影响其余）。 */
    suspend fun loadStats(): List<CacheStats> = CacheKind.entries.map { kind ->
        CacheStats(kind, runCatchingRethrowingCancellation { countOf(kind) }.getOrDefault(0L))
    }

    /** 清理指定缓存（全表删除；被清数据在下次联网使用时按需重新下载）。 */
    suspend fun clear(kind: CacheKind) {
        runCatchingRethrowingCancellation { clearInternal(kind) }
    }

    /**
     * 一键清理全部缓存：逐 kind 串行清理包进同一事务——中途某个 DAO 失败时整体回滚，
     * 避免「清了一半」的中间态（缓存是可再生数据，回滚后重试即可）。
     */
    suspend fun clearAll() {
        runCatchingRethrowingCancellation {
            appDatabase.withTransaction {
                CacheKind.entries.forEach { kind -> clearInternal(kind) }
            }
        }
    }

    private suspend fun clearInternal(kind: CacheKind) {
        when (kind) {
            CacheKind.PRICE -> priceCacheDao.deleteAll()
            CacheKind.SEARCH -> searchCacheDao.deleteAll()
            CacheKind.KLINE -> klineCacheDao.clearAll()
            CacheKind.FUNDAMENTALS -> fundamentalsCacheDao.clear()
            CacheKind.STATEMENTS -> financialStatementsCacheDao.clear()
            CacheKind.LLM_ANALYSIS -> llmAnalysisCacheDao.clear()
            CacheKind.FUYAO -> fuyaoCacheDao.clearAll()
            CacheKind.DIVIDENDS -> {
                dividendDao.deleteAll()
                dividendFreshnessStore.clear()
            }
        }
    }

    /**
     * runCatching 变体：CancellationException 直接抛出（取消不能被当清理失败吞掉），
     * 其余 Throwable 吞成失败结果（红线 #2：缓存清理失败静默）。
     */
    private inline fun <T> runCatchingRethrowingCancellation(block: () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }

    private suspend fun countOf(kind: CacheKind): Long = when (kind) {
        CacheKind.PRICE -> priceCacheDao.count()
        CacheKind.SEARCH -> searchCacheDao.count()
        CacheKind.KLINE -> klineCacheDao.count()
        CacheKind.FUNDAMENTALS -> fundamentalsCacheDao.count()
        CacheKind.STATEMENTS -> financialStatementsCacheDao.count()
        CacheKind.LLM_ANALYSIS -> llmAnalysisCacheDao.count()
        CacheKind.FUYAO -> fuyaoCacheDao.count()
        CacheKind.DIVIDENDS -> dividendDao.count()
    }
}
