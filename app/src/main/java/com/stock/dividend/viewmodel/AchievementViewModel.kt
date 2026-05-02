package com.stock.dividend.viewmodel

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stock.dividend.data.local.entity.StockEntity
import com.stock.dividend.data.repository.AchievementRepository
import com.stock.dividend.data.repository.DividendIncomeRepository
import com.stock.dividend.data.repository.StockRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@Stable
data class AchievementItem(
    val def: AchievementDef,
    val unlocked: Boolean,
    val unlockedAt: Long? = null
)

@Stable
data class AchievementUiState(
    val achievements: List<AchievementItem> = emptyList(),
    val unlockedCount: Int = 0,
    val totalCount: Int = AchievementDef.entries.size,
    val isLoading: Boolean = true
)

@HiltViewModel
class AchievementViewModel @Inject constructor(
    private val achievementRepository: AchievementRepository,
    stockRepository: StockRepository,
    incomeRepository: DividendIncomeRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(AchievementUiState())
    val uiState: StateFlow<AchievementUiState> = _uiState.asStateFlow()

    private val stocksFlow = stockRepository.observeAllStocks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            combine(
                stocksFlow,
                incomeRepository.observeYearlyTotals(),
                achievementRepository.observeAll()
            ) { stocks, yearlyTotals, unlockedEntities ->
                val hasIncome = yearlyTotals.isNotEmpty()
                val ctx = AchievementChecker.CheckContext(
                    stocks = stocks,
                    yearlyTotals = yearlyTotals.associate { it.year to it.total },
                    hasAnyIncomeRecord = hasIncome
                )
                val qualified = AchievementChecker.check(ctx)

                // Sync new unlocks (fire-and-forget via launch)
                launch { achievementRepository.syncAchievements(qualified) }

                // Build UI items
                val unlockedMap = unlockedEntities.associateBy { it.id }
                val items = AchievementDef.entries.map { def ->
                    val entity = unlockedMap[def.id]
                    AchievementItem(
                        def = def,
                        unlocked = entity != null || def.id in qualified,
                        unlockedAt = entity?.unlockedAt
                    )
                }
                _uiState.value = AchievementUiState(
                    achievements = items,
                    unlockedCount = items.count { it.unlocked },
                    totalCount = items.size,
                    isLoading = false
                )
            }.collect {}
        }
    }
}
