package com.stock.dividend.viewmodel

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.local.entity.StockEntity
import com.stock.dividend.data.repository.BollBand
import com.stock.dividend.data.repository.DividendIncomeRepository
import com.stock.dividend.data.repository.GridPlanRepository
import com.stock.dividend.data.repository.HealthLevel
import com.stock.dividend.data.repository.IndexQuote
import com.stock.dividend.data.repository.MarketListItem
import com.stock.dividend.data.repository.NameWeight
import com.stock.dividend.data.repository.PortfolioDiagnosisAssembler
import com.stock.dividend.data.repository.PortfolioRiskDiagnosis
import com.stock.dividend.data.repository.QuoteSnapshot
import com.stock.dividend.data.plane.MarketDataPlane
import com.stock.dividend.data.repository.MarketDataRepository
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

    private val marketDataPlane = mockk<MarketDataPlane>(relaxed = true)
    private val gridPlanRepository = mockk<GridPlanRepository>(relaxed = true)
    private val briefingCoordinator = mockk<TodayBriefingCoordinator>(relaxed = true)
    private val diagnosisAssembler = mockk<PortfolioDiagnosisAssembler>(relaxed = true)
    private val dividendIncomeRepository = mockk<DividendIncomeRepository>(relaxed = true)
    private val transactionRepository: com.stock.dividend.data.repository.TransactionRepository = mockk(relaxed = true)
    private val strategyPlanRepository = mockk<com.stock.dividend.data.repository.StrategyPlanRepository>(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        // 订阅类 Flow 显式 stub 成空，避免 relaxed 返回 mock Flow 在 collect 时抛错
        coEvery { marketDataPlane.getQuoteSnapshots(any(), any()) } returns emptyMap()
        coEvery { marketDataPlane.get10YBondYield(any()) } returns 2.5
        coEvery { marketDataPlane.getAllDividendsWithExDate() } returns emptyList()
        coEvery { marketDataPlane.getBoll(any(), any()) } returns null
        coEvery { marketDataPlane.getIndexQuotes() } returns emptyList()
        coEvery { marketDataPlane.getIndustryList(any(), any()) } returns emptyList()
        every { marketDataPlane.observeAllStocks() } returns flowOf(emptyList())
        every { gridPlanRepository.observeAll() } returns flowOf(emptyList())
        every { strategyPlanRepository.observeAll() } returns flowOf(emptyList())
        every { dividendIncomeRepository.observeTotalByYear(any()) } returns flowOf(0.0)
        every { dividendIncomeRepository.observeForecastTotal() } returns flowOf(0.0)
        coEvery { diagnosisAssembler.assemble(any(), any()) } returns null
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun makeVm() = TodayViewModel(
        marketDataPlane, gridPlanRepository, strategyPlanRepository, briefingCoordinator,
        diagnosisAssembler, dividendIncomeRepository, transactionRepository,
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
        every { marketDataPlane.observeAllStocks() } returns flowOf(listOf(stock))
        coEvery { marketDataPlane.getQuoteSnapshots(any(), any()) } returns
            mapOf("sh.600000" to QuoteSnapshot("sh.600000", price = 8.8, prevClose = 9.0))
        coEvery { marketDataPlane.getBoll(any(), any()) } returns BollBand(middle = 10.0, upper = 11.0, lower = 9.0)
        coEvery { briefingCoordinator.read(any()) } returns null
        val vm = makeVm()
        advanceUntilIdle()
        // price 8.8 ≤ lower 9.0 → 应触发「跌破BOLL下轨」信号
        coVerify(atLeast = 1) { marketDataPlane.getBoll(any(), any()) }
        // marketValue≈880 证明 price 生效（fetchQuoteSnapshots mock ok）；浮点用 tolerance
        assertThat(vm.uiState.value.marketValue).isWithin(0.01).of(880.0)
        assertThat(vm.uiState.value.signals.any { it.title.contains("BOLL下轨") }).isTrue()
    }

    // ── 市场环境（2026-08-15 新增） ──

    private fun index(code: String, name: String, changePct: Double) = IndexQuote(
        code = code, name = name, price = 100.0, changePct = changePct,
        prevClose = 99.0, high = 101.0, low = 98.0, open = 99.5, amount = null,
    )

    private fun industry(name: String, changePct: Double?, inflow: Double? = null) = MarketListItem(
        code = null, name = name, price = null, changePct = changePct,
        pe = null, pb = null, totalMarketCap = null, turnoverRate = null,
        industry = null, mainNetInflow = inflow, mainNetInflowPct = null,
        leaderName = null, leaderCode = null, leaderChangePct = null,
    )

    @Test
    fun marketEnvironment_filtersToFourIndicesAndSplitsMood() = runTest {
        coEvery { briefingCoordinator.read(any()) } returns null
        // 7 指数里只有 4 个目标应保留，且按固定顺序（上证/深证/沪深300/创业板）
        coEvery { marketDataPlane.getIndexQuotes() } returns listOf(
            index("000688", "科创50", 0.5),
            index("000300", "沪深300", -0.3),
            index("000001", "上证指数", 1.2),
            index("399006", "创业板指", 2.0),
            index("000905", "中证500", -0.1),
            index("399001", "深证成指", 0.8),
        )
        coEvery {
            marketDataPlane.getIndustryList(MarketDataRepository.SortBy.CHANGE, any())
        } returns listOf(
            industry("银行", 2.5), industry("白酒", 5.0),
            industry("煤炭", -1.0), industry("电力", -3.0), industry("家电", 0.5),
        )
        coEvery {
            marketDataPlane.getIndustryList(MarketDataRepository.SortBy.INFLOW, any())
        } returns listOf(industry("银行", 1.0, inflow = 12.0))

        val vm = makeVm()
        advanceUntilIdle()
        val s = vm.uiState.value
        assertThat(s.indices.map { it.code }).containsExactly("000001", "399001", "000300", "399006").inOrder()
        // 相对领涨/领跌（默认 Top3，与 get_market_sentiment 工具同口径）
        assertThat(s.marketMood.topGainers.map { it.name }).containsExactly("白酒", "银行", "家电").inOrder()
        assertThat(s.marketMood.topLosers.map { it.name }).containsExactly("电力", "煤炭", "家电").inOrder()
        assertThat(s.inflowIndustries.map { it.name }).containsExactly("银行")
    }

    @Test
    fun emptyHoldings_marketStillPopulatedButNoDiagnosis() = runTest {
        coEvery { briefingCoordinator.read(any()) } returns null
        coEvery { marketDataPlane.getIndexQuotes() } returns listOf(index("000001", "上证指数", 1.0))

        val vm = makeVm()
        advanceUntilIdle()
        val s = vm.uiState.value
        // 无持仓：市场环境照常（看大盘不需要持仓），体检/信号为空
        assertThat(s.hasHoldings).isFalse()
        assertThat(s.indices).hasSize(1)
        assertThat(s.diagnosis).isNull()
        assertThat(s.healthGrade).isNull()
    }

    // ── 组合体检 ──

    private fun fakeDiagnosis() = PortfolioRiskDiagnosis(
        holdingCount = 3, totalMarketValue = 100_000.0,
        industryHhi = 3000.0, industryCr3 = 80.0,
        topIndustries = listOf(NameWeight("银行", 60.0)),
        stockHhi = 4000.0, stockCr1 = 40.0, stockCr3 = 90.0,
        topHoldings = listOf(NameWeight("A", 40.0)),
        dividendSourceCr3 = 85.0, fragileDividendWeightPct = 10.0, highPayoutCodes = listOf("X"),
        weightedDividendYieldPct = 4.0, bondYield10yPct = 3.0, yieldSpreadPct = 1.0,
        suggestions = listOf("组合结构均衡，未触发明显风险规则"),
    )

    @Test
    fun diagnosis_assembledWithRefreshedPricesAndGraded() = runTest {
        val stock = StockEntity(code = "sh.600000", name = "T", marketCode = "1", shares = 100, costPerShare = 10.0)
        every { marketDataPlane.observeAllStocks() } returns flowOf(listOf(stock))
        coEvery { marketDataPlane.getQuoteSnapshots(any(), any()) } returns
            mapOf("sh.600000" to QuoteSnapshot("sh.600000", price = 10.0, prevClose = 9.8))
        coEvery { diagnosisAssembler.assemble(any(), any()) } returns fakeDiagnosis()
        coEvery { briefingCoordinator.read(any()) } returns null
        val vm = makeVm()
        advanceUntilIdle()
        val s = vm.uiState.value
        assertThat(s.diagnosis).isNotNull()
        // 分级由 grade 纯函数产出：HHI 3000>2500 且 CR1 40%>30% 双超限 → 集中 BAD；利差 1.0 → 估值 OK
        assertThat(s.healthGrade!!.concentration).isEqualTo(HealthLevel.BAD)
        assertThat(s.healthGrade!!.valuation).isEqualTo(HealthLevel.OK)
        // 装配器收到的是本页已刷新的现价（不重复拉价）
        coVerify(atLeast = 1) { diagnosisAssembler.assemble(any(), match { it["sh.600000"] == 10.0 }) }
    }

    // ── 股息现金流 ──

    @Test
    fun dividendCashflow_receivedAndForecastLoaded() = runTest {
        every { dividendIncomeRepository.observeTotalByYear(any()) } returns flowOf(1200.0)
        every { dividendIncomeRepository.observeForecastTotal() } returns flowOf(3000.0)
        coEvery { briefingCoordinator.read(any()) } returns null
        val vm = makeVm()
        advanceUntilIdle()
        assertThat(vm.uiState.value.yearDividendReceived).isEqualTo(1200.0)
        assertThat(vm.uiState.value.yearDividendForecast).isEqualTo(3000.0)
    }
}
