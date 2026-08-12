package com.stock.dividend.viewmodel

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stock.dividend.data.local.dao.DividendDao
import com.stock.dividend.data.local.entity.StockEntity
import com.stock.dividend.data.repository.BondYieldRepository
import com.stock.dividend.data.repository.ForecastCalculator
import com.stock.dividend.data.repository.GridPlanRepository
import com.stock.dividend.data.repository.IndexQuote
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
 * **信号轻量口径**（重要决策）：今日页不拉 BOLL（慢，每只股一次周线 K 线），买入触发只用
 * 「股息率达买入线」（[com.stock.dividend.data.repository.computeBuyThreshold]，需 bond+dividend+price）；
 * BOLL 共振信号留给评估页（那里才并发拉三周期 BOLL）。网格/分红倒计时不受影响。
 * 这样今日页保持「每天轻量看一眼」的快，不被 N 次 K 线请求拖慢。
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
        // Collector A: 自选+持仓变化
        viewModelScope.launch {
            allStocksFlow.collect { stocks ->
                lastStocks = stocks
                _uiState.update { it.copy(hasHoldings = stocks.any { s -> s.shares > 0 }) }
                recomputePortfolio(lastSnapshots, lastIndices)
                recomputeSignals(lastSnapshots)
            }
        }

        // Collector B: 刷新触发（首次订阅自动 onStart emit 一次）→ 拉价 + 指数
        viewModelScope.launch {
            _refreshTrigger
                .onStart { emit(Unit) }
                .collect {
                    _uiState.update { it.copy(isLoading = true) }
                    val stocks = lastStocks
                    val snapshots = if (stocks.isEmpty()) emptyMap()
                        else runCatching { stockRepository.fetchQuoteSnapshots(stocks) }.getOrDefault(emptyMap())
                    val indices = runCatching { marketDataRepository.fetchIndexQuotes() }.getOrDefault(emptyList())
                    val stale = snapshots.isEmpty() && stocks.isNotEmpty()
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

    /** 信号聚合（轻量口径，无 BOLL）。bond/dividends/grid 均吞异常返回空（红线 #2）。 */
    private suspend fun recomputeSignals(snapshots: Map<String, QuoteSnapshot>) {
        val stocks = lastStocks
        val bond = runCatching { bondYieldRepository.fetch10YBondYield() }
            .getOrDefault(BondYieldRepository.DEFAULT_YIELD)
        val dividends = runCatching { dividendDao.getAllWithExDate() }.getOrDefault(emptyList())
        val gridPlans = runCatching { gridPlanRepository.observeAll().first() }
            .getOrDefault(emptyList())
        val divByCode = dividends.groupBy { it.stockCode }
        val stockSnapshots = stocks.map { entity ->
            TodayStockSnapshot(
                code = entity.code,
                name = entity.name,
                price = snapshots[entity.code]?.price,
                // 今日页不拉 BOLL：weeklyBand=null → BOLL 共振信号不触发（留给评估页）
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
