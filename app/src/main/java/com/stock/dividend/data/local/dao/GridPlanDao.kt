package com.stock.dividend.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.stock.dividend.data.local.entity.GridPlanEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GridPlanDao {
    @Query("SELECT * FROM grid_plans ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<GridPlanEntity>>

    @Query("SELECT * FROM grid_plans WHERE stockCode = :stockCode ORDER BY updatedAt DESC")
    fun observeByStock(stockCode: String): Flow<List<GridPlanEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: GridPlanEntity)

    @Query("DELETE FROM grid_plans WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM grid_plans")
    suspend fun getAllForBackup(): List<GridPlanEntity>

    @Query("DELETE FROM grid_plans")
    suspend fun clear()

    @Insert
    suspend fun insertAll(items: List<GridPlanEntity>)
}
