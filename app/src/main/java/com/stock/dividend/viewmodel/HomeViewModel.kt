package com.stock.dividend.viewmodel

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stock.dividend.data.local.dao.DividendDao
import com.stock.dividend.data.local.dao.FireGoalDao
import com.stock.dividend.data.local.entity.FireGoalEntity
import com.stock.dividend.data.local.entity.StockEntity
import com.stock.dividend.data.repository.ForecastCalculator
import com.stock.dividend.data.repository.StockRepository
import dagger.hilt.android.lifecycle.HiltViewModel
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
import kotlinx.coroutines.launch
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
    val fireGoal: FireGoalEntity? = null,
    val fireProgress: Float? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val deletedStock: StockEntity? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val stockRepository: StockRepository,
    private val dividendDao: DividendDao,
    private val fireGoalDao: FireGoalDao
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val _refreshTrigger = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    val stocksFlow = stockRepository.observeAllStocks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val fireGoalFlow = fireGoalDao.observe()
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

    init {
        // Single combined flow: stocks + forecasts + FIRE goal → one emission per data change
        viewModelScope.launch {
            combine(
                stocksFlow,
                forecastMapFlow,
                fireGoalFlow
            ) { stocks, forecasts, goal ->
                val total = forecasts.values.sumOf { it.forecastIncome }
                val progress = if (goal != null && goal.targetAmount > 0) {
                    (total / goal.targetAmount * 100).toFloat().coerceAtMost(100f)
                } else null
                _uiState.value.copy(
                    stocks = stocks,
                    stockForecasts = forecasts,
                    forecastTotal = total,
                    fireGoal = goal,
                    fireProgress = progress
                )
            }.collect { state ->
                _uiState.value = state
            }
        }

        // Fetch quotes: triggered by stock list changes and explicit refresh
        viewModelScope.launch {
            combine(
                stocksFlow,
                _refreshTrigger.onStart { emit(Unit) }.conflate()
            ) { stocks, _ -> stocks }
                .collect { stocks ->
                    val stocksWithShares = stocks.filter { it.shares > 0 }
                    if (stocksWithShares.isNotEmpty()) {
                        _uiState.value = _uiState.value.copy(isLoading = true)
                        try {
                            val prices = stockRepository.fetchQuotes(stocksWithShares)

                            val totalMV = stocksWithShares.mapNotNull { stock ->
                                prices[stock.code]?.let { price -> price * stock.shares }
                            }.sum().let { if (it > 0) it else null }

                            val forecasts = _uiState.value.stockForecasts
                            val updatedForecasts = forecasts.mapValues { (code, forecast) ->
                                val price = prices[code]
                                forecast.copy(
                                    currentPrice = price,
                                    marketValue = if (price != null && forecast.shares > 0) price * forecast.shares else null
                                )
                            }
                            _uiState.value = _uiState.value.copy(
                                stockForecasts = updatedForecasts,
                                totalMarketValue = totalMV
                            )
                        } finally {
                            _uiState.value = _uiState.value.copy(isLoading = false)
                        }
                    } else {
                        _uiState.value = _uiState.value.copy(totalMarketValue = null)
                    }
                }
        }
    }

    fun deleteStock(stock: StockEntity) {
        viewModelScope.launch {
            stockRepository.removeStock(stock.code)
            _uiState.value = _uiState.value.copy(deletedStock = stock)
        }
    }

    fun undoDelete() {
        val deleted = _uiState.value.deletedStock ?: return
        viewModelScope.launch {
            stockRepository.addStock(
                com.stock.dividend.data.repository.StockSearchResult(
                    code = deleted.code,
                    name = deleted.name,
                    marketCode = deleted.marketCode
                ),
                shares = deleted.shares,
                costPerShare = deleted.costPerShare
            )
            _uiState.value = _uiState.value.copy(deletedStock = null)
        }
    }

    fun clearDeleted() {
        _uiState.value = _uiState.value.copy(deletedStock = null)
    }

    fun refreshQuotes() {
        _refreshTrigger.tryEmit(Unit)
    }
}
