package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.local.dao.PriceCacheDao
import com.stock.dividend.data.local.dao.StockDao
import com.stock.dividend.data.local.entity.PriceCacheEntity
import com.stock.dividend.data.local.entity.StockEntity
import com.stock.dividend.data.widget.WidgetUiState
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

class WidgetDataRepositoryTest {

    private val stockDao = mockk<StockDao>()
    private val priceCacheDao = mockk<PriceCacheDao>()
    private val fireGoalRepository = mockk<FireGoalRepository>()
    private val stockRepository = mockk<StockRepository>()
    private val repo = WidgetDataRepository(stockDao, priceCacheDao, fireGoalRepository, stockRepository)

    @Test
    fun `returns EMPTY when no holdings`() = runTest {
        coEvery { stockDao.getAll() } returns emptyList()
        coEvery { fireGoalRepository.getGoalOnce() } returns null

        val state = repo.loadSnapshot()

        assertThat(state).isEqualTo(WidgetUiState.EMPTY)
    }

    @Test
    fun `aggregates market value and priced count`() = runTest {
        coEvery { stockDao.getAll() } returns listOf(
            stock("sh.600036", shares = 100, costPerShare = 30.0),
            stock("sz.000001", shares = 200, costPerShare = 10.0),
        )
        coEvery { priceCacheDao.getAll() } returns listOf(
            PriceCacheEntity("sh.600036", price = 36.0, updatedAt = 1000L),
        )
        coEvery { fireGoalRepository.getGoalOnce() } returns null

        val state = repo.loadSnapshot()

        assertThat(state.holdingCount).isEqualTo(2)
        assertThat(state.pricedCount).isEqualTo(1)
        assertThat(state.totalMarketValue).isEqualTo(3600.0)
    }

    @Test
    fun `computes cost basis pnl and percent`() = runTest {
        coEvery { stockDao.getAll() } returns listOf(
            stock("sh.600036", shares = 100, costPerShare = 30.0),
        )
        coEvery { priceCacheDao.getAll() } returns listOf(
            PriceCacheEntity("sh.600036", price = 36.0, updatedAt = 1000L),
        )
        coEvery { fireGoalRepository.getGoalOnce() } returns null

        val state = repo.loadSnapshot()

        assertThat(state.costBasisPnl).isEqualTo(600.0)
        assertThat(state.costBasisPnlPercent).isWithin(0.0001).of(0.2)
    }

    @Test
    fun `fire progress zero when goal not set`() = runTest {
        coEvery { stockDao.getAll() } returns listOf(stock("sh.600036", shares = 100, costPerShare = 30.0))
        coEvery { priceCacheDao.getAll() } returns listOf(PriceCacheEntity("sh.600036", price = 36.0, updatedAt = 1000L))
        coEvery { fireGoalRepository.getGoalOnce() } returns null

        val state = repo.loadSnapshot()

        assertThat(state.fireGoalAmount).isEqualTo(0.0)
    }

    @Test
    fun `lastPriceUpdatedAt is max of cache`() = runTest {
        coEvery { stockDao.getAll() } returns listOf(stock("sh.600036", shares = 100, costPerShare = 30.0))
        coEvery { priceCacheDao.getAll() } returns listOf(
            PriceCacheEntity("sh.600036", price = 36.0, updatedAt = 1000L),
            PriceCacheEntity("sz.000001", price = 10.0, updatedAt = 5000L),
        )
        coEvery { fireGoalRepository.getGoalOnce() } returns null

        val state = repo.loadSnapshot()

        assertThat(state.lastPriceUpdatedAt).isEqualTo(5000L)
    }

    @Test
    fun `dao exception returns EMPTY without throwing`() = runTest {
        coEvery { stockDao.getAll() } throws RuntimeException("db locked")
        coEvery { fireGoalRepository.getGoalOnce() } returns null

        val state = repo.loadSnapshot()

        assertThat(state).isEqualTo(WidgetUiState.EMPTY)
    }

    @Test
    fun `refreshPrices delegates to stockRepository`() = runTest {
        val holdings = listOf(stock("sh.600036", shares = 100, costPerShare = 30.0))
        coEvery { stockDao.getAll() } returns holdings
        coEvery { stockRepository.fetchQuotes(holdings) } returns mapOf("sh.600036" to 37.0)

        val result = repo.refreshPrices()

        assertThat(result.isSuccess).isTrue()
    }

    @Test
    fun `refreshPrices returns failure when fetchQuotes throws`() = runTest {
        coEvery { stockDao.getAll() } returns emptyList()
        coEvery { stockRepository.fetchQuotes(any()) } throws RuntimeException("network")

        val result = repo.refreshPrices()

        assertThat(result.isFailure).isTrue()
    }

    private fun stock(code: String, shares: Int, costPerShare: Double) = StockEntity(
        code = code,
        name = "测试",
        marketCode = if (code.startsWith("sh")) "1" else "0",
        shares = shares,
        costPerShare = costPerShare,
    )
}
