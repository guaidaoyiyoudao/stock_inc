package com.stock.dividend.viewmodel

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.local.dao.DividendDao
import com.stock.dividend.data.local.dao.TransactionDao
import com.stock.dividend.data.local.entity.DividendEntity
import com.stock.dividend.data.local.entity.EXPENSE_PERIOD_MONTHLY
import com.stock.dividend.data.local.entity.EXPENSE_PERIOD_YEARLY
import com.stock.dividend.data.local.entity.LivingExpenseItemEntity
import com.stock.dividend.data.local.entity.StockEntity
import com.stock.dividend.data.local.entity.TransactionEntity
import com.stock.dividend.data.notification.NotificationCheckCoordinator
import com.stock.dividend.data.repository.BollBand
import com.stock.dividend.data.repository.BondYieldRepository
import com.stock.dividend.data.repository.DividendThresholds
import com.stock.dividend.data.repository.Fundamentals
import com.stock.dividend.data.repository.FundamentalsCacheRepository
import com.stock.dividend.data.repository.HoldingAction
import com.stock.dividend.data.repository.KlinePeriod
import com.stock.dividend.data.repository.LlmAnalysis
import com.stock.dividend.data.repository.LlmAnalysisRepository
import com.stock.dividend.data.repository.LlmAnalysisResult
import com.stock.dividend.data.repository.LlmAnalysisState
import com.stock.dividend.data.repository.LivingExpenseRepository
import com.stock.dividend.data.repository.NotificationRuleRepository
import com.stock.dividend.data.repository.PortfolioLlmInput
import com.stock.dividend.data.repository.QuoteSnapshot
import com.stock.dividend.data.repository.StockRepository
import com.stock.dividend.data.repository.StockLlmInput
import com.stock.dividend.data.repository.TradeStrategyRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PortfolioViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val stockRepository: StockRepository = mockk()
    private val dividendDao: DividendDao = mockk()
    private val livingExpenseRepository: LivingExpenseRepository = mockk()
    private val transactionDao: TransactionDao = mockk()
    private val notificationCheckCoordinator: NotificationCheckCoordinator = mockk(relaxed = true)
    private val notificationRuleRepository: NotificationRuleRepository = mockk()
    private val llmAnalysisRepository: LlmAnalysisRepository = mockk()
    private val tradeStrategyRepository: TradeStrategyRepository = mockk {
        coEvery { activeStrategies() } returns emptyList()
    }
    private val fundamentalsCacheRepository: FundamentalsCacheRepository = mockk {
        coEvery { getFundamentals(any(), any()) } returns null
    }
    private val bondYieldRepository: BondYieldRepository = mockk {
        coEvery { fetch10YBondYield(any()) } returns BondYieldRepository.DEFAULT_YIELD
    }
    // Robolectric 提供真实可用的 Context + SharedPreferences，不再需要 mockk 整条 prefs 链
    private lateinit var context: Context

    private val stocksFlow = MutableStateFlow<List<StockEntity>>(emptyList())
    private val livingExpensesFlow = MutableStateFlow<List<LivingExpenseItemEntity>>(emptyList())

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
        every { notificationRuleRepository.observeEvalThresholds() } returns
            MutableStateFlow(DividendThresholds())
        every { stockRepository.observeAllStocks() } returns stocksFlow
        every { stockRepository.observeAllStockTags() } returns MutableStateFlow(emptyList())
        every { stockRepository.observeAllTags() } returns MutableStateFlow(emptyList())
        every { stockRepository.observeIndustryTargets() } returns MutableStateFlow(emptyList())
        every { dividendDao.observeByStock(any()) } returns MutableStateFlow(emptyList())
        every { livingExpenseRepository.observeExpenses() } returns livingExpensesFlow
        coEvery { stockRepository.getIndustryTargets() } returns emptyList()
        coEvery { stockRepository.fetchQuoteSnapshots(any()) } returns emptyMap()
        coEvery { stockRepository.getCachedPrices(any()) } returns emptyMap()
        coEvery { stockRepository.fetchBoll(any()) } returns null
        coEvery { stockRepository.fetchBoll(any(), any()) } returns null
        coEvery { transactionDao.getByStock(any()) } returns emptyList()
        // 已实现盈亏 collector 订阅全量交易流水；默认返回空列表（无卖出 → 无已实现盈亏）。
        every { transactionDao.observeAll() } returns MutableStateFlow(emptyList())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `empty holdings produce empty items`() = runTest {
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.items).isEmpty()
        assertThat(viewModel.uiState.value.holdingsMarketValue).isEqualTo(0.0)
    }

    /**
     * 回归：刷新失败（fetchQuoteSnapshots 返回空 map，模拟网络异常被 StockRepository 吞掉）
     * 时 isLoading 必须复位为 false，否则 TopAppBar 刷新按钮会因 enabled=!isRefreshing
     * 被永久禁用，呈现"一直转圈卡死"。
     */
    @Test
    fun `refresh failure resets isLoading so button is not stuck`() = runTest {
        stocksFlow.value = listOf(
            stock("sz.000001", shares = 100, costPerShare = 10.0)
        )
        // 模拟 fetchQuoteSnapshots 抛异常 → StockRepository 内部 catch 返回 emptyMap()
        coEvery { stockRepository.fetchQuoteSnapshots(any()) } throws java.io.IOException("network down")

        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        // 即便刷新失败，isLoading 也应为 false，允许用户再次点击重试
        assertThat(viewModel.uiState.value.isLoading).isFalse()
    }

    /**
     * 冷启动兜底：网络拉价失败时，UI 用 price_cache 里的缓存价回填，
     * 现价/市值不显示"—"，而是显示上次缓存值。
     */
    @Test
    fun `cold start uses cached prices when network fetch fails`() = runTest {
        stocksFlow.value = listOf(
            stock("sz.000001", shares = 100, costPerShare = 10.0)
        )
        coEvery { stockRepository.getCachedPrices(any()) } returns mapOf("sz.000001" to 12.0)
        // 网络拉价失败
        coEvery { stockRepository.fetchQuoteSnapshots(any()) } throws java.io.IOException("down")

        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        val item = viewModel.uiState.value.items.first { it.code == "sz.000001" }
        // 缓存价 12.0 被回填，现价不为 null
        assertThat(item.currentPrice).isWithin(0.01).of(12.0)
        // 市值 = 12.0 * 100 = 1200
        assertThat(item.marketValue).isWithin(0.01).of(1200.0)
    }

    @Test
    fun `holdings market value and pnl computed from prices`() = runTest {
        stocksFlow.value = listOf(
            stock("sz.000001", shares = 100, costPerShare = 10.0),
            stock("sh.600519", shares = 50, costPerShare = 1500.0)
        )
        coEvery { stockRepository.fetchQuoteSnapshots(any()) } returns mapOf(
            "sz.000001" to QuoteSnapshot(stockCode = "sz.000001", price = 12.0),
            "sh.600519" to QuoteSnapshot(stockCode = "sh.600519", price = 1800.0)
        )

        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        // holdings market value = 100*12 + 50*1800 = 91200
        assertThat(state.holdingsMarketValue).isWithin(0.01).of(91200.0)
        // total cost = 100*10 + 50*1500 = 76000
        assertThat(state.totalCost).isWithin(0.01).of(76000.0)
        // total pnl = (12-10)*100 + (1800-1500)*50 = 15200
        assertThat(state.totalPnl).isWithin(0.01).of(15200.0)

        val first = state.items.first { it.code == "sz.000001" }
        assertThat(first.marketValue).isWithin(0.01).of(1200.0)
        assertThat(first.unrealizedPnl).isWithin(0.01).of(200.0)
    }

    @Test
    fun `items sorted by market value descending`() = runTest {
        stocksFlow.value = listOf(
            stock("sz.000001", shares = 100, costPerShare = 10.0),
            stock("sh.600519", shares = 50, costPerShare = 1500.0)
        )
        coEvery { stockRepository.fetchQuoteSnapshots(any()) } returns mapOf(
            "sz.000001" to QuoteSnapshot(stockCode = "sz.000001", price = 12.0),
            "sh.600519" to QuoteSnapshot(stockCode = "sh.600519", price = 1800.0)
        )

        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        val codes = viewModel.uiState.value.items.map { it.code }
        assertThat(codes).isEqualTo(listOf("sh.600519", "sz.000001"))
    }

    @Test
    fun `targetWeightSum aggregates target weights`() = runTest {
        stocksFlow.value = listOf(
            stock("sz.000001", shares = 100, costPerShare = 10.0, targetWeight = 30.0),
            stock("sh.600519", shares = 50, costPerShare = 1500.0, targetWeight = 50.0)
        )
        coEvery { stockRepository.fetchQuoteSnapshots(any()) } returns emptyMap()

        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.targetWeightSum).isWithin(0.001).of(80.0)
    }

    @Test
    fun `actual weight uses total assets as denominator`() = runTest {
        // Persist total assets = 400000 via real Robolectric SharedPreferences.
        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            .edit()
            .putLong("portfolio_total_assets", 400000.0.toRawBits())
            .commit()
        stocksFlow.value = listOf(
            stock("sz.000001", shares = 100, costPerShare = 10.0, targetWeight = 10.0, industry = "消费")
        )
        coEvery { stockRepository.fetchQuoteSnapshots(any()) } returns mapOf("sz.000001" to QuoteSnapshot(stockCode = "sz.000001", price = 12.0))
        // 行业目标 50%，个股占行业 10% → 目标金额 = 400000 * 50% * 10% / 100 = 20000
        coEvery { stockRepository.getIndustryTargets() } returns listOf(
            com.stock.dividend.data.local.entity.IndustryTargetEntity("消费", 50.0)
        )

        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        val item = viewModel.uiState.value.items.first()
        // market value 1200 / total assets 400000 * 100 = 0.3%
        assertThat(item.actualWeight).isWithin(0.001).of(0.3)
        // 新两层配比语义：targetValue = 行业目标金额(200000) * 个股占行业(10%) / 100 = 20000
        assertThat(item.targetValue).isWithin(0.01).of(20000.0)
    }

    @Test
    fun `actual weight is null when total assets is zero`() = runTest {
        stocksFlow.value = listOf(
            stock("sz.000001", shares = 100, costPerShare = 10.0, targetWeight = 10.0)
        )
        coEvery { stockRepository.fetchQuoteSnapshots(any()) } returns mapOf("sz.000001" to QuoteSnapshot(stockCode = "sz.000001", price = 12.0))

        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        val item = viewModel.uiState.value.items.first()
        assertThat(item.actualWeight).isNull()
        assertThat(item.targetValue).isNull()
    }

    @Test
    fun `confirmEditWeight rejects values outside 0-100`() = runTest {
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.showEditWeightDialog("sz.000001", 0.0)
        viewModel.onWeightInputChanged("150")
        viewModel.confirmEditWeight()
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.editingWeightError).isNotNull()
        coVerify(exactly = 0) { stockRepository.updateTargetWeight(any(), any()) }
    }

    @Test
    fun `confirmEditWeight persists valid value and clears dialog`() = runTest {
        coEvery { stockRepository.updateTargetWeight(any(), any()) } returns Unit
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.showEditWeightDialog("sz.000001", 0.0)
        viewModel.onWeightInputChanged("35")
        viewModel.confirmEditWeight()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { stockRepository.updateTargetWeight("sz.000001", 35.0) }
        assertThat(viewModel.uiState.value.editingCode).isNull()
    }

    @Test
    fun `confirmEditTotalAssets persists value and triggers recompute`() = runTest {
        stocksFlow.value = listOf(
            stock("sz.000001", shares = 100, costPerShare = 10.0, targetWeight = 10.0)
        )
        coEvery { stockRepository.fetchQuoteSnapshots(any()) } returns mapOf("sz.000001" to QuoteSnapshot(stockCode = "sz.000001", price = 12.0))

        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.showEditTotalAssetsDialog()
        viewModel.onTotalAssetsInputChanged("400000")
        viewModel.confirmEditTotalAssets()
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.totalAssets).isWithin(0.01).of(400000.0)
        assertThat(viewModel.uiState.value.editingTotalAssets).isFalse()
        // 验证真实 SharedPreferences 已写入（Double.toRawBits 存为 Long）
        val stored = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            .getLong("portfolio_total_assets", 0L)
        assertThat(stored).isEqualTo(400000.0.toRawBits())
        val item = viewModel.uiState.value.items.first()
        // 新两层配比：无行业目标时 targetValue 为 0
        assertThat(item.targetValue).isWithin(0.01).of(0.0)
    }

    @Test
    fun `confirmEditTotalAssets rejects non-numeric and negative`() = runTest {
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.showEditTotalAssetsDialog()
        viewModel.onTotalAssetsInputChanged("abc")
        viewModel.confirmEditTotalAssets()
        assertThat(viewModel.uiState.value.editingTotalAssetsError).isNotNull()
        assertThat(viewModel.uiState.value.editingTotalAssets).isTrue()

        viewModel.onTotalAssetsInputChanged("-100")
        viewModel.confirmEditTotalAssets()
        assertThat(viewModel.uiState.value.editingTotalAssetsError).isNotNull()
    }

    @Test
    fun `industry groups aggregate market value and map target weight`() = runTest {
        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            .edit()
            .putLong("portfolio_total_assets", 100000.0.toRawBits())
            .commit()
        coEvery { stockRepository.getIndustryTargets() } returns listOf(
            com.stock.dividend.data.local.entity.IndustryTargetEntity("银行", 30.0)
        )
        stocksFlow.value = listOf(
            stock("sh.600036", shares = 100, costPerShare = 30.0, industry = "银行"),  // 招商银行
            stock("sh.601398", shares = 200, costPerShare = 5.0, industry = "银行"),    // 工商银行
            stock("sh.600519", shares = 10, costPerShare = 1500.0, industry = "食品饮料")
        )
        coEvery { stockRepository.fetchQuoteSnapshots(any()) } returns mapOf(
            "sh.600036" to QuoteSnapshot(stockCode = "sh.600036", price = 40.0),  // 4000
            "sh.601398" to QuoteSnapshot(stockCode = "sh.601398", price = 5.0),   // 1000
            "sh.600519" to QuoteSnapshot(stockCode = "sh.600519", price = 1600.0) // 16000
        )

        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        val groups = viewModel.uiState.value.industryGroups
        assertThat(groups.map { it.name }).containsExactly("食品饮料", "银行")  // 按市值降序
        val bankGroup = groups.first { it.name == "银行" }
        // 银行市值 = 4000 + 1000 = 5000；占 100000 = 5.0%
        assertThat(bankGroup.holdingsMarketValue).isWithin(0.01).of(5000.0)
        assertThat(bankGroup.actualWeight).isWithin(0.001).of(5.0)
        assertThat(bankGroup.targetWeight).isEqualTo(30.0)  // 从 industryTargets 映射
        assertThat(bankGroup.stocks).hasSize(2)
    }

    @Test
    fun `confirmEditIndustry persists via repository`() = runTest {
        coEvery { stockRepository.updateIndustryTarget(any(), any()) } returns Unit
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.showEditIndustryDialog("银行", 0.0)
        viewModel.onIndustryWeightInputChanged("30")
        viewModel.confirmEditIndustry()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { stockRepository.updateIndustryTarget("银行", 30.0) }
        assertThat(viewModel.uiState.value.editingIndustry).isNull()
    }

    // --- 合并自选 tab 后新增的用例：股息预测 / FIRE / 撤销删除 / 自选股 ---

    @Test
    fun `shares-zero watch stocks appear in watchlist not items`() = runTest {
        stocksFlow.value = listOf(
            stock("sz.000001", shares = 100, costPerShare = 10.0),
            stock("sh.600519", shares = 0, costPerShare = 0.0)   // 纯自选
        )
        coEvery { stockRepository.fetchQuoteSnapshots(any()) } returns mapOf("sz.000001" to QuoteSnapshot(stockCode = "sz.000001", price = 12.0))

        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        // items 只含持仓股
        assertThat(viewModel.uiState.value.items.map { it.code }).containsExactly("sz.000001")
        // 自选股进 watchlist
        assertThat(viewModel.uiState.value.watchlist.map { it.code }).containsExactly("sh.600519")
    }

    /**
     * 回归：自选股（shares=0）也必须被现价刷新覆盖。
     * 历史缺陷：Collector 2 订阅 holdingsFlow(shares>0)，自选股从不进入 fetchQuoteSnapshots，
     * 纯自选股时刷新还会因 flatMapLatest 短路成 flowOf(emptyMap()) 而彻底失效。
     * 修复后 fetchQuoteSnapshots 的入参应包含自选股 code，返回价同步进 stockForecasts。
     */
    @Test
    fun `refresh fetches quotes for shares-zero watch stocks too`() = runTest {
        stocksFlow.value = listOf(
            stock("sz.000001", shares = 100, costPerShare = 10.0),
            stock("sh.600519", shares = 0, costPerShare = 0.0)   // 纯自选
        )
        // 记录每次 fetchQuoteSnapshots 调用的入参（stocks 列表），断言自选股 code 被包含
        val fetchedStocks = mutableListOf<List<StockEntity>>()
        coEvery { stockRepository.fetchQuoteSnapshots(any()) } answers {
            fetchedStocks += firstArg<List<StockEntity>>()
            mapOf("sz.000001" to QuoteSnapshot(stockCode = "sz.000001", price = 12.0), "sh.600519" to QuoteSnapshot(stockCode = "sh.600519", price = 1800.0))
        }

        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        // 关键断言：fetchQuoteSnapshots 的入参（stocks 列表）必须包含 shares=0 的自选股
        val fetchedCodes = fetchedStocks.flatMap { it.map { s -> s.code } }.toSet()
        assertThat(fetchedCodes).contains("sh.600519")
        // 拉到的自选股现价同步进 stockForecasts（自选卡片据此画「股息率→价位」横轴）
        assertThat(viewModel.uiState.value.stockForecasts["sh.600519"]?.currentPrice)
            .isWithin(0.01).of(1800.0)
    }

    /**
     * 回归：仅有自选股（无任何持仓）时，刷新按钮必须能正常结束 loading，
     * 且 fetchQuoteSnapshots 仍被调用。历史缺陷：holdingsFlow 为空 → flatMapLatest 短路
     * → _refreshTrigger 从不被消费 → 刷新按钮永久转圈。
     */
    @Test
    fun `watchlist-only portfolio still refreshes and resets loading`() = runTest {
        stocksFlow.value = listOf(
            stock("sh.600519", shares = 0, costPerShare = 0.0)   // 仅一只自选股
        )
        coEvery { stockRepository.fetchQuoteSnapshots(any()) } returns mapOf("sh.600519" to QuoteSnapshot(stockCode = "sh.600519", price = 1800.0))

        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        // 纯自选场景下 fetchQuoteSnapshots 也被调用
        coVerify { stockRepository.fetchQuoteSnapshots(any()) }
        // loading 正常复位，刷新按钮不会被卡死
        assertThat(viewModel.uiState.value.isLoading).isFalse()
        // 自选股现价被刷新
        assertThat(viewModel.uiState.value.stockForecasts["sh.600519"]?.currentPrice)
            .isWithin(0.01).of(1800.0)
    }

    @Test
    fun `forecastTotal sums forecast income for shares-greater-than-zero holdings`() = runTest {
        val holding = stock("sz.000001", shares = 100, costPerShare = 10.0, yieldPeriod = "1")
        every { dividendDao.observeByStock("sz.000001") } returns MutableStateFlow(
            listOf(
                DividendEntity(
                    id = "sz.000001_2025",
                    stockCode = "sz.000001",
                    reportDate = "2025-12-31",
                    cashPerShare = 10.0
                )
            )
        )
        stocksFlow.value = listOf(holding)

        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        // forecastIncome = shares(100) * avgCashPerShare(10.0) = 1000
        assertThat(viewModel.uiState.value.forecastTotal).isWithin(0.01).of(1000.0)
        val forecast = viewModel.uiState.value.stockForecasts["sz.000001"]
        assertThat(forecast).isNotNull()
        assertThat(forecast!!.forecastIncome).isWithin(0.01).of(1000.0)
        assertThat(forecast.latestYearlyDividend).isWithin(0.01).of(10.0)
    }

    @Test
    fun `costDividendYield sums latest yearly dividend times shares over total cost`() = runTest {
        // 持仓 A：100 股 × 成本 10，最新年度每股股息 1.0  → 股息合计 100，成本合计 1000
        val holdingA = stock("sz.000001", shares = 100, costPerShare = 10.0, yieldPeriod = "1")
        every { dividendDao.observeByStock("sz.000001") } returns MutableStateFlow(
            listOf(
                DividendEntity(
                    id = "sz.000001_2025",
                    stockCode = "sz.000001",
                    reportDate = "2025-12-31",
                    cashPerShare = 1.0
                )
            )
        )
        // 持仓 B：200 股 × 成本 5，最新年度每股股息 0.5 → 股息合计 100，成本合计 1000
        val holdingB = stock("sz.000002", shares = 200, costPerShare = 5.0, yieldPeriod = "1")
        every { dividendDao.observeByStock("sz.000002") } returns MutableStateFlow(
            listOf(
                DividendEntity(
                    id = "sz.000002_2025",
                    stockCode = "sz.000002",
                    reportDate = "2025-12-31",
                    cashPerShare = 0.5
                )
            )
        )
        stocksFlow.value = listOf(holdingA, holdingB)

        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        // 总成本息率 = (100 + 100) / (1000 + 1000) = 0.10 (10%)
        assertThat(viewModel.uiState.value.costDividendYield).isNotNull()
        assertThat(viewModel.uiState.value.costDividendYield!!).isWithin(0.0001).of(0.10)
    }

    /**
     * 复现用户场景：有持仓+自选时一切正常（价格已刷新），删掉全部持仓、只剩自选后，
     * 自选区块是否还显示、自选股现价是否还在。
     */
    @Test
    fun `watchlist still shows price after all holdings deleted`() = runTest {
        // 初始：1 只持仓 + 1 只自选，价格已刷新
        stocksFlow.value = listOf(
            stock("sz.000001", shares = 100, costPerShare = 10.0),
            stock("sh.600519", shares = 0, costPerShare = 0.0)
        )
        coEvery { stockRepository.fetchQuoteSnapshots(any()) } returns mapOf(
            "sz.000001" to QuoteSnapshot(stockCode = "sz.000001", price = 12.0),
            "sh.600519" to QuoteSnapshot(stockCode = "sh.600519", price = 1800.0)
        )

        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        // 删除前：自选股现价 1800
        assertThat(viewModel.uiState.value.stockForecasts["sh.600519"]?.currentPrice)
            .isWithin(0.01).of(1800.0)

        // 删掉持仓，只剩自选股
        stocksFlow.value = listOf(
            stock("sh.600519", shares = 0, costPerShare = 0.0)
        )
        testDispatcher.scheduler.advanceUntilIdle()

        // 删除后：自选区块仍在
        assertThat(viewModel.uiState.value.watchlist.map { it.code }).containsExactly("sh.600519")
        assertThat(viewModel.uiState.value.filteredWatchlist.map { it.code })
            .containsExactly("sh.600519")
        // 自选股现价仍显示（不因持仓清空而丢失）
        assertThat(viewModel.uiState.value.stockForecasts["sh.600519"]?.currentPrice)
            .isWithin(0.01).of(1800.0)
    }

    @Test
    fun `costDividendYield is null when no dividend data or zero cost`() = runTest {
        // 持仓无股息记录 → 无 latestYearlyDividend
        val holding = stock("sz.000001", shares = 100, costPerShare = 10.0, yieldPeriod = "1")
        every { dividendDao.observeByStock("sz.000001") } returns MutableStateFlow(emptyList())
        stocksFlow.value = listOf(holding)

        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.costDividendYield).isNull()

        // 零成本的纯自选股不应贡献分母 → 仍为 null
        stocksFlow.value = listOf(stock("sz.000002", shares = 0, costPerShare = 0.0))
        testDispatcher.scheduler.advanceUntilIdle()
        assertThat(viewModel.uiState.value.costDividendYield).isNull()
    }

    @Test
    fun `fire progress uses annualized living expenses as denominator`() = runTest {
        val holding = stock("sz.000001", shares = 100, costPerShare = 10.0, yieldPeriod = "1")
        every { dividendDao.observeByStock("sz.000001") } returns MutableStateFlow(
            listOf(
                DividendEntity(
                    id = "sz.000001_2025",
                    stockCode = "sz.000001",
                    reportDate = "2025-12-31",
                    cashPerShare = 10.0
                )
            )
        )
        // 月支出 300（年化 3600）+ 年支出 400 = 年化 4000
        livingExpensesFlow.value = listOf(
            LivingExpenseItemEntity(1, "房租", 300.0, EXPENSE_PERIOD_MONTHLY, 0),
            LivingExpenseItemEntity(2, "保险", 400.0, EXPENSE_PERIOD_YEARLY, 1)
        )
        stocksFlow.value = listOf(holding)

        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        // 预测 1000 / 年化支出 4000 = 25%
        assertThat(viewModel.uiState.value.livingExpenseTargetAmount).isEqualTo(4000.0)
        assertThat(viewModel.uiState.value.fireProgress).isWithin(0.0001f).of(25.0f)
    }

    @Test
    fun `deleteStock backs up transactions and supports undo`() = runTest {
        coEvery { stockRepository.removeStock(any()) } returns Unit
        coEvery { stockRepository.restoreStock(any()) } returns Unit
        coEvery { transactionDao.getByStock("sz.000001") } returns emptyList()
        coEvery { transactionDao.insert(any<TransactionEntity>()) } returns 1L

        stocksFlow.value = listOf(stock("sz.000001", shares = 100, costPerShare = 10.0))
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.deleteStock("sz.000001")
        testDispatcher.scheduler.advanceUntilIdle()

        // 删除后待撤销状态被记录
        assertThat(viewModel.uiState.value.deletedStock).isNotNull()
        assertThat(viewModel.uiState.value.deletedStock!!.code).isEqualTo("sz.000001")
        coVerify { stockRepository.removeStock("sz.000001") }

        viewModel.undoDelete()
        testDispatcher.scheduler.advanceUntilIdle()

        // 撤销恢复股票，清空待删状态
        assertThat(viewModel.uiState.value.deletedStock).isNull()
        coVerify { stockRepository.restoreStock(any()) }
    }

    @Test
    fun `clearDeleted drops the pending deletion state`() = runTest {
        coEvery { stockRepository.removeStock(any()) } returns Unit
        coEvery { transactionDao.getByStock(any()) } returns emptyList()
        stocksFlow.value = listOf(stock("sz.000001", shares = 100, costPerShare = 10.0))
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.deleteStock("sz.000001")
        testDispatcher.scheduler.advanceUntilIdle()
        assertThat(viewModel.uiState.value.deletedStock).isNotNull()

        viewModel.clearDeleted()
        assertThat(viewModel.uiState.value.deletedStock).isNull()
    }

    // ── loadBoll：按需懒加载周线 BOLL 带 ──────────────────────────────

    @Test
    fun `loadBoll fetches band and exposes it via uiState`() = runTest {
        val band = BollBand(middle = 10.0, upper = 11.0, lower = 9.0)
        coEvery { stockRepository.fetchBoll("sh.600036") } returns band

        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.loadBoll("sh.600036")
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.stockBands["sh.600036"]).isEqualTo(band)
    }

    @Test
    fun `loadBoll caches result and does not refetch on repeat call`() = runTest {
        val band = BollBand(middle = 10.0, upper = 11.0, lower = 9.0)
        coEvery { stockRepository.fetchBoll("sh.600036") } returns band

        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.loadBoll("sh.600036")
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.loadBoll("sh.600036") // 重复调用，应命中缓存不重发请求
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { stockRepository.fetchBoll("sh.600036") }
    }

    @Test
    fun `loadBoll caches null on failure to prevent retry storm`() = runTest {
        coEvery { stockRepository.fetchBoll("sh.600036") } throws java.io.IOException("down")

        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.loadBoll("sh.600036")
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.loadBoll("sh.600036") // 失败后再次调用，应被缓存(null)拦截
        testDispatcher.scheduler.advanceUntilIdle()

        // 失败也算「已尝试」，写入 null；重复调用不应再次发请求
        assertThat(viewModel.uiState.value.stockBands["sh.600036"]).isNull()
        coVerify(exactly = 1) { stockRepository.fetchBoll("sh.600036") }
    }

    // ── evaluateVisibleHoldings：一键评估筛选后的持仓 ─────────────────

    @Test
    fun `evaluateVisibleHoldings sets isEvaluating then produces sorted results`() = runTest {
        val band = BollBand(middle = 10.0, upper = 11.0, lower = 9.0)
        // 月线 middle=12，price 8.8 ≤ 12 满足「月中轨及以下」，与日/周下轨共振 → BUY
        val monthly = BollBand(middle = 12.0, upper = 14.0, lower = 10.0)
        stocksFlow.value = listOf(
            stock("sh.600036", shares = 100, costPerShare = 10.0, industry = "银行"),
            stock("sz.000001", shares = 200, costPerShare = 5.0, industry = "银行")
        )
        coEvery { stockRepository.fetchBoll("sh.600036") } returns band
        coEvery { stockRepository.fetchBoll("sh.600036", KlinePeriod.DAILY) } returns band
        coEvery { stockRepository.fetchBoll("sh.600036", KlinePeriod.MONTHLY) } returns monthly
        coEvery { stockRepository.fetchBoll("sz.000001") } returns null // 数据不足
        coEvery { stockRepository.fetchBoll("sz.000001", any()) } returns null
        // 给 sh.600036 一个现价 + 股息
        every { dividendDao.observeByStock("sh.600036") } returns MutableStateFlow(
            listOf(dividend("sh.600036", 0.50))
        )
        coEvery { stockRepository.fetchQuoteSnapshots(any()) } returns mapOf("sh.600036" to QuoteSnapshot(stockCode = "sh.600036", price = 8.8))

        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.evaluateVisibleHoldings()
        // 评估中应置 isEvaluating=true（在协程 launch 后、awaitAll 完成前）
        // advanceUntilIdle 后应完成
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.isEvaluating).isFalse()
        assertThat(state.evaluation).isNotNull()
        val eval = state.evaluation!!
        // sh.600036: 日/周下轨 + 月中轨及以下 三周期共振 → BUY；sz.000001: band null → INSUFFICIENT_DATA
        assertThat(eval.map { it.code to it.action }).containsExactly(
            "sh.600036" to HoldingAction.BUY,
            "sz.000001" to HoldingAction.INSUFFICIENT_DATA
        )
        // 排序：BUY 在 INSUFFICIENT_DATA 之前
        assertThat(eval.first().action).isEqualTo(HoldingAction.BUY)
    }

    @Test
    fun `evaluateVisibleHoldings skips when no visible items`() = runTest {
        stocksFlow.value = emptyList()
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.evaluateVisibleHoldings()
        testDispatcher.scheduler.advanceUntilIdle()

        val eval = viewModel.uiState.value.evaluation
        assertThat(eval).isNotNull()
        assertThat(eval).isEmpty()
    }

    @Test
    fun `evaluateVisibleHoldings respects custom thresholds`() = runTest {
        val band = BollBand(middle = 10.0, upper = 11.0, lower = 9.0)
        val monthly = BollBand(middle = 12.0, upper = 14.0, lower = 10.0)
        stocksFlow.value = listOf(stock("sh.600036", shares = 100, costPerShare = 10.0))
        coEvery { stockRepository.fetchBoll("sh.600036") } returns band
        coEvery { stockRepository.fetchBoll("sh.600036", KlinePeriod.DAILY) } returns band
        coEvery { stockRepository.fetchBoll("sh.600036", KlinePeriod.MONTHLY) } returns monthly
        every { dividendDao.observeByStock("sh.600036") } returns MutableStateFlow(
            listOf(dividend("sh.600036", 0.22)) // yield ~2.5% at price 8.8
        )
        coEvery { stockRepository.fetchQuoteSnapshots(any()) } returns mapOf("sh.600036" to QuoteSnapshot(stockCode = "sh.600036", price = 8.8))
        // 严格门槛：min=3 → 三周期共振但 2.5% 应降级 HOLD
        every { notificationRuleRepository.observeEvalThresholds() } returns
            MutableStateFlow(DividendThresholds(minYieldPercent = 3.0, boostYieldPercent = 6.0))

        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.evaluateVisibleHoldings()
        testDispatcher.scheduler.advanceUntilIdle()

        val eval = viewModel.uiState.value.evaluation!!
        assertThat(eval.first().action).isEqualTo(HoldingAction.HOLD)
    }

    private fun deepSetup() {
        stocksFlow.value = listOf(
            stock("sh.600036", shares = 100, costPerShare = 10.0, industry = "银行")
        )
        val dividends = listOf(
            DividendEntity(id = "sh.600036_2022", stockCode = "sh.600036", reportDate = "2022-12-31", cashPerShare = 0.2),
            DividendEntity(id = "sh.600036_2023", stockCode = "sh.600036", reportDate = "2023-12-31", cashPerShare = 0.3),
            DividendEntity(id = "sh.600036_2024", stockCode = "sh.600036", reportDate = "2024-12-31", cashPerShare = 0.4)
        )
        every { dividendDao.observeByStock("sh.600036") } returns MutableStateFlow(dividends)
        coEvery { stockRepository.fetchQuoteSnapshots(any()) } returns mapOf("sh.600036" to QuoteSnapshot(stockCode = "sh.600036", price = 10.0))
        coEvery { stockRepository.fetchBoll(any()) } returns BollBand(10.0, 12.0, 8.0)
        coEvery { stockRepository.fetchBoll(any(), any()) } returns BollBand(10.0, 12.0, 8.0)
        coEvery { bondYieldRepository.fetch10YBondYield(any()) } returns 2.5
        coEvery { fundamentalsCacheRepository.getFundamentals("sh.600036", false) } returns Fundamentals(
            periods = listOf(Fundamentals.Period("2024-12-31", 12.0, 60.0, 8.0, 5.0, payoutRatio = 25.0))
        )
    }

    @Test
    fun `stockForecasts include 1-3-5 year llm forecast`() = runTest {
        val dividends = listOf(
            DividendEntity(id = "sh.600036_2022", stockCode = "sh.600036", reportDate = "2022-12-31", cashPerShare = 0.2),
            DividendEntity(id = "sh.600036_2023", stockCode = "sh.600036", reportDate = "2023-12-31", cashPerShare = 0.3),
            DividendEntity(id = "sh.600036_2024", stockCode = "sh.600036", reportDate = "2024-12-31", cashPerShare = 0.4)
        )
        every { dividendDao.observeByStock("sh.600036") } returns MutableStateFlow(dividends)
        stocksFlow.value = listOf(stock("sh.600036", shares = 100, costPerShare = 10.0))

        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        val llm = viewModel.uiState.value.stockForecasts["sh.600036"]?.llmForecast
        assertThat(llm).isNotNull()
        assertThat(llm!!.avgCashPerShare1Y).isEqualTo(0.4)
        assertThat(llm.avgCashPerShare3Y).isWithin(1e-9).of(0.3)
        assertThat(llm.avgCashPerShare5Y).isWithin(1e-9).of(0.3)  // 样本不足回退基准值
        assertThat(llm.actualYears).isEqualTo(3)
    }

    @Test
    fun `analyzeWithLlm assembles deep data and passes to repository`() = runTest {
        deepSetup()
        val inputSlot = slot<PortfolioLlmInput>()
        coEvery { llmAnalysisRepository.analyze(capture(inputSlot), any()) } returns LlmAnalysisResult.Success(
            LlmAnalysis("ok", emptyMap(), emptyList())
        )

        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.evaluateVisibleHoldings()
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.analyzeWithLlm()
        testDispatcher.scheduler.advanceUntilIdle()

        val input = inputSlot.captured
        assertThat(input.stockDetails["sh.600036"]?.fundamentals).isNotNull()
        assertThat(input.stockDetails["sh.600036"]?.forecast?.avgCashPerShare1Y).isEqualTo(0.4)
        assertThat(input.stockDetails["sh.600036"]?.buyThreshold?.reached).isEqualTo(false)
        coVerify { fundamentalsCacheRepository.getFundamentals("sh.600036", false) }
        coVerify { llmAnalysisRepository.analyze(input, false) }
    }

    @Test
    fun `analyzeWithLlm passes forceRefresh to fundamentals and repository`() = runTest {
        deepSetup()
        coEvery { llmAnalysisRepository.analyze(any(), any()) } returns LlmAnalysisResult.Success(
            LlmAnalysis("ok", emptyMap(), emptyList())
        )

        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.evaluateVisibleHoldings()
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.analyzeWithLlm(forceRefresh = true)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { fundamentalsCacheRepository.getFundamentals("sh.600036", true) }
        coVerify { llmAnalysisRepository.analyze(any(), true) }
    }

    @Test
    fun `analyzeWithLlm maps cached success metadata to state`() = runTest {
        deepSetup()
        coEvery { llmAnalysisRepository.analyze(any(), any()) } returns LlmAnalysisResult.Success(
            LlmAnalysis("cached", emptyMap(), emptyList()),
            analyzedAt = 123L,
            fromCache = true
        )

        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.evaluateVisibleHoldings()
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.analyzeWithLlm()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value.llmAnalysis as LlmAnalysisState.Success
        assertThat(state.analysis.overview).isEqualTo("cached")
        assertThat(state.fromCache).isTrue()
        assertThat(state.analyzedAt).isEqualTo(123L)
    }

    @Test
    fun `analyzeWithLlm degrades when fundamentals fail`() = runTest {
        deepSetup()
        coEvery { fundamentalsCacheRepository.getFundamentals(any(), any()) } throws RuntimeException("boom")
        val inputSlot = slot<PortfolioLlmInput>()
        coEvery { llmAnalysisRepository.analyze(capture(inputSlot), any()) } returns LlmAnalysisResult.Success(
            LlmAnalysis("ok", emptyMap(), emptyList())
        )

        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.evaluateVisibleHoldings()
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.analyzeWithLlm()
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(inputSlot.captured.stockDetails["sh.600036"]?.fundamentals).isNull()
        assertThat(viewModel.uiState.value.llmAnalysis).isInstanceOf(LlmAnalysisState.Success::class.java)
    }

    private fun createViewModel() = PortfolioViewModel(
        stockRepository,
        dividendDao,
        livingExpenseRepository,
        transactionDao,
        notificationCheckCoordinator,
        notificationRuleRepository,
        llmAnalysisRepository,
        tradeStrategyRepository,
        fundamentalsCacheRepository,
        bondYieldRepository,
        context
    )

    private fun stock(
        code: String,
        shares: Int,
        costPerShare: Double,
        targetWeight: Double = 0.0,
        industry: String = "",
        yieldPeriod: String = "3"
    ) = StockEntity(
        code = code,
        name = if (code.startsWith("sh")) "茅台" else "平安银行",
        marketCode = if (code.startsWith("sh")) "1" else "0",
        shares = shares,
        costPerShare = costPerShare,
        targetWeight = targetWeight,
        industry = industry,
        yieldPeriod = yieldPeriod
    )

    private fun dividend(stockCode: String, cashPerShare: Double) = DividendEntity(
        id = "${stockCode}_2025",
        stockCode = stockCode,
        reportDate = "2025-12-31",
        cashPerShare = cashPerShare
    )
}
