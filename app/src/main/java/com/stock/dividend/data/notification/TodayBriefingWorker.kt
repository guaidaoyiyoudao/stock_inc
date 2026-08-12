package com.stock.dividend.data.notification

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.stock.dividend.data.repository.TodayBriefingCoordinator
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.LocalDate

/** 每日盘后生成今日 AI 简报并缓存。失败 retry；UI 读不到缓存就不显示 AI 卡（不阻塞 UI）。 */
@HiltWorker
class TodayBriefingWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val coordinator: TodayBriefingCoordinator,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = try {
        coordinator.generateAndCache(LocalDate.now())
        Result.success()
    } catch (_: Exception) {
        Result.retry()
    }
}
