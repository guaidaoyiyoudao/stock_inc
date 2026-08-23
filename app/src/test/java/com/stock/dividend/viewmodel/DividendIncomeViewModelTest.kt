package com.stock.dividend.viewmodel

import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.local.entity.DividendIncomeRecordEntity
import com.stock.dividend.data.local.entity.StockEntity
import com.stock.dividend.data.repository.DividendIncomeRepository
import com.stock.dividend.data.plane.MarketDataPlane
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
class DividendIncomeViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private val incomeRepository: DividendIncomeRepository = mockk(relaxed = true)
    private val plane: MarketDataPlane = mockk(relaxed = true)

    private val recordsFlow = MutableStateFlow<List<DividendIncomeRecordEntity>>(emptyList())
    private val yearsFlow = MutableStateFlow<List<Int>>(emptyList())
    private val totalFlow = MutableStateFlow(0.0)
    private val stocksFlow = MutableStateFlow<List<StockEntity>>(emptyList())

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { incomeRepository.observeByYear(any()) } returns recordsFlow
        every { incomeRepository.observeAvailableYears() } returns yearsFlow
        every { incomeRepository.observeTotalByYear(any()) } returns totalFlow
        every { plane.observeAllStocks() } returns stocksFlow
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `init triggers auto-regeneration`() = runTest {
        val viewModel = DividendIncomeViewModel(incomeRepository, plane)
        advanceUntilIdle()

        coVerify { incomeRepository.regenerateAutoRecords() }
    }

    @Test
    fun `selectYear updates selected year`() = runTest {
        val viewModel = DividendIncomeViewModel(incomeRepository, plane)
        advanceUntilIdle()

        viewModel.selectYear(2024)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.selectedYear).isEqualTo(2024)
    }

    @Test
    fun `addManualRecord calls repository and clears dialog`() = runTest {
        coEvery { incomeRepository.addManualRecord(any(), any(), any(), any()) } returns Unit

        val viewModel = DividendIncomeViewModel(incomeRepository, plane)
        advanceUntilIdle()

        viewModel.showAddDialog()
        assertThat(viewModel.uiState.value.showAddDialog).isTrue()

        viewModel.addManualRecord("2025-03-15", 500.0, "sh.600000", "test")
        advanceUntilIdle()

        coVerify { incomeRepository.addManualRecord("2025-03-15", 500.0, "sh.600000", "test") }
        assertThat(viewModel.uiState.value.showAddDialog).isFalse()
    }

    @Test
    fun `correctRecord calls repository and clears dialog`() = runTest {
        coEvery { incomeRepository.correctRecord(any(), any(), any()) } returns Unit

        val viewModel = DividendIncomeViewModel(incomeRepository, plane)
        advanceUntilIdle()

        viewModel.showCorrectDialog("auto_sh.600000_2024-07-10", 246.0)
        assertThat(viewModel.uiState.value.showCorrectDialog).isTrue()

        viewModel.correctRecord("auto_sh.600000_2024-07-10", 300.0, "adjusted")
        advanceUntilIdle()

        coVerify { incomeRepository.correctRecord("auto_sh.600000_2024-07-10", 300.0, "adjusted") }
        assertThat(viewModel.uiState.value.showCorrectDialog).isFalse()
    }

    @Test
    fun `deleteManualRecord calls repository`() = runTest {
        coEvery { incomeRepository.deleteManualRecord(any()) } returns Unit

        val viewModel = DividendIncomeViewModel(incomeRepository, plane)
        advanceUntilIdle()

        viewModel.deleteManualRecord("manual_12345")
        advanceUntilIdle()

        coVerify { incomeRepository.deleteManualRecord("manual_12345") }
    }
}
