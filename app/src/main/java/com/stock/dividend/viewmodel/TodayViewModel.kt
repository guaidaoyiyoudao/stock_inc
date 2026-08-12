package com.stock.dividend.viewmodel

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stock.dividend.data.local.dao.DividendDao
import com.stock.dividend.data.local.entity.StockEntity
import com.stock.dividend.data.repository.BollBand
import com.stock.dividend.data.repository.BondYieldRepository
import com.stock.dividend.data.repository.ForecastCalculator
import com.stock.dividend.data.repository.GridPlanRepository
import com.stock.dividend.data.repository.IndexQuote
import com.stock.dividend.data.repository.KlinePeriod
import com.stock.dividend.data.repository.MarketDataRepository
import com.stock.dividend.data.repository.QuoteSnapshot
import com.stock.dividend.data.repository.StockRepository
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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
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
)

/**
 * 今日首页 ViewModel。多独立 collector 聚合「今日一瞥」（参考 PortfolioViewModel 模式 §4.2）。
 *
 * - Collector A：自选+持仓变化 → hasHoldings + 触发组合/信号重算
 * - Collector B：刷新触发（含首次 onStart）→ 拉价 + 指数 → 算市值/盈亏/对照/信号
 * - Collector C：AI 简报（按今日读缓存）
 *
 * **信号口径**：今日页并发拉**周线 BOLL**（Semaphore(3) 限流，红线 #5）→「跌破周线 BOLL 下轨」信号；
 * 加「股息率达买入线」+ 网格下一档 + 分红倒计时。三周期共振 BUY 仍留评估页（需日+周+月，重）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TodayViewModel @Inject constructor(
    private val stockRepository: StockRepository,
    private val gridPlanRepository: GridPlanRepository,
    private val dividendDao: DividendDao,
    private val bondYieldRepository: BondYieldRepository,
    private val marketDataRepository: MarketDataRepository,
    private val briefingCoordinator: TodayBriefingCoordinator,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TodayUiState())
    val uiState = _uiState.asStateFlow()

    private val _refreshTrigger = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    @Volatile private var lastStocks: List<StockEntity> = emptyList()
    @Volatile private var lastSnapshots: Map<String, QuoteSnapshot> = emptyMap()
    @Volatile private var lastIndices: List<IndexQuote> = emptyList()

    private val allStocksFlow = stockRepository.observeAllStocks()
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
                            runCatching { stockRepository.fetchQuoteSnapshots(stocks) }.getOrDefault(emptyMap())
                        }
                }
            }.collect { snapshots ->
                val indices = runCatching { marketDataRepository.fetchIndexQuotes() }.getOrDefault(emptyList())
                val stale = snapshots.isEmpty() && lastStocks.isNotEmpty()
                lastSnapshots = snapshots
                lastIndices = indices
                recomputePortfolio(snapshots, indices)
                recomputeSignals(snapshots)
                // 红线 #3：无论成功失败都复位 loading
                _uiState.update { it.copy(isLoading = false, dataStale = stale) }
            }
        }

        // Collector C: AI 简报（按今日读缓存，失败 null）
        viewModelScope.launch {
            val text = runCatching { briefingCoordinator.read(LocalDate.now()) }.getOrNull()
            _uiState.update { it.copy(briefing = text) }
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
        val bond = runCatching { bondYieldRepository.fetch10YBondYield() }
            .getOrDefault(BondYieldRepository.DEFAULT_YIELD)
        val dividends = runCatching { dividendDao.getAllWithExDate() }.getOrDefault(emptyList())
        val gridPlans = runCatching { gridPlanRepository.observeAll().first() }
            .getOrDefault(emptyList())
        val divByCode = dividends.groupBy { it.stockCode }
        // 并发拉周线 BOLL（Semaphore(3) 限流，红线 #5：腾讯接口拒高频）
        val bollByCode: Map<String, BollBand?> = if (stocks.isEmpty()) emptyMap()
        else coroutineScope {
            val sem = Semaphore(3)
            stocks.map { entity ->
                async {
                    sem.withPermit {
                        entity.code to runCatching {
                            stockRepository.fetchBoll(entity.code, KlinePeriod.WEEKLY)
                        }.getOrNull()
                    }
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
        )
        val signals = runCatching { TodaySignalAggregator.aggregate(input) }.getOrDefault(emptyList())
        _uiState.update { it.copy(signals = signals) }
    }

    /** 用户下拉刷新：重拉行情 + 指数。 */
    fun refresh() {
        viewModelScope.launch { _refreshTrigger.emit(Unit) }
    }
}
