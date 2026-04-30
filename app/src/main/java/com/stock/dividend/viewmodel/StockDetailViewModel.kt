package com.stock.dividend.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stock.dividend.data.local.entity.DividendEntity
import com.stock.dividend.data.local.entity.StockEntity
import com.stock.dividend.data.repository.DividendRepository
import com.stock.dividend.data.repository.ForecastCalculator
import com.stock.dividend.data.repository.StockRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ForecastDetail(
    val avgCashPerShare: Double,
    val forecastIncome: Double,
    val actualYears: Int
)

data class StockDetailUiState(
    val stock: StockEntity? = null,
    val dividends: List<DividendEntity> = emptyList(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val forecast: ForecastDetail? = null,
    val allForecasts: Map<String, ForecastDetail> = emptyMap(),
    val selectedPeriod: String = "3",
    val visibleCount: Int = 5
)

@HiltViewModel
class StockDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val stockRepository: StockRepository,
    private val dividendRepository: DividendRepository
) : ViewModel() {

    private val stockCode: String = savedStateHandle["code"] ?: ""

    private val _uiState = MutableStateFlow(StockDetailUiState())
    val uiState: StateFlow<StockDetailUiState> = _uiState.asStateFlow()

    init {
        loadStock()
        observeDividends()
    }

    private fun loadStock() {
        viewModelScope.launch {
            stockRepository.observeStock(stockCode).collect { stock ->
                if (stock != null) {
                    _uiState.value = _uiState.value.copy(
                        stock = stock,
                        selectedPeriod = stock.yieldPeriod
                    )
                    recalculateForecasts()
                }
            }
        }
    }

    private fun observeDividends() {
        viewModelScope.launch {
            dividendRepository.observeDividends(stockCode).collect { dividends ->
                _uiState.value = _uiState.value.copy(
                    dividends = dividends,
                    isLoading = false,
                    visibleCount = 5
                )
                recalculateForecasts()
            }
        }
    }

    private fun recalculateForecasts() {
        val state = _uiState.value
        val stock = state.stock ?: return
        val dividends = state.dividends

        val allForecasts = mutableMapOf<String, ForecastDetail>()
        for (period in listOf("1", "3", "5")) {
            val years = period.toInt()
            val result = ForecastCalculator.calculateForecastIncome(
                dividends, stock.shares, years
            )
            if (result != null) {
                allForecasts[period] = ForecastDetail(
                    avgCashPerShare = result.avgCashPerShare,
                    forecastIncome = stock.shares * result.avgCashPerShare,
                    actualYears = result.actualYears
                )
            }
        }

        val selectedForecast = allForecasts[state.selectedPeriod]
        _uiState.value = state.copy(
            allForecasts = allForecasts,
            forecast = selectedForecast
        )
    }

    fun refreshDividends() {
        val stock = _uiState.value.stock ?: return
        if (_uiState.value.isRefreshing) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isRefreshing = true)
            val securityCode = stockCode.substringAfter(".")
            dividendRepository.fetchAndCacheDividends(stockCode, securityCode)
            _uiState.value = _uiState.value.copy(isRefreshing = false)
        }
    }

    fun loadMoreDividends() {
        val state = _uiState.value
        val newCount = (state.visibleCount + 5).coerceAtMost(state.dividends.size)
        _uiState.value = state.copy(visibleCount = newCount)
    }

    fun updateYieldPeriod(period: String) {
        viewModelScope.launch {
            stockRepository.updateYieldPeriod(stockCode, period)
        }
        val state = _uiState.value
        _uiState.value = state.copy(
            selectedPeriod = period,
            forecast = state.allForecasts[period]
        )
    }
}
