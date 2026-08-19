package com.stock.dividend.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.stock.dividend.data.local.entity.KlineCacheEntity
import com.stock.dividend.data.local.entity.KlineCacheMetaEntity

@Dao
interface KlineCacheDao {

    @Query("SELECT * FROM kline_cache WHERE stockCode = :stockCode AND period = :period ORDER BY date ASC")
    suspend fun getBars(stockCode: String, period: String): List<KlineCacheEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBars(bars: List<KlineCacheEntity>)

    @Query("DELETE FROM kline_cache WHERE stockCode = :stockCode AND period = :period")
    suspend fun deleteByStock(stockCode: String, period: String)

    /** 全量重建（首拉/前复权漂移）：删旧插新保持原子。 */
    @Transaction
    suspend fun replaceBars(stockCode: String, period: String, bars: List<KlineCacheEntity>) {
        deleteByStock(stockCode, period)
        upsertBars(bars)
    }

    /** 只保留最近 [keep] 根，防增量写入无限增长。 */
    @Query(
        "DELETE FROM kline_cache WHERE stockCode = :stockCode AND period = :period AND date NOT IN " +
            "(SELECT date FROM kline_cache WHERE stockCode = :stockCode AND period = :period " +
            "ORDER BY date DESC LIMIT :keep)"
    )
    suspend fun trimToRecent(stockCode: String, period: String, keep: Int)

    @Query("SELECT * FROM kline_cache_meta WHERE stockCode = :stockCode AND period = :period")
    suspend fun getMeta(stockCode: String, period: String): KlineCacheMetaEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMeta(meta: KlineCacheMetaEntity)

    /** 缓存管理：当前 K 线条数（meta 行数远小于 bars，不计入展示）。 */
    @Query("SELECT COUNT(*) FROM kline_cache")
    suspend fun count(): Long

    /** 缓存管理：清空全部 K 线缓存（bars + meta 一并删）。 */
    @Transaction
    suspend fun clearAll() {
        deleteAllBars()
        deleteAllMeta()
    }

    @Query("DELETE FROM kline_cache")
    suspend fun deleteAllBars()

    @Query("DELETE FROM kline_cache_meta")
    suspend fun deleteAllMeta()
}
