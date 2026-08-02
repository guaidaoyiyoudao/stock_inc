package com.stock.dividend.viewmodel

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stock.dividend.data.repository.AiAgentConfig
import com.stock.dividend.data.repository.AiAgentConfigRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@Stable
data class AiSettingsUiState(
    /** 自定义系统提示词草稿（多行）。 */
    val systemPromptInput: String = "",
    /** temperature 草稿（字符串，空表示用模型默认）。 */
    val temperatureInput: String = "",
    /** 最大输出长度草稿（字符串，空表示用模型默认）。 */
    val maxTokensInput: String = "",
    val error: String? = null,
    val saved: Boolean = false,
)

@HiltViewModel
class AiSettingsViewModel @Inject constructor(
    private val repository: AiAgentConfigRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AiSettingsUiState())
    val uiState: StateFlow<AiSettingsUiState> = _uiState.asStateFlow()

    /** 已保存的配置（驱动草稿初始化；映射自 SharedPreferences）。 */
    val configState: StateFlow<AiAgentConfig> =
        repository.observe().stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5000), AiAgentConfig()
        )

    init {
        // 配置变化（如保存后）回填草稿，保证 UI 与持久化值一致
        viewModelScope.launch {
            configState.collect { config ->
                _uiState.update {
                    it.copy(
                        systemPromptInput = config.systemPrompt,
                        temperatureInput = config.temperature?.toString() ?: "",
                        maxTokensInput = config.maxTokens?.toString() ?: "",
                    )
                }
            }
        }
    }

    fun onSystemPromptChanged(value: String) {
        _uiState.update { it.copy(systemPromptInput = value, saved = false) }
    }

    fun onTemperatureChanged(value: String) {
        // 只允许数字与小数点，避免乱码
        val filtered = value.filter { it.isDigit() || it == '.' }
        _uiState.update { it.copy(temperatureInput = filtered, saved = false) }
    }

    fun onMaxTokensChanged(value: String) {
        val filtered = value.filter { it.isDigit() }
        _uiState.update { it.copy(maxTokensInput = filtered, saved = false) }
    }

    /** 清空自定义提示词，恢复默认（留空即用 BASE_INSTRUCTION）。 */
    fun restoreDefaultPrompt() {
        _uiState.update { it.copy(systemPromptInput = "", saved = false) }
    }

    fun save() {
        val state = _uiState.value
        // 空串表示「用模型默认」（→ null）；非空才校验范围
        val temperature: Float? = if (state.temperatureInput.isBlank()) null
            else parseTemperature(state.temperatureInput).also {
                if (it == INVALID_TEMP) {
                    _uiState.update { it.copy(error = "温度需在 0~2 之间", saved = false) }
                    return
                }
            }
        val maxTokens: Int? = if (state.maxTokensInput.isBlank()) null
            else parseMaxTokens(state.maxTokensInput).also {
                if (it == INVALID_MAX_TOKENS) {
                    _uiState.update { it.copy(error = "最大输出长度需为大于 0 的整数", saved = false) }
                    return
                }
            }
        viewModelScope.launch {
            repository.saveConfig(
                AiAgentConfig(
                    systemPrompt = state.systemPromptInput,
                    temperature = temperature,
                    maxTokens = maxTokens,
                )
            )
            _uiState.update { it.copy(saved = true, error = null) }
        }
    }

    companion object {
        const val INVALID_TEMP = -1f
        const val INVALID_MAX_TOKENS = -1

        /**
         * 0~2→该值；空串/越界/非数字→[INVALID_TEMP]。空串的 null 语义由调用方 [save] 处理。
         * 注意：0 是合法温度值，故用哨兵 [INVALID_TEMP]（负数）单独表示非法，不与 0 混淆。
         */
        internal fun parseTemperature(input: String): Float {
            val v = input.trim().toFloatOrNull() ?: return INVALID_TEMP
            return if (v in 0f..2f) v else INVALID_TEMP
        }

        /**
         * >0→该值；空串/非正/非数字→[INVALID_MAX_TOKENS]。空串的 null 语义由调用方 [save] 处理。
         * 注意：用哨兵 [INVALID_MAX_TOKENS]（负数）单独表示非法。
         */
        internal fun parseMaxTokens(input: String): Int {
            val v = input.trim().toIntOrNull() ?: return INVALID_MAX_TOKENS
            return if (v > 0) v else INVALID_MAX_TOKENS
        }
    }
}
