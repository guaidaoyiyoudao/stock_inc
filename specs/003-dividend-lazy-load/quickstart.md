# Quickstart: Dividend Lazy Loading

**Feature**: 003-dividend-lazy-load
**Date**: 2026-04-15

## Overview

将股票详情页分红记录从一次性全量显示改为按需分页加载。初始显示最近5年数据，滚动到底部自动追加更早年份。

## Files to Modify

| File | Change |
|------|--------|
| `app/src/main/java/com/stock/dividend/viewmodel/StockDetailViewModel.kt` | 添加分页状态字段、loadMore 方法、分页计算逻辑 |
| `app/src/main/java/com/stock/dividend/ui/screen/StockDetailScreen.kt` | 使用 displayedDividends 替代 dividends 渲染列表，添加无限滚动监听和加载指示器 |

## Implementation Steps

### Step 1: ViewModel 分页状态

在 `StockDetailUiState` 中添加：
- `displayedDividends: List<DividendEntity>` — 当前已加载的记录
- `isLoadingMore: Boolean` — 正在加载更多
- `hasMoreData: Boolean` — 是否还有更多数据

在 ViewModel 中：
- 维护 `_allDividends`（全量数据，来自 Room Flow）
- 当全量数据更新时，计算初始5年分页
- 添加 `loadMoreDividends()` 公开方法

### Step 2: Screen 无限滚动

在 `StockDetailScreen` 中：
- 将 `items(count = uiState.dividends.size)` 改为 `items(count = uiState.displayedDividends.size)`
- 添加 `LaunchedEffect` + `snapshotFlow` 监听滚动位置
- 在列表末尾添加条件性加载指示器 item
- 将 SectionHeader 的 count 改为 `uiState.displayedDividends.size`

### Step 3: 验证

- 打开有 >5 年分红记录的股票 → 确认只显示5年
- 滚动到底部 → 确认自动追加更早年份
- 打开 ≤5 年分红记录的股票 → 确认全部显示，无加载指示器
- 无分红记录 → 确认显示空状态
