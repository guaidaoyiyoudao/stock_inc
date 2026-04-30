# Tasks: Optimize Scroll Performance

**Input**: Design documents from `/specs/006-optimize-scroll-perf/`
**Prerequisites**: plan.md (required), spec.md (required), research.md, data-model.md

**Tests**: Not explicitly requested — test tasks are excluded. Verification via quickstart.md manual testing and existing unit tests.

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3, US4)
- Include exact file paths in descriptions

## Path Conventions

- **Android app**: `app/src/main/java/com/stock/dividend/`
- **Tests**: `app/src/test/java/com/stock/dividend/`

---

## Phase 1: User Story 1 - Smooth Stock List Scrolling (Priority: P1) 🎯 MVP

**Goal**: Eliminate jank on the home screen by consolidating HomeViewModel state emissions and optimizing StockCard rendering.

**Independent Test**: Add 20+ stocks, rapidly scroll the home screen list — should maintain 60fps with no visible stuttering. Trigger a quote refresh and verify scroll is not interrupted.

### Implementation for User Story 1

- [x] T001 [US1] Consolidate HomeViewModel state emissions in `app/src/main/java/com/stock/dividend/viewmodel/HomeViewModel.kt` — merge the 3 independent coroutines (FIRE goal collector line 72, stock/forecast collector line 85, quote fetcher line 131) into a single combined `StateFlow` using `combine()` so that a data change produces exactly 1 state emission instead of 2-3
- [x] T002 [P] [US1] Replace `collectAsState()` with `collectAsStateWithLifecycle()` in `app/src/main/java/com/stock/dividend/ui/screen/HomeScreen.kt` at line 55
- [x] T003 [P] [US1] Cache `SimpleDateFormat` in `app/src/main/java/com/stock/dividend/ui/component/StockCard.kt` — move the `SimpleDateFormat` from `formatTimestamp()` (line 164-167) to a companion object or `remember` block to avoid per-recomposition allocation

**Checkpoint**: Home screen scroll is smooth with 20+ stocks. Background refresh does not interrupt scroll. Verify via `adb shell dumpsys gfxinfo` — >95% frames < 16ms.

---

## Phase 2: User Story 2 - Smooth Dividend History Scrolling (Priority: P2)

**Goal**: Eliminate jank on the stock detail screen by merging dual observers and fixing the always-running refresh animation.

**Independent Test**: Open a stock with 50+ dividend records and scroll through the full detail page — should be smooth. Verify the refresh icon does not animate when idle.

### Implementation for User Story 2

- [x] T004 [US2] Merge dual observers in `app/src/main/java/com/stock/dividend/viewmodel/StockDetailViewModel.kt` — replace the two separate coroutines in `loadStock()` (line 53) and `observeDividends()` (line 67) with a single `combine()` flow that produces one `recalculateForecasts()` call per emission
- [x] T005 [P] [US2] Fix infinite animation in `app/src/main/java/com/stock/dividend/ui/screen/StockDetailScreen.kt` — modify `RefreshButton` (lines 431-457) so the `rememberInfiniteTransition` + `animateFloat` is only active when `isRefreshing == true`; when idle, render a static icon to eliminate per-frame recomposition
- [x] T006 [P] [US2] Replace `collectAsState()` with `collectAsStateWithLifecycle()` in `app/src/main/java/com/stock/dividend/ui/screen/StockDetailScreen.kt` at line 65

**Checkpoint**: Stock detail page scrolls smoothly with 50+ dividend records. Refresh icon is static when not refreshing. No per-frame recomposition on idle screen.

---

## Phase 3: User Story 3 - Smooth Search Results Scrolling (Priority: P3)

**Goal**: Improve search results list efficiency with lifecycle-aware collection and proper list keys.

**Independent Test**: Type a stock search query and scroll through results — list updates smoothly without re-render flicker.

### Implementation for User Story 3

- [x] T007 [US3] Add `key = { it.code }` to the LazyColumn items call in `app/src/main/java/com/stock/dividend/ui/screen/AddStockScreen.kt` at line 201 to enable efficient list diffing when search results update
- [x] T008 [P] [US3] Replace `collectAsState()` with `collectAsStateWithLifecycle()` in `app/src/main/java/com/stock/dividend/ui/screen/AddStockScreen.kt` at line 58

**Checkpoint**: Search results scroll smoothly and update without full list re-render flicker.

---

## Phase 4: User Story 4 - Efficient Background Behavior (Priority: P4)

**Goal**: Ensure remaining screens use lifecycle-aware state collection and all data classes have stability guarantees.

**Independent Test**: Navigate between screens (home → detail → add → home) — no lag when returning to a previously visited screen.

### Implementation for User Story 4

- [x] T009 [P] [US4] Replace `collectAsState()` with `collectAsStateWithLifecycle()` in `app/src/main/java/com/stock/dividend/ui/screen/EditHoldingScreen.kt` at line 52
- [x] T010 [P] [US4] Replace `collectAsState()` with `collectAsStateWithLifecycle()` in `app/src/main/java/com/stock/dividend/ui/screen/FireGoalSetupScreen.kt` at line 39

**Checkpoint**: Navigating between all screens is smooth. No CPU waste on background screens.

---

## Phase 5: Polish & Cross-Cutting Concerns

**Purpose**: Stability annotations and final verification.

- [x] T011 [P] Add `@Stable` annotation to `HomeUiState` and `StockForecast` in `app/src/main/java/com/stock/dividend/viewmodel/HomeViewModel.kt`
- [x] T012 [P] Add `@Stable` annotation to `StockDetailUiState` and `ForecastDetail` in `app/src/main/java/com/stock/dividend/viewmodel/StockDetailViewModel.kt`
- [x] T013 [P] Add `@Stable` annotation to `StockEntity` in `app/src/main/java/com/stock/dividend/data/local/entity/StockEntity.kt`
- [x] T014 [P] Add `@Stable` annotation to `DividendEntity` in `app/src/main/java/com/stock/dividend/data/local/entity/DividendEntity.kt`
- [x] T015 Run `./gradlew test` to verify all existing unit tests pass
- [x] T016 Build debug APK with `./gradlew assembleDebug` and verify no compilation errors
- [ ] T017 Verify scroll performance using quickstart.md validation steps (requires device/emulator)

---

## Dependencies & Execution Order

### Phase Dependencies

- **User Story 1 (Phase 1)**: No dependencies — can start immediately. This is the highest-impact change.
- **User Story 2 (Phase 2)**: No dependencies on US1 — touches different files. Can start in parallel.
- **User Story 3 (Phase 3)**: No dependencies on US1/US2 — touches different files. Can start in parallel.
- **User Story 4 (Phase 4)**: No dependencies on US1-US3 — touches different files. Can start in parallel.
- **Polish (Phase 5)**: Should run after all user stories to verify overall result.

### Within Each User Story

- ViewModel changes should be completed before corresponding screen changes (ensures state flow is correct before collection changes)
- Screen collection changes (`collectAsStateWithLifecycle`) are independent of ViewModel changes
- Component-level optimizations (SimpleDateFormat, key, animation) are independent of ViewModel/screen changes

### Parallel Opportunities

All user story phases (1-4) are independent and can run in parallel since they touch different files:

- **Phase 1** touches: `HomeViewModel.kt`, `HomeScreen.kt`, `StockCard.kt`
- **Phase 2** touches: `StockDetailViewModel.kt`, `StockDetailScreen.kt`
- **Phase 3** touches: `AddStockScreen.kt`
- **Phase 4** touches: `EditHoldingScreen.kt`, `FireGoalSetupScreen.kt`

Within each phase, tasks marked `[P]` can run in parallel.

---

## Parallel Example: All User Stories

```bash
# All 4 user stories can run in parallel (different files):

# Developer A: US1 (Home screen)
Task T001: "Consolidate HomeViewModel state emissions in HomeViewModel.kt"
Task T002: "Replace collectAsState in HomeScreen.kt"
Task T003: "Cache SimpleDateFormat in StockCard.kt"

# Developer B: US2 (Detail screen)
Task T004: "Merge dual observers in StockDetailViewModel.kt"
Task T005: "Fix infinite animation in StockDetailScreen.kt"
Task T006: "Replace collectAsState in StockDetailScreen.kt"

# Developer C: US3 (Search)
Task T007: "Add key to AddStockScreen.kt"
Task T008: "Replace collectAsState in AddStockScreen.kt"

# Developer D: US4 (Background)
Task T009: "Replace collectAsState in EditHoldingScreen.kt"
Task T010: "Replace collectAsState in FireGoalSetupScreen.kt"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: User Story 1 (HomeViewModel + HomeScreen + StockCard)
2. **STOP and VALIDATE**: Test home screen scroll with 20+ stocks
3. If scroll is smooth → most impactful improvement delivered

### Incremental Delivery

1. US1 → Test home screen scroll independently → Biggest impact delivered
2. US2 → Test detail screen scroll independently → Second biggest impact
3. US3 → Test search scroll independently → Minor improvement
4. US4 → Test background navigation → Completes lifecycle coverage
5. Polish → @Stable annotations + final verification

---

## Notes

- [P] tasks = different files, no dependencies
- [Story] label maps task to specific user story for traceability
- No new files are created — all changes are modifications to existing code
- No new dependencies — all optimizations use existing libraries (Lifecycle, Coroutines Flow)
- Existing unit tests must pass after changes — test assertions remain the same
- Commit after each user story phase for clean history
- Stop at any checkpoint to validate story independently
