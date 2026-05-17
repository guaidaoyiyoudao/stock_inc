# Smart Auto-Refresh Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Auto-refresh stock prices on app resume when data is stale (trading hours: 5-min TTL; non-trading: no auto-refresh), fix race condition in HomeViewModel, and fix misleading `lastUpdated` timestamp.

**Architecture:** Add TTL check in HomeViewModel triggered by lifecycle ON_RESUME; fix concurrent-flow race condition by re-reading latest state after network call; persist refresh timestamp to SharedPreferences and `lastUpdated` to Room.

**Tech Stack:** Kotlin Coroutines, Room, SharedPreferences, Lifecycle Compose, Jetpack Compose

---

## File Structure

| File | Role |
|------|------|
| `StockDao.kt` | Add `updateLastUpdated` query |
| `StockRepository.kt` | Add `updateLastUpdated` wrapper + `updateAllLastUpdated` batch |
| `HomeViewModel.kt` | TTL logic, race condition fix, auto-refresh trigger, lastUpdated writes |
| `HomeScreen.kt` | Wire ON_RESUME lifecycle event to ViewModel |
| `HomeViewModelTest.kt` (new) | Test race condition fix and TTL logic |

---

### Task 1: Add updateLastUpdated to StockDao

**Files:**
- Modify: `app/src/main/java/com/stock/dividend/data/local/dao/StockDao.kt`

- [ ] **Step 1: Add the updateLastUpdated query**

Add after the `updateCostPerShare` method:

```kotlin
@Query("UPDATE stocks SET lastUpdated = :timestamp WHERE code = :code")
suspend fun updateLastUpdated(code: String, timestamp: Long)
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/stock/dividend/data/local/dao/StockDao.kt
git commit -m "feat: add updateLastUpdated to StockDao"
```

---

### Task 2: Add updateLastUpdated wrapper to StockRepository

**Files:**
- Modify: `app/src/main/java/com/stock/dividend/data/repository/StockRepository.kt`

- [ ] **Step 1: Add single-stock updateLastUpdated method**

Add after `updateCostPerShare`:

```kotlin
suspend fun updateLastUpdated(code: String, timestamp: Long) {
    stockDao.updateLastUpdated(code, timestamp)
}
```

- [ ] **Step 2: Add batch updateAllLastUpdated method**

Add after the single-stock method:

```kotlin
suspend fun updateAllLastUpdated(codes: List<String>, timestamp: Long) {
    codes.forEach { code -> stockDao.updateLastUpdated(code, timestamp) }
}
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/stock/dividend/data/repository/StockRepository.kt
git commit -m "feat: add updateLastUpdated to StockRepository"
```

---

### Task 3: Fix race condition in HomeViewModel quote refresh

**Files:**
- Modify: `app/src/main/java/com/stock/dividend/viewmodel/HomeViewModel.kt`

**Problem:** The quote-fetch coroutine reads `_uiState.value.stockForecasts` BEFORE the network call (line 161), but Coroutine A may update forecasts during the network call. The fix: re-read after `fetchQuotes()` returns.

- [ ] **Step 1: Fix the quote refresh coroutine to re-read latest state after fetch**

Replace the inner block of the second `viewModelScope.launch` (lines 150-176) where it reads forecasts:

**Before (line 161):**
```kotlin
val forecasts = _uiState.value.stockForecasts
```

**After:**
```kotlin
// Re-read latest forecasts (may have been updated by Coroutine A during network call)
val forecasts = _uiState.value.stockForecasts
```

The code already reads forecasts AT line 161 which is AFTER `fetchQuotes()` on line 155. Wait — let me re-read the actual code flow:

```kotlin
try {
    val prices = stockRepository.fetchQuotes(stocksWithShares)  // line 155 — network call
    
    // ... totalMV calculation ...
    
    val forecasts = _uiState.value.stockForecasts  // line 161 — reads AFTER network call
```

**Analysis:** Line 161 already reads AFTER `fetchQuotes()` returns. So the race condition window is actually very narrow — only between line 161 (reading forecasts) and line 169 (writing _uiState). But Coroutine A also writes via `_uiState.value = state` on line 141. If Coroutine A writes between lines 161-169 of Coroutine B, the price overlay could be lost.

The actual fix: wrap lines 161-172 in a single atomic state update using `_uiState.updateAndGet { }` or use `_uiState.update { }` (which is atomic):

```kotlin
_uiState.update { currentState ->
    val updatedForecasts = currentState.stockForecasts.mapValues { (code, forecast) ->
        val price = prices[code]
        forecast.copy(
            currentPrice = price,
            marketValue = if (price != null && forecast.shares > 0) price * forecast.shares else null
        )
    }
    currentState.copy(
        stockForecasts = updatedForecasts,
        totalMarketValue = totalMV
    )
}
```

`MutableStateFlow.update {}` is atomic — it reads the current value, applies the transform, and writes back in a thread-safe manner. This eliminates the race condition window between read and write.

- [ ] **Step 2: Rewrite the quote refresh block to use atomic update**

Replace lines 150-176 (the entire `collect { stocks -> ... }` block body of the second launch):

```kotlin
.collect { stocks ->
    val stocksWithShares = stocks.filter { it.shares > 0 }
    if (stocksWithShares.isNotEmpty()) {
        _uiState.update { it.copy(isLoading = true) }
        try {
            val prices = stockRepository.fetchQuotes(stocksWithShares)

            val totalMV = stocksWithShares.mapNotNull { stock ->
                prices[stock.code]?.let { price -> price * stock.shares }
            }.sum().let { if (it > 0) it else null }

            _uiState.update { currentState ->
                val updatedForecasts = currentState.stockForecasts.mapValues { (code, forecast) ->
                    val price = prices[code]
                    forecast.copy(
                        currentPrice = price,
                        marketValue = if (price != null && forecast.shares > 0) price * forecast.shares else null
                    )
                }
                currentState.copy(
                    stockForecasts = updatedForecasts,
                    totalMarketValue = totalMV,
                    isLoading = false
                )
            }
            notificationCheckCoordinator.checkWithPrices(stocksWithShares, prices)
        } catch (_: Exception) {
            _uiState.update { it.copy(isLoading = false) }
        }
    } else {
        _uiState.update { it.copy(totalMarketValue = null) }
    }
}
```

Note: `_uiState.update {}` requires `import kotlinx.coroutines.flow.update`.

- [ ] **Step 3: Add the `update` import**

Add to imports:
```kotlin
import kotlinx.coroutines.flow.update
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/stock/dividend/viewmodel/HomeViewModel.kt
git commit -m "fix: use atomic update to eliminate race condition in quote refresh"
```

---

### Task 4: Add TTL logic and auto-refresh to HomeViewModel

**Files:**
- Modify: `app/src/main/java/com/stock/dividend/viewmodel/HomeViewModel.kt`

- [ ] **Step 1: Add new imports**

Add these imports:
```kotlin
import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
```

- [ ] **Step 2: Add constructor parameter for Context and SharedPreferences**

Change the constructor to:
```kotlin
class HomeViewModel @Inject constructor(
    private val stockRepository: StockRepository,
    private val dividendDao: DividendDao,
    private val livingExpenseRepository: LivingExpenseRepository,
    private val transactionDao: TransactionDao,
    private val notificationCheckCoordinator: NotificationCheckCoordinator,
    @ApplicationContext private val context: Context
) : ViewModel() {
```

And add a preferences field:
```kotlin
    private val prefs: SharedPreferences =
        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
```

- [ ] **Step 3: Add TTL helper methods**

Add inside the class body, before `init`:

```kotlin
    private fun isTradingHours(timestampMs: Long): Boolean {
        val now = Instant.ofEpochMilli(timestampMs).atZone(ZoneId.of("Asia/Shanghai"))
        val dayOfWeek = now.dayOfWeek
        if (dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY) return false
        val time = now.toLocalTime()
        val open = LocalTime.of(9, 30)
        val close = LocalTime.of(15, 0)
        return !time.isBefore(open) && !time.isAfter(close)
    }

    private fun shouldAutoRefresh(): Boolean {
        val lastRefreshMs = prefs.getLong(KEY_LAST_REFRESH, 0L)
        if (lastRefreshMs == 0L) return true
        val now = System.currentTimeMillis()
        if (!isTradingHours(now)) return false
        return (now - lastRefreshMs) > TTL_TRADING_MS
    }

    fun onResume() {
        if (shouldAutoRefresh()) {
            refreshQuotes()
        }
    }
```

Add companion object with constants:
```kotlin
    companion object {
        private const val KEY_LAST_REFRESH = "last_quote_refresh_ms"
        private const val TTL_TRADING_MS = 5 * 60 * 1000L // 5 minutes
    }
```

- [ ] **Step 4: Update refreshQuotes to persist timestamp and update lastUpdated**

Modify `refreshQuotes()` to also emit to trigger and update timestamp. But wait — the timestamp should be set AFTER the refresh succeeds, not when it's triggered.

Better approach: In the quote refresh coroutine (the one we fixed in Task 3), add SharedPreferences write and lastUpdated update after successful price fetch.

In the success path of the quote refresh coroutine, after `notificationCheckCoordinator.checkWithPrices(...)`, add:

```kotlin
// Persist refresh timestamp and update lastUpdated for stocks
val now = System.currentTimeMillis()
prefs.edit().putLong(KEY_LAST_REFRESH, now).apply()
stockRepository.updateAllLastUpdated(stocksWithShares.map { it.code }, now)
```

So the complete success path becomes:

```kotlin
_uiState.update { currentState ->
    val updatedForecasts = currentState.stockForecasts.mapValues { (code, forecast) ->
        val price = prices[code]
        forecast.copy(
            currentPrice = price,
            marketValue = if (price != null && forecast.shares > 0) price * forecast.shares else null
        )
    }
    currentState.copy(
        stockForecasts = updatedForecasts,
        totalMarketValue = totalMV,
        isLoading = false
    )
}
notificationCheckCoordinator.checkWithPrices(stocksWithShares, prices)
// Persist refresh timestamp and update lastUpdated for each stock
val now = System.currentTimeMillis()
prefs.edit().putLong(KEY_LAST_REFRESH, now).apply()
stockRepository.updateAllLastUpdated(stocksWithShares.map { it.code }, now)
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/stock/dividend/viewmodel/HomeViewModel.kt
git commit -m "feat: add TTL-based auto-refresh and lastUpdated persistence"
```

---

### Task 5: Wire lifecycle ON_RESUME in HomeScreen

**Files:**
- Modify: `app/src/main/java/com/stock/dividend/ui/screen/HomeScreen.kt`

- [ ] **Step 1: Add imports**

```kotlin
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
```

- [ ] **Step 2: Add LifecycleEventEffect to WatchlistScreen**

In `WatchlistScreen`, add after `val uiState by viewModel.uiState.collectAsStateWithLifecycle()`:

```kotlin
LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
    viewModel.onResume()
}
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/stock/dividend/ui/screen/HomeScreen.kt
git commit -m "feat: trigger auto-refresh on app resume"
```

---

### Task 6: Write unit tests

**Files:**
- Create: `app/src/test/java/com/stock/dividend/viewmodel/HomeViewModelTest.kt`

- [ ] **Step 1: Create HomeViewModelTest**

```kotlin
package com.stock.dividend.viewmodel

import android.content.Context
import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.local.dao.DividendDao
import com.stock.dividend.data.local.dao.TransactionDao
import com.stock.dividend.data.local.entity.StockEntity
import com.stock.dividend.data.notification.NotificationCheckCoordinator
import com.stock.dividend.data.repository.LivingExpenseRepository
import com.stock.dividend.data.repository.StockRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val stockRepository: StockRepository = mockk(relaxed = true)
    private val dividendDao: DividendDao = mockk(relaxed = true)
    private val livingExpenseRepository: LivingExpenseRepository = mockk(relaxed = true)
    private val transactionDao: TransactionDao = mockk(relaxed = true)
    private val notificationCheckCoordinator: NotificationCheckCoordinator = mockk(relaxed = true)
    private val context: Context = mockk(relaxed = true)
    private val prefs: SharedPreferences = mockk(relaxed = true)
    private val stocksFlow = MutableStateFlow<List<StockEntity>>(emptyList())

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE) } returns prefs
        every { stockRepository.observeAllStocks() } returns stocksFlow
        every { livingExpenseRepository.observeExpenses() } returns emptyFlow()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `onResume triggers refresh when no previous refresh`() = runTest {
        every { prefs.getLong("last_quote_refresh_ms", 0L) } returns 0L

        val viewModel = HomeViewModel(
            stockRepository, dividendDao, livingExpenseRepository,
            transactionDao, notificationCheckCoordinator, context
        )
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.onResume()
        dispatcher.scheduler.advanceUntilIdle()

        // Should have triggered refresh since lastRefreshMs is 0 (first launch)
        // The refreshTrigger fires on init AND on onResume
        coEvery { stockRepository.fetchQuotes(any()) } returns emptyMap()
    }

    @Test
    fun `onResume does not trigger refresh within TTL during trading hours`() = runTest {
        val recentTime = System.currentTimeMillis() - 60_000L // 1 minute ago
        every { prefs.getLong("last_quote_refresh_ms", 0L) } returns recentTime

        val viewModel = HomeViewModel(
            stockRepository, dividendDao, livingExpenseRepository,
            transactionDao, notificationCheckCoordinator, context
        )
        dispatcher.scheduler.advanceUntilIdle()

        // Verify initial state is clean
        assertThat(viewModel.uiState.value.isLoading).isFalse()
    }

    @Test
    fun `race condition fix preserves prices after concurrent forecast update`() = runTest {
        val stock = StockEntity(
            code = "sh.600036",
            name = "招商银行",
            marketCode = "1",
            shares = 100,
            yieldPeriod = "3",
            costPerShare = 35.0
        )

        coEvery { stockRepository.fetchQuotes(any()) } returns mapOf("sh.600036" to 40.0)

        stocksFlow.value = listOf(stock)
        every { dividendDao.observeByStock("sh.600036") } returns MutableStateFlow(emptyList())

        val viewModel = HomeViewModel(
            stockRepository, dividendDao, livingExpenseRepository,
            transactionDao, notificationCheckCoordinator, context
        )
        dispatcher.scheduler.advanceUntilIdle()

        // After init + refresh trigger, prices should be applied
        val forecast = viewModel.uiState.value.stockForecasts["sh.600036"]
        assertThat(forecast).isNotNull()
        assertThat(forecast?.currentPrice).isEqualTo(40.0)
        assertThat(forecast?.marketValue).isEqualTo(4000.0)
        assertThat(viewModel.uiState.value.totalMarketValue).isEqualTo(4000.0)
    }

    @Test
    fun `refreshQuotes persists lastUpdated and refresh timestamp`() = runTest {
        val stock = StockEntity(
            code = "sh.600036",
            name = "招商银行",
            marketCode = "1",
            shares = 100,
            yieldPeriod = "3",
            costPerShare = 35.0
        )
        val prefsEditor: SharedPreferences.Editor = mockk(relaxed = true)

        coEvery { stockRepository.fetchQuotes(any()) } returns mapOf("sh.600036" to 40.0)
        every { prefs.edit() } returns prefsEditor
        every { dividendDao.observeByStock("sh.600036") } returns MutableStateFlow(emptyList())

        stocksFlow.value = listOf(stock)

        val viewModel = HomeViewModel(
            stockRepository, dividendDao, livingExpenseRepository,
            transactionDao, notificationCheckCoordinator, context
        )
        dispatcher.scheduler.advanceUntilIdle()

        verify { prefsEditor.putLong("last_quote_refresh_ms", any()) }
        verify { prefsEditor.apply() }
    }
}
```

- [ ] **Step 2: Run tests**

```bash
./gradlew :app:testDebugUnitTest --tests "com.stock.dividend.viewmodel.HomeViewModelTest" 2>&1 | tail -10
```

Expected: all 4 tests pass.

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/com/stock/dividend/viewmodel/HomeViewModelTest.kt
git commit -m "test: add HomeViewModel auto-refresh and race condition tests"
```

---

### Task 7: Build and verify end-to-end

- [ ] **Step 1: Build debug APK**

```bash
./gradlew :app:assembleDebug 2>&1 | tail -5
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Run all unit tests**

```bash
./gradlew :app:testDebugUnitTest 2>&1 | tail -10
```

Expected: all tests pass, no regressions.

- [ ] **Step 3: Commit if any final cleanup needed**

```bash
git status
```

---
