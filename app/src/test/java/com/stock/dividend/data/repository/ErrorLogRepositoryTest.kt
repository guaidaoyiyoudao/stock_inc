package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.local.dao.ErrorLogDao
import com.stock.dividend.data.local.entity.ErrorLogEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

/** [ErrorLogRepository] 单元测试：记录 / 防抖 / 修剪 / 自身失败吞异常（红线 #2）。 */
class ErrorLogRepositoryTest {

    private val dao = mockk<ErrorLogDao>(relaxed = true)
    private lateinit var repository: ErrorLogRepository

    /** 可控时钟（ms）。 */
    private var now = 1_000_000L

    @Before
    fun setup() {
        repository = ErrorLogRepository(dao)
        repository.nowProvider = { now }
    }

    @Test
    fun `record inserts entity with detail and trims table`() = runTest {
        coEvery { dao.latest() } returns null

        repository.record("行情", "行情获取失败（2 只标的）", RuntimeException("timeout"))

        val entitySlot = slot<ErrorLogEntity>()
        coVerify { dao.insert(capture(entitySlot)) }
        coVerify { dao.trimToRecent(ErrorLogRepository.MAX_LOGS) }
        assertThat(entitySlot.captured.source).isEqualTo("行情")
        assertThat(entitySlot.captured.message).isEqualTo("行情获取失败（2 只标的）")
        assertThat(entitySlot.captured.category).isEqualTo(ErrorLogCategory.NETWORK.name)
        assertThat(entitySlot.captured.timestamp).isEqualTo(now)
        assertThat(entitySlot.captured.detail).contains("RuntimeException")
        assertThat(entitySlot.captured.detail).contains("timeout")
    }

    @Test
    fun `record without throwable stores null detail`() = runTest {
        coEvery { dao.latest() } returns null

        repository.record("市场数据", "板块列表获取失败")

        val entitySlot = slot<ErrorLogEntity>()
        coVerify { dao.insert(capture(entitySlot)) }
        assertThat(entitySlot.captured.detail).isNull()
    }

    @Test
    fun `record dedupes same source and message within window`() = runTest {
        coEvery { dao.latest() } returns ErrorLogEntity(
            id = 9, timestamp = now - 10_000, category = "NETWORK",
            source = "行情", message = "行情获取失败（2 只标的）",
        )

        repository.record("行情", "行情获取失败（2 只标的）")

        coVerify(exactly = 0) { dao.insert(any()) }
    }

    @Test
    fun `record logs again after dedup window passes`() = runTest {
        coEvery { dao.latest() } returns ErrorLogEntity(
            id = 9, timestamp = now - ErrorLogRepository.DEDUP_WINDOW_MS - 1,
            category = "NETWORK", source = "行情", message = "行情获取失败（2 只标的）",
        )

        repository.record("行情", "行情获取失败（2 只标的）")

        coVerify(exactly = 1) { dao.insert(any()) }
    }

    @Test
    fun `record does not dedupe different source or message`() = runTest {
        coEvery { dao.latest() } returns ErrorLogEntity(
            id = 9, timestamp = now, category = "NETWORK",
            source = "行情", message = "行情获取失败（2 只标的）",
        )

        repository.record("行情", "行情获取失败（3 只标的）")
        repository.record("分红", "分红数据刷新失败（sh.600036）")

        coVerify(exactly = 2) { dao.insert(any()) }
    }

    @Test
    fun `record caps detail length`() = runTest {
        coEvery { dao.latest() } returns null

        repository.record("行情", "msg", RuntimeException("x".repeat(5_000)))

        val entitySlot = slot<ErrorLogEntity>()
        coVerify { dao.insert(capture(entitySlot)) }
        assertThat(entitySlot.captured.detail?.length).isEqualTo(2_000)
    }

    @Test
    fun `record swallows dao failure`() = runTest {
        coEvery { dao.latest() } throws IllegalStateException("db down")

        // 不抛异常即通过——记录日志的代码不能成为新的故障源
        repository.record("行情", "行情获取失败（2 只标的）")
    }

    @Test
    fun `clearAll swallows dao failure`() = runTest {
        coEvery { dao.clearAll() } throws IllegalStateException("db down")

        repository.clearAll()
    }

    @Test
    fun `count returns zero on dao failure`() = runTest {
        coEvery { dao.count() } throws IllegalStateException("db down")

        assertThat(repository.count()).isEqualTo(0L)
    }
}
