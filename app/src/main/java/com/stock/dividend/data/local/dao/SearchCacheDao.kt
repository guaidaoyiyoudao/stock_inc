package com.stock.dividend.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.stock.dividend.data.local.entity.SearchCacheEntity

@Dao
interface SearchCacheDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<SearchCacheEntity>)

    @Query("SELECT * FROM search_cache WHERE queryKey = :queryKey")
    suspend fun getByQuery(queryKey: String): List<SearchCacheEntity>

    @Query("SELECT * FROM search_cache")
    suspend fun getAll(): List<SearchCacheEntity>

    @Query("DELETE FROM search_cache WHERE queryKey = :queryKey")
    suspend fun deleteByQuery(queryKey: String)

    @Query("DELETE FROM search_cache")
    suspend fun deleteAll()
}
