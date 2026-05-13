package com.stock.dividend.viewmodel

import androidx.compose.runtime.Stable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stock.dividend.data.local.entity.StockEntity
import com.stock.dividend.data.repository.DividendDiscountCalculator
import com.stock.dividend.data.repository.DividendDiscountInput
import com.stock.dividend.data.repository.DividendDiscountResult
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
data class DividendValuationUiState(
    val stock: StockEntity? = null,
    val isLoading: Boolean = true,
    val hasDividendHistory: Boolean = true,
    val dividendBasisYears: Int = 0,
    val currentPrice: Double? = null,
    val dividendBasisInput: String = "",
    val growthRateInput: String = "5",
    val discountRateInput: String = "9",
    val terminalGrowthRateInput: String = "2",
    val projectionYearsInput: String = "10",
    val marginOfSafetyInput: String = "20",
    val result: DividendDiscountResult? = null,
    val validationError: String? = null
)

@HiltViewModel
class DividendValuationViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val stockRepository: StockRepository,
    private val dividendRepository: DividendRepository
) : ViewModel() {
    private val stockCode: String = savedStateHandle["code"] ?: ""
    private val _uiState = MutableStateFlow(DividendValuationUiState())
    val uiState: StateFlow<DividendValuationUiState> = _uiState.asStateFlow()

    private val stockFlow = stockRepository.observeStock(stockCode)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    private val dividendsFlow = dividendRepository.observeDividends(stockCode)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            combine(stockFlow, dividendsFlow) { stock, dividends -> stock to dividends }
                .collect { (stock, dividends) ->
                    val basis = DividendDiscountCalculator.deriveDividendBasis(dividends)
                    val currentPrice = stock?.let {
                        try {
                            stockRepository.fetchQuotes(listOf(it))[it.code]
                        } catch (_: Exception) {
                            null
                        }
                    }
                    _uiState.value = _uiState.value.copy(
                        stock = stock,
                        isLoading = false,
                        hasDividendHistory = basis != null,
                        dividendBasisYears = basis?.actualYears ?: 0,
                        currentPrice = currentPrice,
                        dividendBasisInput = if (_uiState.value.dividendBasisInput.isBlank() && basis != null) {
                            String.format(Locale.US, "%.2f", basis.averageCashPerShare)
                        } else {
                            _uiState.value.dividendBasisInput
                        }
                    ).recalculated()
                }
        }
    }

    fun onDividendBasisChanged(value: String) = update { copy(dividendBasisInput = value) }

    fun onGrowthRateChanged(value: String) = update { copy(growthRateInput = value) }

    fun onDiscountRateChanged(value: String) = update { copy(discountRateInput = value) }

    fun onTerminalGrowthRateChanged(value: String) = update { copy(terminalGrowthRateInput = value) }

    fun onProjectionYearsChanged(value: String) = update { copy(projectionYearsInput = value) }

    fun onMarginOfSafetyChanged(value: String) = update { copy(marginOfSafetyInput = value) }

    fun applyPreset(preset: DividendValuationPreset) = update {
        copy(
            growthRateInput = preset.growthPercent,
            discountRateInput = preset.discountPercent,
            terminalGrowthRateInput = preset.terminalGrowthPercent,
            marginOfSafetyInput = preset.marginPercent
        )
    }

    private fun update(block: DividendValuationUiState.() -> DividendValuationUiState) {
        _uiState.value = _uiState.value.block().recalculated()
    }

    private fun DividendValuationUiState.recalculated(): DividendValuationUiState {
        val basis = dividendBasisInput.toDoubleOrNull()
        val growth = growthRateInput.toDoubleOrNull()
        val discount = discountRateInput.toDoubleOrNull()
        val terminal = terminalGrowthRateInput.toDoubleOrNull()
        val years = projectionYearsInput.toIntOrNull()
        val margin = marginOfSafetyInput.toDoubleOrNull()

        if (basis == null || growth == null || discount == null || terminal == null || years == null || margin == null) {
            return copy(result = null, validationError = "请输入有效估值参数")
        }

        val result = DividendDiscountCalculator.calculate(
            DividendDiscountInput(
                dividendBasisPerShare = basis,
                dividendGrowthRate = (growth / 100.0).coerceIn(0.0, 0.5),
                discountRate = (discount / 100.0).coerceIn(0.0, 0.5),
                terminalGrowthRate = (terminal / 100.0).coerceIn(0.0, 0.5),
                projectionYears = years,
                marginOfSafety = (margin / 100.0).coerceIn(0.0, 0.5),
                currentPrice = currentPrice
            )
        )

        return copy(result = result, validationError = result.validationError)
    }
}

enum class DividendValuationPreset(
    val label: String,
    val growthPercent: String,
    val discountPercent: String,
    val terminalGrowthPercent: String,
    val marginPercent: String
) {
    CONSERVATIVE("保守", "2", "10", "1", "25"),
    BASE("基准", "5", "9", "2", "20"),
    OPTIMISTIC("乐观", "8", "8", "3", "15")
}
