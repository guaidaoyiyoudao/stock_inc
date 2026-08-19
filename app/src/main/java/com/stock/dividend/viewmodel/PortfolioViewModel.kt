package com.stock.dividend.viewmodel

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Stable
import com.stock.dividend.data.repository.EvaluatedStock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stock.dividend.data.local.dao.TransactionDao
import com.stock.dividend.data.local.entity.EXPENSE_PERIOD_MONTHLY
import com.stock.dividend.data.local.entity.StockEntity
import com.stock.dividend.data.local.entity.TransactionEntity
import com.stock.dividend.data.notification.NotificationCheckCoordinator
import com.stock.dividend.data.repository.BollBand
import com.stock.dividend.data.repository.BondYieldRepository
import com.stock.dividend.data.repository.DividendThresholds
import com.stock.dividend.data.repository.ForecastCalculator
import com.stock.dividend.data.repository.HoldingAction
import com.stock.dividend.data.repository.HoldingRecommendation
import com.stock.dividend.data.repository.HoldingRecommender
import com.stock.dividend.data.repository.KlinePeriod
import com.stock.dividend.data.repository.LlmAnalysisRepository
import com.stock.dividend.data.repository.LlmAnalysisResult
import com.stock.dividend.data.repository.TradeStrategyRepository
import com.stock.dividend.data.repository.toUserStrategyRef
import com.stock.dividend.data.repository.LlmAnalysisState
import com.stock.dividend.data.repository.PortfolioAdvisor
import com.stock.dividend.data.repository.PortfolioLlmInput
import com.stock.dividend.data.repository.PortfolioLlmStockDetail
import com.stock.dividend.data.repository.PortfolioSignals
import com.stock.dividend.data.repository.QuoteSnapshot
import com.stock.dividend.data.repository.RealizedPnl
import com.stock.dividend.data.repository.RealizedPnlCalculator
import com.stock.dividend.data.repository.StockLlmInput
import com.stock.dividend.data.repository.LivingExpenseRepository
import com.stock.dividend.data.repository.NotificationRuleRepository
import com.stock.dividend.data.plane.MarketDataPlane
import com.stock.dividend.data.repository.StockRepository
import com.stock.dividend.data.repository.computeBuyThreshold
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject

@Stable
data class PortfolioItem(
    val code: String,
    val name: String,
    val marketCode: String,
    val shares: Int,
    val costPerShare: Double,
    val industry: String = "",
    val currentPrice: Double? = null,
    val marketValue: Double? = null,
    val totalCost: Double,
    val unrealizedPnl: Double? = null,
    val unrealizedPnlRate: Double? = null,
    /** 该股票累计已实现盈亏（FIFO 结转，仅含已平仓部分）；null = 无卖出记录或未加载。 */
    val realizedPnl: Double? = null,
    val actualWeight: Double? = null,
    /** 个股目标：占其所属行业的 %（两层配比模型，行业主个股次）。 */
    val targetWeight: Double,
    val targetValue: Double? = null,
    val targetDiff: Double? = null,
    /** 该股票的所有标签，由 PortfolioViewModel 从 stock_tags 表注入。 */
    val tags: List<String> = emptyList()
)

/**
 * 按行业聚合的分组。actualWeight 为该行业占组合总资产 %；
*  targetWeight 为该行业目标占总资产 %；个股目标在其内部各自占行业 %。
 */
@Stable
data class IndustryGroup(
    val name: String,                        // "银行"；空串归入"未分类"
    val stocks: List<PortfolioItem>,
    val holdingsMarketValue: Double,
    val actualWeight: Double?,               // 行业市值 / 总资产 * 100
    val targetWeight: Double,                // 行业目标占总资产 %
    val targetValue: Double?,                // 总资产 * 行业目标 / 100
    val stockTargetSum: Double               // 行业内个股目标占比之和（应≈100，软提示）
)

/**
 * 自选/持仓股的股息预测数据（用于 shares>0 的持仓股和 shares=0 的纯自选股）。
 * 合并自选 tab 后统一由此 ViewModel 提供。
 */
@Stable
data class StockForecast(
    val shares: Int,
    val avgCashPerShare: Double,
    val forecastIncome: Double,
    val actualYears: Int,
    val currentPrice: Double? = null,
    val marketValue: Double? = null,
    val latestYearlyDividend: Double? = null,
    /** 1/3/5 年每股预测（组合级 LLM 深度数据用）；无足够股息数据时为 null。 */
    val llmForecast: StockLlmInput.StockLlmForecast? = null
)

// EvaluatedStock 迁移至 data/repository/EvaluatedStock.kt（领域 DTO）

@Stable
data class PortfolioUiState(
    val items: List<PortfolioItem> = emptyList(),
    /** shares=0 的纯自选股（合并自选 tab 后仍展示，但与持仓股区分样式）。 */
    val watchlist: List<StockEntity> = emptyList(),
    val stockForecasts: Map<String, StockForecast> = emptyMap(),
    /** 实时行情快照（PE/PB/涨跌幅/换手/市值等）；按 code 索引，与现价同生命周期，纯内存。 */
    val stockQuotes: Map<String, QuoteSnapshot> = emptyMap(),
    /** 周线 BOLL 带（按 code 缓存）。null 值表示已尝试但无数据（防重试）；缺 key 表示尚未加载。 */
    val stockBands: Map<String, BollBand?> = emptyMap(),
    val forecastTotal: Double = 0.0,
    val livingExpenseTargetAmount: Double? = null,
    val fireProgress: Float? = null,
    val industryGroups: List<IndustryGroup> = emptyList(),
    val industryTargetSum: Double = 0.0,     // 行业目标合计（软提示）
    val totalAssets: Double = 0.0,
    val holdingsMarketValue: Double = 0.0,
    val totalCost: Double = 0.0,
    val totalPnl: Double = 0.0,
    val totalPnlRate: Double = 0.0,
    /** 全组合累计已实现盈亏（FIFO）；null = 无任何卖出记录或尚未加载交易流水。 */
    val totalRealizedPnl: Double? = null,
    /** 已实现盈亏率（%）= 累计已实现盈亏 / 累计结转成本；无结转成本时为 null。 */
    val totalRealizedPnlRate: Double? = null,
    /** 总成本息率 = Σ(最新年度每股股息 × 持仓股数) / Σ(成本价 × 持仓股数)，无数据/分母为 0 时为 null。 */
    val costDividendYield: Double? = null,
    val targetWeightSum: Double = 0.0,
    val isLoading: Boolean = false,
    val isRefreshingIndustry: Boolean = false,
    val error: String? = null,
    val editingCode: String? = null,
    val editingWeightInput: String = "",
    val editingWeightError: String? = null,
    val editingTotalAssets: Boolean = false,
    val editingTotalAssetsInput: String = "",
    val editingTotalAssetsError: String? = null,
    val editingIndustry: String? = null,
    val editingIndustryWeightInput: String = "",
    val editingIndustryWeightError: String? = null,
    val deletedStock: StockEntity? = null,
    val deletedTransactions: List<TransactionEntity> = emptyList(),
    // ── 筛选 ──────────────────────────────────────────────────────
    /** 候选行业（来自所有持仓+自选，去重排序，含「未分类」若有空 industry）。 */
    val availableIndustries: List<String> = emptyList(),
    /** 全局已存在的所有标签。 */
    val availableTags: List<String> = emptyList(),
    val selectedIndustries: Set<String> = emptySet(),
    val selectedTags: Set<String> = emptySet(),
    /** 筛选后的持仓股（直接渲染）。 */
    val filteredItems: List<PortfolioItem> = emptyList(),
    /** 筛选后的自选股（直接渲染）。 */
    val filteredWatchlist: List<StockEntity> = emptyList(),
    // ── 一键评估 ────────────────────────────────────────────────────
    /** 评估进行中。 */
    val isEvaluating: Boolean = false,
    /** 评估结果；null = 未评估过，空列表 = 评估过但当前筛选下无股票。 */
    val evaluation: List<EvaluatedStock>? = null,
    /** 组合策略信号（评估后产出）。 */
    val portfolioSignals: PortfolioSignals? = null,
    /** 日线 BOLL（评估期产出，供 prompt 三周期位置用）。 */
    val dailyBands: Map<String, BollBand?> = emptyMap(),
    /** 月线 BOLL（评估期产出，供 prompt 三周期位置用）。 */
    val monthlyBands: Map<String, BollBand?> = emptyMap(),
    /** LLM 解读状态。 */
    val llmAnalysis: LlmAnalysisState = LlmAnalysisState.Idle
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PortfolioViewModel @Inject constructor(
    /** 读股市数据唯一入口：行情/股息/BOLL/基本面/国债。 */
    private val marketDataPlane: MarketDataPlane,
    /** 仅用于写操作与本地域数据（改持仓/行业目标/删股等）；读行情一律走数据平面。 */
    private val stockRepository: StockRepository,
    private val livingExpenseRepository: LivingExpenseRepository,
    private val transactionDao: TransactionDao,
    private val notificationCheckCoordinator: NotificationCheckCoordinator,
    private val notificationRuleRepository: NotificationRuleRepository,
    private val llmAnalysisRepository: LlmAnalysisRepository,
    private val tradeStrategyRepository: TradeStrategyRepository,
    @ApplicationContext context: Context
) : ViewModel() {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

    private val _uiState = MutableStateFlow(PortfolioUiState())
    val uiState: StateFlow<PortfolioUiState> = _uiState.asStateFlow()

    private val _refreshTrigger = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /**
     * 周线 BOLL 带缓存（按 code）。key 存在即表示已尝试加载：
     *  - value 非 null → 有有效 BOLL 数据；
     *  - value 为 null → 已尝试但数据不足/失败，避免切到 BOLL 视图时反复重试。
     */
    private val _stockBands = MutableStateFlow<Map<String, BollBand?>>(emptyMap())

    /** 用户配置的评估门槛（由设置页写入 notification_rules）。 */
    private val _evalThresholds = MutableStateFlow(DividendThresholds())

    /** Latest non-empty holding snapshot, kept so price refresh can recompute without re-reading the DAO. */
    @Volatile
    private var lastStocksSnapshot: List<StockEntity> = emptyList()

    /**
     * 全部股票快照（含 shares=0 的纯自选股），供现价刷新/行业回填覆盖自选股。
     * 与 [lastStocksSnapshot] 分离：items 仍只含持仓股（shares>0），
     * 但拉价/拉行业必须覆盖自选股，否则自选股卡片永远拿不到现价、行业。
     */
    @Volatile
    private var lastAllStocksSnapshot: List<StockEntity> = emptyList()

    /** Latest price snapshot, preserved across holding-stream re-emissions so UI does not flash to "—". */
    @Volatile
    private var lastPricesSnapshot: Map<String, Double> = emptyMap()

    /** User-configured total assets (the denominator for actual weight, basis for target value). */
    @Volatile
    private var currentTotalAssets: Double = 0.0

    /** 全部股票（含 shares=0 的纯自选股），合并自选 tab 后两者共用同一数据源。 */
    private val allStocksFlow = marketDataPlane.observeAllStocks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val holdingsFlow = allStocksFlow
        .map { stocks -> stocks.filter { it.shares > 0 } }

    /** 年化生活支出（月支出 × 12，年支出不变）；用于 FIRE 进度。 */
    private val livingExpenseTargetFlow = livingExpenseRepository.observeExpenses()
        .map { expenses ->
            expenses.sumOf { expense ->
                when (expense.period) {
                    EXPENSE_PERIOD_MONTHLY -> expense.amount * 12
                    else -> expense.amount
                }
            }.takeIf { it > 0.0 }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /**
     * 每股的股息预测。forecast 只对 shares>0 的持仓股计算（ForecastCalculator 按 shares 折算收入）。
     * latestYearlyDividend 对所有股都可计算（自选股也要在卡片上画「股息率→价位」横轴），
     * 因此这里对所有股订阅 dividend，但仅当 shares>0 才计入 forecastTotal。
     *
     * 注意：shares=0 的纯自选股 [ForecastCalculator.calculateForecastIncome] 会返回 null，
     * 但自选卡片仍需要 [StockForecast]（用以承载 currentPrice / latestYearlyDividend / bollBand），
     * 故这里在 result 为 null 时也构造一个占位 forecast（avgCashPerShare/forecastIncome 均为 0）。
     */
    private val forecastMapFlow = allStocksFlow.flatMapLatest { stocks ->
        if (stocks.isEmpty()) {
            flowOf(emptyMap())
        } else {
            val forecastFlows = stocks.map { stock ->
                marketDataPlane.observeDividends(stock.code).map { dividends ->
                    val years = stock.yieldPeriod.toIntOrNull() ?: 3
                    val result = ForecastCalculator.calculateForecastIncome(
                        dividends, stock.shares, years
                    )
                    // 1/3/5 年窗口（本地纯计算）；样本不足的窗口回退到首个可用值
                    val llmForecast = listOf(1, 3, 5).mapNotNull { y ->
                        ForecastCalculator.calculateForecastIncome(dividends, stock.shares, y)
                            ?.let { y to it.avgCashPerShare }
                    }.toMap().let { m ->
                        val base = m.values.firstOrNull() ?: return@let null
                        StockLlmInput.StockLlmForecast(
                            avgCashPerShare1Y = m[1] ?: base,
                            avgCashPerShare3Y = m[3] ?: base,
                            avgCashPerShare5Y = m[5] ?: base,
                            actualYears = result?.actualYears ?: 0
                        )
                    }
                    val forecast = result?.let {
                        StockForecast(
                            shares = stock.shares,
                            avgCashPerShare = it.avgCashPerShare,
                            // shares=0 的自选股 forecastIncome 恒为 0（shares * avg = 0），不计入合计
                            forecastIncome = stock.shares * it.avgCashPerShare,
                            actualYears = it.actualYears,
                            latestYearlyDividend = ForecastCalculator.latestYearlyCashPerShare(dividends),
                            llmForecast = llmForecast
                        )
                    } ?: StockForecast(
                        // 占位：result 为 null（shares<=0 或无足够股息记录）时仍要为自选卡保留槽位，
                        // 否则 currentPrice/latestYearlyDividend 无处挂载，自选股卡片现价永远为空。
                        shares = stock.shares,
                        avgCashPerShare = 0.0,
                        forecastIncome = 0.0,
                        actualYears = 0,
                        latestYearlyDividend = ForecastCalculator.latestYearlyCashPerShare(dividends),
                        llmForecast = llmForecast
                    )
                    stock.code to forecast
                }
            }
            combine(forecastFlows) { results ->
                results.associate { it.first to it.second }
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** 全量 (code → tags) 映射，订阅 stock_tags 表。 */
    private val tagsByCodeFlow = stockRepository.observeAllStockTags()
        .map { list -> list.groupBy { it.stockCode }.mapValues { it.value.map { e -> e.tag } } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** 所有出现过的标签（去重排序）。 */
    private val allTagsFlow = stockRepository.observeAllTags()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * 全量交易流水（响应式），按 stockCode 分组后用 [RealizedPnlCalculator]（FIFO）
     * 计算每只股票的累计已实现盈亏。供组合摘要 + 持仓卡片展示。
     */
    private val realizedPnlByCodeFlow: Flow<Map<String, RealizedPnl>> = transactionDao.observeAll()
        .map { all ->
            all.groupBy { it.stockCode }
                .mapValues { (_, txs) -> RealizedPnlCalculator.calculate(txs) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    init {
        currentTotalAssets = readTotalAssetsFromPrefs()
        _uiState.update { it.copy(totalAssets = currentTotalAssets) }

        // Collector 1: rebuild items whenever holdings change, preserving the last known prices.
        viewModelScope.launch {
            holdingsFlow.collect { stocks ->
                lastStocksSnapshot = stocks
                // 冷启动 lastPricesSnapshot 为空时，先用 price_cache 兜底，避免 UI 一片"—"；
                // 已有网络价时跳过（Collector 2 后台刷新会覆盖缓存）。
                if (lastPricesSnapshot.isEmpty() && stocks.isNotEmpty()) {
                    val cached = marketDataPlane.cachedPrices(stocks.map { it.code })
                    if (cached.isNotEmpty()) lastPricesSnapshot = cached
                }
                publish(recompute(stocks, lastPricesSnapshot))
            }
        }

        // Collector 2: fetch prices on refresh trigger and merge with the latest holdings.
        // 关键：订阅 allStocksFlow（含 shares=0 的自选股），否则自选股永远拉不到现价——
        // 历史上这里订的是 holdingsFlow(shares>0)，导致纯自选股卡片现价永远是缓存兜底，
        // 且仅有自选股时 flatMapLatest 短路成 flowOf(emptyMap())，刷新按钮失效。
        // 下游 recompute 仍用 lastStocksSnapshot(仅持仓) 保证 items 只含持仓股。
        viewModelScope.launch {
            allStocksFlow
                .flatMapLatest { stocks ->
                    lastAllStocksSnapshot = stocks
                    if (stocks.isEmpty()) flowOf(emptyMap()) else {
                        _refreshTrigger.onStart { emit(Unit) }.conflate()
                            .map {
                                _uiState.update { it.copy(isLoading = true) }
                                try {
                                    // 一次请求拿全量行情（PE/PB/涨跌/市值等），从中提取 price 喂下游
                                    // recompute/通知链路，避免再发一次只取现价的 fetchQuotes 请求。
                                    marketDataPlane.getQuoteSnapshots(stocks, force = true)
                                } catch (_: Exception) {
                                    // fetchQuoteSnapshots 自身已吞异常返回空 map，这里兜底网络层之外的问题
                                    emptyMap()
                                }
                            }
                    }
                }
                .collect { snapshots ->
                    // snapshots: Map<String, QuoteSnapshot>。提取 price 喂既有现价链路（recompute/通知），
                    // 完整快照写入 stockQuotes 供 UI 展示 PE/PB/涨跌幅等。
                    val prices = snapshots.mapValues { it.value.price ?: 0.0 }
                        .filterValues { it > 0.0 }
                    // 关键：无论 prices 是否为空（网络失败），都必须结束 loading，
                    // 否则悬浮刷新按钮会因 enabled=!isRefreshing 被永久禁用，卡死。
                    val effectivePrices = if (prices.isNotEmpty()) {
                        lastPricesSnapshot = prices
                        persistRefreshTimestamp()
                        prices
                    } else {
                        // 网络失败时保留缓存价（lastPricesSnapshot 含 Collector 1 填充的兜底），
                        // 而非用空价重算导致 UI 显示"—"。
                        lastPricesSnapshot
                    }
                    publish(recompute(lastStocksSnapshot, effectivePrices))
                    // 把最新价同步进 forecast 卡片（现价/市值，覆盖自选股），并对持仓触发通知检查。
                    // 注意：stockForecasts 用全量价（自选股卡片也依赖现价画「股息率→价位」横轴），
                    //       但通知只针对持仓股（shares>0）。
                    val holdings = lastStocksSnapshot
                    if (prices.isNotEmpty()) {
                        _uiState.update { state ->
                            state.copy(
                                stockForecasts = state.stockForecasts.mapValues { (code, f) ->
                                    val p = prices[code]
                                    f.copy(
                                        currentPrice = p,
                                        marketValue = if (p != null && f.shares > 0) p * f.shares else null
                                    )
                                },
                                stockQuotes = if (snapshots.isNotEmpty()) snapshots else state.stockQuotes
                            )
                        }
                        if (holdings.isNotEmpty()) {
                            notificationCheckCoordinator.checkWithPrices(holdings, prices)
                        }
                    }
                    _uiState.update { it.copy(isLoading = false) }
                }
        }

        // Collector 3: 行业目标变化时重算（行业聚合依赖它）
        viewModelScope.launch {
            stockRepository.observeIndustryTargets().collect {
                publish(recompute(lastStocksSnapshot, lastPricesSnapshot))
            }
        }

        // Collector 4: 自选股（含 shares=0）+ 股息预测 + 生活支出 → 合并进 UI 状态
        viewModelScope.launch {
            combine(
                allStocksFlow,
                forecastMapFlow,
                livingExpenseTargetFlow
            ) { stocks, forecasts, livingExpenseTarget ->
                Triple(stocks, forecasts, livingExpenseTarget)
            }.collect { (stocks, forecasts, livingExpenseTarget) ->
                val forecastTotal = forecasts.values
                    .filter { it.shares > 0 }
                    .sumOf { it.forecastIncome }
                // 总成本息率：分子 = Σ(最新年度每股股息 × 持仓股数)，仅计 shares>0 的持仓；
                // 分母 = Σ(成本价 × 持仓股数)。分母为 0 或无有效股息数据时为 null。
                val holdings = stocks.filter { it.shares > 0 }
                val totalDividendOnCost = holdings.sumOf { stock ->
                    val div = forecasts[stock.code]?.latestYearlyDividend ?: 0.0
                    div * stock.shares
                }
                val totalCostBasis = holdings.sumOf { it.costPerShare * it.shares }
                val costDividendYield = if (totalCostBasis > 0.0 && totalDividendOnCost > 0.0) {
                    totalDividendOnCost / totalCostBasis
                } else null
                val progress = if (livingExpenseTarget != null && livingExpenseTarget > 0) {
                    (forecastTotal / livingExpenseTarget * 100).toFloat().coerceAtMost(100f)
                } else null
                // 冷启动 stockForecasts 为空时，用 price_cache 兜底现价/市值，避免 UI 显示"—"
                val cachedPrices = if (stocks.isNotEmpty()) {
                    val codes = stocks.map { it.code }
                    if (codes.isNotEmpty()) marketDataPlane.cachedPrices(codes) else emptyMap()
                } else emptyMap()
                _uiState.update { state ->
                    val newWatchlist = stocks.filter { it.shares <= 0 }
                    val tagsByCode = state.items.associate { it.code to it.tags }
                    val (fi, fw) = applyPortfolioFilter(
                        state.items, newWatchlist, tagsByCode,
                        state.selectedIndustries, state.selectedTags
                    )
                    state.copy(
                        watchlist = newWatchlist,
                        stockForecasts = forecasts.mapValues { (code, forecast) ->
                            val previous = state.stockForecasts[code]
                            val cachedPrice = cachedPrices[code]
                            // 现价优先级：上一轮已写入的现价 > 本轮网络拉到的价(lastPricesSnapshot)
                            //   > price_cache 兜底。lastPricesSnapshot 由 Collector 2 在网络刷新后更新，
                            //   这里直接读快照可保证自选股(shares=0)也能拿到网络价——
                            //   历史仅依赖 previous/cachedPrice，自选股因 stockForecasts 时序问题拿不到现价。
                            val livePrice = previous?.currentPrice ?: lastPricesSnapshot[code] ?: cachedPrice
                            forecast.copy(
                                currentPrice = livePrice,
                                marketValue = previous?.marketValue
                                    ?: livePrice?.let { if (forecast.shares > 0) it * forecast.shares else null }
                            )
                        },
                        forecastTotal = forecastTotal,
                        costDividendYield = costDividendYield,
                        livingExpenseTargetAmount = livingExpenseTarget,
                        fireProgress = progress,
                        filteredItems = fi,
                        filteredWatchlist = fw
                    )
                }
            }
        }

        // Collector 5: 标签变化 → 重算候选标签 + 把 tags 注入 items + 重算筛选
        viewModelScope.launch {
            combine(allStocksFlow, tagsByCodeFlow, allTagsFlow) { stocks, tagsByCode, allTags ->
                Triple(stocks, tagsByCode, allTags)
            }.collect { (stocks, tagsByCode, allTags) ->
                val industries = stocks.map { it.industry.ifEmpty { "未分类" } }.distinct().sorted()
                _uiState.update { state ->
                    val itemsWithTags = state.items.map { it.copy(tags = tagsByCode[it.code].orEmpty()) }
                    val (fi, fw) = applyPortfolioFilter(
                        itemsWithTags, state.watchlist, tagsByCode,
                        state.selectedIndustries, state.selectedTags
                    )
                    state.copy(
                        availableIndustries = industries,
                        availableTags = allTags,
                        items = itemsWithTags,
                        filteredItems = fi,
                        filteredWatchlist = fw
                    )
                }
            }
        }

        // Collector 6: 周线 BOLL 带变化 → 同步进 UI state（卡片据此渲染或占位）
        viewModelScope.launch {
            _stockBands.collect { bands ->
                _uiState.update { it.copy(stockBands = bands) }
            }
        }

        // Collector 7: 评估门槛（用户在设置里改的 min/boost）变化 → 缓存到 _evalThresholds
        viewModelScope.launch {
            notificationRuleRepository.observeEvalThresholds()
                .distinctUntilChanged()
                .collect { thresholds ->
                    _evalThresholds.value = thresholds
                }
        }

        // Collector 8: 全量交易流水 → FIFO 已实现盈亏（组合级合计 + 注入持仓卡片）
        // 独立 collector 订阅 realizedPnlByCodeFlow，只更新自己负责的字段（§4.2 多 collector 约定）。
        viewModelScope.launch {
            realizedPnlByCodeFlow.collect { pnlByCode: Map<String, RealizedPnl> ->
                // 组合级合计：仅累加有结转成本的股票（卖出过的）。
                val totalCostBasis: Double = pnlByCode.values.sumOf { it.totalCostBasis }
                val totalRealized: Double = pnlByCode.values.sumOf { it.totalRealizedPnl }
                val hasAnyRealized: Boolean = pnlByCode.values.any { it.trades.isNotEmpty() }
                _uiState.update { state ->
                    state.copy(
                        items = state.items.map { item ->
                            val rpnl = pnlByCode[item.code]
                            item.copy(
                                realizedPnl = if (rpnl != null && rpnl.trades.isNotEmpty()) rpnl.totalRealizedPnl else null
                            )
                        },
                        totalRealizedPnl = if (hasAnyRealized) totalRealized else null,
                        totalRealizedPnlRate = if (hasAnyRealized && totalCostBasis > 0.0) {
                            totalRealized / totalCostBasis * 100.0
                        } else null
                    )
                }
            }
        }
    }

    fun refreshQuotes() {
        _refreshTrigger.tryEmit(Unit)
    }

    /**
     * 按需懒加载 [stockCode] 的周线 BOLL 带。卡片切到 BOLL 视图时调用。
     * 已缓存（key 存在，含 null）则跳过，避免重复网络请求；同时把结果（含 null）写入
     * [_stockBands]，UI 据此显示数据或「加载中/无数据」占位。
     */
    fun loadBoll(stockCode: String) {
        if (_stockBands.value.containsKey(stockCode)) return
        // 先占位标记为「加载中」(临时 null 与「无数据 null 同值，但 contains 阻断并发重复请求)
        _stockBands.update { it + (stockCode to null) }
        viewModelScope.launch {
            val band = runCatching { marketDataPlane.getBoll(stockCode) }.getOrNull()
            _stockBands.update { it + (stockCode to band) }
        }
    }

    /**
     * 一键评估当前筛选后可见的持仓股。对每只：
     *  1. 拉日/周/月三周期 BOLL（复用 [_stockBands] 周线缓存，日/月即时拉取）；
     *  2. 取 stockForecasts 的现价/股息；
     *  3. 调 [HoldingRecommender.recommend] 得建议（买入需「日下轨+周下轨+月中轨及以下」三周期共振）；
     *  4. 按 action 优先级（BUY→HOLD→SELL→INSUFFICIENT_DATA）排序。
     *
     * 并发用 [Semaphore] 限流到 3（每只股日/周/月 3 次 BOLL 请求），避免 Tencent 拒。
     */
    fun evaluateVisibleHoldings() {
        viewModelScope.launch {
            val visible = _uiState.value.filteredItems
            if (visible.isEmpty()) {
                _uiState.update {
                    it.copy(isEvaluating = false, evaluation = emptyList(),
                        portfolioSignals = null, dailyBands = emptyMap(), monthlyBands = emptyMap(),
                        llmAnalysis = LlmAnalysisState.Idle)
                }
                return@launch
            }
            _uiState.update {
                it.copy(isEvaluating = true, llmAnalysis = LlmAnalysisState.Idle,
                    portfolioSignals = null, dailyBands = emptyMap(), monthlyBands = emptyMap())
            }
            val thresholds = _evalThresholds.value
            val semaphore = Semaphore(3)  // 每只股 3 次 BOLL 请求，降到 3 并发防限流

            data class EvalRow(val stock: EvaluatedStock, val daily: BollBand?, val monthly: BollBand?)

            val rows = visible.map { item ->
                async {
                    semaphore.withPermit {
                        val weekly = ensureBollLoaded(item.code)
                        val daily = fetchBollForPeriod(item.code, KlinePeriod.DAILY)
                        val monthly = fetchBollForPeriod(item.code, KlinePeriod.MONTHLY)
                        val forecast = _uiState.value.stockForecasts[item.code]
                        val price = forecast?.currentPrice ?: item.currentPrice ?: 0.0
                        val recommendation = HoldingRecommender.recommend(
                            price = price, band = weekly,
                            latestYearlyDividend = forecast?.latestYearlyDividend,
                            thresholds = thresholds,
                            dailyBand = daily,
                            monthlyBand = monthly
                        )
                        val evaluated = EvaluatedStock(
                            code = item.code, name = item.name, industry = item.industry,
                            action = recommendation.action, priceVsLower = recommendation.priceVsLower,
                            dividendYield = recommendation.dividendYield, bollBand = weekly,
                            currentPrice = price.takeIf { it > 0.0 }, reasons = recommendation.reasons
                        )
                        EvalRow(evaluated, daily, monthly)
                    }
                }
            }.awaitAll()

            val sorted = rows.map { it.stock }.sortedWith(
                compareBy<EvaluatedStock> { it.action.priority() }.thenBy { it.priceVsLower }
            )
            val dailyBands = rows.associate { it.stock.code to it.daily }
            val monthlyBands = rows.associate { it.stock.code to it.monthly }
            val signals = PortfolioAdvisor.evaluate(sorted, dailyBands, monthlyBands)
            _uiState.update {
                it.copy(isEvaluating = false, evaluation = sorted,
                    portfolioSignals = signals, dailyBands = dailyBands, monthlyBands = monthlyBands)
            }
        }
    }

    /** 清除评估结果（结果页"清除结果"按钮用）。 */
    fun clearEvaluation() {
        _uiState.update {
            it.copy(evaluation = null, isEvaluating = false,
                portfolioSignals = null, dailyBands = emptyMap(), monthlyBands = emptyMap(),
                llmAnalysis = LlmAnalysisState.Idle)
        }
    }

    /** 触发 LLM 解读（结果页"AI 解读"按钮）。 */
    /** 触发 LLM 解读（结果页"AI 解读"按钮；重新分析传 forceRefresh=true）。 */
    fun analyzeWithLlm(forceRefresh: Boolean = false) {
        val current = _uiState.value
        val evaluation = current.evaluation
        val signals = current.portfolioSignals
        if (evaluation.isNullOrEmpty() || signals == null) return  // 按钮已禁用，防御
        val dailyBands = current.dailyBands
        val monthlyBands = current.monthlyBands
        viewModelScope.launch {
            _uiState.update { it.copy(llmAnalysis = LlmAnalysisState.Loading) }
            // 回流全局用户投资原则（失败降级空，不阻塞分析，红线 #2）
            val userStrategies = runCatching {
                tradeStrategyRepository.activeStrategies().map { toUserStrategyRef(it) }
            }.getOrDefault(emptyList())
            // 每股深度数据：基本面（缓存优先）/ 预测（本地）/ 买入线（国债缓存 + 本地算）
            val stockDetails = buildStockDetails(evaluation, forceRefresh)
            val input = PortfolioLlmInput(
                evaluation = evaluation,
                dailyBands = dailyBands,
                monthlyBands = monthlyBands,
                signals = signals,
                thresholds = _evalThresholds.value,
                userStrategies = userStrategies,
                stockDetails = stockDetails
            )
            val result = llmAnalysisRepository.analyze(input, forceRefresh)
            val state = when (result) {
                is LlmAnalysisResult.Success -> LlmAnalysisState.Success(
                    result.analysis, result.analyzedAt, result.fromCache, result.notice
                )
                LlmAnalysisResult.NotConfigured -> LlmAnalysisState.NotConfigured
                is LlmAnalysisResult.Error -> LlmAnalysisState.Error(result.message)
            }
            _uiState.update { it.copy(llmAnalysis = state) }
        }
    }

    /** 每股深度数据装配：基本面走缓存仓库，预测取本地快照，买入线本地计算。全部失败降级 null。 */
    private suspend fun buildStockDetails(
        evaluation: List<EvaluatedStock>,
        forceRefresh: Boolean
    ): Map<String, PortfolioLlmStockDetail> {
        if (evaluation.isEmpty()) return emptyMap()
        val bondYield = runCatching { marketDataPlane.get10YBondYield(forceRefresh) }
            .getOrDefault(BondYieldRepository.DEFAULT_YIELD)
        val semaphore = Semaphore(3)
        val forecasts = _uiState.value.stockForecasts
        val multipliers = lastAllStocksSnapshot.associate { it.code to it.buyThresholdMultiplier }
        return coroutineScope {
            evaluation.map { stock ->
                async {
                    semaphore.withPermit {
                        val fundamentals = runCatching {
                            marketDataPlane.getFundamentals(stock.code, forceRefresh)
                        }.getOrNull()
                        val forecast = forecasts[stock.code]?.llmForecast
                        val multiplier = multipliers[stock.code] ?: StockEntity.DEFAULT_BUY_THRESHOLD_MULTIPLIER
                        val latestDps = forecasts[stock.code]?.latestYearlyDividend
                        val buyThreshold = computeBuyThreshold(
                            bondYield10Y = bondYield,
                            multiplier = multiplier,
                            latestYearlyCashPerShare = latestDps,
                            currentPrice = stock.currentPrice
                        ).takeIf { it.targetYieldPercent > 0.0 }?.let {
                            StockLlmInput.StockLlmBuyThreshold(
                                targetYieldPercent = it.targetYieldPercent,
                                currentYieldPercent = it.currentYieldPercent,
                                reached = it.reached
                            )
                        }
                        stock.code to PortfolioLlmStockDetail(
                            fundamentals = fundamentals,
                            forecast = forecast,
                            buyThreshold = buyThreshold
                        )
                    }
                }
            }.awaitAll().toMap()
        }
    }

    fun clearLlmAnalysis() {
        _uiState.update { it.copy(llmAnalysis = LlmAnalysisState.Idle) }
    }

    private suspend fun fetchBollForPeriod(code: String, period: KlinePeriod): BollBand? =
        runCatching { marketDataPlane.getBoll(code, period) }.getOrNull()

    /**
     * 确保 [code] 的 boll 已加载（[_stockBands] 有 key 即返回，含 null）；
     * 否则触发 fetchBoll 并等待结果。
     */
    private suspend fun ensureBollLoaded(code: String): BollBand? {
        _stockBands.value[code]?.let { return it }
        // 占位防并发重复请求
        _stockBands.update { it + (code to null) }
        val band = runCatching { marketDataPlane.getBoll(code) }.getOrNull()
        _stockBands.update { it + (code to band) }
        return band
    }

    /**
     * 从自选/持仓中删除一只股票（连带其交易记录，FK CASCADE）。
     * 交易记录先备份到 [PortfolioUiState.deletedTransactions]，可通过 [undoDelete] 恢复。
     */
    fun deleteStock(stock: StockEntity) {
        viewModelScope.launch {
            val transactions = transactionDao.getByStock(stock.code)
            stockRepository.removeStock(stock.code)
            _uiState.update {
                it.copy(
                    deletedStock = stock,
                    deletedTransactions = transactions
                )
            }
        }
    }

    /**
     * 按 code 删除（持仓/自选卡片共用）：从内存快照查 StockEntity 后委托 [deleteStock]，
     * 以保留撤销时恢复交易记录的能力。持仓快照查不到时再查全量快照（含 shares=0 自选股），
     * 仍找不到时退化为无撤销删除。
     */
    fun deleteStock(code: String) {
        val stock = lastStocksSnapshot.firstOrNull { it.code == code }
            ?: lastAllStocksSnapshot.firstOrNull { it.code == code }
        if (stock != null) {
            deleteStock(stock)
        } else {
            viewModelScope.launch { stockRepository.removeStock(code) }
        }
    }

    fun undoDelete() {
        val deleted = _uiState.value.deletedStock ?: return
        viewModelScope.launch {
            stockRepository.restoreStock(deleted)
            _uiState.value.deletedTransactions.forEach { transaction ->
                transactionDao.insert(transaction)
            }
            _uiState.update {
                it.copy(
                    deletedStock = null,
                    deletedTransactions = emptyList()
                )
            }
            refreshQuotes()
        }
    }

    fun clearDeleted() {
        _uiState.update {
            it.copy(
                deletedStock = null,
                deletedTransactions = emptyList()
            )
        }
    }

    fun onResume() {
        if (shouldAutoRefresh()) {
            refreshQuotes()
        }
    }

    /**
     * 批量回填所有持仓的行业信息（东财 f127）。用于老数据补全或行业名变更后刷新。
     * 单股失败不影响其他股；完成后 holdingsFlow 会自动重算。
     */
    fun refreshIndustries() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshingIndustry = true) }
            // 用全量快照（含自选股）：自选股的行业也要能被刷新，否则自选卡片行业永远是空。
            val stocks = lastAllStocksSnapshot.ifEmpty {
                marketDataPlane.observeAllStocks().first()
            }
            stocks.forEach { stock ->
                runCatching { marketDataPlane.ensureIndustry(stock.code) }
            }
            _uiState.update { it.copy(isRefreshingIndustry = false) }
        }
    }

    fun showEditWeightDialog(code: String, currentWeight: Double) {
        _uiState.update {
            it.copy(
                editingCode = code,
                editingWeightInput = currentWeight.toString(),
                editingWeightError = null
            )
        }
    }

    fun dismissDialog() {
        _uiState.update {
            it.copy(
                editingCode = null,
                editingWeightInput = "",
                editingWeightError = null,
                editingTotalAssets = false,
                editingTotalAssetsInput = "",
                editingTotalAssetsError = null,
                editingIndustry = null,
                editingIndustryWeightInput = "",
                editingIndustryWeightError = null
            )
        }
    }

    fun showEditIndustryDialog(industry: String, currentWeight: Double) {
        _uiState.update {
            it.copy(
                editingIndustry = industry,
                editingIndustryWeightInput = currentWeight.toString(),
                editingIndustryWeightError = null
            )
        }
    }

    fun onIndustryWeightInputChanged(input: String) {
        _uiState.update { it.copy(editingIndustryWeightInput = input, editingIndustryWeightError = null) }
    }

    fun confirmEditIndustry() {
        val industry = _uiState.value.editingIndustry ?: return
        val weight = _uiState.value.editingIndustryWeightInput.toDoubleOrNull()
        if (weight == null || weight < 0.0 || weight > 100.0) {
            _uiState.update { it.copy(editingIndustryWeightError = "请输入 0 到 100 之间的数字") }
            return
        }
        viewModelScope.launch {
            stockRepository.updateIndustryTarget(industry, weight)
            _uiState.update {
                it.copy(
                    editingIndustry = null,
                    editingIndustryWeightInput = "",
                    editingIndustryWeightError = null
                )
            }
        }
    }

    // ---------- 筛选事件 ----------

    fun toggleIndustryFilter(industry: String) {
        _uiState.update { state ->
            val newSel = if (industry in state.selectedIndustries) {
                state.selectedIndustries - industry
            } else state.selectedIndustries + industry
            reapplyFilter(state.copy(selectedIndustries = newSel))
        }
    }

    fun clearIndustryFilter() {
        _uiState.update { reapplyFilter(it.copy(selectedIndustries = emptySet())) }
    }

    /**
     * 单选语义的行业筛选：传入 null 清空，否则只保留该行业。
     * 供下拉框使用，复用现有 selectedIndustries（空或单元素）。
     */
    fun setIndustryFilter(industry: String?) {
        val newSel = if (industry.isNullOrBlank()) emptySet() else setOf(industry)
        _uiState.update { reapplyFilter(it.copy(selectedIndustries = newSel)) }
    }

    fun toggleTagFilter(tag: String) {
        _uiState.update { state ->
            val newSel = if (tag in state.selectedTags) state.selectedTags - tag
            else state.selectedTags + tag
            reapplyFilter(state.copy(selectedTags = newSel))
        }
    }

    fun clearTagFilter() {
        _uiState.update { reapplyFilter(it.copy(selectedTags = emptySet())) }
    }

    /**
     * 单选语义的标签筛选：传入 null 清空，否则只保留该标签。
     * 供下拉框使用，复用现有 selectedTags（空或单元素）。
     */
    fun setTagFilter(tag: String?) {
        val newSel = if (tag.isNullOrBlank()) emptySet() else setOf(tag)
        _uiState.update { reapplyFilter(it.copy(selectedTags = newSel)) }
    }

    private fun reapplyFilter(state: PortfolioUiState): PortfolioUiState {
        val tagsByCode = state.items.associate { it.code to it.tags }
        val (fi, fw) = applyPortfolioFilter(
            state.items, state.watchlist, tagsByCode,
            state.selectedIndustries, state.selectedTags
        )
        return state.copy(filteredItems = fi, filteredWatchlist = fw)
    }

    fun onWeightInputChanged(input: String) {
        _uiState.update { it.copy(editingWeightInput = input, editingWeightError = null) }
    }

    fun confirmEditWeight() {
        val code = _uiState.value.editingCode ?: return
        val weight = _uiState.value.editingWeightInput.toDoubleOrNull()
        if (weight == null || weight < 0.0 || weight > 100.0) {
            _uiState.update { it.copy(editingWeightError = "请输入 0 到 100 之间的数字") }
            return
        }
        viewModelScope.launch {
            stockRepository.updateTargetWeight(code, weight)
            _uiState.update {
                it.copy(
                    editingCode = null,
                    editingWeightInput = "",
                    editingWeightError = null
                )
            }
        }
    }

    fun showEditTotalAssetsDialog() {
        _uiState.update {
            it.copy(
                editingTotalAssets = true,
                editingTotalAssetsInput = if (currentTotalAssets > 0.0) {
                    formatTotalAssetsInput(currentTotalAssets)
                } else "",
                editingTotalAssetsError = null
            )
        }
    }

    fun onTotalAssetsInputChanged(input: String) {
        _uiState.update { it.copy(editingTotalAssetsInput = input, editingTotalAssetsError = null) }
    }

    fun confirmEditTotalAssets() {
        val value = _uiState.value.editingTotalAssetsInput.toDoubleOrNull()
        if (value == null || value < 0.0) {
            _uiState.update { it.copy(editingTotalAssetsError = "请输入有效的非负金额") }
            return
        }
        currentTotalAssets = value
        prefs.edit().putLong(KEY_TOTAL_ASSETS, value.toRawBits()).apply()
        _uiState.update {
            it.copy(
                totalAssets = value,
                editingTotalAssets = false,
                editingTotalAssetsInput = "",
                editingTotalAssetsError = null
            )
        }
        viewModelScope.launch { publish(recompute(lastStocksSnapshot, lastPricesSnapshot)) }
    }

    private fun readTotalAssetsFromPrefs(): Double =
        if (prefs.contains(KEY_TOTAL_ASSETS)) {
            Double.fromBits(prefs.getLong(KEY_TOTAL_ASSETS, 0.0.toRawBits()))
        } else 0.0

    private fun formatTotalAssetsInput(value: Double): String {
        // Avoid scientific notation; trim trailing ".0" for integer inputs.
        return if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
    }

    private fun publish(result: RecomputeResult) {
        _uiState.update {
            // 用当前 items 的 tags 兜底（recompute 不知道 tags；Collector 5 会随后用 tagsByCodeFlow 覆盖）
            val tagsByCode = it.items.associate { it.code to it.tags }
            val itemsWithTags = result.items.map { newItem ->
                newItem.copy(tags = tagsByCode[newItem.code].orEmpty())
            }
            val (fi, fw) = applyPortfolioFilter(
                itemsWithTags, it.watchlist, tagsByCode,
                it.selectedIndustries, it.selectedTags
            )
            it.copy(
                items = itemsWithTags,
                industryGroups = result.industryGroups,
                industryTargetSum = result.industryTargetSum,
                holdingsMarketValue = result.holdingsMarketValue,
                totalCost = result.totalCost,
                totalPnl = result.totalPnl,
                totalPnlRate = result.totalPnlRate,
                targetWeightSum = result.targetWeightSum,
                isLoading = result.isLoading,
                error = null,
                filteredItems = fi,
                filteredWatchlist = fw
            )
        }
    }

    private suspend fun recompute(
        stocks: List<StockEntity>,
        prices: Map<String, Double>
    ): RecomputeResult {
        if (stocks.isEmpty()) return RecomputeResult.Empty
        val rawItems = stocks.map { stock ->
            val price = prices[stock.code]
            stock.toPortfolioItem(price)
        }
        val holdingsMarketValue = rawItems.sumOf { it.marketValue ?: 0.0 }
        val totalCost = rawItems.sumOf { it.totalCost }
        val totalPnl = rawItems.sumOf { it.unrealizedPnl ?: 0.0 }
        val totalPnlRate = if (totalCost > 0.0) totalPnl / totalCost else 0.0
        val totalAssets = currentTotalAssets

        // 行业目标映射：industry -> 占总资产 %
        val industryTargets = stockRepository.getIndustryTargets().associate { it.industry to it.targetWeight }
        val industryTargetSum = industryTargets.values.sum()

        // 个股层：actualWeight 占总资产 %；targetWeight 是占行业 %（用户在卡片上设）
        val itemsWithActual = rawItems.map { item ->
            val actualWeight = if (totalAssets > 0.0 && item.marketValue != null) {
                item.marketValue / totalAssets * 100.0
            } else null
            item.copy(actualWeight = actualWeight)
        }

        // 行业聚合
        val groups = itemsWithActual
            .groupBy { it.industry.ifEmpty { "未分类" } }
            .map { (industry, members) ->
                val groupMarketValue = members.sumOf { it.marketValue ?: 0.0 }
                val groupActualWeight = if (totalAssets > 0.0) groupMarketValue / totalAssets * 100.0 else null
                val groupTargetWeight = industryTargets[industry] ?: 0.0
                val groupTargetValue = if (totalAssets > 0.0) totalAssets * groupTargetWeight / 100.0 else null
                val stockTargetSum = members.sumOf { it.targetWeight }
                // 个股目标金额 = 行业目标金额 × 个股占行业% / 100
                val membersWithTargetValue = members.map { m ->
                    val tv = if (totalAssets > 0.0 && groupTargetValue != null) {
                        groupTargetValue * m.targetWeight / 100.0
                    } else null
                    m.copy(
                        targetValue = tv,
                        targetDiff = m.actualWeight?.minus(m.targetWeight)
                    )
                }
                IndustryGroup(
                    name = industry,
                    stocks = membersWithTargetValue.sortedByDescending { it.marketValue ?: 0.0 },
                    holdingsMarketValue = groupMarketValue,
                    actualWeight = groupActualWeight,
                    targetWeight = groupTargetWeight,
                    targetValue = groupTargetValue,
                    stockTargetSum = stockTargetSum
                )
            }
            .sortedByDescending { it.holdingsMarketValue }

        // 展平后的 items（保留原有按市值排序的扁平视图）
        val items = groups.flatMap { it.stocks }
        val targetWeightSum = items.sumOf { it.targetWeight }

        return RecomputeResult(
            items = items,
            industryGroups = groups,
            industryTargetSum = industryTargetSum,
            holdingsMarketValue = holdingsMarketValue,
            totalCost = totalCost,
            totalPnl = totalPnl,
            totalPnlRate = totalPnlRate,
            targetWeightSum = targetWeightSum,
            // loading 状态完全由 Collector 2 显式管理（进入 fetch 前 true、结束/失败后 false）。
            // 此处置 false，避免 Collector 1/3 在 lastPricesSnapshot 为空时把 isLoading 误设回 true，
            // 与 Collector 2 的显式复位相互打架。
            isLoading = false
        )
    }

    private fun isTradingHours(timestampMs: Long): Boolean {
        val now = Instant.ofEpochMilli(timestampMs).atZone(ZoneId.of("Asia/Shanghai"))
        if (now.dayOfWeek == DayOfWeek.SATURDAY || now.dayOfWeek == DayOfWeek.SUNDAY) return false
        val time = now.toLocalTime()
        val open = LocalTime.of(9, 30)
        val close = LocalTime.of(15, 0)
        return !time.isBefore(open) && !time.isAfter(close)
    }

    private fun shouldAutoRefresh(): Boolean {
        val lastRefreshMs = prefs.getLong(KEY_LAST_REFRESH, 0L)
        if (lastRefreshMs == 0L) return true
        val now = System.currentTimeMillis()
        val ttl = if (isTradingHours(now)) TTL_TRADING_MS else TTL_NON_TRADING_MS
        return (now - lastRefreshMs) > ttl
    }

    private fun persistRefreshTimestamp() {
        prefs.edit().putLong(KEY_LAST_REFRESH, System.currentTimeMillis()).apply()
    }

    private fun StockEntity.toPortfolioItem(currentPrice: Double?): PortfolioItem {
        val totalCost = costPerShare * shares
        val marketValue = currentPrice?.let { it * shares }
        val unrealizedPnl = currentPrice?.let { (it - costPerShare) * shares }
        val unrealizedPnlRate = if (costPerShare > 0.0 && currentPrice != null) {
            (currentPrice - costPerShare) / costPerShare
        } else null
        return PortfolioItem(
            code = code,
            name = name,
            marketCode = marketCode,
            shares = shares,
            costPerShare = costPerShare,
            industry = industry,
            currentPrice = currentPrice,
            marketValue = marketValue,
            totalCost = totalCost,
            unrealizedPnl = unrealizedPnl,
            unrealizedPnlRate = unrealizedPnlRate,
            targetWeight = targetWeight
        )
    }

    private data class RecomputeResult(
        val items: List<PortfolioItem>,
        val industryGroups: List<IndustryGroup>,
        val industryTargetSum: Double,
        val holdingsMarketValue: Double,
        val totalCost: Double,
        val totalPnl: Double,
        val totalPnlRate: Double,
        val targetWeightSum: Double,
        val isLoading: Boolean
    ) {
        companion object {
            val Empty = RecomputeResult(
                items = emptyList(),
                industryGroups = emptyList(),
                industryTargetSum = 0.0,
                holdingsMarketValue = 0.0,
                totalCost = 0.0,
                totalPnl = 0.0,
                totalPnlRate = 0.0,
                targetWeightSum = 0.0,
                isLoading = false
            )
        }
    }

    companion object {
        private const val KEY_LAST_REFRESH = "last_portfolio_refresh_ms"
        private const val KEY_TOTAL_ASSETS = "portfolio_total_assets"
        private const val TTL_TRADING_MS = 5 * 60 * 1000L
        private const val TTL_NON_TRADING_MS = 60 * 60 * 1000L
    }
}

/**
 * 持仓/自选股筛选纯函数。
 * - 行业组内 OR、标签组内 OR、跨组 AND
 * - industry="" 归入「未分类」桶
 * - 任一组的 selected 集合为空 = 该组不参与筛选（即放行全部）
 */
fun applyPortfolioFilter(
    items: List<PortfolioItem>,
    watchlist: List<StockEntity>,
    tagsByCode: Map<String, List<String>>,
    selectedIndustries: Set<String>,
    selectedTags: Set<String>
): Pair<List<PortfolioItem>, List<StockEntity>> {
    fun matchIndustry(industry: String): Boolean {
        if (selectedIndustries.isEmpty()) return true
        return industry.ifEmpty { "未分类" } in selectedIndustries
    }
    fun matchTags(code: String): Boolean {
        if (selectedTags.isEmpty()) return true
        return tagsByCode[code].orEmpty().any { it in selectedTags }
    }
    val fi = items.filter { matchIndustry(it.industry) && matchTags(it.code) }
    val fw = watchlist.filter { matchIndustry(it.industry) && matchTags(it.code) }
    return fi to fw
}

/** 评估排序优先级：BUY < HOLD < SELL < INSUFFICIENT_DATA。 */
private fun HoldingAction.priority(): Int = when (this) {
    HoldingAction.BUY -> 0
    HoldingAction.HOLD -> 1
    HoldingAction.SELL -> 2
    HoldingAction.INSUFFICIENT_DATA -> 3
}
