package com.stock.dividend.viewmodel

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.local.entity.GridPlanEntity
import com.stock.dividend.data.local.entity.StockEntity
import com.stock.dividend.data.repository.GridPlanRepository
import com.stock.dividend.data.repository.StockRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GridPlanViewModelTest {

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
        coEvery { gridRepo.observeAll() } returns flowOf(listOf(plan("1")))
        coEvery { stockRepo.observeAllStocks() } returns flowOf(listOf(stock()))
        coEvery { stockRepo.observeAllStocksForSnapshot() } returns emptyList()
        coEvery { stockRepo.fetchQuotes(any()) } returns emptyMap()

        val vm = GridPlanViewModel(gridRepo, stockRepo)
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
        coEvery { gridRepo.observeAll() } returns flowOf(emptyList())
        coEvery { stockRepo.observeAllStocks() } returns flowOf(listOf(stock()))
        coEvery { stockRepo.observeAllStocksForSnapshot() } returns emptyList()
        coEvery { stockRepo.fetchQuotes(any()) } returns emptyMap()

        val vm = GridPlanViewModel(gridRepo, stockRepo)
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
        coEvery { gridRepo.observeAll() } returns flowOf(emptyList())
        coEvery { stockRepo.observeAllStocks() } returns flowOf(emptyList())
        coEvery { stockRepo.observeAllStocksForSnapshot() } returns emptyList()

        val vm = GridPlanViewModel(gridRepo, stockRepo)
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
        coEvery { gridRepo.observeAll() } returns flowOf(emptyList())
        coEvery { stockRepo.observeAllStocks() } returns flowOf(emptyList())
        coEvery { stockRepo.observeAllStocksForSnapshot() } returns emptyList()

        val vm = GridPlanViewModel(gridRepo, stockRepo)
        vm.deletePlan("abc")
        advanceUntilIdle()
        coVerify { gridRepo.delete("abc") }
    }

    @Test
    fun `preview recalculates on param change`() = runTest {
        val gridRepo = mockk<GridPlanRepository>(relaxed = true)
        val stockRepo = mockk<StockRepository>(relaxed = true)
        coEvery { gridRepo.observeAll() } returns flowOf(emptyList())
        coEvery { stockRepo.observeAllStocks() } returns flowOf(emptyList())
        coEvery { stockRepo.observeAllStocksForSnapshot() } returns emptyList()

        val vm = GridPlanViewModel(gridRepo, stockRepo)
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
}
