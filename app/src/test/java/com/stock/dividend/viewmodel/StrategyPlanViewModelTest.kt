package com.stock.dividend.viewmodel

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.local.entity.STRATEGY_TYPE_TAKE_PROFIT
import com.stock.dividend.data.local.entity.StockEntity
import com.stock.dividend.data.local.entity.StrategyPlanEntity
import com.stock.dividend.data.local.entity.TransactionEntity
import com.stock.dividend.data.notification.DividendAlertNotifier
import com.stock.dividend.data.plane.MarketDataPlane
import com.stock.dividend.data.repository.KlineBar
import com.stock.dividend.data.repository.KlinePeriod
import com.stock.dividend.data.repository.StrategyAction
import com.stock.dividend.data.repository.StrategyInputAssembler
import com.stock.dividend.data.repository.StrategyParams
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

    private fun maDcaPlan(id: String = "s1", code: String = "sh.510880") = StrategyPlanEntity(
        id = id, stockCode = code, stockName = "红利ETF",
        maPeriod = 250, sellHalfPercent = 7.5, sellAllPercent = 15.0, dcaAmount = 1000.0
    )

    private fun takeProfitPlan(id: String = "t1", code: String = "sh.600036") = StrategyPlanEntity(
        id = id, stockCode = code, stockName = "招商银行",
        strategyType = STRATEGY_TYPE_TAKE_PROFIT,
        params = StrategyParams.encode(StrategyParams.TakeProfit(halfGainPercent = 15.0, allGainPercent = 25.0))
    )

    private fun stock(code: String = "sh.510880", name: String = "红利ETF") = StockEntity(
        code = code, name = name, marketCode = "1", shares = 0, costPerShare = 0.0
    )

    /** 250 根收盘价全为 10 → 年线 10；配合不同现价驱动不同信号。 */
    private val flatClosesKlines = List(250) { KlineBar("d$it", 10.0, 10.0, 10.0, 10.0, 1000.0) }

    private fun savedStateHandle(code: String? = null) = androidx.lifecycle.SavedStateHandle(
        mutableMapOf<String, Any>().apply { code?.let { put("code", it) } }
    )

    private class TestKit(
        val strategyRepo: StrategyPlanRepository,
        val plane: MarketDataPlane,
        val txRepo: TransactionRepository,
        val assembler: StrategyInputAssembler
    )

    /** 通用 mock 装配：真实装配器 + mock 平面/流水仓库。 */
    private fun testKit(
        plans: List<StrategyPlanEntity>,
        stocks: List<StockEntity>,
        prices: Map<String, Double>,
        transactions: List<TransactionEntity> = emptyList(),
        klines: Map<String, List<KlineBar>> = emptyMap(),
        builder: suspend TestKit.() -> Unit = {}
    ): TestKit {
        val strategyRepo = mockk<StrategyPlanRepository>(relaxed = true)
        val plane = mockk<MarketDataPlane>(relaxed = true)
        val txRepo = mockk<TransactionRepository>(relaxed = true)
        coEvery { strategyRepo.observeAll() } returns flowOf(plans)
        coEvery { plane.observeAllStocks() } returns flowOf(stocks)
        coEvery { txRepo.observeAll() } returns flowOf(transactions)
        coEvery { txRepo.getAll() } returns transactions
        coEvery { plane.getPricesForCodes(any()) } returns prices
        klines.forEach { (code, bars) ->
            coEvery { plane.getKlines(code, KlinePeriod.DAILY, any()) } returns bars
        }
        val kit = TestKit(strategyRepo, plane, txRepo, StrategyInputAssembler(plane, txRepo))
        return kit
    }

    @Test
    fun maDcaPlan_rendersUnifiedEvaluation() = runTest {
        val kit = testKit(
            plans = listOf(maDcaPlan()),
            stocks = listOf(stock()),
            prices = mapOf("sh.510880" to 10.8),   // 高于年线 8% ≥ 7.5% → 卖一半
            transactions = listOf(
                TransactionEntity(stockCode = "sh.510880", type = "BUY", shares = 500, price = 9.0, date = "2026-01-05")
            ),
            klines = mapOf("sh.510880" to flatClosesKlines)
        )
        val notifier = mockk<DividendAlertNotifier>(relaxed = true)
        val vm = StrategyPlanViewModel(savedStateHandle(), kit.strategyRepo, kit.plane, kit.txRepo, kit.assembler, notifier)
        advanceUntilIdle()
        val item = vm.uiState.value.items.first()
        assertThat(item.evaluation).isNotNull()
        assertThat(item.evaluation!!.action).isEqualTo(StrategyAction.SELL_HALF)
        assertThat(item.evaluation!!.sellShares).isEqualTo(200)
        assertThat(item.evaluation!!.metrics).isNotEmpty()
    }

    @Test
    fun takeProfitPlan_evaluatesFromParams() = runTest {
        val kit = testKit(
            plans = listOf(takeProfitPlan()),
            stocks = listOf(stock("sh.600036", "招商银行")),
            prices = mapOf("sh.600036" to 12.6),   // 成本 10 → +26% ≥ 25% 清仓
            transactions = listOf(
                TransactionEntity(stockCode = "sh.600036", type = "BUY", shares = 500, price = 10.0, date = "2026-01-05")
            )
        )
        val notifier = mockk<DividendAlertNotifier>(relaxed = true)
        val vm = StrategyPlanViewModel(savedStateHandle(), kit.strategyRepo, kit.plane, kit.txRepo, kit.assembler, notifier)
        advanceUntilIdle()
        val item = vm.uiState.value.items.first()
        assertThat(item.evaluation!!.action).isEqualTo(StrategyAction.SELL_ALL)
        assertThat(item.evaluation!!.sellShares).isEqualTo(500)
        assertThat(item.evaluation!!.notifyTier).isEqualTo("ALL")
    }

    @Test
    fun insufficientData_evaluationNullNoCrash() = runTest {
        val kit = testKit(
            plans = listOf(maDcaPlan()),
            stocks = listOf(stock()),
            prices = mapOf("sh.510880" to 10.0),
            klines = emptyMap()   // 日线拉不到
        )
        val notifier = mockk<DividendAlertNotifier>(relaxed = true)
        val vm = StrategyPlanViewModel(savedStateHandle(), kit.strategyRepo, kit.plane, kit.txRepo, kit.assembler, notifier)
        advanceUntilIdle()
        assertThat(vm.uiState.value.items).hasSize(1)
        assertThat(vm.uiState.value.items[0].evaluation).isNull()
        assertThat(vm.uiState.value.isLoading).isFalse()
    }

    @Test
    fun savePlan_validatesAndPersistsBothParamKinds() = runTest {
        val kit = testKit(plans = emptyList(), stocks = listOf(stock()), prices = emptyMap())
        val notifier = mockk<DividendAlertNotifier>(relaxed = true)
        val vm = StrategyPlanViewModel(savedStateHandle(), kit.strategyRepo, kit.plane, kit.txRepo, kit.assembler, notifier)
        advanceUntilIdle()

        // 未选标的 → 报错不保存
        vm.showEditor()
        vm.savePlan()
        assertThat(vm.uiState.value.saveError).isNotNull()
        coVerify(exactly = 0) { kit.strategyRepo.upsert(any()) }

        // MA_DCA 非法参数（清仓 ≤ 卖半）→ 报错
        vm.onStockSelected("sh.510880")
        vm.onSellAllChanged("7.0")
        vm.savePlan()
        assertThat(vm.uiState.value.saveError).isNotNull()
        coVerify(exactly = 0) { kit.strategyRepo.upsert(any()) }

        // 合法 MA_DCA → 保存成功
        vm.onSellAllChanged("15")
        vm.savePlan()
        advanceUntilIdle()
        assertThat(vm.uiState.value.showEditor).isFalse()
        val saved = slot<StrategyPlanEntity>()
        coVerify { kit.strategyRepo.upsert(capture(saved)) }
        assertThat(saved.captured.strategyType).isEqualTo(com.stock.dividend.data.local.entity.STRATEGY_TYPE_MA_DCA)
        assertThat(saved.captured.maPeriod).isEqualTo(250)

        // params 类型（目标止盈）：切换类型重置默认输入 → 填标的保存
        vm.showEditor()
        vm.onStrategyTypeChanged(STRATEGY_TYPE_TAKE_PROFIT)
        vm.onStockSelected("sh.510880")
        vm.savePlan()
        advanceUntilIdle()
        assertThat(vm.uiState.value.saveError).isNull()
        assertThat(vm.uiState.value.showEditor).isFalse()
        // 第二次 upsert 为 TAKE_PROFIT：params 列非空且解码等于默认参数
        var takeProfitSaved = false
        coVerify(atLeast = 1) {
            kit.strategyRepo.upsert(match { entity ->
                if (entity.strategyType == STRATEGY_TYPE_TAKE_PROFIT) {
                    takeProfitSaved = StrategyParams.decodeTakeProfit(entity.params) == StrategyParams.TakeProfit()
                    true
                } else false
            })
        }
        assertThat(takeProfitSaved).isTrue()
    }

    @Test
    fun initialStockCode_autoOpensEditorWithSelection() = runTest {
        val kit = testKit(plans = emptyList(), stocks = listOf(stock()), prices = emptyMap())
        val notifier = mockk<DividendAlertNotifier>(relaxed = true)
        val vm = StrategyPlanViewModel(savedStateHandle("sh.510880"), kit.strategyRepo, kit.plane, kit.txRepo, kit.assembler, notifier)
        advanceUntilIdle()
        assertThat(vm.uiState.value.showEditor).isTrue()
        assertThat(vm.uiState.value.selectedStockCode).isEqualTo("sh.510880")
    }
}
