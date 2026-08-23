package com.stock.dividend.data.notification

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * 时效型计划信号检查 Worker（每小时跑一次，独立于每日规则检查）：
 * 网格到档提醒（买入档/波段卖出锚）+ 交易策略卖出阈值提醒（年线定投卖半/清仓档）。
 * 两者都是「该执行了」的时效信号，日频太粗；
 * 详见 [NotificationCheckCoordinator.checkGridPlans] / [NotificationCheckCoordinator.checkStrategies]。
 * 非 A 股交易时段（周末/盘外）直接跳过，不发无意义的行情请求（[AshareTradingTime]）。
 */
@HiltWorker
class GridNotificationWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val coordinator: NotificationCheckCoordinator
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            if (AshareTradingTime.isTradingWindow(java.time.LocalDateTime.now())) {
                coordinator.checkGridPlans()
                coordinator.checkStrategies()
            }
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
