# Achievement System Design

**Goal:** Add a third "Achievement" tab to HomeScreen with milestone achievements based on income and portfolio data, stored in a new Room table.

**Architecture:** Achievement definitions live in a sealed class (`AchievementDef`) with metadata and a check function. A new `achievements` Room table stores unlock timestamps. An `AchievementChecker` pure function evaluates conditions from existing data. ViewModel diffs checker results against the table to auto-unlock new achievements.

**Tech Stack:** Kotlin 2.0.21, Room (migration 6→7), Jetpack Compose

---

## 1. Database

### New table: `achievements`

```sql
CREATE TABLE achievements (
    id TEXT PRIMARY KEY,
    unlockedAt INTEGER NOT NULL
)
```

Migration `MIGRATION_6_7`: create this table.

### New entity: `AchievementEntity`

```kotlin
@Entity(tableName = "achievements")
data class AchievementEntity(
    @PrimaryKey val id: String,
    val unlockedAt: Long
)
```

### New DAO: `AchievementDao`

```kotlin
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

`onConflict = IGNORE` ensures idempotent inserts — no duplicate unlocks.

## 2. Achievement Definitions

### `AchievementDef` sealed class

```kotlin
enum class AchievementDef(
    val id: String,
    val title: String,
    val description: String,
    val icon: String  // Material Icons name for future use
) {
    FIRST_DIVIDEND("first_dividend", "首次分红", "收到第一笔股息收入", "seedling"),
    INCOME_1K("income_1k", "收入破千", "单年股息收入达到1,000元", "sprout"),
    INCOME_10K("income_10k", "收入破万", "单年股息收入达到10,000元", "tree"),
    INCOME_100K("income_100k", "收入十万", "单年股息收入达到100,000元", "forest"),
    PORTFOLIO_START("portfolio_start", "持仓起步", "开始关注第一只股票", "flag"),
    DIVERSIFY_5("diversify_5", "分散投资", "同时持有5只以上股票", "shield"),
    HOLD_1Y("hold_1y", "坚持持有", "最早添加的股票持有满一年", "diamond"),
    STREAK_3Y("streak_3y", "连年分红", "连续3年有股息收入", "snowball");
}
```

To add a new achievement later:
1. Add an enum value to `AchievementDef`
2. Add a condition branch in `AchievementChecker.check()`

### `AchievementChecker`

A pure function (no Android dependencies, easy to unit test):

```kotlin
object AchievementChecker {
    data class CheckContext(
        val stocks: List<StockEntity>,
        val yearlyTotals: Map<Int, Double>,   // year -> total
        val hasAnyIncomeRecord: Boolean
    )

    fun check(ctx: CheckContext): Set<String> {
        val unlocked = mutableSetOf<String>()

        if (ctx.hasAnyIncomeRecord) unlocked += FIRST_DIVIDEND.id

        val maxIncome = ctx.yearlyTotals.values.maxOrNull() ?: 0.0
        if (maxIncome >= 1_000) unlocked += INCOME_1K.id
        if (maxIncome >= 10_000) unlocked += INCOME_10K.id
        if (maxIncome >= 100_000) unlocked += INCOME_100K.id

        if (ctx.stocks.isNotEmpty()) unlocked += PORTFOLIO_START.id
        if (ctx.stocks.size >= 5) unlocked += DIVERSIFY_5.id

        val earliestAddedAt = ctx.stocks.minOfOrNull { it.addedAt } ?: 0L
        if (earliestAddedAt > 0 && System.currentTimeMillis() - earliestAddedAt >= 365L * 24 * 3600 * 1000) {
            unlocked += HOLD_1Y.id
        }

        // Consecutive years with income
        val years = ctx.yearlyTotals.keys.sorted()
        if (years.size >= 3) {
            var maxStreak = 1
            for (i in 1 until years.size) {
                if (years[i] == years[i-1] + 1) maxStreak++
                else maxStreak = 1
            }
            if (maxStreak >= 3) unlocked += STREAK_3Y.id
        }

        return unlocked
    }
}
```

## 3. Repository

### `AchievementRepository`

```kotlin
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

## 4. ViewModel

### New `AchievementViewModel`

```kotlin
@HiltViewModel
class AchievementViewModel @Inject constructor(
    private val achievementRepository: AchievementRepository,
    private val stockRepository: StockRepository,
    private val incomeRepository: DividendIncomeRepository
) : ViewModel() {
    // Expose List<AchievementUiModel> (definition + unlock state)
}
```

**UiState:**
```kotlin
data class AchievementUiState(
    val achievements: List<AchievementItem> = emptyList(),
    val isLoading: Boolean = true
)

data class AchievementItem(
    val def: AchievementDef,
    val unlocked: Boolean,
    val unlockedAt: Long? = null
)
```

**Init logic:**
1. Observe `stocks`, `yearlyTotals`, `achievementRepository.observeAll()` in parallel
2. On each emission, run `AchievementChecker.check()`
3. Sync new unlocks via `achievementRepository.syncAchievements()`
4. Combine definitions + unlock state into `List<AchievementItem>`

## 5. UI

### New `AchievementTabContent` in HomeScreen

Replace the current `when (selectedTabIndex)` with a third branch:

```kotlin
when (selectedTabIndex) {
    0 -> WatchlistContent(...)
    1 -> IncomeTabContent(...)
    2 -> AchievementTabContent(achievementState)
}
```

Add third Tab: `Tab(selected = selectedTabIndex == 2, text = { Text("成就") })`

### New `AchievementGrid` component

Grid layout (2 columns) of achievement cards:
- **Unlocked:** colored icon + title + description + unlock date
- **Locked:** greyed out icon + title + description + "未解锁"

Uses `LazyVerticalGrid` with 2 columns, items at ~80dp height each.

### New `AchievementCard` component

Single card for one achievement. Shows icon (emoji for now, Material Icon later), title, description, and unlock status.

## 6. Files Summary

**New files:**
- `data/local/entity/AchievementEntity.kt`
- `data/local/dao/AchievementDao.kt`
- `data/repository/AchievementRepository.kt`
- `viewmodel/AchievementChecker.kt` (pure function, no Android deps)
- `viewmodel/AchievementViewModel.kt`
- `ui/component/AchievementCard.kt`
- `ui/component/AchievementGrid.kt`
- `viewmodel/AchievementViewModelTest.kt`
- `viewmodel/AchievementCheckerTest.kt`

**Modified files:**
- `AppDatabase.kt` — version 6→7, add entity + DAO + migration
- `DatabaseModule.kt` — add DAO provider + migration
- `HomeScreen.kt` — add third Tab + `AchievementTabContent`
- `libs.versions.toml` / `build.gradle.kts` — no changes needed (no new dependencies)

## Out of Scope
- Animated unlock celebration
- Push notification on unlock
- Achievement sharing
- Cloud sync of achievements
