package com.stock.dividend.data.repository

import androidx.annotation.VisibleForTesting
import com.stock.dividend.data.local.dao.ErrorLogDao
import com.stock.dividend.data.local.entity.ErrorLogEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 失败日志分类。存储 raw（name），展示 label。
 */
enum class ErrorLogCategory(val label: String) {
    /** 网络/数据获取失败（行情、分红、K线、基本面等）。 */
    NETWORK("数据获取"),

    /** 本地数据库读写失败。 */
    DATABASE("本地存储"),

    /** AI/LLM 调用失败。 */
    LLM("AI 调用"),
}

/**
 * 关键失败日志仓库（error_logs 表，DB v26）：记录「静默失败」的关键事件
 * （数据获取失败等，红线 #2 吞异常处埋点），设置 → 数据 → 失败日志 页查看与清理。
 *
 * 记录语义：
 * - **防刷屏**：与最新一条 source+message 相同且 [DEDUP_WINDOW_MS] 内的重复失败只记一次
 *   （退避重试/下拉刷新连点不会刷满一屏）；
 * - **防膨胀**：每条插入后裁剪只保留最近 [MAX_LOGS] 条；
 * - **绝不反噬主流程**：记录自身任何失败（DB 挂了等）一律吞掉——收集日志的代码
 *   不能成为新的故障源（红线 #2 对日志自身同样适用）。
 */
@Singleton
class ErrorLogRepository @Inject constructor(
    private val errorLogDao: ErrorLogDao,
) {

    /** 测试可替换的时钟（默认真实时间）。 */
    @VisibleForTesting
    @Volatile
    var nowProvider: () -> Long = System::currentTimeMillis

    /**
     * 记录一条失败日志。同 [source]+[message] 在防抖窗口内的重复调用静默跳过。
     * 本方法自身任何异常都吞掉（返回不抛）。
     */
    suspend fun record(
        source: String,
        message: String,
        throwable: Throwable? = null,
        category: ErrorLogCategory = ErrorLogCategory.NETWORK,
    ) {
        runCatchingRethrowingCancellation {
            val now = nowProvider()
            val latest = errorLogDao.latest()
            if (latest != null && latest.source == source && latest.message == message &&
                now - latest.timestamp < DEDUP_WINDOW_MS
            ) {
                return
            }
            errorLogDao.insert(
                ErrorLogEntity(
                    timestamp = now,
                    category = category.name,
                    source = source,
                    message = message,
                    detail = throwable?.toDetail(),
                )
            )
            errorLogDao.trimToRecent(MAX_LOGS)
        }
    }

    /** 倒序全量观察（失败日志页响应式列表；清理后自动重发射）。 */
    fun observeAll(): Flow<List<ErrorLogEntity>> = errorLogDao.observeAll()

    /** 清空全部日志（失败日志页「清理」入口）。 */
    suspend fun clearAll() {
        runCatchingRethrowingCancellation { errorLogDao.clearAll() }
    }

    /** 条目数（说明卡合计用）。 */
    suspend fun count(): Long = runCatchingRethrowingCancellation { errorLogDao.count() }.getOrDefault(0L)

    /**
     * runCatching 变体：CancellationException 直接抛出（取消不能被当失败吞掉），
     * 其余 Throwable 吞成失败结果（红线 #2：记日志的代码不能反噬主流程）。
     */
    private inline fun <T> runCatchingRethrowingCancellation(block: () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }

    /** 异常详情：类名+消息+裁剪后的堆栈（长堆栈截断，防 detail 列膨胀）。 */
    private fun Throwable.toDetail(): String {
        val stack = stackTrace.take(MAX_STACK_FRAMES)
            .joinToString("\n") { "    at $it" }
        val text = "${this::class.java.name}: $message\n$stack"
        return if (text.length > MAX_DETAIL_CHARS) text.substring(0, MAX_DETAIL_CHARS) else text
    }

    companion object {
        /** 同源同消息防抖窗口（毫秒）。 */
        const val DEDUP_WINDOW_MS = 60_000L

        /** 表内最多保留条数（超出裁最旧的）。 */
        const val MAX_LOGS = 200

        /** detail 堆栈最多保留帧数。 */
        private const val MAX_STACK_FRAMES = 12

        /** detail 最大字符数。 */
        private const val MAX_DETAIL_CHARS = 2000
    }
}
