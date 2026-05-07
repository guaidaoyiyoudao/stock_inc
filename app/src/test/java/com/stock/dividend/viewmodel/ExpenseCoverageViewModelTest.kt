package com.stock.dividend.viewmodel

import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.local.entity.EXPENSE_PERIOD_MONTHLY
import com.stock.dividend.data.local.entity.EXPENSE_PERIOD_YEARLY
import com.stock.dividend.data.local.entity.LivingExpenseItemEntity
import com.stock.dividend.data.repository.DividendIncomeRepository
import com.stock.dividend.data.repository.LivingExpenseRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ExpenseCoverageViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val dividendIncomeRepository: DividendIncomeRepository = mockk(relaxed = true)
    private val livingExpenseRepository: LivingExpenseRepository = mockk(relaxed = true)
    private val forecastFlow = MutableStateFlow(0.0)
    private val expensesFlow = MutableStateFlow<List<LivingExpenseItemEntity>>(emptyList())

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { dividendIncomeRepository.observeForecastTotal() } returns forecastFlow
        every { livingExpenseRepository.observeExpenses() } returns expensesFlow
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `uiState uses forecast income to cover living expenses`() = runTest {
        forecastFlow.value = 45_000.0
        expensesFlow.value = listOf(
            LivingExpenseItemEntity(1, "房租", 3000.0, EXPENSE_PERIOD_MONTHLY, 0),
            LivingExpenseItemEntity(2, "餐饮", 18_000.0, EXPENSE_PERIOD_YEARLY, 1)
        )

        val viewModel = ExpenseCoverageViewModel(dividendIncomeRepository, livingExpenseRepository)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.forecastAnnualDividendIncome).isEqualTo(45_000.0)
        assertThat(state.totalAnnualExpense).isEqualTo(54_000.0)
        assertThat(state.coverageRatio).isWithin(0.0001).of(45_000.0 / 54_000.0)
        assertThat(state.coveredItemCount).isEqualTo(1)
        assertThat(state.currentCoveringItemName).isEqualTo("餐饮")
        assertThat(state.rows).hasSize(2)
        assertThat(state.rows[0].status).isEqualTo(CoverageStatus.COVERED)
        assertThat(state.rows[1].status).isEqualTo(CoverageStatus.PARTIAL)
    }

    @Test
    fun `saveExpense validates blank name`() = runTest {
        val viewModel = ExpenseCoverageViewModel(dividendIncomeRepository, livingExpenseRepository)

        viewModel.onExpenseNameChanged(" ")
        viewModel.onExpenseAmountChanged("100")
        viewModel.saveExpense()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.dialogError).isEqualTo("请输入支出名称")
    }

    @Test
    fun `saveExpense validates invalid amount`() = runTest {
        val viewModel = ExpenseCoverageViewModel(dividendIncomeRepository, livingExpenseRepository)

        viewModel.onExpenseNameChanged("房租")
        viewModel.onExpenseAmountChanged("abc")
        viewModel.saveExpense()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.dialogError).isEqualTo("请输入有效金额")
    }

    @Test
    fun `saveExpense adds new expense and closes dialog`() = runTest {
        coEvery { livingExpenseRepository.addExpense(any(), any(), any()) } returns 1L
        val viewModel = ExpenseCoverageViewModel(dividendIncomeRepository, livingExpenseRepository)

        viewModel.showAddDialog()
        viewModel.onExpenseNameChanged("房租")
        viewModel.onExpenseAmountChanged("3000")
        viewModel.onExpensePeriodChanged(ExpensePeriod.MONTHLY)
        viewModel.saveExpense()
        advanceUntilIdle()

        coVerify { livingExpenseRepository.addExpense("房租", 3000.0, EXPENSE_PERIOD_MONTHLY) }
        assertThat(viewModel.uiState.value.showExpenseDialog).isFalse()
    }

    @Test
    fun `move and delete actions delegate to repository`() = runTest {
        val viewModel = ExpenseCoverageViewModel(dividendIncomeRepository, livingExpenseRepository)

        viewModel.moveExpenseUp(2)
        viewModel.moveExpenseDown(1)
        viewModel.deleteExpense(3)
        advanceUntilIdle()

        coVerify { livingExpenseRepository.moveUp(2) }
        coVerify { livingExpenseRepository.moveDown(1) }
        coVerify { livingExpenseRepository.deleteExpense(3) }
    }
}
