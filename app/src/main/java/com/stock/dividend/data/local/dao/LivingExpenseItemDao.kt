package com.stock.dividend.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.stock.dividend.data.local.entity.LivingExpenseItemEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LivingExpenseItemDao {
    @Query("SELECT * FROM living_expense_items ORDER BY sortOrder ASC, createdAt ASC")
    fun observeAll(): Flow<List<LivingExpenseItemEntity>>

    @Query("SELECT * FROM living_expense_items ORDER BY sortOrder ASC, createdAt ASC")
    suspend fun getAllOnce(): List<LivingExpenseItemEntity>

    @Query("SELECT * FROM living_expense_items WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): LivingExpenseItemEntity?

    @Query("SELECT MAX(sortOrder) FROM living_expense_items")
    suspend fun getMaxSortOrder(): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: LivingExpenseItemEntity): Long

    @Update
    suspend fun update(item: LivingExpenseItemEntity)

    @Delete
    suspend fun delete(item: LivingExpenseItemEntity)

    @Query("DELETE FROM living_expense_items WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE living_expense_items SET sortOrder = :sortOrder, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateSortOrders(id: Long, sortOrder: Int, updatedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM living_expense_items")
    suspend fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<LivingExpenseItemEntity>)
}
