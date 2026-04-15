# Tasks: Dividend Lazy Loading

**Input**: Design documents from `/specs/003-dividend-lazy-load/`
**Prerequisites**: plan.md (required), spec.md (required), research.md, data-model.md, contracts/

**Tests**: Not explicitly requested. No test tasks included.

**Organization**: Tasks grouped by user story to enable independent implementation and testing.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

---

## Phase 1: Setup

**Purpose**: 无需项目初始化——在现有项目结构内修改。本 feature 不涉及新依赖、新模块或 schema 变更。

*No setup tasks required. All changes are in-place modifications to existing files.*

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: ViewModel 分页状态基础设施——所有 User Story 的前提

**CRITICAL**: 必须在 UI 层变更之前完成，因为 Screen 层依赖新的 UiState 字段

- [ ] T001 扩展 StockDetailUiState data class，添加分页字段：`displayedDividends: List<DividendEntity> = emptyList()`, `isLoadingMore: Boolean = false`, `hasMoreData: Boolean = false` in `app/src/main/java/com/stock/dividend/viewmodel/StockDetailViewModel.kt`

- [ ] T002 在 StockDetailViewModel 中实现分页计算逻辑：维护全量 dividends 用于预测计算，当 Room Flow 发射数据时计算初始5年边界并填充 `displayedDividends`，添加 `loadMoreDividends()` 公开方法按年份追加数据 in `app/src/main/java/com/stock/dividend/viewmodel/StockDetailViewModel.kt`

**Checkpoint**: ViewModel 分页状态就绪——UiState 包含 displayedDividends、isLoadingMore、hasMoreData，loadMoreDividends() 可调用

---

## Phase 3: User Story 1 - Default 5-Year Dividend Display (Priority: P1) MVP

**Goal**: 进入股票详情页时，分红记录区域只显示最近5年数据（或少于5年时全部显示）

**Independent Test**: 打开有 >5 年分红记录的股票，验证初始只显示5年数据；打开 ≤5 年记录的股票，验证全部显示

### Implementation for User Story 1

- [ ] T003 [US1] 修改 StockDetailScreen 的 LazyColumn，将分红记录 items 的数据源从 `uiState.dividends` 改为 `uiState.displayedDividends`，更新 items count 和 key 引用 in `app/src/main/java/com/stock/dividend/ui/screen/StockDetailScreen.kt`

- [ ] T004 [US1] 更新 SectionHeader 的 count 参数，从 `uiState.dividends.size` 改为 `uiState.displayedDividends.size` in `app/src/main/java/com/stock/dividend/ui/screen/StockDetailScreen.kt`

- [ ] T005 [US1] 更新 DividendRecordCard 中的 dividend 引用，从 `uiState.dividends[index]` 改为 `uiState.displayedDividends[index]`，isLast 判断也基于 displayedDividends in `app/src/main/java/com/stock/dividend/ui/screen/StockDetailScreen.kt`

- [ ] T006 [US1] 更新空状态判断条件，从 `uiState.dividends.isEmpty()` 改为 `uiState.displayedDividends.isEmpty()` in `app/src/main/java/com/stock/dividend/ui/screen/StockDetailScreen.kt`

**Checkpoint**: User Story 1 完成——初始加载只显示5年分红数据，≤5年时全部显示，空状态正常

---

## Phase 4: User Story 2 - Scroll-to-Load-More (Priority: P2)

**Goal**: 滑动到底部时自动追加更早年份的分红数据，显示加载指示器，全部加载完后停止

**Independent Test**: 打开有多于5年分红记录的股票，滚动到底部，确认自动追加更早年份数据并显示加载指示器

### Implementation for User Story 2

- [ ] T007 [US2] 添加 LazyColumn 滚动监听：使用 `rememberLazyListState()` + `LaunchedEffect` + `snapshotFlow` 检测最后一个可见 item 接近列表末尾时调用 `viewModel.loadMoreDividends()` in `app/src/main/java/com/stock/dividend/ui/screen/StockDetailScreen.kt`

- [ ] T008 [US2] 在 LazyColumn 末尾添加条件性加载指示器 item：当 `uiState.isLoadingMore` 为 true 时显示 `CircularProgressIndicator`，当 `uiState.hasMoreData && !uiState.isLoadingMore` 时不显示 in `app/src/main/java/com/stock/dividend/ui/screen/StockDetailScreen.kt`

**Checkpoint**: User Story 2 完成——滚动到底部自动加载，显示加载指示器，全部加载完后停止

---

## Phase 5: User Story 3 - 分红记录计数准确显示 (Priority: P3)

**Goal**: "分红记录"标题旁计数徽章反映当前已加载的记录数量，随加载动态更新

**Independent Test**: 初始加载5条记录时计数显示5，滚动加载更多后计数动态增长

### Implementation for User Story 3

- [ ] T009 [US3] 验证 SectionHeader 的 count 已使用 `uiState.displayedDividends.size`（T004 已完成此变更），确认随 loadMoreDividends() 调用后计数自动更新 in `app/src/main/java/com/stock/dividend/ui/screen/StockDetailScreen.kt`

**Checkpoint**: User Story 3 完成——计数徽章准确反映已加载数量并动态更新

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: 边界情况处理和最终验证

- [ ] T010 验证边界情况：无分红记录时空状态正常、同一年多次分红正确分组、快速滚动无重复加载、数据全部加载后不再触发 in `app/src/main/java/com/stock/dividend/ui/screen/StockDetailScreen.kt` and `app/src/main/java/com/stock/dividend/viewmodel/StockDetailViewModel.kt`

- [ ] T011 运行 quickstart.md 验证场景：>5年股票初始5年、滚动追加、≤5年全部显示、无记录空状态 in 真机或模拟器

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: 无需操作
- **Foundational (Phase 2)**: 必须首先完成——所有 UI 任务依赖新的 UiState 字段
- **User Story 1 (Phase 3)**: 依赖 Phase 2 完成
- **User Story 2 (Phase 4)**: 依赖 Phase 3 完成（滚动监听需要在已切换数据源的 LazyColumn 上工作）
- **User Story 3 (Phase 5)**: 依赖 Phase 3 完成（计数基于 displayedDividends）
- **Polish (Phase 6)**: 依赖所有 User Story 完成

### User Story Dependencies

- **US1 (P1)**: 依赖 Foundational → 无其他 story 依赖
- **US2 (P2)**: 依赖 US1 → 在 displayedDividends 数据源上添加滚动行为
- **US3 (P3)**: 依赖 US1 → 验证 T004 的计数变更生效

### Within Each User Story

- ViewModel 状态先于 UI 变更
- 数据源切换先于滚动监听
- 核心功能先于边界验证

### Parallel Opportunities

- T003, T004, T005, T006 可作为一个批次完成（同文件、紧密耦合）
- T007 和 T008 可作为一个批次完成（同文件、紧密耦合）

---

## Parallel Example: User Story 1

```bash
# T003-T006 are in the same file and tightly coupled - do as one batch:
Task: "T003-T006: Switch LazyColumn data source from dividends to displayedDividends, update SectionHeader count, DividendRecordCard references, and empty state condition in StockDetailScreen.kt"
```

## Parallel Example: User Story 2

```bash
# T007-T008 are in the same file and tightly coupled - do as one batch:
Task: "T007-T008: Add scroll detection and loading indicator to LazyColumn in StockDetailScreen.kt"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 2: Foundational (T001-T002)
2. Complete Phase 3: User Story 1 (T003-T006)
3. **STOP and VALIDATE**: 初始只显示5年数据
4. 可独立交付使用

### Incremental Delivery

1. Foundational → ViewModel 分页就绪
2. US1 → 5年默认显示（MVP）
3. US2 → 滚动加载更多
4. US3 → 计数动态更新
5. Polish → 边界验证

---

## Notes

- 所有变更集中在 2 个文件：`StockDetailViewModel.kt` 和 `StockDetailScreen.kt`
- 预测计算不受影响，继续使用全量 dividends 数据
- 无需修改 DAO、Entity 或 Repository
- 分页逻辑完全在内存中完成，无 I/O 开销
