package com.stock.dividend.data.repository

import com.stock.dividend.data.local.dao.StrategyPlanDao
import com.stock.dividend.data.local.entity.StrategyPlanEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 交易策略计划 Repository（@Singleton）。
 * 策略仅用于信号提示与记账辅助，**不联网下单**。
 */
@Singleton
class StrategyPlanRepository @Inject constructor(
    private val strategyPlanDao: StrategyPlanDao
) {
    fun observeAll(): Flow<List<StrategyPlanEntity>> = strategyPlanDao.observeAll()

    fun observeByStock(stockCode: String): Flow<List<StrategyPlanEntity>> =
        strategyPlanDao.observeByStock(stockCode)

    suspend fun upsert(plan: StrategyPlanEntity) = strategyPlanDao.upsert(plan)

    suspend fun delete(id: String) = strategyPlanDao.delete(id)

    /** 回写卖出档提醒状态（通知检查用；不动 updatedAt，避免列表重排）。 */
    suspend fun updateNotifiedSellTier(id: String, lastNotifiedSellTier: String?) =
        strategyPlanDao.updateNotifiedSellTier(id, lastNotifiedSellTier)

    suspend fun getAllForBackup(): List<StrategyPlanEntity> = strategyPlanDao.getAllForBackup()

    suspend fun clear() = strategyPlanDao.clear()

    suspend fun insertAll(items: List<StrategyPlanEntity>) = strategyPlanDao.insertAll(items)
}
