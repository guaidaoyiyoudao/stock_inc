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
    val addInputError: String? = null,
    val showEditTransactionDialog: Boolean = false,
    val editingTransaction: TransactionEntity? = null,
    val editSharesInput: String = "",
    val editPriceInput: String = "",
    val editDateInput: String = "",
    val editInputError: String? = null,
    // ── 标签 ──────────────────────────────────────────
    val tags: List<String> = emptyList(),
    val allTags: List<String> = emptyList(),
    val showAddTagDialog: Boolean = false,
    val addTagInput: String = "",
    val addTagError: String? = null
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
                    val holding = calculateHolding(transactions)

                    _uiState.value = _uiState.value.copy(
                        stockName = stock.name,
                        totalShares = holding.totalShares,
                        avgCostPerShare = holding.avgCostPerShare,
                        transactions = transactions,
                        yieldPeriod = stock.yieldPeriod
                    )
                }
            }
        }
        // 订阅当前股票标签 + 全局已有标签（用于输入建议）
        viewModelScope.launch {
            stockRepository.observeTagsForStock(stockCode).collect { tags ->
                _uiState.value = _uiState.value.copy(tags = tags)
            }
        }
        viewModelScope.launch {
            stockRepository.observeAllTags().collect { all ->
                _uiState.value = _uiState.value.copy(allTags = all)
            }
        }
    }

    fun onYieldPeriodChanged(period: String) {
        _uiState.value = _uiState.value.copy(yieldPeriod = period)
    }

    fun showAddTagDialog() {
        _uiState.value = _uiState.value.copy(
            showAddTagDialog = true,
            addTagInput = "",
            addTagError = null
        )
    }

    fun onAddTagInputChanged(input: String) {
        _uiState.value = _uiState.value.copy(addTagInput = input, addTagError = null)
    }

    fun dismissAddTagDialog() {
        _uiState.value = _uiState.value.copy(
            showAddTagDialog = false,
            addTagInput = "",
            addTagError = null
        )
    }

    /** 确认添加标签：去空白、校验长度、去重；命中已有同名标签直接选中。 */
    fun confirmAddTag() {
        val raw = _uiState.value.addTagInput.trim()
        if (raw.isEmpty()) {
            _uiState.value = _uiState.value.copy(addTagError = "标签不能为空")
            return
        }
        if (raw.length > 20) {
            _uiState.value = _uiState.value.copy(addTagError = "标签最长 20 个字符")
            return
        }
        val current = _uiState.value.tags
        if (raw in current) {
            _uiState.value = _uiState.value.copy(showAddTagDialog = false, addTagInput = "")
            return
        }
        _uiState.value = _uiState.value.copy(
            tags = current + raw,
            showAddTagDialog = false,
            addTagInput = "",
            addTagError = null
        )
    }

    fun removeTag(tag: String) {
        _uiState.value = _uiState.value.copy(tags = _uiState.value.tags - tag)
    }

    fun saveHolding() {
        viewModelScope.launch {
            stockRepository.updateYieldPeriod(stockCode, _uiState.value.yieldPeriod)
            stockRepository.setStockTags(stockCode, _uiState.value.tags)
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
            showAddSellDialog = false,
            showEditTransactionDialog = false,
            editingTransaction = null,
            editInputError = null,
            showAddTagDialog = false,
            addTagInput = "",
            addTagError = null
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
            refreshHoldingState {
                copy(
                    showAddBuyDialog = false,
                    showAddSellDialog = false,
                    addInputError = null
                )
            }
        }
    }

    fun showEditTransactionDialog(transaction: TransactionEntity) {
        _uiState.value = _uiState.value.copy(
            showAddBuyDialog = false,
            showAddSellDialog = false,
            showEditTransactionDialog = true,
            editingTransaction = transaction,
            editSharesInput = transaction.shares.toString(),
            editPriceInput = if (transaction.price > 0.0) transaction.price.toString() else "",
            editDateInput = transaction.date,
            editInputError = null
        )
    }

    fun onEditSharesChanged(input: String) {
        _uiState.value = _uiState.value.copy(editSharesInput = input)
    }

    fun onEditPriceChanged(input: String) {
        _uiState.value = _uiState.value.copy(editPriceInput = input)
    }

    fun onEditDateChanged(input: String) {
        _uiState.value = _uiState.value.copy(editDateInput = input)
    }

    fun confirmEditTransaction() {
        val transaction = _uiState.value.editingTransaction ?: return
        val shares = _uiState.value.editSharesInput.toIntOrNull()
        val price = _uiState.value.editPriceInput.toDoubleOrNull() ?: 0.0
        val date = _uiState.value.editDateInput
        val isBuy = transaction.type == "BUY"

        if (shares == null || shares <= 0) {
            _uiState.value = _uiState.value.copy(editInputError = "请输入有效的股数")
            return
        }

        if (date.isBlank()) {
            _uiState.value = _uiState.value.copy(editInputError = "请输入日期")
            return
        }

        if (isBuy && price <= 0) {
            _uiState.value = _uiState.value.copy(editInputError = "请输入买入价格")
            return
        }

        viewModelScope.launch {
            transactionRepository.updateTransaction(
                transaction.copy(
                    shares = shares,
                    price = price,
                    date = date
                )
            )
            refreshHoldingState {
                copy(
                    showEditTransactionDialog = false,
                    editingTransaction = null,
                    editInputError = null
                )
            }
        }
    }

    fun deleteTransaction(transaction: TransactionEntity) {
        viewModelScope.launch {
            transactionRepository.deleteTransaction(transaction)
            refreshHoldingState()
        }
    }

    private suspend fun refreshHoldingState(transform: EditHoldingUiState.() -> EditHoldingUiState = { this }) {
        val transactions = transactionRepository.getByStock(stockCode)
        val holding = calculateHolding(transactions)

        stockRepository.updateShares(stockCode, holding.totalShares)
        stockRepository.updateCostPerShare(stockCode, holding.avgCostPerShare)

        _uiState.value = _uiState.value.copy(
            totalShares = holding.totalShares,
            avgCostPerShare = holding.avgCostPerShare,
            transactions = transactions
        ).transform()
    }

    private fun calculateHolding(transactions: List<TransactionEntity>): HoldingSummary {
        val totalShares = transactions.sumOf {
            if (it.type == "BUY") it.shares.toLong() else -it.shares.toLong()
        }.toInt().coerceAtLeast(0)
        val buyShares = transactions.filter { it.type == "BUY" }.sumOf { it.shares.toLong() }.toInt()
        val totalCost = transactions.filter { it.type == "BUY" }.sumOf { it.price * it.shares }
        val avgCost = if (buyShares > 0) totalCost / buyShares else 0.0

        return HoldingSummary(totalShares, avgCost)
    }

    private data class HoldingSummary(
        val totalShares: Int,
        val avgCostPerShare: Double
    )
}
