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

    /** 回写到档提醒状态。注意：不更新 updatedAt，避免通知回写导致计划列表重排。 */
    @Query("UPDATE grid_plans SET lastNotifiedLevelPrice = :lastNotifiedLevelPrice WHERE id = :id")
    suspend fun updateNotifiedLevel(id: String, lastNotifiedLevelPrice: Double?)

    /** 回写卖出档提醒状态（波段模式）。同样不更新 updatedAt。 */
    @Query("UPDATE grid_plans SET lastNotifiedSellLevelPrice = :lastNotifiedSellLevelPrice WHERE id = :id")
    suspend fun updateNotifiedSellLevel(id: String, lastNotifiedSellLevelPrice: Double?)

    @Query("SELECT * FROM grid_plans")
    suspend fun getAllForBackup(): List<GridPlanEntity>

    @Query("DELETE FROM grid_plans")
    suspend fun clear()

    @Insert
    suspend fun insertAll(items: List<GridPlanEntity>)
}
