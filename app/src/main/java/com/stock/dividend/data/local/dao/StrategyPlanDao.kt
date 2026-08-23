package com.stock.dividend.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.stock.dividend.data.local.entity.StrategyPlanEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface StrategyPlanDao {
    @Query("SELECT * FROM strategy_plans ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<StrategyPlanEntity>>

    @Query("SELECT * FROM strategy_plans WHERE stockCode = :stockCode ORDER BY updatedAt DESC")
    fun observeByStock(stockCode: String): Flow<List<StrategyPlanEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: StrategyPlanEntity)

    @Query("DELETE FROM strategy_plans WHERE id = :id")
    suspend fun delete(id: String)

    /** 回写卖出档提醒状态。注意：不更新 updatedAt，避免通知回写导致策略列表重排。 */
    @Query("UPDATE strategy_plans SET lastNotifiedSellTier = :lastNotifiedSellTier WHERE id = :id")
    suspend fun updateNotifiedSellTier(id: String, lastNotifiedSellTier: String?)

    @Query("SELECT * FROM strategy_plans")
    suspend fun getAllForBackup(): List<StrategyPlanEntity>

    @Query("DELETE FROM strategy_plans")
    suspend fun clear()

    @Insert
    suspend fun insertAll(items: List<StrategyPlanEntity>)
}
