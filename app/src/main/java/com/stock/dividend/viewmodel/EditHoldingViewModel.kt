package com.stock.dividend.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stock.dividend.data.local.entity.TransactionEntity
import com.stock.dividend.data.repository.StockRepository
import com.stock.dividend.data.repository.TransactionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class EditHoldingUiState(
    val stockCode: String = "",
    val stockName: String? = null,
    val totalShares: Int = 0,
    val avgCostPerShare: Double = 0.0,
    val transactions: List<TransactionEntity> = emptyList(),
    val yieldPeriod: String = "3",
    val showAddBuyDialog: Boolean = false,
    val showAddSellDialog: Boolean = false,
    val addSharesInput: String = "",
    val addPriceInput: String = "",
    val addDateInput: String = LocalDate.now().toString(),
    val addInputError: String? = null
)

@HiltViewModel
class EditHoldingViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val stockRepository: StockRepository,
    private val transactionRepository: TransactionRepository
) : ViewModel() {

    private val stockCode: String = savedStateHandle["code"] ?: ""

    private val _uiState = MutableStateFlow(EditHoldingUiState(stockCode = stockCode))
    val uiState: StateFlow<EditHoldingUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            stockRepository.observeStock(stockCode).collect { stock ->
                if (stock != null) {
                    val transactions = transactionRepository.getByStock(stockCode)
                    val totalShares = transactions.sumOf {
                        if (it.type == "BUY") it.shares.toLong() else -it.shares.toLong()
                    }.toInt().coerceAtLeast(0)
                    val buyShares = transactions.filter { it.type == "BUY" }.sumOf { it.shares.toLong() }.toInt()
                    val totalCost = transactions.filter { it.type == "BUY" }.sumOf { it.price * it.shares }
                    val avgCost = if (buyShares > 0) totalCost / buyShares else 0.0

                    _uiState.value = _uiState.value.copy(
                        stockName = stock.name,
                        totalShares = totalShares,
                        avgCostPerShare = avgCost,
                        transactions = transactions,
                        yieldPeriod = stock.yieldPeriod
                    )
                }
            }
        }
    }

    fun onYieldPeriodChanged(period: String) {
        _uiState.value = _uiState.value.copy(yieldPeriod = period)
    }

    fun saveHolding() {
        viewModelScope.launch {
            stockRepository.updateYieldPeriod(stockCode, _uiState.value.yieldPeriod)
        }
    }

    fun showAddBuyDialog() {
        _uiState.value = _uiState.value.copy(
            showAddBuyDialog = true,
            showAddSellDialog = false,
            addSharesInput = "",
            addPriceInput = "",
            addDateInput = LocalDate.now().toString(),
            addInputError = null
        )
    }

    fun showAddSellDialog() {
        _uiState.value = _uiState.value.copy(
            showAddBuyDialog = false,
            showAddSellDialog = true,
            addSharesInput = "",
            addPriceInput = "",
            addDateInput = LocalDate.now().toString(),
            addInputError = null
        )
    }

    fun dismissDialog() {
        _uiState.value = _uiState.value.copy(
            showAddBuyDialog = false,
            showAddSellDialog = false
        )
    }

    fun onAddSharesChanged(input: String) {
        _uiState.value = _uiState.value.copy(addSharesInput = input)
    }

    fun onAddPriceChanged(input: String) {
        _uiState.value = _uiState.value.copy(addPriceInput = input)
    }

    fun onAddDateChanged(input: String) {
        _uiState.value = _uiState.value.copy(addDateInput = input)
    }

    fun confirmAddTransaction(isBuy: Boolean) {
        val shares = _uiState.value.addSharesInput.toIntOrNull()
        val price = _uiState.value.addPriceInput.toDoubleOrNull() ?: 0.0
        val date = _uiState.value.addDateInput

        if (shares == null || shares <= 0) {
            _uiState.value = _uiState.value.copy(addInputError = "请输入有效的股数")
            return
        }

        if (date.isBlank()) {
            _uiState.value = _uiState.value.copy(addInputError = "请输入日期")
            return
        }

        if (isBuy && price <= 0) {
            _uiState.value = _uiState.value.copy(addInputError = "请输入买入价格")
            return
        }

        val type = if (isBuy) "BUY" else "SELL"
        viewModelScope.launch {
            transactionRepository.addTransaction(
                TransactionEntity(
                    stockCode = stockCode,
                    type = type,
                    shares = shares,
                    price = price,
                    date = date
                )
            )
            val transactions = transactionRepository.getByStock(stockCode)
            val totalShares = transactions.sumOf {
                if (it.type == "BUY") it.shares.toLong() else -it.shares.toLong()
            }.toInt().coerceAtLeast(0)
            val buyShares = transactions.filter { it.type == "BUY" }.sumOf { it.shares.toLong() }.toInt()
            val totalCost = transactions.filter { it.type == "BUY" }.sumOf { it.price * it.shares }
            val avgCost = if (buyShares > 0) totalCost / buyShares else 0.0

            stockRepository.updateShares(stockCode, totalShares)
            stockRepository.updateCostPerShare(stockCode, avgCost)

            _uiState.value = _uiState.value.copy(
                totalShares = totalShares,
                avgCostPerShare = avgCost,
                transactions = transactions,
                showAddBuyDialog = false,
                showAddSellDialog = false,
                addInputError = null
            )
        }
    }

    fun deleteTransaction(transaction: TransactionEntity) {
        viewModelScope.launch {
            transactionRepository.deleteTransaction(transaction)
            val transactions = transactionRepository.getByStock(stockCode)
            val totalShares = transactions.sumOf {
                if (it.type == "BUY") it.shares.toLong() else -it.shares.toLong()
            }.toInt().coerceAtLeast(0)
            val buyShares = transactions.filter { it.type == "BUY" }.sumOf { it.shares.toLong() }.toInt()
            val totalCost = transactions.filter { it.type == "BUY" }.sumOf { it.price * it.shares }
            val avgCost = if (buyShares > 0) totalCost / buyShares else 0.0

            stockRepository.updateShares(stockCode, totalShares)
            stockRepository.updateCostPerShare(stockCode, avgCost)

            _uiState.value = _uiState.value.copy(
                totalShares = totalShares,
                avgCostPerShare = avgCost,
                transactions = transactions
            )
        }
    }
}
