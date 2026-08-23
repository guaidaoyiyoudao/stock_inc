package com.stock.dividend.viewmodel

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stock.dividend.data.local.entity.StockEntity
import com.stock.dividend.data.plane.MarketDataPlane
import com.stock.dividend.data.repository.BollBand
import com.stock.dividend.data.repository.DividendIncomeRepository
import com.stock.dividend.data.repository.ForecastCalculator
import com.stock.dividend.data.repository.GridPlanRepository
import com.stock.dividend.data.repository.IndexQuote
import com.stock.dividend.data.repository.KlinePeriod
import com.stock.dividend.data.repository.MarketDataRepository
import com.stock.dividend.data.repository.MarketListItem
import com.stock.dividend.data.repository.MarketMood
import com.stock.dividend.data.repository.MarketMoodCalculator
import com.stock.dividend.data.repository.PortfolioDiagnosisAssembler
import com.stock.dividend.data.repository.PortfolioHealthGrade
import com.stock.dividend.data.repository.PortfolioRiskDiagnosis
import com.stock.dividend.data.repository.PortfolioRiskDiagnoser
import com.stock.dividend.data.repository.QuoteSnapshot
import com.stock.dividend.data.repository.StrategyInputAssembler
import com.stock.dividend.data.repository.StrategyPlanRepository
import com.stock.dividend.data.repository.TransactionRepository
import com.stock.dividend.data.repository.TodayBriefingCoordinator
import com.stock.dividend.data.repository.TodaySignal
import com.stock.dividend.data.repository.TodaySignalAggregator
import com.stock.dividend.data.repository.TodaySignalInput
import com.stock.dividend.data.repository.TodaySignalType
import com.stock.dividend.data.repository.TodayStockSnapshot
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.Year
import javax.inject.Inject

@Stable
data class TodayUiState(
    val marketValue: Double = 0.0,
    val todayPnl: Double = 0.0,
    val todayPnlRate: Double = 0.0,
    val totalPnl: Double = 0.0,
    val totalPnlRate: Double = 0.0,
    val indexSh: Double? = null,
    val indexHs300: Double? = null,
    val beatHs300: Double? = null,
    val signals: List<TodaySignal> = emptyList(),
    val briefing: String? = null,      // null = AI 卡不显示
    val isLoading: Boolean = false,
    val hasHoldings: Boolean = false,
    val dataStale: Boolean = false,
    // ── 市场环境（2026-08-15 金融分析师视角新增）──
    val indices: List<IndexQuote> = emptyList(),            // 四大指数（上证/深证/沪深300/创业板）
    val marketMood: MarketMood = MarketMood(),              // 领涨/领跌板块 Top3
    val inflowIndustries: List<MarketListItem> = emptyList(), // 主力净流入板块 Top3
    // ── 组合体检 ──
    val diagnosis: PortfolioRiskDiagnosis? = null,
    val healthGrade: PortfolioHealthGrade? = null,
    // ── 股息现金流（本年）──
    val yearDividendReceived: Double = 0.0,
    val yearDividendForecast: Double = 0.0,
)

/**
 * 今日首页 ViewModel。多独立 collector 聚合「今日一瞥」（参考 PortfolioViewModel 模式 §4.2）。
 *
 * - Collector A：自选+持仓变化 → hasHoldings + 触发组合/信号重算
 * - Collector B：刷新触发（含首次 onStart）→ 拉价 + 指数 → 算市值/盈亏/对照 + 并行补
 *   信号/市场环境/组合体检（金融分析师视角三件套，各源吞异常互不拖累，红线 #2）
 * - Collector C：AI 简报（按今日读缓存）
 * - Collector D：股息现金流（本年已到账 vs 全年预测，响应式）
 *
 * * *信号口径**：今日页经数据平面并发拉**周线 BOLL**（平面内置 Semaphore(3) 限流）→「跌破周线 BOLL 下轨」信号；
 * 加「股息率达买入线」+ 网格下一档 + 分红倒计时。三周期共振 BUY 仍留评估页（需日+周+月，重）。
 *
 * **体检口径**：[PortfolioDiagnosisAssembler] 复用本页已刷新行情装配（不重复拉价），
 * 与 Agent 工具 `diagnose_portfolio` 同源；分级由 [PortfolioRiskDiagnoser.grade] 纯函数产出。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TodayViewModel @Inject constructor(
    private val marketDataPlane: MarketDataPlane,
    private val gridPlanRepository: GridPlanRepository,
    private val strategyPlanRepository: StrategyPlanRepository,
    private val strategyInputAssembler: StrategyInputAssembler,
    private val briefingCoordinator: TodayBriefingCoordinator,
    private val diagnosisAssembler: PortfolioDiagnosisAssembler,
    private val dividendIncomeRepository: DividendIncomeRepository,
    private val transactionRepository: TransactionRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TodayUiState())
    val uiState = _uiState.asStateFlow()

    private val _refreshTrigger = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    @Volatile private var lastStocks: List<StockEntity> = emptyList()
    @Volatile private var lastSnapshots: Map<String, QuoteSnapshot> = emptyMap()
    @Volatile private var lastIndices: List<IndexQuote> = emptyList()

    private val allStocksFlow = marketDataPlane.observeAllStocks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        // Collector A: hasHoldings 标记（是否有持仓股）
        viewModelScope.launch {
            allStocksFlow.collect { stocks ->
                _uiState.update { it.copy(hasHoldings = stocks.any { s -> s.shares > 0 }) }
            }
        }

        // Collector B: stocks + 刷新触发 → 拉价 + 指数 → 重算组合/信号。
        // 关键：用 flatMapLatest 把 stocks 与 refresh 绑定，确保 fetchQuoteSnapshots 总用最新 stocks
        // （避免 stocks / refresh 两个独立 collector 的竞态：refresh 先跑时 lastStocks 还空 → 短路空 map）。
        viewModelScope.launch {
            allStocksFlow.flatMapLatest { stocks ->
                lastStocks = stocks
                if (stocks.isEmpty()) flowOf(emptyMap()) else {
                    _refreshTrigger
                        .onStart { emit(Unit) }
                        .map {
                            _uiState.update { it.copy(isLoading = true) }
                            runCatching { marketDataPlane.getQuoteSnapshots(stocks, force = true) }.getOrDefault(emptyMap())
                        }
                }
            }.collect { snapshots ->
                val indices = runCatching { marketDataPlane.getIndexQuotes() }.getOrDefault(emptyList())
                val stale = snapshots.isEmpty() && lastStocks.isNotEmpty()
                lastSnapshots = snapshots
                lastIndices = indices
                recomputePortfolio(snapshots, indices)
                // 信号/市场/体检并行补算（各源吞异常互不拖累），一起完成后复位 loading（红线 #3）
                coroutineScope {
                    val jobs = listOf(
                        async { recomputeSignals(snapshots) },
                        async { recomputeMarket(indices) },
                        async { recomputeDiagnosis(snapshots) },
                    )
                    jobs.awaitAll()
                }
                _uiState.update { it.copy(isLoading = false, dataStale = stale) }
            }
        }

        // Collector C: AI 简报（按今日读缓存，失败 null）
        viewModelScope.launch {
            val text = runCatching { briefingCoordinator.read(LocalDate.now()) }.getOrNull()
            _uiState.update { it.copy(briefing = text) }
        }

        // Collector D: 股息现金流——本年已到账 + 全年预测（响应式，收入记录变化自动刷新）
        viewModelScope.launch {
            combine(
                dividendIncomeRepository.observeTotalByYear(Year.now().value),
                dividendIncomeRepository.observeForecastTotal(),
            ) { received, forecast -> received to forecast }
                .collect { (received, forecast) ->
                    _uiState.update {
                        it.copy(yearDividendReceived = received, yearDividendForecast = forecast)
                    }
                }
        }
    }

    /** 组合市值 / 今日盈亏 / 累计盈亏 / 大盘对照（纯算，无 IO）。 */
    private fun recomputePortfolio(snapshots: Map<String, QuoteSnapshot>, indices: List<IndexQuote>) {
        val holdings = lastStocks.filter { it.shares > 0 }
        var marketValue = 0.0
        var todayPnl = 0.0
        var costBase = 0.0
        for (s in holdings) {
            val q = snapshots[s.code] ?: continue
            val price = q.price ?: continue
            val prevClose = q.prevClose ?: price
            marketValue += price * s.shares
            todayPnl += (price - prevClose) * s.shares
            costBase += s.costPerShare * s.shares
        }
        val totalPnl = marketValue - costBase
        val totalPnlRate = if (costBase > 0) totalPnl / costBase * 100.0 else 0.0
        val todayPnlRate = if (costBase > 0) todayPnl / costBase * 100.0 else 0.0
        val sh = indices.firstOrNull { it.code == "000001" }?.changePct
        val hs300 = indices.firstOrNull { it.code == "000300" }?.changePct
        val beat = hs300?.let { todayPnlRate - it }
        _uiState.update {
            it.copy(
                marketValue = marketValue,
                todayPnl = todayPnl,
                todayPnlRate = todayPnlRate,
                totalPnl = totalPnl,
                totalPnlRate = totalPnlRate,
                indexSh = sh,
                indexHs300 = hs300,
                beatHs300 = beat,
            )
        }
    }

    /** 信号聚合：周线 BOLL（跌破下轨）+ 股息率达线 + 网格 + 分红倒计时。各源吞异常返回空（红线 #2）。 */
    private suspend fun recomputeSignals(snapshots: Map<String, QuoteSnapshot>) {
        val stocks = lastStocks
        val bond = runCatching { marketDataPlane.get10YBondYield() }
            .getOrDefault(com.stock.dividend.data.repository.BondYieldRepository.DEFAULT_YIELD)
        val dividends = runCatching { marketDataPlane.getAllDividendsWithExDate() }.getOrDefault(emptyList())
        val gridPlans = runCatching { gridPlanRepository.observeAll().first() }
            .getOrDefault(emptyList())
        val strategyPlans = runCatching { strategyPlanRepository.observeAll().first() }
            .getOrDefault(emptyList())
        val transactions = runCatching { transactionRepository.getAll() }.getOrDefault(emptyList())
        // 策略统一评估：装配器按类型采集输入（日线/DPS/估值/除权/持仓），调度器分发计算
        val strategyEvaluations: Map<String, com.stock.dividend.data.repository.StrategyEvaluation> =
            if (strategyPlans.isEmpty()) emptyMap()
            else runCatching {
                val inputs = strategyInputAssembler.assemble(
                    strategyPlans,
                    snapshots.mapNotNull { (code, q) ->
                        q.price?.takeIf { it > 0.0 }?.let { code to it }
                    }.toMap()
                )
                strategyPlans.mapNotNull { plan ->
                    inputs[plan.id]?.let { input ->
                        com.stock.dividend.data.repository.StrategyEvaluator.evaluate(plan, input)
                            ?.let { plan.id to it }
                    }
                }.toMap()
            }.getOrDefault(emptyMap())
        val divByCode = dividends.groupBy { it.stockCode }
        // 并发拉周线 BOLL（数据平面内置 Semaphore(3) 限流 + 60s 内存缓存，红线 #5）
        val bollByCode: Map<String, BollBand?> = if (stocks.isEmpty()) emptyMap()
        else coroutineScope {
            stocks.map { entity ->
                async {
                    entity.code to runCatching {
                        marketDataPlane.getBoll(entity.code, KlinePeriod.WEEKLY)
                    }.getOrNull()
                }
            }.awaitAll().toMap()
        }
        val stockSnapshots = stocks.map { entity ->
            TodayStockSnapshot(
                code = entity.code,
                name = entity.name,
                price = snapshots[entity.code]?.price,
                weeklyBand = bollByCode[entity.code],   // 周线 BOLL（跌破下轨信号用）
                latestYearlyDividend = divByCode[entity.code]?.let { ForecastCalculator.latestYearlyCashPerShare(it) },
                bondYield10Y = bond,
                buyThresholdMultiplier = entity.buyThresholdMultiplier,
            )
        }
        val input = TodaySignalInput(
            stocks = stockSnapshots,
            gridPlans = gridPlans,
            gridCurrentPrices = snapshots.mapValues { it.value.price ?: 0.0 },
            dividends = dividends,
            today = LocalDate.now(),
            // 已买档不再出现在「网格下一档」信号里（每档只买一次）
            gridTransactionsByStock = transactions.groupBy { it.stockCode },
            // 策略信号（全部类型统一评估：买点窗口 / 卖出阈值）
            strategyPlans = strategyPlans,
            strategyEvaluations = strategyEvaluations,
        )
        val signals = runCatching { TodaySignalAggregator.aggregate(input) }.getOrDefault(emptyList())
        _uiState.update { it.copy(signals = signals) }
    }

    /**
     * 市场环境：四大指数 + 领涨领跌板块 + 主力净流入板块。
     * 各源吞异常返回空（红线 #2）；无持仓时同样计算（看大盘不需要持仓）。
     */
    private suspend fun recomputeMarket(indices: List<IndexQuote>) {
        val byCode = indices.associateBy { it.code }
        val topIndices = TODAY_INDICES.mapNotNull { byCode[it] }
        val industries = runCatching {
            marketDataPlane.getIndustryList(MarketDataRepository.SortBy.CHANGE, limit = 30)
        }.getOrDefault(emptyList())
        val mood = MarketMoodCalculator.splitGainersLosers(industries)
        val inflow = runCatching {
            marketDataPlane.getIndustryList(MarketDataRepository.SortBy.INFLOW, limit = 3)
        }.getOrDefault(emptyList())
        _uiState.update {
            it.copy(indices = topIndices, marketMood = mood, inflowIndustries = inflow)
        }
    }

    /** 组合体检：复用本页已刷新行情装配（不重复拉价），分级交给 [PortfolioRiskDiagnoser.grade]。 */
    private suspend fun recomputeDiagnosis(snapshots: Map<String, QuoteSnapshot>) {
        val holdings = lastStocks.filter { it.shares > 0 }
        val prices = snapshots.mapNotNull { (code, q) ->
            q.price?.takeIf { it > 0.0 }?.let { code to it }
        }.toMap()
        val diagnosis = runCatching { diagnosisAssembler.assemble(holdings, prices) }.getOrNull()
        _uiState.update {
            it.copy(
                diagnosis = diagnosis,
                healthGrade = diagnosis?.let { PortfolioRiskDiagnoser.grade(it) },
            )
        }
    }

    /** 用户下拉刷新：重拉行情 + 指数。 */
    fun refresh() {
        viewModelScope.launch { _refreshTrigger.emit(Unit) }
    }

    companion object {
        /** 今日页展示的四大指数（code 为接口返回 6 位代码）。 */
        private val TODAY_INDICES = listOf("000001", "399001", "000300", "399006")
    }
}
