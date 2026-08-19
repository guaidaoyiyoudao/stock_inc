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

    @Query("SELECT * FROM dividends WHERE stockCode = :stockCode ORDER BY reportDate DESC")
    suspend fun getByStock(stockCode: String): List<DividendEntity>

    @Query("SELECT * FROM dividends ORDER BY COALESCE(exDividendDate, recordDate, reportDate) ASC")
    fun observeAll(): Flow<List<DividendEntity>>

    @Query("DELETE FROM dividends")
    suspend fun deleteAll()

    /** 缓存管理：当前条目数。 */
    @Query("SELECT COUNT(*) FROM dividends")
    suspend fun count(): Long

    @Query("SELECT COALESCE(SUM(cashPerShare), 0.0) FROM dividends")
    fun observeTotalCashPerShare(): Flow<Double>

    @Query("SELECT * FROM dividends WHERE exDividendDate IS NOT NULL")
    suspend fun getAllWithExDate(): List<DividendEntity>

    @Query("SELECT * FROM dividends WHERE stockCode = :stockCode ORDER BY reportDate DESC LIMIT 1")
    suspend fun getLatestByStock(stockCode: String): DividendEntity?

    @Query("SELECT * FROM dividends")
    suspend fun getAll(): List<DividendEntity>

    /** 该股最新除权日（ISO 日期）。K 线缓存用它检测前复权漂移：出现更新除权日 → 全历史价格位移。 */
    @Query("SELECT MAX(exDividendDate) FROM dividends WHERE stockCode = :stockCode")
    suspend fun getLatestExDividendDate(stockCode: String): String?

    /** 历史保留式写入：按 id 定点删除本次结果覆盖到的行（窗口外历史行不动）。 */
    @Query("DELETE FROM dividends WHERE stockCode = :stockCode AND id IN (:ids)")
    suspend fun deleteByIds(stockCode: String, ids: List<String>)

    /** 历史保留式写入：按除权日定点删除——腾讯(id=code_exDate)与东财(id=code_reportDate)两种 id 方案跨源去重。 */
    @Query("DELETE FROM dividends WHERE stockCode = :stockCode AND exDividendDate IN (:exDates)")
    suspend fun deleteByStockAndExDates(stockCode: String, exDates: List<String>)

    /** 清洗失效预案行（exDate=null 且不在本次结果中）。仅东财全量路径调用（腾讯不携带预案信息）。 */
    @Query("DELETE FROM dividends WHERE stockCode = :stockCode AND exDividendDate IS NULL AND id NOT IN (:keepIds)")
    suspend fun deleteStalePendingByStock(stockCode: String, keepIds: List<String>)
}
