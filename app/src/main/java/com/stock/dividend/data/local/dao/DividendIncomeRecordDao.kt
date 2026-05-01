package com.stock.dividend.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.stock.dividend.data.local.entity.DividendIncomeRecordEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DividendIncomeRecordDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(records: List<DividendIncomeRecordEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: DividendIncomeRecordEntity)

    @Query("SELECT * FROM dividend_income_records WHERE year = :year ORDER BY date DESC")
    fun observeByYear(year: Int): Flow<List<DividendIncomeRecordEntity>>

    @Query("SELECT * FROM dividend_income_records WHERE source = 'auto'")
    suspend fun getAllAutoRecords(): List<DividendIncomeRecordEntity>

    @Query("UPDATE dividend_income_records SET source = 'manual', amount = :amount, note = :note, updatedAt = :updatedAt WHERE id = :id")
    suspend fun correctRecord(id: String, amount: Double, note: String?, updatedAt: Long)

    @Query("DELETE FROM dividend_income_records WHERE id = :id AND source = 'manual'")
    suspend fun deleteManualRecord(id: String): Int

    @Query("SELECT DISTINCT year FROM dividend_income_records ORDER BY year DESC")
    fun observeAvailableYears(): Flow<List<Int>>

    @Query("SELECT COALESCE(SUM(amount), 0.0) FROM dividend_income_records WHERE year = :year")
    fun observeTotalByYear(year: Int): Flow<Double>

    @Query("SELECT COALESCE(SUM(amount), 0.0) FROM dividend_income_records WHERE year = :year AND source = 'manual'")
    fun observeManualTotalByYear(year: Int): Flow<Double>

    @Query("SELECT COALESCE(SUM(amount), 0.0) FROM dividend_income_records WHERE year = :year AND source = 'auto'")
    fun observeAutoTotalByYear(year: Int): Flow<Double>

    @Query("SELECT COUNT(*) FROM dividend_income_records WHERE year = :year AND source = 'manual'")
    suspend fun getManualCountByYear(year: Int): Int

    @Query("SELECT COUNT(*) FROM dividend_income_records WHERE year = :year AND source = 'auto'")
    suspend fun getAutoCountByYear(year: Int): Int
}
