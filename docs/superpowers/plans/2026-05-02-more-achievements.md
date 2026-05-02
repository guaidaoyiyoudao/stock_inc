# More Achievements Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add 10 new achievements across 4 new categories (记录习惯, 收益突破, 目标达成, 数据完整) to the existing Android achievement system.

**Architecture:** Extend the existing pattern — add entries to `AchievementCategory` and `AchievementDef` enums, extend `CheckContext` with new data fields, add condition branches to `AchievementChecker.check()`, and expand the ViewModel's combine block with new data flows. No database migration needed.

**Tech Stack:** Kotlin 2.0, Room, Hilt, Coroutines + Flow, JUnit + Truth for testing

---

## File Structure

| File | Action | Responsibility |
|---|---|---|
| `viewmodel/AchievementCategory.kt` | Modify | Add 4 new category enum entries |
| `viewmodel/AchievementDef.kt` | Modify | Add 10 new achievement enum entries |
| `viewmodel/AchievementChecker.kt` | Modify | Extend CheckContext, add 10 condition branches |
| `viewmodel/AchievementViewModel.kt` | Modify | Add FireGoalRepository, expand combine to 8 flows |
| `data/local/dao/DividendIncomeRecordDao.kt` | Modify | Add 3 new DAO queries + data class |
| `data/repository/DividendIncomeRepository.kt` | Modify | Add wrapper methods + observeForecastTotal |
| `viewmodel/AchievementCheckerTest.kt` | Modify | Add 10+ new unit tests |

---

### Task 1: Add New Achievement Categories

**Files:**
- Modify: `app/src/main/java/com/stock/dividend/viewmodel/AchievementCategory.kt`

- [ ] **Step 1: Add 4 new enum entries to AchievementCategory**

```kotlin
package com.stock.dividend.viewmodel

enum class AchievementCategory(
    val id: String,
    val title: String,
    val description: String,
    val icon: String
) {
    INCOME_MILESTONE("income_milestone", "收入里程碑", "迈向财务自由之路", "💰"),
    INVESTMENT_STRATEGY("investment_strategy", "投资策略", "构建多元化组合", "📊"),
    LONG_TERM_COMMITMENT("long_term_commitment", "长期坚持", "时间是最好的朋友", "⏳"),
    RECORDING_HABIT("recording_habit", "记录习惯", "坚持记录每一笔股息", "📝"),
    INCOME_BREAKTHROUGH("income_breakthrough", "收益突破", "追求更高的股息回报", "🚀"),
    GOAL_ACHIEVEMENT("goal_achievement", "目标达成", "向 FIRE 财务自由迈进", "🎯"),
    DATA_COMPLETENESS("data_completeness", "数据完整", "完善投资数据，掌控全局", "✅")
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/stock/dividend/viewmodel/AchievementCategory.kt
git commit -m "feat: add 4 new achievement categories"
```

---

### Task 2: Add New Achievement Definitions

**Files:**
- Modify: `app/src/main/java/com/stock/dividend/viewmodel/AchievementDef.kt`

- [ ] **Step 1: Add 10 new enum entries to AchievementDef**

Add after the existing `STREAK_3Y` entry (change its trailing comma from `;` to `,` and end the new last entry with `;`):

```kotlin
package com.stock.dividend.viewmodel

enum class AchievementDef(
    val id: String,
    val title: String,
    val description: String,
    val icon: String,
    val category: AchievementCategory
) {
    FIRST_DIVIDEND("first_dividend", "首次分红", "收到第一笔股息收入", "🌱", AchievementCategory.INCOME_MILESTONE),
    INCOME_1K("income_1k", "收入破千", "单年股息收入达到1,000元", "🌿", AchievementCategory.INCOME_MILESTONE),
    INCOME_10K("income_10k", "收入破万", "单年股息收入达到10,000元", "🌳", AchievementCategory.INCOME_MILESTONE),
    INCOME_100K("income_100k", "收入十万", "单年股息收入达到100,000元", "🏔️", AchievementCategory.INCOME_MILESTONE),
    PORTFOLIO_START("portfolio_start", "持仓起步", "开始关注第一只股票", "🚩", AchievementCategory.INVESTMENT_STRATEGY),
    DIVERSIFY_5("diversify_5", "分散投资", "同时持有5只以上股票", "🛡️", AchievementCategory.INVESTMENT_STRATEGY),
    HOLD_1Y("hold_1y", "坚持持有", "最早添加的股票持有满一年", "💎", AchievementCategory.LONG_TERM_COMMITMENT),
    STREAK_3Y("streak_3y", "连年分红", "连续3年有股息收入", "❄️", AchievementCategory.LONG_TERM_COMMITMENT),
    RECORD_10("record_10", "勤于记录", "累计记录10笔股息收入", "📝", AchievementCategory.RECORDING_HABIT),
    RECORD_50("record_50", "记录达人", "累计记录50笔股息收入", "📋", AchievementCategory.RECORDING_HABIT),
    SINGLE_100("single_100", "单笔突破", "单笔股息收入超过100元", "🚀", AchievementCategory.INCOME_BREAKTHROUGH),
    YOY_GROWTH_50("yoy_growth_50", "年年增长", "年度股息收入同比增长50%以上", "📈", AchievementCategory.INCOME_BREAKTHROUGH),
    STOCK_INCOME_1K("stock_income_1k", "股息王", "单只股票年度股息超过1,000元", "👑", AchievementCategory.INCOME_BREAKTHROUGH),
    SET_FIRE_GOAL("set_fire_goal", "确立目标", "设置FIRE财务自由目标", "🎯", AchievementCategory.GOAL_ACHIEVEMENT),
    FIRE_PROGRESS_10("fire_progress_10", "起步前行", "FIRE目标进度达到10%", "🏃", AchievementCategory.GOAL_ACHIEVEMENT),
    FIRE_PROGRESS_50("fire_progress_50", "半程之星", "FIRE目标进度达到50%", "🌟", AchievementCategory.GOAL_ACHIEVEMENT),
    COMPLETE_PROFILE("complete_profile", "完整档案", "所有持仓股票都填写了股数和成本价", "✅", AchievementCategory.DATA_COMPLETENESS),
    PORTFOLIO_10("portfolio_10", "投资全景", "同时持有10只以上有完整数据的股票", "📊", AchievementCategory.DATA_COMPLETENESS);
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/stock/dividend/viewmodel/AchievementDef.kt
git commit -m "feat: add 10 new achievement definitions"
```

---

### Task 3: Add New DAO Queries

**Files:**
- Modify: `app/src/main/java/com/stock/dividend/data/local/dao/DividendIncomeRecordDao.kt`

- [ ] **Step 1: Add StockYearlyIncome data class and 3 new queries**

Add the `StockYearlyIncome` data class after the existing `YearlyTotal` data class (line 10), and add 3 new `@Query` methods to the `DividendIncomeRecordDao` interface:

```kotlin
data class StockYearlyIncome(
    val stockCode: String,
    val year: Int,
    val total: Double
)
```

Add these methods inside the `DividendIncomeRecordDao` interface:

```kotlin
@Query("SELECT COUNT(*) FROM dividend_income_records")
fun observeRecordCount(): Flow<Int>

@Query("SELECT COALESCE(MAX(amount), 0.0) FROM dividend_income_records")
fun observeMaxSingleIncome(): Flow<Double>

@Query("SELECT stockCode, year, SUM(amount) as total FROM dividend_income_records WHERE stockCode IS NOT NULL GROUP BY stockCode, year")
fun observePerStockYearlyIncome(): Flow<List<StockYearlyIncome>>
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/stock/dividend/data/local/dao/DividendIncomeRecordDao.kt
git commit -m "feat: add DAO queries for record count, max single income, and per-stock yearly income"
```

---

### Task 4: Add Repository Methods

**Files:**
- Modify: `app/src/main/java/com/stock/dividend/data/repository/DividendIncomeRepository.kt`

- [ ] **Step 1: Add wrapper methods for the 3 new DAO queries**

Add these methods to `DividendIncomeRepository`:

```kotlin
fun observeRecordCount(): Flow<Int> =
    incomeRecordDao.observeRecordCount()

fun observeMaxSingleIncome(): Flow<Double> =
    incomeRecordDao.observeMaxSingleIncome()

fun observePerStockYearlyIncome(): Flow<List<StockYearlyIncome>> =
    incomeRecordDao.observePerStockYearlyIncome()
```

Also add the import at the top:

```kotlin
import com.stock.dividend.data.local.dao.StockYearlyIncome
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/stock/dividend/data/repository/DividendIncomeRepository.kt
git commit -m "feat: add repository methods for record count, max income, and per-stock income"
```

---

### Task 5: Add observeForecastTotal to Repository

**Files:**
- Modify: `app/src/main/java/com/stock/dividend/data/repository/DividendIncomeRepository.kt`

- [ ] **Step 1: Add observeForecastTotal method**

This method combines the stock list with per-stock dividend data and ForecastCalculator to produce the total forecast annual income. Add to `DividendIncomeRepository`:

```kotlin
fun observeForecastTotal(): Flow<Double> =
    stockDao.observeAll().map { stocks ->
        stocks.filter { it.shares > 0 }.sumOf { stock ->
            val dividends = runCatching {
                // One-shot query — not reactive per-stock, acceptable for achievement checking
                dividendDao.getLatestDividendsForForecast(stock.code)
            }.getOrDefault(emptyList())
            val result = ForecastCalculator.calculateForecastIncome(
                dividends, stock.shares, stock.yieldPeriod.toIntOrNull() ?: 3
            )
            result?.avgCashPerShare?.let { it * stock.shares } ?: 0.0
        }
    }
```

Note: This requires `dividendDao.getLatestDividendsForForecast()` — a suspend function. Since `observeForecastTotal()` returns a `Flow`, we use `map` which is a coroutine context. However, `stockDao.observeAll()` emits on each stock change, and calling a suspend DAO inside `map` works but is not ideal for Room's reactive flow.

**Alternative (simpler) approach:** Use a non-reactive method that the ViewModel calls as a `StateFlow` initialized in `init`:

Actually, looking at how `HomeViewModel` does it (using `flatMapLatest` for per-stock dividend flows), the simplest correct approach for the achievement system is:

Add this import:
```kotlin
import kotlinx.coroutines.flow.map
```

Add this method to `DividendIncomeRepository`:

```kotlin
fun observeForecastTotal(): Flow<Double> =
    stockDao.observeAll().flatMapLatest { stocks ->
        val activeStocks = stocks.filter { it.shares > 0 }
        if (activeStocks.isEmpty()) {
            flowOf(0.0)
        } else {
            combine(
                activeStocks.map { stock ->
                    dividendDao.observeByStock(stock.code).map { dividends ->
                        val result = ForecastCalculator.calculateForecastIncome(
                            dividends, stock.shares, stock.yieldPeriod.toIntOrNull() ?: 3
                        )
                        result?.avgCashPerShare?.let { it * stock.shares } ?: 0.0
                    }
                }
            ) { incomes -> incomes.sum() }
        }
    }.distinctUntilChanged()
```

Additional imports needed:
```kotlin
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/stock/dividend/data/repository/DividendIncomeRepository.kt
git commit -m "feat: add observeForecastTotal to DividendIncomeRepository"
```

---

### Task 6: Extend CheckContext and Add Achievement Conditions

**Files:**
- Modify: `app/src/main/java/com/stock/dividend/viewmodel/AchievementChecker.kt`

- [ ] **Step 1: Write failing tests for the 10 new achievements**

Add these tests to `app/src/test/java/com/stock/dividend/viewmodel/AchievementCheckerTest.kt`:

First, update the `emptyCtx()` helper and add a new helper for the extended context:

```kotlin
private fun emptyCtx() = AchievementChecker.CheckContext(
    stocks = emptyList(),
    yearlyTotals = emptyMap(),
    hasAnyIncomeRecord = false,
    incomeRecordCount = 0,
    maxSingleIncome = 0.0,
    perStockYearlyIncome = emptyMap(),
    fireGoal = null,
    forecastTotal = 0.0
)
```

Then add these test methods:

```kotlin
// --- Recording Habit ---

@Test
fun `RECORD_10 unlocked when income record count reaches 10`() {
    val result = AchievementChecker.check(emptyCtx().copy(incomeRecordCount = 10))
    assertThat(result).contains("record_10")
}

@Test
fun `RECORD_10 not unlocked when income record count is 9`() {
    val result = AchievementChecker.check(emptyCtx().copy(incomeRecordCount = 9))
    assertThat(result).doesNotContain("record_10")
}

@Test
fun `RECORD_50 unlocked when income record count reaches 50`() {
    val result = AchievementChecker.check(emptyCtx().copy(incomeRecordCount = 50))
    assertThat(result).contains("record_50")
}

@Test
fun `RECORD_50 not unlocked when income record count is 49`() {
    val result = AchievementChecker.check(emptyCtx().copy(incomeRecordCount = 49))
    assertThat(result).doesNotContain("record_50")
}

// --- Income Breakthrough ---

@Test
fun `SINGLE_100 unlocked when max single income reaches 100`() {
    val result = AchievementChecker.check(emptyCtx().copy(maxSingleIncome = 150.0))
    assertThat(result).contains("single_100")
}

@Test
fun `SINGLE_100 not unlocked when max single income is 99`() {
    val result = AchievementChecker.check(emptyCtx().copy(maxSingleIncome = 99.0))
    assertThat(result).doesNotContain("single_100")
}

@Test
fun `YOY_GROWTH_50 unlocked when year-over-year growth exceeds 50 percent`() {
    val result = AchievementChecker.check(
        emptyCtx().copy(yearlyTotals = mapOf(2023 to 1000.0, 2024 to 1600.0))
    )
    assertThat(result).contains("yoy_growth_50")
}

@Test
fun `YOY_GROWTH_50 not unlocked when growth is exactly 50 percent`() {
    val result = AchievementChecker.check(
        emptyCtx().copy(yearlyTotals = mapOf(2023 to 1000.0, 2024 to 1500.0))
    )
    assertThat(result).doesNotContain("yoy_growth_50")
}

@Test
fun `YOY_GROWTH_50 not unlocked with only one year of data`() {
    val result = AchievementChecker.check(
        emptyCtx().copy(yearlyTotals = mapOf(2024 to 5000.0))
    )
    assertThat(result).doesNotContain("yoy_growth_50")
}

@Test
fun `STOCK_INCOME_1K unlocked when single stock yearly income reaches 1000`() {
    val result = AchievementChecker.check(
        emptyCtx().copy(
            perStockYearlyIncome = mapOf("sh.600000" to mapOf(2024 to 1200.0))
        )
    )
    assertThat(result).contains("stock_income_1k")
}

@Test
fun `STOCK_INCOME_1K not unlocked when single stock yearly income is 999`() {
    val result = AchievementChecker.check(
        emptyCtx().copy(
            perStockYearlyIncome = mapOf("sh.600000" to mapOf(2024 to 999.0))
        )
    )
    assertThat(result).doesNotContain("stock_income_1k")
}

// --- Goal Achievement ---

@Test
fun `SET_FIRE_GOAL unlocked when fire goal is set`() {
    val goal = FireGoalEntity(targetAmount = 50000.0)
    val result = AchievementChecker.check(emptyCtx().copy(fireGoal = goal))
    assertThat(result).contains("set_fire_goal")
}

@Test
fun `SET_FIRE_GOAL not unlocked when no fire goal`() {
    val result = AchievementChecker.check(emptyCtx())
    assertThat(result).doesNotContain("set_fire_goal")
}

@Test
fun `FIRE_PROGRESS_10 unlocked when progress reaches 10 percent`() {
    val goal = FireGoalEntity(targetAmount = 10000.0)
    val result = AchievementChecker.check(emptyCtx().copy(fireGoal = goal, forecastTotal = 1500.0))
    assertThat(result).contains("fire_progress_10")
}

@Test
fun `FIRE_PROGRESS_10 not unlocked when progress is below 10 percent`() {
    val goal = FireGoalEntity(targetAmount = 10000.0)
    val result = AchievementChecker.check(emptyCtx().copy(fireGoal = goal, forecastTotal = 500.0))
    assertThat(result).doesNotContain("fire_progress_10")
}

@Test
fun `FIRE_PROGRESS_50 unlocked when progress reaches 50 percent`() {
    val goal = FireGoalEntity(targetAmount = 10000.0)
    val result = AchievementChecker.check(emptyCtx().copy(fireGoal = goal, forecastTotal = 6000.0))
    assertThat(result).contains("fire_progress_50")
}

@Test
fun `FIRE_PROGRESS_50 not unlocked when progress is below 50 percent`() {
    val goal = FireGoalEntity(targetAmount = 10000.0)
    val result = AchievementChecker.check(emptyCtx().copy(fireGoal = goal, forecastTotal = 4000.0))
    assertThat(result).doesNotContain("fire_progress_50")
}

@Test
fun `FIRE progress achievements not unlocked when targetAmount is zero`() {
    val goal = FireGoalEntity(targetAmount = 0.0)
    val result = AchievementChecker.check(emptyCtx().copy(fireGoal = goal, forecastTotal = 1000.0))
    assertThat(result).doesNotContain("fire_progress_10")
    assertThat(result).doesNotContain("fire_progress_50")
}

@Test
fun `FIRE progress achievements not unlocked when fireGoal is null`() {
    val result = AchievementChecker.check(emptyCtx().copy(forecastTotal = 1000.0))
    assertThat(result).doesNotContain("fire_progress_10")
    assertThat(result).doesNotContain("fire_progress_50")
}

// --- Data Completeness ---

@Test
fun `COMPLETE_PROFILE unlocked when all held stocks have cost basis`() {
    val stocks = listOf(
        testStock.copy(shares = 100, costPerShare = 10.0),
        testStock.copy(code = "sh.600001", name = "股票2", shares = 200, costPerShare = 15.0)
    )
    val result = AchievementChecker.check(emptyCtx().copy(stocks = stocks))
    assertThat(result).contains("complete_profile")
}

@Test
fun `COMPLETE_PROFILE not unlocked when a held stock has zero cost basis`() {
    val stocks = listOf(
        testStock.copy(shares = 100, costPerShare = 10.0),
        testStock.copy(code = "sh.600001", name = "股票2", shares = 200, costPerShare = 0.0)
    )
    val result = AchievementChecker.check(emptyCtx().copy(stocks = stocks))
    assertThat(result).doesNotContain("complete_profile")
}

@Test
fun `COMPLETE_PROFILE not unlocked with no held stocks`() {
    val stocks = listOf(
        testStock.copy(shares = 0, costPerShare = 0.0)
    )
    val result = AchievementChecker.check(emptyCtx().copy(stocks = stocks))
    assertThat(result).doesNotContain("complete_profile")
}

@Test
fun `PORTFOLIO_10 unlocked when 10 stocks have complete data`() {
    val stocks = (1..10).map { i ->
        testStock.copy(code = "sh.60000$i", name = "股票$i", shares = 100 * i, costPerShare = 10.0 + i)
    }
    val result = AchievementChecker.check(emptyCtx().copy(stocks = stocks))
    assertThat(result).contains("portfolio_10")
}

@Test
fun `PORTFOLIO_10 not unlocked with only 9 complete stocks`() {
    val stocks = (1..9).map { i ->
        testStock.copy(code = "sh.60000$i", name = "股票$i", shares = 100 * i, costPerShare = 10.0 + i)
    }
    val result = AchievementChecker.check(emptyCtx().copy(stocks = stocks))
    assertThat(result).doesNotContain("portfolio_10")
}
```

Also add the import at the top of the test file:
```kotlin
import com.stock.dividend.data.local.entity.FireGoalEntity
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.stock.dividend.viewmodel.AchievementCheckerTest"` (or the Android Studio test runner)
Expected: Compilation errors because `CheckContext` doesn't have the new fields yet.

- [ ] **Step 3: Extend CheckContext and add condition branches**

Update `AchievementChecker.kt`:

```kotlin
package com.stock.dividend.viewmodel

import com.stock.dividend.data.local.entity.FireGoalEntity
import com.stock.dividend.data.local.entity.StockEntity

object AchievementChecker {
    data class CheckContext(
        val stocks: List<StockEntity>,
        val yearlyTotals: Map<Int, Double>,
        val hasAnyIncomeRecord: Boolean,
        val incomeRecordCount: Int = 0,
        val maxSingleIncome: Double = 0.0,
        val perStockYearlyIncome: Map<String, Map<Int, Double>> = emptyMap(),
        val fireGoal: FireGoalEntity? = null,
        val forecastTotal: Double = 0.0
    )

    fun check(ctx: CheckContext): Set<String> {
        val unlocked = mutableSetOf<String>()

        // --- Existing achievements ---
        if (ctx.hasAnyIncomeRecord) unlocked.add(AchievementDef.FIRST_DIVIDEND.id)

        val maxIncome = ctx.yearlyTotals.values.maxOrNull() ?: 0.0
        if (maxIncome >= 1_000) unlocked.add(AchievementDef.INCOME_1K.id)
        if (maxIncome >= 10_000) unlocked.add(AchievementDef.INCOME_10K.id)
        if (maxIncome >= 100_000) unlocked.add(AchievementDef.INCOME_100K.id)

        if (ctx.stocks.isNotEmpty()) unlocked.add(AchievementDef.PORTFOLIO_START.id)
        if (ctx.stocks.size >= 5) unlocked.add(AchievementDef.DIVERSIFY_5.id)

        val earliestAddedAt = ctx.stocks.minOfOrNull { it.addedAt }
        if (earliestAddedAt != null && earliestAddedAt > 0 &&
            System.currentTimeMillis() - earliestAddedAt >= 365L * 24 * 3600 * 1000
        ) {
            unlocked.add(AchievementDef.HOLD_1Y.id)
        }

        val years = ctx.yearlyTotals.keys.sorted()
        if (years.size >= 3) {
            var maxStreak = 1
            for (i in 1 until years.size) {
                if (years[i] == years[i - 1] + 1) maxStreak++ else maxStreak = 1
            }
            if (maxStreak >= 3) unlocked.add(AchievementDef.STREAK_3Y.id)
        }

        // --- Recording Habit ---
        if (ctx.incomeRecordCount >= 10) unlocked.add(AchievementDef.RECORD_10.id)
        if (ctx.incomeRecordCount >= 50) unlocked.add(AchievementDef.RECORD_50.id)

        // --- Income Breakthrough ---
        if (ctx.maxSingleIncome >= 100) unlocked.add(AchievementDef.SINGLE_100.id)

        val sortedYears = ctx.yearlyTotals.keys.sorted()
        if (sortedYears.size >= 2) {
            for (i in 1 until sortedYears.size) {
                val prev = ctx.yearlyTotals[sortedYears[i - 1]] ?: continue
                val curr = ctx.yearlyTotals[sortedYears[i]] ?: continue
                if (prev > 0 && curr > prev * 1.5) {
                    unlocked.add(AchievementDef.YOY_GROWTH_50.id)
                    break
                }
            }
        }

        val maxStockIncome = ctx.perStockYearlyIncome.values
            .flatMap { it.values }
            .maxOrNull() ?: 0.0
        if (maxStockIncome >= 1_000) unlocked.add(AchievementDef.STOCK_INCOME_1K.id)

        // --- Goal Achievement ---
        if (ctx.fireGoal != null) unlocked.add(AchievementDef.SET_FIRE_GOAL.id)
        if (ctx.fireGoal != null && ctx.fireGoal.targetAmount > 0) {
            val progress = ctx.forecastTotal / ctx.fireGoal.targetAmount
            if (progress >= 0.1) unlocked.add(AchievementDef.FIRE_PROGRESS_10.id)
            if (progress >= 0.5) unlocked.add(AchievementDef.FIRE_PROGRESS_50.id)
        }

        // --- Data Completeness ---
        val heldStocks = ctx.stocks.filter { it.shares > 0 }
        if (heldStocks.isNotEmpty() && heldStocks.all { it.costPerShare > 0 }) {
            unlocked.add(AchievementDef.COMPLETE_PROFILE.id)
        }
        if (heldStocks.count { it.costPerShare > 0 } >= 10) {
            unlocked.add(AchievementDef.PORTFOLIO_10.id)
        }

        return unlocked
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "com.stock.dividend.viewmodel.AchievementCheckerTest"`
Expected: All tests PASS (both old and new).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/stock/dividend/viewmodel/AchievementChecker.kt app/src/test/java/com/stock/dividend/viewmodel/AchievementCheckerTest.kt
git commit -m "feat: extend CheckContext and add 10 new achievement conditions with tests"
```

---

### Task 7: Update AchievementViewModel

**Files:**
- Modify: `app/src/main/java/com/stock/dividend/viewmodel/AchievementViewModel.kt`

- [ ] **Step 1: Add FireGoalRepository and expand combine block**

The combine block grows from 3 flows to 8. Use `kotlinx.coroutines.flow.combine` with the iterable overload to handle more than 5 flows.

```kotlin
package com.stock.dividend.viewmodel

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stock.dividend.data.local.entity.StockEntity
import com.stock.dividend.data.repository.AchievementRepository
import com.stock.dividend.data.repository.DividendIncomeRepository
import com.stock.dividend.data.repository.FireGoalRepository
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
data class AchievementItem(
    val def: AchievementDef,
    val unlocked: Boolean,
    val unlockedAt: Long? = null
)

@Stable
data class AchievementUiState(
    val achievements: List<AchievementItem> = emptyList(),
    val unlockedCount: Int = 0,
    val totalCount: Int = AchievementDef.entries.size,
    val isLoading: Boolean = true
)

@HiltViewModel
class AchievementViewModel @Inject constructor(
    private val achievementRepository: AchievementRepository,
    stockRepository: StockRepository,
    incomeRepository: DividendIncomeRepository,
    fireGoalRepository: FireGoalRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(AchievementUiState())
    val uiState: StateFlow<AchievementUiState> = _uiState.asStateFlow()

    private val stocksFlow = stockRepository.observeAllStocks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            combine(
                stocksFlow,
                incomeRepository.observeYearlyTotals(),
                achievementRepository.observeAll(),
                incomeRepository.observeRecordCount(),
                incomeRepository.observeMaxSingleIncome(),
                incomeRepository.observePerStockYearlyIncome(),
                fireGoalRepository.observeGoal(),
                incomeRepository.observeForecastTotal()
            ) { stocks, yearlyTotals, unlockedEntities, recordCount, maxSingle, perStockIncome, fireGoal, forecastTotal ->
                val hasIncome = yearlyTotals.isNotEmpty()
                val ctx = AchievementChecker.CheckContext(
                    stocks = stocks,
                    yearlyTotals = yearlyTotals.associate { it.year to it.total },
                    hasAnyIncomeRecord = hasIncome,
                    incomeRecordCount = recordCount,
                    maxSingleIncome = maxSingle,
                    perStockYearlyIncome = perStockIncome
                        .groupBy { it.stockCode }
                        .mapValues { (_, items) -> items.associate { it.year to it.total } },
                    fireGoal = fireGoal,
                    forecastTotal = forecastTotal
                )
                val qualified = AchievementChecker.check(ctx)

                // Sync new unlocks
                launch { achievementRepository.syncAchievements(qualified) }

                // Build UI items
                val unlockedMap = unlockedEntities.associateBy { it.id }
                val items = AchievementDef.entries.map { def ->
                    val entity = unlockedMap[def.id]
                    AchievementItem(
                        def = def,
                        unlocked = entity != null || def.id in qualified,
                        unlockedAt = entity?.unlockedAt
                    )
                }
                _uiState.value = AchievementUiState(
                    achievements = items,
                    unlockedCount = items.count { it.unlocked },
                    totalCount = items.size,
                    isLoading = false
                )
            }.collect {}
        }
    }
}
```

Note: The `combine` function used here is `kotlinx.coroutines.flow.combine` with 8 arguments, which is supported starting from kotlinx-coroutines 1.4+. The project uses Coroutines 1.9.0, so this is fine.

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/stock/dividend/viewmodel/AchievementViewModel.kt
git commit -m "feat: expand AchievementViewModel with 8-flow combine and FIRE goal support"
```

---

### Task 8: Build and Manual Test

**Files:** None (verification only)

- [ ] **Step 1: Build the project**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Run all unit tests**

Run: `./gradlew test`
Expected: All tests pass, including the new achievement checker tests.

- [ ] **Step 3: Commit any fixes if needed**

If build or test failures occur, fix and commit.

- [ ] **Step 4: Final commit (if not already committed)**

All changes should be committed by now. Verify with `git status`.
