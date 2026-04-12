package com.stock.dividend.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stock.dividend.data.local.entity.DividendEntity
import com.stock.dividend.data.local.entity.StockEntity
import com.stock.dividend.data.repository.DividendRepository
import com.stock.dividend.data.repository.StockRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class StockDetailUiState(
    val stock: StockEntity? = null,
    val dividends: List<DividendEntity> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null
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
            val stocks = stockRepository.observeAllStocks()
            stocks.collect { list ->
                val stock = list.find { it.code == stockCode }
                if (stock != null) {
                    _uiState.value = _uiState.value.copy(stock = stock)
                }
            }
        }
    }

    private fun observeDividends() {
        viewModelScope.launch {
            dividendRepository.observeDividends(stockCode).collect { dividends ->
                _uiState.value = _uiState.value.copy(
                    dividends = dividends,
                    isLoading = false
                )
            }
        }
    }
}
