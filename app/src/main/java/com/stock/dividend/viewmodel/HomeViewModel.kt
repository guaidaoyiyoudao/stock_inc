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
import com.stock.dividend.data.repository.ForecastCalculator
import com.stock.dividend.data.repository.LivingExpenseRepository
import com.stock.dividend.data.repository.StockRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject

@Stable
data class StockForecast(
    val shares: Int,
    val avgCashPerShare: Double,
    val forecastIncome: Double,
    val actualYears: Int,
    val currentPrice: Double? = null,
    val marketValue: Double? = null
)

@Stable
data class HomeUiState(
    val stocks: List<StockEntity> = emptyList(),
    val forecastTotal: Double = 0.0,
    val stockForecasts: Map<String, StockForecast> = emptyMap(),
    val totalMarketValue: Double? = null,
    val livingExpenseTargetAmount: Double? = null,
    val fireProgress: Float? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val deletedStock: StockEntity? = null,
    val deletedTransactions: List<TransactionEntity> = emptyList()
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val stockRepository: StockRepository,
    private val dividendDao: DividendDao,
    private val livingExpenseRepository: LivingExpenseRepository,
    private val transactionDao: TransactionDao,
    private val notificationCheckCoordinator: NotificationCheckCoordinator,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val _refreshTrigger = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    val stocksFlow = stockRepository.observeAllStocks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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

    private val forecastMapFlow = stocksFlow.flatMapLatest { stocks ->
        val activeStocks = stocks.filter { it.shares > 0 }
        if (activeStocks.isEmpty()) {
            flowOf(emptyMap())
        } else {
            val forecastFlows = activeStocks.map { stock ->
                dividendDao.observeByStock(stock.code).map { dividends ->
                    val years = stock.yieldPeriod.toIntOrNull() ?: 3
                    val result = ForecastCalculator.calculateForecastIncome(
                        dividends, stock.shares, years
                    )
                    stock.code to result?.let {
                        StockForecast(
                            shares = stock.shares,
                            avgCashPerShare = it.avgCashPerShare,
                            forecastIncome = stock.shares * it.avgCashPerShare,
                            actualYears = it.actualYears
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

    private fun isTradingHours(timestampMs: Long): Boolean {
        val now = Instant.ofEpochMilli(timestampMs).atZone(ZoneId.of("Asia/Shanghai"))
        val dayOfWeek = now.dayOfWeek
        if (dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY) return false
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

    fun onResume() {
        if (shouldAutoRefresh()) {
            refreshQuotes()
        }
    }

    companion object {
        private const val KEY_LAST_REFRESH = "last_quote_refresh_ms"
        private const val TTL_TRADING_MS = 5 * 60 * 1000L
        private const val TTL_NON_TRADING_MS = 60 * 60 * 1000L
    }

    init {
        viewModelScope.launch {
            combine(
                stocksFlow,
                forecastMapFlow,
                livingExpenseTargetFlow
            ) { stocks, forecasts, livingExpenseTarget ->
                val total = forecasts.values.sumOf { it.forecastIncome }
                val progress = if (livingExpenseTarget != null && livingExpenseTarget > 0) {
                    (total / livingExpenseTarget * 100).toFloat().coerceAtMost(100f)
                } else null
                Triple(stocks, forecasts, Triple(total, livingExpenseTarget, progress))
            }.collect { (stocks, forecasts, triple) ->
                val (total, livingExpenseTarget, progress) = triple
                _uiState.update { currentState ->
                    currentState.copy(
                        stocks = stocks,
                        stockForecasts = forecasts.mapValues { (code, forecast) ->
                            val previous = currentState.stockForecasts[code]
                            forecast.copy(
                                currentPrice = previous?.currentPrice,
                                marketValue = previous?.marketValue
                            )
                        },
                        forecastTotal = total,
                        livingExpenseTargetAmount = livingExpenseTarget,
                        fireProgress = progress
                    )
                }
            }
        }

        viewModelScope.launch {
            combine(
                stocksFlow,
                _refreshTrigger.onStart { emit(Unit) }.conflate()
            ) { stocks, _ -> stocks }
                .collect { stocks ->
                    val stocksWithShares = stocks.filter { it.shares > 0 }
                    if (stocksWithShares.isNotEmpty()) {
                        _uiState.update { it.copy(isLoading = true) }
                        try {
                            val prices = stockRepository.fetchQuotes(stocksWithShares)

                            val totalMV = stocksWithShares.mapNotNull { stock ->
                                prices[stock.code]?.let { price -> price * stock.shares }
                            }.sum().let { if (it > 0) it else null }

                            _uiState.update { currentState ->
                                val updatedForecasts = currentState.stockForecasts.mapValues { (code, forecast) ->
                                    val price = prices[code]
                                    forecast.copy(
                                        currentPrice = price,
                                        marketValue = if (price != null && forecast.shares > 0) price * forecast.shares else null
                                    )
                                }
                                currentState.copy(
                                    stockForecasts = updatedForecasts,
                                    totalMarketValue = totalMV,
                                    isLoading = false
                                )
                            }
                            notificationCheckCoordinator.checkWithPrices(stocksWithShares, prices)
                            val now = System.currentTimeMillis()
                            prefs.edit().putLong(KEY_LAST_REFRESH, now).apply()
                        } catch (_: Exception) {
                            _uiState.update { it.copy(isLoading = false) }
                        }
                    } else {
                        _uiState.update { it.copy(totalMarketValue = null) }
                    }
                }
        }
    }

    fun deleteStock(stock: StockEntity) {
        viewModelScope.launch {
            val transactions = transactionDao.getByStock(stock.code)
            stockRepository.removeStock(stock.code)
            _uiState.value = _uiState.value.copy(
                deletedStock = stock,
                deletedTransactions = transactions
            )
        }
    }

    fun undoDelete() {
        val deleted = _uiState.value.deletedStock ?: return
        viewModelScope.launch {
            stockRepository.restoreStock(deleted)
            _uiState.value.deletedTransactions.forEach { transaction ->
                transactionDao.insert(transaction)
            }
            _uiState.value = _uiState.value.copy(
                deletedStock = null,
                deletedTransactions = emptyList()
            )
            refreshQuotes()
        }
    }

    fun clearDeleted() {
        _uiState.value = _uiState.value.copy(
            deletedStock = null,
            deletedTransactions = emptyList()
        )
    }

    fun refreshQuotes() {
        _refreshTrigger.tryEmit(Unit)
    }
}
