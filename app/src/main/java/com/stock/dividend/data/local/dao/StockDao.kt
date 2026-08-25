package com.stock.dividend.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.stock.dividend.data.local.entity.StockEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface StockDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(stock: StockEntity): Long

    @Query("DELETE FROM stocks WHERE code = :code")
    suspend fun delete(code: String)

    @Query("SELECT * FROM stocks ORDER BY addedAt ASC")
    fun observeAll(): Flow<List<StockEntity>>

    @Query("SELECT * FROM stocks WHERE code = :code")
    suspend fun getByCode(code: String): StockEntity?

    /** 按代码列表批量取实体（单次 IN 查询；数据平面 getPricesForCodes 取齐自选股用）。 */
    @Query("SELECT * FROM stocks WHERE code IN (:codes)")
    suspend fun getByCodes(codes: List<String>): List<StockEntity>

    @Query("SELECT * FROM stocks WHERE code = :code")
    fun observeByCode(code: String): Flow<StockEntity?>

    @Query("UPDATE stocks SET shares = :shares WHERE code = :code")
    suspend fun updateShares(code: String, shares: Int)

    @Query("UPDATE stocks SET yieldPeriod = :period WHERE code = :code")
    suspend fun updateYieldPeriod(code: String, period: String)

    @Query("UPDATE stocks SET costPerShare = :costPerShare WHERE code = :code")
    suspend fun updateCostPerShare(code: String, costPerShare: Double)

    @Query("UPDATE stocks SET targetWeight = :weight WHERE code = :code")
    suspend fun updateTargetWeight(code: String, weight: Double)

    @Query("UPDATE stocks SET industry = :industry WHERE code = :code")
    suspend fun updateIndustry(code: String, industry: String)

    @Query("UPDATE stocks SET buyThresholdMultiplier = :multiplier WHERE code = :code")
    suspend fun updateBuyThresholdMultiplier(code: String, multiplier: Double)

    @Query("SELECT * FROM stocks")
    suspend fun getAll(): List<StockEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(stocks: List<StockEntity>)

    @Query("DELETE FROM stocks")
    suspend fun deleteAll()

    @Query("UPDATE stocks SET lastUpdated = :timestamp WHERE code = :code")
    suspend fun updateLastUpdated(code: String, timestamp: Long)
}
