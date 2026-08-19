package com.stock.dividend.data.plane

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * 同 key 并发请求合并（in-flight dedup）：同一 key 的并发调用只执行一次 [block]，
 * 其余调用者等待并共享同一结果；完成（含失败/取消）后自动注销登记，后续调用重新执行。
 *
 * 数据平面用它防止「今日页 + 简报 + Agent 工具同时取同一批行情/BOLL」时的重复网络请求。
 * 每个数据域持有一个实例（值类型不同），key 由调用方保证稳定性（如排序后的代码串）。
 */
internal class InFlightMap<T> {
    private val mutex = Mutex()
    private val flights = ConcurrentHashMap<String, Deferred<T>>()

    suspend fun run(key: String, block: suspend () -> T): T = coroutineScope {
        var created: Deferred<T>? = null
        val actual = mutex.withLock {
            flights[key]?.takeIf { it.isActive } ?: async(start = CoroutineStart.LAZY) { block() }
                .also { d ->
                    created = d
                    flights[key] = d
                    // 完成后注销（含失败/取消）；两参 remove 只移除自己，避免误删后来者。
                    // 注意捕获具名 d——invokeOnCompletion 的 it 是 Throwable，会遮蔽外层
                    d.invokeOnCompletion { flights.remove(key, d) }
                }
        }
        // 拥有者在锁外启动，避免 block 在持锁期间执行阻塞其他 key 的登记
        created?.start()
        actual.await()
    }
}
