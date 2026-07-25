package com.stock.dividend.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.stock.dividend.data.local.entity.StockTagEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface StockTagDao {

    @Query("SELECT * FROM stock_tags")
    fun observeAll(): Flow<List<StockTagEntity>>

    @Query("SELECT * FROM stock_tags WHERE stockCode = :code")
    fun observeByStock(code: String): Flow<List<StockTagEntity>>

    @Query("SELECT DISTINCT tag FROM stock_tags ORDER BY tag")
    fun observeAllTags(): Flow<List<String>>

    @Query("SELECT tag FROM stock_tags WHERE stockCode = :code")
    suspend fun getTagsForStock(code: String): List<String>

    @Query("SELECT DISTINCT tag FROM stock_tags ORDER BY tag")
    suspend fun getAllTags(): List<String>

    @Query("SELECT * FROM stock_tags")
    suspend fun getAll(): List<StockTagEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(tag: StockTagEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(tags: List<StockTagEntity>)

    @Query("DELETE FROM stock_tags WHERE stockCode = :stockCode AND tag = :tag")
    suspend fun delete(stockCode: String, tag: String)

    @Query("DELETE FROM stock_tags WHERE stockCode = :code")
    suspend fun clearForStock(code: String)

    @Query("DELETE FROM stock_tags")
    suspend fun deleteAll()
}
