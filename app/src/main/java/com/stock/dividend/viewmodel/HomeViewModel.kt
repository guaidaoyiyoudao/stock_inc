package com.stock.dividend.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stock.dividend.data.local.dao.DividendDao
import com.stock.dividend.data.local.entity.StockEntity
import com.stock.dividend.data.repository.ForecastCalculator
import com.stock.dividend.data.repository.StockRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class StockForecast(
    val shares: Int,
    val avgCashPerShare: Double,
    val forecastIncome: Double,
    val actualYears: Int
)

data class HomeUiState(
    val stocks: List<StockEntity> = emptyList(),
    val forecastTotal: Double = 0.0,
    val stockForecasts: Map<String, StockForecast> = emptyMap(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val deletedStock: StockEntity? = null
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val stockRepository: StockRepository,
    private val dividendDao: DividendDao
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    val stocksFlow = stockRepository.observeAllStocks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            stocksFlow.collect { stocks ->
                val forecasts = mutableMapOf<String, StockForecast>()
                for (stock in stocks) {
                    if (stock.shares <= 0) continue
                    val dividends = dividendDao.observeByStock(stock.code).first()
                    val years = stock.yieldPeriod.toIntOrNull() ?: 3
                    val result = ForecastCalculator.calculateForecastIncome(dividends, stock.shares, years)
                    if (result != null) {
                        forecasts[stock.code] = StockForecast(
                            shares = stock.shares,
                            avgCashPerShare = result.avgCashPerShare,
                            forecastIncome = stock.shares * result.avgCashPerShare,
                            actualYears = result.actualYears
                        )
                    }
                }
                val total = forecasts.values.sumOf { it.forecastIncome }
                _uiState.value = _uiState.value.copy(
                    stocks = stocks,
                    stockForecasts = forecasts,
                    forecastTotal = total
                )
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
                shares = deleted.shares
            )
            _uiState.value = _uiState.value.copy(deletedStock = null)
        }
    }

    fun clearDeleted() {
        _uiState.value = _uiState.value.copy(deletedStock = null)
    }
}
