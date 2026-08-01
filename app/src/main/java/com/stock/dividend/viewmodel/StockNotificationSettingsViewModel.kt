package com.stock.dividend.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stock.dividend.data.local.entity.NOTIFICATION_RULE_TYPE_BOLL_WEEKLY_UPPER
import com.stock.dividend.data.local.entity.NOTIFICATION_RULE_TYPE_DIVIDEND_YIELD_BELOW_THRESHOLD
import com.stock.dividend.data.local.entity.NOTIFICATION_RULE_TYPE_DIVIDEND_YIELD_THRESHOLD
import com.stock.dividend.data.local.entity.NOTIFICATION_RULE_TYPE_PRICE_ABOVE
import com.stock.dividend.data.local.entity.NOTIFICATION_RULE_TYPE_PRICE_BELOW
import com.stock.dividend.data.local.entity.NotificationRuleEntity
import com.stock.dividend.data.repository.NotificationRuleRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

val stockCustomNotificationRuleTypes = listOf(
    NOTIFICATION_RULE_TYPE_PRICE_ABOVE,
    NOTIFICATION_RULE_TYPE_PRICE_BELOW,
    NOTIFICATION_RULE_TYPE_DIVIDEND_YIELD_THRESHOLD,
    NOTIFICATION_RULE_TYPE_DIVIDEND_YIELD_BELOW_THRESHOLD,
    NOTIFICATION_RULE_TYPE_BOLL_WEEKLY_UPPER
)

data class StockNotificationRuleSettingUiState(
    val type: String,
    val title: String,
    val description: String,
    val thresholdLabel: String,
    val thresholdInput: String,
    val enabled: Boolean = false,
    val thresholdError: String? = null
)

data class StockCustomNotificationSettingsUiState(
    val rules: List<StockNotificationRuleSettingUiState> = defaultStockRuleSettings(),
    val saved: Boolean = false
)

@HiltViewModel
class StockNotificationSettingsViewModel @Inject constructor(
    private val repository: NotificationRuleRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {
    private val stockCode: String = checkNotNull(savedStateHandle["code"])
    private val _uiState = MutableStateFlow(StockCustomNotificationSettingsUiState())
    val uiState: StateFlow<StockCustomNotificationSettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeStockRules(stockCode).collect { rules ->
                _uiState.value = _uiState.value.copy(
                    rules = mergeRules(rules),
                    saved = false
                )
            }
        }
    }

    fun updateEnabled(type: String, enabled: Boolean) {
        updateRule(type) { it.copy(enabled = enabled) }
    }

    fun updateThreshold(type: String, value: String) {
        updateRule(type) { it.copy(thresholdInput = value, thresholdError = null) }
    }

    fun save() {
        val state = _uiState.value
        val validatedRules = state.rules.map { rule ->
            // BOLL 上轨规则阈值由系统按周线布林带自动计算，无需用户输入，跳过阈值校验
            if (rule.type == NOTIFICATION_RULE_TYPE_BOLL_WEEKLY_UPPER) {
                rule.copy(thresholdError = null)
            } else {
                val threshold = rule.thresholdInput.toDoubleOrNull()
                if (rule.enabled && (threshold == null || threshold <= 0.0)) {
                    rule.copy(thresholdError = "请输入大于 0 的阈值")
                } else {
                    rule.copy(thresholdError = null)
                }
            }
        }
        if (validatedRules.any { it.thresholdError != null }) {
            _uiState.value = state.copy(rules = validatedRules, saved = false)
            return
        }

        viewModelScope.launch {
            validatedRules.forEach { rule ->
                repository.saveRule(
                    type = rule.type,
                    stockCode = stockCode,
                    enabled = rule.enabled,
                    thresholdValue = rule.thresholdInput.toDoubleOrNull() ?: defaultThresholdFor(rule.type)
                )
            }
            _uiState.value = _uiState.value.copy(saved = true)
        }
    }

    private fun mergeRules(savedRules: List<NotificationRuleEntity>): List<StockNotificationRuleSettingUiState> {
        val savedByType = savedRules.associateBy { it.type }
        return defaultStockRuleSettings().map { default ->
            val saved = savedByType[default.type] ?: return@map default
            default.copy(
                enabled = saved.enabled,
                thresholdInput = saved.thresholdPercent.toString(),
                thresholdError = null
            )
        }
    }

    private fun updateRule(
        type: String,
        transform: (StockNotificationRuleSettingUiState) -> StockNotificationRuleSettingUiState
    ) {
        _uiState.value = _uiState.value.copy(
            rules = _uiState.value.rules.map { rule ->
                if (rule.type == type) transform(rule) else rule
            },
            saved = false
        )
    }
}

fun defaultStockRuleSettings(): List<StockNotificationRuleSettingUiState> = listOf(
    StockNotificationRuleSettingUiState(
        type = NOTIFICATION_RULE_TYPE_PRICE_ABOVE,
        title = "股价高于目标价",
        description = "当前价格从低位上穿目标价时通知",
        thresholdLabel = "目标价",
        thresholdInput = "10.0"
    ),
    StockNotificationRuleSettingUiState(
        type = NOTIFICATION_RULE_TYPE_PRICE_BELOW,
        title = "股价低于目标价",
        description = "当前价格从高位跌破目标价时通知",
        thresholdLabel = "目标价",
        thresholdInput = "10.0"
    ),
    StockNotificationRuleSettingUiState(
        type = NOTIFICATION_RULE_TYPE_DIVIDEND_YIELD_THRESHOLD,
        title = "股息率高于目标",
        description = "股息率从低位上穿目标百分比时通知",
        thresholdLabel = "目标股息率 (%)",
        thresholdInput = "5.0"
    ),
    StockNotificationRuleSettingUiState(
        type = NOTIFICATION_RULE_TYPE_DIVIDEND_YIELD_BELOW_THRESHOLD,
        title = "股息率低于目标",
        description = "股息率从高位跌破目标百分比时通知",
        thresholdLabel = "目标股息率 (%)",
        thresholdInput = "3.0"
    ),
    StockNotificationRuleSettingUiState(
        type = NOTIFICATION_RULE_TYPE_BOLL_WEEKLY_UPPER,
        title = "股价触及周BOLL上轨",
        description = "当前价格上穿周线布林带上轨时通知，阈值按周线自动计算",
        thresholdLabel = "",
        thresholdInput = "0.0"
    )
)

private fun defaultThresholdFor(type: String): Double =
    defaultStockRuleSettings().first { it.type == type }.thresholdInput.toDouble()
