package com.stock.dividend.viewmodel

import android.content.Context
import android.content.SharedPreferences
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
import com.stock.dividend.data.repository.LivingExpenseRepository
import com.stock.dividend.data.repository.StockRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
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
class PortfolioViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val stockRepository: StockRepository = mockk()
    private val dividendDao: DividendDao = mockk()
    private val livingExpenseRepository: LivingExpenseRepository = mockk()
    private val transactionDao: TransactionDao = mockk()
    private val notificationCheckCoordinator: NotificationCheckCoordinator = mockk(relaxed = true)
    private val context: Context = mockk(relaxed = true)
    private val prefs: SharedPreferences = mockk(relaxed = true)
    private val prefsEditor: SharedPreferences.Editor = mockk(relaxed = true) {
        every { putLong(any(), any()) } returns this
    }

    private val stocksFlow = MutableStateFlow<List<StockEntity>>(emptyList())
    private val livingExpensesFlow = MutableStateFlow<List<LivingExpenseItemEntity>>(emptyList())

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE) } returns prefs
        every { prefs.edit() } returns prefsEditor
        every { prefs.getLong("last_portfolio_refresh_ms", 0L) } returns 0L
        every { prefs.contains("portfolio_total_assets") } returns false
        every { stockRepository.observeAllStocks() } returns stocksFlow
        every { stockRepository.observeIndustryTargets() } returns MutableStateFlow(emptyList())
        every { dividendDao.observeByStock(any()) } returns MutableStateFlow(emptyList())
        every { livingExpenseRepository.observeExpenses() } returns livingExpensesFlow
        coEvery { stockRepository.getIndustryTargets() } returns emptyList()
        coEvery { stockRepository.fetchQuotes(any()) } returns emptyMap()
        coEvery { stockRepository.getCachedPrices(any()) } returns emptyMap()
        coEvery { transactionDao.getByStock(any()) } returns emptyList()
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
     * 回归：刷新失败（fetchQuotes 返回空 map，模拟网络异常被 StockRepository 吞掉）
     * 时 isLoading 必须复位为 false，否则 TopAppBar 刷新按钮会因 enabled=!isRefreshing
     * 被永久禁用，呈现"一直转圈卡死"。
     */
    @Test
    fun `refresh failure resets isLoading so button is not stuck`() = runTest {
        stocksFlow.value = listOf(
            stock("sz.000001", shares = 100, costPerShare = 10.0)
        )
        // 模拟 fetchQuotes 抛异常 → StockRepository 内部 catch 返回 emptyMap()
        coEvery { stockRepository.fetchQuotes(any()) } throws java.io.IOException("network down")

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
        coEvery { stockRepository.fetchQuotes(any()) } throws java.io.IOException("down")

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
        coEvery { stockRepository.fetchQuotes(any()) } returns mapOf(
            "sz.000001" to 12.0,
            "sh.600519" to 1800.0
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
        coEvery { stockRepository.fetchQuotes(any()) } returns mapOf(
            "sz.000001" to 12.0,
            "sh.600519" to 1800.0
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
        coEvery { stockRepository.fetchQuotes(any()) } returns emptyMap()

        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.targetWeightSum).isWithin(0.001).of(80.0)
    }

    @Test
    fun `actual weight uses total assets as denominator`() = runTest {
        // Persist total assets = 400000 via prefs.
        every { prefs.contains("portfolio_total_assets") } returns true
        every { prefs.getLong("portfolio_total_assets", any()) } returns 400000.0.toRawBits()
        stocksFlow.value = listOf(
            stock("sz.000001", shares = 100, costPerShare = 10.0, targetWeight = 10.0, industry = "消费")
        )
        coEvery { stockRepository.fetchQuotes(any()) } returns mapOf("sz.000001" to 12.0)
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
        coEvery { stockRepository.fetchQuotes(any()) } returns mapOf("sz.000001" to 12.0)

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
        coEvery { stockRepository.fetchQuotes(any()) } returns mapOf("sz.000001" to 12.0)

        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.showEditTotalAssetsDialog()
        viewModel.onTotalAssetsInputChanged("400000")
        viewModel.confirmEditTotalAssets()
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.totalAssets).isWithin(0.01).of(400000.0)
        assertThat(viewModel.uiState.value.editingTotalAssets).isFalse()
        coVerify { prefsEditor.putLong("portfolio_total_assets", 400000.0.toRawBits()) }
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
        every { prefs.contains("portfolio_total_assets") } returns true
        every { prefs.getLong("portfolio_total_assets", any()) } returns 100000.0.toRawBits()
        coEvery { stockRepository.getIndustryTargets() } returns listOf(
            com.stock.dividend.data.local.entity.IndustryTargetEntity("银行", 30.0)
        )
        stocksFlow.value = listOf(
            stock("sh.600036", shares = 100, costPerShare = 30.0, industry = "银行"),  // 招商银行
            stock("sh.601398", shares = 200, costPerShare = 5.0, industry = "银行"),    // 工商银行
            stock("sh.600519", shares = 10, costPerShare = 1500.0, industry = "食品饮料")
        )
        coEvery { stockRepository.fetchQuotes(any()) } returns mapOf(
            "sh.600036" to 40.0,  // 4000
            "sh.601398" to 5.0,   // 1000
            "sh.600519" to 1600.0 // 16000
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
        coEvery { stockRepository.fetchQuotes(any()) } returns mapOf("sz.000001" to 12.0)

        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        // items 只含持仓股
        assertThat(viewModel.uiState.value.items.map { it.code }).containsExactly("sz.000001")
        // 自选股进 watchlist
        assertThat(viewModel.uiState.value.watchlist.map { it.code }).containsExactly("sh.600519")
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

    private fun createViewModel() = PortfolioViewModel(
        stockRepository,
        dividendDao,
        livingExpenseRepository,
        transactionDao,
        notificationCheckCoordinator,
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
}
