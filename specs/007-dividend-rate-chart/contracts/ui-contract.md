# UI Contract: Dividend Rate Chart

**Feature**: 007-dividend-rate-chart  
**Date**: 2026-05-07

## StockDetailUiState Contract

### New Field

| Field | Type | Default | Consumer |
|-------|------|---------|----------|
| `dividendRatePoints` | `List<DividendRatePoint>` | `emptyList()` | Dividend rate chart/fallback in `StockDetailScreen` |

### DividendRatePoint

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `period` | `String` | Yes | Annual period for point details |
| `label` | `String` | Yes | Compact x-axis label, usually the year |
| `ratePercent` | `Double` | Yes | Sum of valid dividend rate percentages for the year |

## Rendering Contract

| Condition | Required UI |
|-----------|-------------|
| `dividendRatePoints.size >= 2` | Show section header "分红率趋势" and render a line chart |
| `dividendRatePoints.size == 1` | Show section header "分红率趋势", display the single percentage value, and explain that history is insufficient to form a trend |
| `dividendRatePoints.isEmpty()` and dividends exist | Show section header "分红率趋势" and explain that no dividend rate trend data is available |
| `uiState.dividends.isEmpty()` | Existing full-page empty dividend state remains unchanged |

## Chart Interaction Contract

| Interaction | Expected Result |
|-------------|-----------------|
| User inspects a plotted point | The associated period and dividend rate percentage are identifiable |
| Screen is viewed in dark theme | Chart remains readable using Material color roles |
| Screen is viewed on narrow mobile width | Labels and values do not overlap existing dividend section content |

## Formatting Contract

- Dividend rate values display with a percent sign.
- Percent values use a consistent compact precision suitable for financial display, such as two decimal places.
- Axis labels use annual period labels to reduce crowding.
- Detailed point information uses the annual `period` and summed percent value where space allows.

## Non-Goals

- No changes to `DividendEntity` persistence schema.
- No changes to dividend refresh API behavior.
- No forecasted future dividend rate series.
- No recalculation of dividend rate from price or cash-per-share data.
