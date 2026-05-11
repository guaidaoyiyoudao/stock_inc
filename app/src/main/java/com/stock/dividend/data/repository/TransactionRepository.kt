package com.stock.dividend.data.repository

import com.stock.dividend.data.local.dao.TransactionDao
import com.stock.dividend.data.local.entity.TransactionEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TransactionRepository @Inject constructor(
    private val transactionDao: TransactionDao
) {
    suspend fun addTransaction(transaction: TransactionEntity): Long =
        transactionDao.insert(transaction)

    suspend fun deleteTransaction(transaction: TransactionEntity) =
        transactionDao.delete(transaction)

    suspend fun deleteTransactionById(id: Long) =
        transactionDao.deleteById(id)

    suspend fun updateTransactionDate(id: Long, date: String) =
        transactionDao.updateDate(id, date)

    fun observeByStock(stockCode: String): Flow<List<TransactionEntity>> =
        transactionDao.observeByStock(stockCode)

    suspend fun getByStock(stockCode: String): List<TransactionEntity> =
        transactionDao.getByStock(stockCode)

    suspend fun getNetShares(stockCode: String): Int =
        transactionDao.getNetShares(stockCode)

    suspend fun getFirstBuyDate(stockCode: String): String? =
        transactionDao.getFirstBuyDate(stockCode)

    suspend fun getAll(): List<TransactionEntity> =
        transactionDao.getAll()
}
