package com.stock.dividend.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stock.dividend.data.local.entity.EXPENSE_PERIOD_MONTHLY
import com.stock.dividend.data.local.entity.EXPENSE_PERIOD_YEARLY
import com.stock.dividend.data.local.entity.LivingExpenseItemEntity
import com.stock.dividend.data.repository.DividendIncomeRepository
import com.stock.dividend.data.repository.LivingExpenseRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ExpenseCoverageUiState(
    val forecastAnnualDividendIncome: Double = 0.0,
    val totalAnnualExpense: Double = 0.0,
    val coverageRatio: Double = 0.0,
    val coveredItemCount: Int = 0,
    val currentCoveringItemName: String? = null,
    val remainingSurplus: Double = 0.0,
    val rows: List<ExpenseCoverageRow> = emptyList(),
    val showExpenseDialog: Boolean = false,
    val editingExpenseId: Long? = null,
    val expenseNameInput: String = "",
    val expenseAmountInput: String = "",
    val expensePeriodInput: ExpensePeriod = ExpensePeriod.MONTHLY,
    val dialogError: String? = null,
    val annualDividendIncome: Double = forecastAnnualDividendIncome,
    val annualExpenseGoal: Double? = totalAnnualExpense.takeIf { it > 0.0 },
    val difference: Double = forecastAnnualDividendIncome - totalAnnualExpense,
    val isGoalReached: Boolean = totalAnnualExpense > 0.0 && coverageRatio >= 1.0,
    val shortfallAmount: Double = if (difference < 0.0) -difference else 0.0,
    val hasGoal: Boolean = totalAnnualExpense > 0.0
) {
    val hasExpenses: Boolean = rows.isNotEmpty()
}

@HiltViewModel
class ExpenseCoverageViewModel @Inject constructor(
    private val dividendIncomeRepository: DividendIncomeRepository,
    private val livingExpenseRepository: LivingExpenseRepository
) : ViewModel() {
    private val dialogState = MutableStateFlow(ExpenseCoverageUiState())

    val uiState: StateFlow<ExpenseCoverageUiState> = combine(
        dividendIncomeRepository.observeForecastTotal(),
        livingExpenseRepository.observeExpenses(),
        dialogState
    ) { forecastIncome, expenses, dialog ->
        val calculation = ExpenseCoverageCalculator.calculate(
            forecastAnnualDividendIncome = forecastIncome,
            items = expenses.map { it.toCoverageInput() }
        )
        dialog.copy(
            forecastAnnualDividendIncome = calculation.forecastAnnualDividendIncome,
            totalAnnualExpense = calculation.totalAnnualExpense,
            coverageRatio = calculation.coverageRatio,
            coveredItemCount = calculation.coveredItemCount,
            currentCoveringItemName = calculation.currentCoveringItemName,
            remainingSurplus = calculation.remainingSurplus,
            rows = calculation.rows,
            annualDividendIncome = calculation.forecastAnnualDividendIncome,
            annualExpenseGoal = calculation.totalAnnualExpense.takeIf { it > 0.0 },
            difference = calculation.forecastAnnualDividendIncome - calculation.totalAnnualExpense,
            isGoalReached = calculation.totalAnnualExpense > 0.0 && calculation.coverageRatio >= 1.0,
            shortfallAmount = (calculation.totalAnnualExpense - calculation.forecastAnnualDividendIncome)
                .coerceAtLeast(0.0),
            hasGoal = calculation.totalAnnualExpense > 0.0
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ExpenseCoverageUiState())

    fun showAddDialog() {
        dialogState.update {
            it.copy(
                showExpenseDialog = true,
                editingExpenseId = null,
                expenseNameInput = "",
                expenseAmountInput = "",
                expensePeriodInput = ExpensePeriod.MONTHLY,
                dialogError = null
            )
        }
    }

    fun showEditDialog(row: ExpenseCoverageRow) {
        dialogState.update {
            it.copy(
                showExpenseDialog = true,
                editingExpenseId = row.id,
                expenseNameInput = row.name,
                expenseAmountInput = formatAmountForInput(row.amount),
                expensePeriodInput = row.period,
                dialogError = null
            )
        }
    }

    fun dismissDialog() {
        dialogState.update { it.copy(showExpenseDialog = false, dialogError = null) }
    }

    fun onExpenseNameChanged(input: String) {
        dialogState.update { it.copy(expenseNameInput = input, dialogError = null) }
    }

    fun onExpenseAmountChanged(input: String) {
        dialogState.update { it.copy(expenseAmountInput = input, dialogError = null) }
    }

    fun onExpensePeriodChanged(period: ExpensePeriod) {
        dialogState.update { it.copy(expensePeriodInput = period, dialogError = null) }
    }

    fun saveExpense() {
        val state = dialogState.value
        val name = state.expenseNameInput.trim()
        val amount = state.expenseAmountInput.trim().toDoubleOrNull()
        when {
            name.isBlank() -> dialogState.update { it.copy(dialogError = "请输入支出名称") }
            amount == null -> dialogState.update { it.copy(dialogError = "请输入有效金额") }
            amount <= 0.0 -> dialogState.update { it.copy(dialogError = "支出金额必须大于零") }
            amount > 999_999_999_999.0 -> dialogState.update { it.copy(dialogError = "金额超出有效范围") }
            else -> viewModelScope.launch {
                val period = state.expensePeriodInput.toStorageValue()
                val editingId = state.editingExpenseId
                if (editingId == null) {
                    livingExpenseRepository.addExpense(name, amount, period)
                } else {
                    livingExpenseRepository.updateExpense(editingId, name, amount, period)
                }
                dialogState.update { it.copy(showExpenseDialog = false, dialogError = null) }
            }
        }
    }

    fun deleteExpense(id: Long) {
        viewModelScope.launch {
            livingExpenseRepository.deleteExpense(id)
        }
    }

    fun moveExpenseUp(id: Long) {
        viewModelScope.launch {
            livingExpenseRepository.moveUp(id)
        }
    }

    fun moveExpenseDown(id: Long) {
        viewModelScope.launch {
            livingExpenseRepository.moveDown(id)
        }
    }

    private fun LivingExpenseItemEntity.toCoverageInput(): CoverageExpenseInput =
        CoverageExpenseInput(
            id = id,
            name = name,
            amount = amount,
            period = period.toExpensePeriod(),
            sortOrder = sortOrder
        )

    private fun String.toExpensePeriod(): ExpensePeriod =
        if (this == EXPENSE_PERIOD_YEARLY) ExpensePeriod.YEARLY else ExpensePeriod.MONTHLY

    private fun ExpensePeriod.toStorageValue(): String =
        when (this) {
            ExpensePeriod.MONTHLY -> EXPENSE_PERIOD_MONTHLY
            ExpensePeriod.YEARLY -> EXPENSE_PERIOD_YEARLY
        }

    private fun formatAmountForInput(amount: Double): String =
        if (amount == amount.toLong().toDouble()) {
            amount.toLong().toString()
        } else {
            String.format(Locale.US, "%.2f", amount)
        }
}
