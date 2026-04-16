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

## Data Layer

### StockEntity Changes

Add `costPerShare: Double = 0.0` to `StockEntity`.

### Database Migration (v2 -> v3)

```sql
ALTER TABLE stocks ADD COLUMN costPerShare REAL NOT NULL DEFAULT 0.0;
```

### StockDao

Add `updateCostPerShare(code: String, costPerShare: Double)` method.

## Network Layer

### New QuoteApi Interface

- Endpoint: EastMoney batch quote API (`https://push2.eastmoney.com/api/qt/ulist.np/get`)
- Accept list of stock codes, return map of code -> current price
- Parse response fields for current price (f2 field in EastMoney API)

### NetworkModule

Register `QuoteApi` as a new Retrofit interface.

## Repository Layer

### StockRepository

Add `suspend fun fetchQuotes(codes: List<String>): Map<String, Double>` that calls `QuoteApi` and returns a code-to-price map.

## ViewModel Layer

### HomeViewModel

- Add `stockPrices: StateFlow<Map<String, Double>>` for in-memory price cache
- Fetch quotes when stock list loads and on pull-to-refresh
- Extend `StockForecast` data class with `currentPrice: Double?` and `marketValue: Double?`
- Calculate total market value across all stocks for summary card

### AddStockViewModel

- Add `costPerShareInput` and `costPerShareError` state fields
- Validate: non-negative, valid decimal number
- Pass `costPerShare` to repository when inserting stock

### EditHoldingViewModel

- Add `costPerShareInput` and `costPerShareError` state fields
- Pre-fill with existing `costPerShare` value
- Save via `updateCostPerShare` on confirm

## UI Layer

### StockCard Component

- Add `marketValue: Double?` parameter
- Display market value on the right side, above forecast income
- Show "--" when price unavailable

### AddStockScreen

- Add "Per-share cost (optional)" text field below shares input
- Support decimal input, non-negative validation

### EditHoldingScreen

- Add "Per-share cost" text field below shares input
- Pre-fill with current costPerShare value

### DividendSummaryCard

- Add "Total Market Value" row below forecast income total
- Sum of all stock market values, show "--" if any price missing

## Files Changed

- `data/local/entity/StockEntity.kt` - add costPerShare field
- `data/local/AppDatabase.kt` - version bump, migration
- `data/local/dao/StockDao.kt` - add updateCostPerShare
- `data/remote/QuoteApi.kt` - new file, quote API interface
- `data/remote/dto/QuoteResponse.kt` - new file, response DTO
- `data/repository/StockRepository.kt` - add fetchQuotes
- `di/NetworkModule.kt` - register QuoteApi
- `viewmodel/HomeViewModel.kt` - price cache, market value calc
- `viewmodel/AddStockViewModel.kt` - cost input state
- `viewmodel/EditHoldingViewModel.kt` - cost input state
- `ui/component/StockCard.kt` - show market value
- `ui/component/DividendSummaryCard.kt` - show total market value
- `ui/screen/AddStockScreen.kt` - cost input field
- `ui/screen/EditHoldingScreen.kt` - cost input field
- `ui/screen/HomeScreen.kt` - pass market value data
