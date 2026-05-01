# Income Trend Chart Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a year-over-year income bar chart to the Income tab using Vico chart library.

**Architecture:** Add Vico dependency, extend DAO with yearly totals query, expose through ViewModel as a map, create `IncomeTrendChart` composable, embed in `IncomeTabContent`.

**Tech Stack:** Vico 3.1.0 (compose + compose-m3), Kotlin 2.0.21, Jetpack Compose

---

## Chunk 1: Dependencies + Data Layer

### Task 1: Add Vico dependencies

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: Add Vico to version catalog**

In `gradle/libs.versions.toml`, add to `[versions]`:
```
vico = "3.1.0"
```

Add to `[libraries]`:
```
vico-compose = { group = "com.patrykandpatrick.vico", name = "compose", version.ref = "vico" }
vico-compose-m3 = { group = "com.patrykandpatrick.vico", name = "compose-m3", version.ref = "vico" }
```

- [ ] **Step 2: Add Vico to app build.gradle.kts**

In `app/build.gradle.kts`, add to the dependencies block:
```kotlin
implementation(libs.vico.compose)
implementation(libs.vico.compose.m3)
```

- [ ] **Step 3: Verify build syncs**

Run: `./gradlew :app:assembleDebug --dry-run`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts
git commit -m "build: add Vico chart library dependency"
```

### Task 2: Add DAO yearly totals query

**Files:**
- Modify: `app/src/main/java/com/stock/dividend/data/local/dao/DividendIncomeRecordDao.kt`
- Modify: `app/src/main/java/com/stock/dividend/data/repository/DividendIncomeRepository.kt`

- [ ] **Step 1: Add YearlyTotal data class and query to DAO**

In `DividendIncomeRecordDao.kt`, add outside the interface:

```kotlin
data class YearlyTotal(val year: Int, val total: Double)
```

Inside the interface, add:

```kotlin
@Query("SELECT year, COALESCE(SUM(amount), 0.0) as total FROM dividend_income_records GROUP BY year ORDER BY year ASC")
fun observeYearlyTotals(): Flow<List<YearlyTotal>>
```

Note: Room requires the data class fields to exactly match the query column aliases. The `COALESCE(SUM(amount), 0.0)` must be aliased as `total`, and `year` matches the column name.

- [ ] **Step 2: Add passthrough in repository**

In `DividendIncomeRepository.kt`, add:

```kotlin
fun observeYearlyTotals(): Flow<List<YearlyTotal>> =
    incomeRecordDao.observeYearlyTotals()
```

Import `YearlyTotal` from the DAO package.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/stock/dividend/data/local/dao/DividendIncomeRecordDao.kt app/src/main/java/com/stock/dividend/data/repository/DividendIncomeRepository.kt
git commit -m "feat: add yearly totals query for income trend chart"
```

---

## Chunk 2: ViewModel + UI

### Task 3: Expose yearly totals in ViewModel

**Files:**
- Modify: `app/src/main/java/com/stock/dividend/viewmodel/DividendIncomeViewModel.kt`

- [ ] **Step 1: Add yearlyTotals to UiState**

In `DividendIncomeUiState`, add field:
```kotlin
val yearlyTotals: Map<Int, Double> = emptyMap()
```

- [ ] **Step 2: Add coroutine to observe yearly totals**

In `DividendIncomeViewModel.init`, add a new coroutine block:

```kotlin
// Observe yearly totals for trend chart
viewModelScope.launch {
    incomeRepository.observeYearlyTotals().collect { totals ->
        _uiState.value = _uiState.value.copy(
            yearlyTotals = totals.associate { it.year to it.total }
        )
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/stock/dividend/viewmodel/DividendIncomeViewModel.kt
git commit -m "feat: expose yearly totals map in DividendIncomeViewModel"
```

### Task 4: Create IncomeTrendChart component

**Files:**
- Create: `app/src/main/java/com/stock/dividend/ui/component/IncomeTrendChart.kt`

- [ ] **Step 1: Create the chart composable**

```kotlin
package com.stock.dividend.ui.component

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.columnSeries
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberFadingEdges
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter

@Composable
fun IncomeTrendChart(
    yearlyTotals: Map<Int, Double>,
    selectedYear: Int,
    onYearClick: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    if (yearlyTotals.size < 2) return

    val years = remember(yearlyTotals) { yearlyTotals.keys.sorted() }
    val amounts = remember(yearlyTotals) { years.map { yearlyTotals[it] ?: 0.0 } }
    val modelProducer = remember { CartesianChartModelProducer() }

    LaunchedEffect(amounts) {
        modelProducer.runTransaction {
            columnSeries { series(*amounts.toTypedArray()) }
        }
    }

    val bottomAxisValueFormatter = remember(years) {
        CartesianValueFormatter { value, _, _ ->
            val index = value.toInt()
            if (index in years.indices) years[index].toString() else ""
        }
    }

    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        CartesianChartHost(
            chart = rememberCartesianChart(
                rememberColumnCartesianLayer(),
                startAxis = VerticalAxis.rememberStart(),
                bottomAxis = HorizontalAxis.rememberBottom(
                    valueFormatter = bottomAxisValueFormatter
                ),
                fadingEdges = rememberFadingEdges(),
            ),
            modelProducer = modelProducer,
            modifier = Modifier
                .height(180.dp)
                .padding(horizontal = 8.dp, vertical = 12.dp),
        )
    }
}
```

**Important notes:**
- The exact Vico API signatures depend on the 3.1.0 release. The import paths above use the documented patterns. If `rememberBottom`/`rememberStart` don't exist, the alternative is `VerticalAxis.rememberStart()` / `HorizontalAxis.rememberBottom()`.
- If `rememberFadingEdges()` is not available, remove it — it's optional polish.
- The `CartesianValueFormatter` lambda signature may vary. Adjust based on compilation errors.

- [ ] **Step 2: Verify compilation**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

If compilation fails, fix import paths or API differences based on error messages. Common Vico 3.x API patterns:
- Axis: `VerticalAxis.rememberStart()` or `remember { VerticalAxis.Start() }`
- ValueFormatter: Check the exact functional interface signature

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/stock/dividend/ui/component/IncomeTrendChart.kt
git commit -m "feat: add IncomeTrendChart component with Vico column chart"
```

### Task 5: Integrate chart into IncomeTabContent

**Files:**
- Modify: `app/src/main/java/com/stock/dividend/ui/screen/HomeScreen.kt`

- [ ] **Step 1: Add import for IncomeTrendChart**

At the top of `HomeScreen.kt`, add:
```kotlin
import com.stock.dividend.ui.component.IncomeTrendChart
```

- [ ] **Step 2: Insert chart into IncomeTabContent**

In the `IncomeTabContent` function, after the `YearSelector` and its `Spacer(modifier = Modifier.height(8.dp))`, add the chart before the `IncomeSummaryCard`:

```kotlin
IncomeTrendChart(
    yearlyTotals = state.yearlyTotals,
    selectedYear = state.selectedYear,
    onYearClick = { viewModel.selectYear(it) },
    modifier = Modifier.padding(horizontal = 16.dp)
)

Spacer(modifier = Modifier.height(8.dp))
```

The full order should be:
1. `YearSelector`
2. `Spacer(8.dp)`
3. `IncomeTrendChart` (new)
4. `Spacer(8.dp)`
5. `IncomeSummaryCard`
6. `Spacer(8.dp)`
7. Records list or empty state

- [ ] **Step 3: Build and verify**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/stock/dividend/ui/screen/HomeScreen.kt
git commit -m "feat: integrate income trend chart into income tab"
```

### Task 6: Build verification and cleanup

**Files:** All modified files

- [ ] **Step 1: Run full debug build**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Run existing unit tests**

Run: `./gradlew :app:testDebugUnitTest`
Expected: All tests pass (no existing tests should break — we only added a new DAO query and a ViewModel field)

- [ ] **Step 3: Final commit if any fixes needed**

If any fixes were needed during verification, commit them.

- [ ] **Step 4: Done — income trend chart feature complete**

The chart is now visible in the Income tab. Users with 2+ years of data will see a bar chart showing their year-over-year dividend income trend.
