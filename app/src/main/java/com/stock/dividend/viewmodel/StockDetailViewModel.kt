package com.stock.dividend.viewmodel

import androidx.compose.runtime.Stable
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@Stable
data class ForecastDetail(
    val avgCashPerShare: Double,
    val forecastIncome: Double,
    val actualYears: Int
)

@Stable
data class DividendRatePoint(
    val period: String,
    val label: String,
    val ratePercent: Double
)

@Stable
data class StockDetailUiState(
    val stock: StockEntity? = null,
    val dividends: List<DividendEntity> = emptyList(),
    val dividendRatePoints: List<DividendRatePoint> = emptyList(),
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

    private val stockFlow = stockRepository.observeStock(stockCode)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val dividendsFlow = dividendRepository.observeDividends(stockCode)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            combine(stockFlow, dividendsFlow) { stock, dividends ->
                Pair(stock, dividends)
            }.collect { (stock, dividends) ->
                val dividendRatePoints = deriveDividendRatePoints(dividends)
                if (stock != null) {
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
                    val selectedPeriod = _uiState.value.selectedPeriod.let { period ->
                        if (allForecasts.containsKey(period)) period else allForecasts.keys.firstOrNull() ?: "3"
                    }

                    _uiState.value = _uiState.value.copy(
                        stock = stock,
                        dividends = dividends,
                        dividendRatePoints = dividendRatePoints,
                        isLoading = false,
                        visibleCount = 5,
                        allForecasts = allForecasts,
                        forecast = allForecasts[selectedPeriod],
                        selectedPeriod = selectedPeriod
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        dividends = dividends,
                        dividendRatePoints = dividendRatePoints,
                        isLoading = false,
                        visibleCount = 5
                    )
                }
            }
        }
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

    private fun deriveDividendRatePoints(dividends: List<DividendEntity>): List<DividendRatePoint> {
        return dividends
            .mapNotNull { dividend ->
                val yield = dividend.dividendYield
                if (yield == null || !yield.isFinite() || yield < 0.0) {
                    null
                } else {
                    dividend.reportDate.substringBefore("-") to yield
                }
            }
            .groupBy(keySelector = { it.first }, valueTransform = { it.second })
            .map { (year, yields) ->
                DividendRatePoint(
                    period = year,
                    label = year,
                    ratePercent = yields.sum()
                )
            }
            .sortedBy { it.period }
    }
}
