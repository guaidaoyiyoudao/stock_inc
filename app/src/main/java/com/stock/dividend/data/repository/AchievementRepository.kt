package com.stock.dividend.data.repository

import com.stock.dividend.data.local.dao.AchievementDao
import com.stock.dividend.data.local.entity.AchievementEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AchievementRepository @Inject constructor(
    private val achievementDao: AchievementDao
) {
    fun observeAll(): Flow<List<AchievementEntity>> = achievementDao.observeAll()

    suspend fun syncAchievements(qualifiedIds: Set<String>) {
        val existingIds = achievementDao.getAllIds().toSet()
        val newAchievements = (qualifiedIds - existingIds).map {
            AchievementEntity(id = it, unlockedAt = System.currentTimeMillis())
        }
        if (newAchievements.isNotEmpty()) {
            achievementDao.insertAll(newAchievements)
        }
    }
}
