# Achievement System Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a third "Achievement" tab with milestone achievements stored in a new Room table.

**Architecture:** New `achievements` table (id + unlockedAt), `AchievementDef` enum for definitions, `AchievementChecker` pure function for conditions, `AchievementViewModel` for state, grid UI in HomeScreen.

**Tech Stack:** Kotlin 2.0.21, Room (migration 6→7), Jetpack Compose

---

## Chunk 1: Data Layer

### Task 1: Add AchievementEntity + AchievementDao + DB migration

**Files:**
- Create: `app/src/main/java/com/stock/dividend/data/local/entity/AchievementEntity.kt`
- Create: `app/src/main/java/com/stock/dividend/data/local/dao/AchievementDao.kt`
- Modify: `app/src/main/java/com/stock/dividend/data/local/AppDatabase.kt`
- Modify: `app/src/main/java/com/stock/dividend/di/DatabaseModule.kt`

- [ ] **Step 1: Create AchievementEntity**

```kotlin
package com.stock.dividend.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "achievements")
data class AchievementEntity(
    @PrimaryKey val id: String,
    val unlockedAt: Long
)
```

- [ ] **Step 2: Create AchievementDao**

```kotlin
package com.stock.dividend.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.stock.dividend.data.local.entity.AchievementEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AchievementDao {
    @Query("SELECT * FROM achievements")
    fun observeAll(): Flow<List<AchievementEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(achievements: List<AchievementEntity>)

    @Query("SELECT id FROM achievements")
    suspend fun getAllIds(): List<String>
}
```

- [ ] **Step 3: Update AppDatabase**

In `AppDatabase.kt`:
- Add `AchievementEntity::class` to entities array
- Bump version from 6 to 7
- Add `abstract fun achievementDao(): AchievementDao`
- Add `MIGRATION_6_7`:

```kotlin
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `achievements` (`id` TEXT NOT NULL, `unlockedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
    }
}
```

- [ ] **Step 4: Update DatabaseModule**

In `DatabaseModule.kt`:
- Add `AchievementDao` import
- Add `MIGRATION_6_7` to `addMigrations()`
- Add provider:

```kotlin
@Provides
fun provideAchievementDao(database: AppDatabase): AchievementDao = database.achievementDao()
```

- [ ] **Step 5: Verify build**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/stock/dividend/data/local/entity/AchievementEntity.kt app/src/main/java/com/stock/dividend/data/local/dao/AchievementDao.kt app/src/main/java/com/stock/dividend/data/local/AppDatabase.kt app/src/main/java/com/stock/dividend/di/DatabaseModule.kt
git commit -m "feat: add achievements table with Room migration 6→7"
```

---

## Chunk 2: Core Logic

### Task 2: Create AchievementDef enum and AchievementChecker

**Files:**
- Create: `app/src/main/java/com/stock/dividend/viewmodel/AchievementDef.kt`
- Create: `app/src/main/java/com/stock/dividend/viewmodel/AchievementChecker.kt`

- [ ] **Step 1: Create AchievementDef**

```kotlin
package com.stock.dividend.viewmodel

enum class AchievementDef(
    val id: String,
    val title: String,
    val description: String,
    val icon: String
) {
    FIRST_DIVIDEND("first_dividend", "首次分红", "收到第一笔股息收入", "🌱"),
    INCOME_1K("income_1k", "收入破千", "单年股息收入达到1,000元", "🌿"),
    INCOME_10K("income_10k", "收入破万", "单年股息收入达到10,000元", "🌳"),
    INCOME_100K("income_100k", "收入十万", "单年股息收入达到100,000元", "🏔️"),
    PORTFOLIO_START("portfolio_start", "持仓起步", "开始关注第一只股票", "🚩"),
    DIVERSIFY_5("diversify_5", "分散投资", "同时持有5只以上股票", "🛡️"),
    HOLD_1Y("hold_1y", "坚持持有", "最早添加的股票持有满一年", "💎"),
    STREAK_3Y("streak_3y", "连年分红", "连续3年有股息收入", "❄️");
}
```

- [ ] **Step 2: Create AchievementChecker**

```kotlin
package com.stock.dividend.viewmodel

import com.stock.dividend.data.local.entity.StockEntity

object AchievementChecker {
    data class CheckContext(
        val stocks: List<StockEntity>,
        val yearlyTotals: Map<Int, Double>,
        val hasAnyIncomeRecord: Boolean
    )

    fun check(ctx: CheckContext): Set<String> {
        val unlocked = mutableSetOf<String>()

        if (ctx.hasAnyIncomeRecord) unlocked.add(AchievementDef.FIRST_DIVIDEND.id)

        val maxIncome = ctx.yearlyTotals.values.maxOrNull() ?: 0.0
        if (maxIncome >= 1_000) unlocked.add(AchievementDef.INCOME_1K.id)
        if (maxIncome >= 10_000) unlocked.add(AchievementDef.INCOME_10K.id)
        if (maxIncome >= 100_000) unlocked.add(AchievementDef.INCOME_100K.id)

        if (ctx.stocks.isNotEmpty()) unlocked.add(AchievementDef.PORTFOLIO_START.id)
        if (ctx.stocks.size >= 5) unlocked.add(AchievementDef.DIVERSIFY_5.id)

        val earliestAddedAt = ctx.stocks.minOfOrNull { it.addedAt }
        if (earliestAddedAt != null && earliestAddedAt > 0 &&
            System.currentTimeMillis() - earliestAddedAt >= 365L * 24 * 3600 * 1000) {
            unlocked.add(AchievementDef.HOLD_1Y.id)
        }

        val years = ctx.yearlyTotals.keys.sorted()
        if (years.size >= 3) {
            var maxStreak = 1
            for (i in 1 until years.size) {
                if (years[i] == years[i - 1] + 1) maxStreak++ else maxStreak = 1
            }
            if (maxStreak >= 3) unlocked.add(AchievementDef.STREAK_3Y.id)
        }

        return unlocked
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/stock/dividend/viewmodel/AchievementDef.kt app/src/main/java/com/stock/dividend/viewmodel/AchievementChecker.kt
git commit -m "feat: add AchievementDef enum and AchievementChecker"
```

### Task 3: Create AchievementChecker unit tests

**Files:**
- Create: `app/src/test/java/com/stock/dividend/viewmodel/AchievementCheckerTest.kt`

- [ ] **Step 1: Write tests**

Test each achievement condition:
- `FIRST_DIVIDEND` — hasAnyIncomeRecord true/false
- `INCOME_1K/10K/100K` — yearlyTotals with various max values
- `PORTFOLIO_START` — empty/non-empty stock list
- `DIVERSIFY_5` — stocks count < 5 and >= 5
- `HOLD_1Y` — stock addedAt within/over 1 year ago
- `STREAK_3Y` — consecutive years with gaps and without

Use `StockEntity(code="test", name="test", marketCode="1", shares=100, addedAt=...)` for test data.

- [ ] **Step 2: Run tests**

Run: `./gradlew :app:testDebugUnitTest --tests "com.stock.dividend.viewmodel.AchievementCheckerTest"`
Expected: All pass

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/com/stock/dividend/viewmodel/AchievementCheckerTest.kt
git commit -m "test: add AchievementChecker unit tests"
```

### Task 4: Create AchievementRepository

**Files:**
- Create: `app/src/main/java/com/stock/dividend/data/repository/AchievementRepository.kt`

- [ ] **Step 1: Create repository**

```kotlin
package com.stock.dividend.data.repository

import com.stock.dividend.data.local.dao.AchievementDao
import com.stock.dividend.data.local.entity.AchievementEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AchievementRepository @Inject constructor(
    private val achievementDao: AchievementDao
) {
    fun observeAll(): Flow<List<AchievementEntity>> = achievementDao.observeAll()

    suspend fun syncAchievements(qualifiedIds: Set<String>) {
        val existingIds = achievementDao.getAllIds().toSet()
        val newAchievements = (qualifiedIds - existingIds).map {
            AchievementEntity(id = it, unlockedAt = System.currentTimeMillis())
        }
        if (newAchievements.isNotEmpty()) {
            achievementDao.insertAll(newAchievements)
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/stock/dividend/data/repository/AchievementRepository.kt
git commit -m "feat: add AchievementRepository with sync logic"
```

---

## Chunk 3: ViewModel + UI

### Task 5: Create AchievementViewModel

**Files:**
- Create: `app/src/main/java/com/stock/dividend/viewmodel/AchievementViewModel.kt`

- [ ] **Step 1: Create ViewModel**

```kotlin
@Stable
data class AchievementItem(
    val def: AchievementDef,
    val unlocked: Boolean,
    val unlockedAt: Long? = null
)

@Stable
data class AchievementUiState(
    val achievements: List<AchievementItem> = emptyList(),
    val isLoading: Boolean = true
)

@HiltViewModel
class AchievementViewModel @Inject constructor(
    private val achievementRepository: AchievementRepository,
    stockRepository: StockRepository,
    incomeRepository: DividendIncomeRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(AchievementUiState())
    val uiState: StateFlow<AchievementUiState> = _uiState.asStateFlow()

    private val stocksFlow = stockRepository.observeAllStocks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        // Observe all data sources and compute achievements
        viewModelScope.launch {
            combine(
                stocksFlow,
                incomeRepository.observeYearlyTotals(),
                achievementRepository.observeAll()
            ) { stocks, yearlyTotals, unlocked ->
                val hasIncome = yearlyTotals.isNotEmpty()
                val ctx = AchievementChecker.CheckContext(
                    stocks = stocks,
                    yearlyTotals = yearlyTotals.associate { it.year to it.total },
                    hasAnyIncomeRecord = hasIncome
                )
                val qualified = AchievementChecker.check(ctx)

                // Sync new unlocks
                achievementRepository.syncAchievements(qualified)

                // Build UI items
                val unlockedMap = unlocked.associateBy { it.id }
                achievements = AchievementDef.entries.map { def ->
                    val entity = unlockedMap[def.id]
                    AchievementItem(
                        def = def,
                        unlocked = entity != null || def.id in qualified,
                        unlockedAt = entity?.unlockedAt
                    )
                }
            }.collect { state ->
                _uiState.value = AchievementUiState(
                    achievements = state.achievements,
                    isLoading = false
                )
            }
        }
    }
}
```

Note: The `combine` return type needs adjustment — the lambda needs to return a data class, not assign to `achievements` inside it. Adjust as needed during implementation.

- [ ] **Step 2: Verify build**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/stock/dividend/viewmodel/AchievementViewModel.kt
git commit -m "feat: add AchievementViewModel with auto-unlock logic"
```

### Task 6: Create UI components

**Files:**
- Create: `app/src/main/java/com/stock/dividend/ui/component/AchievementCard.kt`

- [ ] **Step 1: Create AchievementCard composable**

A card showing one achievement with:
- Icon (emoji from `AchievementDef.icon`)
- Title and description
- Unlock status: unlocked shows date, locked shows "未解锁" in muted color
- Unlocked: full color; locked: greyscale / muted alpha

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/stock/dividend/ui/component/AchievementCard.kt
git commit -m "feat: add AchievementCard component"
```

### Task 7: Integrate into HomeScreen

**Files:**
- Modify: `app/src/main/java/com/stock/dividend/ui/screen/HomeScreen.kt`

- [ ] **Step 1: Add imports and ViewModel parameter**

Add `AchievementViewModel` parameter to `HomeScreen` and third Tab.

- [ ] **Step 2: Add third Tab to TabRow**

Add `Tab(selected = selectedTabIndex == 2, text = { Text("成就") })`

- [ ] **Step 3: Add AchievementTabContent**

New composable using `LazyVerticalGrid` with 2 columns, displaying `AchievementCard` for each item.

- [ ] **Step 4: Update FAB visibility**

Add `2 -> true` case for selectedTabIndex.

- [ ] **Step 5: Build and verify**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/stock/dividend/ui/screen/HomeScreen.kt
git commit -m "feat: integrate achievement tab into HomeScreen"
```

### Task 8: Build verification

- [ ] **Step 1: Run full build**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Run all unit tests**

Run: `./gradlew :app:testDebugUnitTest`
Expected: All pass

- [ ] **Step 3: Done — achievement system complete**
