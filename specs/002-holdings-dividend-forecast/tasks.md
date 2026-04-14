# Tasks: Holdings Dividend Forecast

**Input**: Design documents from `/specs/002-holdings-dividend-forecast/`
**Prerequisites**: plan.md (required), spec.md (required for user stories), data-model.md, contracts/, research.md

**Tests**: Tests are OPTIONAL - not explicitly requested in the feature specification.

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

## Path Conventions

- **Mobile**: `app/src/main/java/com/stock/dividend/` at repository root
- Resource files: `app/src/main/res/`
- Test files: `app/src/test/java/com/stock/dividend/`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: 数据库迁移和现有 Entity 扩展，为所有 User Story 提供基础

- [x] T001 Add `shares` (Int, default 0) and `yieldPeriod` (String, default "3") fields to StockEntity in app/src/main/java/com/stock/dividend/data/local/entity/StockEntity.kt
- [x] T002 [P] Add `updateShares(code, shares)` and `updateYieldPeriod(code, period)` and `observeByCode(code)` queries to StockDao in app/src/main/java/com/stock/dividend/data/local/dao/StockDao.kt
- [x] T003 Update AppDatabase to version=2, add MIGRATION_1_2 (ALTER TABLE stocks ADD COLUMN shares INTEGER NOT NULL DEFAULT 0; ALTER TABLE stocks ADD COLUMN yieldPeriod TEXT NOT NULL DEFAULT '3'), register migration in DatabaseModule in app/src/main/java/com/stock/dividend/data/local/AppDatabase.kt and app/src/main/java/com/stock/dividend/di/DatabaseModule.kt
- [x] T004 [P] Add Chinese string resources for holdings and forecast labels (编辑持仓, 持有股数, 预测年度股息收入, 仅供参考, 暂无历史数据无法预测, 基于 X 年数据, 保存, 持仓数量, 股息率档位) in app/src/main/res/values/strings.xml

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Repository 层扩展，为 US1/US2/US3 提供数据操作方法

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

- [x] T005 Update StockRepository: modify `addStock()` to accept `shares: Int = 0` parameter, add `updateShares(code, shares)` and `updateYieldPeriod(code, period)` and `observeStock(code)` methods in app/src/main/java/com/stock/dividend/data/repository/StockRepository.kt

**Checkpoint**: Foundation ready - user story implementation can now begin

---

## Phase 3: User Story 1 - Record Stock Holdings (Priority: P1) MVP

**Goal**: 用户可以在添加股票时填写持有股数，在独立编辑页面修改持仓，卡片展示持仓数量

**Independent Test**: 添加一只股票并输入持仓数量，验证卡片显示持仓数量和详情页可编辑

### Implementation for User Story 1

- [x] T006 [US1] Update AddStockViewModel: add `shares` and `sharesError` to AddStockUiState, modify `addStock()` to pass shares parameter, add `onSharesChanged(shares: String)` with validation (non-negative integer) in app/src/main/java/com/stock/dividend/viewmodel/AddStockViewModel.kt
- [x] T007 [P] [US1] Update AddStockScreen: add OutlinedTextField for shares input (keyboard type number) after stock selection, before confirm button, with validation error display in app/src/main/java/com/stock/dividend/ui/screen/AddStockScreen.kt
- [x] T008 [US1] Create EditHoldingScreen Composable with TopAppBar (stock name), shares OutlinedTextField, yieldPeriod SegmentedButton (1年/3年/5年), Save button, Cancel button in app/src/main/java/com/stock/dividend/ui/screen/EditHoldingScreen.kt
- [x] T009 [US1] Create EditHoldingViewModel with stock state (from observeStock), shares input state, yieldPeriod state, saveHolding() that calls repository updateShares + updateYieldPeriod and navigates back in app/src/main/java/com/stock/dividend/viewmodel/EditHoldingViewModel.kt
- [x] T010 [US1] Update StockCard: add `shares: Int` and `forecastIncome: String?` parameters, display shares count and forecast income on the card in app/src/main/java/com/stock/dividend/ui/component/StockCard.kt
- [x] T011 [US1] Update StockDetailScreen: add "编辑持仓" button in TopAppBar area navigating to EditHoldingScreen, display shares count at top of detail content in app/src/main/java/com/stock/dividend/ui/screen/StockDetailScreen.kt
- [x] T012 [US1] Update AppNavigation: add `EDIT_HOLDING/{code}` route, wire StockDetailScreen "编辑持仓" → EditHoldingScreen in app/src/main/java/com/stock/dividend/ui/navigation/AppNavigation.kt
- [x] T013 [US1] Update HomeViewModel: collect shares from StockEntity for each stock, pass shares to HomeUiState, remove direct DividendDao injection (prepare for forecast in US2) in app/src/main/java/com/stock/dividend/viewmodel/HomeViewModel.kt
- [x] T014 [US1] Update HomeScreen: pass shares and forecastIncome=null (placeholder) to StockCard in app/src/main/java/com/stock/dividend/ui/screen/HomeScreen.kt

**Checkpoint**: At this point, User Story 1 should be fully functional - users can add stocks with shares, edit holdings on a separate screen, and see shares on cards

---

## Phase 4: User Story 2 - Forecast Dividend Income by Average Yield (Priority: P2)

**Goal**: 系统根据历史股息数据计算平均每股派息金额，乘以持仓数量预测年度股息收入，主页展示汇总

**Independent Test**: 添加股票并输入持仓，验证预测收入计算正确，主页汇总等于各股票预测之和

### Implementation for User Story 2

- [x] T015 [US2] Create ForecastCalculator utility object with `calculateAvgCashPerShare(dividends: List<DividendEntity>, years: Int): Pair<Double, Int>` (avgCashPerShare, actualYears), implementing year-dedup + arithmetic mean logic in app/src/main/java/com/stock/dividend/data/repository/ForecastCalculator.kt
- [x] T016 [US2] Update StockDetailViewModel: add forecast calculation using ForecastCalculator for all 3 periods (1/3/5), add `forecast: ForecastDetail?`, `allForecasts: Map<String, ForecastDetail>`, `selectedPeriod: String` to StockDetailUiState, add `updateYieldPeriod()` method in app/src/main/java/com/stock/dividend/viewmodel/StockDetailViewModel.kt
- [x] T017 [P] [US2] Update StockDetailScreen: add forecast section below dividend list showing selected period forecast income prominently, all 3 periods comparison, "仅供参考" disclaimer, "基于 X 年数据" label when data insufficient, "暂无历史数据" when no data in app/src/main/java/com/stock/dividend/ui/screen/StockDetailScreen.kt
- [x] T018 [US2] Update HomeViewModel: add `stockForecasts: Map<String, StockForecast>` and `forecastTotal: Double` to HomeUiState, calculate forecast per stock using ForecastCalculator + each stock's yieldPeriod, compute forecastTotal as sum of all forecasts in app/src/main/java/com/stock/dividend/viewmodel/HomeViewModel.kt
- [x] T019 [P] [US2] Update DividendSummaryCard: change title to "预测年度股息收入", display forecastTotal amount, add "仅供参考" disclaimer in app/src/main/java/com/stock/dividend/ui/component/DividendSummaryCard.kt
- [x] T020 [US2] Update HomeScreen: pass forecastIncome from stockForecasts to StockCard, update DividendSummaryCard with forecastTotal in app/src/main/java/com/stock/dividend/ui/screen/HomeScreen.kt

**Checkpoint**: At this point, User Stories 1 AND 2 should both work independently - users see forecast income on detail page and summary on home

---

## Phase 5: User Story 3 - Compare Yield Scenarios (Priority: P3)

**Goal**: 详情页同时展示1年/3年/5年平均股息率下的预测收入对比

**Independent Test**: 查看股票详情页，验证三种情景预测收入同时展示

### Implementation for User Story 3

- [x] T021 [US3] Create ForecastComparisonCard Composable showing 3-row comparison (1年/3年/5年) with period label, avgCashPerShare, forecastIncome per row, highlighting the selected period in app/src/main/java/com/stock/dividend/ui/component/ForecastComparisonCard.kt
- [x] T022 [US3] Update StockDetailScreen: integrate ForecastComparisonCard in the forecast section, ensure switching selected period updates the highlight and the main forecast display in app/src/main/java/com/stock/dividend/ui/screen/StockDetailScreen.kt
- [x] T023 [US3] Update EditHoldingScreen: add yield period selector (SegmentedButton with 1年/3年/5年) that loads and persists current selection, save updates yieldPeriod via repository in app/src/main/java/com/stock/dividend/ui/screen/EditHoldingScreen.kt

**Checkpoint**: All user stories should now be independently functional - users see multi-scenario comparison and can switch yield periods

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Improvements that affect multiple user stories

- [x] T024 [P] Update existing unit tests in StockRepositoryTest to cover new addStock with shares parameter, updateShares, updateYieldPeriod in app/src/test/java/com/stock/dividend/data/repository/StockRepositoryTest.kt
- [x] T025 [P] Create ForecastCalculatorTest with unit tests for: year dedup, 1/3/5 year averages, insufficient data (0 years, less than requested), zero shares, single year data in app/src/test/java/com/stock/dividend/data/repository/ForecastCalculatorTest.kt
- [x] T026 Verify quickstart.md scenarios: build APK, upgrade from v1, add stock with shares, verify forecast on detail page, verify summary on home page

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies - can start immediately
- **Foundational (Phase 2)**: Depends on Setup (T001-T003) completion - BLOCKS all user stories
- **User Stories (Phase 3+)**: All depend on Foundational phase completion
  - US1 (Phase 3): Can start after Phase 2
  - US2 (Phase 4): Depends on US1 (reuses StockCard, HomeViewModel, HomeScreen modifications)
  - US3 (Phase 5): Depends on US2 (uses ForecastCalculator and allForecasts state)
- **Polish (Phase 6)**: Depends on all user stories being complete

### User Story Dependencies

- **User Story 1 (P1)**: Can start after Foundational (Phase 2) - No dependencies on other stories
- **User Story 2 (P2)**: Depends on US1 (modifies same files: HomeViewModel, HomeScreen, StockDetailScreen, StockCard)
- **User Story 3 (P3)**: Depends on US2 (adds ForecastComparisonCard to the forecast section US2 created)

### Within Each User Story

- Models/Entities before Repository methods
- Repository methods before ViewModel
- ViewModel before Screen
- Core implementation before navigation wiring

### Parallel Opportunities

- T002 + T004 (different files: StockDao vs strings.xml)
- T007 + T008 (different files: AddStockScreen vs EditHoldingScreen)
- T017 + T019 (different files: StockDetailScreen vs DividendSummaryCard)
- T024 + T025 (different test files)

---

## Parallel Example: User Story 1

```bash
# Launch in parallel:
Task: T007 "Update AddStockScreen" and T008 "Create EditHoldingScreen"

# Sequential after above:
Task: T009 "Create EditHoldingViewModel"
Task: T010 "Update StockCard"
Task: T011 "Update StockDetailScreen"
Task: T012 "Update AppNavigation"
Task: T013 "Update HomeViewModel"
Task: T014 "Update HomeScreen"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup (T001-T004)
2. Complete Phase 2: Foundational (T005)
3. Complete Phase 3: User Story 1 (T006-T014)
4. **STOP and VALIDATE**: Test adding a stock with shares, editing holdings, viewing on card
5. Build APK and verify Room migration works on upgrade

### Incremental Delivery

1. Complete Setup + Foundational → Foundation ready
2. Add User Story 1 → Test independently → Build APK (MVP!)
3. Add User Story 2 → Test forecast calculation → Build APK
4. Add User Story 3 → Test multi-scenario comparison → Build APK
5. Polish → Final release APK

---

## Notes

- [P] tasks = different files, no dependencies
- [Story] label maps task to specific user story for traceability
- Each user story should be independently completable and testable
- Commit after each task or logical group
- Stop at any checkpoint to validate story independently
- All UI text MUST be in Chinese via strings.xml
- Room migration v1→v2 is critical for preserving existing user data
- ForecastCalculator is a pure utility (no Android dependencies) for easy unit testing
