package com.stock.dividend.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stock.dividend.data.plane.MarketDataPlane
import com.stock.dividend.data.local.entity.TransactionEntity
import com.stock.dividend.data.repository.TransactionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 全局交易流水列表项：交易记录 + 对应股票名（解析自 stocks 表）。
 * 股票已被删除时（外键级联会连同删除其交易，故此处 name 理论上不为空，仍兜底用 code）。
 */
data class TransactionHistoryItem(
    val transaction: TransactionEntity,
    val stockName: String,
    val stockCode: String
)

data class TransactionHistoryUiState(
    val items: List<TransactionHistoryItem> = emptyList(),
    /** 累计买入金额（元）。 */
    val totalBuyAmount: Double = 0.0,
    /** 累计卖出金额（元）。 */
    val totalSellAmount: Double = 0.0,
    val isLoading: Boolean = true,
    // ── 笔记编辑弹窗 ──
    val showNoteDialog: Boolean = false,
    val editingTransaction: TransactionEntity? = null,
    val noteInput: String = ""
)

/**
 * 全局交易流水页 ViewModel（§4.2 模式）。
 *
 * 订阅全量交易流水 + 全量自选股，组合成带股票名的列表项，按日期倒序展示。
 * 支持编辑单笔交易备注（复盘笔记）。
 */
@HiltViewModel
class TransactionHistoryViewModel @Inject constructor(
    private val transactionRepository: TransactionRepository,
    private val marketDataPlane: MarketDataPlane
) : ViewModel() {

    private val _uiState = MutableStateFlow(TransactionHistoryUiState())
    val uiState: StateFlow<TransactionHistoryUiState> = _uiState.asStateFlow()

    init {
        // 单 collector：全量交易 + 股票名映射 → 组合列表项 + 汇总
        // 交易流水不大（个人投资记录），combine 一次即可，无需拆多 collector。
        viewModelScope.launch {
            transactionRepository.observeAll()
                .combine(marketDataPlane.observeAllStocks()) { txs, stocks ->
                    val nameByCode = stocks.associate { it.code to it.name }
                    txs.map { tx ->
                        TransactionHistoryItem(
                            transaction = tx,
                            stockName = nameByCode[tx.stockCode] ?: tx.stockCode,
                            stockCode = tx.stockCode
                        )
                    }.sortedByDescending { it.transaction.date } // 最新在前
                }
                .collect { items ->
                    val buy = items.sumOf { tx ->
                        if (tx.transaction.type == "BUY") tx.transaction.price * tx.transaction.shares else 0.0
                    }
                    val sell = items.sumOf { tx ->
                        if (tx.transaction.type == "SELL") tx.transaction.price * tx.transaction.shares else 0.0
                    }
                    _uiState.update {
                        it.copy(
                            items = items,
                            totalBuyAmount = buy,
                            totalSellAmount = sell,
                            isLoading = false
                        )
                    }
                }
        }
    }

    fun showNoteDialog(transaction: TransactionEntity) {
        _uiState.update {
            it.copy(
                showNoteDialog = true,
                editingTransaction = transaction,
                noteInput = transaction.note.orEmpty()
            )
        }
    }

    fun onNoteChanged(input: String) {
        _uiState.update { it.copy(noteInput = input) }
    }

    fun dismissNoteDialog() {
        _uiState.update {
            it.copy(showNoteDialog = false, editingTransaction = null, noteInput = "")
        }
    }

    /** 保存备注：trim 后空串落库为 null，避免展示噪音。 */
    fun confirmNote() {
        val transaction = _uiState.value.editingTransaction ?: return
        val note = _uiState.value.noteInput.trim().takeIf { it.isNotEmpty() }
        viewModelScope.launch {
            transactionRepository.updateTransaction(transaction.copy(note = note))
            _uiState.update {
                it.copy(showNoteDialog = false, editingTransaction = null, noteInput = "")
            }
        }
    }
}
