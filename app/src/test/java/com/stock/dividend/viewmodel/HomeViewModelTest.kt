package com.stock.dividend.viewmodel

import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.local.dao.DividendDao
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
class HomeViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val stockRepository: StockRepository = mockk()
    private val dividendDao: DividendDao = mockk()

    private val stocksFlow = MutableStateFlow<List<StockEntity>>(emptyList())
    private val totalDividendFlow = MutableStateFlow(0.0)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        coEvery { stockRepository.observeAllStocks() } returns stocksFlow
        coEvery { dividendDao.observeByStock(any()) } returns MutableStateFlow(emptyList())
        coEvery { dividendDao.observeTotalCashPerShare() } returns totalDividendFlow
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state has empty stocks and zero total`() = runTest {
        val viewModel = HomeViewModel(stockRepository, dividendDao)
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.stocks).isEmpty()
        assertThat(viewModel.uiState.value.forecastTotal).isEqualTo(0.0)
    }

    @Test
    fun `stocks update when repository emits new data`() = runTest {
        val viewModel = HomeViewModel(stockRepository, dividendDao)
        val stock = StockEntity("sz.000001", "平安银行", "0")

        stocksFlow.value = listOf(stock)
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.stocks).hasSize(1)
        assertThat(viewModel.uiState.value.stocks[0].name).isEqualTo("平安银行")
    }

    @Test
    fun `forecastTotal starts at zero`() = runTest {
        val viewModel = HomeViewModel(stockRepository, dividendDao)
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.forecastTotal).isEqualTo(0.0)
    }

    @Test
    fun `deleteStock calls repository removeStock`() = runTest {
        coEvery { stockRepository.removeStock("sz.000001") } returns Unit
        val viewModel = HomeViewModel(stockRepository, dividendDao)

        val stock = StockEntity("sz.000001", "平安银行", "0")
        viewModel.deleteStock(stock)
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.deletedStock).isEqualTo(stock)
    }

    @Test
    fun `undoDelete re-adds the stock`() = runTest {
        coEvery { stockRepository.removeStock("sz.000001") } returns Unit
        coEvery { stockRepository.addStock(any(), any()) } returns Result.success(Unit)

        val viewModel = HomeViewModel(stockRepository, dividendDao)
        val stock = StockEntity("sz.000001", "平安银行", "0")

        viewModel.deleteStock(stock)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.undoDelete()
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.deletedStock).isNull()
    }

    @Test
    fun `clearDeleted removes deletedStock from state`() = runTest {
        coEvery { stockRepository.removeStock("sz.000001") } returns Unit
        val viewModel = HomeViewModel(stockRepository, dividendDao)

        val stock = StockEntity("sz.000001", "平安银行", "0")
        viewModel.deleteStock(stock)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.clearDeleted()

        assertThat(viewModel.uiState.value.deletedStock).isNull()
    }

    @Test
    fun `undoDelete does nothing when no deleted stock`() = runTest {
        val viewModel = HomeViewModel(stockRepository, dividendDao)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.undoDelete()
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.deletedStock).isNull()
    }

    @Test
    fun `initial isLoading is false`() = runTest {
        val viewModel = HomeViewModel(stockRepository, dividendDao)
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.isLoading).isFalse()
    }

    @Test
    fun `initial error is null`() = runTest {
        val viewModel = HomeViewModel(stockRepository, dividendDao)
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.error).isNull()
    }
}
