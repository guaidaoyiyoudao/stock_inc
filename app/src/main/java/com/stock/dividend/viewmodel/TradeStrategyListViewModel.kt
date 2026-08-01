package com.stock.dividend.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stock.dividend.data.local.dao.TradeStrategyDao
import com.stock.dividend.data.local.entity.STRATEGY_STATUS_ARCHIVED
import com.stock.dividend.data.local.entity.TradeStrategyEntity
import com.stock.dividend.data.repository.risksFromJson
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class StrategyListItem(
    val id: String,
    val targetText: String,
    val direction: String,
    val reasoning: String,
    val risks: List<String>,
    val validUntil: String?,
    val sourceNote: String?,
    val createdAt: Long
)

data class TradeStrategyListUiState(val items: List<StrategyListItem> = emptyList())

@HiltViewModel
class TradeStrategyListViewModel @Inject constructor(
    private val strategyDao: TradeStrategyDao
) : ViewModel() {

    val uiState: StateFlow<TradeStrategyListUiState> =
        strategyDao.observeAll().map { list -> TradeStrategyListUiState(list.map { it.toItem() }) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TradeStrategyListUiState())

    fun archive(id: String) {
        viewModelScope.launch { strategyDao.updateStatus(id, STRATEGY_STATUS_ARCHIVED) }
    }

    fun delete(id: String) {
        viewModelScope.launch { strategyDao.delete(id) }
    }

    private fun TradeStrategyEntity.toItem() = StrategyListItem(
        id, targetText, direction, reasoning, risksFromJson(risks), validUntil, sourceNote, createdAt
    )
}
