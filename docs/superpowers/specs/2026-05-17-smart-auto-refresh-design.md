# Smart Auto-Refresh Design

## Context

The app currently requires manual pull-to-refresh to update stock prices. Users opening the app see stale prices from the last session. Additionally, `StockEntity.lastUpdated` is never written after initial insert, so the "updated at" display is misleading.

## Goals

1. Auto-refresh stock prices when the user returns to the app, but only if data is stale
2. Avoid unnecessary network requests during non-trading hours
3. Fix the race condition between forecast computation and quote fetching in HomeViewModel
4. Fix `lastUpdated` timestamp to reflect actual refresh time

## Non-Goals

- Background periodic refresh (WorkManager) — only foreground auto-refresh
- Dividend data auto-refresh — dividends change infrequently, manual refresh is sufficient
- Price persistence across process death — restart triggers a fresh fetch

---

## Design

### TTL Strategy

| Period | TTL | Behavior |
|--------|-----|----------|
| Trading hours (Mon–Fri 9:30–15:00 CST) | 5 minutes | Auto-refresh if stale |
| Non-trading hours (all other times) | ∞ | No auto-refresh, manual only |

The TTL timestamp is stored in `SharedPreferences` (key: `last_quote_refresh_ms`), surviving process restart.

### Trigger Flow

```
App resumes (ON_RESUME)
  → Check if within trading hours
    → YES: now - lastRefreshMs > 5 min?
      → YES: emit to _refreshTrigger → fetch quotes → update lastRefreshMs
      → NO: skip (data is fresh)
    → NO: skip (only manual pull-to-refresh works)

Manual pull-to-refresh:
  → Always triggers fetch → updates lastRefreshMs regardless of TTL
```

### Race Condition Fix

**Problem:** HomeViewModel launches two concurrent coroutines that both write to `_uiState`:

- Coroutine A: Reactively computes forecasts from Room Flows, writes `_uiState`
- Coroutine B: Triggered by `_refreshTrigger`, calls `fetchQuotes()`, overlays prices on forecasts, writes `_uiState`

If Coroutine B reads forecasts before the network call, and Coroutine A updates them during the network call, Coroutine B overlays prices on stale forecast data.

**Fix:** In Coroutine B, after `fetchQuotes()` returns, re-read `_uiState.value.stockForecasts` to get the latest forecasts (which may have been updated by Coroutine A during the network call), then overlay prices on the fresh forecasts. This is a ~3 line change.

### lastUpdated Fix

- Add `StockDao.updateLastUpdated(code: String, timestamp: Long)` 
- After a successful `fetchQuotes()`, call `updateLastUpdated` for each stock that had its price refreshed via `stockRepository.updateLastUpdated(code, now)`
- The `StockCard` composable already reads `stock.lastUpdated` and displays "更新于 MM-dd HH:mm"

### Files to Modify

| File | Change |
|------|--------|
| `viewmodel/HomeViewModel.kt` | Add ON_RESUME lifecycle observer; fix race condition by re-reading state after fetch; update lastUpdated after refresh |
| `data/local/dao/StockDao.kt` | Add `updateLastUpdated(code, timestamp)` method |
| `data/repository/StockRepository.kt` | Add `updateLastUpdated(code, timestamp)` wrapper |
| `ui/screen/HomeScreen.kt` | Pass lifecycle to ViewModel (or use `LifecycleEventEffect`) |

### Implementation Approach

1. Add `updateLastUpdated` to StockDao + StockRepository
2. In HomeViewModel.init, add a `LifecycleEventObserver` via `ProcessLifecycleOwner` or pass lifecycle from Composable
3. On `ON_RESUME`: check TTL, auto-trigger refresh if stale
4. In the quote refresh coroutine: re-read `_uiState.value.stockForecasts` after `fetchQuotes()` returns, overlay prices on that
5. After successful quote fetch: call `stockRepository.updateLastUpdated(code, now)` for each stock
6. Store/read refresh timestamp in SharedPreferences

### Edge Cases

- **First launch**: `lastRefreshMs` is 0 → always triggers refresh (stale by definition)
- **Network error during auto-refresh**: `lastRefreshMs` is NOT updated (retry on next resume)
- **Rapid resume/dismiss**: `_refreshTrigger` uses `conflate()` — multiple rapid triggers collapse to one
- **A shares trading calendar**: Simple weekday + time check is sufficient (no need for exact holiday calendar — the cost is one extra refresh on a holiday, which is harmless)

### Verification

1. Open app → verify price refresh happens automatically (first launch)
2. Pull-to-refresh → verify prices update + "更新于" timestamp updates
3. Switch to another app for 3 minutes (within trading hours) → switch back → verify no refresh (within TTL)
4. Manually advance `lastRefreshMs` to 6 minutes ago → switch back → verify auto-refresh triggers
5. Test outside trading hours → verify no auto-refresh
6. Unit test: verify `HomeViewModel` race condition fix with concurrent forecast updates
