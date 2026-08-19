package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.stock.dividend.data.local.dao.FundamentalsCacheDao
import com.stock.dividend.data.local.entity.FundamentalsCacheEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Test

class FundamentalsCacheRepositoryTest {

    private val dao: FundamentalsCacheDao = mockk()
    private val stockRepository: StockRepository = mockk()
    private val gson = Gson()

    private val fundamentals = Fundamentals(
        periods = listOf(Fundamentals.Period("2025-03-31", 12.0, 60.0, 8.0, 5.0, payoutRatio = 25.0))
    )

    private fun entity(fetchedAt: Long = System.currentTimeMillis()) = FundamentalsCacheEntity(
        stockCode = "sh.600036",
        payload = gson.toJson(fundamentals),
        fetchedAt = fetchedAt
    )

    private fun repo() = FundamentalsCacheRepository(dao, stockRepository)

    @Test
    fun `fresh cache returns without network`() = runTest {
        coEvery { dao.get("sh.600036") } returns entity()
        val result = repo().getFundamentals("sh.600036")
        assertThat(result).isNotNull()
        assertThat(result!!.periods[0].roe).isEqualTo(12.0)
        coVerify(exactly = 0) { stockRepository.fetchFundamentals(any()) }
    }

    @Test
    fun `stale cache triggers network and writes through`() = runTest {
        coEvery { dao.get("sh.600036") } returns entity(fetchedAt = System.currentTimeMillis() - 8L * 24 * 60 * 60 * 1000)
        coEvery { stockRepository.fetchFundamentals("sh.600036") } returns fundamentals
        coEvery { dao.upsert(any()) } returns Unit

        val result = repo().getFundamentals("sh.600036")
        assertThat(result).isNotNull()
        coVerify { stockRepository.fetchFundamentals("sh.600036") }
        coVerify { dao.upsert(any()) }
    }

    @Test
    fun `network failure falls back to stale cache`() = runTest {
        coEvery { dao.get("sh.600036") } returns entity(fetchedAt = System.currentTimeMillis() - 8L * 24 * 60 * 60 * 1000)
        coEvery { stockRepository.fetchFundamentals("sh.600036") } returns null

        val result = repo().getFundamentals("sh.600036")
        assertThat(result).isNotNull()
        assertThat(result!!.periods[0].roe).isEqualTo(12.0)
    }

    @Test
    fun `no cache and network failure yields null`() = runTest {
        coEvery { dao.get("sh.600036") } returns null
        coEvery { stockRepository.fetchFundamentals("sh.600036") } returns null

        val result = repo().getFundamentals("sh.600036")
        assertThat(result).isNull()
    }

    @Test
    fun `network exception is swallowed and stale cache returned`() = runTest {
        coEvery { dao.get("sh.600036") } returns entity(fetchedAt = System.currentTimeMillis() - 8L * 24 * 60 * 60 * 1000)
        coEvery { stockRepository.fetchFundamentals("sh.600036") } throws RuntimeException("boom")

        val result = repo().getFundamentals("sh.600036")
        assertThat(result).isNotNull()
    }

    @Test
    fun `forceRefresh bypasses fresh cache`() = runTest {
        coEvery { dao.get("sh.600036") } returns entity()
        coEvery { stockRepository.fetchFundamentals("sh.600036") } returns fundamentals
        coEvery { dao.upsert(any()) } returns Unit

        repo().getFundamentals("sh.600036", forceRefresh = true)
        coVerify { stockRepository.fetchFundamentals("sh.600036") }
    }

    @Test
    fun `stale refresh merges and preserves older cached periods`() = runTest {
        val cachedFundamentals = Fundamentals(
            periods = listOf(
                Fundamentals.Period("2023-12-31", 10.0, 50.0, 5.0, 4.0),
                Fundamentals.Period("2024-12-31", 11.0, 55.0, 6.0, 5.0)
            )
        )
        coEvery { dao.get("sh.600036") } returns FundamentalsCacheEntity(
            stockCode = "sh.600036",
            payload = gson.toJson(cachedFundamentals),
            fetchedAt = System.currentTimeMillis() - 8L * 24 * 60 * 60 * 1000
        )
        val remote = Fundamentals(
            periods = listOf(
                Fundamentals.Period("2024-12-31", 12.0, 60.0, 8.0, 5.0, payoutRatio = 25.0),
                Fundamentals.Period("2025-12-31", 13.0, 58.0, 9.0, 6.0)
            )
        )
        coEvery { stockRepository.fetchFundamentals("sh.600036") } returns remote
        val payloadSlot = slot<FundamentalsCacheEntity>()
        coEvery { dao.upsert(capture(payloadSlot)) } returns Unit

        val result = repo().getFundamentals("sh.600036")

        // 2023 期从缓存续接、2024 期被远端覆盖、2025 期新增
        assertThat(result!!.periods.map { it.reportDate }).containsExactly(
            "2023-12-31", "2024-12-31", "2025-12-31"
        ).inOrder()
        assertThat(result.periods[1].roe).isEqualTo(12.0)
        // 落库 payload 同样保留全部历史期次
        val persisted = gson.fromJson(payloadSlot.captured.payload, Fundamentals::class.java)
        assertThat(persisted.periods).hasSize(3)
    }
}
