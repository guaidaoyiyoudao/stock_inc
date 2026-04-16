# Cost Basis & Market Value Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add per-share cost basis input to stock add/edit flows and display total market value on home page cards using real-time stock prices from EastMoney.

**Architecture:** Add `costPerShare` field to StockEntity with database migration v3→v4. Create a new `QuoteApi` Retrofit interface to fetch real-time prices from EastMoney. Prices are cached in-memory in `HomeViewModel` and used to calculate market value per stock and total portfolio market value displayed on cards.

**Tech Stack:** Kotlin 2.0, Jetpack Compose, Material Design 3, Room, Retrofit + OkHttp, Hilt, Coroutines + Flow

---

## Chunk 1: Data Layer — Entity, DAO, Migration

### Task 1: Add costPerShare to StockEntity and create migration

**Files:**
- Modify: `app/src/main/java/com/stock/dividend/data/local/entity/StockEntity.kt:7-16`
- Modify: `app/src/main/java/com/stock/dividend/data/local/AppDatabase.kt:16,42-43`
- Modify: `app/src/main/java/com/stock/dividend/data/local/dao/StockDao.kt:31-32`
- Modify: `app/src/main/java/com/stock/dividend/di/DatabaseModule.kt:28`

- [ ] **Step 1: Add costPerShare field to StockEntity**

In `StockEntity.kt`, add `costPerShare` after `yieldPeriod`:

```kotlin
@Entity(tableName = "stocks")
data class StockEntity(
    @PrimaryKey
    val code: String,
    val name: String,
    val marketCode: String,
    val addedAt: Long = System.currentTimeMillis(),
    val lastUpdated: Long? = null,
    val shares: Int = 0,
    val yieldPeriod: String = "3",
    val costPerShare: Double = 0.0
)
```

- [ ] **Step 2: Bump database version and add migration v3→v4**

In `AppDatabase.kt`:
- Change `version = 3` to `version = 4`
- Add `MIGRATION_3_4` after `MIGRATION_2_3`:

```kotlin
@Database(
    entities = [StockEntity::class, DividendEntity::class, FireGoalEntity::class],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun stockDao(): StockDao
    abstract fun dividendDao(): DividendDao
    abstract fun fireGoalDao(): FireGoalDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE stocks ADD COLUMN shares INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE stocks ADD COLUMN yieldPeriod TEXT NOT NULL DEFAULT '3'")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `fire_goal` (" +
                            "`id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                            "`targetAmount` REAL NOT NULL, " +
                            "`createdAt` INTEGER NOT NULL, " +
                            "`updatedAt` INTEGER NOT NULL)"
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE stocks ADD COLUMN costPerShare REAL NOT NULL DEFAULT 0.0")
            }
        }
    }
}
```

- [ ] **Step 3: Add updateCostPerShare to StockDao**

In `StockDao.kt`, add after `updateYieldPeriod`:

```kotlin
@Query("UPDATE stocks SET costPerShare = :costPerShare WHERE code = :code")
suspend fun updateCostPerShare(code: String, costPerShare: Double)
```

- [ ] **Step 4: Register MIGRATION_3_4 in DatabaseModule**

In `DatabaseModule.kt`, update line 28:

```kotlin
.addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4)
```

Add import:
```kotlin
import com.stock.dividend.data.local.AppDatabase
```
(Note: this import likely already exists)

- [ ] **Step 5: Build and verify compilation**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/stock/dividend/data/local/entity/StockEntity.kt \
        app/src/main/java/com/stock/dividend/data/local/AppDatabase.kt \
        app/src/main/java/com/stock/dividend/data/local/dao/StockDao.kt \
        app/src/main/java/com/stock/dividend/di/DatabaseModule.kt
git commit -m "feat: add costPerShare field to StockEntity with db migration v3->v4"
```

---

## Chunk 2: Network Layer — QuoteApi

### Task 2: Create QuoteApi and QuoteResponse DTO

**Files:**
- Create: `app/src/main/java/com/stock/dividend/data/remote/QuoteApi.kt`
- Create: `app/src/main/java/com/stock/dividend/data/remote/dto/QuoteResponse.kt`
- Modify: `app/src/main/java/com/stock/dividend/di/NetworkModule.kt:21,49-70`

- [ ] **Step 1: Create QuoteResponse DTO**

Create `app/src/main/java/com/stock/dividend/data/remote/dto/QuoteResponse.kt`:

```kotlin
package com.stock.dividend.data.remote.dto

import com.google.gson.annotations.SerializedName

data class QuoteResponse(
    val data: QuoteData?
)

data class QuoteData(
    val diff: List<QuoteItem>?
)

data class QuoteItem(
    @SerializedName("f2")
    val price: Double?,
    @SerializedName("f12")
    val code: String,
    @SerializedName("f13")
    val market: Int
)
```

- [ ] **Step 2: Create QuoteApi interface**

Create `app/src/main/java/com/stock/dividend/data/remote/QuoteApi.kt`:

```kotlin
package com.stock.dividend.data.remote

import com.stock.dividend.data.remote.dto.QuoteResponse
import retrofit2.http.GET
import retrofit2.http.Query

interface QuoteApi {

    @GET("api/qt/ulist.np/get")
    suspend fun getQuotes(
        @Query("secids") secids: String,
        @Query("fields") fields: String = "f2,f12,f13",
        @Query("ut") ut: String = "fa5fd1943c7b386f172d6893dbfba10b"
    ): QuoteResponse
}
```

- [ ] **Step 3: Register QuoteApi in NetworkModule**

In `NetworkModule.kt`, add base URL constant and provider method:

After line 21 (`private const val DATA_BASE_URL = ...`), add:
```kotlin
private const val QUOTE_BASE_URL = "https://push2.eastmoney.com/"
```

After line 69 (after `provideDividendApi`), add:
```kotlin
@Provides
@Singleton
fun provideQuoteApi(client: OkHttpClient): QuoteApi {
    return Retrofit.Builder()
        .baseUrl(QUOTE_BASE_URL)
        .client(client)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(QuoteApi::class.java)
}
```

Add import at top:
```kotlin
import com.stock.dividend.data.remote.QuoteApi
```

- [ ] **Step 4: Build and verify compilation**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/stock/dividend/data/remote/QuoteApi.kt \
        app/src/main/java/com/stock/dividend/data/remote/dto/QuoteResponse.kt \
        app/src/main/java/com/stock/dividend/di/NetworkModule.kt
git commit -m "feat: add QuoteApi for real-time stock price fetching"
```

---

## Chunk 3: Repository Layer — fetchQuotes and addStock update

### Task 3: Update StockRepository with QuoteApi and fetchQuotes

**Files:**
- Modify: `app/src/main/java/com/stock/dividend/data/repository/StockRepository.kt`

- [ ] **Step 1: Add QuoteApi to StockRepository constructor and implement fetchQuotes**

Update `StockRepository.kt`:

```kotlin
package com.stock.dividend.data.repository

import com.stock.dividend.data.local.dao.StockDao
import com.stock.dividend.data.local.entity.StockEntity
import com.stock.dividend.data.remote.QuoteApi
import com.stock.dividend.data.remote.SearchApi
import com.stock.dividend.data.remote.dto.StockSearchResponse
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

data class StockSearchResult(
    val code: String,
    val name: String,
    val marketCode: String
)

@Singleton
class StockRepository @Inject constructor(
    private val api: SearchApi,
    private val quoteApi: QuoteApi,
    private val stockDao: StockDao
) {
    suspend fun searchStocks(query: String): Result<List<StockSearchResult>> {
        return try {
            val response = api.searchStocks(input = query)
            val items = response.quotationCodeTable?.Data
                ?.filter { it.Classify == "AStock" }
                ?.map { item ->
                    StockSearchResult(
                        code = formatStockCode(item.MktNum, item.Code),
                        name = item.Name,
                        marketCode = item.MktNum
                    )
                } ?: emptyList()
            Result.success(items)
        } catch (e: Exception) {
            Result.failure(Exception(e.toUserMessage(), e))
        }
    }

    suspend fun addStock(
        searchResult: StockSearchResult,
        shares: Int = 0,
        costPerShare: Double = 0.0
    ): Result<Unit> {
        return try {
            val entity = StockEntity(
                code = searchResult.code,
                name = searchResult.name,
                marketCode = searchResult.marketCode,
                shares = shares,
                costPerShare = costPerShare
            )
            stockDao.insert(entity)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(Exception(e.toUserMessage(), e))
        }
    }

    suspend fun removeStock(code: String) {
        stockDao.delete(code)
    }

    fun observeAllStocks(): Flow<List<StockEntity>> {
        return stockDao.observeAll()
    }

    fun observeStock(code: String): Flow<StockEntity?> {
        return stockDao.observeByCode(code)
    }

    suspend fun updateShares(code: String, shares: Int) {
        stockDao.updateShares(code, shares.coerceAtLeast(0))
    }

    suspend fun updateYieldPeriod(code: String, period: String) {
        stockDao.updateYieldPeriod(code, period)
    }

    suspend fun updateCostPerShare(code: String, costPerShare: Double) {
        stockDao.updateCostPerShare(code, costPerShare.coerceAtLeast(0.0))
    }

    suspend fun fetchQuotes(stocks: List<StockEntity>): Map<String, Double> {
        if (stocks.isEmpty()) return emptyMap()
        return try {
            val secids = stocks.joinToString(",") { stock ->
                "${stock.marketCode}.${stock.code.substringAfter(".")}"
            }
            val response = quoteApi.getQuotes(secids = secids)
            val priceMap = mutableMapOf<String, Double>()
            response.data?.diff?.forEach { item ->
                val price = item.price
                if (price != null && price > 0) {
                    val prefix = if (item.market == 1) "sh" else "sz"
                    val appCode = "$prefix.${item.code}"
                    priceMap[appCode] = price
                }
            }
            priceMap
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun formatStockCode(marketCode: String, code: String): String {
        val prefix = if (marketCode == "1") "sh" else "sz"
        return "$prefix.$code"
    }
}
```

Key changes:
- Added `quoteApi: QuoteApi` to constructor
- `addStock` now accepts `costPerShare: Double = 0.0`
- Added `updateCostPerShare` delegation method
- Added `fetchQuotes` — converts codes to API format, calls QuoteApi, filters invalid prices, converts back to app format, returns empty map on any error

- [ ] **Step 2: Build and verify compilation**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/stock/dividend/data/repository/StockRepository.kt
git commit -m "feat: add fetchQuotes and costPerShare support to StockRepository"
```

---

## Chunk 4: ViewModel Layer — HomeViewModel, AddStock, EditHolding

### Task 4: Update HomeViewModel with price cache and market value

**Files:**
- Modify: `app/src/main/java/com/stock/dividend/viewmodel/HomeViewModel.kt`

- [ ] **Step 1: Update StockForecast and HomeUiState data classes**

In `HomeViewModel.kt`, update `StockForecast` to include market value fields:

```kotlin
data class StockForecast(
    val shares: Int,
    val avgCashPerShare: Double,
    val forecastIncome: Double,
    val actualYears: Int,
    val currentPrice: Double? = null,
    val marketValue: Double? = null
)
```

Update `HomeUiState` to include `totalMarketValue`:

```kotlin
data class HomeUiState(
    val stocks: List<StockEntity> = emptyList(),
    val forecastTotal: Double = 0.0,
    val stockForecasts: Map<String, StockForecast> = emptyMap(),
    val totalMarketValue: Double? = null,
    val fireGoal: FireGoalEntity? = null,
    val fireProgress: Float? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val deletedStock: StockEntity? = null
)
```

- [ ] **Step 2: Update HomeViewModel class body — targeted edits**

The following edits are made to `HomeViewModel.kt`. Do NOT rewrite the whole file — make each edit surgically.

**Edit 2a: Add price cache and refresh trigger fields**

After line 51 (`val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()`), add:

```kotlin
private val _stockPrices = MutableStateFlow<Map<String, Double>>(emptyMap())
private val _refreshTrigger = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
```

**Edit 2b: Add quote-fetching coroutine to init block**

At the end of the `init` block (after the closing `}` of the stocks observation launch, before the closing `}` of `init`), add a third launch:

```kotlin
// Fetch quotes: triggered by stock list changes and explicit refresh
viewModelScope.launch {
    combine(
        stocksFlow,
        _refreshTrigger.onStart { emit(Unit) }.conflate()
    ) { stocks, _ -> stocks }
        .collect { stocks ->
            val stocksWithShares = stocks.filter { it.shares > 0 }
            if (stocksWithShares.isNotEmpty()) {
                val prices = stockRepository.fetchQuotes(stocksWithShares)
                _stockPrices.value = prices

                val forecasts = _uiState.value.stockForecasts
                val updatedForecasts = forecasts.mapValues { (code, forecast) ->
                    val price = prices[code]
                    forecast.copy(
                        currentPrice = price,
                        marketValue = if (price != null && forecast.shares > 0) price * forecast.shares else null
                    )
                }
                val totalMV = updatedForecasts.values.mapNotNull { it.marketValue }.sum().let {
                    if (it > 0) it else null
                }
                _uiState.value = _uiState.value.copy(
                    stockForecasts = updatedForecasts,
                    totalMarketValue = totalMV
                )
            } else {
                _stockPrices.value = emptyMap()
                _uiState.value = _uiState.value.copy(totalMarketValue = null)
            }
        }
}
```

**Edit 2c: Update existing collect block to NOT fetch quotes**

In the existing stocks observation `collect` block (around line 105-118), the `_uiState.value = _uiState.value.copy(...)` calls should NOT include `totalMarketValue`. The existing code stays as-is, just make sure it does NOT set `totalMarketValue`. The existing code already doesn't — it only sets `stocks`, `stockForecasts`, `forecastTotal`, `fireProgress`. Keep it that way.

**Edit 2d: Add refreshQuotes method**

After `clearDeleted()`, add:

```kotlin
fun refreshQuotes() {
    _refreshTrigger.tryEmit(Unit)
}
```

**Edit 2e: Update undoDelete to pass costPerShare**

In the `undoDelete()` method (around line 131), update the `addStock` call:

```kotlin
fun undoDelete() {
    val deleted = _uiState.value.deletedStock ?: return
    viewModelScope.launch {
        stockRepository.addStock(
            com.stock.dividend.data.repository.StockSearchResult(
                code = deleted.code,
                name = deleted.name,
                marketCode = deleted.marketCode
            ),
            shares = deleted.shares,
            costPerShare = deleted.costPerShare
        )
        _uiState.value = _uiState.value.copy(deletedStock = null)
    }
}
```

**Edit 2f: Add imports**

Add at the top of the file:
```kotlin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.onStart
```

Architecture notes:
- Quote fetching is separated from the forecast Flow pipeline — it has its own coroutine
- It combines `stocksFlow` with a refresh trigger, so quotes are fetched when stocks change OR on explicit refresh
- `conflate()` ensures rapid refreshes don't queue up — only the latest matters
- The forecast pipeline (existing) produces `StockForecast` without price data; the quote pipeline enriches them with `currentPrice` and `marketValue` after prices arrive

- [ ] **Step 2: Build and verify compilation**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/stock/dividend/viewmodel/HomeViewModel.kt
git commit -m "feat: add price cache and market value calculation to HomeViewModel"
```

### Task 5: Update AddStockViewModel with cost input

**Files:**
- Modify: `app/src/main/java/com/stock/dividend/viewmodel/AddStockViewModel.kt`

- [ ] **Step 1: Add costPerShare fields to AddStockUiState and ViewModel**

Update `AddStockUiState`:

```kotlin
data class AddStockUiState(
    val searchQuery: String = "",
    val searchResults: List<StockSearchResult> = emptyList(),
    val isSearching: Boolean = false,
    val error: String? = null,
    val addedStock: String? = null,
    val canRetry: Boolean = false,
    val shares: Int = 0,
    val sharesInput: String = "",
    val sharesError: String? = null,
    val costPerShareInput: String = "",
    val costPerShareError: String? = null
)
```

Add `onCostPerShareChanged` method to `AddStockViewModel`, after `onSharesChanged`:

```kotlin
fun onCostPerShareChanged(input: String) {
    val error = if (input.isNotBlank()) {
        val parsed = input.toDoubleOrNull()
        if (parsed == null || parsed < 0) "请输入有效的非负数" else null
    } else null
    _uiState.value = _uiState.value.copy(
        costPerShareInput = input,
        costPerShareError = error
    )
}
```

Update `addStock` method at line 85 to pass `costPerShare`:

```kotlin
fun addStock(result: StockSearchResult) {
    lastAddResult = result
    val costPerShare = _uiState.value.costPerShareInput.toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0
    viewModelScope.launch {
        stockRepository.addStock(result, _uiState.value.shares, costPerShare)
            .onSuccess {
                // ... rest unchanged
```

- [ ] **Step 2: Build and verify compilation**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/stock/dividend/viewmodel/AddStockViewModel.kt
git commit -m "feat: add costPerShare input to AddStockViewModel"
```

### Task 6: Update EditHoldingViewModel with cost input

**Files:**
- Modify: `app/src/main/java/com/stock/dividend/viewmodel/EditHoldingViewModel.kt`

- [ ] **Step 1: Add costPerShare fields to EditHoldingUiState and ViewModel**

Update `EditHoldingUiState`:

```kotlin
data class EditHoldingUiState(
    val stockCode: String = "",
    val stockName: String? = null,
    val sharesInput: String = "",
    val sharesError: String? = null,
    val yieldPeriod: String = "3",
    val costPerShareInput: String = "",
    val costPerShareError: String? = null
)
```

Update init block to pre-fill costPerShare:

```kotlin
init {
    viewModelScope.launch {
        stockRepository.observeStock(stockCode).collect { stock ->
            if (stock != null) {
                _uiState.value = _uiState.value.copy(
                    stockName = stock.name,
                    sharesInput = if (stock.shares > 0) stock.shares.toString() else "",
                    yieldPeriod = stock.yieldPeriod,
                    costPerShareInput = if (stock.costPerShare > 0) stock.costPerShare.toString() else ""
                )
            }
        }
    }
}
```

Add `onCostPerShareChanged` method, after `onYieldPeriodChanged`:

```kotlin
fun onCostPerShareChanged(input: String) {
    val error = if (input.isNotBlank()) {
        val parsed = input.toDoubleOrNull()
        if (parsed == null || parsed < 0) "请输入有效的非负数" else null
    } else null
    _uiState.value = _uiState.value.copy(
        costPerShareInput = input,
        costPerShareError = error
    )
}
```

Update `saveHolding` to also save costPerShare:

```kotlin
fun saveHolding() {
    val shares = _uiState.value.sharesInput.toIntOrNull()?.coerceAtLeast(0) ?: 0
    val period = _uiState.value.yieldPeriod
    val costPerShare = _uiState.value.costPerShareInput.toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0
    viewModelScope.launch {
        stockRepository.updateShares(stockCode, shares)
        stockRepository.updateYieldPeriod(stockCode, period)
        stockRepository.updateCostPerShare(stockCode, costPerShare)
    }
}
```

- [ ] **Step 2: Build and verify compilation**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/stock/dividend/viewmodel/EditHoldingViewModel.kt
git commit -m "feat: add costPerShare input to EditHoldingViewModel"
```

---

## Chunk 5: UI Layer — Cards and Screens

### Task 7: Update StockCard to show market value

**Files:**
- Modify: `app/src/main/java/com/stock/dividend/ui/component/StockCard.kt:34-41,124-138`

- [ ] **Step 1: Add marketValue parameter and display**

Update `StockCard` function signature — add `marketValue: Double? = null` parameter after `forecastIncome`:

```kotlin
@Composable
fun StockCard(
    name: String,
    code: String,
    shares: Int = 0,
    forecastIncome: String? = null,
    marketValue: String? = null,
    lastUpdated: Long? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
)
```

Update the right-side Column (lines 124-138) to show market value above forecast income:

```kotlin
if (marketValue != null || forecastIncome != null) {
    Column(horizontalAlignment = Alignment.End) {
        if (marketValue != null) {
            Text(
                text = marketValue,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.tertiary,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "市值",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
        }
        if (forecastIncome != null) {
            Text(
                text = forecastIncome,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "预测收入",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/stock/dividend/ui/component/StockCard.kt
git commit -m "feat: display market value on StockCard"
```

### Task 8: Update DividendSummaryCard to show total market value

**Files:**
- Modify: `app/src/main/java/com/stock/dividend/ui/component/DividendSummaryCard.kt:36-39,107-138`

- [ ] **Step 1: Add totalMarketValue parameter and display row**

Update `DividendSummaryCard` signature:

```kotlin
@Composable
fun DividendSummaryCard(
    totalAmount: Double,
    totalMarketValue: Double? = null,
    modifier: Modifier = Modifier
)
```

Add a market value row after the "日均/月均" Row (after line 138, before the Spacer at line 139). Insert a new section between the 日均/月均 row and the disclaimer:

```kotlin
                // After the Row with 日均/月均, add:
                if (totalMarketValue != null) {
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.6f))
                        )
                        Text(
                            text = "持仓总市值",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White.copy(alpha = 0.85f)
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = buildAnnotatedString {
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                                append("¥")
                            }
                            append("%,.2f".format(totalMarketValue))
                        },
                        style = MaterialTheme.typography.headlineSmall,
                        color = Color.White
                    )
                }
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/stock/dividend/ui/component/DividendSummaryCard.kt
git commit -m "feat: display total market value on DividendSummaryCard"
```

### Task 9: Update HomeScreen to thread market value

**Files:**
- Modify: `app/src/main/java/com/stock/dividend/ui/screen/HomeScreen.kt:128,144-149,158-162,197-204`

- [ ] **Step 1: Pass totalMarketValue to DividendSummaryCard**

At line 128, update:
```kotlin
DividendSummaryCard(
    totalAmount = uiState.forecastTotal,
    totalMarketValue = uiState.totalMarketValue
)
```

- [ ] **Step 2: Update SwipeToDismissStockItem to accept and pass marketValue**

Update the call at lines 144-149:
```kotlin
SwipeToDismissStockItem(
    stock = stock,
    forecastIncome = uiState.stockForecasts[stock.code]?.forecastIncome,
    marketValue = uiState.stockForecasts[stock.code]?.marketValue,
    onDismiss = { viewModel.deleteStock(stock) },
    onClick = { onStockClick(stock.code) }
)
```

Update `SwipeToDismissStockItem` signature at lines 158-162:
```kotlin
@Composable
private fun SwipeToDismissStockItem(
    stock: StockEntity,
    forecastIncome: Double? = null,
    marketValue: Double? = null,
    onDismiss: () -> Unit,
    onClick: () -> Unit
)
```

Update StockCard call inside SwipeToDismissStockItem at lines 197-204:
```kotlin
StockCard(
    name = stock.name,
    code = stock.code,
    shares = stock.shares,
    forecastIncome = forecastIncome?.let { "¥${"%.2f".format(it)}" },
    marketValue = marketValue?.let { "¥${"%,.2f".format(it)}" },
    lastUpdated = stock.lastUpdated,
    onClick = onClick
)
```

- [ ] **Step 3: Build and verify compilation**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/stock/dividend/ui/screen/HomeScreen.kt
git commit -m "feat: thread market value through HomeScreen to cards"
```

### Task 10: Update AddStockScreen with cost input

**Files:**
- Modify: `app/src/main/java/com/stock/dividend/ui/screen/AddStockScreen.kt:209-224`

- [ ] **Step 1: Add cost per share input field below shares input**

After the shares `OutlinedTextField` (after line 223), add:

```kotlin
Spacer(modifier = Modifier.height(8.dp))

OutlinedTextField(
    value = uiState.costPerShareInput,
    onValueChange = viewModel::onCostPerShareChanged,
    label = { Text("每股成本价（选填）") },
    modifier = Modifier.fillMaxWidth(),
    singleLine = true,
    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    isError = uiState.costPerShareError != null,
    supportingText = uiState.costPerShareError?.let {
        { Text(it, color = MaterialTheme.colorScheme.error) }
    },
    shape = MaterialTheme.shapes.medium
)
```

- [ ] **Step 2: Build and verify compilation**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/stock/dividend/ui/screen/AddStockScreen.kt
git commit -m "feat: add cost per share input to AddStockScreen"
```

### Task 11: Update EditHoldingScreen with cost input

**Files:**
- Modify: `app/src/main/java/com/stock/dividend/ui/screen/EditHoldingScreen.kt:69,157-159`

- [ ] **Step 1: Update save button enabled state**

At line 69, update to also check costPerShare error:
```kotlin
enabled = uiState.sharesError == null && uiState.costPerShareError == null
```

- [ ] **Step 2: Add cost per share input section**

After the shares OutlinedTextField (after line 157, before `Spacer(modifier = Modifier.height(24.dp))`), add:

```kotlin
Spacer(modifier = Modifier.height(16.dp))

Text(
    text = "每股成本价",
    style = MaterialTheme.typography.labelLarge,
    color = MaterialTheme.colorScheme.onSurfaceVariant
)

Spacer(modifier = Modifier.height(8.dp))

OutlinedTextField(
    value = uiState.costPerShareInput,
    onValueChange = viewModel::onCostPerShareChanged,
    label = { Text("成本价（元/股）") },
    modifier = Modifier.fillMaxWidth(),
    singleLine = true,
    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    isError = uiState.costPerShareError != null,
    supportingText = uiState.costPerShareError?.let {
        { Text(it, color = MaterialTheme.colorScheme.error) }
    },
    shape = MaterialTheme.shapes.medium
)
```

- [ ] **Step 3: Build and verify full compilation**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/stock/dividend/ui/screen/EditHoldingScreen.kt
git commit -m "feat: add cost per share input to EditHoldingScreen"
```

---

## Chunk 6: Final Integration Build

### Task 12: Full build verification and final commit

- [ ] **Step 1: Run full debug build**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Verify all changed files are committed**

Run: `git status`
Expected: no untracked or modified files related to the feature

- [ ] **Step 3: Review git log for clean commit history**

Run: `git log --oneline -10`
Expected: commits for each task in order
