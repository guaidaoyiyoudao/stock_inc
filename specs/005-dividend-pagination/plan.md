# Implementation Plan: Dividend Record Pagination

**Branch**: `005-dividend-pagination` | **Date**: 2026-04-30 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `specs/005-dividend-pagination/spec.md`

## Summary

Add paginated display to the dividend record list on the stock detail screen. Show at most 5 records initially, with a "加载更多" button to load 5 more at a time. Pagination is UI-only — all dividend data is already in memory. Only two files need changes: `StockDetailViewModel.kt` (add visible count state) and `StockDetailScreen.kt` (slice the list and add load-more button).

## Technical Context

**Language/Version**: Kotlin 2.0
**Primary Dependencies**: Jetpack Compose, Material Design 3, Hilt, Coroutines + Flow
**Storage**: Room (existing `dividends` table, no schema changes)
**Testing**: JUnit + MockK + Truth (existing test setup)
**Target Platform**: Android (minSdk 24)
**Project Type**: Mobile app (single Activity + Composable Screens)
**Performance Goals**: Instantaneous (data already in memory)
**Constraints**: Offline-capable, no network calls for this feature
**Scale/Scope**: Single screen, 2 files modified

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|-----------|--------|-------|
| I. Modern Android Development | PASS | Kotlin + Compose + MVVM, no changes to architecture |
| II. Offline-first | PASS | Data already in memory, no network calls |
| III. Data Accuracy | PASS | No data transformation, only display slicing |
| IV. Simplicity | PASS | Two files changed, minimal state addition |
| V. User-friendly Error Handling | PASS | No error scenarios in this feature |
| Design Standards | PASS | Uses existing Material Design 3 patterns |
| Development Workflow | PASS | Follows existing patterns (ViewModel state + Compose UI) |

No violations. All gates PASS.

## Project Structure

### Documentation (this feature)

```text
specs/005-dividend-pagination/
├── plan.md              # This file
└── spec.md              # Feature specification
```

### Source Code (repository root)

```text
app/src/main/java/com/stock/dividend/
├── viewmodel/
│   └── StockDetailViewModel.kt    # Add visibleCount state
└── ui/screen/
    └── StockDetailScreen.kt       # Slice list, add load-more button

app/src/test/java/com/stock/dividend/
└── viewmodel/
    └── StockDetailViewModelTest.kt # Test pagination state
```

**Structure Decision**: Minimal change to existing MVVM architecture. ViewModel tracks how many items to show; Compose slices the list and renders a load-more button when items remain.

## Implementation Steps

### Step 1: Update StockDetailViewModel

**File**: `app/src/main/java/com/stock/dividend/viewmodel/StockDetailViewModel.kt`

Add pagination state to `StockDetailUiState`:
- `visibleCount: Int = 5` — number of dividend records currently displayed

Add to `StockDetailViewModel`:
- `loadMoreDividends()` method — increases `visibleCount` by 5, capped at total dividends size
- Reset `visibleCount` to 5 when dividends are refreshed (`refreshDividends()`)

### Step 2: Update StockDetailScreen

**File**: `app/src/main/java/com/stock/dividend/ui/screen/StockDetailScreen.kt`

In the dividend list `items()` block:
- Replace `count = uiState.dividends.size` with `count = min(uiState.visibleCount, uiState.dividends.size)`
- After the dividend items, add a "加载更多" button item when `visibleCount < dividends.size`

### Step 3: Test

Update `StockDetailViewModelTest.kt` to cover:
- Initial visibleCount is 5
- loadMoreDividends increases by 5
- loadMoreDividends caps at total size
- Refresh resets visibleCount to 5

## Complexity Tracking

No violations — no entries needed.
