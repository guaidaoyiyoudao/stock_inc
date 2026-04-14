package com.stock.dividend.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stock.dividend.data.repository.StockRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class EditHoldingUiState(
    val stockCode: String = "",
    val stockName: String? = null,
    val sharesInput: String = "",
    val sharesError: String? = null,
    val yieldPeriod: String = "3"
)

@HiltViewModel
class EditHoldingViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val stockRepository: StockRepository
) : ViewModel() {

    private val stockCode: String = savedStateHandle["code"] ?: ""

    private val _uiState = MutableStateFlow(EditHoldingUiState(stockCode = stockCode))
    val uiState: StateFlow<EditHoldingUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            stockRepository.observeStock(stockCode).collect { stock ->
                if (stock != null) {
                    _uiState.value = _uiState.value.copy(
                        stockName = stock.name,
                        sharesInput = if (stock.shares > 0) stock.shares.toString() else "",
                        yieldPeriod = stock.yieldPeriod
                    )
                }
            }
        }
    }

    fun onSharesChanged(input: String) {
        val error = if (input.isNotBlank()) {
            val parsed = input.toIntOrNull()
            if (parsed == null || parsed < 0) "请输入有效的非负整数" else null
        } else null
        _uiState.value = _uiState.value.copy(
            sharesInput = input,
            sharesError = error
        )
    }

    fun onYieldPeriodChanged(period: String) {
        _uiState.value = _uiState.value.copy(yieldPeriod = period)
    }

    fun saveHolding() {
        val shares = _uiState.value.sharesInput.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val period = _uiState.value.yieldPeriod
        viewModelScope.launch {
            stockRepository.updateShares(stockCode, shares)
            stockRepository.updateYieldPeriod(stockCode, period)
        }
    }
}
