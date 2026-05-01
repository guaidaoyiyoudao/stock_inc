package com.stock.dividend.viewmodel

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stock.dividend.data.local.entity.DividendIncomeRecordEntity
import com.stock.dividend.data.local.entity.StockEntity
import com.stock.dividend.data.repository.DividendIncomeRepository
import com.stock.dividend.data.repository.StockRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

@Stable
data class DividendIncomeUiState(
    val selectedYear: Int = LocalDate.now().year,
    val records: List<DividendIncomeRecordWithStock> = emptyList(),
    val availableYears: List<Int> = emptyList(),
    val yearlyTotal: Double = 0.0,
    val manualCount: Int = 0,
    val autoCount: Int = 0,
    val prevYearTotal: Double? = null,
    val yearlyTotals: Map<Int, Double> = emptyMap(),
    val stocks: List<StockEntity> = emptyList(),
    val showAddDialog: Boolean = false,
    val showCorrectDialog: Boolean = false,
    val correctTargetId: String = "",
    val correctCurrentAmount: Double = 0.0,
    val isLoading: Boolean = true
)

@Stable
data class DividendIncomeRecordWithStock(
    val record: DividendIncomeRecordEntity,
    val stockName: String? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DividendIncomeViewModel @Inject constructor(
    private val incomeRepository: DividendIncomeRepository,
    private val stockRepository: StockRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(DividendIncomeUiState())
    val uiState: StateFlow<DividendIncomeUiState> = _uiState.asStateFlow()

    private val _selectedYear = MutableStateFlow(LocalDate.now().year)

    private val stocksFlow = stockRepository.observeAllStocks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        // Auto-generate missing records on init
        viewModelScope.launch {
            incomeRepository.generateMissingAutoRecords()
            _uiState.value = _uiState.value.copy(isLoading = false)
        }

        // Observe available years
        viewModelScope.launch {
            incomeRepository.observeAvailableYears().collect { years ->
                _uiState.value = _uiState.value.copy(availableYears = years)
            }
        }

        // Observe records for selected year + stock names
        viewModelScope.launch {
            combine(
                _selectedYear.flatMapLatest { year ->
                    incomeRepository.observeByYear(year)
                },
                stocksFlow
            ) { records, stocks ->
                val stockMap = stocks.associateBy { it.code }
                records.map { record ->
                    DividendIncomeRecordWithStock(
                        record = record,
                        stockName = stockMap[record.stockCode]?.name
                    )
                }
            }.collect { recordsWithStock ->
                val manualCount = recordsWithStock.count { it.record.source == "manual" }
                val autoCount = recordsWithStock.count { it.record.source == "auto" }
                _uiState.value = _uiState.value.copy(
                    records = recordsWithStock,
                    manualCount = manualCount,
                    autoCount = autoCount
                )
            }
        }

        // Observe current year total
        viewModelScope.launch {
            _selectedYear.flatMapLatest { year ->
                incomeRepository.observeTotalByYear(year)
            }.collect { total ->
                _uiState.value = _uiState.value.copy(yearlyTotal = total)
            }
        }

        // Observe previous year total for YoY comparison
        viewModelScope.launch {
            _selectedYear.flatMapLatest { year ->
                incomeRepository.observeTotalByYear(year - 1)
            }.collect { prevTotal ->
                _uiState.value = _uiState.value.copy(
                    prevYearTotal = if (prevTotal == 0.0) null else prevTotal
                )
            }
        }

        // Observe stocks for stock selector
        viewModelScope.launch {
            stocksFlow.collect { stocks ->
                _uiState.value = _uiState.value.copy(stocks = stocks)
            }
        }

        // Observe yearly totals for trend chart
        viewModelScope.launch {
            incomeRepository.observeYearlyTotals().collect { totals ->
                _uiState.value = _uiState.value.copy(
                    yearlyTotals = totals.associate { it.year to it.total }
                )
            }
        }
    }

    fun selectYear(year: Int) {
        _selectedYear.value = year
        _uiState.value = _uiState.value.copy(selectedYear = year)
    }

    fun showAddDialog() {
        _uiState.value = _uiState.value.copy(showAddDialog = true)
    }

    fun dismissAddDialog() {
        _uiState.value = _uiState.value.copy(showAddDialog = false)
    }

    fun addManualRecord(date: String, amount: Double, stockCode: String?, note: String?) {
        _uiState.value = _uiState.value.copy(showAddDialog = false)
        viewModelScope.launch {
            incomeRepository.addManualRecord(date, amount, stockCode, note)
        }
    }

    fun showCorrectDialog(id: String, currentAmount: Double) {
        _uiState.value = _uiState.value.copy(
            showCorrectDialog = true,
            correctTargetId = id,
            correctCurrentAmount = currentAmount
        )
    }

    fun dismissCorrectDialog() {
        _uiState.value = _uiState.value.copy(showCorrectDialog = false)
    }

    fun correctRecord(id: String, amount: Double, note: String?) {
        _uiState.value = _uiState.value.copy(showCorrectDialog = false)
        viewModelScope.launch {
            incomeRepository.correctRecord(id, amount, note)
        }
    }

    fun deleteManualRecord(id: String) {
        viewModelScope.launch {
            incomeRepository.deleteManualRecord(id)
        }
    }
}
