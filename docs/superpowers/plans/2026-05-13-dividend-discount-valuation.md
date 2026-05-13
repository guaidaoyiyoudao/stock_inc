# Dividend Discount Valuation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a stock-level dividend discount valuation page opened from stock detail, with editable assumptions, valuation conclusion, and projected cash flow detail.

**Architecture:** Keep valuation math in a pure Kotlin calculator under `data/repository` so it is deterministic and unit-tested. Add a Hilt ViewModel that combines stock, dividends, and quote data, then expose formatted state to a new Compose screen. Wire the screen into the existing tab navigation from stock detail without changing bottom navigation.

**Tech Stack:** Kotlin 2.0.21, Java 17, Jetpack Compose Material 3, Hilt ViewModel, Coroutines Flow, MockK, Truth, JUnit.

---

## File Structure

- Create `app/src/main/java/com/stock/dividend/data/repository/DividendDiscountCalculator.kt`
  - Owns pure input models, output models, validation, 5-year dividend basis derivation, and DDM math.
- Create `app/src/test/java/com/stock/dividend/data/repository/DividendDiscountCalculatorTest.kt`
  - Unit tests for calculator and basis derivation.
- Create `app/src/main/java/com/stock/dividend/viewmodel/DividendValuationViewModel.kt`
  - Loads stock/dividends/quote, applies assumptions, exposes UI state, handles edits and presets.
- Create `app/src/test/java/com/stock/dividend/viewmodel/DividendValuationViewModelTest.kt`
  - ViewModel tests for defaults, no-dividend flow, quote failure, manual edits, and presets.
- Create `app/src/main/java/com/stock/dividend/ui/screen/DividendValuationScreen.kt`
  - Compose page for conclusion, assumptions, validation, and cash flow rows.
- Modify `app/src/main/java/com/stock/dividend/ui/screen/MainScaffold.kt`
  - Add `dividendValuation/{code}` route and pass navigation callback into stock detail.
- Modify `app/src/main/java/com/stock/dividend/ui/screen/StockDetailScreen.kt`
  - Add `onOpenDividendValuation` callback and visible entry action/card.

Existing dirty files must be preserved. Before editing `StockDetailScreen.kt`, inspect current contents and make the smallest additive change around existing user edits.

---

### Task 1: Pure Dividend Discount Calculator

**Files:**
- Create: `app/src/main/java/com/stock/dividend/data/repository/DividendDiscountCalculator.kt`
- Test: `app/src/test/java/com/stock/dividend/data/repository/DividendDiscountCalculatorTest.kt`

- [ ] **Step 1: Write the failing calculator tests**

Create `app/src/test/java/com/stock/dividend/data/repository/DividendDiscountCalculatorTest.kt`:

```kotlin
package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.local.entity.DividendEntity
import org.junit.Test

class DividendDiscountCalculatorTest {
    private fun dividend(reportDate: String, cashPerShare: Double) = DividendEntity(
        id = "sz.000001_$reportDate",
        stockCode = "sz.000001",
        reportDate = reportDate,
        cashPerShare = cashPerShare
    )

    @Test
    fun `calculate returns intrinsic value safety price and cash flow rows`() {
        val result = DividendDiscountCalculator.calculate(
            input = DividendDiscountInput(
                dividendBasisPerShare = 2.0,
                dividendGrowthRate = 0.05,
                discountRate = 0.09,
                terminalGrowthRate = 0.02,
                projectionYears = 3,
                marginOfSafety = 0.20,
                currentPrice = 40.0
            )
        )

        assertThat(result.validationError).isNull()
        assertThat(result.cashFlowRows.map { it.year }).containsExactly(1, 2, 3).inOrder()
        assertThat(result.cashFlowRows[0].projectedDividend).isWithin(0.001).of(2.10)
        assertThat(result.intrinsicValuePerShare).isWithin(0.01).of(35.07)
        assertThat(result.safetyBuyPrice).isWithin(0.01).of(28.06)
        assertThat(result.discountOrPremiumPercent).isWithin(0.0001).of(-0.1232)
        assertThat(result.valuationStatus).isEqualTo(DividendValuationStatus.OVERVALUED)
    }

    @Test
    fun `calculate marks undervalued when intrinsic value is above current price`() {
        val result = DividendDiscountCalculator.calculate(
            DividendDiscountInput(2.0, 0.05, 0.09, 0.02, 3, 0.20, 30.0)
        )

        assertThat(result.valuationStatus).isEqualTo(DividendValuationStatus.UNDERVALUED)
        assertThat(result.discountOrPremiumPercent).isGreaterThan(0.0)
    }

    @Test
    fun `calculate omits market comparison when current price is missing`() {
        val result = DividendDiscountCalculator.calculate(
            DividendDiscountInput(2.0, 0.05, 0.09, 0.02, 3, 0.20, null)
        )

        assertThat(result.valuationStatus).isEqualTo(DividendValuationStatus.NO_MARKET_PRICE)
        assertThat(result.discountOrPremiumPercent).isNull()
    }

    @Test
    fun `calculate rejects discount rate less than or equal to terminal growth rate`() {
        val result = DividendDiscountCalculator.calculate(
            DividendDiscountInput(2.0, 0.05, 0.02, 0.02, 10, 0.20, 30.0)
        )

        assertThat(result.validationError).isEqualTo("折现率必须大于终值增长率")
        assertThat(result.cashFlowRows).isEmpty()
    }

    @Test
    fun `calculate clamps projection years to one through thirty`() {
        val low = DividendDiscountCalculator.calculate(
            DividendDiscountInput(2.0, 0.05, 0.09, 0.02, 0, 0.20, null)
        )
        val high = DividendDiscountCalculator.calculate(
            DividendDiscountInput(2.0, 0.05, 0.09, 0.02, 45, 0.20, null)
        )

        assertThat(low.projectionYears).isEqualTo(1)
        assertThat(high.projectionYears).isEqualTo(30)
    }

    @Test
    fun `deriveDividendBasis averages most recent five dividend years`() {
        val result = DividendDiscountCalculator.deriveDividendBasis(
            listOf(
                dividend("2025-12-31", 6.0),
                dividend("2024-12-31", 5.0),
                dividend("2024-06-30", 1.0),
                dividend("2023-12-31", 4.0),
                dividend("2022-12-31", 3.0),
                dividend("2021-12-31", 2.0),
                dividend("2020-12-31", 100.0)
            )
        )

        assertThat(result).isNotNull()
        assertThat(result!!.averageCashPerShare).isWithin(0.001).of(4.2)
        assertThat(result.actualYears).isEqualTo(5)
    }

    @Test
    fun `deriveDividendBasis returns null when no positive dividends exist`() {
        val result = DividendDiscountCalculator.deriveDividendBasis(
            listOf(dividend("2025-12-31", 0.0))
        )

        assertThat(result).isNull()
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
./gradlew testDebugUnitTest --tests "com.stock.dividend.data.repository.DividendDiscountCalculatorTest"
```

Expected: FAIL because `DividendDiscountCalculator`, `DividendDiscountInput`, and related types do not exist.

- [ ] **Step 3: Write minimal calculator implementation**

Create `app/src/main/java/com/stock/dividend/data/repository/DividendDiscountCalculator.kt`:

```kotlin
package com.stock.dividend.data.repository

import com.stock.dividend.data.local.entity.DividendEntity
import kotlin.math.pow

data class DividendDiscountInput(
    val dividendBasisPerShare: Double,
    val dividendGrowthRate: Double,
    val discountRate: Double,
    val terminalGrowthRate: Double,
    val projectionYears: Int,
    val marginOfSafety: Double,
    val currentPrice: Double?
)

data class DividendBasisResult(
    val averageCashPerShare: Double,
    val actualYears: Int
)

data class DividendCashFlowRow(
    val year: Int,
    val projectedDividend: Double,
    val discountedDividend: Double
)

enum class DividendValuationStatus {
    UNDERVALUED,
    OVERVALUED,
    FAIR,
    NO_MARKET_PRICE,
    INVALID
}

data class DividendDiscountResult(
    val projectionYears: Int,
    val intrinsicValuePerShare: Double,
    val currentPrice: Double?,
    val discountOrPremiumPercent: Double?,
    val safetyBuyPrice: Double,
    val valuationStatus: DividendValuationStatus,
    val cashFlowRows: List<DividendCashFlowRow>,
    val terminalValue: Double,
    val discountedTerminalValue: Double,
    val validationError: String?
)

object DividendDiscountCalculator {
    fun deriveDividendBasis(dividends: List<DividendEntity>): DividendBasisResult? {
        val yearlyCash = dividends
            .filter { it.cashPerShare > 0.0 && it.reportDate.length >= 4 }
            .groupBy { it.reportDate.substring(0, 4) }
            .mapValues { (_, rows) -> rows.sumOf { it.cashPerShare } }
            .toList()
            .sortedByDescending { it.first }
            .take(5)

        if (yearlyCash.isEmpty()) return null

        return DividendBasisResult(
            averageCashPerShare = yearlyCash.sumOf { it.second } / yearlyCash.size,
            actualYears = yearlyCash.size
        )
    }

    fun calculate(input: DividendDiscountInput): DividendDiscountResult {
        val years = input.projectionYears.coerceIn(1, 30)
        val currentPrice = input.currentPrice?.takeIf { it > 0.0 }

        if (input.discountRate <= input.terminalGrowthRate) {
            return invalid(years, currentPrice, "折现率必须大于终值增长率")
        }
        if (input.dividendBasisPerShare < 0.0) {
            return invalid(years, currentPrice, "股息基准不能为负数")
        }

        val rows = (1..years).map { year ->
            val projectedDividend = input.dividendBasisPerShare * (1.0 + input.dividendGrowthRate).pow(year)
            DividendCashFlowRow(
                year = year,
                projectedDividend = projectedDividend,
                discountedDividend = projectedDividend / (1.0 + input.discountRate).pow(year)
            )
        }
        val finalDividend = rows.last().projectedDividend
        val terminalValue = finalDividend * (1.0 + input.terminalGrowthRate) /
            (input.discountRate - input.terminalGrowthRate)
        val discountedTerminalValue = terminalValue / (1.0 + input.discountRate).pow(years)
        val intrinsicValue = rows.sumOf { it.discountedDividend } + discountedTerminalValue
        val safetyBuyPrice = intrinsicValue * (1.0 - input.marginOfSafety.coerceIn(0.0, 0.5))
        val comparison = currentPrice?.let { (intrinsicValue - it) / it }

        return DividendDiscountResult(
            projectionYears = years,
            intrinsicValuePerShare = intrinsicValue,
            currentPrice = currentPrice,
            discountOrPremiumPercent = comparison,
            safetyBuyPrice = safetyBuyPrice,
            valuationStatus = when {
                comparison == null -> DividendValuationStatus.NO_MARKET_PRICE
                comparison > 0.05 -> DividendValuationStatus.UNDERVALUED
                comparison < -0.05 -> DividendValuationStatus.OVERVALUED
                else -> DividendValuationStatus.FAIR
            },
            cashFlowRows = rows,
            terminalValue = terminalValue,
            discountedTerminalValue = discountedTerminalValue,
            validationError = null
        )
    }

    private fun invalid(
        years: Int,
        currentPrice: Double?,
        message: String
    ) = DividendDiscountResult(
        projectionYears = years,
        intrinsicValuePerShare = 0.0,
        currentPrice = currentPrice,
        discountOrPremiumPercent = null,
        safetyBuyPrice = 0.0,
        valuationStatus = DividendValuationStatus.INVALID,
        cashFlowRows = emptyList(),
        terminalValue = 0.0,
        discountedTerminalValue = 0.0,
        validationError = message
    )
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run:

```bash
./gradlew testDebugUnitTest --tests "com.stock.dividend.data.repository.DividendDiscountCalculatorTest"
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/stock/dividend/data/repository/DividendDiscountCalculator.kt app/src/test/java/com/stock/dividend/data/repository/DividendDiscountCalculatorTest.kt
git commit -m "feat: add dividend discount calculator"
```

---

### Task 2: Dividend Valuation ViewModel

**Files:**
- Create: `app/src/main/java/com/stock/dividend/viewmodel/DividendValuationViewModel.kt`
- Test: `app/src/test/java/com/stock/dividend/viewmodel/DividendValuationViewModelTest.kt`

- [ ] **Step 1: Write failing ViewModel tests**

Create `app/src/test/java/com/stock/dividend/viewmodel/DividendValuationViewModelTest.kt`:

```kotlin
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
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
./gradlew testDebugUnitTest --tests "com.stock.dividend.viewmodel.DividendValuationViewModelTest"
```

Expected: FAIL because `DividendValuationViewModel` and state types do not exist.

- [ ] **Step 3: Implement ViewModel and UI state**

Create `app/src/main/java/com/stock/dividend/viewmodel/DividendValuationViewModel.kt`:

```kotlin
package com.stock.dividend.viewmodel

import androidx.compose.runtime.Stable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stock.dividend.data.local.entity.StockEntity
import com.stock.dividend.data.repository.DividendDiscountCalculator
import com.stock.dividend.data.repository.DividendDiscountInput
import com.stock.dividend.data.repository.DividendDiscountResult
import com.stock.dividend.data.repository.DividendRepository
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
data class DividendValuationUiState(
    val stock: StockEntity? = null,
    val isLoading: Boolean = true,
    val hasDividendHistory: Boolean = true,
    val dividendBasisYears: Int = 0,
    val currentPrice: Double? = null,
    val dividendBasisInput: String = "",
    val growthRateInput: String = "5",
    val discountRateInput: String = "9",
    val terminalGrowthRateInput: String = "2",
    val projectionYearsInput: String = "10",
    val marginOfSafetyInput: String = "20",
    val result: DividendDiscountResult? = null,
    val validationError: String? = null
)

@HiltViewModel
class DividendValuationViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val stockRepository: StockRepository,
    private val dividendRepository: DividendRepository
) : ViewModel() {
    private val stockCode: String = savedStateHandle["code"] ?: ""
    private val _uiState = MutableStateFlow(DividendValuationUiState())
    val uiState: StateFlow<DividendValuationUiState> = _uiState.asStateFlow()

    private val stockFlow = stockRepository.observeStock(stockCode)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    private val dividendsFlow = dividendRepository.observeDividends(stockCode)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            combine(stockFlow, dividendsFlow) { stock, dividends -> stock to dividends }
                .collect { (stock, dividends) ->
                    val basis = DividendDiscountCalculator.deriveDividendBasis(dividends)
                    val currentPrice = stock?.let { stockRepository.fetchQuotes(listOf(it))[it.code] }
                    _uiState.value = _uiState.value.copy(
                        stock = stock,
                        isLoading = false,
                        hasDividendHistory = basis != null,
                        dividendBasisYears = basis?.actualYears ?: 0,
                        currentPrice = currentPrice,
                        dividendBasisInput = if (_uiState.value.dividendBasisInput.isBlank() && basis != null) {
                            "%.2f".format(basis.averageCashPerShare)
                        } else {
                            _uiState.value.dividendBasisInput
                        }
                    ).recalculated()
                }
        }
    }

    fun onDividendBasisChanged(value: String) = update { copy(dividendBasisInput = value) }
    fun onGrowthRateChanged(value: String) = update { copy(growthRateInput = value) }
    fun onDiscountRateChanged(value: String) = update { copy(discountRateInput = value) }
    fun onTerminalGrowthRateChanged(value: String) = update { copy(terminalGrowthRateInput = value) }
    fun onProjectionYearsChanged(value: String) = update { copy(projectionYearsInput = value) }
    fun onMarginOfSafetyChanged(value: String) = update { copy(marginOfSafetyInput = value) }

    fun applyPreset(preset: DividendValuationPreset) = update {
        copy(
            growthRateInput = preset.growthPercent,
            discountRateInput = preset.discountPercent,
            terminalGrowthRateInput = preset.terminalGrowthPercent,
            marginOfSafetyInput = preset.marginPercent
        )
    }

    private fun update(block: DividendValuationUiState.() -> DividendValuationUiState) {
        _uiState.value = _uiState.value.block().recalculated()
    }

    private fun DividendValuationUiState.recalculated(): DividendValuationUiState {
        val basis = dividendBasisInput.toDoubleOrNull()
        val growth = growthRateInput.toDoubleOrNull()
        val discount = discountRateInput.toDoubleOrNull()
        val terminal = terminalGrowthRateInput.toDoubleOrNull()
        val years = projectionYearsInput.toIntOrNull()
        val margin = marginOfSafetyInput.toDoubleOrNull()
        if (basis == null || growth == null || discount == null || terminal == null || years == null || margin == null) {
            return copy(result = null, validationError = "请输入有效估值参数")
        }

        val result = DividendDiscountCalculator.calculate(
            DividendDiscountInput(
                dividendBasisPerShare = basis,
                dividendGrowthRate = (growth / 100.0).coerceIn(0.0, 0.5),
                discountRate = (discount / 100.0).coerceIn(0.0, 0.5),
                terminalGrowthRate = (terminal / 100.0).coerceIn(0.0, 0.5),
                projectionYears = years,
                marginOfSafety = (margin / 100.0).coerceIn(0.0, 0.5),
                currentPrice = currentPrice
            )
        )
        return copy(result = result, validationError = result.validationError)
    }
}

enum class DividendValuationPreset(
    val label: String,
    val growthPercent: String,
    val discountPercent: String,
    val terminalGrowthPercent: String,
    val marginPercent: String
) {
    CONSERVATIVE("保守", "2", "10", "1", "25"),
    BASE("基准", "5", "9", "2", "20"),
    OPTIMISTIC("乐观", "8", "8", "3", "15")
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run:

```bash
./gradlew testDebugUnitTest --tests "com.stock.dividend.viewmodel.DividendValuationViewModelTest"
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/stock/dividend/viewmodel/DividendValuationViewModel.kt app/src/test/java/com/stock/dividend/viewmodel/DividendValuationViewModelTest.kt
git commit -m "feat: add dividend valuation view model"
```

---

### Task 3: Compose Valuation Screen

**Files:**
- Create: `app/src/main/java/com/stock/dividend/ui/screen/DividendValuationScreen.kt`

- [ ] **Step 1: Add screen content**

Create `app/src/main/java/com/stock/dividend/ui/screen/DividendValuationScreen.kt`:

```kotlin
package com.stock.dividend.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stock.dividend.data.repository.DividendValuationStatus
import com.stock.dividend.ui.component.AppCardDefaults
import com.stock.dividend.ui.component.CompactTopAppBar
import com.stock.dividend.ui.component.FinanceMetric
import com.stock.dividend.ui.component.FinanceStatusTone
import com.stock.dividend.ui.component.SectionHeader
import com.stock.dividend.ui.component.StatusPill
import com.stock.dividend.viewmodel.DividendValuationPreset
import com.stock.dividend.viewmodel.DividendValuationUiState
import com.stock.dividend.viewmodel.DividendValuationViewModel

@Composable
fun DividendValuationScreen(
    onBack: () -> Unit,
    viewModel: DividendValuationViewModel = hiltViewModel()
) {
    val state = viewModel.uiState.collectAsStateWithLifecycle().value
    DividendValuationContent(
        state = state,
        onBack = onBack,
        onDividendBasisChanged = viewModel::onDividendBasisChanged,
        onGrowthRateChanged = viewModel::onGrowthRateChanged,
        onDiscountRateChanged = viewModel::onDiscountRateChanged,
        onTerminalGrowthRateChanged = viewModel::onTerminalGrowthRateChanged,
        onProjectionYearsChanged = viewModel::onProjectionYearsChanged,
        onMarginOfSafetyChanged = viewModel::onMarginOfSafetyChanged,
        onPreset = viewModel::applyPreset
    )
}

@Composable
private fun DividendValuationContent(
    state: DividendValuationUiState,
    onBack: () -> Unit,
    onDividendBasisChanged: (String) -> Unit,
    onGrowthRateChanged: (String) -> Unit,
    onDiscountRateChanged: (String) -> Unit,
    onTerminalGrowthRateChanged: (String) -> Unit,
    onProjectionYearsChanged: (String) -> Unit,
    onMarginOfSafetyChanged: (String) -> Unit,
    onPreset: (DividendValuationPreset) -> Unit
) {
    Scaffold(
        topBar = {
            CompactTopAppBar(
                title = state.stock?.name ?: "股息折现估值",
                onBack = onBack
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(
                start = AppCardDefaults.PageHorizontalPadding,
                top = 12.dp,
                end = AppCardDefaults.PageHorizontalPadding,
                bottom = AppCardDefaults.BottomNavigationPadding
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { ValuationSummaryCard(state) }
            item {
                SectionHeader(title = "估值假设")
                AssumptionCard(
                    state = state,
                    onDividendBasisChanged = onDividendBasisChanged,
                    onGrowthRateChanged = onGrowthRateChanged,
                    onDiscountRateChanged = onDiscountRateChanged,
                    onTerminalGrowthRateChanged = onTerminalGrowthRateChanged,
                    onProjectionYearsChanged = onProjectionYearsChanged,
                    onMarginOfSafetyChanged = onMarginOfSafetyChanged,
                    onPreset = onPreset
                )
            }
            item { SectionHeader(title = "未来现金流明细") }
            state.result?.cashFlowRows?.let { rows ->
                items(rows, key = { it.year }) { row ->
                    Card(colors = AppCardDefaults.listCardColors()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(AppCardDefaults.ListPadding),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            FinanceMetric("年份", "第 ${row.year} 年")
                            FinanceMetric("预估股息", formatCurrency(row.projectedDividend), textAlign = TextAlign.End)
                            FinanceMetric("折现值", formatCurrency(row.discountedDividend), textAlign = TextAlign.End)
                        }
                    }
                }
                item { TerminalValueCard(state) }
            }
        }
    }
}

@Composable
private fun ValuationSummaryCard(state: DividendValuationUiState) {
    val result = state.result
    Card(colors = AppCardDefaults.summaryCardColors()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(AppCardDefaults.SummaryPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("估值结论", style = MaterialTheme.typography.labelLarge)
                    Text(
                        text = result?.let { formatCurrency(it.intrinsicValuePerShare) } ?: "--",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
                result?.let { StatusPill(statusText(it.valuationStatus), statusTone(it.valuationStatus)) }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                FinanceMetric("当前价", state.currentPrice?.let(::formatCurrency) ?: "--")
                FinanceMetric("折价/溢价", result?.discountOrPremiumPercent?.let { "%.1f%%".format(it * 100) } ?: "--")
                FinanceMetric("安全买入价", result?.let { formatCurrency(it.safetyBuyPrice) } ?: "--")
            }
            if (!state.hasDividendHistory) {
                Text("缺少历史股息数据，请手动输入股息基准。", style = MaterialTheme.typography.bodySmall)
            }
            state.validationError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun AssumptionCard(
    state: DividendValuationUiState,
    onDividendBasisChanged: (String) -> Unit,
    onGrowthRateChanged: (String) -> Unit,
    onDiscountRateChanged: (String) -> Unit,
    onTerminalGrowthRateChanged: (String) -> Unit,
    onProjectionYearsChanged: (String) -> Unit,
    onMarginOfSafetyChanged: (String) -> Unit,
    onPreset: (DividendValuationPreset) -> Unit
) {
    Card(colors = AppCardDefaults.listCardColors()) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DividendValuationPreset.entries.forEach { preset ->
                    AssistChip(onClick = { onPreset(preset) }, label = { Text(preset.label) })
                }
            }
            AssumptionField("股息基准", state.dividendBasisInput, onDividendBasisChanged)
            AssumptionField("未来股息增长率 (%)", state.growthRateInput, onGrowthRateChanged)
            AssumptionField("折现率 (%)", state.discountRateInput, onDiscountRateChanged)
            AssumptionField("终值增长率 (%)", state.terminalGrowthRateInput, onTerminalGrowthRateChanged)
            AssumptionField("预测年限", state.projectionYearsInput, onProjectionYearsChanged)
            AssumptionField("安全边际 (%)", state.marginOfSafetyInput, onMarginOfSafetyChanged)
            if (state.dividendBasisYears > 0) {
                Text("股息基准来自近 ${state.dividendBasisYears} 年平均每股股息。", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun AssumptionField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
    )
}

@Composable
private fun TerminalValueCard(state: DividendValuationUiState) {
    val result = state.result ?: return
    Card(colors = AppCardDefaults.listCardColors()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(AppCardDefaults.ListPadding),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            FinanceMetric("终值", formatCurrency(result.terminalValue))
            FinanceMetric("终值折现", formatCurrency(result.discountedTerminalValue), textAlign = TextAlign.End)
            FinanceMetric("合计", formatCurrency(result.intrinsicValuePerShare), textAlign = TextAlign.End)
        }
    }
}

private fun formatCurrency(value: Double): String = "¥%.2f".format(value)

private fun statusText(status: DividendValuationStatus): String = when (status) {
    DividendValuationStatus.UNDERVALUED -> "低估"
    DividendValuationStatus.OVERVALUED -> "高估"
    DividendValuationStatus.FAIR -> "合理"
    DividendValuationStatus.NO_MARKET_PRICE -> "无行情"
    DividendValuationStatus.INVALID -> "参数无效"
}

private fun statusTone(status: DividendValuationStatus): FinanceStatusTone = when (status) {
    DividendValuationStatus.UNDERVALUED -> FinanceStatusTone.Positive
    DividendValuationStatus.OVERVALUED -> FinanceStatusTone.Negative
    DividendValuationStatus.FAIR -> FinanceStatusTone.Neutral
    DividendValuationStatus.NO_MARKET_PRICE -> FinanceStatusTone.Warning
    DividendValuationStatus.INVALID -> FinanceStatusTone.Negative
}
```

- [ ] **Step 2: Compile**

Run:

```bash
./gradlew compileDebugKotlin
```

Expected: PASS. If Compose modifier chaining complains about line breaks, split chained modifiers onto multiple lines without changing behavior.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/stock/dividend/ui/screen/DividendValuationScreen.kt
git commit -m "feat: add dividend valuation screen"
```

---

### Task 4: Navigation and Stock Detail Entry

**Files:**
- Modify: `app/src/main/java/com/stock/dividend/ui/screen/MainScaffold.kt`
- Modify: `app/src/main/java/com/stock/dividend/ui/screen/StockDetailScreen.kt`

- [ ] **Step 1: Inspect dirty stock detail file**

Run:

```bash
git diff -- app/src/main/java/com/stock/dividend/ui/screen/StockDetailScreen.kt
```

Expected: review existing user changes. Do not revert them.

- [ ] **Step 2: Add navigation route in `MainScaffold.kt`**

Modify imports:

```kotlin
import androidx.navigation.NavType
import androidx.navigation.navArgument
```

Ensure `DividendValuationScreen` is in the same package, so no import is needed.

In the `stockDetail/{code}` composable, pass the new callback:

```kotlin
StockDetailScreen(
    stockCode = code,
    onBack = { tabNavController.popBackStack() },
    onEditHolding = { c -> tabNavController.navigate("editHolding/$c") },
    onOpenDividendValuation = { c -> tabNavController.navigate("dividendValuation/$c") }
)
```

Add route near `editHolding/{code}`:

```kotlin
composable(
    route = "dividendValuation/{code}",
    arguments = listOf(navArgument("code") { type = NavType.StringType })
) {
    DividendValuationScreen(onBack = { tabNavController.popBackStack() })
}
```

- [ ] **Step 3: Add stock detail entry**

In `StockDetailScreen` signature, add:

```kotlin
onOpenDividendValuation: (String) -> Unit = {},
```

Add an action icon in `CompactTopAppBar` actions using an existing Material icon:

```kotlin
IconButton(onClick = { onOpenDividendValuation(stockCode) }) {
    Icon(
        imageVector = Icons.Filled.Analytics,
        contentDescription = "股息折现估值",
        tint = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
```

If `Icons.Filled.Analytics` is not available in the current Material Icons dependency, use `Icons.Filled.Calculate`.

Also add a visible card near the forecast section when stock data exists:

```kotlin
item {
    DividendValuationEntryCard(onClick = { onOpenDividendValuation(stockCode) })
}
```

Add this composable near other private card composables in `StockDetailScreen.kt`:

```kotlin
@Composable
private fun DividendValuationEntryCard(onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = AppCardDefaults.listCardColors()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppCardDefaults.ListPadding),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "股息折现估值",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "基于近 5 年股息和未来增长假设评估合理价值",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onClick) {
                Text("查看")
            }
        }
    }
}
```

- [ ] **Step 4: Compile**

Run:

```bash
./gradlew compileDebugKotlin
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/stock/dividend/ui/screen/MainScaffold.kt app/src/main/java/com/stock/dividend/ui/screen/StockDetailScreen.kt
git commit -m "feat: open dividend valuation from stock detail"
```

---

### Task 5: Full Verification

**Files:**
- No new files expected.

- [ ] **Step 1: Run focused unit tests**

Run:

```bash
./gradlew testDebugUnitTest --tests "com.stock.dividend.data.repository.DividendDiscountCalculatorTest" --tests "com.stock.dividend.viewmodel.DividendValuationViewModelTest"
```

Expected: PASS.

- [ ] **Step 2: Run broader unit tests**

Run:

```bash
./gradlew testDebugUnitTest
```

Expected: PASS.

- [ ] **Step 3: Check git status**

Run:

```bash
git status --short
```

Expected: only pre-existing unrelated dirty files remain, or no changes if everything was committed. Do not stage `.superpowers/` unless explicitly requested.

---

## Self-Review

- Spec coverage: independent valuation page, stock-detail entry, 5-year dividend basis, editable assumptions, presets, conclusion card, cash flow details, missing-dividend state, quote-failure behavior, and tests are each covered by tasks.
- Placeholder scan: no placeholder markers or open-ended implementation instructions remain.
- Type consistency: calculator result types are used by the ViewModel and screen with the same names throughout the plan.
