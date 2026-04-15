# Research: Dividend Lazy Loading

**Feature**: 003-dividend-lazy-load
**Date**: 2026-04-15

## Research Tasks

### 1. 分页查询策略：按年份 vs 按条数

**Decision**: 按年份分页——每次加载一个完整年份的所有分红记录

**Rationale**:
- 用户对"5年"的理解是按年份而非条数。同一年可能有多次分红（中期、年末），按年份分组保证了同一年的记录不会被截断。
- 现有数据已按 `reportDate DESC` 排序，按年份切分天然适配。
- 数据量小（典型 10-30 条），Room 查询开销可忽略，分页逻辑可以完全在 ViewModel 内存中完成，无需修改 DAO。

**Alternatives considered**:
- **按条数分页（每页 N 条）**：简单但可能截断同一年份的多次分红记录，用户体验不佳。
- **Room 分页查询（Paging 3）**：引入 Jetpack Paging 库过度复杂。数据量极小，内存分页足以满足需求。
- **DAO 层 LIMIT/OFFSET 查询**：需要新增 DAO 方法，增加了不必要的复杂度。由于数据已在内存中（Flow 返回全部列表），在 ViewModel 层做切片更直接。

### 2. 无限滚动实现方式

**Decision**: 使用 LazyColumn 的 `LazyListState.canScrollForward` + `derivedStateOf` 检测滚动到底部

**Rationale**:
- 项目已使用 `LazyColumn` 显示分红数据，只需添加滚动监听。
- `LazyListState.layoutInfo.visibleItemsInfo` 可以判断最后一个可见 item 是否接近列表末尾。
- 使用 `LaunchedEffect` + `snapshotFlow` 监听滚动位置，触发加载更多。
- 这是 Compose 中无限滚动的标准模式。

**Alternatives considered**:
- **Pull-to-Refresh 式手动加载**：不符合"向下滑动自动加载"的需求。
- **"加载更多"按钮**：额外交互步骤，不如自动加载流畅。

### 3. 分页状态管理

**Decision**: 在 `StockDetailUiState` 中添加分页相关字段

**Rationale**:
- 现有 `StockDetailUiState` 已包含 `dividends` 列表和 `isLoading` 状态。
- 需要追踪：当前已加载到哪一年、是否还有更多数据、是否正在加载更多。
- 在现有 UiState 中添加字段是最简单直接的方式，符合 YAGNI 原则。
- 预测计算（`recalculateForecasts`）仍然使用全量 dividends 数据（从 Room Flow 获取），只有 UI 展示使用分页数据。

**Alternatives considered**:
- **独立的分页 StateFlow**：增加了一个需要协调的状态源，增加复杂度。
- **ViewModel 层分页数据持有**：在 ViewModel 内维护 `_allDividends`（全量）和 `_displayedDividends`（分页），但 Flow 已经提供了全量数据，只需在此基础上做切片。

### 4. 预测计算不受分页影响

**Decision**: 预测计算继续使用全量 dividends 数据

**Rationale**:
- 预测卡片（`ForecastMainCard`、`ForecastComparisonCard`）依赖于全部历史分红数据来计算平均值。
- 分页只影响 UI 展示层（分红记录列表），不影响业务逻辑。
- Room Flow 仍然提供全量数据，ViewModel 同时维护全量数据（用于预测）和分页数据（用于展示）。

### 5. 初始加载"5年"的精确含义

**Decision**: 取当前年份往前推 5 个自然年（如 2026 年则加载 2022-2026），包含这些年份的所有分红记录

**Rationale**:
- 与 spec 中的定义一致："当前年份往前推5个自然年"。
- `reportDate` 格式为 `"YYYY-MM-DD"`，取 `substringBefore("-")` 即可得到年份进行比较。
- 使用 `LocalDate.now().year` 获取当前年份。

**Alternatives considered**:
- **从最近一条记录的年份往前推 5 年**：如果当前年份没有分红记录，会导致初始显示空白。
- **固定加载前 N 条记录**：不能保证年份完整性。

## Key Technical Decisions Summary

| # | Decision | Approach |
|---|----------|----------|
| 1 | 分页策略 | ViewModel 内存分页，按年份分组，每次追加一年 |
| 2 | 滚动检测 | `LazyListState` + `LaunchedEffect`/`snapshotFlow` |
| 3 | 状态管理 | 扩展现有 UiState，增加分页字段 |
| 4 | 预测计算 | 不受影响，继续使用全量数据 |
| 5 | 5年定义 | 当前年份 - 5 到当前年份（含） |
