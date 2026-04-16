# Data Model: FIRE Retirement Goal Progress

**Branch**: `004-fire-retirement-goal` | **Date**: 2026-04-16

## Entities

### FireGoalEntity (NEW)

Room entity representing the user's FIRE retirement income target.

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| id | Long | PK, AUTOINCREMENT | Row ID (always 1 for single-user) |
| targetAmount | Double | NOT NULL | Target annual income in CNY |
| createdAt | Long | NOT NULL | Creation timestamp (epoch millis) |
| updatedAt | Long | NOT NULL | Last modification timestamp (epoch millis) |

**Table name**: `fire_goal`

**Validation rules**:
- `targetAmount` must be > 0
- `targetAmount` must be <= 999,999,999,999
- Only one row expected (application-level constraint, enforced by repository)

**State transitions**:
- `NOT_SET` → `SET` : User creates a goal (INSERT)
- `SET` → `MODIFIED` : User changes target amount (UPDATE updatedAt)
- `SET`/`MODIFIED` → `NOT_SET` : User deletes the goal (DELETE)

## Existing Entities (NO CHANGES)

### StockEntity
Unchanged. `shares` field used indirectly via forecast calculation.

### DividendEntity
Unchanged. `cashPerShare` field used indirectly via forecast calculation.

## Derived / View-Only Models

### FireProgress (UI state, not persisted)

| Field | Type | Description |
|-------|------|-------------|
| targetAmount | Double | From FireGoalEntity |
| forecastTotal | Double | From existing HomeViewModel computation |
| percentage | Float | `min(forecastTotal / targetAmount * 100, 100f)` |
| isGoalSet | Boolean | Whether a FireGoal exists |
| isAchieved | Boolean | `percentage >= 100` |

## Relationships

```
FireGoalEntity (1) ←── no FK relations ──→ StockEntity / DividendEntity

Progress calculation:
  FireGoalEntity.targetAmount (denominator)
  +
  HomeViewModel.forecastTotal (numerator)
  = FireProgress.percentage
```

The FIRE goal is independent of stock/dividend entities. Progress is computed reactively by combining the goal with existing forecast data in the ViewModel layer.

## Room Migration

**Version**: 2 → 3

```sql
CREATE TABLE IF NOT EXISTS `fire_goal` (
  `id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
  `target_amount` REAL NOT NULL,
  `created_at` INTEGER NOT NULL,
  `updated_at` INTEGER NOT NULL
)
```

## DAO Operations

### FireGoalDao

| Method | Type | Query | Description |
|--------|------|-------|-------------|
| observe() | Flow\<FireGoalEntity?\> | SELECT * FROM fire_goal LIMIT 1 | Reactive observation of current goal |
| getOnce() | suspend FireGoalEntity? | SELECT * FROM fire_goal LIMIT 1 | One-shot read |
| insert(goal) | suspend Unit | INSERT INTO fire_goal | Create new goal |
| update(goal) | suspend Unit | UPDATE fire_goal SET ... | Modify existing goal |
| delete() | suspend Unit | DELETE FROM fire_goal | Remove goal |
