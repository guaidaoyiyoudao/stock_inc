# Quickstart: FIRE Retirement Goal Progress

**Branch**: `004-fire-retirement-goal` | **Date**: 2026-04-16

## Overview

This feature adds a FIRE retirement goal tracker to the existing stock dividend app. Users set a target annual income, and the home page displays a progress bar showing how close their expected annual dividend income is to that target.

## Implementation Order

1. **Data layer** (bottom-up, no UI dependency):
   - `FireGoalEntity` — Room entity
   - `FireGoalDao` — Room DAO
   - `MIGRATION_2_3` — Database migration
   - `AppDatabase` — Register new entity + DAO + migration
   - `DatabaseModule` — Provide new DAO via Hilt
   - `FireGoalRepository` — Repository with reactive Flow

2. **ViewModel layer**:
   - `FireGoalViewModel` — CRUD operations for setup screen
   - `HomeViewModel` — Add FIRE state, observe `FireGoalDao.observe()`

3. **UI layer**:
   - `FireProgressCard` — Reusable Composable card component
   - `FireGoalSetupScreen` — Full screen for input/modify/delete
   - `HomeScreen` — Insert `FireProgressCard` at top of LazyColumn
   - `AppNavigation` — Add `FIRE_GOAL_SETUP` route

## Key Files to Create

| File | Type | Purpose |
|------|------|---------|
| `data/local/entity/FireGoalEntity.kt` | NEW | Room entity |
| `data/local/dao/FireGoalDao.kt` | NEW | Room DAO |
| `data/repository/FireGoalRepository.kt` | NEW | Repository |
| `viewmodel/FireGoalViewModel.kt` | NEW | Setup screen ViewModel |
| `ui/component/FireProgressCard.kt` | NEW | Progress bar card |
| `ui/screen/FireGoalSetupScreen.kt` | NEW | Setup screen |

## Key Files to Modify

| File | Change |
|------|--------|
| `data/local/AppDatabase.kt` | Add entity, bump version to 3, add MIGRATION_2_3 |
| `di/DatabaseModule.kt` | Provide FireGoalDao |
| `viewmodel/HomeViewModel.kt` | Add FireGoal observation to UI state |
| `ui/screen/HomeScreen.kt` | Add FireProgressCard, pass navigation callback |
| `ui/navigation/AppNavigation.kt` | Add FIRE_GOAL_SETUP route |

## Reusing Existing Code

- **Forecast total**: `HomeViewModel.forecastTotal` already computes the expected annual dividend income — reuse directly as the FIRE progress numerator.
- **ForecastCalculator**: No changes needed; `forecastTotal` is the sum of all `ForecastCalculator` results.
- **Navigation pattern**: Follow existing `Routes` object pattern with string routes.
- **Theme**: Use existing `MaterialTheme.colorScheme` and custom `Jade`/`Gold` colors from `Color.kt`.

## Database Schema Change

```sql
-- Migration v2 → v3
CREATE TABLE IF NOT EXISTS `fire_goal` (
  `id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
  `target_amount` REAL NOT NULL,
  `created_at` INTEGER NOT NULL,
  `updated_at` INTEGER NOT NULL
)
```

## Testing Checklist

- [ ] Set FIRE target → persists after app restart
- [ ] Modify FIRE target → home page updates immediately
- [ ] Delete FIRE target → home page shows setup prompt
- [ ] Progress bar shows correct percentage
- [ ] Progress bar caps at 100% when forecast > target
- [ ] Amounts format correctly for large values (亿 unit)
- [ ] Dark mode renders correctly
- [ ] Back navigation from setup screen works
- [ ] Delete confirmation dialog appears
- [ ] Empty/invalid input shows validation error
