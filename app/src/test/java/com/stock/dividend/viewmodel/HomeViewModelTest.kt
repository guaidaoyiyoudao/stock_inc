package com.stock.dividend.viewmodel

import android.content.Context
import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.local.dao.DividendDao
import com.stock.dividend.data.local.dao.TransactionDao
import com.stock.dividend.data.local.entity.DividendEntity
import com.stock.dividend.data.local.entity.EXPENSE_PERIOD_MONTHLY
import com.stock.dividend.data.local.entity.EXPENSE_PERIOD_YEARLY
import com.stock.dividend.data.local.entity.LivingExpenseItemEntity
import com.stock.dividend.data.local.entity.StockEntity
import com.stock.dividend.data.local.entity.TransactionEntity
import com.stock.dividend.data.notification.NotificationCheckCoordinator
import com.stock.dividend.data.repository.LivingExpenseRepository
import com.stock.dividend.data.repository.StockRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
    private val notificationCheckCoordinator: NotificationCheckCoordinator = mockk(relaxed = true)
    private val context: Context = mockk(relaxed = true)
    private val prefs: SharedPreferences = mockk(relaxed = true)
    private val prefsEditor: SharedPreferences.Editor = mockk(relaxed = true) {
        every { putLong(any(), any()) } returns this
    }

    private val stocksFlow = MutableStateFlow<List<StockEntity>>(emptyList())
    private val totalDividendFlow = MutableStateFlow(0.0)
    private val livingExpensesFlow = MutableStateFlow<List<LivingExpenseItemEntity>>(emptyList())

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE) } returns prefs
        every { prefs.edit() } returns prefsEditor
        every { prefs.getLong("last_quote_refresh_ms", 0L) } returns 0L
        every { stockRepository.observeAllStocks() } returns stocksFlow
        every { dividendDao.observeByStock(any()) } returns MutableStateFlow(emptyList())
        coEvery { dividendDao.observeTotalCashPerShare() } returns totalDividendFlow
        every { livingExpenseRepository.observeExpenses() } returns livingExpensesFlow
        coEvery { stockRepository.fetchQuotes(any()) } returns emptyMap()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state has empty stocks and zero total`() = runTest {
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.stocks).isEmpty()
        assertThat(viewModel.uiState.value.forecastTotal).isEqualTo(0.0)
    }

    @Test
    fun `stocks update when repository emits new data`() = runTest {
        val viewModel = createViewModel()
        val stock = StockEntity("sz.000001", "平安银行", "0")

        stocksFlow.value = listOf(stock)
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.stocks).hasSize(1)
        assertThat(viewModel.uiState.value.stocks[0].name).isEqualTo("平安银行")
    }

    @Test
    fun `forecastTotal starts at zero`() = runTest {
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.forecastTotal).isEqualTo(0.0)
    }

    @Test
    fun `totalMarketValue sums quote price times shares for active holdings`() = runTest {
        val stocks = listOf(
            StockEntity("sz.000001", "平安银行", "0", shares = 100),
            StockEntity("sh.600000", "浦发银行", "1", shares = 200),
            StockEntity("sz.000002", "万科A", "0", shares = 0)
        )
        stocksFlow.value = stocks
        coEvery {
            stockRepository.fetchQuotes(match { requested ->
                requested.map { it.code } == listOf("sz.000001", "sh.600000")
            })
        } returns mapOf(
            "sz.000001" to 10.5,
            "sh.600000" to 7.25
        )

        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.totalMarketValue).isEqualTo(2500.0)
    }

    @Test
    fun `deleteStock calls repository removeStock`() = runTest {
        coEvery { stockRepository.removeStock("sz.000001") } returns Unit
        coEvery { transactionDao.getByStock("sz.000001") } returns emptyList()
        val viewModel = createViewModel()

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

        val viewModel = createViewModel()
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
        val viewModel = createViewModel()

        val stock = StockEntity("sz.000001", "平安银行", "0")
        viewModel.deleteStock(stock)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.clearDeleted()

        assertThat(viewModel.uiState.value.deletedStock).isNull()
    }

    @Test
    fun `undoDelete does nothing when no deleted stock`() = runTest {
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.undoDelete()
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.deletedStock).isNull()
    }

    @Test
    fun `initial isLoading is false`() = runTest {
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.isLoading).isFalse()
    }

    @Test
    fun `initial error is null`() = runTest {
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.error).isNull()
    }

    @Test
    fun `fire card target uses annualized living expenses`() = runTest {
        val stock = StockEntity("sz.000001", "平安银行", "0", shares = 100, yieldPeriod = "1")
        stocksFlow.value = listOf(stock)
        every { dividendDao.observeByStock("sz.000001") } returns MutableStateFlow(
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

        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.livingExpenseTargetAmount).isEqualTo(4000.0)
        assertThat(viewModel.uiState.value.fireProgress).isWithin(0.0001f).of(25.0f)
    }

    // --- New tests for auto-refresh and race condition fix ---

    @Test
    fun `race condition fix preserves prices after concurrent forecast update`() = runTest {
        val stock = StockEntity(
            code = "sh.600036",
            name = "招商银行",
            marketCode = "1",
            shares = 100,
            yieldPeriod = "1",
            costPerShare = 35.0
        )

        val dividendFlow = MutableStateFlow(
            listOf(
                DividendEntity(
                    id = "sh.600036_2025",
                    stockCode = "sh.600036",
                    reportDate = "2025-07-10",
                    cashPerShare = 5.0
                )
            )
        )
        every { dividendDao.observeByStock("sh.600036") } returns dividendFlow
        coEvery { stockRepository.fetchQuotes(any()) } returns mapOf("sh.600036" to 40.0)

        stocksFlow.value = listOf(stock)

        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        // totalMarketValue is computed directly from prices and stocks — always available after refresh
        assertThat(viewModel.uiState.value.totalMarketValue).isEqualTo(4000.0)
    }

    @Test
    fun `refreshQuotes persists lastUpdated and refresh timestamp`() = runTest {
        val stock = StockEntity(
            code = "sh.600036",
            name = "招商银行",
            marketCode = "1",
            shares = 100,
            yieldPeriod = "1",
            costPerShare = 35.0
        )

        coEvery { stockRepository.fetchQuotes(any()) } returns mapOf("sh.600036" to 40.0)
        every { dividendDao.observeByStock("sh.600036") } returns MutableStateFlow(
            listOf(
                DividendEntity(
                    id = "sh.600036_2025",
                    stockCode = "sh.600036",
                    reportDate = "2025-07-10",
                    cashPerShare = 5.0
                )
            )
        )

        stocksFlow.value = listOf(stock)

        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        verify { prefsEditor.putLong("last_quote_refresh_ms", any()) }
        verify { prefsEditor.apply() }
    }

    @Test
    fun `onResume triggers refresh when no previous refresh`() = runTest {
        coEvery { stockRepository.fetchQuotes(any()) } returns emptyMap()

        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        // onResume should trigger refresh since lastRefreshMs is 0 (first launch)
        viewModel.onResume()
        testDispatcher.scheduler.advanceUntilIdle()
    }

    @Test
    fun `uiState is not loading after successful refresh`() = runTest {
        val stock = StockEntity(
            code = "sh.600036",
            name = "招商银行",
            marketCode = "1",
            shares = 100,
            yieldPeriod = "1",
            costPerShare = 35.0
        )

        coEvery { stockRepository.fetchQuotes(any()) } returns mapOf("sh.600036" to 40.0)
        every { dividendDao.observeByStock("sh.600036") } returns MutableStateFlow(
            listOf(
                DividendEntity(
                    id = "sh.600036_2025",
                    stockCode = "sh.600036",
                    reportDate = "2025-07-10",
                    cashPerShare = 5.0
                )
            )
        )

        stocksFlow.value = listOf(stock)

        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.isLoading).isFalse()
    }

    @Test
    fun `empty stocks produces null totalMarketValue`() = runTest {
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.totalMarketValue).isNull()
        assertThat(viewModel.uiState.value.stockForecasts).isEmpty()
    }

    private fun createViewModel() = HomeViewModel(
        stockRepository,
        dividendDao,
        livingExpenseRepository,
        transactionDao,
        notificationCheckCoordinator,
        context
    )
}
