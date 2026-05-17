package com.stock.dividend.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.stock.dividend.data.local.entity.TransactionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {
    @Insert
    suspend fun insert(transaction: TransactionEntity): Long

    @Delete
    suspend fun delete(transaction: TransactionEntity)

    @Update
    suspend fun update(transaction: TransactionEntity)

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM transactions WHERE stockCode = :stockCode ORDER BY date ASC, createdAt ASC")
    fun observeByStock(stockCode: String): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE stockCode = :stockCode ORDER BY date ASC, createdAt ASC")
    suspend fun getByStock(stockCode: String): List<TransactionEntity>

    @Query("SELECT * FROM transactions ORDER BY date ASC, createdAt ASC")
    suspend fun getAll(): List<TransactionEntity>

    @Query(
        """SELECT COALESCE(SUM(CASE WHEN type = 'BUY' THEN shares ELSE 0 END), 0) -
                  COALESCE(SUM(CASE WHEN type = 'SELL' THEN shares ELSE 0 END), 0)
           FROM transactions WHERE stockCode = :stockCode"""
    )
    suspend fun getNetShares(stockCode: String): Int

    @Query(
        """SELECT MIN(date) FROM transactions
           WHERE stockCode = :stockCode AND type = 'BUY'"""
    )
    suspend fun getFirstBuyDate(stockCode: String): String?
}
