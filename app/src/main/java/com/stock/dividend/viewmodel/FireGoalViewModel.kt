package com.stock.dividend.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stock.dividend.data.local.entity.FireGoalEntity
import com.stock.dividend.data.repository.FireGoalRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FireGoalUiState(
    val amountInput: String = "",
    val existingGoal: FireGoalEntity? = null,
    val error: String? = null,
    val isSaving: Boolean = false,
    val showDeleteDialog: Boolean = false,
    val saved: Boolean = false,
    val deleted: Boolean = false
)

@HiltViewModel
class FireGoalViewModel @Inject constructor(
    private val fireGoalRepository: FireGoalRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(FireGoalUiState())
    val uiState: StateFlow<FireGoalUiState> = _uiState.asStateFlow()

    init {
        loadExistingGoal()
    }

    private fun loadExistingGoal() {
        viewModelScope.launch {
            val goal = fireGoalRepository.getGoalOnce()
            if (goal != null) {
                _uiState.value = _uiState.value.copy(
                    existingGoal = goal,
                    amountInput = formatAmountForInput(goal.targetAmount)
                )
            }
        }
    }

    fun onAmountChanged(input: String) {
        _uiState.value = _uiState.value.copy(
            amountInput = input,
            error = null
        )
    }

    fun saveGoal() {
        val input = _uiState.value.amountInput.trim()
        val amount = input.toDoubleOrNull()

        when {
            input.isEmpty() -> {
                _uiState.value = _uiState.value.copy(error = "请输入目标金额")
            }
            amount == null -> {
                _uiState.value = _uiState.value.copy(error = "请输入有效的数字")
            }
            amount <= 0 -> {
                _uiState.value = _uiState.value.copy(error = "目标金额必须大于零")
            }
            amount > 999_999_999_999 -> {
                _uiState.value = _uiState.value.copy(error = "金额超出有效范围")
            }
            else -> {
                _uiState.value = _uiState.value.copy(isSaving = true)
                viewModelScope.launch {
                    fireGoalRepository.saveGoal(amount)
                    _uiState.value = _uiState.value.copy(isSaving = false, saved = true)
                }
            }
        }
    }

    fun showDeleteDialog() {
        _uiState.value = _uiState.value.copy(showDeleteDialog = true)
    }

    fun dismissDeleteDialog() {
        _uiState.value = _uiState.value.copy(showDeleteDialog = false)
    }

    fun deleteGoal() {
        viewModelScope.launch {
            fireGoalRepository.deleteGoal()
            _uiState.value = _uiState.value.copy(showDeleteDialog = false, deleted = true)
        }
    }

    private fun formatAmountForInput(amount: Double): String {
        return if (amount == amount.toLong().toDouble()) {
            amount.toLong().toString()
        } else {
            "%.2f".format(amount)
        }
    }
}
