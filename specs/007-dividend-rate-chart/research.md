# Research: Dividend Rate Chart

**Feature**: 007-dividend-rate-chart  
**Date**: 2026-05-07

## Research Tasks

### 1. Dividend rate data source

**Decision**: Use existing `DividendEntity.dividendYield` values as the chart's dividend rate source.

**Rationale**:
- The project already persists dividend yield per dividend record, matching the feature's "分红率" requirement.
- The constitution requires source data accuracy. Reusing stored values avoids recalculating or altering source data.
- No Room migration, DAO change, repository change, or network contract change is needed.

**Alternatives considered**:
- **Calculate yield from cash dividend and stock price**: rejected because it introduces new price timing assumptions and risks diverging from the source data.
- **Fetch a new dividend-yield endpoint**: rejected because existing cached data already contains the needed value and the app is offline-first.
- **Plot cash dividend per share instead**: rejected because the feature explicitly asks for dividend rate.

### 2. Chart data derivation location

**Decision**: Derive display-ready annual dividend rate points in `StockDetailViewModel`.

**Rationale**:
- The ViewModel already combines stock and dividend flows for the stock detail screen.
- Sorting, filtering null values, grouping same-year records, summing annual dividend rates, and deciding trend eligibility are presentation-state decisions that can be tested without Compose.
- Keeping the screen focused on rendering reduces duplicate data preparation if the chart component is reused later.

**Alternatives considered**:
- **Compute points directly inside `StockDetailScreen`**: simpler initially, but harder to unit test and increases screen logic.
- **Add repository methods for chart data**: unnecessary because chart points are a direct projection of already observed dividends.
- **Create a separate use case class**: rejected as premature abstraction for a single screen.

### 3. Chart library and visual pattern

**Decision**: Use MPAndroidChart inside a dedicated `DividendRateChart` Compose component via `AndroidView`.

**Rationale**:
- MPAndroidChart is an established open-source Android chart library with mature line-chart styling controls.
- A first Vico implementation rendered correctly but looked too plain for the desired modern financial UI. MPAndroidChart provides direct control over smoothing, fills, markers, grid lines, and axis density.
- A dedicated component keeps dividend rate chart behavior separate from income bar chart behavior while leaving existing Vico charts unchanged.

**Alternatives considered**:
- **Custom Canvas line chart**: rejected because it increases rendering, axis, label, and accessibility work.
- **Reuse Vico for the dividend chart**: initially implemented, then rejected after visual review because the default styling was not polished enough without deeper customization.
- **Reuse `IncomeTrendChart` directly**: rejected because it renders columns and models yearly income, not dividend rate points.

### 4. Fallback behavior for insufficient data

**Decision**: Show an explanatory Chinese fallback when fewer than two valid dividend rate records exist; show the single available value when exactly one valid value exists.

**Rationale**:
- A line trend requires at least two points.
- The spec requires users not to mistake missing data for poor dividend performance.
- Existing detail page already uses clear empty-state language, so this fits the current UX.

**Alternatives considered**:
- **Hide the chart silently**: rejected because users cannot tell whether data is missing or the feature failed.
- **Plot one point on a chart**: rejected because a single point does not communicate trend and may look broken.
- **Treat missing yields as zero**: rejected because that would be inaccurate and misleading.

### 5. Annual aggregation, ordering, and labels

**Decision**: Group valid dividend rate records by report year, sum each year's values, sort annual points ascending, and label points by year.

**Rationale**:
- Time-series charts read naturally left-to-right from older to newer periods.
- Stocks can distribute dividends multiple times in one year; summing those records gives users one annual dividend rate point instead of splitting the year into several points.
- The detail list can remain in its existing order; only chart projection changes ordering.
- Compact labels reduce crowding on mobile while point details still expose full period and percent value.

**Alternatives considered**:
- **Use list order directly**: rejected because repository/list order may be newest-first, which reverses trend direction visually.
- **Plot every dividend event separately**: rejected because same-year multiple dividends should be displayed as an annual total.
- **Group by year and average yields**: rejected because the requested display is a total for same-year dividends.
- **Use ex-dividend date only**: rejected because not all records necessarily have that date.

## Key Technical Decisions Summary

| # | Decision | Approach |
|---|----------|----------|
| 1 | Data source | Existing `DividendEntity.dividendYield` |
| 2 | Derivation | ViewModel creates sorted annual `DividendRatePoint` list |
| 3 | Rendering | New Compose `DividendRateChart` wrapping MPAndroidChart |
| 4 | Fallback | Chinese empty/insufficient-state message, single value shown when available |
| 5 | Aggregation and ordering | Group by report year, sum same-year values, plot annual points ascending |
