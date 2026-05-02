package com.stock.dividend.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.stock.dividend.data.local.entity.DividendEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DividendDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(dividends: List<DividendEntity>)

    @Query("DELETE FROM dividends WHERE stockCode = :stockCode")
    suspend fun deleteByStockCode(stockCode: String)

    @Query("SELECT * FROM dividends WHERE stockCode = :stockCode ORDER BY reportDate DESC")
    fun observeByStock(stockCode: String): Flow<List<DividendEntity>>

    @Query("DELETE FROM dividends")
    suspend fun deleteAll()

    @Query("SELECT COALESCE(SUM(cashPerShare), 0.0) FROM dividends")
    fun observeTotalCashPerShare(): Flow<Double>

    @Query("SELECT * FROM dividends WHERE exDividendDate IS NOT NULL")
    suspend fun getAllWithExDate(): List<DividendEntity>

    @Query("SELECT * FROM dividends WHERE stockCode = :stockCode ORDER BY reportDate DESC LIMIT 1")
    suspend fun getLatestByStock(stockCode: String): DividendEntity?
}
