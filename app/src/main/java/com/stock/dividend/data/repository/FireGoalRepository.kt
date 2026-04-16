package com.stock.dividend.data.repository

import com.stock.dividend.data.local.dao.FireGoalDao
import com.stock.dividend.data.local.entity.FireGoalEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FireGoalRepository @Inject constructor(
    private val fireGoalDao: FireGoalDao
) {
    fun observeGoal(): Flow<FireGoalEntity?> = fireGoalDao.observe()

    suspend fun getGoalOnce(): FireGoalEntity? = fireGoalDao.getOnce()

    suspend fun saveGoal(amount: Double) {
        val existing = fireGoalDao.getOnce()
        if (existing != null) {
            fireGoalDao.updateAmount(amount)
        } else {
            fireGoalDao.insert(FireGoalEntity(targetAmount = amount))
        }
    }

    suspend fun updateGoal(amount: Double) {
        fireGoalDao.updateAmount(amount)
    }

    suspend fun deleteGoal() {
        fireGoalDao.delete()
    }
}
