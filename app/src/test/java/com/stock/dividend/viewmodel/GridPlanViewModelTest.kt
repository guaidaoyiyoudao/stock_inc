package com.stock.dividend.viewmodel

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.local.entity.GridPlanEntity
import com.stock.dividend.data.local.entity.StockEntity
import com.stock.dividend.data.repository.DividendRepository
import com.stock.dividend.data.repository.GridPlanRepository
import com.stock.dividend.data.repository.StockRepository
import io.mockk.coEvery
import io.mockk.coVerify
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
class GridPlanViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun plan(id: String, code: String = "sh.600036") = GridPlanEntity(
        id = id, stockCode = code, stockName = "招商银行",
        basePrice = 10.0, lowPrice = 8.0, highPrice = 12.0, grids = 4, totalCapital = 100000.0
    )

    private fun stock(code: String = "sh.600036", name: String = "招商银行") = StockEntity(
        code = code, name = name, marketCode = "1", shares = 0, costPerShare = 0.0
    )

    @Test
    fun `observeAll renders plans with grid result`() = runTest {
        val gridRepo = mockk<GridPlanRepository>()
        val stockRepo = mockk<StockRepository>()
        val divRepo = mockk<DividendRepository>(relaxed = true)
        coEvery { gridRepo.observeAll() } returns flowOf(listOf(plan("1")))
        coEvery { stockRepo.observeAllStocks() } returns flowOf(listOf(stock()))
        coEvery { stockRepo.observeAllStocksForSnapshot() } returns emptyList()
        coEvery { stockRepo.fetchQuotes(any()) } returns emptyMap()

        val vm = GridPlanViewModel(gridRepo, stockRepo, divRepo)
        vm.uiState.test {
            var state = awaitItem()
            if (state.items.isEmpty()) state = awaitItem()
            assertThat(state.items).hasSize(1)
            // 计划对应的 GridResult 应已生成（4 档：8/9/11/12）
            val result = state.items[0].result
            assertThat(result.validationError).isNull()
            assertThat(result.levels).hasSize(4)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `savePlan persists entity via repository`() = runTest {
        val gridRepo = mockk<GridPlanRepository>(relaxed = true)
        val stockRepo = mockk<StockRepository>(relaxed = true)
        val divRepo = mockk<DividendRepository>(relaxed = true)
        coEvery { gridRepo.observeAll() } returns flowOf(emptyList())
        coEvery { stockRepo.observeAllStocks() } returns flowOf(listOf(stock()))
        coEvery { stockRepo.observeAllStocksForSnapshot() } returns emptyList()
        coEvery { stockRepo.fetchQuotes(any()) } returns emptyMap()

        val vm = GridPlanViewModel(gridRepo, stockRepo, divRepo)
        vm.showGenerator()
        vm.onStockSelected("sh.600036")
        vm.onBasePriceChanged("10")
        vm.onLowPriceChanged("8")
        vm.onHighPriceChanged("12")
        vm.onGridsChanged("4")
        vm.onTotalCapitalChanged("100000")
        vm.savePlan()
        advanceUntilIdle()

        coVerify {
            gridRepo.upsert(match {
                it.stockCode == "sh.600036" && it.basePrice == 10.0 && it.grids == 4
            })
        }
        assertThat(vm.uiState.value.showGenerator).isFalse()
    }

    @Test
    fun `savePlan ignores when stock not selected`() = runTest {
        val gridRepo = mockk<GridPlanRepository>(relaxed = true)
        val stockRepo = mockk<StockRepository>(relaxed = true)
        val divRepo = mockk<DividendRepository>(relaxed = true)
        coEvery { gridRepo.observeAll() } returns flowOf(emptyList())
        coEvery { stockRepo.observeAllStocks() } returns flowOf(emptyList())
        coEvery { stockRepo.observeAllStocksForSnapshot() } returns emptyList()

        val vm = GridPlanViewModel(gridRepo, stockRepo, divRepo)
        vm.showGenerator()
        // 不选标的，直接保存
        vm.onBasePriceChanged("10")
        vm.onLowPriceChanged("8")
        vm.onHighPriceChanged("12")
        vm.savePlan()
        advanceUntilIdle()

        // 不应调用 upsert
        coVerify(exactly = 0) { gridRepo.upsert(any()) }
    }

    @Test
    fun `deletePlan calls repository delete`() = runTest {
        val gridRepo = mockk<GridPlanRepository>(relaxed = true)
        val stockRepo = mockk<StockRepository>(relaxed = true)
        val divRepo = mockk<DividendRepository>(relaxed = true)
        coEvery { gridRepo.observeAll() } returns flowOf(emptyList())
        coEvery { stockRepo.observeAllStocks() } returns flowOf(emptyList())
        coEvery { stockRepo.observeAllStocksForSnapshot() } returns emptyList()

        val vm = GridPlanViewModel(gridRepo, stockRepo, divRepo)
        vm.deletePlan("abc")
        advanceUntilIdle()
        coVerify { gridRepo.delete("abc") }
    }

    @Test
    fun `preview recalculates on param change`() = runTest {
        val gridRepo = mockk<GridPlanRepository>(relaxed = true)
        val stockRepo = mockk<StockRepository>(relaxed = true)
        val divRepo = mockk<DividendRepository>(relaxed = true)
        coEvery { gridRepo.observeAll() } returns flowOf(emptyList())
        coEvery { stockRepo.observeAllStocks() } returns flowOf(emptyList())
        coEvery { stockRepo.observeAllStocksForSnapshot() } returns emptyList()

        val vm = GridPlanViewModel(gridRepo, stockRepo, divRepo)
        vm.showGenerator()
        // 参数不全时 preview 为 null
        assertThat(vm.uiState.value.preview).isNull()
        vm.onBasePriceChanged("10")
        vm.onLowPriceChanged("8")
        vm.onHighPriceChanged("12")
        vm.onGridsChanged("4")
        vm.onTotalCapitalChanged("100000")
        // 参数齐全后 preview 非空
        assertThat(vm.uiState.value.preview).isNotNull()
        assertThat(vm.uiState.value.preview?.levels).hasSize(4)
    }

    @Test
    fun `autoAnchor fills params from boll and dividend`() = runTest {
        val gridRepo = mockk<GridPlanRepository>(relaxed = true)
        val stockRepo = mockk<StockRepository>(relaxed = true)
        val divRepo = mockk<DividendRepository>(relaxed = true)
        coEvery { gridRepo.observeAll() } returns flowOf(emptyList())
        coEvery { stockRepo.observeAllStocks() } returns flowOf(listOf(stock()))
        coEvery { stockRepo.observeAllStocksForSnapshot() } returns emptyList()
        // BOLL 8/10/12，股息 0.6/股
        coEvery { stockRepo.fetchBoll(any(), any()) } returns com.stock.dividend.data.repository.BollBand(10.0, 12.0, 8.0)
        coEvery { divRepo.observeDividends(any()) } returns flowOf(
            listOf(com.stock.dividend.data.local.entity.DividendEntity(id = "1", stockCode = "sh.600036", reportDate = "2024", cashPerShare = 0.6))
        )

        val vm = GridPlanViewModel(gridRepo, stockRepo, divRepo)
        vm.showGenerator()
        vm.onStockSelected("sh.600036")
        vm.onTargetYieldChanged("6")
        vm.autoAnchor()
        advanceUntilIdle()

        // 锚定应填充：基准 10、下界 min(8, 10)=8、上界 12
        val state = vm.uiState.value
        assertThat(state.isAnchoring).isFalse()
        assertThat(state.anchorInfo).isNotNull()
        assertThat(state.anchorInfo?.basePrice).isEqualTo(10.0)
        assertThat(state.anchorInfo?.lowPrice).isEqualTo(8.0)
        assertThat(state.anchorInfo?.highPrice).isEqualTo(12.0)
        assertThat(state.basePriceInput).isEqualTo("10.00")
        assertThat(state.lowPriceInput).isEqualTo("8.00")
        assertThat(state.highPriceInput).isEqualTo("12.00")
        assertThat(state.anchorError).isNull()
    }

    @Test
    fun `autoAnchor reports error when data insufficient`() = runTest {
        val gridRepo = mockk<GridPlanRepository>(relaxed = true)
        val stockRepo = mockk<StockRepository>(relaxed = true)
        val divRepo = mockk<DividendRepository>(relaxed = true)
        coEvery { gridRepo.observeAll() } returns flowOf(emptyList())
        coEvery { stockRepo.observeAllStocks() } returns flowOf(listOf(stock()))
        coEvery { stockRepo.observeAllStocksForSnapshot() } returns emptyList()
        // BOLL 拉取失败（null）
        coEvery { stockRepo.fetchBoll(any(), any()) } returns null
        coEvery { divRepo.observeDividends(any()) } returns flowOf(emptyList())

        val vm = GridPlanViewModel(gridRepo, stockRepo, divRepo)
        vm.showGenerator()
        vm.onStockSelected("sh.600036")
        vm.onTargetYieldChanged("6")
        vm.autoAnchor()
        advanceUntilIdle()

        assertThat(vm.uiState.value.anchorInfo).isNull()
        assertThat(vm.uiState.value.anchorError).isNotNull()
        assertThat(vm.uiState.value.isAnchoring).isFalse()
    }

    @Test
    fun `autoAnchor requires stock selected`() = runTest {
        val gridRepo = mockk<GridPlanRepository>(relaxed = true)
        val stockRepo = mockk<StockRepository>(relaxed = true)
        val divRepo = mockk<DividendRepository>(relaxed = true)
        coEvery { gridRepo.observeAll() } returns flowOf(emptyList())
        coEvery { stockRepo.observeAllStocks() } returns flowOf(emptyList())
        coEvery { stockRepo.observeAllStocksForSnapshot() } returns emptyList()

        val vm = GridPlanViewModel(gridRepo, stockRepo, divRepo)
        vm.showGenerator()
        // 不选标的直接锚定
        vm.onTargetYieldChanged("6")
        vm.autoAnchor()
        advanceUntilIdle()

        assertThat(vm.uiState.value.anchorError).contains("选择标的")
        assertThat(vm.uiState.value.anchorInfo).isNull()
    }
}
