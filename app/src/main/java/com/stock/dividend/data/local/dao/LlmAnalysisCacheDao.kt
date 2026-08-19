package com.stock.dividend.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.stock.dividend.data.local.entity.LlmAnalysisCacheEntity

@Dao
interface LlmAnalysisCacheDao {
    @Query("SELECT * FROM llm_analysis_cache WHERE cacheKey = :cacheKey AND scope = :scope")
    suspend fun get(cacheKey: String, scope: String): LlmAnalysisCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: LlmAnalysisCacheEntity)

    @Query("DELETE FROM llm_analysis_cache")
    suspend fun clear()

    /** 缓存管理：当前条目数。 */
    @Query("SELECT COUNT(*) FROM llm_analysis_cache")
    suspend fun count(): Long
}
