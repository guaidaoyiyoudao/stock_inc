package com.stock.dividend.viewmodel

import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.local.dao.DividendDao
import com.stock.dividend.data.local.dao.TransactionDao
import com.stock.dividend.data.local.entity.DividendEntity
import com.stock.dividend.data.local.entity.EXPENSE_PERIOD_MONTHLY
import com.stock.dividend.data.local.entity.EXPENSE_PERIOD_YEARLY
import com.stock.dividend.data.local.entity.LivingExpenseItemEntity
import com.stock.dividend.data.local.entity.StockEntity
import com.stock.dividend.data.local.entity.TransactionEntity
import com.stock.dividend.data.repository.LivingExpenseRepository
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
    private val livingExpenseRepository: LivingExpenseRepository = mockk()
    private val transactionDao: TransactionDao = mockk()

    private val stocksFlow = MutableStateFlow<List<StockEntity>>(emptyList())
    private val totalDividendFlow = MutableStateFlow(0.0)
    private val livingExpensesFlow = MutableStateFlow<List<LivingExpenseItemEntity>>(emptyList())

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        coEvery { stockRepository.observeAllStocks() } returns stocksFlow
        coEvery { dividendDao.observeByStock(any()) } returns MutableStateFlow(emptyList())
        coEvery { dividendDao.observeTotalCashPerShare() } returns totalDividendFlow
        coEvery { livingExpenseRepository.observeExpenses() } returns livingExpensesFlow
        coEvery { stockRepository.fetchQuotes(any()) } returns emptyMap()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state has empty stocks and zero total`() = runTest {
        val viewModel = HomeViewModel(stockRepository, dividendDao, livingExpenseRepository, transactionDao)
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.stocks).isEmpty()
        assertThat(viewModel.uiState.value.forecastTotal).isEqualTo(0.0)
    }

    @Test
    fun `stocks update when repository emits new data`() = runTest {
        val viewModel = HomeViewModel(stockRepository, dividendDao, livingExpenseRepository, transactionDao)
        val stock = StockEntity("sz.000001", "平安银行", "0")

        stocksFlow.value = listOf(stock)
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.stocks).hasSize(1)
        assertThat(viewModel.uiState.value.stocks[0].name).isEqualTo("平安银行")
    }

    @Test
    fun `forecastTotal starts at zero`() = runTest {
        val viewModel = HomeViewModel(stockRepository, dividendDao, livingExpenseRepository, transactionDao)
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.forecastTotal).isEqualTo(0.0)
    }

    @Test
    fun `deleteStock calls repository removeStock`() = runTest {
        coEvery { stockRepository.removeStock("sz.000001") } returns Unit
        coEvery { transactionDao.getByStock("sz.000001") } returns emptyList()
        val viewModel = HomeViewModel(stockRepository, dividendDao, livingExpenseRepository, transactionDao)

        val stock = StockEntity("sz.000001", "平安银行", "0")
        viewModel.deleteStock(stock)
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.deletedStock).isEqualTo(stock)
    }

    @Test
    fun `undoDelete re-adds the stock`() = runTest {
        coEvery { stockRepository.removeStock("sz.000001") } returns Unit
        coEvery { stockRepository.restoreStock(any()) } returns Unit
        coEvery { transactionDao.getByStock("sz.000001") } returns emptyList()
        coEvery { transactionDao.insert(any<TransactionEntity>()) } returns 1L

        val viewModel = HomeViewModel(stockRepository, dividendDao, livingExpenseRepository, transactionDao)
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
        coEvery { transactionDao.getByStock("sz.000001") } returns emptyList()
        val viewModel = HomeViewModel(stockRepository, dividendDao, livingExpenseRepository, transactionDao)

        val stock = StockEntity("sz.000001", "平安银行", "0")
        viewModel.deleteStock(stock)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.clearDeleted()

        assertThat(viewModel.uiState.value.deletedStock).isNull()
    }

    @Test
    fun `undoDelete does nothing when no deleted stock`() = runTest {
        val viewModel = HomeViewModel(stockRepository, dividendDao, livingExpenseRepository, transactionDao)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.undoDelete()
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.deletedStock).isNull()
    }

    @Test
    fun `initial isLoading is false`() = runTest {
        val viewModel = HomeViewModel(stockRepository, dividendDao, livingExpenseRepository, transactionDao)
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.isLoading).isFalse()
    }

    @Test
    fun `initial error is null`() = runTest {
        val viewModel = HomeViewModel(stockRepository, dividendDao, livingExpenseRepository, transactionDao)
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.error).isNull()
    }

    @Test
    fun `fire card target uses annualized living expenses`() = runTest {
        val stock = StockEntity("sz.000001", "平安银行", "0", shares = 100, yieldPeriod = "1")
        stocksFlow.value = listOf(stock)
        coEvery { dividendDao.observeByStock("sz.000001") } returns MutableStateFlow(
            listOf(
                DividendEntity(
                    id = "sz.000001_2025",
                    stockCode = "sz.000001",
                    reportDate = "2025-12-31",
                    cashPerShare = 10.0
                )
            )
        )
        livingExpensesFlow.value = listOf(
            LivingExpenseItemEntity(1, "房租", 300.0, EXPENSE_PERIOD_MONTHLY, 0),
            LivingExpenseItemEntity(2, "保险", 400.0, EXPENSE_PERIOD_YEARLY, 1)
        )

        val viewModel = HomeViewModel(stockRepository, dividendDao, livingExpenseRepository, transactionDao)
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.livingExpenseTargetAmount).isEqualTo(4000.0)
        assertThat(viewModel.uiState.value.fireProgress).isWithin(0.0001f).of(25.0f)
    }
}
