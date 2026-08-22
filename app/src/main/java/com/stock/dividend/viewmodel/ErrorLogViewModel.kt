package com.stock.dividend.viewmodel

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stock.dividend.data.local.entity.ErrorLogEntity
import com.stock.dividend.data.repository.ErrorLogCategory
import com.stock.dividend.data.repository.ErrorLogRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 单条失败日志的展示条目（category raw 已转中文 label）。 */
@Stable
data class ErrorLogItem(
    val id: Long,
    val timestamp: Long,
    val categoryLabel: String,
    val source: String,
    val message: String,
    val detail: String?,
)

@Stable
data class ErrorLogUiState(
    val isLoading: Boolean = true,
    val logs: List<ErrorLogItem> = emptyList(),
    /** 展开堆栈详情的日志 id（null = 全部收起）。 */
    val expandedLogId: Long? = null,
    /** 正在确认「清理全部」。 */
    val confirmingClear: Boolean = false,
    val isClearing: Boolean = false,
    /** 一次性结果提示（Snackbar 消费后调 [ErrorLogViewModel.consumeMessage]）。 */
    val message: String? = null,
)

/**
 * 失败日志 VM（设置 → 数据 → 失败日志）：关键静默失败（数据获取失败等）的
 * 展示 + 全部清理。
 *
 * 列表经 [ErrorLogRepository.observeAll] 响应式订阅（Room Flow，清理后自动重发射）；
 * 仓库自身吞异常（红线 #2），VM 保证 isLoading/isClearing 复位（红线 #3）。
 */
@HiltViewModel
class ErrorLogViewModel @Inject constructor(
    private val errorLogRepository: ErrorLogRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ErrorLogUiState())
    val uiState: StateFlow<ErrorLogUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            // collect 异常退出（DB 故障等）也要复位 isLoading，不能停在加载态
            runCatching {
                errorLogRepository.observeAll().collect { logs ->
                    _uiState.update { it.copy(isLoading = false, logs = logs.map { it.toItem() }) }
                }
            }
            _uiState.update { if (it.isLoading) it.copy(isLoading = false) else it }
        }
    }

    fun toggleExpanded(id: Long) {
        _uiState.update {
            it.copy(expandedLogId = if (it.expandedLogId == id) null else id)
        }
    }

    fun onClearClicked() {
        _uiState.update { it.copy(confirmingClear = true) }
    }

    fun dismissConfirm() {
        _uiState.update { it.copy(confirmingClear = false) }
    }

    fun confirmClear() {
        if (!_uiState.value.confirmingClear) return
        _uiState.update { it.copy(isClearing = true, confirmingClear = false) }
        viewModelScope.launch {
            errorLogRepository.clearAll()
            // logs 经 observeAll 自动重发射为空
            _uiState.update { it.copy(isClearing = false, message = "已清理全部失败日志") }
        }
    }

    fun consumeMessage() {
        _uiState.update { it.copy(message = null) }
    }

    private fun ErrorLogEntity.toItem(): ErrorLogItem = ErrorLogItem(
        id = id,
        timestamp = timestamp,
        categoryLabel = ErrorLogCategory.entries.firstOrNull { it.name == category }?.label
            ?: category,
        source = source,
        message = message,
        detail = detail,
    )
}
