# UI State Contract: Dividend Lazy Loading

**Feature**: 003-dividend-lazy-load
**Date**: 2026-04-15

## StockDetailUiState

### Existing Fields (unchanged behavior)

| Field | Type | Consumer |
|-------|------|----------|
| `stock` | `StockEntity?` | HoldingInfoBanner, ForecastSection, DividendRecordCard |
| `dividends` | `List<DividendEntity>` | 预测计算（内部使用，不再直接用于 UI 渲染） |
| `isLoading` | `Boolean` | Loading 状态显示 |
| `error` | `String?` | 错误提示 |
| `forecast` | `ForecastDetail?` | ForecastMainCard |
| `allForecasts` | `Map<String, ForecastDetail>` | ForecastComparisonCard |
| `selectedPeriod` | `String` | ForecastMainCard, ForecastComparisonCard |

### New Fields

| Field | Type | Default | Consumer |
|-------|------|---------|----------|
| `displayedDividends` | `List<DividendEntity>` | `emptyList()` | DividendRecordCard 列表 |
| `isLoadingMore` | `Boolean` | `false` | 底部加载指示器 |
| `hasMoreData` | `Boolean` | `false` | 是否显示加载指示器 |

## ViewModel Public Interface

| Method | Signature | Description |
|--------|-----------|-------------|
| `loadMoreDividends()` | `Unit` | 追加下一个年份的分红数据到 displayedDividends |

## Screen Events

| Event | Trigger | Action |
|-------|---------|--------|
| 滚动到底部 | LazyColumn 最后可见 item 接近末尾 | 调用 `viewModel.loadMoreDividends()` |
| 数据更新 | Room Flow 发射新数据 | 重新计算分页边界和 displayedDividends |
