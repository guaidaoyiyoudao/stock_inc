# Tasks: Dividend Record Pagination

**Input**: Design documents from `/specs/005-dividend-pagination/`
**Prerequisites**: plan.md (required), spec.md (required)

**Tests**: Tests are NOT explicitly requested in the feature specification. However, an existing test file `StockDetailViewModelTest.kt` exists and will be updated to cover new pagination state.

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2)
- Include exact file paths in descriptions

## Phase 1: Setup

**Purpose**: No setup tasks needed — existing project, no new dependencies or schema changes.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Add pagination state to ViewModel — required before any UI changes.

- [x] T001 [US1] Add `visibleCount: Int = 5` field to `StockDetailUiState` data class in `app/src/main/java/com/stock/dividend/viewmodel/StockDetailViewModel.kt`
- [x] T002 [US1] Add `loadMoreDividends()` method to `StockDetailViewModel` that increases `visibleCount` by 5, capped at `dividends.size` in `app/src/main/java/com/stock/dividend/viewmodel/StockDetailViewModel.kt`
- [x] T003 [US1] Reset `visibleCount` to 5 when dividends are refreshed (in the dividends-collecting coroutine) in `app/src/main/java/com/stock/dividend/viewmodel/StockDetailViewModel.kt`

**Checkpoint**: ViewModel pagination state ready — UI work can begin

---

## Phase 3: User Story 1 - Browse Dividend History Page by Page (Priority: P1) MVP

**Goal**: Transform unbounded dividend list into paginated display with "加载更多" button

**Independent Test**: Open stock detail with 12+ dividend records. Verify only 5 shown initially, tap "加载更多" to see 5 more, tap again for remainder. Verify no button when all loaded.

### Implementation for User Story 1

- [x] T004 [US1] Replace `count = uiState.dividends.size` with `count = min(uiState.visibleCount, uiState.dividends.size)` in the dividend LazyColumn items block in `app/src/main/java/com/stock/dividend/ui/screen/StockDetailScreen.kt`
- [x] T005 [US1] Add "加载更多" button item after dividend items (shown only when `visibleCount < dividends.size`) in `app/src/main/java/com/stock/dividend/ui/screen/StockDetailScreen.kt`

**Checkpoint**: User Story 1 fully functional — paginated dividend display with load-more button

---

## Phase 4: User Story 2 - See Total Dividend Count (Priority: P2)

**Goal**: Ensure existing count badge in "分红记录" section header shows total record count (not displayed count)

**Independent Test**: Open stock detail with 12 records showing 5, verify header badge shows "12"

### Implementation for User Story 2

- [x] T006 [US2] Verify "分红记录" section header count badge uses `uiState.dividends.size` (total), NOT `uiState.visibleCount` (displayed) in `app/src/main/java/com/stock/dividend/ui/screen/StockDetailScreen.kt`

**Checkpoint**: User Story 2 verified — total count badge works correctly alongside pagination

---

## Phase 5: Polish & Tests

**Purpose**: Update existing test file to cover pagination state

- [x] T007 Add test: `initial visibleCount is 5` in `app/src/test/java/com/stock/dividend/viewmodel/StockDetailViewModelTest.kt`
- [x] T008 Add test: `loadMoreDividends increases visibleCount by 5` in `app/src/test/java/com/stock/dividend/viewmodel/StockDetailViewModelTest.kt`
- [x] T009 Add test: `loadMoreDividends caps at total dividends size` in `app/src/test/java/com/stock/dividend/viewmodel/StockDetailViewModelTest.kt`
- [x] T010 Add test: `refreshing dividends resets visibleCount to 5` in `app/src/test/java/com/stock/dividend/viewmodel/StockDetailViewModelTest.kt`

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — nothing to do
- **Foundational (Phase 2)**: No dependencies — can start immediately
- **US1 (Phase 3)**: Depends on Phase 2 (ViewModel state must exist before UI uses it)
- **US2 (Phase 4)**: Depends on Phase 2 (needs ViewModel state to verify)
- **Polish (Phase 5)**: Depends on Phase 2 (tests verify ViewModel behavior)

### User Story Dependencies

- **US1 (P1)**: Depends on Phase 2 only — independent of other stories
- **US2 (P2)**: Depends on Phase 2 only — verification task, no code changes expected

### Within Each Phase

- T001 → T002 → T003 (sequential, same file, each builds on previous)
- T004 → T005 (sequential, same file, slicing before button)
- T006 (standalone verification)
- T007–T010 (can run in parallel, all marked [P])

### Parallel Opportunities

- T007, T008, T009, T010 can all run in parallel (independent test methods)

---

## Parallel Example: Phase 5 Tests

```bash
# Launch all test tasks together:
Task: "Add test: initial visibleCount is 5"
Task: "Add test: loadMoreDividends increases visibleCount by 5"
Task: "Add test: loadMoreDividends caps at total dividends size"
Task: "Add test: refreshing dividends resets visibleCount to 5"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 2: Add ViewModel pagination state (T001–T003)
2. Complete Phase 3: Update UI for paginated display (T004–T005)
3. **STOP and VALIDATE**: Test with stock having 12+ dividends
4. Deploy/demo if ready

### Full Delivery

1. Phase 2 → Phase 3 (MVP)
2. Phase 4 → Verify count badge (quick check)
3. Phase 5 → Add test coverage

---

## Notes

- [P] tasks = different files or independent test methods, no dependencies
- [Story] label maps task to specific user story for traceability
- Only 2 source files modified: `StockDetailViewModel.kt` and `StockDetailScreen.kt`
- 1 test file updated: `StockDetailViewModelTest.kt`
- No database changes, no new dependencies
- Commit after each phase completion
