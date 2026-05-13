package com.stock.dividend.viewmodel

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.local.entity.DividendEntity
import com.stock.dividend.data.local.entity.StockEntity
import com.stock.dividend.data.repository.DividendRepository
import com.stock.dividend.data.repository.StockRepository
import io.mockk.coEvery
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
class DividendValuationViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val stockRepository: StockRepository = mockk()
    private val dividendRepository: DividendRepository = mockk()
    private val stockFlow = MutableStateFlow<StockEntity?>(null)
    private val dividendsFlow = MutableStateFlow<List<DividendEntity>>(emptyList())

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { stockRepository.observeStock("sz.000001") } returns stockFlow
        every { dividendRepository.observeDividends("sz.000001") } returns dividendsFlow
        coEvery { stockRepository.fetchQuotes(any()) } returns mapOf("sz.000001" to 30.0)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = DividendValuationViewModel(
        savedStateHandle = SavedStateHandle(mapOf("code" to "sz.000001")),
        stockRepository = stockRepository,
        dividendRepository = dividendRepository
    )

    private fun dividend(year: Int, cash: Double) = DividendEntity(
        id = "sz.000001_$year",
        stockCode = "sz.000001",
        reportDate = "$year-12-31",
        cashPerShare = cash
    )

    @Test
    fun `defaults dividend basis from most recent five years`() = runTest {
        stockFlow.value = StockEntity("sz.000001", "平安银行", "0")
        dividendsFlow.value = listOf(
            dividend(2025, 6.0),
            dividend(2024, 5.0),
            dividend(2023, 4.0),
            dividend(2022, 3.0),
            dividend(2021, 2.0),
            dividend(2020, 100.0)
        )

        val viewModel = viewModel()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.dividendBasisInput).isEqualTo("4.00")
        assertThat(viewModel.uiState.value.dividendBasisYears).isEqualTo(5)
        assertThat(viewModel.uiState.value.currentPrice).isEqualTo(30.0)
        assertThat(viewModel.uiState.value.result).isNotNull()
    }

    @Test
    fun `uses fewer years when fewer than five dividend years exist`() = runTest {
        stockFlow.value = StockEntity("sz.000001", "平安银行", "0")
        dividendsFlow.value = listOf(dividend(2025, 6.0), dividend(2024, 4.0))

        val viewModel = viewModel()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.dividendBasisInput).isEqualTo("5.00")
        assertThat(viewModel.uiState.value.dividendBasisYears).isEqualTo(2)
    }

    @Test
    fun `allows manual dividend basis when no dividend records exist`() = runTest {
        stockFlow.value = StockEntity("sz.000001", "平安银行", "0")

        val viewModel = viewModel()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.hasDividendHistory).isFalse()
        viewModel.onDividendBasisChanged("3.25")
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.result?.intrinsicValuePerShare).isGreaterThan(0.0)
    }

    @Test
    fun `continues without market comparison when quote loading fails`() = runTest {
        coEvery { stockRepository.fetchQuotes(any()) } returns emptyMap()
        stockFlow.value = StockEntity("sz.000001", "平安银行", "0")
        dividendsFlow.value = listOf(dividend(2025, 6.0))

        val viewModel = viewModel()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.currentPrice).isNull()
        assertThat(viewModel.uiState.value.result?.discountOrPremiumPercent).isNull()
    }

    @Test
    fun `assumption changes recalculate result`() = runTest {
        stockFlow.value = StockEntity("sz.000001", "平安银行", "0")
        dividendsFlow.value = listOf(dividend(2025, 2.0))
        val viewModel = viewModel()
        advanceUntilIdle()

        val before = viewModel.uiState.value.result!!.intrinsicValuePerShare
        viewModel.onGrowthRateChanged("8")
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.result!!.intrinsicValuePerShare).isGreaterThan(before)
    }

    @Test
    fun `preset updates assumptions and recalculates result`() = runTest {
        stockFlow.value = StockEntity("sz.000001", "平安银行", "0")
        dividendsFlow.value = listOf(dividend(2025, 2.0))
        val viewModel = viewModel()
        advanceUntilIdle()

        val before = viewModel.uiState.value.result!!.intrinsicValuePerShare
        viewModel.applyPreset(DividendValuationPreset.OPTIMISTIC)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.growthRateInput).isEqualTo("8")
        assertThat(state.discountRateInput).isEqualTo("8")
        assertThat(state.terminalGrowthRateInput).isEqualTo("3")
        assertThat(state.marginOfSafetyInput).isEqualTo("15")
        assertThat(state.result!!.intrinsicValuePerShare).isGreaterThan(before)
    }
}
