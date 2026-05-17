package com.stock.dividend.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stock.dividend.data.repository.NotificationRuleRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class StockNotificationSettingsViewModel @Inject constructor(
    private val repository: NotificationRuleRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {
    private val stockCode: String = checkNotNull(savedStateHandle["code"])
    private val _uiState = MutableStateFlow(NotificationSettingsUiState())
    val uiState: StateFlow<NotificationSettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeStockDividendYieldRule(stockCode).collect { rule ->
                _uiState.value = _uiState.value.copy(
                    enabled = rule?.enabled ?: false,
                    thresholdInput = rule?.thresholdPercent?.toString() ?: "5.0",
                    thresholdError = null
                )
            }
        }
    }

    fun updateEnabled(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(enabled = enabled, saved = false)
    }

    fun updateThreshold(value: String) {
        _uiState.value = _uiState.value.copy(thresholdInput = value, thresholdError = null, saved = false)
    }

    fun save() {
        val state = _uiState.value
        val threshold = state.thresholdInput.toDoubleOrNull()
        if (threshold == null || threshold <= 0.0) {
            _uiState.value = state.copy(thresholdError = "请输入大于 0 的阈值", saved = false)
            return
        }
        viewModelScope.launch {
            repository.saveDividendYieldRule(
                stockCode = stockCode,
                enabled = state.enabled,
                thresholdPercent = threshold
            )
            _uiState.value = _uiState.value.copy(saved = true)
        }
    }
}
