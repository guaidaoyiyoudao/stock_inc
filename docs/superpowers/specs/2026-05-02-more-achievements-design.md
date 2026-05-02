# More Achievements Design

Date: 2026-05-02

## Overview

Add 10 new achievements across 4 new categories to the existing achievement system. The current system has 8 achievements in 3 categories (收入里程碑, 投资策略, 长期坚持). This spec adds 记录习惯, 收益突破, 目标达成, 数据完整 categories.

No database schema changes required — the `achievements` table stores arbitrary string IDs.

## New Categories

Added to `AchievementCategory` enum:

| ID | Icon | Title | Description |
|---|---|---|---|
| `recording_habit` | 📝 | 记录习惯 | 坚持记录每一笔股息 |
| `income_breakthrough` | 🚀 | 收益突破 | 追求更高的股息回报 |
| `goal_achievement` | 🎯 | 目标达成 | 向 FIRE 财务自由迈进 |
| `data_completeness` | ✅ | 数据完整 | 完善投资数据，掌控全局 |

## New Achievements

### 记录习惯 (recording_habit) — 2 achievements

| ID | Icon | Title | Description | Condition |
|---|---|---|---|---|
| `record_10` | 📝 | 勤于记录 | 累计记录10笔股息收入 | `incomeRecordCount >= 10` |
| `record_50` | 📋 | 记录达人 | 累计记录50笔股息收入 | `incomeRecordCount >= 50` |

### 收益突破 (income_breakthrough) — 3 achievements

| ID | Icon | Title | Description | Condition |
|---|---|---|---|---|
| `single_100` | 🚀 | 单笔突破 | 单笔股息收入超过100元 | `maxSingleIncome >= 100` |
| `yoy_growth_50` | 📈 | 年年增长 | 年度股息收入同比增长50%以上 | 使用现有 `yearlyTotals` 字段：存在连续两年，后一年 >= 前一年 × 1.5 |
| `stock_income_1k` | 👑 | 股息王 | 单只股票年度股息超过1,000元 | 某只股票某年总收入 >= 1000 |

### 目标达成 (goal_achievement) — 3 achievements

| ID | Icon | Title | Description | Condition |
|---|---|---|---|---|
| `set_fire_goal` | 🎯 | 确立目标 | 设置FIRE财务自由目标 | `fireGoal != null` |
| `fire_progress_10` | 🏃 | 起步前行 | FIRE目标进度达到10% | `fireGoal != null && fireGoal.targetAmount > 0 && forecastTotal / fireGoal.targetAmount >= 0.1` |
| `fire_progress_50` | 🌟 | 半程之星 | FIRE目标进度达到50% | `fireGoal != null && fireGoal.targetAmount > 0 && forecastTotal / fireGoal.targetAmount >= 0.5` |

Null safety: `fire_progress_10` and `fire_progress_50` require `fireGoal` to be non-null and `targetAmount > 0`. If `fireGoal` is null or `targetAmount` is 0, these achievements remain locked.

### 数据完整 (data_completeness) — 2 achievements

| ID | Icon | Title | Description | Condition |
|---|---|---|---|---|
| `complete_profile` | ✅ | 完整档案 | 所有持仓股票都填写了股数和成本价 | 至少有1只 `shares > 0` 的股票，且所有 `shares > 0` 的股票都有 `costPerShare > 0` |
| `portfolio_10` | 📊 | 投资全景 | 同时持有10只以上有完整数据的股票 | `stocks.count { shares > 0 && costPerShare > 0 } >= 10` |

Precondition: `complete_profile` requires at least one stock with `shares > 0` to avoid vacuously true on empty portfolio.

## Data Layer Changes

### CheckContext Extension

Add 4 new fields to the existing `CheckContext` data class in `AchievementChecker.kt`:

```kotlin
data class CheckContext(
    val stocks: List<StockEntity>,                            // existing
    val yearlyTotals: Map<Int, Double>,                       // existing
    val hasAnyIncomeRecord: Boolean,                          // existing
    val incomeRecordCount: Int,                               // NEW
    val maxSingleIncome: Double,                              // NEW
    val perStockYearlyIncome: Map<String, Map<Int, Double>>,  // NEW: stockCode -> year -> total
    val fireGoal: FireGoalEntity?,                            // NEW
    val forecastTotal: Double                                 // NEW
)
```

### New DAO Queries (3 queries in `DividendIncomeRecordDao`)

1. **Income record count** — `Flow<Int>`
   ```sql
   SELECT COUNT(*) FROM dividend_income_records
   ```

2. **Max single income** — `Flow<Double>` (uses COALESCE to avoid null on empty table)
   ```sql
   SELECT COALESCE(MAX(amount), 0.0) FROM dividend_income_records
   ```

3. **Per-stock yearly income** — `Flow<List<StockYearlyIncome>>` (new data class)
   ```sql
   SELECT stockCode, year, SUM(amount) as total FROM dividend_income_records WHERE stockCode IS NOT NULL GROUP BY stockCode, year
   ```

New data class for query 3 result:
```kotlin
data class StockYearlyIncome(
    val stockCode: String,
    val year: Int,
    val total: Double
)
```

The ViewModel maps `List<StockYearlyIncome>` to `Map<String, Map<Int, Double>>` for the `perStockYearlyIncome` field.

Note: `stockCode` is nullable in `DividendIncomeRecordEntity` (manual records may have null stockCode). The query filters `WHERE stockCode IS NOT NULL` to only include stock-linked records.

### forecastTotal Computation

`forecastTotal` is the total forecast annual dividend income across all stocks. It is computed by:

1. For each stock, call `ForecastCalculator.calculateForecastIncome(dividends, shares, yieldPeriod)` to get `avgCashPerShare`
2. Sum `avgCashPerShare * shares` for all stocks

This requires:
- Dividend data per stock (from `DividendDao.observeByStock()`)
- Stock entity data (shares, yieldPeriod — from existing `StockEntity`)

Implementation approach: Add a `observeForecastTotal(): Flow<Double>` method to `DividendIncomeRepository` (or a dedicated repository) that combines stock list + dividend data + ForecastCalculator. The ViewModel injects this repository and adds the flow to the combine block.

This is NOT a simple reuse of `ForecastCalculator` — it needs an aggregation wrapper. `ForecastCalculator` provides per-stock computation; the new code adds the summing logic.

### ViewModel Combine Block

The combine block grows from 3 flows to 8 flows. Since Kotlin's `combine` only natively supports up to 5 arguments, use the array-based overload:

```kotlin
combine(flows: Array<Flow<*>>, transform: (Array<Any?>) -> T)
```

Or use the `kotlinx.coroutines.flow.combine` iterable variant.

Flows to combine:
1. `stockRepository.observeAllStocks()` → `stocks` (existing)
2. `incomeRepository.observeYearlyTotals()` → `yearlyTotals` (existing)
3. `achievementRepository.observeAll()` → `unlockedAchievements` (existing)
4. `incomeRepository.observeRecordCount()` → `incomeRecordCount` (NEW)
5. `incomeRepository.observeMaxSingleIncome()` → `maxSingleIncome` (NEW)
6. `incomeRepository.observePerStockYearlyIncome()` → `perStockYearlyIncome` (NEW)
7. `fireGoalRepository.observeGoal()` → `fireGoal` (NEW)
8. `incomeRepository.observeForecastTotal()` → `forecastTotal` (NEW)

New constructor dependency: `AchievementViewModel` needs `FireGoalRepository` added as a constructor parameter for Hilt injection.

## Files to Modify

| File | Change |
|---|---|
| `viewmodel/AchievementCategory.kt` | Add 4 new category enum entries |
| `viewmodel/AchievementDef.kt` | Add 10 new achievement enum entries |
| `viewmodel/AchievementChecker.kt` | Extend `CheckContext` with 4 new fields, add 10 new condition branches |
| `viewmodel/AchievementViewModel.kt` | Add `FireGoalRepository` constructor param, expand combine to 8 flows using array-based combine |
| `data/local/dao/DividendIncomeRecordDao.kt` | Add 3 new queries with explicit return types |
| `data/repository/DividendIncomeRepository.kt` | Add wrapper methods for new DAO queries + `observeForecastTotal()` |
| `data/local/dao/DividendDao.kt` | May need adjustment if forecast total needs per-stock dividend queries |
| `ui/component/AchievementCard.kt` | No change (generic rendering) |
| `viewmodel/AchievementCheckerTest.kt` | Add unit tests for 10 new conditions |

## What Does NOT Change

- Database schema (no migration needed)
- `AchievementDao` / `AchievementRepository` (generic sync logic)
- UI components (generic rendering)
- Navigation
