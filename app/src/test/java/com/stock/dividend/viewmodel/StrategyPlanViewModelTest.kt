package com.stock.dividend.viewmodel

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.local.entity.STRATEGY_TYPE_MA_DCA
import com.stock.dividend.data.local.entity.StockEntity
import com.stock.dividend.data.local.entity.StrategyPlanEntity
import com.stock.dividend.data.local.entity.TransactionEntity
import com.stock.dividend.data.notification.DividendAlertNotifier
import com.stock.dividend.data.plane.MarketDataPlane
import com.stock.dividend.data.repository.KlineBar
import com.stock.dividend.data.repository.KlinePeriod
import com.stock.dividend.data.repository.MaDcaSignal
import com.stock.dividend.data.repository.StrategyPlanRepository
import com.stock.dividend.data.repository.TransactionRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
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
class StrategyPlanViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun plan(id: String = "s1", code: String = "sh.510880") = StrategyPlanEntity(
        id = id, stockCode = code, stockName = "红利ETF",
        strategyType = STRATEGY_TYPE_MA_DCA,
        maPeriod = 250, sellHalfPercent = 7.5, sellAllPercent = 15.0, dcaAmount = 1000.0
    )

    private fun stock(code: String = "sh.510880", name: String = "红利ETF") = StockEntity(
        code = code, name = name, marketCode = "1", shares = 0, costPerShare = 0.0
    )

    /** 250 根收盘价全为 10 → 年线 10；配合不同现价驱动不同信号。 */
    private val flatClosesKlines = List(250) { KlineBar("d$it", 10.0, 10.0, 10.0, 10.0, 1000.0) }

    private fun savedStateHandle(code: String? = null) = androidx.lifecycle.SavedStateHandle(
        mutableMapOf<String, Any>().apply { code?.let { put("code", it) } }
    )

    @Test
    fun `observeAll renders plans with evaluation and sell shares`() = runTest {
        val strategyRepo = mockk<StrategyPlanRepository>(relaxed = true)
        val plane = mockk<MarketDataPlane>(relaxed = true)
        val txRepo = mockk<TransactionRepository>(relaxed = true)
        val notifier = mockk<DividendAlertNotifier>(relaxed = true)
        coEvery { strategyRepo.observeAll() } returns flowOf(listOf(plan()))
        coEvery { plane.observeAllStocks() } returns flowOf(listOf(stock()))
        coEvery { txRepo.observeAll() } returns flowOf(
            listOf(TransactionEntity(stockCode = "sh.510880", type = "BUY", shares = 500, date = "2026-01-05"))
        )
        coEvery { plane.getPricesForCodes(any()) } returns mapOf("sh.510880" to 10.8)
        coEvery { plane.getKlines("sh.510880", KlinePeriod.DAILY, 250) } returns flatClosesKlines

        val vm = StrategyPlanViewModel(savedStateHandle(), strategyRepo, plane, txRepo, notifier)
        vm.uiState.test {
            var state = awaitItem()
            while (state.items.isEmpty() || state.items[0].evaluation == null) state = awaitItem()
            val item = state.items[0]
            // 现价 10.8 高于年线 10 达 8% ≥ 7.5% → 卖出一半；持仓 500 → 整手 200
            assertThat(item.evaluation!!.signal).isEqualTo(MaDcaSignal.SELL_HALF)
            assertThat(item.holdingShares).isEqualTo(500)
            assertThat(item.sellTargetShares).isEqualTo(200)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `dca window computes buy shares from dca amount`() = runTest {
        val strategyRepo = mockk<StrategyPlanRepository>(relaxed = true)
        val plane = mockk<MarketDataPlane>(relaxed = true)
        val txRepo = mockk<TransactionRepository>(relaxed = true)
        val notifier = mockk<DividendAlertNotifier>(relaxed = true)
        coEvery { strategyRepo.observeAll() } returns flowOf(listOf(plan()))
        coEvery { plane.observeAllStocks() } returns flowOf(listOf(stock()))
        coEvery { txRepo.observeAll() } returns flowOf(emptyList())
        // 现价 8 → 低于年线 10：定投窗口；1000 元 ÷ 8 = 125 股 → 整手 100
        coEvery { plane.getPricesForCodes(any()) } returns mapOf("sh.510880" to 8.0)
        coEvery { plane.getKlines("sh.510880", KlinePeriod.DAILY, 250) } returns flatClosesKlines

        val vm = StrategyPlanViewModel(savedStateHandle(), strategyRepo, plane, txRepo, notifier)
        advanceUntilIdle()
        val item = vm.uiState.value.items[0]
        assertThat(item.evaluation!!.signal).isEqualTo(MaDcaSignal.DCA_WINDOW)
        assertThat(item.dcaBuyShares).isEqualTo(100)
    }

    @Test
    fun `insufficient klines leaves evaluation null without crash`() = runTest {
        val strategyRepo = mockk<StrategyPlanRepository>(relaxed = true)
        val plane = mockk<MarketDataPlane>(relaxed = true)
        val txRepo = mockk<TransactionRepository>(relaxed = true)
        val notifier = mockk<DividendAlertNotifier>(relaxed = true)
        coEvery { strategyRepo.observeAll() } returns flowOf(listOf(plan()))
        coEvery { plane.observeAllStocks() } returns flowOf(listOf(stock()))
        coEvery { txRepo.observeAll() } returns flowOf(emptyList())
        coEvery { plane.getPricesForCodes(any()) } returns mapOf("sh.510880" to 10.0)
        coEvery { plane.getKlines(any(), any(), any()) } returns emptyList()

        val vm = StrategyPlanViewModel(savedStateHandle(), strategyRepo, plane, txRepo, notifier)
        advanceUntilIdle()
        assertThat(vm.uiState.value.items).hasSize(1)
        assertThat(vm.uiState.value.items[0].evaluation).isNull()
        assertThat(vm.uiState.value.isLoading).isFalse()
    }

    @Test
    fun `savePlan validates params and upserts entity`() = runTest {
        val strategyRepo = mockk<StrategyPlanRepository>(relaxed = true)
        val plane = mockk<MarketDataPlane>(relaxed = true)
        val txRepo = mockk<TransactionRepository>(relaxed = true)
        val notifier = mockk<DividendAlertNotifier>(relaxed = true)
        coEvery { strategyRepo.observeAll() } returns flowOf(emptyList())
        coEvery { plane.observeAllStocks() } returns flowOf(listOf(stock()))
        coEvery { txRepo.observeAll() } returns flowOf(emptyList())

        val vm = StrategyPlanViewModel(savedStateHandle(), strategyRepo, plane, txRepo, notifier)
        advanceUntilIdle()

        // 未选标的 → 报错不保存
        vm.showEditor()
        vm.savePlan()
        assertThat(vm.uiState.value.saveError).isNotNull()
        coVerify(exactly = 0) { strategyRepo.upsert(any()) }

        // 选标的 + 非法参数（清仓 ≤ 卖半）→ 报错不保存
        vm.onStockSelected("sh.510880")
        vm.onSellAllChanged("7.0")
        vm.savePlan()
        assertThat(vm.uiState.value.saveError).isNotNull()
        coVerify(exactly = 0) { strategyRepo.upsert(any()) }

        // 合法参数 → 保存成功并关闭编辑器
        vm.onSellAllChanged("15")
        vm.savePlan()
        advanceUntilIdle()
        assertThat(vm.uiState.value.saveError).isNull()
        assertThat(vm.uiState.value.showEditor).isFalse()
        val saved = slot<StrategyPlanEntity>()
        coVerify { strategyRepo.upsert(capture(saved)) }
        assertThat(saved.captured.stockCode).isEqualTo("sh.510880")
        assertThat(saved.captured.maPeriod).isEqualTo(250)
        assertThat(saved.captured.sellHalfPercent).isEqualTo(7.5)
        assertThat(saved.captured.sellAllPercent).isEqualTo(15.0)
        assertThat(saved.captured.strategyType).isEqualTo(STRATEGY_TYPE_MA_DCA)
    }

    @Test
    fun `initial stock code auto opens editor with selection`() = runTest {
        val strategyRepo = mockk<StrategyPlanRepository>(relaxed = true)
        val plane = mockk<MarketDataPlane>(relaxed = true)
        val txRepo = mockk<TransactionRepository>(relaxed = true)
        val notifier = mockk<DividendAlertNotifier>(relaxed = true)
        coEvery { strategyRepo.observeAll() } returns flowOf(emptyList())
        coEvery { plane.observeAllStocks() } returns flowOf(listOf(stock()))
        coEvery { txRepo.observeAll() } returns flowOf(emptyList())

        val vm = StrategyPlanViewModel(savedStateHandle("sh.510880"), strategyRepo, plane, txRepo, notifier)
        advanceUntilIdle()
        // 个股详情页入口：自动打开编辑器并预选该标的
        assertThat(vm.uiState.value.showEditor).isTrue()
        assertThat(vm.uiState.value.selectedStockCode).isEqualTo("sh.510880")
    }
}
