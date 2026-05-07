# Data Model: Dividend Rate Chart

**Feature**: 007-dividend-rate-chart  
**Date**: 2026-05-07

## Entity Changes

### DividendEntity (unchanged)

Existing persisted entity remains the authoritative source for dividend records.

| Field | Type | Use in Feature |
|-------|------|----------------|
| `id` | `String` | Stable record identity |
| `stockCode` | `String` | Associates record with the current stock detail page |
| `reportDate` | `String` | Source for annual grouping and chart point label |
| `cashPerShare` | `Double` | Existing dividend record display; not used for rate calculation |
| `dividendYield` | `Double?` | Dividend rate value plotted on the chart when non-null and valid |
| `exDividendDate` | `String?` | Existing record context; not required for chart plotting |
| `recordDate` | `String?` | Existing record context; not required for chart plotting |
| `planStatus` | `String?` | Existing record context; not required for chart plotting |

No Room migration is required.

### DividendRatePoint (new UI model)

Derived, non-persisted model used by the stock detail UI.

| Field | Type | Description |
|-------|------|-------------|
| `period` | `String` | Annual period shown in point details, e.g. `2024` |
| `label` | `String` | Compact x-axis label, matching the annual period |
| `ratePercent` | `Double` | Sum of all valid `dividendYield` values for the year |

### StockDetailUiState (modified)

Extend existing UI state with chart-ready data.

| Field | Type | Description |
|-------|------|-------------|
| `dividendRatePoints` | `List<DividendRatePoint>` | Valid annual dividend yield totals sorted by year ascending for chart rendering |

Existing fields remain unchanged:

| Field | Type | Notes |
|-------|------|-------|
| `stock` | `StockEntity?` | Existing stock context |
| `dividends` | `List<DividendEntity>` | Full dividend records used by existing record list and forecast logic |
| `isLoading` | `Boolean` | Existing initial loading state |
| `isRefreshing` | `Boolean` | Existing refresh state |
| `error` | `String?` | Existing error state |
| `forecast` | `ForecastDetail?` | Existing forecast display |
| `allForecasts` | `Map<String, ForecastDetail>` | Existing forecast comparison display |
| `selectedPeriod` | `String` | Existing forecast period selection |
| `visibleCount` | `Int` | Existing dividend record pagination count |

## Data Flow

```text
Room dividends table
    ↓ Flow<List<DividendEntity>>
DividendRepository.observeDividends(stockCode)
    ↓
StockDetailViewModel
    ├── preserves full dividends for existing record list and forecasts
    └── derives dividendRatePoints:
        1. discard records with null, NaN, infinite, or negative dividendYield
        2. group remaining records by reportDate year
        3. sum each year's valid dividendYield values
        4. sort annual points ascending by year
    ↓
StockDetailScreen
    ├── shows DividendRateChart when dividendRatePoints.size >= 2
    ├── shows single-value insufficient-trend message when size == 1
    └── shows unavailable message when size == 0
```

## Validation Rules

- `DividendRatePoint.ratePercent` must be the sum of valid `DividendEntity.dividendYield` values for the same year.
- Records with `dividendYield == null` are not plotted.
- Non-finite or negative dividend yield values are not plotted.
- Multiple valid dividend records in the same year produce one annual chart point.
- Chart points are ordered by year ascending.
- The chart is eligible for trend display only when at least two valid points exist.
- Single valid point state must display that rate value with a percent indicator.
- Empty state must explain that dividend rate trend data is unavailable.

## State Transitions

```text
[Initial page load]
    ↓
Dividend Flow emits records
    ↓
ViewModel derives dividendRatePoints
    ↓
if valid points >= 2 → chart visible
if valid points == 1 → single-value fallback visible
if valid points == 0 → unavailable fallback visible

[User refreshes dividends]
    ↓
Repository updates cached records
    ↓
Dividend Flow emits new records
    ↓
ViewModel re-derives dividendRatePoints from latest cached records
```
