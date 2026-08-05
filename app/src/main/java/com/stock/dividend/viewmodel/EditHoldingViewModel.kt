package com.stock.dividend.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stock.dividend.data.local.entity.TransactionEntity
import com.stock.dividend.data.repository.HoldingCalculator
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
    // ── 添加交易（买入/卖出合并到一个 Sheet，方向用 isBuyInput 区分）──
    val showTransactionSheet: Boolean = false,
    val isBuyInput: Boolean = true,
    val addSharesInput: String = "",
    val addPriceInput: String = "",
    val addDateInput: String = LocalDate.now().toString(),
    val addNoteInput: String = "",
    val addSharesError: String? = null,
    val addPriceError: String? = null,
    // ── 编辑已有交易（方向锁定，不可切换）──
    val showEditTransactionSheet: Boolean = false,
    val editingTransaction: TransactionEntity? = null,
    val editSharesInput: String = "",
    val editPriceInput: String = "",
    val editDateInput: String = "",
    val editNoteInput: String = "",
    val editSharesError: String? = null,
    val editPriceError: String? = null,
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

    /** 解析 query 参数 buyPrice/buyShares；任一缺失返回 null（不预填）。 */
    private val prefilledTransaction: Pair<String, String>? by lazy {
        val price = savedStateHandle.get<String>("buyPrice")
        val shares = savedStateHandle.get<String>("buyShares")
        if (!price.isNullOrBlank() && !shares.isNullOrBlank()) price to shares else null
    }

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
        // 从个股详情/网格页跳转携带的预填参数（query string）：buyPrice / buyShares
        // 命中时自动打开买入表单并预填，实现「下一档一键记账」闭环。
        // 仅首次进入生效（query 参数一次性），避免每次订阅都重开表单。
        prefilledTransaction?.let { (price, shares) ->
            _uiState.value = _uiState.value.copy(
                showTransactionSheet = true,
                isBuyInput = true,
                addPriceInput = price,
                addSharesInput = shares,
                addDateInput = LocalDate.now().toString(),
                addSharesError = null,
                addPriceError = null
            )
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

    /** 打开交易录入 Sheet。默认方向：无持仓 → 买入，有持仓 → 卖出。 */
    fun showTransactionSheet(isBuy: Boolean) {
        _uiState.value = _uiState.value.copy(
            showTransactionSheet = true,
            isBuyInput = isBuy,
            addSharesInput = "",
            addPriceInput = "",
            addDateInput = LocalDate.now().toString(),
            addNoteInput = "",
            addSharesError = null,
            addPriceError = null
        )
    }

    /** 表单内切换买/卖方向：保留已输入的股数/价格，仅清方向相关错误。 */
    fun onTransactionTypeChanged(isBuy: Boolean) {
        _uiState.value = _uiState.value.copy(
            isBuyInput = isBuy,
            addPriceError = null
        )
    }

    fun dismissDialog() {
        _uiState.value = _uiState.value.copy(
            showTransactionSheet = false,
            showEditTransactionSheet = false,
            editingTransaction = null,
            editSharesError = null,
            editPriceError = null,
            showAddTagDialog = false,
            addTagInput = "",
            addTagError = null
        )
    }

    fun onAddSharesChanged(input: String) {
        _uiState.value = _uiState.value.copy(
            addSharesInput = input,
            addSharesError = null
        )
    }

    fun onAddPriceChanged(input: String) {
        _uiState.value = _uiState.value.copy(
            addPriceInput = input,
            addPriceError = null
        )
    }

    fun onAddDateChanged(input: String) {
        _uiState.value = _uiState.value.copy(addDateInput = input)
    }

    fun onAddNoteChanged(input: String) {
        _uiState.value = _uiState.value.copy(addNoteInput = input)
    }

    fun confirmAddTransaction() {
        val state = _uiState.value
        val isBuy = state.isBuyInput
        val shares = state.addSharesInput.toIntOrNull()
        val price = state.addPriceInput.toDoubleOrNull() ?: 0.0
        val date = state.addDateInput

        // 字段级校验：一次把所有错误都标出，便于用户修正
        var sharesError: String? = null
        var priceError: String? = null
        if (shares == null || shares <= 0) sharesError = "请输入有效的股数"
        if (isBuy && price <= 0) priceError = "请输入买入价格"
        if (sharesError != null || priceError != null) {
            _uiState.value = _uiState.value.copy(
                addSharesError = sharesError,
                addPriceError = priceError
            )
            return
        }

        val type = if (isBuy) "BUY" else "SELL"
        val note = state.addNoteInput.trim().takeIf { it.isNotEmpty() }
        viewModelScope.launch {
            transactionRepository.addTransaction(
                TransactionEntity(
                    stockCode = stockCode,
                    type = type,
                    shares = shares!!,
                    price = price,
                    date = date,
                    note = note
                )
            )
            refreshHoldingState {
                copy(
                    showTransactionSheet = false,
                    addSharesError = null,
                    addPriceError = null
                )
            }
        }
    }

    fun showEditTransactionDialog(transaction: TransactionEntity) {
        _uiState.value = _uiState.value.copy(
            showTransactionSheet = false,
            showEditTransactionSheet = true,
            editingTransaction = transaction,
            editSharesInput = transaction.shares.toString(),
            editPriceInput = if (transaction.price > 0.0) transaction.price.toString() else "",
            editDateInput = transaction.date,
            editNoteInput = transaction.note.orEmpty(),
            editSharesError = null,
            editPriceError = null
        )
    }

    fun onEditSharesChanged(input: String) {
        _uiState.value = _uiState.value.copy(
            editSharesInput = input,
            editSharesError = null
        )
    }

    fun onEditPriceChanged(input: String) {
        _uiState.value = _uiState.value.copy(
            editPriceInput = input,
            editPriceError = null
        )
    }

    fun onEditDateChanged(input: String) {
        _uiState.value = _uiState.value.copy(editDateInput = input)
    }

    fun onEditNoteChanged(input: String) {
        _uiState.value = _uiState.value.copy(editNoteInput = input)
    }

    fun confirmEditTransaction() {
        val transaction = _uiState.value.editingTransaction ?: return
        val shares = _uiState.value.editSharesInput.toIntOrNull()
        val price = _uiState.value.editPriceInput.toDoubleOrNull() ?: 0.0
        val date = _uiState.value.editDateInput
        val isBuy = transaction.type == "BUY"

        var sharesError: String? = null
        var priceError: String? = null
        if (shares == null || shares <= 0) sharesError = "请输入有效的股数"
        if (isBuy && price <= 0) priceError = "请输入买入价格"
        if (sharesError != null || priceError != null) {
            _uiState.value = _uiState.value.copy(
                editSharesError = sharesError,
                editPriceError = priceError
            )
            return
        }

        viewModelScope.launch {
            val note = _uiState.value.editNoteInput.trim().takeIf { it.isNotEmpty() }
            transactionRepository.updateTransaction(
                transaction.copy(
                    shares = shares!!,
                    price = price,
                    date = date,
                    note = note
                )
            )
            refreshHoldingState {
                copy(
                    showEditTransactionSheet = false,
                    editingTransaction = null,
                    editSharesError = null,
                    editPriceError = null
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

    private fun calculateHolding(transactions: List<TransactionEntity>) =
        HoldingCalculator.calculate(transactions)
}
