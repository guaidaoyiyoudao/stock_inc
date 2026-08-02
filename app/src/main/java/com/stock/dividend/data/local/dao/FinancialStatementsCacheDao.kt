package com.stock.dividend.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.stock.dividend.data.local.entity.FinancialStatementsCacheEntity

@Dao
interface FinancialStatementsCacheDao {
    @Query("SELECT * FROM financial_statements_cache WHERE stockCode = :stockCode")
    suspend fun get(stockCode: String): FinancialStatementsCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: FinancialStatementsCacheEntity)

    @Query("DELETE FROM financial_statements_cache")
    suspend fun clear()
}
