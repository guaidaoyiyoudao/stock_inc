package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.stock.dividend.data.local.dao.FundamentalsCacheDao
import com.stock.dividend.data.local.entity.FundamentalsCacheEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
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
}
