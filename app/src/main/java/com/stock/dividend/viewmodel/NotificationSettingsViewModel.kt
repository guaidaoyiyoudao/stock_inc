package com.stock.dividend.viewmodel

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stock.dividend.data.repository.DividendThresholds
import com.stock.dividend.data.repository.NotificationRuleRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@Stable
data class NotificationSettingsUiState(
    val enabled: Boolean = false,
    val thresholdInput: String = "5.0",
    val thresholdError: String? = null,
    val saved: Boolean = false,
    // ── 评估门槛 ──
    val evalMinInput: String = DividendThresholds.DEFAULT_MIN_YIELD.toString(),
    val evalBoostInput: String = DividendThresholds.DEFAULT_BOOST_YIELD.toString(),
    val evalError: String? = null,
    val evalSaved: Boolean = false
)

@HiltViewModel
class NotificationSettingsViewModel @Inject constructor(
    private val repository: NotificationRuleRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(NotificationSettingsUiState())
    val uiState: StateFlow<NotificationSettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeGlobalDividendYieldRule().collect { rule ->
                _uiState.value = _uiState.value.copy(
                    enabled = rule?.enabled ?: false,
                    thresholdInput = rule?.thresholdPercent?.toString() ?: "5.0",
                    thresholdError = null
                )
            }
        }
        viewModelScope.launch {
            repository.observeEvalThresholds().collect { thresholds ->
                _uiState.value = _uiState.value.copy(
                    evalMinInput = thresholds.minYieldPercent.toString(),
                    evalBoostInput = thresholds.boostYieldPercent.toString(),
                    evalError = null
                )
            }
        }
    }

    fun updateEnabled(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(enabled = enabled, saved = false)
    }

    fun updateThreshold(value: String) {
        _uiState.value = _uiState.value.copy(
            thresholdInput = value,
            thresholdError = null,
            saved = false
        )
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
                stockCode = null,
                enabled = state.enabled,
                thresholdPercent = threshold
            )
            _uiState.value = _uiState.value.copy(saved = true)
        }
    }

    fun updateEvalMin(value: String) {
        _uiState.value = _uiState.value.copy(evalMinInput = value, evalError = null, evalSaved = false)
    }

    fun updateEvalBoost(value: String) {
        _uiState.value = _uiState.value.copy(evalBoostInput = value, evalError = null, evalSaved = false)
    }

    fun saveEvalThresholds() {
        val state = _uiState.value
        val min = state.evalMinInput.toDoubleOrNull()
        val boost = state.evalBoostInput.toDoubleOrNull()
        if (min == null || min <= 0.0) {
            _uiState.value = state.copy(
                evalError = "最低股息率需为大于 0 的数字", evalSaved = false
            )
            return
        }
        if (boost == null || boost < min) {
            _uiState.value = state.copy(
                evalError = "加分股息率需 ≥ 最低股息率", evalSaved = false
            )
            return
        }
        viewModelScope.launch {
            repository.saveEvalThresholds(
                minYieldPercent = min,
                boostYieldPercent = boost
            )
            _uiState.value = _uiState.value.copy(evalSaved = true, evalError = null)
        }
    }
}
