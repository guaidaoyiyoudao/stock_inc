# Design: Stock Cost Basis & Market Value Display

Date: 2026-04-16
Feature Branch: 004-fire-retirement-goal
Status: Approved

## Overview

Add per-share cost basis input to stock add/edit flows, fetch real-time stock prices via EastMoney quote API, and display total market value on the home page cards.

## Requirements

1. Users can enter per-share cost price when adding or editing a stock
2. Home page cards display each stock's market value (shares x current price)
3. Home page summary card displays total portfolio market value
4. Real-time prices fetched from EastMoney quote API

## Design Decisions

- **Approach**: Simple in-memory price cache (Approach A). No database persistence for prices.
- **Cost input**: Per-share cost price (not total cost). System calculates total cost = costPerShare x shares.
- **Price source**: EastMoney real-time quote API, fetched on load/refresh.
- **Offline behavior**: Market value shows "--" when price data unavailable.
- **Partial price failure**: Show partial sum of available prices in summary card. Do not block stock list display on quote failures. Quote fetch errors are silently swallowed (no error state).
- **Market value UX**: Individual cards show "--" for missing price. Summary card shows sum of available prices with no additional indicator — the total is approximate if any price is missing.

## Data Layer

### StockEntity Changes

Add `costPerShare: Double = 0.0` to `StockEntity`.

### Database Migration (v3 -> v4)

The database is currently at version 3 (MIGRATION_2_3 already exists for fire_goal table). This feature bumps to version 4.

```sql
ALTER TABLE stocks ADD COLUMN costPerShare REAL NOT NULL DEFAULT 0.0;
```

Register `MIGRATION_3_4` in `DatabaseModule.kt` alongside the existing migrations.

### StockDao

Add `updateCostPerShare(code: String, costPerShare: Double)` method. Follows the existing pattern of individual field update methods (same as `updateShares`, `updateYieldPeriod`).

## Network Layer

### Stock Code Format Conversion

The app uses format `sh.600519` / `sz.000001`. The EastMoney quote API uses format `1.600519` / `0.000001` (market prefix as integer).

Conversion rules:
- `sh.XXXXXX` -> `1.XXXXXX` (marketCode "1" from StockEntity)
- `sz.XXXXXX` -> `0.XXXXXX` (marketCode "0" from StockEntity)

The `StockEntity.marketCode` field already stores this value. Construct API secids as `${stock.marketCode}.${stock.code.substringAfter(".")}`.

Reverse mapping from API response: use `f12` (stock code digits) + `f13` (market number) -> app format `${if (f13 == "1") "sh" else "sz"}.$f12`.

### New QuoteApi Interface

- **Base URL**: `https://push2.eastmoney.com/`
- **Endpoint**: `GET api/qt/ulist.np/get`
- **Key parameters**:
  - `secids`: comma-separated list of market-prefixed codes (e.g., `1.600519,0.000001`)
  - `fields`: `f2,f12,f13` (current price, stock code, market number)
- Returns a JSON object with a `data.diff` array, each element containing `f2` (price), `f12` (code), `f13` (market).

### QuoteResponse DTO

```kotlin
data class QuoteResponse(
    val data: QuoteData?
)

data class QuoteData(
    val diff: List<QuoteItem>?
)

data class QuoteItem(
    @SerializedName("f2") val price: Double?,   // nullable: API returns -1 or "-" for suspended stocks
    @SerializedName("f12") val code: String,
    @SerializedName("f13") val market: Int
)
```

- `price` is nullable to handle Gson deserialization when API returns non-numeric sentinel values (e.g., `"-"`).
- Repository filters out items where `price == null || price <= 0`.

### NetworkModule

- Register `QuoteApi` as a third Retrofit interface with base URL `https://push2.eastmoney.com/`.
- The existing OkHttpClient interceptor sets Referer to `https://data.eastmoney.com/` for domains not matching `searchapi.eastmoney.com`. This is acceptable for `push2.eastmoney.com` — no interceptor changes needed.

## Repository Layer

### StockRepository

- Add `QuoteApi` as a constructor parameter (Hilt-injected).
- Add `suspend fun fetchQuotes(codes: List<String>): Map<String, Double>` that:
  - Converts app-format codes to API-format secids
  - Calls `QuoteApi`
  - Filters out items where `price == null || price <= 0` (handles suspended/delisted stocks)
  - Converts response back to app-format code-to-price map
  - Wraps API call in try-catch, returns empty map on failure
- **Update `addStock` signature**: add `costPerShare: Double = 0.0` parameter. Pass to `StockEntity` constructor.

## ViewModel Layer

### HomeViewModel

- Add `stockPrices: StateFlow<Map<String, Double>>` for in-memory price cache
- Fetch quotes when stock list loads and on pull-to-refresh
- Quote fetch failures are non-blocking — caught silently, `stockPrices` remains empty or stale
- Extend `StockForecast` data class with `currentPrice: Double? = null` and `marketValue: Double? = null`. Update all existing construction sites to include these new defaulted fields.
- `marketValue` calculation happens at ViewModel layer: `if (currentPrice != null && shares > 0) currentPrice * shares else null`
- Add `totalMarketValue: Double?` to `HomeUiState` — sum of non-null market values. `null` when no prices are available at all.
- **Update `undoDelete`**: call `addStock(result, shares = deleted.shares, costPerShare = deleted.costPerShare)` to preserve cost basis.

### AddStockViewModel

- Add `costPerShareInput` and `costPerShareError` state fields
- Validate: non-negative, valid decimal number
- Pass `costPerShare` to `stockRepository.addStock(...)` when inserting

### EditHoldingViewModel

- Add `costPerShareInput` and `costPerShareError` state fields
- Pre-fill with existing `costPerShare` value on load
- Save via `updateCostPerShare` on confirm

## UI Layer

### StockCard Component

- Add `marketValue: Double?` parameter
- Display market value on the right side, above forecast income
- Show "--" when `marketValue` is null

### HomeScreen

- Thread `marketValue` through `SwipeToDismissStockItem` to `StockCard`
- `SwipeToDismissStockItem` needs a new `marketValue: Double?` parameter
- Pass `totalMarketValue` from `HomeUiState` to `DividendSummaryCard`

### AddStockScreen

- Add "每股成本价（选填）" text field below shares input
- Support decimal input, non-negative validation

### EditHoldingScreen

- Add "每股成本价" text field below shares input
- Pre-fill with current costPerShare value

### DividendSummaryCard

- Add `totalMarketValue: Double? = null` parameter
- Add "持仓总市值" row below forecast income total
- Show formatted amount when value available, "--" when null

## Files Changed

- `data/local/entity/StockEntity.kt` - add costPerShare field
- `data/local/AppDatabase.kt` - version bump to 4, migration v3->v4
- `data/local/dao/StockDao.kt` - add updateCostPerShare
- `data/remote/QuoteApi.kt` - new file, quote API interface
- `data/remote/dto/QuoteResponse.kt` - new file, response DTO
- `data/repository/StockRepository.kt` - add QuoteApi constructor param, fetchQuotes, update addStock signature
- `di/NetworkModule.kt` - register QuoteApi with push2.eastmoney.com base URL
- `di/DatabaseModule.kt` - register MIGRATION_3_4
- `viewmodel/HomeViewModel.kt` - price cache, market value calc, undoDelete fix, HomeUiState totalMarketValue
- `viewmodel/AddStockViewModel.kt` - cost input state
- `viewmodel/EditHoldingViewModel.kt` - cost input state
- `ui/component/StockCard.kt` - show market value
- `ui/component/DividendSummaryCard.kt` - add totalMarketValue param, show 持仓总市值
- `ui/screen/AddStockScreen.kt` - cost input field
- `ui/screen/EditHoldingScreen.kt` - cost input field
- `ui/screen/HomeScreen.kt` - thread market value through SwipeToDismissStockItem, pass totalMarketValue to summary card
