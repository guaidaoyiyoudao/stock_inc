package com.stock.dividend.viewmodel

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stock.dividend.data.local.dao.StockYearlyIncome
import com.stock.dividend.data.local.dao.YearlyTotal
import com.stock.dividend.data.local.entity.AchievementEntity
import com.stock.dividend.data.local.entity.FireGoalEntity
import com.stock.dividend.data.local.entity.StockEntity
import com.stock.dividend.data.repository.AchievementRepository
import com.stock.dividend.data.repository.DividendIncomeRepository
import com.stock.dividend.data.repository.FireGoalRepository
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
    val isLoading: Boolean = true,
    /** 本次会话内新解锁的成就 id（一次性庆祝事件语义；首次加载不触发）。 */
    val newlyUnlockedIds: List<String> = emptyList()
)

@HiltViewModel
class AchievementViewModel @Inject constructor(
    private val achievementRepository: AchievementRepository,
    stockRepository: StockRepository,
    incomeRepository: DividendIncomeRepository,
    private val fireGoalRepository: FireGoalRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(AchievementUiState())
    val uiState: StateFlow<AchievementUiState> = _uiState.asStateFlow()

    /** 上一帧已解锁 id 集合；null = 尚未完成首次加载（首次不庆祝历史成就）。 */
    private var previousUnlockedIds: Set<String>? = null

    private val stocksFlow = stockRepository.observeAllStocks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            combine(
                stocksFlow,
                incomeRepository.observeYearlyTotals(),
                achievementRepository.observeAll(),
                incomeRepository.observeRecordCount(),
                incomeRepository.observeMaxSingleIncome(),
                incomeRepository.observePerStockYearlyIncome(),
                fireGoalRepository.observeGoal(),
                incomeRepository.observeForecastTotal()
            ) { args ->
                @Suppress("UNCHECKED_CAST")
                val stocks = args[0] as List<StockEntity>
                val yearlyTotals = args[1] as List<YearlyTotal>
                val unlockedEntities = args[2] as List<AchievementEntity>
                val recordCount = args[3] as Int
                val maxSingle = args[4] as Double
                val perStockIncome = args[5] as List<StockYearlyIncome>
                val fireGoal = args[6] as FireGoalEntity?
                val forecastTotal = args[7] as Double

                val hasIncome = yearlyTotals.isNotEmpty()
                val ctx = AchievementChecker.CheckContext(
                    stocks = stocks,
                    yearlyTotals = yearlyTotals.associate { it.year to it.total },
                    hasAnyIncomeRecord = hasIncome,
                    incomeRecordCount = recordCount,
                    maxSingleIncome = maxSingle,
                    perStockYearlyIncome = perStockIncome
                        .groupBy { it.stockCode }
                        .mapValues { (_, items) -> items.associate { it.year to it.total } },
                    fireGoal = fireGoal,
                    forecastTotal = forecastTotal
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
                val unlockedIds = items.filter { it.unlocked }.map { it.def.id }.toSet()
                val newlyUnlocked = previousUnlockedIds
                    ?.let { prev -> (unlockedIds - prev).toList() }
                    ?: emptyList()
                previousUnlockedIds = unlockedIds
                _uiState.value = AchievementUiState(
                    achievements = items,
                    unlockedCount = items.count { it.unlocked },
                    totalCount = items.size,
                    isLoading = false,
                    newlyUnlockedIds = newlyUnlocked
                )
            }.collect {}
        }
    }
}
