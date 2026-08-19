package com.stock.dividend.viewmodel

import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.local.entity.DividendEntity
import com.stock.dividend.data.local.entity.StockEntity
import com.stock.dividend.data.plane.MarketDataPlane
import com.stock.dividend.data.repository.Fundamentals
import com.stock.dividend.data.repository.KlinePeriod
import com.stock.dividend.data.repository.LlmAnalysisRepository
import com.stock.dividend.data.repository.StockLlmAnalysis
import com.stock.dividend.data.repository.StockLlmAnalysisResult
import com.stock.dividend.data.repository.StockLlmAnalysisState
import com.stock.dividend.data.repository.StockLlmInput
import com.stock.dividend.data.repository.StockRepository
import com.stock.dividend.data.repository.enrichPayoutRatio
import com.stock.dividend.data.repository.TradeStrategyRepository
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StockDetailViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    /** 数据平面 mock：读路径唯一入口。 */
    private val plane: MarketDataPlane = mockk()

    /** 写路径仓库 mock（updateYieldPeriod/updateBuyThresholdMultiplier 等）。 */
    private val stockRepository: StockRepository = mockk(relaxed = true)
    private val llmAnalysisRepository: LlmAnalysisRepository = mockk {
        coEvery { analyzeStock(any(), any(), any()) } returns StockLlmAnalysisResult.Success(
            StockLlmAnalysis("", "", "", emptyList())
        )
    }
    private val tradeStrategyRepository: TradeStrategyRepository = mockk {
        coEvery { activeStrategies() } returns emptyList()
    }

    private val stocksFlow = MutableStateFlow<List<StockEntity>>(emptyList())
    private val stockFlow = MutableStateFlow<StockEntity?>(null)
    private val dividendsFlow = MutableStateFlow<List<DividendEntity>>(emptyList())

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        coEvery { plane.observeStock(any()) } returns stockFlow
        coEvery { plane.observeDividends(any()) } returns dividendsFlow
        coEvery { plane.get10YBondYield(any()) } returns
            com.stock.dividend.data.repository.BondYieldRepository.DEFAULT_YIELD
        coEvery { plane.getPrices(any(), any()) } returns emptyMap()
        coEvery { plane.getQuoteSnapshots(any(), any()) } returns emptyMap()
        coEvery { plane.getBoll(any(), any()) } returns null
        coEvery { plane.getKlines(any(), any(), any(), any()) } returns emptyList()
        coEvery { plane.getFundamentals(any(), any()) } returns null
        coEvery { plane.refreshDividends(any()) } returns Result.success(Unit)
        // init 触发的分红新鲜度检查（K 线股息率网格线语义，§4.2A）
        coEvery { plane.ensureDividendsFresh(any()) } just Runs
        // enrichFundamentals 按平面真实口径用测试 dividendsFlow 补派息率（纯函数）
        coEvery { plane.enrichFundamentals(any(), any()) } coAnswers {
            val f: Fundamentals = firstArg()
            val epsDiv = dividendsFlow.value
                .filter { it.reportDate.isNotBlank() && it.cashPerShare > 0.0 }
                .associate { it.reportDate to it.cashPerShare }
            enrichPayoutRatio(f, epsDiv)
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state has isLoading true`() = runTest {

        val viewModel = createVm("sz.000001")

        assertThat(viewModel.uiState.value.isLoading).isTrue()
    }

    @Test
    fun `stock loads from repository`() = runTest {
        val stock = StockEntity("sz.000001", "平安银行", "0")
        stockFlow.value = stock

        val viewModel = createVm("sz.000001")
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.stock?.name).isEqualTo("平安银行")
    }

    @Test
    fun `dividends update from repository flow`() = runTest {
        val dividends = listOf(
            DividendEntity(
                id = "sz.000001_2024-12-31",
                stockCode = "sz.000001",
                reportDate = "2024-12-31",
                cashPerShare = 0.246,
                dividendYield = 5.93,
                exDividendDate = "2025-07-11",
                recordDate = "2025-07-10",
                planStatus = "实施方案"
            )
        )
        dividendsFlow.value = dividends

        val viewModel = createVm("sz.000001")
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.dividends).hasSize(1)
        assertThat(viewModel.uiState.value.dividends[0].cashPerShare).isEqualTo(0.246)
        assertThat(viewModel.uiState.value.isLoading).isFalse()
    }

    @Test
    fun `stock remains null when not found in repository`() = runTest {
        stockFlow.value = null

        val viewModel = createVm("sz.999999")
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.stock).isNull()
    }

    @Test
    fun `empty dividends list sets isLoading to false`() = runTest {
        val viewModel = createVm("sz.000001")
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.dividends).isEmpty()
        assertThat(viewModel.uiState.value.isLoading).isFalse()
    }

    @Test
    fun `initial error is null`() = runTest {
        val viewModel = createVm("sz.000001")

        assertThat(viewModel.uiState.value.error).isNull()
    }

    private fun createViewModel(
        code: String = "sz.000001",
        dividends: List<DividendEntity> = emptyList()
    ): StockDetailViewModel {
        dividendsFlow.value = dividends
        return createVm(code)
    }

    private fun createVm(code: String): StockDetailViewModel = StockDetailViewModel(
        savedStateHandle = androidx.lifecycle.SavedStateHandle(mapOf("code" to code)),
        marketDataPlane = plane,
        llmAnalysisRepository = llmAnalysisRepository,
        tradeStrategyRepository = tradeStrategyRepository,
        stockRepository = stockRepository
    )

    private fun makeDividends(count: Int): List<DividendEntity> {
        return (1..count).map { i ->
            DividendEntity(
                id = "sz.000001_2024-$i",
                stockCode = "sz.000001",
                reportDate = "2024-$i",
                cashPerShare = 0.1 * i,
                dividendYield = 1.0 * i,
                exDividendDate = "2025-07-$i",
                recordDate = "2025-07-${i - 1}",
                planStatus = "实施方案"
            )
        }
    }

    private fun makeDividend(
        id: String,
        reportDate: String,
        dividendYield: Double?
    ): DividendEntity {
        return DividendEntity(
            id = id,
            stockCode = "sz.000001",
            reportDate = reportDate,
            cashPerShare = 0.1,
            dividendYield = dividendYield,
            exDividendDate = null,
            recordDate = null,
            planStatus = "实施方案"
        )
    }

    @Test
    fun `initial visibleCount is 5`() = runTest {
        val viewModel = createViewModel(dividends = makeDividends(12))
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.visibleCount).isEqualTo(5)
    }

    @Test
    fun `loadMoreDividends increases visibleCount by 5`() = runTest {
        val viewModel = createViewModel(dividends = makeDividends(12))
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.loadMoreDividends()

        assertThat(viewModel.uiState.value.visibleCount).isEqualTo(10)
    }

    @Test
    fun `loadMoreDividends caps at total dividends size`() = runTest {
        val viewModel = createViewModel(dividends = makeDividends(12))
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.loadMoreDividends() // 5 → 10
        viewModel.loadMoreDividends() // 10 → 12 (capped)

        assertThat(viewModel.uiState.value.visibleCount).isEqualTo(12)
    }

    @Test
    fun `refreshing dividends resets visibleCount to 5`() = runTest {
        val viewModel = createViewModel(dividends = makeDividends(12))
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.loadMoreDividends()
        assertThat(viewModel.uiState.value.visibleCount).isEqualTo(10)

        // Simulate dividend refresh by emitting different data through the flow
        // MutableStateFlow uses structural equality, so we must emit a different list
        dividendsFlow.value = makeDividends(13)
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.visibleCount).isEqualTo(5)
    }

    @Test
    fun `dividend rate points include only valid yields sorted by report date`() = runTest {
        val viewModel = createViewModel(
            dividends = listOf(
                makeDividend("2024", "2024-12-31", 4.2),
                makeDividend("2022", "2022-12-31", 2.8),
                makeDividend("null", "2023-06-30", null),
                makeDividend("negative", "2023-12-31", -1.0),
                makeDividend("nan", "2021-12-31", Double.NaN),
                makeDividend("infinite", "2020-12-31", Double.POSITIVE_INFINITY)
            )
        )
        testDispatcher.scheduler.advanceUntilIdle()

        val points = viewModel.uiState.value.dividendRatePoints

        assertThat(points.map { it.period }).containsExactly("2022", "2024").inOrder()
        assertThat(points.map { it.label }).containsExactly("2022", "2024").inOrder()
        assertThat(points.map { it.ratePercent }).containsExactly(2.8, 4.2).inOrder()
    }

    @Test
    fun `multiple valid dividend yields produce chart eligible points`() = runTest {
        val viewModel = createViewModel(
            dividends = listOf(
                makeDividend("2022", "2022-12-31", 2.1),
                makeDividend("2023", "2023-12-31", 3.4),
                makeDividend("2024", "2024-12-31", 4.5)
            )
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.dividendRatePoints).hasSize(3)
    }

    @Test
    fun `null dividend yields produce empty dividend rate points`() = runTest {
        val viewModel = createViewModel(
            dividends = listOf(
                makeDividend("2022", "2022-12-31", null),
                makeDividend("2023", "2023-12-31", null)
            )
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.dividendRatePoints).isEmpty()
    }

    @Test
    fun `single valid dividend yield preserves one point and percent value`() = runTest {
        val viewModel = createViewModel(
            dividends = listOf(
                makeDividend("2022", "2022-12-31", null),
                makeDividend("2023", "2023-12-31", 3.25)
            )
        )
        testDispatcher.scheduler.advanceUntilIdle()

        val points = viewModel.uiState.value.dividendRatePoints

        assertThat(points).hasSize(1)
        assertThat(points[0].period).isEqualTo("2023")
        assertThat(points[0].label).isEqualTo("2023")
        assertThat(points[0].ratePercent).isEqualTo(3.25)
    }

    @Test
    fun `out of order dividend records produce ascending dividend rate points`() = runTest {
        val viewModel = createViewModel(
            dividends = listOf(
                makeDividend("2024", "2024-12-31", 4.5),
                makeDividend("2021", "2021-12-31", 1.6),
                makeDividend("2023", "2023-12-31", 3.1),
                makeDividend("2022", "2022-12-31", 2.4)
            )
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.dividendRatePoints.map { it.period })
            .containsExactly("2021", "2022", "2023", "2024")
            .inOrder()
    }

    @Test
    fun `multiple dividends in the same year are summed into one dividend rate point`() = runTest {
        val viewModel = createViewModel(
            dividends = listOf(
                makeDividend("2024-final", "2024-12-31", 2.3),
                makeDividend("2023-final", "2023-12-31", 3.0),
                makeDividend("2024-mid", "2024-06-30", 1.2)
            )
        )
        testDispatcher.scheduler.advanceUntilIdle()

        val points = viewModel.uiState.value.dividendRatePoints

        assertThat(points.map { it.period }).containsExactly("2023", "2024").inOrder()
        assertThat(points.map { it.ratePercent }).containsExactly(3.0, 3.5).inOrder()
    }

    // endregion 原有用例
    // region 个股 AI 解读（analyzeWithLlm → LlmAnalysisRepository.analyzeStock）

    /** 与 [createViewModel] 类似，但同时填充 stockFlow，使 uiState.stock 非空（AI 解读的前置条件）。 */
    private fun createViewModelWithStock(
        dividends: List<DividendEntity> = makeDividends(2)
    ): StockDetailViewModel {
        stockFlow.value = StockEntity("sz.000001", "测试银行", "0", shares = 1000, industry = "银行")
        return createViewModel(dividends = dividends)
    }

    @Test
    fun `analyzeWithLlm returns NotConfigured when repository reports it`() = runTest {
        coEvery { llmAnalysisRepository.analyzeStock(any(), any(), any()) } returns StockLlmAnalysisResult.NotConfigured
        val viewModel = createViewModelWithStock()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.analyzeWithLlm()
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.llmAnalysis).isEqualTo(StockLlmAnalysisState.NotConfigured)
    }

    @Test
    fun `analyzeWithLlm maps success to Success state with cache metadata`() = runTest {
        coEvery { llmAnalysisRepository.analyzeStock(any(), any(), any()) } returns StockLlmAnalysisResult.Success(
            StockLlmAnalysis("偏低", "稳", "可关注", listOf("波动")),
            analyzedAt = 123L,
            fromCache = true
        )

        val viewModel = createViewModelWithStock()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.analyzeWithLlm()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value.llmAnalysis
        assertThat(state).isInstanceOf(StockLlmAnalysisState.Success::class.java)
        val success = state as StockLlmAnalysisState.Success
        assertThat(success.analysis.valuation).isEqualTo("偏低")
        assertThat(success.analysis.action).isEqualTo("可关注")
        assertThat(success.analysis.risks).containsExactly("波动")
        assertThat(success.analyzedAt).isEqualTo(123L)
        assertThat(success.fromCache).isTrue()
    }

    @Test
    fun `analyzeWithLlm fetches three-period boll before delegating`() = runTest {
        coEvery { plane.getBoll("sz.000001", KlinePeriod.DAILY) } returns
            com.stock.dividend.data.repository.BollBand(middle = 10.0, upper = 11.0, lower = 9.0)
        coEvery { plane.getBoll("sz.000001", KlinePeriod.WEEKLY) } returns
            com.stock.dividend.data.repository.BollBand(middle = 10.0, upper = 11.0, lower = 9.0)
        coEvery { plane.getBoll("sz.000001", KlinePeriod.MONTHLY) } returns
            com.stock.dividend.data.repository.BollBand(middle = 12.0, upper = 14.0, lower = 10.0)

        val viewModel = createViewModelWithStock()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.analyzeWithLlm()
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.llmAnalysis).isInstanceOf(StockLlmAnalysisState.Success::class.java)
        coVerify { plane.getBoll("sz.000001", KlinePeriod.DAILY) }
        coVerify { plane.getBoll("sz.000001", KlinePeriod.WEEKLY) }
        coVerify { plane.getBoll("sz.000001", KlinePeriod.MONTHLY) }
        coVerify { llmAnalysisRepository.analyzeStock(any(), any(), false) }
    }

    @Test
    fun `analyzeWithLlm maps repository error to Error state`() = runTest {
        coEvery { llmAnalysisRepository.analyzeStock(any(), any(), any()) } returns
            StockLlmAnalysisResult.Error("API key 无效")

        val viewModel = createViewModelWithStock()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.analyzeWithLlm()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value.llmAnalysis
        assertThat(state).isInstanceOf(StockLlmAnalysisState.Error::class.java)
        assertThat((state as StockLlmAnalysisState.Error).message).isEqualTo("API key 无效")
    }

    @Test
    fun `clearLlmAnalysis resets state to Idle`() = runTest {
        val viewModel = createViewModelWithStock()
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.analyzeWithLlm()
        testDispatcher.scheduler.advanceUntilIdle()
        assertThat(viewModel.uiState.value.llmAnalysis).isInstanceOf(StockLlmAnalysisState.Success::class.java)

        viewModel.clearLlmAnalysis()

        assertThat(viewModel.uiState.value.llmAnalysis).isEqualTo(StockLlmAnalysisState.Idle)
    }

    @Test
    fun `analyzeWithLlm early returns without delegating when no dividends`() = runTest {
        val viewModel = createViewModelWithStock(dividends = emptyList())
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.analyzeWithLlm()
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.llmAnalysis).isEqualTo(StockLlmAnalysisState.Idle)
        coVerify(exactly = 0) { llmAnalysisRepository.analyzeStock(any(), any(), any()) }
    }

    @Test
    fun `analyzeWithLlm degrades missing boll periods without blocking`() = runTest {
        // 三周期全失败（setUp 默认 fetchBoll 返回 null）；repository 默认返回 Success
        val viewModel = createViewModelWithStock()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.analyzeWithLlm()
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.llmAnalysis).isInstanceOf(StockLlmAnalysisState.Success::class.java)
        assertThat(
            (viewModel.uiState.value.llmAnalysis as StockLlmAnalysisState.Success).analysis.valuation
        ).isEqualTo("")
    }

    @Test
    fun `analyzeWithLlm passes forceRefresh to repository`() = runTest {
        val viewModel = createViewModelWithStock()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.analyzeWithLlm(forceRefresh = true)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { llmAnalysisRepository.analyzeStock(any(), any(), true) }
    }

    // endregion

    // region 基本面加载与派息率补全（经 FundamentalsCacheRepository）

    @Test
    fun `fundamentals load and payout ratio enriched from dividends`() = runTest {
        // 原始基本面（payoutRatio=null，basicEps=1.20）——经缓存仓库返回
        coEvery { plane.getFundamentals("sz.000001", false) } returns Fundamentals(
            periods = listOf(
                Fundamentals.Period("2024-12-31", 10.0, 60.0, 8.0, 5.0, basicEps = 1.20, payoutRatio = null)
            )
        )
        // 对应报告期的每股派息 0.30 → 派息率 0.30/1.20*100 = 25
        val dividends = listOf(
            DividendEntity(
                id = "sz.000001_2024-12-31", stockCode = "sz.000001",
                reportDate = "2024-12-31", cashPerShare = 0.30, dividendYield = 5.0
            )
        )

        val viewModel = createViewModelWithStock(dividends = dividends)
        testDispatcher.scheduler.advanceUntilIdle()

        val fundamentals = viewModel.uiState.value.fundamentals
        assertThat(fundamentals).isNotNull()
        assertThat(fundamentals!!.periods).hasSize(1)
        assertThat(fundamentals.periods[0].payoutRatio).isEqualTo(25.0)
        assertThat(viewModel.uiState.value.fundamentalsLoading).isFalse()
    }

    @Test
    fun `fundamentals degrade to null when cache repository throws and loading flag resets`() = runTest {
        coEvery { plane.getFundamentals(any(), any()) } throws RuntimeException("network")

        val viewModel = createViewModelWithStock()
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.fundamentals).isNull()
        // 红线 #3：失败也要复位 loading
        assertThat(viewModel.uiState.value.fundamentalsLoading).isFalse()
    }

    @Test
    fun `refreshFundamentals forces refresh through cache repository`() = runTest {
        coEvery { plane.getFundamentals("sz.000001", false) } returns Fundamentals(
            periods = listOf(Fundamentals.Period("2024-12-31", 12.0, 60.0, 8.0, 5.0, basicEps = 1.0, payoutRatio = null))
        )
        coEvery { plane.getFundamentals("sz.000001", true) } returns Fundamentals(
            periods = listOf(Fundamentals.Period("2025-03-31", 11.0, 61.0, 6.0, 4.0, basicEps = 1.0, payoutRatio = null))
        )

        val viewModel = createViewModelWithStock()
        testDispatcher.scheduler.advanceUntilIdle()
        val before = viewModel.uiState.value.fundamentals

        viewModel.refreshFundamentals()
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.fundamentals).isNotEqualTo(before)
        assertThat(viewModel.uiState.value.fundamentals!!.periods[0].reportDate).isEqualTo("2025-03-31")
        coVerify { plane.getFundamentals("sz.000001", true) }
    }

    // endregion

    // region 股息率取当年累计（多次分红累加）

    @Test
    fun `latest dividend yield sums multiple dividends in the same year for prompt`() = runTest {
        val inputSlot = slot<StockLlmInput>()
        coEvery { llmAnalysisRepository.analyzeStock(capture(inputSlot), any(), any()) } returns
            StockLlmAnalysisResult.Success(StockLlmAnalysis("ok", "", "", emptyList()))

        // 同一年（2024）两笔分红：2.0% + 3.0% = 5.0%（累计股息率）
        val dividends = listOf(
            DividendEntity(
                id = "sz.000001_2024-06-30", stockCode = "sz.000001",
                reportDate = "2024-06-30", cashPerShare = 0.10, dividendYield = 2.0
            ),
            DividendEntity(
                id = "sz.000001_2024-12-31", stockCode = "sz.000001",
                reportDate = "2024-12-31", cashPerShare = 0.15, dividendYield = 3.0
            )
        )

        val viewModel = createViewModelWithStock(dividends = dividends)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.analyzeWithLlm()
        testDispatcher.scheduler.advanceUntilIdle()

        // 喂给仓库的输入快照应为当年累计 5.0%，而非单笔 2.0% 或 3.0%
        assertThat(inputSlot.captured.latestDividendYield).isEqualTo(5.0)
    }

    // endregion

    // region K 线股息率网格（dps 派生 + 新鲜度触发）

    @Test
    fun `dps derives from latest yearly dividends with same-year sum`() = runTest {
        // 2023 年 0.10；2024 年两笔 0.10 + 0.15 = 0.25 → dps 取最新年度（2024）累计 0.25
        val dividends = listOf(
            DividendEntity(
                id = "sz.000001_2023-12-31", stockCode = "sz.000001",
                reportDate = "2023-12-31", cashPerShare = 0.10, dividendYield = 2.0
            ),
            DividendEntity(
                id = "sz.000001_2024-06-30", stockCode = "sz.000001",
                reportDate = "2024-06-30", cashPerShare = 0.10, dividendYield = 2.0
            ),
            DividendEntity(
                id = "sz.000001_2024-12-31", stockCode = "sz.000001",
                reportDate = "2024-12-31", cashPerShare = 0.15, dividendYield = 3.0
            )
        )

        val viewModel = createViewModel(dividends = dividends)
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.dps).isWithin(1e-9).of(0.25)
    }

    @Test
    fun `dps is null when no dividends`() = runTest {
        val viewModel = createViewModel(dividends = emptyList())
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.dps).isNull()
    }

    @Test
    fun `init triggers dividend freshness check through plane`() = runTest {
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        // K 线股息率网格线的 DPS 口径依赖分红新鲜（空表/超 7 天自动拉网）
        coVerify(exactly = 1) { plane.ensureDividendsFresh("sz.000001") }
    }

    // endregion
}
