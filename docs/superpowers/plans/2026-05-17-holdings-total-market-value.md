# Holdings Total Market Value Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show total portfolio market value in the holdings list header, displaying `¥0.00` when no market value is available.

**Architecture:** Reuse the existing `HomeUiState.totalMarketValue` produced by `HomeViewModel`. Add a small pure formatter in `HomeScreen.kt` and a local `HoldingsSectionHeader` composable so this presentation-only feature does not expand the shared `SectionHeader` API.

**Tech Stack:** Kotlin 2.0.21, Jetpack Compose, Material 3, Coroutines Flow, JUnit, Truth, MockK.

---

## File Structure

- Modify `app/src/main/java/com/stock/dividend/ui/screen/HomeScreen.kt`: add the local holdings header composable, format nullable market value as currency, and replace the existing holdings `SectionHeader` call.
- Modify `app/src/test/java/com/stock/dividend/viewmodel/HomeViewModelTest.kt`: add focused ViewModel coverage proving quote prices produce `totalMarketValue`.
- Create `app/src/test/java/com/stock/dividend/ui/screen/HomeScreenFormatTest.kt`: test the pure formatter for present and missing market values.

## Task 1: Add ViewModel Coverage for Total Market Value

**Files:**
- Test: `app/src/test/java/com/stock/dividend/viewmodel/HomeViewModelTest.kt`

- [ ] **Step 1: Write the failing test**

Add this test near the other `HomeViewModel` tests:

```kotlin
@Test
fun `totalMarketValue sums quote price times shares for active holdings`() = runTest {
    val stocks = listOf(
        StockEntity("sz.000001", "平安银行", "0", shares = 100),
        StockEntity("sh.600000", "浦发银行", "1", shares = 200),
        StockEntity("sz.000002", "万科A", "0", shares = 0)
    )
    stocksFlow.value = stocks
    coEvery { stockRepository.fetchQuotes(match { requested ->
        requested.map { it.code } == listOf("sz.000001", "sh.600000")
    }) } returns mapOf(
        "sz.000001" to 10.5,
        "sh.600000" to 7.25
    )

    val viewModel = HomeViewModel(stockRepository, dividendDao, livingExpenseRepository, transactionDao)
    testDispatcher.scheduler.advanceUntilIdle()

    assertThat(viewModel.uiState.value.totalMarketValue).isEqualTo(2500.0)
}
```

- [ ] **Step 2: Run the test to verify it fails or proves existing behavior**

Run:

```bash
./gradlew testDebugUnitTest --tests com.stock.dividend.viewmodel.HomeViewModelTest.totalMarketValue\ sums\ quote\ price\ times\ shares\ for\ active\ holdings
```

Expected: PASS if the existing ViewModel already implements this behavior. If it fails, the failure should show `totalMarketValue` is missing or incorrect.

- [ ] **Step 3: Keep production code unchanged if the test passes**

If Step 2 passes, do not change `HomeViewModel.kt`. This task is only coverage for an existing data path.

- [ ] **Step 4: Commit**

```bash
git add app/src/test/java/com/stock/dividend/viewmodel/HomeViewModelTest.kt
git commit -m "test: cover holdings total market value"
```

## Task 2: Add Market Value Formatter

**Files:**
- Modify: `app/src/main/java/com/stock/dividend/ui/screen/HomeScreen.kt`
- Create: `app/src/test/java/com/stock/dividend/ui/screen/HomeScreenFormatTest.kt`

- [ ] **Step 1: Write the failing formatter tests**

Create `app/src/test/java/com/stock/dividend/ui/screen/HomeScreenFormatTest.kt`:

```kotlin
package com.stock.dividend.ui.screen

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HomeScreenFormatTest {

    @Test
    fun `formatHoldingsTotalMarketValue displays zero when value is null`() {
        assertThat(formatHoldingsTotalMarketValue(null)).isEqualTo("总市值 ¥0.00")
    }

    @Test
    fun `formatHoldingsTotalMarketValue displays formatted amount when value is present`() {
        assertThat(formatHoldingsTotalMarketValue(12345.678)).isEqualTo("总市值 ¥12,345.68")
    }
}
```

- [ ] **Step 2: Run formatter tests to verify they fail**

Run:

```bash
./gradlew testDebugUnitTest --tests com.stock.dividend.ui.screen.HomeScreenFormatTest
```

Expected: FAIL because `formatHoldingsTotalMarketValue` does not exist.

- [ ] **Step 3: Add the minimal formatter implementation**

In `app/src/main/java/com/stock/dividend/ui/screen/HomeScreen.kt`, add this import:

```kotlin
import java.util.Locale
```

Add this top-level helper near the bottom of the file:

```kotlin
internal fun formatHoldingsTotalMarketValue(value: Double?): String {
    return "总市值 ¥${"%,.2f".format(Locale.US, value ?: 0.0)}"
}
```

- [ ] **Step 4: Run formatter tests to verify they pass**

Run:

```bash
./gradlew testDebugUnitTest --tests com.stock.dividend.ui.screen.HomeScreenFormatTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/stock/dividend/ui/screen/HomeScreen.kt app/src/test/java/com/stock/dividend/ui/screen/HomeScreenFormatTest.kt
git commit -m "test: add holdings market value formatter"
```

## Task 3: Render Total Market Value in Holdings Header

**Files:**
- Modify: `app/src/main/java/com/stock/dividend/ui/screen/HomeScreen.kt`
- Test: `app/src/test/java/com/stock/dividend/ui/screen/HomeScreenFormatTest.kt`

- [ ] **Step 1: Write the failing UI-facing formatter assertion**

Add this test to `HomeScreenFormatTest` to lock the exact display text used by the header:

```kotlin
@Test
fun `formatHoldingsTotalMarketValue displays negative-safe zero for non-positive null fallback only`() {
    assertThat(formatHoldingsTotalMarketValue(0.0)).isEqualTo("总市值 ¥0.00")
}
```

- [ ] **Step 2: Run the test to verify current formatting still passes**

Run:

```bash
./gradlew testDebugUnitTest --tests com.stock.dividend.ui.screen.HomeScreenFormatTest
```

Expected: PASS. This test is a guard before wiring the formatter into Compose.

- [ ] **Step 3: Add the local holdings header composable**

In `app/src/main/java/com/stock/dividend/ui/screen/HomeScreen.kt`, add this composable near `WatchlistContent`:

```kotlin
@Composable
private fun HoldingsSectionHeader(
    totalMarketValue: Double?,
    onAddStockClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 6.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = stringResource(R.string.watchlist_section_holdings),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = formatHoldingsTotalMarketValue(totalMarketValue),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        TextButton(onClick = onAddStockClick) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null
            )
            Text(
                text = stringResource(R.string.add_stock),
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}
```

Ensure `HomeScreen.kt` imports `androidx.compose.material3.TextButton` already exist. `Row`, `Column`, `Icon`, `Text`, `Icons.Default.Add`, `Arrangement`, `Alignment`, `MaterialTheme`, and `FontWeight` are already imported in the file.

- [ ] **Step 4: Replace the holdings section header call**

In `WatchlistContent`, replace:

```kotlin
SectionHeader(
    title = stringResource(R.string.watchlist_section_holdings),
    actionText = stringResource(R.string.add_stock),
    actionIcon = Icons.Default.Add,
    onActionClick = onAddStockClick
)
```

with:

```kotlin
HoldingsSectionHeader(
    totalMarketValue = uiState.totalMarketValue,
    onAddStockClick = onAddStockClick
)
```

- [ ] **Step 5: Run focused tests**

Run:

```bash
./gradlew testDebugUnitTest --tests com.stock.dividend.ui.screen.HomeScreenFormatTest --tests com.stock.dividend.viewmodel.HomeViewModelTest.totalMarketValue\ sums\ quote\ price\ times\ shares\ for\ active\ holdings
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/stock/dividend/ui/screen/HomeScreen.kt app/src/test/java/com/stock/dividend/ui/screen/HomeScreenFormatTest.kt
git commit -m "feat: show holdings total market value"
```

## Task 4: Final Verification

**Files:**
- Verify only; no planned file edits.

- [ ] **Step 1: Run all unit tests**

```bash
./gradlew testDebugUnitTest
```

Expected: PASS.

- [ ] **Step 2: Inspect final diff for scope**

```bash
git diff HEAD~3..HEAD -- app/src/main/java/com/stock/dividend/ui/screen/HomeScreen.kt app/src/test/java/com/stock/dividend/viewmodel/HomeViewModelTest.kt app/src/test/java/com/stock/dividend/ui/screen/HomeScreenFormatTest.kt
```

Expected: Diff only adds the formatter, holdings header UI, and focused tests.

- [ ] **Step 3: Check worktree for unrelated edits**

```bash
git status --short
```

Expected: Existing unrelated dirty files may remain, but no uncommitted changes from this feature.

## Self-Review

- Spec coverage: Requirements 1 and 3 are covered by Task 3; requirement 2 is covered by Tasks 1 and 3; requirement 4 is preserved by keeping data and refresh code unchanged.
- Placeholder scan: no placeholder markers or vague future work remain.
- Type consistency: `totalMarketValue` remains `Double?`, the formatter accepts `Double?`, and the UI passes `uiState.totalMarketValue` directly.
