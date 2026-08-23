package com.stock.dividend.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.stock.dividend.data.local.entity.FuyaoCacheEntity

@Dao
interface FuyaoCacheDao {
    @Query("SELECT * FROM fuyao_cache WHERE `key` = :key")
    suspend fun get(key: String): FuyaoCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: FuyaoCacheEntity)

    @Query("SELECT COUNT(*) FROM fuyao_cache")
    suspend fun count(): Long

    @Query("DELETE FROM fuyao_cache")
    suspend fun clearAll()
}
