# Research: Optimize Scroll Performance

**Date**: 2026-04-30
**Feature**: 006-optimize-scroll-perf

## Performance Analysis Findings

### 1. Cascading State Emissions in HomeViewModel

**Problem**: Three independent coroutines in `HomeViewModel.init` all write to `_uiState`, causing 2-3 rapid state emissions per data change:
1. FIRE goal collector (line 72) → updates `fireGoal`, `fireProgress`
2. Stock + forecast collector (line 85) → updates `stocks`, `stockForecasts`, `forecastTotal`, `fireProgress`
3. Quote fetcher (line 131) → updates `stockForecasts` again, `totalMarketValue`

Each emission triggers a full `HomeScreen` recomposition because `HomeUiState` is a single monolithic data class.

**Root Cause**: Separation of concerns between data sources is correct, but the state consolidation is missing — all three flows independently copy-modify `_uiState`, racing against each other.

**Decision**: Merge the three coroutines into a single combined `StateFlow` using Kotlin Flow's `combine()` operator. The ViewModel will derive a single unified state from all source flows, emitting once per coherent data update.

**Rationale**:
- Single emission per data change eliminates cascading recompositions
- `combine()` naturally handles multiple Flow sources
- The resulting code is simpler than managing 3 coroutines with manual state merges

**Alternatives Considered**:
- `distinctUntilChanged()` on each emission — helps but doesn't eliminate the cascading problem
- Debouncing with `conflate()` — adds latency, doesn't solve root cause
- Splitting UiState into multiple StateFlows — increases complexity without solving the coordination problem

### 2. Lifecycle-Unaware State Collection

**Problem**: All 5 screens use `collectAsState()` instead of `collectAsStateWithLifecycle()`. This means Flow emissions continue to trigger recompositions even when the screen is paused/stopped (e.g., during navigation to another screen).

**Root Cause**: The `lifecycle-runtime-compose` dependency is present (build.gradle.kts line 77) but `collectAsStateWithLifecycle()` was never adopted.

**Decision**: Replace `collectAsState()` with `collectAsStateWithLifecycle()` on all 5 screens.

**Rationale**:
- One-line change per screen
- Zero behavioral change for visible screens
- Eliminates all wasted recompositions for background screens
- Recommended by Android官方最佳实践

**Alternatives Considered**:
- Custom lifecycle-aware wrapper — unnecessary when official API exists
- Leaving as-is — wastes CPU and battery for no benefit

### 3. Dual Forecast Calculations in StockDetailViewModel

**Problem**: Two independent Room Flow observers (`loadStock()` and `observeDividends()`) both call `recalculateForecasts()`, causing 2+ rapid state emissions when the screen first loads.

**Root Cause**: Stock and dividend data come from separate Room tables with separate DAO queries, but the forecast depends on both. Each observer independently triggers recalculation when only one piece of data has arrived.

**Decision**: Use `combine()` to merge the stock and dividend flows into a single stream, then calculate forecasts once per combined emission.

**Rationale**:
- Guarantees forecasts are calculated with both stock and dividend data present
- Single emission per data change
- Simpler than coordinating two independent collectors

**Alternatives Considered**:
- `debounce()` after each observer — adds latency
- Flag-based deduplication — adds complexity

### 4. SimpleDateFormat Allocation in StockCard

**Problem**: `formatTimestamp()` (StockCard.kt line 164-167) creates a new `SimpleDateFormat` instance on every call. During scrolling, this allocates many short-lived objects that pressure the garbage collector.

**Root Cause**: `SimpleDateFormat` is called inside a private function invoked during composable rendering, not cached.

**Decision**: Move the formatter to a companion object or use `remember` at the call site to cache it.

**Rationale**:
- `SimpleDateFormat` construction is expensive (~1ms on some devices)
- During scroll with 5-8 visible cards, this creates 5-8 formatter instances per frame
- Single cached instance eliminates all allocations

**Alternatives Considered**:
- Pre-format the timestamp in the ViewModel — requires changing the data model
- Use `java.time.format.DateTimeFormatter` — requires API 26+ (minSdk is 24)

### 5. Missing Key on AddStockScreen LazyColumn

**Problem**: `items(uiState.searchResults)` at AddStockScreen.kt line 201 does not provide a `key` parameter, preventing Compose from efficiently diffing the list when search results change.

**Decision**: Add `key = { it.code }` to the items call.

**Rationale**: `StockSearchResult` has a unique `code` field — natural key candidate.

### 6. Infinite Animation on Idle StockDetailScreen

**Problem**: `RefreshButton` in StockDetailScreen (lines 431-440) creates an `rememberInfiniteTransition()` with `animateFloat()` that runs unconditionally. Even when `isRefreshing == false`, the animation value changes every frame, causing the `RefreshButton` composable to recompose on every frame.

**Root Cause**: The animated value is read inside the `Modifier.rotate()` regardless of the `isRefreshing` state. The `if` check only prevents visual rotation, but the composable still recomposes because it reads the ever-changing `rotation` state.

**Decision**: Only create and run the animation when `isRefreshing` is true. Use a conditional approach: when not refreshing, display a static icon; when refreshing, show the animated rotation.

**Rationale**:
- Eliminates per-frame recomposition of RefreshButton when idle
- No visual change — animation only visible during refresh anyway

**Alternatives Considered**:
- `derivedStateOf` to gate the animation — still creates the animation, just gates the read
- `AnimatedVisibility` — overkill for this use case

### 7. Stability Annotations

**Problem**: No `@Stable` annotations on data classes used as Compose state. While the Compose compiler can often infer stability for simple data classes, explicit annotations guarantee it.

**Decision**: Add `@Stable` to `HomeUiState`, `StockDetailUiState`, and entity classes used in lists.

**Rationale**: Guarantees the Compose compiler will skip recomposition of unchanged items, removing any ambiguity.

**Alternatives Considered**:
- Strong skipping mode (Compose Compiler option) — requires Kotlin 2.0+ with specific compiler config; our setup may already have this via kotlin-compose-compiler plugin, but explicit annotations provide guaranteed behavior

## Summary of Changes by Priority

| Priority | Change | Files Affected | Impact |
|----------|--------|----------------|--------|
| P0 | Consolidate HomeViewModel state emissions | HomeViewModel.kt | Eliminates 2-3× recompositions per data change |
| P0 | Lifecycle-aware state collection | 5 screen files | Stops background screen processing |
| P1 | Merge StockDetailViewModel dual observers | StockDetailViewModel.kt | Eliminates duplicate forecast calculations |
| P1 | Fix infinite animation on idle | StockDetailScreen.kt | Eliminates per-frame recomposition |
| P2 | Cache SimpleDateFormat | StockCard.kt | Eliminates GC pressure during scroll |
| P2 | Add missing LazyColumn key | AddStockScreen.kt | Enables efficient list diffing |
| P3 | Add stability annotations | Entity + UiState files | Guarantees recomposition skipping |
