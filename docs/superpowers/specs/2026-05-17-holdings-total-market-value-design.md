# Design: Holdings List Total Market Value

Date: 2026-05-17
Status: Approved for planning

## Overview

Show the portfolio total market value in the holdings list header on the watchlist screen. The app already calculates `HomeUiState.totalMarketValue` from quote prices and per-stock holdings, so this feature only changes the holdings-list presentation.

## Requirements

1. The holdings list header displays total market value on the right side.
2. The value uses the existing quote-derived `HomeUiState.totalMarketValue`.
3. If total market value is unavailable, there are no priced holdings, or the value is `null`, the UI displays `总市值 ¥0.00`.
4. The existing dividend summary card and quote refresh behavior remain unchanged.

## Design Decisions

- **Placement**: Display the value in the `持仓列表` header area, aligned to the right of the existing title/action row.
- **Text**: Use `总市值 ¥12,345.67` for available values and `总市值 ¥0.00` for missing values.
- **Data source**: Reuse `HomeUiState.totalMarketValue`; do not add database fields or new repository methods.
- **Refresh behavior**: Keep the existing pull-to-refresh quote loading. The header updates when `totalMarketValue` changes.
- **Empty and missing-price behavior**: Missing market value is represented as zero in the header, per product decision.

## UI Changes

`HomeScreen` currently renders the holdings section with `SectionHeader` for the title and add-stock action. The header will be extended or composed locally so it can show:

- Left: `持仓列表`
- Right: `总市值 ¥0.00` or the formatted market value
- Action: existing add-stock affordance remains available

The exact implementation should follow the existing `SectionHeader` pattern. If `SectionHeader` already supports a compact trailing area, extend it with an optional subtitle/value parameter. If that would make the shared component awkward, keep the change local to `WatchlistContent`.

## Data Flow

1. `HomeViewModel` fetches quote prices through the existing refresh flow.
2. `HomeUiState.totalMarketValue` holds the sum of priced positions.
3. `WatchlistContent` formats `uiState.totalMarketValue ?: 0.0`.
4. The holdings header renders the formatted string.

## Testing

Add focused tests for:

- `HomeViewModel` keeps using quote-derived prices to calculate `totalMarketValue`.
- The header formatting path displays `¥0.00` when the value is `null`.
- The header displays the formatted value when total market value is present.

If current Compose UI test coverage makes direct header assertions expensive, isolate the formatting in a small pure helper and test that helper, then verify the parameter is threaded through the watchlist UI.

## Out of Scope

- Persisting market prices.
- Changing quote fetch cadence or error handling.
- Changing per-stock card market value display.
- Adding total market value back to the dividend summary card.

## Self-Review

- Placeholder scan: no placeholders or TODO markers remain.
- Consistency: the requirements, UI behavior, and data flow all use `HomeUiState.totalMarketValue`.
- Scope: this is a small presentation feature for one screen section.
- Ambiguity: missing market value explicitly displays as `¥0.00`.
