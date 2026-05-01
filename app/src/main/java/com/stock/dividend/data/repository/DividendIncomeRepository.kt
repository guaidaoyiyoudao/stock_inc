package com.stock.dividend.data.repository

import com.stock.dividend.data.local.dao.DividendDao
import com.stock.dividend.data.local.dao.DividendIncomeRecordDao
import com.stock.dividend.data.local.dao.YearlyTotal
import com.stock.dividend.data.local.dao.StockDao
import com.stock.dividend.data.local.entity.DividendIncomeRecordEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DividendIncomeRepository @Inject constructor(
    private val incomeRecordDao: DividendIncomeRecordDao,
    private val dividendDao: DividendDao,
    private val stockDao: StockDao
) {
    suspend fun generateMissingAutoRecords() {
        val allDividends = dividendDao.getAllWithExDate()
        val existingIds = incomeRecordDao.getAllIds().toSet()

        val newRecords = mutableListOf<DividendIncomeRecordEntity>()
        for (dividend in allDividends) {
            val exDate = dividend.exDividendDate ?: continue
            val autoId = "auto_${dividend.stockCode}_${exDate}"
            if (autoId in existingIds) continue

            val stock = stockDao.getByCode(dividend.stockCode) ?: continue
            val shares = stock.shares
            val amount = dividend.cashPerShare * shares

            newRecords.add(
                DividendIncomeRecordEntity(
                    id = autoId,
                    stockCode = dividend.stockCode,
                    year = exDate.substring(0, 4).toInt(),
                    date = exDate,
                    amount = amount,
                    exDividendDate = exDate,
                    source = "auto"
                )
            )
        }

        if (newRecords.isNotEmpty()) {
            incomeRecordDao.insertAll(newRecords)
        }
    }

    fun observeByYear(year: Int): Flow<List<DividendIncomeRecordEntity>> =
        incomeRecordDao.observeByYear(year)

    fun observeAvailableYears(): Flow<List<Int>> =
        incomeRecordDao.observeAvailableYears()

    fun observeTotalByYear(year: Int): Flow<Double> =
        incomeRecordDao.observeTotalByYear(year)

    fun observeYearlyTotals(): Flow<List<YearlyTotal>> =
        incomeRecordDao.observeYearlyTotals()

    suspend fun getManualCountByYear(year: Int): Int =
        incomeRecordDao.getManualCountByYear(year)

    suspend fun getAutoCountByYear(year: Int): Int =
        incomeRecordDao.getAutoCountByYear(year)

    suspend fun correctRecord(id: String, amount: Double, note: String?) {
        incomeRecordDao.correctRecord(id, amount, note, System.currentTimeMillis())
    }

    suspend fun addManualRecord(date: String, amount: Double, stockCode: String?, note: String?) {
        val year = date.substring(0, 4).toInt()
        val record = DividendIncomeRecordEntity(
            id = "manual_${System.currentTimeMillis()}",
            stockCode = stockCode,
            year = year,
            date = date,
            amount = amount,
            source = "manual",
            note = note
        )
        incomeRecordDao.insert(record)
    }

    suspend fun deleteManualRecord(id: String) {
        incomeRecordDao.deleteManualRecord(id)
    }
}
