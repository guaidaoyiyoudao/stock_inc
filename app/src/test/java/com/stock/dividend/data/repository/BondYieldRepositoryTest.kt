package com.stock.dividend.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.remote.BondYieldApi
import com.stock.dividend.data.remote.dto.BondYieldResponse
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * [BondYieldRepository] 集成测试：mock API 返回真实结构的 [BondYieldResponse]，
 * 验证「取最新条目 → 10Y 字段解析 → 缓存 → 降级」全链路。
 *
 * 注：10Y 字段值已是「%」单位，Repository 不做换算。
 */
class BondYieldRepositoryTest {

    private val api: BondYieldApi = mockk()
    private val prefs: SharedPreferences = mockk(relaxed = true)
    private val editor: SharedPreferences.Editor = mockk(relaxed = true)
    private val context: Context = mockk {
        every { getSharedPreferences(any(), any()) } returns prefs
    }
    private val repo = BondYieldRepository(context, api, mockk(relaxed = true))

    /** 真实结构响应：data 倒序，首条 10Y=1.7337。 */
    private fun realResponse(): BondYieldResponse = BondYieldResponse(
        BondYieldResponse.BondYieldResult(
            data = listOf(
                BondYieldResponse.BondYieldItem(solarDate = "2026-07-27 00:00:00", yield10Y = 1.7337),
                BondYieldResponse.BondYieldItem(solarDate = "2026-07-23 00:00:00", yield10Y = 1.7325)
            )
        )
    )

    private fun primePrefs(yieldValue: Double? = null, updatedAt: Long = 0L) {
        every { prefs.getString(eq("bond_yield_10y"), any()) } returns yieldValue?.toString()
        every { prefs.getLong(eq("bond_yield_10y_updated_at"), any()) } returns updatedAt
        every { prefs.edit() } returns editor
        every { editor.putString(any(), any()) } returns editor
        every { editor.putLong(any(), any()) } returns editor
    }

    @Test
    fun `takes latest entry and returns yield as-is`() = runTest {
        primePrefs()
        coEvery { api.getTreasuryYield(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns realResponse()
        val yield = repo.fetch10YBondYield(forceRefresh = true)
        assertThat(yield).isWithin(1e-9).of(1.7337)
    }

    @Test
    fun `falls back to default when remote throws`() = runTest {
        primePrefs()
        coEvery { api.getTreasuryYield(any(), any(), any(), any(), any(), any(), any(), any(), any()) } throws
            java.net.SocketTimeoutException("down")
        val yield = repo.fetch10YBondYield(forceRefresh = true)
        assertThat(yield).isWithin(1e-9).of(BondYieldRepository.DEFAULT_YIELD)
    }

    @Test
    fun `falls back to default when result is null`() = runTest {
        primePrefs()
        coEvery { api.getTreasuryYield(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
            BondYieldResponse(null)
        val yield = repo.fetch10YBondYield(forceRefresh = true)
        assertThat(yield).isWithin(1e-9).of(BondYieldRepository.DEFAULT_YIELD)
    }

    @Test
    fun `falls back to default when data is empty`() = runTest {
        primePrefs()
        coEvery { api.getTreasuryYield(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
            BondYieldResponse(BondYieldResponse.BondYieldResult(emptyList()))
        val yield = repo.fetch10YBondYield(forceRefresh = true)
        assertThat(yield).isWithin(1e-9).of(BondYieldRepository.DEFAULT_YIELD)
    }

    @Test
    fun `falls back to default when latest 10Y is null`() = runTest {
        primePrefs()
        coEvery { api.getTreasuryYield(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
            BondYieldResponse(BondYieldResponse.BondYieldResult(listOf(BondYieldResponse.BondYieldItem("2026-07-27", null))))
        val yield = repo.fetch10YBondYield(forceRefresh = true)
        assertThat(yield).isWithin(1e-9).of(BondYieldRepository.DEFAULT_YIELD)
    }

    @Test
    fun `falls back to last cached value when remote fails`() = runTest {
        primePrefs(yieldValue = 2.8, updatedAt = System.currentTimeMillis())
        coEvery { api.getTreasuryYield(any(), any(), any(), any(), any(), any(), any(), any(), any()) } throws
            java.net.SocketTimeoutException("down")
        val yield = repo.fetch10YBondYield(forceRefresh = true)
        assertThat(yield).isWithin(1e-9).of(2.8)
    }

    @Test
    fun `returns cached value within ttl without calling api`() = runTest {
        primePrefs(yieldValue = 2.7, updatedAt = System.currentTimeMillis())
        // 不 stub api；若被调用 mockk 会抛 MissingExit
        val yield = repo.fetch10YBondYield(forceRefresh = false)
        assertThat(yield).isWithin(1e-9).of(2.7)
    }

    @Test
    fun `remote zero or negative yield is ignored and falls back`() = runTest {
        primePrefs()
        coEvery { api.getTreasuryYield(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
            BondYieldResponse(BondYieldResponse.BondYieldResult(listOf(BondYieldResponse.BondYieldItem("2026-07-27", 0.0))))
        val yield = repo.fetch10YBondYield(forceRefresh = true)
        assertThat(yield).isWithin(1e-9).of(BondYieldRepository.DEFAULT_YIELD)
    }

    // ---------- 2026-08-20 审计 M3/L9 修复：失败不锁死缓存 + 向后扫备选行 ----------

    @Test
    fun `failed fetch does not poison memory cache - next call retries remote`() = runTest {
        // 断网冷启动时首次失败返回默认值，但恢复后第二次调用必须重试远端拿到真值
        // （修复前：DEFAULT_YIELD 被写入 memoryCache，进程存活期永远 2.5）
        primePrefs()
        coEvery { api.getTreasuryYield(any(), any(), any(), any(), any(), any(), any(), any(), any()) } throws
            java.net.SocketTimeoutException("down") andThen realResponse()

        val first = repo.fetch10YBondYield(forceRefresh = true)
        assertThat(first).isWithin(1e-9).of(BondYieldRepository.DEFAULT_YIELD)

        val second = repo.fetch10YBondYield(forceRefresh = false)   // 非强制——修复前这里会命中被污染的 memoryCache
        assertThat(second).isWithin(1e-9).of(1.7337)
    }

    @Test
    fun `skips rows with null 10Y and scans next available row`() = runTest {
        // 首行 10Y 为 null（当日尚未更新）时向后扫备选行，而非直接落默认值
        primePrefs()
        coEvery { api.getTreasuryYield(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
            BondYieldResponse(BondYieldResponse.BondYieldResult(listOf(
                BondYieldResponse.BondYieldItem(solarDate = "2026-07-27 00:00:00", yield10Y = null),
                BondYieldResponse.BondYieldItem(solarDate = "2026-07-23 00:00:00", yield10Y = 1.7325)
            )))
        val yield = repo.fetch10YBondYield(forceRefresh = true)
        assertThat(yield).isWithin(1e-9).of(1.7325)
    }
}
