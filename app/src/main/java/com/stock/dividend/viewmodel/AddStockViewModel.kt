package com.stock.dividend.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stock.dividend.data.repository.DividendRepository
import com.stock.dividend.data.repository.StockRepository
import com.stock.dividend.data.repository.StockSearchResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class AddStockUiState(
    val searchQuery: String = "",
    val searchResults: List<StockSearchResult> = emptyList(),
    val selectedStock: StockSearchResult? = null,
    val isSearching: Boolean = false,
    val error: String? = null,
    val addedStock: String? = null,
    val canRetry: Boolean = false,
    val shares: Int = 0,
    val sharesInput: String = "",
    val sharesError: String? = null,
    val costPerShareInput: String = "",
    val costPerShareError: String? = null,
    val buyDateInput: String = LocalDate.now().toString(),
    val buyDateError: String? = null,
    val hasSearched: Boolean = false
)

@OptIn(FlowPreview::class)
@HiltViewModel
class AddStockViewModel @Inject constructor(
    private val stockRepository: StockRepository,
    private val dividendRepository: DividendRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AddStockUiState())
    val uiState: StateFlow<AddStockUiState> = _uiState.asStateFlow()

    private val searchQuery = MutableStateFlow("")
    private var lastSearchQuery: String? = null
    private var lastAddResult: StockSearchResult? = null

    init {
        viewModelScope.launch {
            searchQuery
                .debounce(300)
                .distinctUntilChanged()
                .filter { it.isNotBlank() }
                .collect { query ->
                    performSearch(query)
                }
        }
    }

    fun onSearchQueryChanged(query: String) {
        _uiState.value = _uiState.value.copy(
            searchQuery = query,
            error = null,
            addedStock = null,
            canRetry = false
        )
        searchQuery.value = query
        if (query.isBlank()) {
            _uiState.value = _uiState.value.copy(
                searchResults = emptyList(),
                selectedStock = null,
                hasSearched = false,
                isSearching = false
            )
        }
    }

    fun quickAddFirstResult() {
        _uiState.value.searchResults.firstOrNull()?.let(::selectStock)
    }

    fun onSharesChanged(input: String) {
        val error = if (input.isNotBlank()) {
            val parsed = input.toIntOrNull()
            if (parsed == null || parsed < 0) "请输入有效的非负整数" else null
        } else null
        _uiState.value = _uiState.value.copy(
            sharesInput = input,
            shares = input.toIntOrNull()?.coerceAtLeast(0) ?: 0,
            sharesError = error
        )
    }

    fun onCostPerShareChanged(input: String) {
        val error = if (input.isNotBlank()) {
            val parsed = input.toDoubleOrNull()
            if (parsed == null || parsed < 0) "请输入有效的非负数" else null
        } else null
        _uiState.value = _uiState.value.copy(
            costPerShareInput = input,
            costPerShareError = error
        )
    }

    fun onBuyDateChanged(input: String) {
        val error = if (input.isNotBlank()) {
            try {
                LocalDate.parse(input)
                null
            } catch (e: Exception) {
                "日期格式无效 (YYYY-MM-DD)"
            }
        } else null
        _uiState.value = _uiState.value.copy(
            buyDateInput = input,
            buyDateError = error
        )
    }

    fun selectStock(result: StockSearchResult) {
        _uiState.value = _uiState.value.copy(
            selectedStock = if (_uiState.value.selectedStock?.code == result.code) null else result
        )
    }

    fun confirmAddStock() {
        val result = _uiState.value.selectedStock ?: return
        if (!isInputValid()) return
        addStock(result)
    }

    fun addStock(result: StockSearchResult) {
        lastAddResult = result
        val costPerShare = _uiState.value.costPerShareInput.toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0
        val buyDate = _uiState.value.buyDateInput
        viewModelScope.launch {
            stockRepository.addStock(result, _uiState.value.shares, costPerShare, buyDate)
                .onSuccess {
                    val securityCode = result.code.substringAfter(".")
                    dividendRepository.fetchAndCacheDividends(result.code, securityCode)
                        .onSuccess {
                            _uiState.value = _uiState.value.copy(
                                addedStock = result.name,
                                error = null,
                                canRetry = false
                            )
                        }
                        .onFailure { e ->
                            _uiState.value = _uiState.value.copy(
                                addedStock = result.name,
                                error = e.message ?: "股息数据加载失败，请重试",
                                canRetry = true
                            )
                        }
                }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(
                        error = e.message ?: "添加失败，请重试",
                        canRetry = true
                    )
                }
        }
    }

    fun retrySearch() {
        val query = lastSearchQuery ?: return
        viewModelScope.launch {
            performSearch(query)
        }
    }

    fun retryAddStock() {
        val result = lastAddResult ?: return
        addStock(result)
    }

    fun resetForNewAdd() {
        _uiState.value = _uiState.value.copy(
            searchQuery = "",
            searchResults = emptyList(),
            selectedStock = null,
            addedStock = null,
            error = null,
            canRetry = false,
            sharesInput = "",
            shares = 0,
            sharesError = null,
            costPerShareInput = "",
            costPerShareError = null,
            buyDateInput = LocalDate.now().toString(),
            buyDateError = null,
            hasSearched = false
        )
        searchQuery.value = ""
    }

    private fun isInputValid(): Boolean {
        val state = _uiState.value
        return state.sharesError == null && state.costPerShareError == null && state.buyDateError == null
    }

    private suspend fun performSearch(query: String) {
        lastSearchQuery = query
        _uiState.value = _uiState.value.copy(isSearching = true, error = null, canRetry = false)
        stockRepository.searchStocks(query)
            .onSuccess { results ->
                _uiState.value = _uiState.value.copy(
                    searchResults = results,
                    isSearching = false,
                    hasSearched = true
                )
            }
            .onFailure { e ->
                _uiState.value = _uiState.value.copy(
                    searchResults = emptyList(),
                    isSearching = false,
                    hasSearched = true,
                    error = e.message ?: "搜索失败，请重试",
                    canRetry = true
                )
            }
    }
}
