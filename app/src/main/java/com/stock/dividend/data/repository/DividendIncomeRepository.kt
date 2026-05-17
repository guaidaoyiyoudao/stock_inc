package com.stock.dividend.data.repository

import com.stock.dividend.data.local.dao.DividendDao
import com.stock.dividend.data.local.dao.DividendIncomeRecordDao
import com.stock.dividend.data.local.dao.StockYearlyIncome
import com.stock.dividend.data.local.dao.YearlyTotal
import com.stock.dividend.data.local.dao.StockDao
import com.stock.dividend.data.local.dao.TransactionDao
import com.stock.dividend.data.local.entity.DividendIncomeRecordEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DividendIncomeRepository @Inject constructor(
    private val incomeRecordDao: DividendIncomeRecordDao,
    private val dividendDao: DividendDao,
    private val stockDao: StockDao,
    private val transactionDao: TransactionDao
) {
    suspend fun generateMissingAutoRecords() {
        val allDividends = dividendDao.getAllWithExDate()
        val existingIds = incomeRecordDao.getAllIds().toSet()
        val existingRecords = incomeRecordDao.getAllRecords()
        deleteAutoRecordsDuplicatedByManual(existingRecords)
        val existingEventKeys = existingRecords
            .mapNotNull { record ->
                val stockCode = record.stockCode ?: return@mapNotNull null
                val date = (record.exDividendDate ?: record.date).toDateOnlyOrNull() ?: return@mapNotNull null
                stockCode to date
            }
            .toSet()

        val newRecords = mutableListOf<DividendIncomeRecordEntity>()
        for (dividend in allDividends) {
            val exDate = dividend.exDividendDate.toDateOnlyOrNull() ?: continue
            val autoId = "auto_${dividend.stockCode}_${exDate}"
            if (autoId in existingIds) continue
            if (dividend.stockCode to exDate in existingEventKeys) continue

            val stock = stockDao.getByCode(dividend.stockCode) ?: continue

            val heldShares = calculateHeldSharesAtDate(dividend.stockCode, exDate, stock.shares)
            if (heldShares <= 0) continue

            val amount = dividend.cashPerShare * heldShares

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

    private suspend fun deleteAutoRecordsDuplicatedByManual(records: List<DividendIncomeRecordEntity>) {
        val manualEventKeys = records
            .filter { it.source == "manual" }
            .mapNotNull { record ->
                val stockCode = record.stockCode ?: return@mapNotNull null
                val date = (record.exDividendDate ?: record.date).toDateOnlyOrNull() ?: return@mapNotNull null
                stockCode to date
            }
            .toSet()

        val duplicateAutoIds = records
            .filter { it.source == "auto" }
            .filter { record ->
                val stockCode = record.stockCode ?: return@filter false
                val date = (record.exDividendDate ?: record.date).toDateOnlyOrNull() ?: return@filter false
                stockCode to date in manualEventKeys
            }
            .map { it.id }

        if (duplicateAutoIds.isNotEmpty()) {
            incomeRecordDao.deleteByIds(duplicateAutoIds)
        }
    }

    suspend fun regenerateAutoRecords() {
        incomeRecordDao.deleteAllAutoRecords()
        generateMissingAutoRecords()
    }

    private suspend fun calculateHeldSharesAtDate(stockCode: String, date: String, defaultShares: Int): Int {
        val transactions = transactionDao.getByStock(stockCode)
        if (transactions.isEmpty()) return defaultShares

        val held = transactions
            .filter { it.date <= date }
            .sumOf { if (it.type == "BUY") it.shares else -it.shares }
        return held.coerceAtLeast(0)
    }

    private fun String?.toDateOnlyOrNull(): String? =
        this
            ?.substringBefore("T")
            ?.substringBefore(" ")
            ?.takeIf { it.isNotBlank() }

    fun observeByYear(year: Int): Flow<List<DividendIncomeRecordEntity>> =
        incomeRecordDao.observeByYear(year)

    fun observeAvailableYears(): Flow<List<Int>> =
        incomeRecordDao.observeAvailableYears()

    fun observeTotalByYear(year: Int): Flow<Double> =
        incomeRecordDao.observeTotalByYear(year)

    fun observeYearlyTotals(): Flow<List<YearlyTotal>> =
        incomeRecordDao.observeYearlyTotals()

    fun observeRecordCount(): Flow<Int> =
        incomeRecordDao.observeRecordCount()

    fun observeMaxSingleIncome(): Flow<Double> =
        incomeRecordDao.observeMaxSingleIncome()

    fun observePerStockYearlyIncome(): Flow<List<StockYearlyIncome>> =
        incomeRecordDao.observePerStockYearlyIncome()

    fun observeForecastTotal(): Flow<Double> =
        stockDao.observeAll().flatMapLatest { stocks ->
            val activeStocks = stocks.filter { it.shares > 0 }
            if (activeStocks.isEmpty()) {
                flowOf(0.0)
            } else {
                combine(
                    activeStocks.map { stock ->
                        dividendDao.observeByStock(stock.code).map { dividends ->
                            val result = ForecastCalculator.calculateForecastIncome(
                                dividends, stock.shares, stock.yieldPeriod.toIntOrNull() ?: 3
                            )
                            result?.avgCashPerShare?.let { it * stock.shares } ?: 0.0
                        }
                    }
                ) { incomes -> incomes.sum() }
            }
        }.distinctUntilChanged()

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
