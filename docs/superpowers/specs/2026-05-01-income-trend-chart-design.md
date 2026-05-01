# Income Trend Chart Design

**Goal:** Add a year-over-year income bar chart to the existing Income tab, showing dividend income trend across all available years.

**Architecture:** Reuse existing `DividendIncomeRecordDao` to query yearly totals for all years. Add a new `yearlyTotals` map to `DividendIncomeUiState`. Create a single new Composable `IncomeTrendChart` using Vico chart library. Embed it in `IncomeTabContent` above the summary card.

**Tech Stack:** Vico 3.1.0 (compose + compose-m3), Kotlin 2.0.21, Jetpack Compose

---

## 1. Dependency Changes

### `gradle/libs.versions.toml`

Add to `[versions]`:
```
vico = "3.1.0"
```

Add to `[libraries]`:
```
vico-compose = { group = "com.patrykandpatrick.vico", name = "compose", version.ref = "vico" }
vico-compose-m3 = { group = "com.patrykandpatrick.vico", name = "compose-m3", version.ref = "vico" }
```

### `app/build.gradle.kts`

Add to dependencies:
```
implementation(libs.vico.compose)
implementation(libs.vico.compose.m3)
```

No database migration needed. No new tables.

## 2. Data Layer

### DAO: `DividendIncomeRecordDao`

Add one query:

```kotlin
@Query("SELECT year, COALESCE(SUM(amount), 0.0) as total FROM dividend_income_records GROUP BY year ORDER BY year ASC")
fun observeYearlyTotals(): Flow<List<YearlyTotal>>

data class YearlyTotal(val year: Int, val total: Double)
```

### Repository: `DividendIncomeRepository`

Add passthrough:

```kotlin
fun observeYearlyTotals(): Flow<List<YearlyTotal>> =
    incomeRecordDao.observeYearlyTotals()
```

## 3. ViewModel Changes

### `DividendIncomeUiState`

Add field:
```kotlin
val yearlyTotals: Map<Int, Double> = emptyMap()  // year -> total income
```

### `DividendIncomeViewModel`

Add new coroutine in `init`:
```kotlin
// Observe yearly totals for chart
viewModelScope.launch {
    incomeRepository.observeYearlyTotals().collect { totals ->
        _uiState.value = _uiState.value.copy(
            yearlyTotals = totals.associate { it.year to it.total }
        )
    }
}
```

## 4. UI Component

### `IncomeTrendChart.kt` (new file)

A Composable that renders a column chart of yearly income totals.

**Props:**
- `yearlyTotals: Map<Int, Double>` — year -> total amount
- `selectedYear: Int` — currently selected year (highlighted bar)
- `onYearClick: (Int) -> Unit` — tap a bar to select that year
- `modifier: Modifier`

**Behavior:**
- Show one column per year in `yearlyTotals`, sorted ascending
- Selected year column uses `colorScheme.primary`, other columns use `colorScheme.primaryContainer`
- Y-axis shows amounts in yuan
- X-axis shows year labels (e.g., "2024", "2025")
- If fewer than 2 years of data, show nothing (return empty Box) — the chart is meaningless with 0-1 data points
- Height: ~180dp
- Card wrapper with same style as `IncomeSummaryCard` (RoundedCornerShape 14dp, elevation 1dp)

**Vico implementation sketch:**
```kotlin
@Composable
fun IncomeTrendChart(
    yearlyTotals: Map<Int, Double>,
    selectedYear: Int,
    onYearClick: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    if (yearlyTotals.size < 2) return  // Need at least 2 years

    val modelProducer = remember { CartesianChartModelProducer() }
    val years = yearlyTotals.keys.sorted()
    val amounts = years.map { yearlyTotals[it] ?: 0.0 }

    LaunchedEffect(yearlyTotals) {
        modelProducer.runTransaction {
            columnSeries { series(*amounts.toTypedArray()) }
        }
    }

    Card(...) {
        CartesianChartHost(
            chart = rememberCartesianChart(
                rememberColumnCartesianLayer(
                    columnProvider = ColumnProvider.series(... colors by index)
                ),
                startAxis = VerticalAxis.rememberStart(),
                bottomAxis = HorizontalAxis.rememberBottom(
                    valueFormatter = { value, _, _ ->
                        years.getOrElse(value.toInt()) { "" }.toString()
                    }
                ),
            ),
            modelProducer = modelProducer,
            marker = remember { YearMarker(years, selectedYear, onYearClick) },
            modifier = modifier.height(180.dp),
        )
    }
}
```

Note: The selected year highlighting and click handling will use Vico's marker/selection API. The exact implementation may use `ColumnCartesianLayer.ColumnProvider` for per-column colors and a custom `CartesianMarker` for click detection.

## 5. Integration in `IncomeTabContent`

Insert `IncomeTrendChart` above `IncomeSummaryCard` in `IncomeTabContent` (`HomeScreen.kt`):

```kotlin
// In IncomeTabContent, after YearSelector:
IncomeTrendChart(
    yearlyTotals = state.yearlyTotals,
    selectedYear = state.selectedYear,
    onYearClick = { viewModel.selectYear(it) },
    modifier = Modifier.padding(horizontal = 16.dp)
)
Spacer(modifier = Modifier.height(8.dp))
// Then IncomeSummaryCard as before...
```

## 6. Files Summary

**New files:**
- `app/src/main/java/com/stock/dividend/ui/component/IncomeTrendChart.kt`

**Modified files:**
- `gradle/libs.versions.toml` — add vico version + libraries
- `app/build.gradle.kts` — add vico dependencies
- `DividendIncomeRecordDao.kt` — add `observeYearlyTotals()` query + `YearlyTotal` data class
- `DividendIncomeRepository.kt` — add `observeYearlyTotals()` passthrough
- `DividendIncomeUiState` — add `yearlyTotals` field
- `DividendIncomeViewModel.kt` — add coroutine to observe yearly totals
- `HomeScreen.kt` — add `IncomeTrendChart` to `IncomeTabContent`

**No database migration needed.**

## Out of Scope (Future Iterations)
- Cumulative income line overlay
- Monthly granularity chart
- Achievement/milestone system
- Compound interest simulator
- Pie chart for portfolio allocation
