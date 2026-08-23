package com.stock.dividend.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stock.dividend.data.repository.FuyaoConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 「数据 → 数据源」设置页：同花顺扶摇 API Key 的编辑与保存。 */
@HiltViewModel
class DataSourceSettingsViewModel @Inject constructor(
    private val fuyaoConfig: FuyaoConfig
) : ViewModel() {

    /** 当前保存的 key（空 = 未配置，同花顺源禁用、全走东财/腾讯）。 */
    val fuyaoApiKeyState: StateFlow<String> = fuyaoConfig.observeApiKey()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    fun saveFuyaoApiKey(apiKey: String) {
        viewModelScope.launch { fuyaoConfig.saveApiKey(apiKey) }
    }
}
