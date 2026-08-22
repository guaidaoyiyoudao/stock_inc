package com.stock.dividend.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.stock.dividend.data.local.entity.ErrorLogEntity
import kotlinx.coroutines.flow.Flow

/**
 * 关键失败日志 DAO。id 自增即时间序，倒序 = 最新在前。
 */
@Dao
interface ErrorLogDao {

    @Insert
    suspend fun insert(entity: ErrorLogEntity)

    @Query("SELECT * FROM error_logs ORDER BY id DESC")
    fun observeAll(): Flow<List<ErrorLogEntity>>

    /** 最新一条（防抖判重用）。 */
    @Query("SELECT * FROM error_logs ORDER BY id DESC LIMIT 1")
    suspend fun latest(): ErrorLogEntity?

    @Query("SELECT COUNT(*) FROM error_logs")
    suspend fun count(): Long

    @Query("DELETE FROM error_logs")
    suspend fun clearAll()

    /** 只保留最近 [keep] 条（防表无限膨胀）。 */
    @Query("DELETE FROM error_logs WHERE id NOT IN (SELECT id FROM error_logs ORDER BY id DESC LIMIT :keep)")
    suspend fun trimToRecent(keep: Int)
}
