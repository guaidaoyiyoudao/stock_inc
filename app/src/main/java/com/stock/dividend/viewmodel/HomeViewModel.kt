package com.stock.dividend.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stock.dividend.data.local.dao.DividendDao
import com.stock.dividend.data.local.entity.StockEntity
import com.stock.dividend.data.repository.StockRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class StockWithDividends(
    val stock: StockEntity,
    val latestDividendYear: String? = null,
    val latestCashPerShare: Double? = null
)

data class HomeUiState(
    val stocks: List<StockEntity> = emptyList(),
    val totalDividend: Double = 0.0,
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
                _uiState.value = _uiState.value.copy(stocks = stocks)
            }
        }
        viewModelScope.launch {
            dividendDao.observeTotalCashPerShare().collect { total ->
                _uiState.value = _uiState.value.copy(totalDividend = total)
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
                )
            )
            _uiState.value = _uiState.value.copy(deletedStock = null)
        }
    }

    fun clearDeleted() {
        _uiState.value = _uiState.value.copy(deletedStock = null)
    }
}
