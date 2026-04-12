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
    private val dividendsFlow = MutableStateFlow<List<DividendEntity>>(emptyList())

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        coEvery { stockRepository.observeAllStocks() } returns stocksFlow
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
        coEvery { dividendDao.observeByStock("sz.000001") } returns dividendsFlow
        val stock = StockEntity("sz.000001", "平安银行", "0")

        stocksFlow.value = listOf(stock)

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
        val otherStock = StockEntity("sh.600519", "贵州茅台", "1")
        stocksFlow.value = listOf(otherStock)

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
}
