package com.stock.dividend.viewmodel

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stock.dividend.data.plane.MarketDataPlane
import com.stock.dividend.data.repository.CacheKind
import com.stock.dividend.data.repository.CacheManagementRepository
import com.stock.dividend.data.repository.CacheStats
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 单类缓存的展示条目。 */
@Stable
data class CacheEntry(
    val kind: CacheKind,
    val entries: Long,
    val permanent: Boolean,
)

@Stable
data class CacheManagementUiState(
    val isLoading: Boolean = true,
    val entries: List<CacheEntry> = emptyList(),
    /** 正在确认清理的单类缓存（确认弹窗数据源）。 */
    val confirming: CacheKind? = null,
    /** 正在确认「一键清理全部」。 */
    val confirmingAll: Boolean = false,
    val isClearing: Boolean = false,
    /** 一次性结果提示（Snackbar 消费后调 [CacheManagementViewModel.consumeMessage]）。 */
    val message: String? = null,
)

/**
 * 缓存管理 VM（设置 → 数据 → 缓存管理）：
 * 各持久缓存条目数展示 + 按种类清理（含「全部清理」）。
 *
 * 清理编排：`CacheManagementRepository.clear/clearAll`（持久层）+
 * [MarketDataPlane.clearSessionCaches]（内存会话缓存）+ 重新加载统计。
 * 仓库自身吞异常（红线 #2），VM 只需保证 isLoading/isClearing 复位（红线 #3）。
 */
@HiltViewModel
class CacheManagementViewModel @Inject constructor(
    private val repository: CacheManagementRepository,
    private val plane: MarketDataPlane,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CacheManagementUiState())
    val uiState: StateFlow<CacheManagementUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun onClearClicked(kind: CacheKind) {
        _uiState.update { it.copy(confirming = kind, confirmingAll = false) }
    }

    fun onClearAllClicked() {
        _uiState.update { it.copy(confirmingAll = true, confirming = null) }
    }

    fun dismissConfirm() {
        _uiState.update { it.copy(confirming = null, confirmingAll = false) }
    }

    fun confirmClear() {
        val kind = _uiState.value.confirming ?: return
        _uiState.update { it.copy(isClearing = true, confirming = null) }
        viewModelScope.launch {
            repository.clear(kind)
            plane.clearSessionCaches()
            val stats = runCatching { repository.loadStats() }.getOrDefault(emptyList())
            _uiState.update {
                it.copy(
                    isClearing = false,
                    entries = stats.toEntries(),
                    message = "已清理「${kind.label}」",
                )
            }
        }
    }

    fun confirmClearAll() {
        if (!_uiState.value.confirmingAll) return
        _uiState.update { it.copy(isClearing = true, confirmingAll = false) }
        viewModelScope.launch {
            repository.clearAll()
            plane.clearSessionCaches()
            val stats = runCatching { repository.loadStats() }.getOrDefault(emptyList())
            _uiState.update {
                it.copy(
                    isClearing = false,
                    entries = stats.toEntries(),
                    message = "已清理全部缓存",
                )
            }
        }
    }

    fun consumeMessage() {
        _uiState.update { it.copy(message = null) }
    }

    private fun refresh() {
        _uiState.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            val stats = runCatching { repository.loadStats() }.getOrDefault(emptyList())
            _uiState.update { it.copy(isLoading = false, entries = stats.toEntries()) }
        }
    }

    private fun List<CacheStats>.toEntries(): List<CacheEntry> =
        map { CacheEntry(kind = it.kind, entries = it.entries, permanent = it.kind.permanent) }
}
