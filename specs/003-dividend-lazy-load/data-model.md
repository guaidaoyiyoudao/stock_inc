# Data Model: Dividend Lazy Loading

**Feature**: 003-dividend-lazy-load
**Date**: 2026-04-15

## Entity Changes

### StockDetailUiState (modified)

现有 UiState 需要扩展以支持分页：

| Field | Type | Description |
|-------|------|-------------|
| `dividends` | `List<DividendEntity>` | 保留——全量数据，用于预测计算 |
| `displayedDividends` | `List<DividendEntity>` | **新增**——当前已加载到 UI 的分红记录（分页子集） |
| `isLoadingMore` | `Boolean` | **新增**——是否正在加载更多数据（区分于初始 `isLoading`） |
| `hasMoreData` | `Boolean` | **新增**——是否还有更早年份的数据未加载 |
| `loadedYearBoundary` | `Int?` | **新增**——已加载的最小年份，用于计算下次加载哪个年份 |

### DividendEntity (unchanged)

无修改。所有字段保持不变。

### DividendDao (unchanged)

无修改。继续使用 `observeByStock()` 返回全量 Flow。

## State Transitions

```text
[页面加载]
    ↓
observeDividends Flow 发射全量数据
    ↓
计算当前年份 - 5 得到年份边界
    ↓
displayedDividends = 全量数据中 year >= boundary 的记录
hasMoreData = (边界年份之前是否还有数据)
    ↓
[用户滚动到底部] AND hasMoreData AND !isLoadingMore
    ↓
isLoadingMore = true
    ↓
loadedYearBoundary -= 1（或取下一个更早年份）
    ↓
displayedDividends += 全量数据中 year == 新边界年份的记录
hasMoreData = 重新计算
isLoadingMore = false
```

## Data Flow

```text
Room DB (dividends table)
    ↓ Flow<List<DividendEntity>> (全量，按 reportDate DESC)
StockDetailViewModel
    ├── allDividends (全量) → recalculateForecasts() → 预测卡片
    └── displayedDividends (分页) → StockDetailScreen → 分红记录列表
```

## Validation Rules

- `displayedDividends` 始终是 `allDividends` 的子集（不超出）
- `displayedDividends` 中的记录按 `reportDate DESC` 排序
- 初始加载时 `displayedDividends` 包含最近 5 年的所有记录
- 每次"加载更多"追加一个完整年份的记录
- `hasMoreData` 只在 `displayedDividends.size == allDividends.size` 时为 false
