package com.stock.dividend.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.stock.dividend.data.local.entity.FireGoalEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FireGoalDao {
    @Query("SELECT * FROM fire_goal LIMIT 1")
    fun observe(): Flow<FireGoalEntity?>

    @Query("SELECT * FROM fire_goal LIMIT 1")
    suspend fun getOnce(): FireGoalEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(goal: FireGoalEntity)

    @Query("UPDATE fire_goal SET targetAmount = :amount, updatedAt = :updatedAt")
    suspend fun updateAmount(amount: Double, updatedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM fire_goal")
    suspend fun delete()

    @Query("SELECT * FROM fire_goal")
    suspend fun getAll(): List<FireGoalEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(goals: List<FireGoalEntity>)
}
