package com.stock.dividend.data.plane

import com.google.common.truth.Truth.assertThat
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * [InFlightMap] 并发合并语义锁定（2026-08-24 评审修复回归）：
 * 1. 同 key 并发只执行一次 block；
 * 2. 共享请求挂独立宿主作用域——首个调用者（拥有者）被取消不级联取消其他 joiner
 *    （修复前 async 挂在拥有者 coroutineScope 下，页面退出会连带击中所有等待者）；
 * 3. 失败传播给所有等待者、登记注销后下一次调用重新执行。
 */
class InFlightMapTest {

    @Test
    fun `same key concurrent calls execute block once and share result`() = runTest {
        val map = InFlightMap<String>()
        var executions = 0
        val gate = CompletableDeferred<Unit>()

        val callers = (1..5).map {
            async { map.run("k") { executions++; gate.await(); "v" } }
        }
        advanceUntilIdle()   // 5 个并发调用全部登记/等待，首个已启动执行到 gate
        gate.complete(Unit)
        advanceUntilIdle()

        assertThat(callers.map { it.await() }).containsExactly("v", "v", "v", "v", "v")
        assertThat(executions).isEqualTo(1)
    }

    @Test
    fun `owner cancellation does not cancel shared result for joiners`() = runTest {
        val map = InFlightMap<String>()
        val gate = CompletableDeferred<Unit>()
        // 模拟「首个调用者 = 用户即将退出的页面」：独立 Job 作用域，稍后整体取消
        val ownerScope = CoroutineScope(coroutineContext + Job())
        ownerScope.launch { map.run("k") { gate.await(); "v" } }
        advanceUntilIdle()

        // 第二个调用者（今日页+简报同时取同一批数据的设计场景）join 在飞请求
        val joiner = async {
            map.run("k") { throw IllegalStateException("joiner 不应重新执行 block") }
        }
        advanceUntilIdle()

        ownerScope.cancel()   // 拥有者退出页面：修复前会级联取消共享请求，joiner 一并被击中
        gate.complete(Unit)
        advanceUntilIdle()

        assertThat(joiner.await()).isEqualTo("v")
    }

    @Test
    fun `failure propagates to all awaiters and next call re-executes`() = runTest {
        val map = InFlightMap<String>()
        var attempts = 0
        // gate 保证失败发生在两个调用者都登记之后（否则共享请求在 Default 线程秒失败并
        // 注销，第二个调用者会另起一次——那不是合并语义的路径）
        val gate = CompletableDeferred<Unit>()

        val awaiters = listOf(
            async { runCatching { map.run("k") { attempts++; gate.await(); throw IOException("x") } } },
            async { runCatching { map.run("k") { attempts++; throw IllegalStateException("不应执行") } } }
        )
        advanceUntilIdle()   // 两个调用者都已 join 同一在飞请求
        gate.complete(Unit)  // 失败发生：共享传播给两个等待者
        advanceUntilIdle()

        assertThat(awaiters.map { it.await().isFailure }).containsExactly(true, true)
        assertThat(attempts).isEqualTo(1)   // 同 key 并发只执行一次（失败同样共享）

        // 完成后注销：下一次调用重新执行（新的一次尝试）
        val again = runCatching { map.run("k") { attempts++; throw IOException("x") } }
        assertThat(again.isFailure).isTrue()
        assertThat(attempts).isEqualTo(2)
    }

    @Test
    fun `different keys execute independently`() = runTest {
        val map = InFlightMap<String>()
        var executions = 0

        val a = async { map.run("a") { executions++; "A" } }
        val b = async { map.run("b") { executions++; "B" } }
        advanceUntilIdle()

        assertThat(a.await()).isEqualTo("A")
        assertThat(b.await()).isEqualTo("B")
        assertThat(executions).isEqualTo(2)
    }
}
