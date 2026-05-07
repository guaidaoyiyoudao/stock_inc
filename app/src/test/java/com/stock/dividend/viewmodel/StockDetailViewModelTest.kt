package com.stock.dividend.viewmodel

import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.local.dao.DividendDao
import com.stock.dividend.data.local.entity.DividendEntity
import com.stock.dividend.data.local.entity.StockEntity
import com.stock.dividend.data.repository.DividendRepository
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
        coEvery { stockRepository.getFirstBuyDate(any()) } returns null
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun mockDividendRepository(): DividendRepository = mockk {
        coEvery { observeDividends(any()) } returns dividendsFlow
        coEvery { getLatestDividend(any()) } returns null
    }

    @Test
    fun `initial state has isLoading true`() = runTest {
        coEvery { dividendDao.observeByStock("sz.000001") } returns dividendsFlow

        val viewModel = StockDetailViewModel(
            savedStateHandle = androidx.lifecycle.SavedStateHandle(mapOf("code" to "sz.000001")),
            stockRepository = stockRepository,
            dividendRepository = mockDividendRepository()
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
            dividendRepository = mockDividendRepository()
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
            dividendRepository = mockDividendRepository()
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
            dividendRepository = mockDividendRepository()
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.stock).isNull()
    }

    @Test
    fun `empty dividends list sets isLoading to false`() = runTest {
        val viewModel = StockDetailViewModel(
            savedStateHandle = androidx.lifecycle.SavedStateHandle(mapOf("code" to "sz.000001")),
            stockRepository = stockRepository,
            dividendRepository = mockDividendRepository()
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
            dividendRepository = mockDividendRepository()
        )

        assertThat(viewModel.uiState.value.error).isNull()
    }

    private fun createViewModel(
        code: String = "sz.000001",
        dividends: List<DividendEntity> = emptyList()
    ): StockDetailViewModel {
        dividendsFlow.value = dividends
        val repo = mockDividendRepository()
        return StockDetailViewModel(
            savedStateHandle = androidx.lifecycle.SavedStateHandle(mapOf("code" to code)),
            stockRepository = stockRepository,
            dividendRepository = repo
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

    private fun makeDividend(
        id: String,
        reportDate: String,
        dividendYield: Double?
    ): DividendEntity {
        return DividendEntity(
            id = id,
            stockCode = "sz.000001",
            reportDate = reportDate,
            cashPerShare = 0.1,
            dividendYield = dividendYield,
            exDividendDate = null,
            recordDate = null,
            planStatus = "实施方案"
        )
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

    @Test
    fun `dividend rate points include only valid yields sorted by report date`() = runTest {
        val viewModel = createViewModel(
            dividends = listOf(
                makeDividend("2024", "2024-12-31", 4.2),
                makeDividend("2022", "2022-12-31", 2.8),
                makeDividend("null", "2023-06-30", null),
                makeDividend("negative", "2023-12-31", -1.0),
                makeDividend("nan", "2021-12-31", Double.NaN),
                makeDividend("infinite", "2020-12-31", Double.POSITIVE_INFINITY)
            )
        )
        testDispatcher.scheduler.advanceUntilIdle()

        val points = viewModel.uiState.value.dividendRatePoints

        assertThat(points.map { it.period }).containsExactly("2022", "2024").inOrder()
        assertThat(points.map { it.label }).containsExactly("2022", "2024").inOrder()
        assertThat(points.map { it.ratePercent }).containsExactly(2.8, 4.2).inOrder()
    }

    @Test
    fun `multiple valid dividend yields produce chart eligible points`() = runTest {
        val viewModel = createViewModel(
            dividends = listOf(
                makeDividend("2022", "2022-12-31", 2.1),
                makeDividend("2023", "2023-12-31", 3.4),
                makeDividend("2024", "2024-12-31", 4.5)
            )
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.dividendRatePoints).hasSize(3)
    }

    @Test
    fun `null dividend yields produce empty dividend rate points`() = runTest {
        val viewModel = createViewModel(
            dividends = listOf(
                makeDividend("2022", "2022-12-31", null),
                makeDividend("2023", "2023-12-31", null)
            )
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.dividendRatePoints).isEmpty()
    }

    @Test
    fun `single valid dividend yield preserves one point and percent value`() = runTest {
        val viewModel = createViewModel(
            dividends = listOf(
                makeDividend("2022", "2022-12-31", null),
                makeDividend("2023", "2023-12-31", 3.25)
            )
        )
        testDispatcher.scheduler.advanceUntilIdle()

        val points = viewModel.uiState.value.dividendRatePoints

        assertThat(points).hasSize(1)
        assertThat(points[0].period).isEqualTo("2023")
        assertThat(points[0].label).isEqualTo("2023")
        assertThat(points[0].ratePercent).isEqualTo(3.25)
    }

    @Test
    fun `out of order dividend records produce ascending dividend rate points`() = runTest {
        val viewModel = createViewModel(
            dividends = listOf(
                makeDividend("2024", "2024-12-31", 4.5),
                makeDividend("2021", "2021-12-31", 1.6),
                makeDividend("2023", "2023-12-31", 3.1),
                makeDividend("2022", "2022-12-31", 2.4)
            )
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.dividendRatePoints.map { it.period })
            .containsExactly("2021", "2022", "2023", "2024")
            .inOrder()
    }

    @Test
    fun `multiple dividends in the same year are summed into one dividend rate point`() = runTest {
        val viewModel = createViewModel(
            dividends = listOf(
                makeDividend("2024-final", "2024-12-31", 2.3),
                makeDividend("2023-final", "2023-12-31", 3.0),
                makeDividend("2024-mid", "2024-06-30", 1.2)
            )
        )
        testDispatcher.scheduler.advanceUntilIdle()

        val points = viewModel.uiState.value.dividendRatePoints

        assertThat(points.map { it.period }).containsExactly("2023", "2024").inOrder()
        assertThat(points.map { it.ratePercent }).containsExactly(3.0, 3.5).inOrder()
    }
}
