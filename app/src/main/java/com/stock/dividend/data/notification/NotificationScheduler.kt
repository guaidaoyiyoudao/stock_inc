package com.stock.dividend.data.notification

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val NOTIFICATION_CHECK_WORK = "notification-rule-checks"
private const val TODAY_BRIEFING_WORK = "today-briefing"

@Singleton
class NotificationScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun schedulePeriodicChecks() {
        val request = PeriodicWorkRequestBuilder<NotificationCheckWorker>(1, TimeUnit.DAYS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            NOTIFICATION_CHECK_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    /** 每日盘后生成今日 AI 简报并缓存。默认 15:45（A 股 15:00 收盘后留 15 分钟数据稳定）。 */
    fun scheduleTodayBriefing() {
        val request = PeriodicWorkRequestBuilder<TodayBriefingWorker>(1, TimeUnit.DAYS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setInitialDelay(minutesUntilNext(15, 45), TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            TODAY_BRIEFING_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }
}

/** 计算到下一个目标时分（hour:minute）的分钟数；已过则顺延次日。 */
private fun minutesUntilNext(hour: Int, minute: Int): Long {
    val now = java.time.LocalDateTime.now()
    var next = now.toLocalDate().atTime(hour, minute)
    if (!next.isAfter(now)) next = next.plusDays(1)
    return java.time.Duration.between(now, next).toMinutes()
}
