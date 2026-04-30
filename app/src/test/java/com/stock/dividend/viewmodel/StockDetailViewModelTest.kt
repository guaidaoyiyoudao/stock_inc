package com.stock.dividend.viewmodel

import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.local.dao.DividendDao
import com.stock.dividend.data.local.entity.DividendEntity
import com.stock.dividend.data.local.entity.StockEntity
import com.stock.dividend.data.repository.StockRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StockDetailViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val stockRepository: StockRepository = mockk()
    private val dividendDao: DividendDao = mockk()

    private val stocksFlow = MutableStateFlow<List<StockEntity>>(emptyList())
    private val stockFlow = MutableStateFlow<StockEntity?>(null)
    private val dividendsFlow = MutableStateFlow<List<DividendEntity>>(emptyList())

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        coEvery { stockRepository.observeStock(any()) } returns stockFlow
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state has isLoading true`() = runTest {
        coEvery { dividendDao.observeByStock("sz.000001") } returns dividendsFlow

        val viewModel = StockDetailViewModel(
            savedStateHandle = androidx.lifecycle.SavedStateHandle(mapOf("code" to "sz.000001")),
            stockRepository = stockRepository,
            dividendRepository = mockk {
                coEvery { observeDividends("sz.000001") } returns dividendsFlow
            }
        )

        assertThat(viewModel.uiState.value.isLoading).isTrue()
    }

    @Test
    fun `stock loads from repository`() = runTest {
        val stock = StockEntity("sz.000001", "平安银行", "0")
        stockFlow.value = stock

        val viewModel = StockDetailViewModel(
            savedStateHandle = androidx.lifecycle.SavedStateHandle(mapOf("code" to "sz.000001")),
            stockRepository = stockRepository,
            dividendRepository = mockk {
                coEvery { observeDividends("sz.000001") } returns dividendsFlow
            }
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.stock?.name).isEqualTo("平安银行")
    }

    @Test
    fun `dividends update from repository flow`() = runTest {
        val dividends = listOf(
            DividendEntity(
                id = "sz.000001_2024-12-31",
                stockCode = "sz.000001",
                reportDate = "2024-12-31",
                cashPerShare = 0.246,
                dividendYield = 5.93,
                exDividendDate = "2025-07-11",
                recordDate = "2025-07-10",
                planStatus = "实施方案"
            )
        )
        dividendsFlow.value = dividends

        val viewModel = StockDetailViewModel(
            savedStateHandle = androidx.lifecycle.SavedStateHandle(mapOf("code" to "sz.000001")),
            stockRepository = stockRepository,
            dividendRepository = mockk {
                coEvery { observeDividends("sz.000001") } returns dividendsFlow
            }
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.dividends).hasSize(1)
        assertThat(viewModel.uiState.value.dividends[0].cashPerShare).isEqualTo(0.246)
        assertThat(viewModel.uiState.value.isLoading).isFalse()
    }

    @Test
    fun `stock remains null when not found in repository`() = runTest {
        stockFlow.value = null

        val viewModel = StockDetailViewModel(
            savedStateHandle = androidx.lifecycle.SavedStateHandle(mapOf("code" to "sz.999999")),
            stockRepository = stockRepository,
            dividendRepository = mockk {
                coEvery { observeDividends("sz.999999") } returns dividendsFlow
            }
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.stock).isNull()
    }

    @Test
    fun `empty dividends list sets isLoading to false`() = runTest {
        val viewModel = StockDetailViewModel(
            savedStateHandle = androidx.lifecycle.SavedStateHandle(mapOf("code" to "sz.000001")),
            stockRepository = stockRepository,
            dividendRepository = mockk {
                coEvery { observeDividends("sz.000001") } returns dividendsFlow
            }
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.dividends).isEmpty()
        assertThat(viewModel.uiState.value.isLoading).isFalse()
    }

    @Test
    fun `initial error is null`() = runTest {
        val viewModel = StockDetailViewModel(
            savedStateHandle = androidx.lifecycle.SavedStateHandle(mapOf("code" to "sz.000001")),
            stockRepository = stockRepository,
            dividendRepository = mockk {
                coEvery { observeDividends("sz.000001") } returns dividendsFlow
            }
        )

        assertThat(viewModel.uiState.value.error).isNull()
    }

    private fun createViewModel(
        code: String = "sz.000001",
        dividends: List<DividendEntity> = emptyList()
    ): StockDetailViewModel {
        dividendsFlow.value = dividends
        return StockDetailViewModel(
            savedStateHandle = androidx.lifecycle.SavedStateHandle(mapOf("code" to code)),
            stockRepository = stockRepository,
            dividendRepository = mockk {
                coEvery { observeDividends(code) } returns dividendsFlow
            }
        )
    }

    private fun makeDividends(count: Int): List<DividendEntity> {
        return (1..count).map { i ->
            DividendEntity(
                id = "sz.000001_2024-$i",
                stockCode = "sz.000001",
                reportDate = "2024-$i",
                cashPerShare = 0.1 * i,
                dividendYield = 1.0 * i,
                exDividendDate = "2025-07-$i",
                recordDate = "2025-07-${i - 1}",
                planStatus = "实施方案"
            )
        }
    }

    @Test
    fun `initial visibleCount is 5`() = runTest {
        val viewModel = createViewModel(dividends = makeDividends(12))
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.visibleCount).isEqualTo(5)
    }

    @Test
    fun `loadMoreDividends increases visibleCount by 5`() = runTest {
        val viewModel = createViewModel(dividends = makeDividends(12))
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.loadMoreDividends()

        assertThat(viewModel.uiState.value.visibleCount).isEqualTo(10)
    }

    @Test
    fun `loadMoreDividends caps at total dividends size`() = runTest {
        val viewModel = createViewModel(dividends = makeDividends(12))
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.loadMoreDividends() // 5 → 10
        viewModel.loadMoreDividends() // 10 → 12 (capped)

        assertThat(viewModel.uiState.value.visibleCount).isEqualTo(12)
    }

    @Test
    fun `refreshing dividends resets visibleCount to 5`() = runTest {
        val viewModel = createViewModel(dividends = makeDividends(12))
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.loadMoreDividends()
        assertThat(viewModel.uiState.value.visibleCount).isEqualTo(10)

        // Simulate dividend refresh by emitting different data through the flow
        // MutableStateFlow uses structural equality, so we must emit a different list
        dividendsFlow.value = makeDividends(13)
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.visibleCount).isEqualTo(5)
    }
}
