package com.stock.dividend.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.stock.dividend.data.local.entity.PriceCacheEntity

@Dao
interface PriceCacheDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<PriceCacheEntity>)

    @Query("SELECT * FROM price_cache WHERE code IN (:codes)")
    suspend fun getByCodes(codes: List<String>): List<PriceCacheEntity>

    @Query("SELECT * FROM price_cache")
    suspend fun getAll(): List<PriceCacheEntity>

    @Query("DELETE FROM price_cache WHERE code IN (:codes)")
    suspend fun deleteByCodes(codes: List<String>)

    @Query("DELETE FROM price_cache")
    suspend fun deleteAll()
}
