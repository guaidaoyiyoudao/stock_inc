package com.stock.dividend.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.stock.dividend.data.local.entity.IndustryTargetEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface IndustryTargetDao {

    @Query("SELECT * FROM industry_targets")
    fun observeAll(): Flow<List<IndustryTargetEntity>>

    @Query("SELECT * FROM industry_targets")
    suspend fun getAll(): List<IndustryTargetEntity>

    @Query("SELECT * FROM industry_targets WHERE industry = :industry")
    suspend fun getByIndustry(industry: String): IndustryTargetEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: IndustryTargetEntity)

    @Query("DELETE FROM industry_targets WHERE industry = :industry")
    suspend fun deleteByIndustry(industry: String)
}
