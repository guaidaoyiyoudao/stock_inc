# Tasks: Dividend Rate Chart

**Input**: Design documents from `/specs/007-dividend-rate-chart/`
**Prerequisites**: plan.md (required), spec.md (required for user stories), research.md, data-model.md, contracts/

**Tests**: Included for ViewModel derivation and quickstart validation because the feature specification defines independent test criteria and the plan calls for unit coverage of chart point derivation.

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (US1, US2, US3)
- Every task includes an exact file path

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Confirm existing chart and stock detail infrastructure before story implementation.

- [x] T001 Inspect existing Vico usage and line-layer imports in `app/src/main/java/com/stock/dividend/ui/component/IncomeTrendChart.kt`
- [x] T002 Inspect current dividend section layout and insertion point in `app/src/main/java/com/stock/dividend/ui/screen/StockDetailScreen.kt`
- [x] T003 [P] Inspect current stock detail state tests in `app/src/test/java/com/stock/dividend/viewmodel/StockDetailViewModelTest.kt`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Add shared UI state model and derivation support required by every story.

**CRITICAL**: No user story work can begin until this phase is complete.

- [x] T004 Add `DividendRatePoint` stable data class in `app/src/main/java/com/stock/dividend/viewmodel/StockDetailViewModel.kt`
- [x] T005 Add `dividendRatePoints: List<DividendRatePoint> = emptyList()` to `StockDetailUiState` in `app/src/main/java/com/stock/dividend/viewmodel/StockDetailViewModel.kt`
- [x] T006 Implement private annual dividend rate point derivation from `DividendEntity.dividendYield`, grouping same-year records by report year and summing valid values in `app/src/main/java/com/stock/dividend/viewmodel/StockDetailViewModel.kt`
- [x] T007 Wire derived `dividendRatePoints` into every `_uiState.value.copy(...)` path that updates dividends in `app/src/main/java/com/stock/dividend/viewmodel/StockDetailViewModel.kt`
- [x] T008 [P] Add unit tests for valid yield filtering, invalid yield filtering, same-year summing, and ascending year ordering in `app/src/test/java/com/stock/dividend/viewmodel/StockDetailViewModelTest.kt`

**Checkpoint**: Stock detail UI state exposes chart-ready dividend rate points and derivation is covered by unit tests.

---

## Phase 3: User Story 1 - View Dividend Rate Trend (Priority: P1) MVP

**Goal**: A stock with at least two valid dividend rate records shows a dividend rate line chart in the stock detail dividend section.

**Independent Test**: Open a stock detail page with multiple valid dividend rate records and confirm the dividend section presents connected rate points with clear period and percent values.

### Tests for User Story 1

- [x] T009 [P] [US1] Add ViewModel test for multiple valid dividend yields producing chart-eligible `dividendRatePoints` in `app/src/test/java/com/stock/dividend/viewmodel/StockDetailViewModelTest.kt`

### Implementation for User Story 1

- [x] T010 [P] [US1] Create `DividendRateChart` composable using MPAndroidChart via `AndroidView` in `app/src/main/java/com/stock/dividend/ui/component/DividendRateChart.kt`
- [x] T011 [US1] Add period-axis and percent-value formatting to `DividendRateChart` in `app/src/main/java/com/stock/dividend/ui/component/DividendRateChart.kt`
- [x] T012 [US1] Import and place the "分红率趋势" chart section above "分红记录" in `app/src/main/java/com/stock/dividend/ui/screen/StockDetailScreen.kt`
- [x] T013 [US1] Pass `uiState.dividendRatePoints` into `DividendRateChart` only when at least two valid points exist in `app/src/main/java/com/stock/dividend/ui/screen/StockDetailScreen.kt`
- [x] T014 [US1] Ensure existing forecast cards, refresh action, visible dividend record pagination, and `DividendRecordCard` behavior remain unchanged in `app/src/main/java/com/stock/dividend/ui/screen/StockDetailScreen.kt`

**Checkpoint**: User Story 1 is independently functional and the feature MVP is demoable.

---

## Phase 4: User Story 2 - Understand Sparse Or Missing Dividend Data (Priority: P2)

**Goal**: Stocks with zero, one, or partial dividend rate history show clear fallback states instead of a misleading empty chart.

**Independent Test**: Open stock detail pages with no valid dividend rates, exactly one valid dividend rate, and mixed missing values; confirm the dividend section communicates the state clearly.

### Tests for User Story 2

- [x] T015 [P] [US2] Add ViewModel test for null dividend yields producing an empty `dividendRatePoints` list in `app/src/test/java/com/stock/dividend/viewmodel/StockDetailViewModelTest.kt`
- [x] T016 [P] [US2] Add ViewModel test for exactly one valid dividend yield preserving the single point and percent value in `app/src/test/java/com/stock/dividend/viewmodel/StockDetailViewModelTest.kt`

### Implementation for User Story 2

- [x] T017 [P] [US2] Create `DividendRateFallbackCard` composable for unavailable and insufficient-trend states in `app/src/main/java/com/stock/dividend/ui/component/DividendRateChart.kt`
- [x] T018 [US2] Render the unavailable fallback when `uiState.dividends` is not empty and `uiState.dividendRatePoints` is empty in `app/src/main/java/com/stock/dividend/ui/screen/StockDetailScreen.kt`
- [x] T019 [US2] Render the single-value fallback with percent formatting when `uiState.dividendRatePoints.size == 1` in `app/src/main/java/com/stock/dividend/ui/screen/StockDetailScreen.kt`
- [x] T020 [US2] Keep the existing full-page "暂无股息数据" empty state unchanged when `uiState.dividends` is empty in `app/src/main/java/com/stock/dividend/ui/screen/StockDetailScreen.kt`

**Checkpoint**: User Story 2 handles sparse and missing dividend rate histories independently.

---

## Phase 5: User Story 3 - Compare Recent Dividend Rate Direction (Priority: P3)

**Goal**: The chart makes rising, falling, and stable recent dividend rate direction easy to scan without manual calculation.

**Independent Test**: Review stocks with rising, falling, flat, and sharply varying dividend rate histories and confirm the chart order, scale, and labels make the trend readable.

### Tests for User Story 3

- [x] T021 [P] [US3] Add ViewModel test that out-of-order dividend records produce ascending `dividendRatePoints` in `app/src/test/java/com/stock/dividend/viewmodel/StockDetailViewModelTest.kt`

### Implementation for User Story 3

- [x] T022 [US3] Tune line chart scaling and point markers so low, high, zero, rising, falling, and flat values remain visible in `app/src/main/java/com/stock/dividend/ui/component/DividendRateChart.kt`
- [x] T023 [US3] Add compact x-axis labels that avoid crowding for typical dividend histories in `app/src/main/java/com/stock/dividend/ui/component/DividendRateChart.kt`
- [x] T024 [US3] Add selected or inspected point detail display showing full period and percent value in `app/src/main/java/com/stock/dividend/ui/component/DividendRateChart.kt`
- [x] T025 [US3] Verify chart card spacing and text wrapping against the surrounding dividend section in `app/src/main/java/com/stock/dividend/ui/screen/StockDetailScreen.kt`

**Checkpoint**: User Story 3 improves scanability without changing source data or existing dividend record behavior.

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Validate behavior across tests, build, themes, and quickstart scenarios.

- [x] T026 [P] Run focused unit tests for `app/src/test/java/com/stock/dividend/viewmodel/StockDetailViewModelTest.kt` with `./gradlew testDebugUnitTest --tests com.stock.dividend.viewmodel.StockDetailViewModelTest`
- [x] T027 Run debug build for `app/build.gradle.kts` with `./gradlew assembleDebug`
- [ ] T028 [P] Manually validate quickstart scenarios in `specs/007-dividend-rate-chart/quickstart.md`
- [x] T029 [P] Check dark theme readability for the chart and fallback cards in `app/src/main/java/com/stock/dividend/ui/component/DividendRateChart.kt`
- [x] T030 Check mobile-width label readability and no overlap in `app/src/main/java/com/stock/dividend/ui/screen/StockDetailScreen.kt`
- [x] T031 Update implementation notes if behavior differs from plan in `specs/007-dividend-rate-chart/quickstart.md`

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies; can start immediately.
- **Foundational (Phase 2)**: Depends on Setup; blocks all user stories.
- **User Story 1 (Phase 3)**: Depends on Foundational; delivers MVP.
- **User Story 2 (Phase 4)**: Depends on Foundational; can be implemented after or alongside US1, but screen integration is easier after US1 placement exists.
- **User Story 3 (Phase 5)**: Depends on US1 chart component; can proceed after the basic chart renders.
- **Polish (Phase 6)**: Depends on desired user stories being complete.

### User Story Dependencies

- **US1 (P1)**: Starts after Phase 2; no dependency on US2 or US3.
- **US2 (P2)**: Starts after Phase 2; fallback UI integrates with the same section as US1.
- **US3 (P3)**: Depends on the `DividendRateChart` created for US1.

### Within Each User Story

- Tests should be added before or with implementation and should fail before the corresponding behavior exists.
- ViewModel/state tasks precede screen rendering tasks.
- Chart component tasks precede `StockDetailScreen` integration tasks.
- Validate each story at its checkpoint before moving to lower-priority work.

### Parallel Opportunities

- T003 can run in parallel with T001 and T002.
- T008 can run in parallel once T004-T006 are defined, while T007 wires state updates.
- T009 and T010 touch different files and can run in parallel.
- T015, T016, and T017 can run in parallel because they touch tests and component fallback code separately.
- T026, T028, and T029 can run in parallel during polish if a build is not actively modifying files.

---

## Parallel Example: User Story 1

```text
Task: "T009 [P] [US1] Add ViewModel test for multiple valid dividend yields producing chart-eligible dividendRatePoints in app/src/test/java/com/stock/dividend/viewmodel/StockDetailViewModelTest.kt"
Task: "T010 [P] [US1] Create DividendRateChart composable using MPAndroidChart via AndroidView in app/src/main/java/com/stock/dividend/ui/component/DividendRateChart.kt"
```

## Parallel Example: User Story 2

```text
Task: "T015 [P] [US2] Add ViewModel test for null dividend yields producing an empty dividendRatePoints list in app/src/test/java/com/stock/dividend/viewmodel/StockDetailViewModelTest.kt"
Task: "T016 [P] [US2] Add ViewModel test for exactly one valid dividend yield preserving the single point and percent value in app/src/test/java/com/stock/dividend/viewmodel/StockDetailViewModelTest.kt"
Task: "T017 [P] [US2] Create DividendRateFallbackCard composable for unavailable and insufficient-trend states in app/src/main/java/com/stock/dividend/ui/component/DividendRateChart.kt"
```

## Parallel Example: User Story 3

```text
Task: "T021 [P] [US3] Add ViewModel test that out-of-order dividend records produce ascending dividendRatePoints in app/src/test/java/com/stock/dividend/viewmodel/StockDetailViewModelTest.kt"
Task: "T022 [US3] Tune line chart scaling and point markers so low, high, zero, rising, falling, and flat values remain visible in app/src/main/java/com/stock/dividend/ui/component/DividendRateChart.kt"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1 setup.
2. Complete Phase 2 foundational state derivation.
3. Complete Phase 3 User Story 1.
4. Stop and validate the chart appears for stocks with two or more valid dividend rate records.

### Incremental Delivery

1. Add chart-ready state and tests.
2. Add the basic line chart for valid histories (US1).
3. Add sparse/missing-data fallbacks (US2).
4. Improve scanability and point details (US3).
5. Run quickstart validation and build checks.

### Single-Developer Strategy

Proceed sequentially in task order. Avoid working on `StockDetailScreen.kt` fallback integration before the basic chart section exists, because those tasks share the same layout area.

## Notes

- Do not modify `DividendEntity` schema or introduce a Room migration.
- Do not recalculate dividend yield from price or cash-per-share data.
- Preserve existing dividend record list pagination and forecast behavior.
- All user-facing text added by this feature should be Chinese.
