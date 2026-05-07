package com.stock.dividend.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stock.dividend.data.repository.DividendIncomeRepository
import com.stock.dividend.data.repository.FireGoalRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import javax.inject.Inject


data class ExpenseCoverageUiState(
    val year: Int = LocalDate.now().year,
    val annualDividendIncome: Double = 0.0,
    val annualExpenseGoal: Double? = null,
    val coverageRatio: Double = 0.0,
    val difference: Double = 0.0,
    val isGoalReached: Boolean = false,
    val shortfallAmount: Double = 0.0,
    val hasGoal: Boolean = false
)

@HiltViewModel
class ExpenseCoverageViewModel @Inject constructor(
    dividendIncomeRepository: DividendIncomeRepository,
    fireGoalRepository: FireGoalRepository
) : ViewModel() {

    private val currentYear = LocalDate.now().year

    val uiState: StateFlow<ExpenseCoverageUiState> = combine(
        dividendIncomeRepository.observeTotalByYear(currentYear),
        fireGoalRepository.observeGoal()
    ) { income, goal ->
        val goalAmount = goal?.targetAmount
        val targetAmount = goalAmount ?: 0.0
        val hasGoal = targetAmount > 0
        val ratio = if (hasGoal) income / targetAmount else 0.0
        val diff = if (hasGoal) income - targetAmount else income
        val reached = hasGoal && ratio >= 1.0
        ExpenseCoverageUiState(
            year = currentYear,
            annualDividendIncome = income,
            annualExpenseGoal = goalAmount,
            coverageRatio = ratio,
            difference = diff,
            isGoalReached = reached,
            shortfallAmount = if (hasGoal && diff < 0) -diff else 0.0,
            hasGoal = hasGoal
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ExpenseCoverageUiState())
}
