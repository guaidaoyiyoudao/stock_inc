package com.stock.dividend.data.plane

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * 同 key 并发请求合并（in-flight dedup）：同一 key 的并发调用只执行一次 [block]，
 * 其余调用者等待并共享同一结果；完成（含失败/取消）后自动注销登记，后续调用重新执行。
 *
 * 数据平面用它防止「今日页 + 简报 + Agent 工具同时取同一批行情/BOLL」时的重复网络请求。
 * 每个数据域持有一个实例（值类型不同），key 由调用方保证稳定性（如排序后的代码串）。
 *
 * **共享请求运行在独立宿主作用域**（[scope]，SupervisorJob）：若挂在首个调用者的
 * coroutineScope 下，「用户退出页面 → viewModelScope 取消」会沿结构化并发级联取消
 * 在飞请求，所有已 join 的并发调用者一起被 CancellationException 击中；下游若用
 * catch(Exception)/runCatching 兜底还会把取消误判为失败（负缓存/假失败日志）。
 * 独立作用域下调用者取消只影响自己的 await，共享请求照常完成并写缓存。
 */
internal class InFlightMap<T> {
    private val mutex = Mutex()
    private val flights = ConcurrentHashMap<String, Deferred<T>>()

    private val scope = CoroutineScope(SupervisorJob())

    suspend fun run(key: String, block: suspend () -> T): T {
        var created: Deferred<T>? = null
        val actual = mutex.withLock {
            // 用 isCompleted 而非 isActive 判存活：LAZY Deferred 在 start 前处于 New 态
            // （isActive=false），若按 isActive 判定，「锁内注册、锁外 start」窗口内的并发
            // 调用者会误判无在飞请求而重复发起（恰是本类要防的场景）
            flights[key]?.takeIf { !it.isCompleted }
                ?: scope.async(start = CoroutineStart.LAZY) { block() }
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
        return actual.await()
    }
}
