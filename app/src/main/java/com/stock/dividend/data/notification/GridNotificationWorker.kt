package com.stock.dividend.data.notification

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * 网格到档提醒检查 Worker（独立于每日规则检查，每小时跑一次）。
 * 网格到档是「该执行了」的时效信号，日频太粗；详见 [NotificationCheckCoordinator.checkGridPlans]。
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
            }
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
