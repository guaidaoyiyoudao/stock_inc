# Lightweight Design System Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a small reusable Compose design-system layer and migrate the watchlist and income pages to shared section header patterns.

**Architecture:** Keep the design system as simple Compose helpers under `app/src/main/java/com/stock/dividend/ui/component/`. The first implementation introduces `SectionHeader`, `FinanceMetric`, `StatusPill`, and `AppCardDefaults`, then replaces duplicated watchlist and income section header code without changing ViewModels, repositories, navigation destinations, or data calculations.

**Tech Stack:** Kotlin 2.0.21, Java 17, Jetpack Compose, Material Design 3, AndroidX Compose UI testing, Gradle.

---

## File Structure

- Create: `app/src/main/java/com/stock/dividend/ui/component/DesignSystem.kt`
  - Owns first-phase shared UI helpers.
  - Exposes `SectionHeader`, `FinanceMetric`, `StatusPill`, and `AppCardDefaults`.
- Create: `app/src/androidTest/java/com/stock/dividend/ui/component/DesignSystemTest.kt`
  - Verifies `SectionHeader` renders title and action and triggers the action callback.
- Modify: `app/src/main/java/com/stock/dividend/ui/screen/HomeScreen.kt`
  - Replaces duplicated watchlist and income header rows with `SectionHeader`.
  - Keeps existing add-stock and add-income behavior intact.
- Modify: `app/src/main/res/values/strings.xml`
  - Adds string resources for `持仓列表`, `收入记录`, and `添加收入` if they are not already present.
- Verify only: `app/src/main/java/com/stock/dividend/ui/screen/MainScaffold.kt`
  - Confirm no floating add action is reintroduced for watchlist or income.

## Task 1: Add Shared Design-System Components

**Files:**
- Create: `app/src/androidTest/java/com/stock/dividend/ui/component/DesignSystemTest.kt`
- Create: `app/src/main/java/com/stock/dividend/ui/component/DesignSystem.kt`

- [ ] **Step 1: Write the failing Compose UI test**

Create `app/src/androidTest/java/com/stock/dividend/ui/component/DesignSystemTest.kt`:

```kotlin
package com.stock.dividend.ui.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import com.stock.dividend.ui.theme.StockDividendTheme
import org.junit.Rule
import org.junit.Test

class DesignSystemTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun sectionHeaderShowsTitleAndRunsAction() {
        var clicked = false

        composeRule.setContent {
            StockDividendTheme {
                SectionHeader(
                    title = "收入记录",
                    actionText = "添加收入",
                    actionIcon = Icons.Default.Add,
                    onActionClick = { clicked = true }
                )
            }
        }

        composeRule.onNodeWithText("收入记录").assertIsDisplayed()
        composeRule.onNodeWithText("添加收入").assertIsDisplayed()
        composeRule.onNodeWithText("添加收入").performClick()

        assertThat(clicked).isTrue()
    }
}
```

- [ ] **Step 2: Run the UI test to verify it fails because `SectionHeader` does not exist**

Run:

```bash
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.stock.dividend.ui.component.DesignSystemTest
```

Expected: FAIL at compile time with an unresolved reference for `SectionHeader`. If no Android device or emulator is available, record that limitation and still run `./gradlew compileDebugKotlin` after implementation.

- [ ] **Step 3: Add the shared design-system components**

Create `app/src/main/java/com/stock/dividend/ui/component/DesignSystem.kt`:

```kotlin
package com.stock.dividend.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.stock.dividend.ui.theme.FinanceGreen
import com.stock.dividend.ui.theme.FinanceRed

object AppCardDefaults {
    val ListPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
    val SummaryPadding = PaddingValues(horizontal = 18.dp, vertical = 18.dp)
    val SectionSpacing: Dp = 10.dp
    val PageHorizontalPadding: Dp = 16.dp
    val BottomNavigationPadding: Dp = 88.dp

    @Composable
    fun listCardColors(): CardColors =
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)

    @Composable
    fun summaryCardColors(): CardColors =
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    actionText: String? = null,
    actionIcon: ImageVector? = null,
    onActionClick: (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 6.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold
        )

        if (actionText != null && onActionClick != null) {
            TextButton(onClick = onActionClick) {
                if (actionIcon != null) {
                    Icon(
                        imageVector = actionIcon,
                        contentDescription = null
                    )
                }
                Text(
                    text = actionText,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

@Composable
fun FinanceMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    textAlign: TextAlign = TextAlign.Start
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = valueColor,
            fontWeight = FontWeight.SemiBold,
            textAlign = textAlign
        )
    }
}

enum class FinanceStatusTone {
    Positive,
    Warning,
    Negative,
    Neutral
}

@Composable
fun StatusPill(
    text: String,
    tone: FinanceStatusTone,
    modifier: Modifier = Modifier
) {
    val color = when (tone) {
        FinanceStatusTone.Positive -> FinanceGreen
        FinanceStatusTone.Warning -> MaterialTheme.colorScheme.tertiary
        FinanceStatusTone.Negative -> FinanceRed
        FinanceStatusTone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        color = color.copy(alpha = 0.12f)
    ) {
        Text(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            fontWeight = FontWeight.SemiBold
        )
    }
}
```

- [ ] **Step 4: Run the UI test to verify it passes**

Run:

```bash
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.stock.dividend.ui.component.DesignSystemTest
```

Expected: PASS when a device or emulator is available. If no Android device or emulator is available, run:

```bash
./gradlew compileDebugKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit the shared components**

Run:

```bash
git add app/src/main/java/com/stock/dividend/ui/component/DesignSystem.kt app/src/androidTest/java/com/stock/dividend/ui/component/DesignSystemTest.kt
git commit -m "Add lightweight design system components"
```

## Task 2: Migrate Watchlist and Income Section Headers

**Files:**
- Modify: `app/src/main/java/com/stock/dividend/ui/screen/HomeScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 1: Add string resources for shared section labels**

Modify `app/src/main/res/values/strings.xml` and add these strings near the home and income strings:

```xml
<string name="watchlist_section_holdings">持仓列表</string>
<string name="income_section_records">收入记录</string>
<string name="income_action_add">添加收入</string>
```

Keep the existing `add_stock` string for `添加股票`.

- [ ] **Step 2: Run resource processing to verify the new resources are valid**

Run:

```bash
./gradlew compileDebugKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Replace the watchlist header row with `SectionHeader`**

In `app/src/main/java/com/stock/dividend/ui/screen/HomeScreen.kt`, add imports:

```kotlin
import androidx.compose.ui.res.stringResource
import com.stock.dividend.R
import com.stock.dividend.ui.component.SectionHeader
```

Replace the current watchlist header `Row` item with:

```kotlin
item {
    SectionHeader(
        title = stringResource(R.string.watchlist_section_holdings),
        actionText = stringResource(R.string.add_stock),
        actionIcon = Icons.Default.Add,
        onActionClick = onAddStockClick
    )
}
```

- [ ] **Step 4: Replace the income header row with `SectionHeader`**

In `IncomeTabContent`, replace the current income records header `Row` with:

```kotlin
SectionHeader(
    title = stringResource(R.string.income_section_records),
    actionText = stringResource(R.string.income_action_add),
    actionIcon = Icons.Default.Add,
    onActionClick = onAddIncomeClick,
    modifier = Modifier.padding(horizontal = AppCardDefaults.PageHorizontalPadding)
)
```

Add this import:

```kotlin
import com.stock.dividend.ui.component.AppCardDefaults
```

- [ ] **Step 5: Remove no-longer-needed imports from `HomeScreen.kt`**

Remove imports that become unused after replacing the custom header rows:

```kotlin
import androidx.compose.ui.text.font.FontWeight
```

Only remove `FontWeight` if the compiler reports it unused. If other functions in `HomeScreen.kt` still use it, leave it.

- [ ] **Step 6: Run focused tests for unchanged behavior**

Run:

```bash
./gradlew testDebugUnitTest --tests "com.stock.dividend.viewmodel.HomeViewModelTest" --tests "com.stock.dividend.viewmodel.DividendIncomeViewModelTest"
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Compile the app**

Run:

```bash
./gradlew compileDebugKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit the first page migration**

Run:

```bash
git add app/src/main/java/com/stock/dividend/ui/screen/HomeScreen.kt app/src/main/res/values/strings.xml
git commit -m "Use shared section headers on primary tabs"
```

## Task 3: Clean Up Main Navigation Action State and Verify No FAB Regression

**Files:**
- Verify: `app/src/main/java/com/stock/dividend/ui/screen/MainScaffold.kt`
- Test: existing unit tests

- [ ] **Step 1: Verify `MainScaffold` has no add-action floating button**

Inspect `app/src/main/java/com/stock/dividend/ui/screen/MainScaffold.kt` and confirm `Scaffold` does not define `floatingActionButton`.

The `Scaffold` should look structurally like:

```kotlin
Scaffold(
    snackbarHost = { SnackbarHost(snackbarHostState) },
    bottomBar = {
        NavigationBar(
            tonalElevation = 0.dp
        ) {
            bottomNavItems.forEachIndexed { index, item ->
                NavigationBarItem(
                    selected = index == selectedTabIndex,
                    onClick = {
                        if (index != selectedTabIndex) {
                            tabNavController.navigate(item.route) {
                                popUpTo(tabNavController.graph.startDestinationId) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    },
                    icon = {
                        Icon(
                            imageVector = item.icon,
                            contentDescription = item.label,
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    label = { Text(item.label, style = MaterialTheme.typography.labelSmall) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
            }
        }
    }
) { padding ->
    NavHost(
        navController = tabNavController,
        startDestination = "watchlist",
        modifier = Modifier.padding(padding)
    ) {
        composable("watchlist") {
            WatchlistScreen(
                snackbarHostState = snackbarHostState,
                onAddStockClick = { tabNavController.navigate("addStock") },
                onStockClick = { code -> tabNavController.navigate("stockDetail/$code") },
                onFireCardClick = { rootNavController.navigate(Routes.EXPENSE_COVERAGE) }
            )
        }
        composable("income") {
            IncomeScreen()
        }
        composable("achievements") {
            AchievementScreen()
        }
        composable("addStock") {
            AddStockScreen(onBack = { tabNavController.popBackStack() })
        }
        composable(
            route = "stockDetail/{code}",
            arguments = listOf(navArgument("code") { type = NavType.StringType })
        ) { entry ->
            val code = entry.arguments?.getString("code") ?: return@composable
            StockDetailScreen(
                stockCode = code,
                onBack = { tabNavController.popBackStack() },
                onEditHolding = { c -> tabNavController.navigate("editHolding/$c") }
            )
        }
        composable(
            route = "editHolding/{code}",
            arguments = listOf(navArgument("code") { type = NavType.StringType })
        ) {
            EditHoldingScreen(onBack = { tabNavController.popBackStack() })
        }
    }
}
```

- [ ] **Step 2: Search for old FAB trigger code**

Run:

```bash
rg -n "fabTrigger|incomeFabTrigger|floatingActionButton|ExtendedFloatingActionButton" app/src/main/java/com/stock/dividend/ui/screen/MainScaffold.kt app/src/main/java/com/stock/dividend/ui/screen/HomeScreen.kt
```

Expected: no matches for `fabTrigger`, `incomeFabTrigger`, or `floatingActionButton` in these two files. `ExtendedFloatingActionButton` should not appear in these two files after this migration.

- [ ] **Step 3: Run the full unit test suite**

Run:

```bash
./gradlew testDebugUnitTest
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Compile debug Kotlin**

Run:

```bash
./gradlew compileDebugKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Check whitespace and final diff**

Run:

```bash
git diff --check
git diff --stat
```

Expected: `git diff --check` prints no output. `git diff --stat` shows only the design-system component, its UI test, `HomeScreen.kt`, and `strings.xml` unless previous committed work is already part of the branch.

- [ ] **Step 6: Commit final cleanup if Task 3 changed files**

If Task 3 required code cleanup, run:

```bash
git add app/src/main/java/com/stock/dividend/ui/screen/MainScaffold.kt app/src/main/java/com/stock/dividend/ui/screen/HomeScreen.kt
git commit -m "Remove primary tab floating add action"
```

If Task 3 did not change files, do not create an empty commit.

## Self-Review

Spec coverage:

- Shared UI patterns: Task 1 adds reusable components.
- Section header row: Task 1 creates `SectionHeader`; Task 2 migrates watchlist and income.
- Card baseline: Task 1 adds `AppCardDefaults` for shared padding and card colors.
- Metric text: Task 1 adds `FinanceMetric`.
- Status labels: Task 1 adds `StatusPill`.
- Action placement: Task 2 keeps add actions in section headers; Task 3 verifies the old floating add action stays removed.
- Business logic unchanged: Tasks only touch UI components, screen composition, strings, and UI tests.

Placeholder scan:

- This plan contains no unresolved marker text or unspecified implementation steps.
- Commands include exact paths and expected outcomes.

Type consistency:

- `SectionHeader`, `FinanceMetric`, `StatusPill`, `FinanceStatusTone`, and `AppCardDefaults` are defined in Task 1 before later use.
- `HomeScreen.kt` migration references `SectionHeader` and `AppCardDefaults` with matching names.
