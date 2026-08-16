package com.stock.dividend.data.repository

import com.stock.dividend.data.local.dao.GridPlanDao
import com.stock.dividend.data.local.entity.GridPlanEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 网格交易计划 Repository（@Singleton）。
 * 网格计划仅用于档位表生成与提示，**不联网下单**。
 */
@Singleton
class GridPlanRepository @Inject constructor(
    private val gridPlanDao: GridPlanDao
) {
    fun observeAll(): Flow<List<GridPlanEntity>> = gridPlanDao.observeAll()

    fun observeByStock(stockCode: String): Flow<List<GridPlanEntity>> =
        gridPlanDao.observeByStock(stockCode)

    suspend fun upsert(plan: GridPlanEntity) = gridPlanDao.upsert(plan)

    suspend fun delete(id: String) = gridPlanDao.delete(id)

    /** 回写到档提醒状态（通知检查用；不动 updatedAt，避免列表重排）。 */
    suspend fun updateNotifiedLevel(id: String, lastNotifiedLevelPrice: Double?) =
        gridPlanDao.updateNotifiedLevel(id, lastNotifiedLevelPrice)

    suspend fun getAllForBackup(): List<GridPlanEntity> = gridPlanDao.getAllForBackup()

    suspend fun clear() = gridPlanDao.clear()

    suspend fun insertAll(items: List<GridPlanEntity>) = gridPlanDao.insertAll(items)
}
