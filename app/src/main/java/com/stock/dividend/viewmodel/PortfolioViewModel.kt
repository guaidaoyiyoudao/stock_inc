package com.stock.dividend.viewmodel

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stock.dividend.data.local.dao.DividendDao
import com.stock.dividend.data.local.dao.TransactionDao
import com.stock.dividend.data.local.entity.EXPENSE_PERIOD_MONTHLY
import com.stock.dividend.data.local.entity.StockEntity
import com.stock.dividend.data.local.entity.TransactionEntity
import com.stock.dividend.data.notification.NotificationCheckCoordinator
import com.stock.dividend.data.repository.BollBand
import com.stock.dividend.data.repository.DividendThresholds
import com.stock.dividend.data.repository.ForecastCalculator
import com.stock.dividend.data.repository.HoldingAction
import com.stock.dividend.data.repository.HoldingRecommendation
import com.stock.dividend.data.repository.HoldingRecommender
import com.stock.dividend.data.repository.LivingExpenseRepository
import com.stock.dividend.data.repository.NotificationRuleRepository
import com.stock.dividend.data.repository.StockRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
    val latestYearlyDividend: Double? = null
)

/** 一只股票的评估结果（结果页直接渲染）。 */
@Stable
data class EvaluatedStock(
    val code: String,
    val name: String,
    val industry: String,
    val action: HoldingAction,
    val priceVsLower: Double,
    val dividendYield: Double?,
    val bollBand: BollBand?,
    val currentPrice: Double?,
    val reasons: List<String>
)

@Stable
data class PortfolioUiState(
    val items: List<PortfolioItem> = emptyList(),
    /** shares=0 的纯自选股（合并自选 tab 后仍展示，但与持仓股区分样式）。 */
    val watchlist: List<StockEntity> = emptyList(),
    val stockForecasts: Map<String, StockForecast> = emptyMap(),
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
    val evaluation: List<EvaluatedStock>? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PortfolioViewModel @Inject constructor(
    private val stockRepository: StockRepository,
    private val dividendDao: DividendDao,
    private val livingExpenseRepository: LivingExpenseRepository,
    private val transactionDao: TransactionDao,
    private val notificationCheckCoordinator: NotificationCheckCoordinator,
    private val notificationRuleRepository: NotificationRuleRepository,
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

    /** Latest price snapshot, preserved across holding-stream re-emissions so UI does not flash to "—". */
    @Volatile
    private var lastPricesSnapshot: Map<String, Double> = emptyMap()

    /** User-configured total assets (the denominator for actual weight, basis for target value). */
    @Volatile
    private var currentTotalAssets: Double = 0.0

    /** 全部股票（含 shares=0 的纯自选股），合并自选 tab 后两者共用同一数据源。 */
    private val allStocksFlow = stockRepository.observeAllStocks()
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
     */
    private val forecastMapFlow = allStocksFlow.flatMapLatest { stocks ->
        if (stocks.isEmpty()) {
            flowOf(emptyMap())
        } else {
            val forecastFlows = stocks.map { stock ->
                dividendDao.observeByStock(stock.code).map { dividends ->
                    val years = stock.yieldPeriod.toIntOrNull() ?: 3
                    val result = ForecastCalculator.calculateForecastIncome(
                        dividends, stock.shares, years
                    )
                    stock.code to result?.let {
                        StockForecast(
                            shares = stock.shares,
                            avgCashPerShare = it.avgCashPerShare,
                            // shares=0 的自选股 forecastIncome 恒为 0（shares * avg = 0），不计入合计
                            forecastIncome = stock.shares * it.avgCashPerShare,
                            actualYears = it.actualYears,
                            latestYearlyDividend = ForecastCalculator.latestYearlyCashPerShare(dividends)
                        )
                    }
                }
            }
            combine(forecastFlows) { results ->
                results
                    .filter { it.second != null }
                    .associate { it.first to it.second!! }
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
                    val cached = stockRepository.getCachedPrices(stocks.map { it.code })
                    if (cached.isNotEmpty()) lastPricesSnapshot = cached
                }
                publish(recompute(stocks, lastPricesSnapshot))
            }
        }

        // Collector 2: fetch prices on refresh trigger and merge with the latest holdings.
        viewModelScope.launch {
            holdingsFlow
                .flatMapLatest { stocks ->
                    if (stocks.isEmpty()) flowOf(emptyMap()) else {
                        _refreshTrigger.onStart { emit(Unit) }.conflate()
                            .map {
                                _uiState.update { it.copy(isLoading = true) }
                                try {
                                    stockRepository.fetchQuotes(stocks)
                                } catch (_: Exception) {
                                    // fetchQuotes 自身已吞异常返回空 map，这里兜底网络层之外的问题
                                    emptyMap()
                                }
                            }
                    }
                }
                .collect { prices ->
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
                    // 把最新价同步进 forecast 卡片（现价/市值），并触发通知检查
                    val holdings = lastStocksSnapshot
                    if (holdings.isNotEmpty() && prices.isNotEmpty()) {
                        _uiState.update { state ->
                            state.copy(
                                stockForecasts = state.stockForecasts.mapValues { (code, f) ->
                                    val p = prices[code]
                                    f.copy(
                                        currentPrice = p,
                                        marketValue = if (p != null && f.shares > 0) p * f.shares else null
                                    )
                                }
                            )
                        }
                        notificationCheckCoordinator.checkWithPrices(holdings, prices)
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
                val progress = if (livingExpenseTarget != null && livingExpenseTarget > 0) {
                    (forecastTotal / livingExpenseTarget * 100).toFloat().coerceAtMost(100f)
                } else null
                // 冷启动 stockForecasts 为空时，用 price_cache 兜底现价/市值，避免 UI 显示"—"
                val cachedPrices = if (stocks.isNotEmpty()) {
                    val codes = stocks.map { it.code }
                    if (codes.isNotEmpty()) stockRepository.getCachedPrices(codes) else emptyMap()
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
                            forecast.copy(
                                currentPrice = previous?.currentPrice ?: cachedPrice,
                                marketValue = previous?.marketValue
                                    ?: cachedPrice?.let { if (forecast.shares > 0) it * forecast.shares else null }
                            )
                        },
                        forecastTotal = forecastTotal,
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
            val band = try {
                stockRepository.fetchBoll(stockCode)
            } catch (_: Exception) {
                null
            }
            _stockBands.update { it + (stockCode to band) }
        }
    }

    /**
     * 一键评估当前筛选后可见的持仓股。对每只：
     *  1. 确保 boll 已加载（复用 [_stockBands] 缓存，缺则触发 fetchBoll）；
     *  2. 取 stockForecasts 的现价/股息；
     *  3. 调 [HoldingRecommender.recommend] 得建议；
     *  4. 按 action 优先级（BUY→HOLD→SELL→INSUFFICIENT_DATA）排序。
     *
     * 并发用 [Semaphore] 限流到 4，避免一次性几十个 Tencent 请求被拒。
     */
    fun evaluateVisibleHoldings() {
        viewModelScope.launch {
            val visible = _uiState.value.filteredItems
            if (visible.isEmpty()) {
                _uiState.update { it.copy(isEvaluating = false, evaluation = emptyList()) }
                return@launch
            }
            _uiState.update { it.copy(isEvaluating = true) }
            val thresholds = _evalThresholds.value
            val semaphore = Semaphore(4)

            val results = visible.map { item ->
                async {
                    semaphore.withPermit {
                        val band = ensureBollLoaded(item.code)
                        val forecast = _uiState.value.stockForecasts[item.code]
                        val price = forecast?.currentPrice ?: item.currentPrice ?: 0.0
                        val recommendation = HoldingRecommender.recommend(
                            price = price,
                            band = band,
                            latestYearlyDividend = forecast?.latestYearlyDividend,
                            thresholds = thresholds
                        )
                        EvaluatedStock(
                            code = item.code,
                            name = item.name,
                            industry = item.industry,
                            action = recommendation.action,
                            priceVsLower = recommendation.priceVsLower,
                            dividendYield = recommendation.dividendYield,
                            bollBand = band,
                            currentPrice = price.takeIf { it > 0.0 },
                            reasons = recommendation.reasons
                        )
                    }
                }
            }.awaitAll()

            val sorted = results.sortedWith(
                compareBy<EvaluatedStock> { it.action.priority() }
                    .thenBy { it.priceVsLower }
            )
            _uiState.update { it.copy(isEvaluating = false, evaluation = sorted) }
        }
    }

    /** 清除评估结果（结果页"清除结果"按钮用）。 */
    fun clearEvaluation() {
        _uiState.update { it.copy(evaluation = null, isEvaluating = false) }
    }

    /**
     * 确保 [code] 的 boll 已加载（[_stockBands] 有 key 即返回，含 null）；
     * 否则触发 fetchBoll 并等待结果。
     */
    private suspend fun ensureBollLoaded(code: String): BollBand? {
        _stockBands.value[code]?.let { return it }
        // 占位防并发重复请求
        _stockBands.update { it + (code to null) }
        val band = try {
            stockRepository.fetchBoll(code)
        } catch (_: Exception) {
            null
        }
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
     * 按 code 删除（持仓卡片用）：从内存快照查 StockEntity 后委托 [deleteStock]，
     * 以保留撤销时恢复交易记录的能力。快照里找不到时退化为无撤销删除。
     */
    fun deleteStock(code: String) {
        val stock = lastStocksSnapshot.firstOrNull { it.code == code }
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
            val stocks = lastStocksSnapshot.ifEmpty {
                stockRepository.observeAllStocks().first()
            }
            stocks.forEach { stock ->
                try { stockRepository.fetchAndCacheIndustry(stock.code) } catch (_: Exception) { /* 单股失败跳过 */ }
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
