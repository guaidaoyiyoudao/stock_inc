package com.stock.dividend.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.stock.dividend.data.local.entity.FundamentalsCacheEntity

@Dao
interface FundamentalsCacheDao {
    @Query("SELECT * FROM fundamentals_cache WHERE stockCode = :stockCode")
    suspend fun get(stockCode: String): FundamentalsCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: FundamentalsCacheEntity)

    @Query("DELETE FROM fundamentals_cache")
    suspend fun clear()

    /** 缓存管理：当前条目数。 */
    @Query("SELECT COUNT(*) FROM fundamentals_cache")
    suspend fun count(): Long
}
