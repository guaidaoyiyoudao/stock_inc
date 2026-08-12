package com.stock.dividend.viewmodel

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.local.dao.DividendDao
import com.stock.dividend.data.local.entity.StockEntity
import com.stock.dividend.data.repository.BollBand
import com.stock.dividend.data.repository.BondYieldRepository
import com.stock.dividend.data.repository.GridPlanRepository
import com.stock.dividend.data.repository.MarketDataRepository
import com.stock.dividend.data.repository.QuoteSnapshot
import com.stock.dividend.data.repository.StockRepository
import com.stock.dividend.data.repository.TodayBriefingCoordinator
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TodayViewModelTest {

    private val stockRepository = mockk<StockRepository>(relaxed = true)
    private val gridPlanRepository = mockk<GridPlanRepository>(relaxed = true)
    private val dividendDao = mockk<DividendDao>(relaxed = true)
    private val bondYieldRepository = mockk<BondYieldRepository>(relaxed = true)
    private val marketDataRepository = mockk<MarketDataRepository>(relaxed = true)
    private val briefingCoordinator = mockk<TodayBriefingCoordinator>(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        // 订阅类 Flow 显式 stub 成空，避免 relaxed 返回 mock Flow 在 collect 时抛错
        every { stockRepository.observeAllStocks() } returns flowOf(emptyList())
        every { gridPlanRepository.observeAll() } returns flowOf(emptyList())
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun makeVm() = TodayViewModel(
        stockRepository, gridPlanRepository, dividendDao,
        bondYieldRepository, marketDataRepository, briefingCoordinator,
    )

    @Test
    fun briefingLoadedIntoState() = runTest {
        coEvery { briefingCoordinator.read(any()) } returns "今日一句话简报。"
        val vm = makeVm()
        advanceUntilIdle()
        assertThat(vm.uiState.value.briefing).isEqualTo("今日一句话简报。")
    }

    @Test
    fun briefingNull_whenCoordinatorReturnsNull() = runTest {
        coEvery { briefingCoordinator.read(any()) } returns null
        val vm = makeVm()
        advanceUntilIdle()
        assertThat(vm.uiState.value.briefing).isNull()
    }

    @Test
    fun emptyHoldings_showsNoHoldingsFlag() = runTest {
        coEvery { briefingCoordinator.read(any()) } returns null
        val vm = makeVm()
        advanceUntilIdle()
        assertThat(vm.uiState.value.hasHoldings).isFalse()
    }

    @Test
    fun bollLowerBreakSignal_emittedWhenPriceBelowLower() = runTest {
        val stock = StockEntity(code = "sh.600000", name = "T", marketCode = "1", shares = 100, costPerShare = 10.0)
        every { stockRepository.observeAllStocks() } returns flowOf(listOf(stock))
        coEvery { stockRepository.fetchQuoteSnapshots(any()) } returns
            mapOf("sh.600000" to QuoteSnapshot("sh.600000", price = 8.8, prevClose = 9.0))
        coEvery { stockRepository.fetchBoll(any(), any()) } returns BollBand(middle = 10.0, upper = 11.0, lower = 9.0)
        coEvery { briefingCoordinator.read(any()) } returns null
        val vm = makeVm()
        advanceUntilIdle()
        // price 8.8 ≤ lower 9.0 → 应触发「跌破BOLL下轨」信号
        coVerify(atLeast = 1) { stockRepository.fetchBoll(any(), any()) }
        // marketValue≈880 证明 price 生效（fetchQuoteSnapshots mock ok）；浮点用 tolerance
        assertThat(vm.uiState.value.marketValue).isWithin(0.01).of(880.0)
        assertThat(vm.uiState.value.signals.any { it.title.contains("BOLL下轨") }).isTrue()
    }
}
