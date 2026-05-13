# Dividend Discount Valuation Page Design

## Summary

Add a dedicated stock-level valuation page that uses a dividend discount model to estimate intrinsic value per share. The page is opened from an individual stock detail screen, automatically derives its starting assumptions from that stock's dividend history and current quote, and lets the user adjust assumptions before reviewing the valuation result and projected cash flow detail.

## Goals

- Provide a focused valuation workflow for dividend investors.
- Use existing stock, dividend, and quote data so the first result appears without manual setup when data is available.
- Keep the stock detail page lightweight by linking to a separate valuation page instead of embedding the full tool.
- Make valuation assumptions visible and editable.
- Cover calculation behavior with unit tests independent of Compose UI.

## Non-Goals

- Do not add a bottom navigation tab for valuation.
- Do not implement free cash flow DCF in this version.
- Do not persist valuation scenarios or user-edited assumptions in this version.
- Do not add stock search or cross-stock comparison to the valuation page.

## User Flow

1. User opens a stock detail page.
2. User taps the dividend discount valuation entry.
3. App opens the valuation page for that stock.
4. App loads the stock record, dividend records, and current quote.
5. App calculates default assumptions and initial valuation.
6. User adjusts assumptions or taps a preset.
7. App recalculates the conclusion and cash flow detail immediately.

## Navigation

Add a route under the existing tab navigation:

- Route: `dividendValuation/{code}`
- Entry point: stock detail page action or card labeled `股息折现估值`
- Back behavior: returns to the stock detail page through `popBackStack()`

The root bottom navigation remains unchanged.

## Data Sources

- Stock record: `StockRepository.observeStock(code)`
- Dividend records: `DividendRepository.observeDividends(code)`
- Current quote: `StockRepository.fetchQuotes(listOf(stock))`

The current quote is optional. If quote loading fails, the page still calculates intrinsic value and safety buy price, but hides undervalued or overvalued comparison against market price.

## Default Assumptions

- Dividend basis: average annual cash dividend per share from the most recent 5 calendar years available in dividend records.
- Growth rate: `5.0%`
- Discount rate: `9.0%`
- Terminal growth rate: `2.0%`
- Projection years: `10`
- Margin of safety: `20.0%`

If fewer than 5 dividend years exist, use all available years. If no dividend records exist, show an empty-data state and allow manual dividend basis input.

## Calculation Model

Create a pure calculator for dividend discount valuation. Inputs:

- `dividendBasisPerShare`
- `dividendGrowthRate`
- `discountRate`
- `terminalGrowthRate`
- `projectionYears`
- `marginOfSafety`
- `currentPrice`

Outputs:

- `intrinsicValuePerShare`
- `currentPrice`
- `discountOrPremiumPercent`
- `safetyBuyPrice`
- `valuationStatus`
- `cashFlowRows`
- `terminalValue`
- `discountedTerminalValue`

Formula:

- For each projected year `n`, dividend = previous dividend times `(1 + dividendGrowthRate)`.
- Discounted dividend = projected dividend divided by `(1 + discountRate)^n`.
- Terminal value at final year = final projected dividend times `(1 + terminalGrowthRate)` divided by `(discountRate - terminalGrowthRate)`.
- Discounted terminal value = terminal value divided by `(1 + discountRate)^projectionYears`.
- Intrinsic value = sum of discounted dividends plus discounted terminal value.
- Safety buy price = intrinsic value times `(1 - marginOfSafety)`.
- Discount or premium percent = `(intrinsicValue - currentPrice) / currentPrice`.

Validation:

- Dividend basis must be non-negative.
- Discount rate must be greater than terminal growth rate.
- Projection years must be clamped to `1..30`.
- Growth rate, discount rate, terminal growth rate, and margin of safety should be limited to `0.0%..50.0%`.

## Page Layout

Use the approved structure:

1. Top app bar with stock name or code and back navigation.
2. Valuation conclusion card:
   - Intrinsic value per share
   - Current price when available
   - Undervalued or overvalued percentage when current price is available
   - Safety buy price
   - Status pill
3. Assumption editor:
   - Dividend basis
   - Dividend growth rate
   - Discount rate
   - Terminal growth rate
   - Projection years
   - Margin of safety
   - Presets: conservative, base, optimistic
4. Future cash flow detail:
   - Year
   - Projected dividend
   - Discounted dividend
   - Terminal value row
   - Total row

Use the existing lightweight design system helpers where they match the page structure, including `CompactTopAppBar`, `SectionHeader`, `AppCardDefaults`, `FinanceMetric`, and `StatusPill`.

## Presets

Presets update only editable assumptions, not the stock, dividend history, or quote:

- Conservative: growth `2.0%`, discount `10.0%`, terminal growth `1.0%`, margin `25.0%`
- Base: growth `5.0%`, discount `9.0%`, terminal growth `2.0%`, margin `20.0%`
- Optimistic: growth `8.0%`, discount `8.0%`, terminal growth `3.0%`, margin `15.0%`

If a preset would make discount rate less than or equal to terminal growth rate after future changes, the UI should reject that state and show validation feedback instead of calculating.

## Empty and Error States

- No stock found: show a centered message and back action.
- No dividend records: show `缺少历史股息数据`, keep the assumption editor visible, and let the user enter dividend basis manually.
- Quote loading failed: show intrinsic value and safety buy price; omit the market comparison status.
- Invalid assumptions: show validation text near the assumption editor and do not update valuation results until the inputs are valid.

## Testing

Unit tests should cover the pure calculator:

- Calculates intrinsic value, terminal value, and safety buy price for a normal base case.
- Uses current price to produce undervalued and overvalued statuses.
- Rejects discount rate less than or equal to terminal growth rate.
- Clamps projection years to `1..30`.
- Builds cash flow rows in year order.

ViewModel tests should cover:

- Defaults dividend basis from the most recent 5 dividend years.
- Uses fewer available years when fewer than 5 exist.
- Allows manual dividend basis when no dividend records exist.
- Recalculates when assumptions change.
- Continues without market comparison when quote loading fails.

## Implementation Notes

Keep calculation code separate from Compose so it remains deterministic and easy to test. The page should follow existing navigation and screen patterns rather than introducing a new navigation layer. Existing user changes in stock detail and income timeline files should not be reverted.
