# Tasks: Stock Dividend Tracker

**Input**: Design documents from `/specs/001-stock-dividend-tracker/`
**Prerequisites**: plan.md (required), spec.md (required for user stories), data-model.md, contracts/

**Tests**: Tests are OPTIONAL - not explicitly requested in the feature specification.

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

## Path Conventions

- **Mobile**: `app/src/main/java/com/stock/dividend/` at repository root
- Resource files: `app/src/main/res/`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Project initialization and basic Android structure

- [x] T001 Create Android project structure with Gradle Kotlin DSL, Version Catalog in gradle/libs.versions.toml, and single-module layout in build.gradle.kts and settings.gradle.kts
- [x] T002 [P] Configure dependencies in gradle/libs.versions.toml: Kotlin 2.0, Jetpack Compose BOM, Material 3, Hilt, Room, Retrofit, OkHttp, Navigation Compose, Coroutines
- [x] T003 [P] Create Application class with Hilt entry point in app/src/main/java/com/stock/dividend/StockDividendApp.kt
- [x] T004 [P] Create single Activity with NavHost placeholder in app/src/main/java/com/stock/dividend/MainActivity.kt
- [x] T005 [P] Configure AndroidManifest.xml with INTERNET permission and Application class reference in app/src/main/AndroidManifest.xml

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core infrastructure that MUST be complete before ANY user story can be implemented

**CRITICAL**: No user story work can begin until this phase is complete

- [x] T006 Create StockEntity Room entity with fields (code PK, name, marketCode, addedAt, lastUpdated) in app/src/main/java/com/stock/dividend/data/local/entity/StockEntity.kt
- [x] T007 [P] Create DividendEntity Room entity with fields (id PK, stockCode FK, reportDate, cashPerShare, dividendYield, exDividendDate, recordDate, planStatus) in app/src/main/java/com/stock/dividend/data/local/entity/DividendEntity.kt
- [x] T008 Create StockDao with insert (OR IGNORE), delete, observeAll (Flow) queries in app/src/main/java/com/stock/dividend/data/local/dao/StockDao.kt
- [x] T009 [P] Create DividendDao with insert (OR REPLACE), deleteByStockCode, observeByStock (Flow), deleteAll queries in app/src/main/java/com/stock/dividend/data/local/dao/DividendDao.kt
- [x] T010 Create AppDatabase (Room) with StockEntity, DividendEntity, foreign key CASCADE on stockCode in app/src/main/java/com/stock/dividend/data/local/AppDatabase.kt
- [x] T011 Create DatabaseModule Hilt module providing AppDatabase, StockDao, DividendDao in app/src/main/java/com/stock/dividend/di/DatabaseModule.kt
- [x] T012 Create StockSearchResponse DTO and DividendResponse DTO with Gson annotations matching East Money API JSON structure in app/src/main/java/com/stock/dividend/data/remote/dto/StockSearchResponse.kt and app/src/main/java/com/stock/dividend/data/remote/dto/DividendResponse.kt
- [x] T013 Create EastMoneyApi Retrofit interface with searchStocks() and getDividends() endpoints in app/src/main/java/com/stock/dividend/data/remote/EastMoneyApi.kt
- [x] T014 Create NetworkModule Hilt module providing OkHttpClient (with Referer interceptor, 10s connect/read timeout), GsonConverterFactory, Retrofit instance in app/src/main/java/com/stock/dividend/di/NetworkModule.kt
- [x] T015 Create Material Design 3 theme with Dynamic Color support, dark theme, and Chinese-compatible typography in app/src/main/java/com/stock/dividend/ui/theme/Theme.kt, Color.kt, Type.kt
- [x] T016 Create AppNavigation with NavHost defining routes (home, addStock, stockDetail/{code}) in app/src/main/java/com/stock/dividend/ui/navigation/AppNavigation.kt
- [x] T017 Create Chinese string resources (app name, labels, error messages, empty state text, data source label "数据来源: 东方财富") in app/src/main/res/values/strings.xml

**Checkpoint**: Foundation ready - user story implementation can now begin in parallel

---

## Phase 3: User Story 1 - Add Stock and View Dividend Data (Priority: P1) MVP

**Goal**: 用户可以搜索并添加股票,查看该股票的逐年股息收入明细

**Independent Test**: 添加一只股票(如"平安银行"),验证搜索、选择、股息数据展示是否正常

### Implementation for User Story 1

- [x] T018 [US1] Create StockRepository with searchStocks() (calls EastMoneyApi, filters A-stock results), addStock() (insert into Room), observeAllStocks() (Room Flow) in app/src/main/java/com/stock/dividend/data/repository/StockRepository.kt
- [x] T019 [P] [US1] Create DividendRepository with fetchAndCacheDividends() (calls EastMoneyApi, converts DTO→Entity with /10.0 cashPerShare, upserts into Room), observeDividends() (Room Flow) in app/src/main/java/com/stock/dividend/data/repository/DividendRepository.kt
- [x] T020 [US1] Create AddStockViewModel with search query state, debounce search (300ms), search results list, loading/error states with retry support, addStock() method (insert stock + fetch dividends) in app/src/main/java/com/stock/dividend/viewmodel/AddStockViewModel.kt
- [x] T020b [US1] Add network error handling with user-friendly Chinese messages and retry buttons to AddStockViewModel and DividendRepository in app/src/main/java/com/stock/dividend/viewmodel/AddStockViewModel.kt and app/src/main/java/com/stock/dividend/data/repository/DividendRepository.kt
- [x] T021 [US1] Create AddStockScreen Composable with search TextField, results LazyColumn (stock name + code), loading indicator, error message, "no results" state in app/src/main/java/com/stock/dividend/ui/screen/AddStockScreen.kt
- [x] T022 [US1] Create HomeViewModel with stocks list state (Room Flow), refresh dividends for all stocks, loading/error states in app/src/main/java/com/stock/dividend/viewmodel/HomeViewModel.kt
- [x] T023 [P] [US1] Create StockCard Composable showing stock name, code, latest dividend info in a Material 3 Card in app/src/main/java/com/stock/dividend/ui/component/StockCard.kt
- [x] T024 [P] [US1] Create EmptyStateView Composable with icon, text prompt, and CTA to add stock in app/src/main/java/com/stock/dividend/ui/component/EmptyStateView.kt
- [x] T025 [US1] Create HomeScreen Composable with TopAppBar, stock list LazyColumn using StockCard, FAB navigating to AddStockScreen, empty state using EmptyStateView, SwipeRefresh for data refresh in app/src/main/java/com/stock/dividend/ui/screen/HomeScreen.kt
- [x] T026 [US1] Create StockDetailViewModel loading single stock and its dividend records (Flow), grouped by report year in app/src/main/java/com/stock/dividend/viewmodel/StockDetailViewModel.kt
- [x] T027 [US1] Create StockDetailScreen Composable with TopAppBar (stock name), LazyColumn showing yearly dividend records (report date, cashPerShare, exDividendDate, planStatus), and footer with data source label ("数据来源: 东方财富") and lastUpdated timestamp in app/src/main/java/com/stock/dividend/ui/screen/StockDetailScreen.kt
- [x] T027b [US1] Add data source label ("数据来源: 东方财富") and lastUpdated display to StockCard Composable in app/src/main/java/com/stock/dividend/ui/component/StockCard.kt
- [x] T028 [US1] Wire up navigation: HomeScreen FAB → AddStockScreen, StockCard click → StockDetailScreen, back navigation in app/src/main/java/com/stock/dividend/ui/navigation/AppNavigation.kt

**Checkpoint**: At this point, User Story 1 should be fully functional - users can add stocks and view dividend data independently

---

## Phase 4: User Story 2 - View Total Dividend Income Summary (Priority: P2)

**Goal**: 主页顶部展示所有关注股票的股息收入汇总金额

**Independent Test**: 添加多只股票,验证首页顶部汇总金额是否等于所有股票股息之和

### Implementation for User Story 2

- [x] T029 [US2] Add observeTotalDividendSummary() SQL query to DividendDao returning Flow of sum(cashPerShare) grouped by stock with yearly totals in app/src/main/java/com/stock/dividend/data/local/dao/DividendDao.kt
- [x] T030 [US2] Create DividendSummaryCard Composable with large total amount display, yearly breakdown, and Material 3 styling in app/src/main/java/com/stock/dividend/ui/component/DividendSummaryCard.kt
- [x] T031 [US2] Add summary state to HomeViewModel, collect DividendDao summary Flow, update UI state in app/src/main/java/com/stock/dividend/viewmodel/HomeViewModel.kt
- [x] T032 [US2] Integrate DividendSummaryCard at top of HomeScreen LazyColumn, show "¥0.00" with prompt when no stocks in app/src/main/java/com/stock/dividend/ui/screen/HomeScreen.kt

**Checkpoint**: At this point, User Stories 1 AND 2 should both work independently - users see total dividend summary at top of home screen

---

## Phase 5: User Story 3 - Manage Stock Watchlist (Priority: P3)

**Goal**: 用户可以删除不再关注的股票,删除后汇总数据自动更新

**Independent Test**: 添加股票后删除,验证列表和汇总是否正确更新

### Implementation for User Story 3

- [x] T033 [US3] Add removeStock() method to StockRepository (delegates to StockDao delete, CASCADE removes dividends) in app/src/main/java/com/stock/dividend/data/repository/StockRepository.kt
- [x] T034 [US3] Add deleteStock() method to HomeViewModel with confirmation state, call removeStock() and show undo Snackbar in app/src/main/java/com/stock/dividend/viewmodel/HomeViewModel.kt
- [x] T035 [US3] Add swipe-to-delete (SwipeToDismiss) on StockCard in HomeScreen LazyColumn, with confirmation dialog and undo Snackbar in app/src/main/java/com/stock/dividend/ui/screen/HomeScreen.kt

**Checkpoint**: All user stories should now be independently functional - users can add, view, summarize, and delete stocks

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Improvements that affect multiple user stories

- [x] T036 [P] Add network error handling with user-friendly Chinese messages and retry buttons to StockRepository in app/src/main/java/com/stock/dividend/data/repository/StockRepository.kt (DividendRepository retry already implemented in T020b)
- [x] T037 [P] Add loading shimmer placeholders for stock list and dividend data in app/src/main/java/com/stock/dividend/ui/component/StockCard.kt
- [x] T038 [P] Add deep link support for stock detail screen in app/src/main/java/com/stock/dividend/ui/navigation/AppNavigation.kt
- [x] T039 Configure ProGuard rules for Retrofit, Room, and Hilt in app/proguard-rules.pro
- [x] T040 Verify quickstart.md scenarios: build APK, install on emulator, add stock, view dividends, check summary

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies - can start immediately
- **Foundational (Phase 2)**: Depends on Setup completion - BLOCKS all user stories
- **User Stories (Phase 3+)**: All depend on Foundational phase completion
  - User stories can then proceed in parallel (if staffed)
  - Or sequentially in priority order (P1 → P2 → P3)
- **Polish (Phase 6)**: Depends on all desired user stories being complete

### User Story Dependencies

- **User Story 1 (P1)**: Can start after Foundational (Phase 2) - No dependencies on other stories
- **User Story 2 (P2)**: Can start after US1 (reuses HomeViewModel and HomeScreen)
- **User Story 3 (P3)**: Can start after US1 (reuses HomeViewModel, HomeScreen, StockRepository)

### Within Each User Story

- Models before services
- Services before ViewModels
- ViewModels before Screens
- Core implementation before navigation wiring
- Story complete before moving to next priority

### Parallel Opportunities

- All Setup tasks marked [P] can run in parallel (T002-T005)
- T006 + T007 (entities) can run in parallel
- T008 + T009 (DAOs) can run in parallel
- T023 + T024 (UI components) can run in parallel
- T036 + T037 + T038 (polish tasks) can run in parallel

---

## Parallel Example: User Story 1

```bash
# Launch entity creation together:
Task: T018 "Create StockRepository" and T019 "Create DividendRepository"

# Launch UI components together:
Task: T023 "Create StockCard" and T024 "Create EmptyStateView"

# Sequential after above:
Task: T020 "Create AddStockViewModel"
Task: T021 "Create AddStockScreen"
Task: T022 "Create HomeViewModel"
Task: T025 "Create HomeScreen"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup (T001-T005)
2. Complete Phase 2: Foundational (T006-T017)
3. Complete Phase 3: User Story 1 (T018-T028)
4. **STOP and VALIDATE**: Test adding a stock and viewing dividends
5. Build APK and verify on device/emulator

### Incremental Delivery

1. Complete Setup + Foundational → Foundation ready
2. Add User Story 1 → Test independently → Build APK (MVP!)
3. Add User Story 2 → Test summary display → Build APK
4. Add User Story 3 → Test delete functionality → Build APK
5. Polish → Final release APK

---

## Notes

- [P] tasks = different files, no dependencies
- [Story] label maps task to specific user story for traceability
- Each user story should be independently completable and testable
- Commit after each task or logical group
- Stop at any checkpoint to validate story independently
- All UI text MUST be in Chinese via strings.xml
- Data source: East Money public HTTP API (no backend server)
