package com.stock.dividend.viewmodel

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.local.entity.GridPlanEntity
import com.stock.dividend.data.local.entity.StockEntity
import com.stock.dividend.data.notification.DividendAlertNotifier
import com.stock.dividend.data.repository.DividendRepository
import com.stock.dividend.data.repository.GridPlanRepository
import com.stock.dividend.data.repository.KlineBar
import com.stock.dividend.data.repository.KlinePeriod
import com.stock.dividend.data.repository.KlineRepository
import com.stock.dividend.data.repository.StockRepository
import com.stock.dividend.data.repository.TransactionRepository
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

    /** 构造 SavedStateHandle（可选携带 stockCode，模拟个股详情页入口）。 */
    private fun savedStateHandle(code: String? = null) = androidx.lifecycle.SavedStateHandle(
        mutableMapOf<String, Any>().apply { code?.let { put("code", it) } }
    )

    @Test
    fun `observeAll renders plans with grid result`() = runTest {
        val gridRepo = mockk<GridPlanRepository>()
        val stockRepo = mockk<StockRepository>()
        val divRepo = mockk<DividendRepository>(relaxed = true)
        val txRepo = mockk<TransactionRepository>(relaxed = true)
        val klineRepo = mockk<KlineRepository>(relaxed = true)
        val notifier = mockk<DividendAlertNotifier>(relaxed = true)
        coEvery { gridRepo.observeAll() } returns flowOf(listOf(plan("1")))
        coEvery { stockRepo.observeAllStocks() } returns flowOf(listOf(stock()))
        coEvery { stockRepo.observeAllStocksForSnapshot() } returns emptyList()
        coEvery { txRepo.observeAll() } returns flowOf(emptyList())
        coEvery { stockRepo.fetchQuotes(any()) } returns emptyMap()

        val vm = GridPlanViewModel(savedStateHandle(), gridRepo, stockRepo, divRepo, txRepo, klineRepo, notifier)
        vm.uiState.test {
            // 跳过中间态（init 的权限检查等会先发 items 仍空的状态）
            var state = awaitItem()
            while (state.items.isEmpty()) state = awaitItem()
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
        val txRepo = mockk<TransactionRepository>(relaxed = true)
        val klineRepo = mockk<KlineRepository>(relaxed = true)
        val notifier = mockk<DividendAlertNotifier>(relaxed = true)
        coEvery { gridRepo.observeAll() } returns flowOf(emptyList())
        coEvery { stockRepo.observeAllStocks() } returns flowOf(listOf(stock()))
        coEvery { stockRepo.observeAllStocksForSnapshot() } returns emptyList()
        coEvery { txRepo.observeAll() } returns flowOf(emptyList())
        coEvery { stockRepo.fetchQuotes(any()) } returns emptyMap()

        val vm = GridPlanViewModel(savedStateHandle(), gridRepo, stockRepo, divRepo, txRepo, klineRepo, notifier)
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
        val txRepo = mockk<TransactionRepository>(relaxed = true)
        val klineRepo = mockk<KlineRepository>(relaxed = true)
        val notifier = mockk<DividendAlertNotifier>(relaxed = true)
        coEvery { gridRepo.observeAll() } returns flowOf(emptyList())
        coEvery { stockRepo.observeAllStocks() } returns flowOf(emptyList())
        coEvery { stockRepo.observeAllStocksForSnapshot() } returns emptyList()
        coEvery { txRepo.observeAll() } returns flowOf(emptyList())

        val vm = GridPlanViewModel(savedStateHandle(), gridRepo, stockRepo, divRepo, txRepo, klineRepo, notifier)
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

    /** 回归保护：参数不完整时 savePlan 必须给出可见错误（曾静默 return 导致「不能保存」）。 */
    @Test
    fun `savePlan with incomplete params sets visible error instead of silent noop`() = runTest {
        val gridRepo = mockk<GridPlanRepository>(relaxed = true)
        val stockRepo = mockk<StockRepository>(relaxed = true)
        val divRepo = mockk<DividendRepository>(relaxed = true)
        val txRepo = mockk<TransactionRepository>(relaxed = true)
        val klineRepo = mockk<KlineRepository>(relaxed = true)
        val notifier = mockk<DividendAlertNotifier>(relaxed = true)
        coEvery { gridRepo.observeAll() } returns flowOf(emptyList())
        coEvery { stockRepo.observeAllStocks() } returns flowOf(listOf(stock()))
        coEvery { stockRepo.observeAllStocksForSnapshot() } returns emptyList()
        coEvery { txRepo.observeAll() } returns flowOf(emptyList())
        coEvery { stockRepo.fetchQuotes(any()) } returns emptyMap()

        val vm = GridPlanViewModel(savedStateHandle(), gridRepo, stockRepo, divRepo, txRepo, klineRepo, notifier)
        vm.showGenerator()
        vm.onStockSelected("sh.600036")
        // 只填了部分参数（缺 highPrice/grids/capital）
        vm.onBasePriceChanged("10")
        vm.onLowPriceChanged("8")
        vm.savePlan()
        advanceUntilIdle()

        // 保存失败必须可见，且不落库
        assertThat(vm.uiState.value.saveError).isNotNull()
        assertThat(vm.uiState.value.showGenerator).isTrue()
        coVerify(exactly = 0) { gridRepo.upsert(any()) }
    }

    @Test
    fun `deletePlan calls repository delete`() = runTest {
        val gridRepo = mockk<GridPlanRepository>(relaxed = true)
        val stockRepo = mockk<StockRepository>(relaxed = true)
        val divRepo = mockk<DividendRepository>(relaxed = true)
        val txRepo = mockk<TransactionRepository>(relaxed = true)
        val klineRepo = mockk<KlineRepository>(relaxed = true)
        val notifier = mockk<DividendAlertNotifier>(relaxed = true)
        coEvery { gridRepo.observeAll() } returns flowOf(emptyList())
        coEvery { stockRepo.observeAllStocks() } returns flowOf(emptyList())
        coEvery { stockRepo.observeAllStocksForSnapshot() } returns emptyList()
        coEvery { txRepo.observeAll() } returns flowOf(emptyList())

        val vm = GridPlanViewModel(savedStateHandle(), gridRepo, stockRepo, divRepo, txRepo, klineRepo, notifier)
        vm.deletePlan("abc")
        advanceUntilIdle()
        coVerify { gridRepo.delete("abc") }
    }

    @Test
    fun `preview recalculates on param change`() = runTest {
        val gridRepo = mockk<GridPlanRepository>(relaxed = true)
        val stockRepo = mockk<StockRepository>(relaxed = true)
        val divRepo = mockk<DividendRepository>(relaxed = true)
        val txRepo = mockk<TransactionRepository>(relaxed = true)
        val klineRepo = mockk<KlineRepository>(relaxed = true)
        val notifier = mockk<DividendAlertNotifier>(relaxed = true)
        coEvery { gridRepo.observeAll() } returns flowOf(emptyList())
        coEvery { stockRepo.observeAllStocks() } returns flowOf(emptyList())
        coEvery { stockRepo.observeAllStocksForSnapshot() } returns emptyList()
        coEvery { txRepo.observeAll() } returns flowOf(emptyList())

        val vm = GridPlanViewModel(savedStateHandle(), gridRepo, stockRepo, divRepo, txRepo, klineRepo, notifier)
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
        val txRepo = mockk<TransactionRepository>(relaxed = true)
        val klineRepo = mockk<KlineRepository>(relaxed = true)
        val notifier = mockk<DividendAlertNotifier>(relaxed = true)
        coEvery { gridRepo.observeAll() } returns flowOf(emptyList())
        coEvery { stockRepo.observeAllStocks() } returns flowOf(listOf(stock()))
        coEvery { stockRepo.observeAllStocksForSnapshot() } returns emptyList()
        coEvery { txRepo.observeAll() } returns flowOf(emptyList())
        // 三周期 BOLL：日(9/10/11)、周(8/10/12)、月(9/11/13)；股息 0.6/股
        coEvery { stockRepo.fetchBoll(any(), com.stock.dividend.data.repository.KlinePeriod.DAILY) } returns
            com.stock.dividend.data.repository.BollBand(10.0, 11.0, 9.0)
        coEvery { stockRepo.fetchBoll(any(), com.stock.dividend.data.repository.KlinePeriod.WEEKLY) } returns
            com.stock.dividend.data.repository.BollBand(10.0, 12.0, 8.0)
        coEvery { stockRepo.fetchBoll(any(), com.stock.dividend.data.repository.KlinePeriod.MONTHLY) } returns
            com.stock.dividend.data.repository.BollBand(11.0, 13.0, 9.0)
        coEvery { divRepo.observeDividends(any()) } returns flowOf(
            listOf(com.stock.dividend.data.local.entity.DividendEntity(id = "1", stockCode = "sh.600036", reportDate = "2024", cashPerShare = 0.6))
        )

        val vm = GridPlanViewModel(savedStateHandle(), gridRepo, stockRepo, divRepo, txRepo, klineRepo, notifier)
        vm.showGenerator()
        vm.onStockSelected("sh.600036")
        vm.onTargetYieldChanged("8")   // 股息底 7.5 < 起点 8
        vm.autoAnchor()
        advanceUntilIdle()

        // 买入起点 = min(日下轨9, 周下轨8, 月BOLL中轨11) = 8；资金用完位 = min(技术下轨8, 股息底7.5) = 7.5；
        // 参考上界 = 月BOLL上轨 13
        val state = vm.uiState.value
        assertThat(state.isAnchoring).isFalse()
        assertThat(state.anchorInfo).isNotNull()
        assertThat(state.anchorInfo?.basePrice).isEqualTo(8.0)
        assertThat(state.anchorInfo?.lowPrice).isEqualTo(7.5)
        assertThat(state.anchorInfo?.highPrice).isEqualTo(13.0)
        assertThat(state.basePriceInput).isEqualTo("8.00")
        assertThat(state.lowPriceInput).isEqualTo("7.50")
        assertThat(state.highPriceInput).isEqualTo("13.00")
        assertThat(state.anchorError).isNull()
    }

    @Test
    fun `autoAnchor reports error when data insufficient`() = runTest {
        val gridRepo = mockk<GridPlanRepository>(relaxed = true)
        val stockRepo = mockk<StockRepository>(relaxed = true)
        val divRepo = mockk<DividendRepository>(relaxed = true)
        val txRepo = mockk<TransactionRepository>(relaxed = true)
        val klineRepo = mockk<KlineRepository>(relaxed = true)
        val notifier = mockk<DividendAlertNotifier>(relaxed = true)
        coEvery { gridRepo.observeAll() } returns flowOf(emptyList())
        coEvery { stockRepo.observeAllStocks() } returns flowOf(listOf(stock()))
        coEvery { stockRepo.observeAllStocksForSnapshot() } returns emptyList()
        coEvery { txRepo.observeAll() } returns flowOf(emptyList())
        // BOLL 拉取失败（null）
        coEvery { stockRepo.fetchBoll(any(), any()) } returns null
        coEvery { divRepo.observeDividends(any()) } returns flowOf(emptyList())

        val vm = GridPlanViewModel(savedStateHandle(), gridRepo, stockRepo, divRepo, txRepo, klineRepo, notifier)
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
        val txRepo = mockk<TransactionRepository>(relaxed = true)
        val klineRepo = mockk<KlineRepository>(relaxed = true)
        val notifier = mockk<DividendAlertNotifier>(relaxed = true)
        coEvery { gridRepo.observeAll() } returns flowOf(emptyList())
        coEvery { stockRepo.observeAllStocks() } returns flowOf(emptyList())
        coEvery { stockRepo.observeAllStocksForSnapshot() } returns emptyList()
        coEvery { txRepo.observeAll() } returns flowOf(emptyList())

        val vm = GridPlanViewModel(savedStateHandle(), gridRepo, stockRepo, divRepo, txRepo, klineRepo, notifier)
        vm.showGenerator()
        // 不选标的直接锚定
        vm.onTargetYieldChanged("6")
        vm.autoAnchor()
        advanceUntilIdle()

        assertThat(vm.uiState.value.anchorError).contains("选择标的")
        assertThat(vm.uiState.value.anchorInfo).isNull()
    }

    /** 个股详情页入口：携带 code 创建 VM → 自动打开生成器、预选标的并触发锚定。 */
    @Test
    fun `initial stock code auto-opens generator and anchors`() = runTest {
        val gridRepo = mockk<GridPlanRepository>(relaxed = true)
        val stockRepo = mockk<StockRepository>(relaxed = true)
        val divRepo = mockk<DividendRepository>(relaxed = true)
        val txRepo = mockk<TransactionRepository>(relaxed = true)
        val klineRepo = mockk<KlineRepository>(relaxed = true)
        val notifier = mockk<DividendAlertNotifier>(relaxed = true)
        coEvery { gridRepo.observeAll() } returns flowOf(emptyList())
        coEvery { stockRepo.observeAllStocks() } returns flowOf(listOf(stock()))
        coEvery { stockRepo.observeAllStocksForSnapshot() } returns emptyList()
        coEvery { txRepo.observeAll() } returns flowOf(emptyList())
        // 三周期 BOLL：日(11/12/13)、周(10.5/11.5/12.5)、月(10/11/12)
        // 默认目标 6% → 股息底 10 < 起点 min(11, 10.5, 月BOLL中轨11) = 10.5
        coEvery { stockRepo.fetchBoll(any(), com.stock.dividend.data.repository.KlinePeriod.DAILY) } returns
            com.stock.dividend.data.repository.BollBand(12.0, 13.0, 11.0)
        coEvery { stockRepo.fetchBoll(any(), com.stock.dividend.data.repository.KlinePeriod.WEEKLY) } returns
            com.stock.dividend.data.repository.BollBand(11.5, 12.5, 10.5)
        coEvery { stockRepo.fetchBoll(any(), com.stock.dividend.data.repository.KlinePeriod.MONTHLY) } returns
            com.stock.dividend.data.repository.BollBand(11.0, 12.0, 10.0)
        coEvery { divRepo.observeDividends(any()) } returns flowOf(
            listOf(com.stock.dividend.data.local.entity.DividendEntity(id = "1", stockCode = "sh.600036", reportDate = "2024", cashPerShare = 0.6))
        )

        // 关键：携带 code 构造，模拟从个股详情页进入
        val vm = GridPlanViewModel(savedStateHandle("sh.600036"), gridRepo, stockRepo, divRepo, txRepo, klineRepo, notifier)
        // init 的 collector 需先跑到（发射自选股 → 触发自动打开+锚定）
        advanceUntilIdle()

        val state = vm.uiState.value
        assertThat(state.showGenerator).isTrue()                       // 生成器自动打开
        assertThat(state.selectedStockCode).isEqualTo("sh.600036")     // 自动预选该股
        assertThat(state.anchorInfo).isNotNull()                       // 自动锚定成功
        // 买入起点 = min(日下轨11, 周下轨10.5, 月BOLL中轨11) = 10.5
        assertThat(state.basePriceInput).isEqualTo("10.50")
        assertThat(state.anchorError).isNull()
    }

    /** 携带不存在的 code → 不触发自动打开（等用户手动操作）。 */
    @Test
    fun `unknown initial code does not auto-open generator`() = runTest {
        val gridRepo = mockk<GridPlanRepository>(relaxed = true)
        val stockRepo = mockk<StockRepository>(relaxed = true)
        val divRepo = mockk<DividendRepository>(relaxed = true)
        val txRepo = mockk<TransactionRepository>(relaxed = true)
        val klineRepo = mockk<KlineRepository>(relaxed = true)
        val notifier = mockk<DividendAlertNotifier>(relaxed = true)
        coEvery { gridRepo.observeAll() } returns flowOf(emptyList())
        coEvery { stockRepo.observeAllStocks() } returns flowOf(listOf(stock("sh.600000", "浦发银行")))
        coEvery { stockRepo.observeAllStocksForSnapshot() } returns emptyList()
        coEvery { txRepo.observeAll() } returns flowOf(emptyList())

        val vm = GridPlanViewModel(savedStateHandle("sz.999999"), gridRepo, stockRepo, divRepo, txRepo, klineRepo, notifier)
        advanceUntilIdle()

        assertThat(vm.uiState.value.showGenerator).isFalse()
        assertThat(vm.uiState.value.selectedStockCode).isEmpty()
    }

    /** 回归保护：编辑计划保存时保留原 createdAt（曾两分支都写 now 导致被刷新），并重置到档提醒状态。 */
    @Test
    fun `editing plan preserves createdAt and resets notify state`() = runTest {
        val gridRepo = mockk<GridPlanRepository>(relaxed = true)
        val stockRepo = mockk<StockRepository>(relaxed = true)
        val divRepo = mockk<DividendRepository>(relaxed = true)
        val txRepo = mockk<TransactionRepository>(relaxed = true)
        val klineRepo = mockk<KlineRepository>(relaxed = true)
        val notifier = mockk<DividendAlertNotifier>(relaxed = true)
        coEvery { gridRepo.observeAll() } returns flowOf(emptyList())
        coEvery { stockRepo.observeAllStocks() } returns flowOf(listOf(stock()))
        coEvery { stockRepo.observeAllStocksForSnapshot() } returns emptyList()
        coEvery { txRepo.observeAll() } returns flowOf(emptyList())

        val vm = GridPlanViewModel(savedStateHandle(), gridRepo, stockRepo, divRepo, txRepo, klineRepo, notifier)
        vm.editPlan(plan("1").copy(createdAt = 111L, lastNotifiedLevelPrice = 9.33))
        // 改一档参数再保存（档位变了，旧的已提醒档位作废）
        vm.onGridsChanged("5")
        vm.savePlan()
        advanceUntilIdle()

        coVerify {
            gridRepo.upsert(match {
                it.id == "1" &&
                    it.createdAt == 111L &&              // 原创建时间保留
                    it.lastNotifiedLevelPrice == null && // 档位参数已变，提醒状态重置
                    it.grids == 5
            })
        }
    }

    /** 到档提醒开关切换：只翻转 notifyEnabled，不动 updatedAt（避免开关把计划顶到列表顶部）。 */
    @Test
    fun `toggleNotify flips flag without touching updatedAt`() = runTest {
        val gridRepo = mockk<GridPlanRepository>(relaxed = true)
        val stockRepo = mockk<StockRepository>(relaxed = true)
        val divRepo = mockk<DividendRepository>(relaxed = true)
        val txRepo = mockk<TransactionRepository>(relaxed = true)
        val klineRepo = mockk<KlineRepository>(relaxed = true)
        val notifier = mockk<DividendAlertNotifier>(relaxed = true)
        coEvery { gridRepo.observeAll() } returns flowOf(emptyList())
        coEvery { stockRepo.observeAllStocks() } returns flowOf(emptyList())
        coEvery { stockRepo.observeAllStocksForSnapshot() } returns emptyList()
        coEvery { txRepo.observeAll() } returns flowOf(emptyList())

        val vm = GridPlanViewModel(savedStateHandle(), gridRepo, stockRepo, divRepo, txRepo, klineRepo, notifier)
        vm.toggleNotify(plan("1").copy(notifyEnabled = true, updatedAt = 555L))
        advanceUntilIdle()

        coVerify {
            gridRepo.upsert(match {
                it.notifyEnabled == false && it.updatedAt == 555L
            })
        }
    }
    // ── 一键重锚定 ─────────────────────────────────────

    /** 重锚定成功：产出新旧三价对比弹窗数据（targetYield 用计划存档值）。 */
    @Test
    fun `reanchorPlan produces diff dialog`() = runTest {
        val gridRepo = mockk<GridPlanRepository>(relaxed = true)
        val stockRepo = mockk<StockRepository>(relaxed = true)
        val divRepo = mockk<DividendRepository>(relaxed = true)
        val txRepo = mockk<TransactionRepository>(relaxed = true)
        val klineRepo = mockk<KlineRepository>(relaxed = true)
        val notifier = mockk<DividendAlertNotifier>(relaxed = true)
        coEvery { gridRepo.observeAll() } returns flowOf(emptyList())
        coEvery { stockRepo.observeAllStocks() } returns flowOf(emptyList())
        coEvery { stockRepo.observeAllStocksForSnapshot() } returns emptyList()
        coEvery { txRepo.observeAll() } returns flowOf(emptyList())
        // 三周期 BOLL：日(11/12/13)、周(10.5/11.5/12.5)、月(10/11/12)
        coEvery { stockRepo.fetchBoll(any(), com.stock.dividend.data.repository.KlinePeriod.DAILY) } returns
            com.stock.dividend.data.repository.BollBand(12.0, 13.0, 11.0)
        coEvery { stockRepo.fetchBoll(any(), com.stock.dividend.data.repository.KlinePeriod.WEEKLY) } returns
            com.stock.dividend.data.repository.BollBand(11.5, 12.5, 10.5)
        coEvery { stockRepo.fetchBoll(any(), com.stock.dividend.data.repository.KlinePeriod.MONTHLY) } returns
            com.stock.dividend.data.repository.BollBand(11.0, 12.0, 10.0)
        coEvery { divRepo.observeDividends(any()) } returns flowOf(
            listOf(com.stock.dividend.data.local.entity.DividendEntity(id = "1", stockCode = "sh.600036", reportDate = "2024", cashPerShare = 0.6))
        )

        val vm = GridPlanViewModel(savedStateHandle(), gridRepo, stockRepo, divRepo, txRepo, klineRepo, notifier)
        // 计划存档目标股息率 6% → 股息底 10；起点 = min(11, 10.5, 月中轨 11) = 10.5
        vm.reanchorPlan(plan("1").copy(targetYieldPercent = 6.0))
        advanceUntilIdle()

        val diff = vm.uiState.value.reanchorDiff
        assertThat(diff).isNotNull()
        assertThat(diff!!.newBasePrice).isEqualTo(10.5)
        assertThat(diff.targetYieldUsed).isEqualTo(6.0)
        assertThat(vm.uiState.value.isReanchoring).isFalse()
    }

    /** 数据不足（BOLL 全失败）→ 可见错误，不产弹窗。 */
    @Test
    fun `reanchorPlan reports error when data insufficient`() = runTest {
        val gridRepo = mockk<GridPlanRepository>(relaxed = true)
        val stockRepo = mockk<StockRepository>(relaxed = true)
        val divRepo = mockk<DividendRepository>(relaxed = true)
        val txRepo = mockk<TransactionRepository>(relaxed = true)
        val klineRepo = mockk<KlineRepository>(relaxed = true)
        val notifier = mockk<DividendAlertNotifier>(relaxed = true)
        coEvery { gridRepo.observeAll() } returns flowOf(emptyList())
        coEvery { stockRepo.observeAllStocks() } returns flowOf(emptyList())
        coEvery { stockRepo.observeAllStocksForSnapshot() } returns emptyList()
        coEvery { txRepo.observeAll() } returns flowOf(emptyList())
        coEvery { stockRepo.fetchBoll(any(), any()) } returns null
        coEvery { divRepo.observeDividends(any()) } returns flowOf(emptyList())

        val vm = GridPlanViewModel(savedStateHandle(), gridRepo, stockRepo, divRepo, txRepo, klineRepo, notifier)
        vm.reanchorPlan(plan("1"))
        advanceUntilIdle()

        assertThat(vm.uiState.value.reanchorDiff).isNull()
        assertThat(vm.uiState.value.reanchorError).isNotNull()
    }

    /** 确认重锚定：保存新三价，保留 createdAt 并重置到档提醒状态。 */
    @Test
    fun `confirmReanchor saves new prices preserving createdAt`() = runTest {
        val gridRepo = mockk<GridPlanRepository>(relaxed = true)
        val stockRepo = mockk<StockRepository>(relaxed = true)
        val divRepo = mockk<DividendRepository>(relaxed = true)
        val txRepo = mockk<TransactionRepository>(relaxed = true)
        val klineRepo = mockk<KlineRepository>(relaxed = true)
        val notifier = mockk<DividendAlertNotifier>(relaxed = true)
        coEvery { gridRepo.observeAll() } returns flowOf(emptyList())
        coEvery { stockRepo.observeAllStocks() } returns flowOf(emptyList())
        coEvery { stockRepo.observeAllStocksForSnapshot() } returns emptyList()
        coEvery { txRepo.observeAll() } returns flowOf(emptyList())
        // 单周期 BOLL（日）+ 分红即可锚定成功
        coEvery { stockRepo.fetchBoll(any(), com.stock.dividend.data.repository.KlinePeriod.DAILY) } returns
            com.stock.dividend.data.repository.BollBand(11.0, 12.0, 10.0)
        coEvery { stockRepo.fetchBoll(any(), com.stock.dividend.data.repository.KlinePeriod.WEEKLY) } returns null
        coEvery { stockRepo.fetchBoll(any(), com.stock.dividend.data.repository.KlinePeriod.MONTHLY) } returns null
        coEvery { divRepo.observeDividends(any()) } returns flowOf(
            listOf(com.stock.dividend.data.local.entity.DividendEntity(id = "1", stockCode = "sh.600036", reportDate = "2024", cashPerShare = 0.6))
        )

        val vm = GridPlanViewModel(savedStateHandle(), gridRepo, stockRepo, divRepo, txRepo, klineRepo, notifier)
        val original = plan("1").copy(createdAt = 111L, lastNotifiedLevelPrice = 9.33, targetYieldPercent = 8.0)
        vm.reanchorPlan(original)
        advanceUntilIdle()
        assertThat(vm.uiState.value.reanchorDiff).isNotNull()

        vm.confirmReanchor()
        advanceUntilIdle()
        coVerify {
            gridRepo.upsert(match {
                it.id == "1" &&
                    it.createdAt == 111L &&              // 原创建时间保留
                    it.lastNotifiedLevelPrice == null && // 到档提醒状态重置
                    it.basePrice == 10.0 &&              // 日下轨起点 min(10, 无, 无)=10
                    it.lowPrice == 7.5 &&                 // 股息底 0.6/8% = 7.5
                    it.targetYieldPercent == 8.0
            })
        }
        assertThat(vm.uiState.value.reanchorDiff).isNull()
    }

    // ── 回测 ───────────────────────────────────────────

    /** 回测：K 线可用 → 结果按 plan id 落位；K 线为空 → 可见错误。 */
    @Test
    fun `backtestPlan stores result or error by plan id`() = runTest {
        val gridRepo = mockk<GridPlanRepository>(relaxed = true)
        val stockRepo = mockk<StockRepository>(relaxed = true)
        val divRepo = mockk<DividendRepository>(relaxed = true)
        val txRepo = mockk<TransactionRepository>(relaxed = true)
        val klineRepo = mockk<KlineRepository>(relaxed = true)
        val notifier = mockk<DividendAlertNotifier>(relaxed = true)
        coEvery { gridRepo.observeAll() } returns flowOf(emptyList())
        coEvery { stockRepo.observeAllStocks() } returns flowOf(emptyList())
        coEvery { stockRepo.observeAllStocksForSnapshot() } returns emptyList()
        coEvery { txRepo.observeAll() } returns flowOf(emptyList())
        // 两根日 K：首日 9.5 → base 档（10.0）触发；次日 8.5 → 9.33/8.67 逐档
        coEvery { klineRepo.fetchKlines(any(), KlinePeriod.DAILY, 250) } returns listOf(
            KlineBar(date = "2026-01-02", open = 9.5, close = 9.5, high = 9.5, low = 9.5, volume = 1.0),
            KlineBar(date = "2026-01-05", open = 8.5, close = 8.5, high = 8.5, low = 8.5, volume = 1.0)
        )

        val vm = GridPlanViewModel(savedStateHandle(), gridRepo, stockRepo, divRepo, txRepo, klineRepo, notifier)
        vm.backtestPlan(plan("1"))
        advanceUntilIdle()
        assertThat(vm.uiState.value.backtestResults["1"]).isNotNull()
        assertThat(vm.uiState.value.backtestResults["1"]!!.triggeredCount).isEqualTo(3)  // 10/9.33/8.67
        assertThat(vm.uiState.value.backtestingIds).isEmpty()

        // K 线为空 → 错误可见
        coEvery { klineRepo.fetchKlines(any(), KlinePeriod.DAILY, 250) } returns emptyList()
        vm.backtestPlan(plan("2"))
        advanceUntilIdle()
        assertThat(vm.uiState.value.backtestErrors["2"]).isNotNull()
    }

    // ── 弹药库 / 持仓口径 / 等比 / 权限 ────────────────

    /** 列表装配：弹药库汇总 + 实际持仓股数 + 等比计划的档位几何。 */
    @Test
    fun `items expose ammo summary holding shares and geometric levels`() = runTest {
        val gridRepo = mockk<GridPlanRepository>(relaxed = true)
        val stockRepo = mockk<StockRepository>(relaxed = true)
        val divRepo = mockk<DividendRepository>(relaxed = true)
        val txRepo = mockk<TransactionRepository>(relaxed = true)
        val klineRepo = mockk<KlineRepository>(relaxed = true)
        val notifier = mockk<DividendAlertNotifier>(relaxed = true)
        val geom = plan("1").copy(basePrice = 16.0, lowPrice = 4.0, highPrice = 20.0, grids = 3, gridType = "GEOM")
        coEvery { gridRepo.observeAll() } returns flowOf(listOf(geom))
        coEvery { stockRepo.observeAllStocks() } returns flowOf(
            listOf(stock().copy(shares = 500))
        )
        coEvery { stockRepo.observeAllStocksForSnapshot() } returns emptyList()
        coEvery { txRepo.observeAll() } returns flowOf(emptyList())

        val vm = GridPlanViewModel(savedStateHandle(), gridRepo, stockRepo, divRepo, txRepo, klineRepo, notifier)
        advanceUntilIdle()

        val item = vm.uiState.value.items.firstOrNull()
        assertThat(item).isNotNull()
        // 等比档位 4/8/16（比值 2）
        assertThat(item!!.result.levels.map { it.price }).containsExactly(4.0, 8.0, 16.0).inOrder()
        // 实际持仓 500 股
        assertThat(item.holdingShares).isEqualTo(500)
        // 弹药库：单计划 10 万资金
        assertThat(vm.uiState.value.ammoSummary).isNotNull()
        assertThat(vm.uiState.value.ammoSummary!!.totalCapital).isEqualTo(100000.0)
    }

    /** 通知权限被关（canNotify=false）→ notificationBlocked=true 可见。 */
    @Test
    fun `notification blocked flag exposed when permission denied`() = runTest {
        val gridRepo = mockk<GridPlanRepository>(relaxed = true)
        val stockRepo = mockk<StockRepository>(relaxed = true)
        val divRepo = mockk<DividendRepository>(relaxed = true)
        val txRepo = mockk<TransactionRepository>(relaxed = true)
        val klineRepo = mockk<KlineRepository>(relaxed = true)
        val notifier = mockk<DividendAlertNotifier>(relaxed = true)
        coEvery { notifier.canNotify() } returns false
        coEvery { gridRepo.observeAll() } returns flowOf(emptyList())
        coEvery { stockRepo.observeAllStocks() } returns flowOf(emptyList())
        coEvery { stockRepo.observeAllStocksForSnapshot() } returns emptyList()
        coEvery { txRepo.observeAll() } returns flowOf(emptyList())

        val vm = GridPlanViewModel(savedStateHandle(), gridRepo, stockRepo, divRepo, txRepo, klineRepo, notifier)
        advanceUntilIdle()
        assertThat(vm.uiState.value.notificationBlocked).isTrue()
    }
}