package com.stock.dividend.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.stock.dividend.data.local.entity.NotificationRuleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NotificationRuleDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rule: NotificationRuleEntity)

    @Query("SELECT * FROM notification_rules WHERE type = :type")
    suspend fun getRulesByType(type: String): List<NotificationRuleEntity>

    @Query("SELECT * FROM notification_rules WHERE type = :type AND stockCode IS NULL LIMIT 1")
    fun observeGlobalRule(type: String): Flow<NotificationRuleEntity?>

    @Query("SELECT * FROM notification_rules WHERE type = :type AND stockCode = :stockCode LIMIT 1")
    fun observeStockRule(type: String, stockCode: String): Flow<NotificationRuleEntity?>

    @Query("SELECT * FROM notification_rules WHERE stockCode = :stockCode ORDER BY type")
    fun observeStockRules(stockCode: String): Flow<List<NotificationRuleEntity>>

    @Query("SELECT * FROM notification_rules WHERE type = :type AND stockCode IS NULL LIMIT 1")
    suspend fun getGlobalRule(type: String): NotificationRuleEntity?

    @Query("SELECT * FROM notification_rules WHERE type = :type AND stockCode = :stockCode LIMIT 1")
    suspend fun getStockRule(type: String, stockCode: String): NotificationRuleEntity?

    @Query("SELECT * FROM notification_rules WHERE stockCode = :stockCode ORDER BY type")
    suspend fun getStockRules(stockCode: String): List<NotificationRuleEntity>

    @Query("SELECT * FROM notification_rules")
    suspend fun getAll(): List<NotificationRuleEntity>

    @Query("DELETE FROM notification_rules")
    suspend fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rules: List<NotificationRuleEntity>)
}
