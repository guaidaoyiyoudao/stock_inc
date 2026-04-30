# Quickstart: Verify Scroll Performance Optimization

**Date**: 2026-04-30
**Feature**: 006-optimize-scroll-perf

## Prerequisites

- Android device or emulator (API 24+)
- Project builds successfully: `./gradlew assembleDebug`
- Existing test suite passes: `./gradlew test`

## Verification Steps

### 1. Build & Install

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 2. Unit Tests

```bash
./gradlew test
```

All existing tests must pass without modification to test logic (test assertions remain the same; test setup may adapt to new ViewModel internals).

### 3. Manual Scroll Performance Test

**Home Screen (P1)**:
1. Add 10+ stocks to the portfolio
2. Pull-to-refresh to trigger quote fetching
3. Rapidly scroll up and down the stock list
4. Expected: No visible stuttering, frames render within 16ms

**Stock Detail Screen (P2)**:
1. Open a stock with 20+ dividend records
2. Scroll through the entire detail page
3. Expected: Smooth scrolling through forecast cards and dividend records

**Search Screen (P3)**:
1. Tap FAB to add stock
2. Type a query and scroll results
3. Expected: Smooth list updates and scrolling

### 4. Frame Metrics Verification (Developer)

```bash
# Reset frame stats
adb shell dumpsys gfxinfo com.stock.dividend reset

# Perform scroll operations on the device

# Check frame timing
adb shell dumpsys gfxinfo com.stock.dividend framestats
```

Expected: >95% of frames render in <16ms (Janky frames < 5%).

### 5. Background Behavior Check

1. Open home screen, note displayed data
2. Navigate to stock detail screen
3. Wait 5 seconds
4. Navigate back to home screen
5. Expected: Data is current, no visible reload flash, smooth transition

### 6. Regression Checklist

- [ ] Stock list displays correctly with all data
- [ ] Swipe-to-delete still works
- [ ] Undo delete via snackbar still works
- [ ] Pull-to-refresh fetches new quotes
- [ ] Forecast cards show correct values
- [ ] Dividend history loads and paginates correctly
- [ ] FIRE progress card displays and updates
- [ ] Search adds stocks correctly
- [ ] Edit holding screen works
- [ ] All Chinese text renders correctly
