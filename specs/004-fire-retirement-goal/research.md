# Research: FIRE Retirement Goal Progress

**Branch**: `004-fire-retirement-goal` | **Date**: 2026-04-16

## Research Topics

### R-001: Data Storage Strategy for FIRE Goal

**Decision**: New Room entity `fire_goal` in existing `stock_dividend.db`, migration v2 → v3.

**Rationale**:
- Constitution mandates Room for data persistence.
- Single-row table (one goal per user) is the simplest approach.
- Data stores preference (SharedPreferences/DataStore) rejected because the goal is a structured entity with timestamps, not a simple key-value preference. Room gives us type safety, migration support, and reactive Flow observation.

**Alternatives considered**:
- SharedPreferences: Simpler but no reactive observation, no structured timestamps.
- DataStore Preferences: Better than SharedPreferences but still flat key-value.
- Separate database file: Unnecessary complexity for a single entity.

### R-002: Progress Calculation Source

**Decision**: Reuse existing `HomeViewModel.forecastTotal` as the expected annual dividend income.

**Rationale**:
- `HomeViewModel` already computes `forecastTotal` as the sum of all per-stock forecast incomes (`shares * avgCashPerShare`).
- This is exactly the "预期年股息收入" defined in the spec.
- No new data aggregation logic needed — just observe `forecastTotal` and combine with `FireGoal.targetAmount` to compute percentage.

**Alternatives considered**:
- Separate calculation in a new UseCase: Unnecessary duplication of existing logic.
- Compute in Repository layer: Would bypass existing reactive Flow chain in HomeViewModel.

### R-003: UI Placement of FIRE Card

**Decision**: Insert `FireProgressCard` at the top of the HomeScreen `LazyColumn`, above the existing `DividendSummaryCard`.

**Rationale**:
- The spec says "主页顶部" — top of the home page.
- Placing above `DividendSummaryCard` gives it maximum visibility.
- The `DividendSummaryCard` shows total forecast income, which naturally relates to the FIRE progress above it.

**Alternatives considered**:
- Below DividendSummaryCard: Less prominent, contradicts spec.
- As a separate section/tab: Too much navigation overhead for a simple card.

### R-004: Navigation to Goal Setup Screen

**Decision**: Add `FIRE_GOAL_SETUP` route. Navigate from `FireProgressCard` tap (and from `DividendSummaryCard` area when no goal is set).

**Rationale**:
- Follows existing navigation pattern (string routes, `NavHost` in `AppNavigation.kt`).
- Tapping the FIRE card is the natural interaction for both setting and modifying the goal.
- No arguments needed — it's a single-user, single-goal scenario.

**Alternatives considered**:
- Dialog/BottomSheet for input: Screen provides more space and better UX for amount input + delete action.
- Settings page section: Would require creating a Settings infrastructure; overkill for one setting.

### R-005: Room Migration Strategy

**Decision**: Add `MIGRATION_2_3` that creates the `fire_goal` table.

**Rationale**:
- Standard Room migration approach already used in the project (MIGRATION_1_2 exists).
- The migration is a simple CREATE TABLE statement.

**SQL**:
```sql
CREATE TABLE IF NOT EXISTS `fire_goal` (
  `id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
  `target_amount` REAL NOT NULL,
  `created_at` INTEGER NOT NULL,
  `updated_at` INTEGER NOT NULL
)
```

**Alternatives considered**:
- `fallbackToDestructiveMigration()`: Would wipe all existing user data. Unacceptable.
- Export schema: Already configured in project.

### R-006: Input Validation for Target Amount

**Decision**: Validate on ViewModel layer — amount must be positive (> 0), with reasonable upper bound.

**Rationale**:
- Constitution principle V requires user-friendly error handling.
- Spec FR-002 requires positive number validation.
- ViewModel validation keeps UI logic out of Composable and allows unit testing.

**Validation rules**:
- Must be > 0
- Must be <= 999,999,999,999 (999 billion — reasonable upper bound)
- Must be a valid number (no empty, no non-numeric)

**Alternatives considered**:
- Composable-side validation only: Harder to test, mixes concerns.
- Repository-side validation: Validation is a use-case concern, not data layer.
