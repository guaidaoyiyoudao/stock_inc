package com.stock.dividend.viewmodel

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.local.dao.DividendDao
import com.stock.dividend.data.repository.BondYieldRepository
import com.stock.dividend.data.repository.GridPlanRepository
import com.stock.dividend.data.repository.MarketDataRepository
import com.stock.dividend.data.repository.StockRepository
import com.stock.dividend.data.repository.TodayBriefingCoordinator
import io.mockk.coEvery
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
}
