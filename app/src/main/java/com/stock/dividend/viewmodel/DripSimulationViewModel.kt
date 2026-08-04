package com.stock.dividend.viewmodel

import androidx.compose.runtime.Stable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stock.dividend.data.local.entity.StockEntity
import com.stock.dividend.data.repository.DripResult
import com.stock.dividend.data.repository.DripCalculator
import com.stock.dividend.data.repository.DividendRepository
import com.stock.dividend.data.repository.StockRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Stable
data class DripSimulationUiState(
    val stock: StockEntity? = null,
    val isLoading: Boolean = true,
    /** 是否有足够分红历史启动模拟（≥1 个有效分红年份）。 */
    val hasDividendHistory: Boolean = true,
    val availableStartYear: String? = null,
    val availableEndYear: String? = null,
    val currentPrice: Double? = null,
    // ── 可调参数（字符串，便于绑定输入框）──
    val initialAmountInput: String = "10000",
    val initialPriceInput: String = "",
    val reinvestPriceInput: String = "",
    val endPriceInput: String = "",
    val startYearInput: String = "",
    val endYearInput: String = "",
    // ── 结果 ──
    val result: DripResult? = null,
    val validationError: String? = null
)

/**
 * 分红再投资（DRIP）模拟 ViewModel。
 *
 * 订阅 stock + dividends，预填参数（初始价/再投价/期末价默认取当前价），
 * 用户调整任意参数即时重算（[DripCalculator] 纯函数）。
 */
@HiltViewModel
class DripSimulationViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val stockRepository: StockRepository,
    private val dividendRepository: DividendRepository
) : ViewModel() {

    private val stockCode: String = savedStateHandle["code"] ?: ""
    private val _uiState = MutableStateFlow(DripSimulationUiState())
    val uiState: StateFlow<DripSimulationUiState> = _uiState.asStateFlow()

    private val stockFlow = stockRepository.observeStock(stockCode)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    private val dividendsFlow = dividendRepository.observeDividends(stockCode)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            combine(stockFlow, dividendsFlow) { stock, dividends -> stock to dividends }
                .collect { (stock, dividends) ->
                    if (stock == null) return@collect
                    val currentPrice = fetchCurrentPrice(stock)
                    // 计算可用年份窗口（供 start/end year 输入校验）
                    val years = dividends
                        .filter { it.reportDate.length >= 4 && it.cashPerShare > 0.0 }
                        .map { it.reportDate.substring(0, 4) }
                        .distinct()
                        .sorted()
                    val hasHistory = years.isNotEmpty()
                    val firstYear = years.firstOrNull()
                    val lastYear = years.lastOrNull()

                    _uiState.value = _uiState.value.copy(
                        stock = stock,
                        isLoading = false,
                        hasDividendHistory = hasHistory,
                        availableStartYear = firstYear,
                        availableEndYear = lastYear,
                        currentPrice = currentPrice,
                        // 首次进入：用当前价预填各价格字段，默认窗口取全量
                        initialPriceInput = _uiState.value.initialPriceInput.ifBlank {
                            currentPrice?.let { String.format(Locale.US, "%.2f", it) } ?: ""
                        },
                        reinvestPriceInput = _uiState.value.reinvestPriceInput.ifBlank {
                            currentPrice?.let { String.format(Locale.US, "%.2f", it) } ?: ""
                        },
                        endPriceInput = _uiState.value.endPriceInput.ifBlank {
                            currentPrice?.let { String.format(Locale.US, "%.2f", it) } ?: ""
                        },
                        startYearInput = _uiState.value.startYearInput.ifBlank { firstYear ?: "" },
                        endYearInput = _uiState.value.endYearInput.ifBlank { lastYear ?: "" }
                    ).recalculated(dividends)
                }
        }
    }

    private suspend fun fetchCurrentPrice(stock: StockEntity): Double? = try {
        stockRepository.fetchQuotes(listOf(stock))[stock.code]
    } catch (_: Exception) {
        null
    }

    private fun update(transform: DripSimulationUiState.() -> DripSimulationUiState) {
        val dividends = dividendsFlow.value
        _uiState.value = _uiState.value.transform().recalculated(dividends)
    }

    fun onInitialAmountChanged(value: String) = update { copy(initialAmountInput = value) }
    fun onInitialPriceChanged(value: String) = update { copy(initialPriceInput = value) }
    fun onReinvestPriceChanged(value: String) = update { copy(reinvestPriceInput = value) }
    fun onEndPriceChanged(value: String) = update { copy(endPriceInput = value) }
    fun onStartYearChanged(value: String) = update { copy(startYearInput = value) }
    fun onEndYearChanged(value: String) = update { copy(endYearInput = value) }

    /** 用当前价一键填充全部三个价格字段。 */
    fun useCurrentPriceForAll() = update {
        val p = currentPrice?.let { String.format(Locale.US, "%.2f", it) } ?: ""
        copy(initialPriceInput = p, reinvestPriceInput = p, endPriceInput = p)
    }

    private fun DripSimulationUiState.recalculated(dividends: List<com.stock.dividend.data.local.entity.DividendEntity>): DripSimulationUiState {
        val initialAmount = initialAmountInput.toDoubleOrNull()
        val initialPrice = initialPriceInput.toDoubleOrNull()
        val reinvestPrice = reinvestPriceInput.toDoubleOrNull()
        val endPrice = endPriceInput.toDoubleOrNull()

        if (initialAmount == null || initialPrice == null || reinvestPrice == null || endPrice == null) {
            return copy(result = null, validationError = "请补全所有数值参数")
        }
        if (initialAmount <= 0 || initialPrice <= 0 || endPrice <= 0) {
            return copy(result = null, validationError = "初始金额、初始价、期末价须 > 0")
        }

        val start = startYearInput.ifBlank { null }
        val end = endYearInput.ifBlank { null }
        val result = DripCalculator.simulate(
            dividends = dividends,
            initialAmount = initialAmount,
            initialPrice = initialPrice,
            reinvestPrice = reinvestPrice,
            endPrice = endPrice,
            startYear = start,
            endYear = end
        )
        return copy(
            result = result,
            validationError = if (result == null) "无足够分红历史或参数无效" else null
        )
    }
}
