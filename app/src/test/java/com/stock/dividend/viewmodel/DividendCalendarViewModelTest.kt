package com.stock.dividend.viewmodel

import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.local.dao.DividendDao
import com.stock.dividend.data.local.entity.DividendEntity
import com.stock.dividend.data.local.entity.StockEntity
import com.stock.dividend.data.repository.DividendRepository
import com.stock.dividend.data.repository.StockRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
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
class DividendCalendarViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val dividendDao: DividendDao = mockk()
    private val stockRepository: StockRepository = mockk()
    private val dividendRepository: DividendRepository = mockk()
    private val dividendsFlow = MutableStateFlow<List<DividendEntity>>(emptyList())
    private val stocksFlow = MutableStateFlow<List<StockEntity>>(emptyList())

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { dividendDao.observeAll() } returns dividendsFlow
        every { stockRepository.observeAllStocks() } returns stocksFlow
        coEvery { dividendRepository.fetchAndCacheDividends(any(), any()) } returns Result.success(Unit)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `calendar events use preferred date and estimate amount from current shares`() = runTest {
        val viewModel = DividendCalendarViewModel(dividendDao, stockRepository, dividendRepository)
        stocksFlow.value = listOf(
            StockEntity(code = "sz.000001", name = "平安银行", marketCode = "0", shares = 200),
            StockEntity(code = "sh.600000", name = "浦发银行", marketCode = "1", shares = 100)
        )
        dividendsFlow.value = listOf(
            DividendEntity(
                id = "sz.000001_2001",
                stockCode = "sz.000001",
                reportDate = "2001-04-30",
                cashPerShare = 0.30,
                exDividendDate = "2001-06-01",
                recordDate = "2001-06-02",
                planStatus = "实施"
            ),
            DividendEntity(
                id = "sh.600000_2001",
                stockCode = "sh.600000",
                reportDate = "2001-05-10",
                cashPerShare = 0.50,
                recordDate = "2001-07-15",
                planStatus = "预案"
            )
        )
        viewModel.onFilterChanged(DividendCalendarFilter.HISTORY)
        testDispatcher.scheduler.advanceUntilIdle()

        val events = viewModel.uiState.value.events

        assertThat(events).hasSize(2)
        assertThat(events[0].eventDate).isEqualTo("2001-06-01")
        assertThat(events[0].eventType).isEqualTo("除权除息")
        assertThat(events[0].stockName).isEqualTo("平安银行")
        assertThat(events[0].estimatedAmount).isEqualTo(60.0)
        assertThat(events[1].eventDate).isEqualTo("2001-07-15")
        assertThat(events[1].eventType).isEqualTo("股权登记")
        assertThat(events[1].estimatedAmount).isEqualTo(50.0)
        assertThat(viewModel.uiState.value.monthGroups.map { it.month }).containsExactly("2001-06", "2001-07")
    }

    @Test
    fun `future filter only keeps future ex dividend date events`() = runTest {
        val viewModel = DividendCalendarViewModel(dividendDao, stockRepository, dividendRepository)
        stocksFlow.value = listOf(
            StockEntity(code = "sz.000001", name = "平安银行", marketCode = "0", shares = 100)
        )
        dividendsFlow.value = listOf(
            DividendEntity(
                id = "past",
                stockCode = "sz.000001",
                reportDate = "2000-01-01",
                cashPerShare = 0.10
            ),
            DividendEntity(
                id = "future",
                stockCode = "sz.000001",
                reportDate = "2098-12-31",
                cashPerShare = 0.20,
                exDividendDate = "2099-01-01"
            ),
            DividendEntity(
                id = "report-date-only",
                stockCode = "sz.000001",
                reportDate = "2099-02-01",
                cashPerShare = 0.30
            )
        )
        viewModel.onFilterChanged(DividendCalendarFilter.FUTURE)
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.events.map { it.id }).containsExactly("future")
        assertThat(viewModel.uiState.value.events.first().eventDate).isEqualTo("2099-01-01")
        assertThat(viewModel.uiState.value.events.first().eventType).isEqualTo("除权除息")
    }

    @Test
    fun `calendar grid contains six weeks and marks event days`() = runTest {
        val viewModel = DividendCalendarViewModel(dividendDao, stockRepository, dividendRepository)
        stocksFlow.value = listOf(
            StockEntity(code = "sz.000001", name = "平安银行", marketCode = "0", shares = 100)
        )
        dividendsFlow.value = listOf(
            DividendEntity(
                id = "event-1",
                stockCode = "sz.000001",
                reportDate = "2001-04-30",
                cashPerShare = 0.20,
                exDividendDate = "2001-06-15"
            )
        )
        viewModel.onFilterChanged(DividendCalendarFilter.HISTORY)
        viewModel.onVisibleMonthChanged("2001-06")
        testDispatcher.scheduler.advanceUntilIdle()

        val days = viewModel.uiState.value.calendarDays

        assertThat(days).hasSize(42)
        assertThat(days.first().date).isEqualTo("2001-05-28")
        assertThat(days.last().date).isEqualTo("2001-07-08")
        assertThat(days.first { it.date == "2001-06-15" }.hasEvents).isTrue()
        assertThat(days.first { it.date == "2001-06-15" }.isCurrentMonth).isTrue()
    }

    @Test
    fun `select date exposes only events on that day`() = runTest {
        val viewModel = DividendCalendarViewModel(dividendDao, stockRepository, dividendRepository)
        stocksFlow.value = listOf(
            StockEntity(code = "sz.000001", name = "平安银行", marketCode = "0", shares = 100),
            StockEntity(code = "sh.600000", name = "浦发银行", marketCode = "1", shares = 100)
        )
        dividendsFlow.value = listOf(
            DividendEntity(
                id = "selected",
                stockCode = "sz.000001",
                reportDate = "2001-04-30",
                cashPerShare = 0.20,
                exDividendDate = "2001-06-15"
            ),
            DividendEntity(
                id = "other-day",
                stockCode = "sh.600000",
                reportDate = "2001-04-30",
                cashPerShare = 0.30,
                exDividendDate = "2001-06-16"
            )
        )
        viewModel.onFilterChanged(DividendCalendarFilter.HISTORY)
        viewModel.onVisibleMonthChanged("2001-06")
        viewModel.onDateSelected("2001-06-15")
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.selectedDate).isEqualTo("2001-06-15")
        assertThat(viewModel.uiState.value.selectedDateEvents.map { it.id }).containsExactly("selected")
    }

    @Test
    fun `calendar normalizes stored space time dates for month and selected day`() = runTest {
        val viewModel = DividendCalendarViewModel(dividendDao, stockRepository, dividendRepository)
        stocksFlow.value = listOf(
            StockEntity(code = "sh.600398", name = "海澜之家", marketCode = "1", shares = 100)
        )
        dividendsFlow.value = listOf(
            DividendEntity(
                id = "hilan-2026",
                stockCode = "sh.600398",
                reportDate = "2025-12-31 00:00:00",
                cashPerShare = 0.41,
                exDividendDate = "2026-05-11 00:00:00",
                recordDate = "2026-05-08 00:00:00",
                planStatus = "实施分配"
            )
        )
        viewModel.onFilterChanged(DividendCalendarFilter.MONTH)
        viewModel.onVisibleMonthChanged("2026-05")
        viewModel.onDateSelected("2026-05-11")
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.events.map { it.id }).containsExactly("hilan-2026")
        assertThat(viewModel.uiState.value.calendarDays.first { it.date == "2026-05-11" }.hasEvents).isTrue()
        assertThat(viewModel.uiState.value.selectedDateEvents.map { it.id }).containsExactly("hilan-2026")
    }

    @Test
    fun `refresh fetches dividends for all stocks`() = runTest {
        val viewModel = DividendCalendarViewModel(dividendDao, stockRepository, dividendRepository)
        stocksFlow.value = listOf(
            StockEntity(code = "sh.600398", name = "海澜之家", marketCode = "1", shares = 100),
            StockEntity(code = "sz.000001", name = "平安银行", marketCode = "0", shares = 100)
        )
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.refreshDividends()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { dividendRepository.fetchAndCacheDividends("sh.600398", "600398") }
        coVerify { dividendRepository.fetchAndCacheDividends("sz.000001", "000001") }
        assertThat(viewModel.uiState.value.isRefreshing).isFalse()
    }

    @Test
    fun `go to today restores current month and selected date`() = runTest {
        val viewModel = DividendCalendarViewModel(dividendDao, stockRepository, dividendRepository)
        viewModel.onVisibleMonthChanged("2001-06")
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.goToToday()
        testDispatcher.scheduler.advanceUntilIdle()

        val today = java.time.LocalDate.now()
        assertThat(viewModel.uiState.value.visibleMonth).isEqualTo(java.time.YearMonth.from(today).toString())
        assertThat(viewModel.uiState.value.selectedDate).isEqualTo(today.toString())
    }

    @Test
    fun `history filter jumps to latest historical event date`() = runTest {
        val viewModel = DividendCalendarViewModel(dividendDao, stockRepository, dividendRepository)
        stocksFlow.value = listOf(
            StockEntity(code = "sh.600398", name = "海澜之家", marketCode = "1", shares = 100)
        )
        dividendsFlow.value = listOf(
            DividendEntity(
                id = "older",
                stockCode = "sh.600398",
                reportDate = "2024-12-31",
                cashPerShare = 0.18,
                exDividendDate = "2025-07-10"
            ),
            DividendEntity(
                id = "latest",
                stockCode = "sh.600398",
                reportDate = "2025-12-31",
                cashPerShare = 0.41,
                exDividendDate = "2026-05-11"
            )
        )
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onFilterChanged(DividendCalendarFilter.HISTORY)
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.visibleMonth).isEqualTo("2026-05")
        assertThat(viewModel.uiState.value.selectedDate).isEqualTo("2026-05-11")
        assertThat(viewModel.uiState.value.selectedDateEvents.map { it.id }).containsExactly("latest")
    }

    @Test
    fun `month filter shows events when navigating to historical month`() = runTest {
        val viewModel = DividendCalendarViewModel(dividendDao, stockRepository, dividendRepository)
        stocksFlow.value = listOf(
            StockEntity(code = "sh.600398", name = "海澜之家", marketCode = "1", shares = 100)
        )
        dividendsFlow.value = listOf(
            DividendEntity(
                id = "history-month",
                stockCode = "sh.600398",
                reportDate = "2024-12-31",
                cashPerShare = 0.18,
                exDividendDate = "2025-07-10"
            )
        )
        viewModel.onFilterChanged(DividendCalendarFilter.MONTH)
        viewModel.onVisibleMonthChanged("2025-07")
        viewModel.onDateSelected("2025-07-10")
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.events.map { it.id }).containsExactly("history-month")
        assertThat(viewModel.uiState.value.calendarDays.first { it.date == "2025-07-10" }.hasEvents).isTrue()
        assertThat(viewModel.uiState.value.selectedDateEvents.map { it.id }).containsExactly("history-month")
    }

    @Test
    fun `calendar ignores dividend plans without execution dates`() = runTest {
        val viewModel = DividendCalendarViewModel(dividendDao, stockRepository, dividendRepository)
        stocksFlow.value = listOf(
            StockEntity(code = "sh.600398", name = "海澜之家", marketCode = "1", shares = 100)
        )
        dividendsFlow.value = listOf(
            DividendEntity(
                id = "plan-only",
                stockCode = "sh.600398",
                reportDate = "2026-06-30",
                cashPerShare = 0.10,
                exDividendDate = null,
                recordDate = null,
                planStatus = "预披露",
                planNoticeDate = "2026-04-30"
            )
        )
        viewModel.onFilterChanged(DividendCalendarFilter.YEAR)
        viewModel.onDateSelected("2026-04-30")
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.events).isEmpty()
        assertThat(viewModel.uiState.value.selectedDateEvents).isEmpty()
    }
}
