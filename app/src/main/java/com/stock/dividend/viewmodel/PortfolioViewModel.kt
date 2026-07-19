package com.stock.dividend.viewmodel

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stock.dividend.data.local.entity.StockEntity
import com.stock.dividend.data.repository.StockRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
    val currentPrice: Double? = null,
    val marketValue: Double? = null,
    val totalCost: Double,
    val unrealizedPnl: Double? = null,
    val unrealizedPnlRate: Double? = null,
    val actualWeight: Double? = null,
    val targetWeight: Double,
    val targetValue: Double? = null,
    val targetDiff: Double? = null
)

@Stable
data class PortfolioUiState(
    val items: List<PortfolioItem> = emptyList(),
    val totalAssets: Double = 0.0,
    val holdingsMarketValue: Double = 0.0,
    val totalCost: Double = 0.0,
    val totalPnl: Double = 0.0,
    val totalPnlRate: Double = 0.0,
    val targetWeightSum: Double = 0.0,
    val isLoading: Boolean = false,
    val error: String? = null,
    val editingCode: String? = null,
    val editingWeightInput: String = "",
    val editingWeightError: String? = null,
    val editingTotalAssets: Boolean = false,
    val editingTotalAssetsInput: String = "",
    val editingTotalAssetsError: String? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PortfolioViewModel @Inject constructor(
    private val stockRepository: StockRepository,
    @ApplicationContext context: Context
) : ViewModel() {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

    private val _uiState = MutableStateFlow(PortfolioUiState())
    val uiState: StateFlow<PortfolioUiState> = _uiState.asStateFlow()

    private val _refreshTrigger = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Latest non-empty holding snapshot, kept so price refresh can recompute without re-reading the DAO. */
    @Volatile
    private var lastStocksSnapshot: List<StockEntity> = emptyList()

    /** Latest price snapshot, preserved across holding-stream re-emissions so UI does not flash to "—". */
    @Volatile
    private var lastPricesSnapshot: Map<String, Double> = emptyMap()

    /** User-configured total assets (the denominator for actual weight, basis for target value). */
    @Volatile
    private var currentTotalAssets: Double = 0.0

    private val holdingsFlow = stockRepository.observeAllStocks()
        .map { stocks -> stocks.filter { it.shares > 0 } }

    init {
        currentTotalAssets = readTotalAssetsFromPrefs()
        _uiState.update { it.copy(totalAssets = currentTotalAssets) }

        // Collector 1: rebuild items whenever holdings change, preserving the last known prices.
        viewModelScope.launch {
            holdingsFlow.collect { stocks ->
                lastStocksSnapshot = stocks
                publish(recompute(stocks, lastPricesSnapshot))
            }
        }

        // Collector 2: fetch prices on refresh trigger and merge with the latest holdings.
        viewModelScope.launch {
            holdingsFlow
                .flatMapLatest { stocks ->
                    if (stocks.isEmpty()) flowOf(emptyMap()) else {
                        _refreshTrigger.onStart { emit(Unit) }.conflate()
                            .map { stockRepository.fetchQuotes(stocks) }
                    }
                }
                .collect { prices ->
                    if (prices.isNotEmpty()) lastPricesSnapshot = prices
                    publish(recompute(lastStocksSnapshot, prices))
                    if (prices.isNotEmpty()) {
                        persistRefreshTimestamp()
                    }
                }
        }
    }

    fun refreshQuotes() {
        _refreshTrigger.tryEmit(Unit)
    }

    fun onResume() {
        if (shouldAutoRefresh()) {
            refreshQuotes()
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
                editingTotalAssetsError = null
            )
        }
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
        publish(recompute(lastStocksSnapshot, lastPricesSnapshot))
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
            it.copy(
                items = result.items,
                holdingsMarketValue = result.holdingsMarketValue,
                totalCost = result.totalCost,
                totalPnl = result.totalPnl,
                totalPnlRate = result.totalPnlRate,
                targetWeightSum = result.targetWeightSum,
                isLoading = result.isLoading,
                error = null
            )
        }
    }

    private fun recompute(stocks: List<StockEntity>, prices: Map<String, Double>): RecomputeResult {
        if (stocks.isEmpty()) return RecomputeResult.Empty
        val rawItems = stocks.map { stock ->
            val price = prices[stock.code]
            stock.toPortfolioItem(price)
        }
        val holdingsMarketValue = rawItems.sumOf { it.marketValue ?: 0.0 }
        val totalCost = rawItems.sumOf { it.totalCost }
        val totalPnl = rawItems.sumOf { it.unrealizedPnl ?: 0.0 }
        val totalPnlRate = if (totalCost > 0.0) totalPnl / totalCost else 0.0
        val targetWeightSum = rawItems.sumOf { it.targetWeight }
        val totalAssets = currentTotalAssets

        val items = rawItems
            .map { item ->
                // Actual weight = market value / total assets (user-configured denominator).
                val actualWeight = if (totalAssets > 0.0 && item.marketValue != null) {
                    item.marketValue / totalAssets * 100.0
                } else null
                // Target value = total assets × target weight (e.g. 400000 × 10% = 40000).
                val targetValue = if (totalAssets > 0.0) {
                    totalAssets * item.targetWeight / 100.0
                } else null
                item.copy(
                    actualWeight = actualWeight,
                    targetValue = targetValue,
                    targetDiff = actualWeight?.minus(item.targetWeight)
                )
            }
            .sortedByDescending { it.marketValue ?: 0.0 }

        return RecomputeResult(
            items = items,
            holdingsMarketValue = holdingsMarketValue,
            totalCost = totalCost,
            totalPnl = totalPnl,
            totalPnlRate = totalPnlRate,
            targetWeightSum = targetWeightSum,
            isLoading = prices.isEmpty()
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
