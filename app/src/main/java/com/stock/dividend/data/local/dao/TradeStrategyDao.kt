package com.stock.dividend.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.stock.dividend.data.local.entity.TradeStrategyEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TradeStrategyDao {
    @Query("SELECT * FROM trade_strategies ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<TradeStrategyEntity>>

    /** 全部活跃且未过期的策略（回流用，全局，不过滤个股）。 */
    @Query(
        "SELECT * FROM trade_strategies WHERE status = 'ACTIVE' " +
            "AND (validUntil IS NULL OR validUntil >= :today) ORDER BY createdAt DESC"
    )
    suspend fun activeStrategies(today: String): List<TradeStrategyEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: TradeStrategyEntity)

    @Query("UPDATE trade_strategies SET status = :status, updatedAt = :now WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, now: Long = System.currentTimeMillis())

    @Query("DELETE FROM trade_strategies WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM trade_strategies")
    suspend fun getAllForBackup(): List<TradeStrategyEntity>

    @Query("DELETE FROM trade_strategies")
    suspend fun clear()

    @Insert
    suspend fun insertAll(items: List<TradeStrategyEntity>)
}
