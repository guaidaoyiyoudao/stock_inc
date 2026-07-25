# 股票筛选与删除行业配置栏 — 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 删除 PortfolioScreen 的「行业配置栏」UI 区块；为持仓/自选股增加「行业 + 自定义标签」双维度 AND FilterChip 筛选。

**Architecture:** 新增多对多 `stock_tags(code, tag)` 表（FK CASCADE，DB v13→v14）承载标签；`PortfolioViewModel` 引入 `tagsByCodeFlow` 与纯函数 `applyFilter`，把筛后列表写进 `filteredItems`/`filteredWatchlist`；`PortfolioScreen` 顶部加 `LazyRow` of `FilterChip`（行业组 + 标签组），删除行业配置区块；`EditHoldingScreen` 加 `FlowRow` of `InputChip` + 添加标签弹窗。同步更新 `BackupContainer`/`BackupRepository` 让标签参与备份。

**Tech Stack:** Kotlin 2.0.21 + Java 17、Jetpack Compose（Material3 `FilterChip`/`InputChip`/`FlowRow`）、Room v14、Hilt、Coroutines Flow、Truth + MockK + runTest。

**Reference spec:** `docs/superpowers/specs/2026-07-25-stock-filter-design.md`

---

## File Structure

**Create:**
- `app/src/main/java/com/stock/dividend/data/local/entity/StockTagEntity.kt` — 多对多标签实体
- `app/src/main/java/com/stock/dividend/data/local/dao/StockTagDao.kt` — 标签 DAO
- `app/src/test/java/com/stock/dividend/viewmodel/PortfolioFilterTest.kt` — `applyFilter` 纯函数单测

**Modify:**
- `app/src/main/java/com/stock/dividend/data/local/AppDatabase.kt` — v14、注册 `StockTagEntity`、`MIGRATION_13_14`、`stockTagDao()`
- `app/src/main/java/com/stock/dividend/di/DatabaseModule.kt` — 注入 `StockTagDao`、加 migration、补 `BackupRepository` 入参
- `app/src/main/java/com/stock/dividend/data/repository/StockRepository.kt` — 加 `stockTagDao` 字段与 `observeAllTags`/`observeTagsForStock`/`setStockTags`
- `app/src/main/java/com/stock/dividend/data/local/backup/BackupData.kt` — `BackupContainer.stockTags`
- `app/src/main/java/com/stock/dividend/data/repository/BackupRepository.kt` — export/import stock_tags
- `app/src/main/java/com/stock/dividend/viewmodel/PortfolioViewModel.kt` — `PortfolioItem.tags`、`tagsByCodeFlow`、`applyFilter`、新 state 字段、toggle/clear
- `app/src/main/java/com/stock/dividend/ui/screen/PortfolioScreen.kt` — 删行业区块、加 `FilterChipsRow`
- `app/src/main/java/com/stock/dividend/viewmodel/EditHoldingViewModel.kt` — 标签 state + add/remove/save
- `app/src/main/java/com/stock/dividend/ui/screen/EditHoldingScreen.kt` — 标签编辑 UI
- `app/src/test/java/com/stock/dividend/data/repository/StockRepositoryTest.kt` — 补 `stockTagDao` mock
- `app/build.gradle.kts` — versionCode 5→6, versionName 3.0.2→3.1.0

---

## Task 1: 新增 StockTagEntity

**Files:**
- Create: `app/src/main/java/com/stock/dividend/data/local/entity/StockTagEntity.kt`

- [ ] **Step 1: 创建实体**

```kotlin
package com.stock.dividend.data.local.entity

import androidx.compose.runtime.Stable
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Stable
@Entity(
    tableName = "stock_tags",
    primaryKeys = ["stockCode", "tag"],
    foreignKeys = [ForeignKey(
        entity = StockEntity::class,
        parentColumns = ["code"],
        childColumns = ["stockCode"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("stockCode"), Index("tag")]
)
data class StockTagEntity(
    val stockCode: String,
    val tag: String,
    val createdAt: Long = System.currentTimeMillis()
)
```

- [ ] **Step 2: 编译验证**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL（实体未被任何 DAO 引用，但应单独编译通过）

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/stock/dividend/data/local/entity/StockTagEntity.kt
git commit -m "feat: add StockTagEntity for multi-tag-per-stock"
```

---

## Task 2: 新增 StockTagDao

**Files:**
- Create: `app/src/main/java/com/stock/dividend/data/local/dao/StockTagDao.kt`

- [ ] **Step 1: 创建 DAO**

```kotlin
package com.stock.dividend.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.stock.dividend.data.local.entity.StockTagEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface StockTagDao {

    @Query("SELECT * FROM stock_tags")
    fun observeAll(): Flow<List<StockTagEntity>>

    @Query("SELECT * FROM stock_tags WHERE stockCode = :code")
    fun observeByStock(code: String): Flow<List<StockTagEntity>>

    @Query("SELECT DISTINCT tag FROM stock_tags ORDER BY tag")
    fun observeAllTags(): Flow<List<String>>

    @Query("SELECT tag FROM stock_tags WHERE stockCode = :code")
    suspend fun getTagsForStock(code: String): List<String>

    @Query("SELECT DISTINCT tag FROM stock_tags ORDER BY tag")
    suspend fun getAllTags(): List<String>

    @Query("SELECT * FROM stock_tags")
    suspend fun getAll(): List<StockTagEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(tag: StockTagEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(tags: List<StockTagEntity>)

    @Query("DELETE FROM stock_tags WHERE stockCode = :code AND tag = :tag")
    suspend fun delete(stockCode: String, tag: String)

    @Query("DELETE FROM stock_tags WHERE stockCode = :code")
    suspend fun clearForStock(code: String)

    @Query("DELETE FROM stock_tags")
    suspend fun deleteAll()
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/stock/dividend/data/local/dao/StockTagDao.kt
git commit -m "feat: add StockTagDao with observe/insert/clear queries"
```

---

## Task 3: AppDatabase 升级 v14 + MIGRATION_13_14

**Files:**
- Modify: `app/src/main/java/com/stock/dividend/data/local/AppDatabase.kt`

- [ ] **Step 1: 在 `AppDatabase.kt` 顶部 imports 加入 `StockTagEntity` 和 `StockTagDao`**

在 `import com.stock.dividend.data.local.entity.StockEntity` 之后添加：

```kotlin
import com.stock.dividend.data.local.entity.StockTagEntity
```

在 `import com.stock.dividend.data.local.dao.StockDao` 之后添加：

```kotlin
import com.stock.dividend.data.local.dao.StockTagDao
```

- [ ] **Step 2: 在 `@Database` 注解的 entities 列表追加 `StockTagEntity::class`，version 改 14**

把 `AppDatabase.kt:30-46` 的 `@Database(...)` 替换为：

```kotlin
@Database(
    entities = [
        StockEntity::class,
        DividendEntity::class,
        FireGoalEntity::class,
        DividendIncomeRecordEntity::class,
        TransactionEntity::class,
        AchievementEntity::class,
        LivingExpenseItemEntity::class,
        NotificationRuleEntity::class,
        IndustryTargetEntity::class,
        PriceCacheEntity::class,
        SearchCacheEntity::class,
        StockTagEntity::class
    ],
    version = 14,
    exportSchema = false
)
```

- [ ] **Step 3: 在 abstract class body 加 stockTagDao()**

在 `abstract fun searchCacheDao(): SearchCacheDao` 之后追加：

```kotlin
    abstract fun stockTagDao(): StockTagDao
```

- [ ] **Step 4: 在 companion object 末尾追加 MIGRATION_13_14**

在 `MIGRATION_12_13` 定义之后（`AppDatabase.kt:226` 之前的 `}` 之前）追加：

```kotlin
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `stock_tags` (" +
                            "`stockCode` TEXT NOT NULL, " +
                            "`tag` TEXT NOT NULL, " +
                            "`createdAt` INTEGER NOT NULL, " +
                            "PRIMARY KEY(`stockCode`, `tag`), " +
                            "FOREIGN KEY(`stockCode`) REFERENCES `stocks`(`code`) " +
                            "ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_stock_tags_stockCode` " +
                            "ON `stock_tags`(`stockCode`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_stock_tags_tag` " +
                            "ON `stock_tags`(`tag`)"
                )
            }
        }
```

- [ ] **Step 5: 编译验证**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/stock/dividend/data/local/AppDatabase.kt
git commit -m "feat: bump AppDatabase to v14 with stock_tags migration"
```

---

## Task 4: DatabaseModule 注入 DAO + 注册 migration + Backup 入参

**Files:**
- Modify: `app/src/main/java/com/stock/dividend/di/DatabaseModule.kt`

- [ ] **Step 1: 加 import**

在 `import com.stock.dividend.data.local.dao.StockDao` 后追加：

```kotlin
import com.stock.dividend.data.local.dao.StockTagDao
```

- [ ] **Step 2: addMigrations 末尾追加 MIGRATION_13_14**

把 `DatabaseModule.kt:37` 的 `.addMigrations(...)` 行末尾的 `AppDatabase.MIGRATION_12_13)` 改为 `AppDatabase.MIGRATION_12_13, AppDatabase.MIGRATION_13_14)`。

- [ ] **Step 3: 加 provideStockTagDao**

在 `provideSearchCacheDao` 之后追加：

```kotlin
    @Provides
    fun provideStockTagDao(db: AppDatabase): StockTagDao = db.stockTagDao()
```

- [ ] **Step 4: provideBackupRepository 加入 stockTagDao 参数**

把 `DatabaseModule.kt:74-92` 的 `provideBackupRepository` 替换为：

```kotlin
    @Provides
    @Singleton
    fun provideBackupRepository(
        db: AppDatabase,
        stockDao: StockDao,
        dividendDao: DividendDao,
        fireGoalDao: FireGoalDao,
        dividendIncomeRecordDao: DividendIncomeRecordDao,
        transactionDao: TransactionDao,
        achievementDao: AchievementDao,
        livingExpenseItemDao: LivingExpenseItemDao,
        notificationRuleDao: NotificationRuleDao,
        stockTagDao: StockTagDao
    ): BackupRepository {
        return BackupRepository(
            db, stockDao, dividendDao, fireGoalDao,
            dividendIncomeRecordDao, transactionDao,
            achievementDao, livingExpenseItemDao, notificationRuleDao,
            stockTagDao
        )
    }
```

- [ ] **Step 5: 编译验证**

Run: `./gradlew :app:compileDebugKotlin`
Expected: 编译失败（BackupRepository 构造函数还没加 stockTagDao 参数）—— 这是预期的，下一 Task 修。

- [ ] **Step 6: Commit（与 Task 5 一起提交，避免中间断编译）**

暂不 commit，等 Task 5 完成后一起提交。

---

## Task 5: StockRepository 加 stockTagDao 与标签方法

**Files:**
- Modify: `app/src/main/java/com/stock/dividend/data/repository/StockRepository.kt`
- Modify: `app/src/test/java/com/stock/dividend/data/repository/StockRepositoryTest.kt`

- [ ] **Step 1: 加 import**

在 `StockRepository.kt` 的 DAO import 区追加：

```kotlin
import com.stock.dividend.data.local.dao.StockTagDao
import com.stock.dividend.data.local.entity.StockTagEntity
```

- [ ] **Step 2: 构造函数加 stockTagDao 字段**

把 `StockRepository.kt:44-53` 的构造函数替换为：

```kotlin
@Singleton
class StockRepository @Inject constructor(
    private val api: SearchApi,
    private val quoteApi: QuoteApi,
    private val stockDao: StockDao,
    private val transactionDao: TransactionDao,
    private val industryTargetDao: IndustryTargetDao,
    private val priceCacheDao: PriceCacheDao,
    private val searchCacheDao: SearchCacheDao,
    private val stockTagDao: StockTagDao,
    private val appDatabase: AppDatabase
) {
```

- [ ] **Step 3: 在 `deleteIndustryTarget` 方法之后（class 末尾 `}` 之前）追加标签方法**

```kotlin
    // ---------- 股票标签 ----------

    /** 全量订阅所有 (code, tag)，ViewModel 据此算 tagsByCode 映射。 */
    fun observeAllStockTags(): Flow<List<StockTagEntity>> = stockTagDao.observeAll()

    /** 全局所有出现过的标签（去重排序），供 EditHolding 输入建议。 */
    fun observeAllTags(): Flow<List<String>> = stockTagDao.observeAllTags()

    /** 某只股票当前的所有标签（Flow，编辑页订阅用）。 */
    fun observeTagsForStock(code: String): Flow<List<String>> =
        stockTagDao.observeByStock(code).map { list -> list.map { it.tag } }

    /**
     * 全量覆盖某只股票的标签集合：事务内先 clear，再批量 insert。
     * 标签去空白并去重；空标签自动忽略。
     */
    suspend fun setStockTags(code: String, tags: List<String>) {
        val normalized = tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        appDatabase.withTransaction {
            stockTagDao.clearForStock(code)
            normalized.forEach { tag ->
                stockTagDao.insert(StockTagEntity(stockCode = code, tag = tag))
            }
        }
    }
```

注：`map` 已经在文件顶部 import（来自 `kotlinx.coroutines.flow.map`）。如未 import 则加 `import kotlinx.coroutines.flow.map`。

- [ ] **Step 4: 编译验证**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL（此时 BackupRepository 也还需要改 —— 见 Task 6，所以这一步可能仍失败，可放与 Task 6 合并验证）

- [ ] **Step 5: 修 StockRepositoryTest 构造**

把 `StockRepositoryTest.kt:40-53` 替换为：

```kotlin
class StockRepositoryTest {

    private val api: SearchApi = mockk()
    private val quoteApi: QuoteApi = mockk()
    private val dao: StockDao = mockk(relaxed = true)
    private val transactionDao: TransactionDao = mockk(relaxed = true)
    private val industryTargetDao: IndustryTargetDao = mockk(relaxed = true)
    private val priceCacheDao: PriceCacheDao = mockk(relaxed = true)
    private val searchCacheDao: SearchCacheDao = mockk(relaxed = true)
    private val stockTagDao: StockTagDao = mockk(relaxed = true)
    private val appDatabase: AppDatabase = mockk(relaxed = true)
    private val repository = StockRepository(
        api, quoteApi, dao, transactionDao, industryTargetDao,
        priceCacheDao, searchCacheDao, stockTagDao, appDatabase
    )
```

并在 import 区追加：

```kotlin
import com.stock.dividend.data.local.dao.StockTagDao
```

- [ ] **Step 6: 跑 StockRepositoryTest 全绿**

Run: `./gradlew :app:testDebugUnitTest --tests "com.stock.dividend.data.repository.StockRepositoryTest"`
Expected: BUILD SUCCESSFUL, 所有原有测试通过（构造函数已对齐）

- [ ] **Step 7: 加 setStockTags 单测**

在 `StockRepositoryTest.kt` 末尾 `private fun stockItem(...)` 之前追加：

```kotlin
    @Test
    fun `setStockTags clears then inserts normalized distinct tags in a transaction`() = runTest {
        repository.setStockTags("sh.600036", listOf(" 高息 ", "白马", "高息", ""))

        coVerify { stockTagDao.clearForStock("sh.600036") }
        coVerify { stockTagDao.insert(match { it.stockCode == "sh.600036" && it.tag == "高息" }) }
        coVerify { stockTagDao.insert(match { it.stockCode == "sh.600036" && it.tag == "白马" }) }
        // 空/去重后只剩 2 个 insert
        coVerify(exactly = 2) { stockTagDao.insert(any()) }
    }
```

注：`coVerify` 已在 import 中。

- [ ] **Step 8: 跑测试验证通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.stock.dividend.data.repository.StockRepositoryTest"`
Expected: BUILD SUCCESSFUL，新增 `setStockTags...` 测试通过。

- [ ] **Step 9: Commit（Task 4 + 5 + 测试一起提交，保证编译/测试一致）**

```bash
git add app/src/main/java/com/stock/dividend/di/DatabaseModule.kt \
        app/src/main/java/com/stock/dividend/data/repository/StockRepository.kt \
        app/src/test/java/com/stock/dividend/data/repository/StockRepositoryTest.kt
git commit -m "feat: inject StockTagDao and add setStockTags/observeTags to StockRepository"
```

---

## Task 6: BackupContainer + BackupRepository 支持 stock_tags

**Files:**
- Modify: `app/src/main/java/com/stock/dividend/data/local/backup/BackupData.kt`
- Modify: `app/src/main/java/com/stock/dividend/data/repository/BackupRepository.kt`
- Modify: `app/src/test/java/com/stock/dividend/data/repository/BackupRepositoryTest.kt`

- [ ] **Step 1: BackupContainer 加 stockTags 字段**

把 `BackupData.kt` 的 imports 替换（在 `StockEntity` 后追加 `StockTagEntity`）：

```kotlin
import com.stock.dividend.data.local.entity.AchievementEntity
import com.stock.dividend.data.local.entity.DividendEntity
import com.stock.dividend.data.local.entity.DividendIncomeRecordEntity
import com.stock.dividend.data.local.entity.FireGoalEntity
import com.stock.dividend.data.local.entity.LivingExpenseItemEntity
import com.stock.dividend.data.local.entity.NotificationRuleEntity
import com.stock.dividend.data.local.entity.StockEntity
import com.stock.dividend.data.local.entity.StockTagEntity
import com.stock.dividend.data.local.entity.TransactionEntity
```

把 `BackupContainer` data class 替换为：

```kotlin
data class BackupContainer(
    val metadata: BackupMetadata,
    val stocks: List<StockEntity>,
    val dividends: List<DividendEntity>,
    val fireGoals: List<FireGoalEntity>,
    val dividendIncomeRecords: List<DividendIncomeRecordEntity>,
    val transactions: List<TransactionEntity>,
    val achievements: List<AchievementEntity>,
    val livingExpenseItems: List<LivingExpenseItemEntity>,
    val notificationRules: List<NotificationRuleEntity>,
    val stockTags: List<StockTagEntity> = emptyList()
)
```

`stockTags` 默认 `emptyList()` 保证旧备份 JSON（无此字段）反序列化不报错。

- [ ] **Step 2: BackupRepository 构造函数加 stockTagDao**

把 `BackupRepository.kt:29-39` 的构造函数替换为：

```kotlin
@Singleton
class BackupRepository @Inject constructor(
    private val db: AppDatabase,
    private val stockDao: StockDao,
    private val dividendDao: DividendDao,
    private val fireGoalDao: FireGoalDao,
    private val dividendIncomeRecordDao: DividendIncomeRecordDao,
    private val transactionDao: TransactionDao,
    private val achievementDao: AchievementDao,
    private val livingExpenseItemDao: LivingExpenseItemDao,
    private val notificationRuleDao: NotificationRuleDao,
    private val stockTagDao: StockTagDao
) {
```

import 区追加：

```kotlin
import com.stock.dividend.data.local.dao.StockTagDao
import com.stock.dividend.data.local.entity.StockTagEntity
```

- [ ] **Step 3: exportToJson 加入 stockTags**

把 `BackupRepository.kt:44-80` 的 `coroutineScope { ... }` 替换为（在 `rules` 之后加 `stockTags`）：

```kotlin
            val container = coroutineScope {
                val stocks = async { stockDao.getAll() }
                val dividends = async { dividendDao.getAll() }
                val fireGoals = async { fireGoalDao.getAll() }
                val incomeRecords = async { dividendIncomeRecordDao.getAllRecords() }
                val transactions = async { transactionDao.getAll() }
                val achievements = async { achievementDao.getAll() }
                val expenses = async { livingExpenseItemDao.getAllOnce() }
                val rules = async { notificationRuleDao.getAll() }
                val stockTags = async { stockTagDao.getAll() }

                BackupContainer(
                    metadata = BackupMetadata(
                        appVersion = try {
                            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
                        } catch (_: PackageManager.NameNotFoundException) {
                            "unknown"
                        },
                        versionCode = try {
                            @Suppress("DEPRECATION")
                            val code = context.packageManager.getPackageInfo(context.packageName, 0).versionCode
                            code
                        } catch (_: PackageManager.NameNotFoundException) {
                            0
                        },
                        exportTimestamp = System.currentTimeMillis(),
                        dbVersion = db.openHelper.readableDatabase.version
                    ),
                    stocks = stocks.await(),
                    dividends = dividends.await(),
                    fireGoals = fireGoals.await(),
                    dividendIncomeRecords = incomeRecords.await(),
                    transactions = transactions.await(),
                    achievements = achievements.await(),
                    livingExpenseItems = expenses.await(),
                    notificationRules = rules.await(),
                    stockTags = stockTags.await()
                )
            }
```

- [ ] **Step 4: importFromJson 删表 + 插入 stock_tags**

把 `BackupRepository.kt:105-125` 的 `db.withTransaction { ... }` 替换为：

```kotlin
            db.withTransaction {
                // Delete children first (foreign key safety)
                stockTagDao.deleteAll()
                dividendIncomeRecordDao.deleteAll()
                dividendDao.deleteAll()
                transactionDao.deleteAll()
                notificationRuleDao.deleteAll()
                achievementDao.deleteAll()
                livingExpenseItemDao.deleteAll()
                fireGoalDao.delete()
                stockDao.deleteAll()

                // Insert parents first, then children
                stockDao.insertAll(container.stocks)
                fireGoalDao.insertAll(container.fireGoals)
                livingExpenseItemDao.insertAll(container.livingExpenseItems)
                achievementDao.replaceAll(container.achievements)
                notificationRuleDao.insertAll(container.notificationRules)
                dividendDao.insertAll(container.dividends)
                dividendIncomeRecordDao.insertAll(container.dividendIncomeRecords)
                transactionDao.insertAll(container.transactions)
                // stock_tags 必须在 stocks 之后（FK），IGNORE 防御重复主键
                stockTagDao.insertAll(container.stockTags)
            }
```

- [ ] **Step 5: 修 BackupRepositoryTest 现有 round-trip 测试（构造函数已变，需补字段）**

`BackupRepositoryTest` 只测了 Gson 序列化、没有构造 `BackupRepository` 实例，所以构造函数变化不影响现有测试。但 `json round-trip preserves all entity data` 用 `BackupContainer(...)` 显式构造，新加的 `stockTags` 字段有默认值，旧构造调用仍能编译。

为提升覆盖，在该测试的 `BackupContainer(...)` 构造里加一项 `stockTags`，验证标签也参与 round-trip：

在 `BackupRepositoryTest.kt:30` 的 `BackupContainer(...)` 调用末尾（最后一个字段 `notificationRules = listOf(...)` 之后，闭合 `)` 之前）插入：

```kotlin
            ,
            stockTags = listOf(
                StockTagEntity(stockCode = "sh.600036", tag = "高息", createdAt = 5000L),
                StockTagEntity(stockCode = "sz.000001", tag = "白马", createdAt = 6000L)
            )
```

并在 import 区追加：

```kotlin
import com.stock.dividend.data.local.entity.StockTagEntity
```

然后在测试末尾断言（`assertThat(roundTripped...)` 区块）加：

```kotlin
        assertThat(roundTripped.stockTags).hasSize(2)
        assertThat(roundTripped.stockTags.map { it.stockCode to it.tag })
            .containsExactly("sh.600036" to "高息", "sz.000001" to "白马")
```

- [ ] **Step 6: 跑 BackupRepositoryTest 验证**

Run: `./gradlew :app:testDebugUnitTest --tests "com.stock.dividend.data.repository.BackupRepositoryTest"`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: 全量编译**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/stock/dividend/data/local/backup/BackupData.kt \
        app/src/main/java/com/stock/dividend/data/repository/BackupRepository.kt \
        app/src/test/java/com/stock/dividend/data/repository/BackupRepositoryTest.kt
git commit -m "feat: include stock_tags in backup export/import"
```

---

## Task 7: PortfolioViewModel 引入 applyFilter 纯函数（TDD）

**Files:**
- Create: `app/src/test/java/com/stock/dividend/viewmodel/PortfolioFilterTest.kt`
- Modify: `app/src/main/java/com/stock/dividend/viewmodel/PortfolioViewModel.kt`

- [ ] **Step 1: 写失败测试**

Create `app/src/test/java/com/stock/dividend/viewmodel/PortfolioFilterTest.kt`:

```kotlin
package com.stock.dividend.viewmodel

import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.local.entity.StockEntity
import org.junit.Test

class PortfolioFilterTest {

    private fun item(code: String, industry: String) = PortfolioItem(
        code = code, name = code, marketCode = "1", shares = 100,
        costPerShare = 10.0, industry = industry, targetWeight = 0.0
    )

    private fun watch(code: String, industry: String) = StockEntity(
        code = code, name = code, marketCode = "1", shares = 0, industry = industry
    )

    private val items = listOf(
        item("sh.600036", "银行"),
        item("sh.601318", "保险"),
        item("sh.600519", "")   // 未分类
    )

    private val watchlist = listOf(
        watch("sz.000001", "银行"),
        watch("sz.000002", "")
    )

    private val tagsByCode = mapOf(
        "sh.600036" to listOf("高息"),
        "sh.601318" to listOf("白马"),
        "sz.000001" to listOf("高息", "白马")
    )

    @Test
    fun `empty selections returns all`() {
        val (fi, fw) = applyPortfolioFilter(
            items, watchlist, tagsByCode,
            selectedIndustries = emptySet(), selectedTags = emptySet()
        )
        assertThat(fi).hasSize(3)
        assertThat(fw).hasSize(2)
    }

    @Test
    fun `industry filter narrows both lists, unclassified bucket`() {
        val (fi, fw) = applyPortfolioFilter(
            items, watchlist, tagsByCode,
            selectedIndustries = setOf("银行"), selectedTags = emptySet()
        )
        assertThat(fi.map { it.code }).containsExactly("sh.600036")
        assertThat(fw.map { it.code }).containsExactly("sz.000001")

        val (fi2, _) = applyPortfolioFilter(
            items, watchlist, tagsByCode,
            selectedIndustries = setOf("未分类"), selectedTags = emptySet()
        )
        assertThat(fi2.map { it.code }).containsExactly("sh.600519")
    }

    @Test
    fun `multiple industries are OR`() {
        val (fi, _) = applyPortfolioFilter(
            items, watchlist, tagsByCode,
            selectedIndustries = setOf("银行", "保险"), selectedTags = emptySet()
        )
        assertThat(fi.map { it.code }).containsExactly("sh.600036", "sh.601318")
    }

    @Test
    fun `tags filter is OR within tags`() {
        val (fi, fw) = applyPortfolioFilter(
            items, watchlist, tagsByCode,
            selectedIndustries = emptySet(), selectedTags = setOf("高息", "白马")
        )
        // sh.600036=高息 ✓ ; sh.601318=白马 ✓ ; sh.600519 无标签 ✗
        assertThat(fi.map { it.code }).containsExactly("sh.600036", "sh.601318")
        assertThat(fw.map { it.code }).containsExactly("sz.000001")
    }

    @Test
    fun `cross-dimension is AND`() {
        val (fi, fw) = applyPortfolioFilter(
            items, watchlist, tagsByCode,
            selectedIndustries = setOf("银行"), selectedTags = setOf("高息")
        )
        // 银行 ∩ 高息: sh.600036(银行+高息) ✓ ; sz.000001(银行+高息) ✓
        assertThat(fi.map { it.code }).containsExactly("sh.600036")
        assertThat(fw.map { it.code }).containsExactly("sz.000001")

        val (fi2, _) = applyPortfolioFilter(
            items, watchlist, tagsByCode,
            selectedIndustries = setOf("银行"), selectedTags = setOf("白马")
        )
        // 银行 ∩ 白马: sh.600036(银行但只高息) ✗
        assertThat(fi2).isEmpty()
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.stock.dividend.viewmodel.PortfolioFilterTest"`
Expected: 编译失败 —— `applyPortfolioFilter` 与 `PortfolioItem.tags` 都不存在。

- [ ] **Step 3: 在 PortfolioViewModel.kt 加 applyPortfolioFilter 顶层纯函数**

在 `PortfolioViewModel.kt` 顶部 `PortfolioItem` data class 定义里追加 `tags` 字段：

把 `PortfolioViewModel.kt:42-59` 的 `PortfolioItem` 替换为：

```kotlin
@Stable
data class PortfolioItem(
    val code: String,
    val name: String,
    val marketCode: String,
    val shares: Int,
    val costPerShare: Double,
    val industry: String = "",
    val currentPrice: Double? = null,
    val marketValue: Double? = null,
    val totalCost: Double,
    val unrealizedPnl: Double? = null,
    val unrealizedPnlRate: Double? = null,
    val actualWeight: Double? = null,
    /** 个股目标：占其所属行业的 %（两层配比模型，行业主个股次）。 */
    val targetWeight: Double,
    val targetValue: Double? = null,
    val targetDiff: Double? = null,
    /** 该股票的所有标签，由 PortfolioViewModel 从 stock_tags 表注入。 */
    val tags: List<String> = emptyList()
)
```

在 `PortfolioViewModel.kt` 文件末尾（最后一个 `}` 之后）追加顶层函数：

```kotlin
/**
 * 持仓/自选股筛选纯函数。
 * - 行业组内 OR、标签组内 OR、跨组 AND
 * - industry="" 归入「未分类」桶
 * - 任一组的 selected 集合为空 = 该组不参与筛选（即放行全部）
 */
fun applyPortfolioFilter(
    items: List<PortfolioItem>,
    watchlist: List<StockEntity>,
    tagsByCode: Map<String, List<String>>,
    selectedIndustries: Set<String>,
    selectedTags: Set<String>
): Pair<List<PortfolioItem>, List<StockEntity>> {
    fun matchIndustry(industry: String): Boolean {
        if (selectedIndustries.isEmpty()) return true
        return industry.ifEmpty { "未分类" } in selectedIndustries
    }
    fun matchTags(code: String): Boolean {
        if (selectedTags.isEmpty()) return true
        return tagsByCode[code].orEmpty().any { it in selectedTags }
    }
    val fi = items.filter { matchIndustry(it.industry) && matchTags(it.code) }
    val fw = watchlist.filter { matchIndustry(it.industry) && matchTags(it.code) }
    return fi to fw
}
```

`StockEntity` 已在文件顶部 import。

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.stock.dividend.viewmodel.PortfolioFilterTest"`
Expected: BUILD SUCCESSFUL, 5 个测试全绿

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/stock/dividend/viewmodel/PortfolioViewModel.kt \
        app/src/test/java/com/stock/dividend/viewmodel/PortfolioFilterTest.kt
git commit -m "feat: add applyPortfolioFilter pure function with AND/OR semantics"
```

---

## Task 8: PortfolioViewModel 接入 tags flow + state + 事件方法

**Files:**
- Modify: `app/src/main/java/com/stock/dividend/viewmodel/PortfolioViewModel.kt`

- [ ] **Step 1: PortfolioUiState 加新字段**

把 `PortfolioViewModel.kt:91-122` 的 `PortfolioUiState` 替换为（仅追加，不改原有字段）：

```kotlin
@Stable
data class PortfolioUiState(
    val items: List<PortfolioItem> = emptyList(),
    val watchlist: List<StockEntity> = emptyList(),
    val stockForecasts: Map<String, StockForecast> = emptyMap(),
    val forecastTotal: Double = 0.0,
    val livingExpenseTargetAmount: Double? = null,
    val fireProgress: Float? = null,
    val industryGroups: List<IndustryGroup> = emptyList(),
    val industryTargetSum: Double = 0.0,
    val totalAssets: Double = 0.0,
    val holdingsMarketValue: Double = 0.0,
    val totalCost: Double = 0.0,
    val totalPnl: Double = 0.0,
    val totalPnlRate: Double = 0.0,
    val targetWeightSum: Double = 0.0,
    val isLoading: Boolean = false,
    val isRefreshingIndustry: Boolean = false,
    val error: String? = null,
    val editingCode: String? = null,
    val editingWeightInput: String = "",
    val editingWeightError: String? = null,
    val editingTotalAssets: Boolean = false,
    val editingTotalAssetsInput: String = "",
    val editingTotalAssetsError: String? = null,
    val editingIndustry: String? = null,
    val editingIndustryWeightInput: String = "",
    val editingIndustryWeightError: String? = null,
    val deletedStock: StockEntity? = null,
    val deletedTransactions: List<TransactionEntity> = emptyList(),
    // ── 筛选 ──────────────────────────────────────────────────────
    /** 候选行业（来自所有持仓+自选，去重排序，含「未分类」若有空 industry）。 */
    val availableIndustries: List<String> = emptyList(),
    /** 全局已存在的所有标签。 */
    val availableTags: List<String> = emptyList(),
    val selectedIndustries: Set<String> = emptySet(),
    val selectedTags: Set<String> = emptySet(),
    /** 筛选后的持仓股（直接渲染）。 */
    val filteredItems: List<PortfolioItem> = emptyList(),
    /** 筛选后的自选股（直接渲染）。 */
    val filteredWatchlist: List<StockEntity> = emptyList()
)
```

- [ ] **Step 2: 构造函数注入 stockRepository 已有；新增 tagsByCodeFlow**

`stockRepository` 已在构造函数。在 `PortfolioViewModel` 类内（`forecastMapFlow` 定义之后、`init` 之前）加：

```kotlin
    /** 全量 (code → tags) 映射，订阅 stock_tags 表。 */
    private val tagsByCodeFlow = stockRepository.observeAllStockTags()
        .map { list -> list.groupBy { it.stockCode }.mapValues { it.value.map { e -> e.tag } } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** 所有出现过的标签（去重排序）。 */
    private val allTagsFlow = stockRepository.observeAllTags()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
```

- [ ] **Step 3: 新增 collector 5 — 标签/行业候选 + 筛选重算**

在 `init { ... }` 内的 collector 4 之后追加：

```kotlin
        // Collector 5: 标签变化 → 重算候选标签 + 把 tags 注入 items + 重算筛选
        viewModelScope.launch {
            combine(allStocksFlow, tagsByCodeFlow, allTagsFlow) { stocks, tagsByCode, allTags ->
                Triple(stocks, tagsByCode, allTags)
            }.collect { (stocks, tagsByCode, allTags) ->
                val industries = stocks.map { it.industry.ifEmpty { "未分类" } }.distinct().sorted()
                _uiState.update { state ->
                    val itemsWithTags = state.items.map { it.copy(tags = tagsByCode[it.code].orEmpty()) }
                    val (fi, fw) = applyPortfolioFilter(
                        itemsWithTags, state.watchlist, tagsByCode,
                        state.selectedIndustries, state.selectedTags
                    )
                    state.copy(
                        availableIndustries = industries,
                        availableTags = allTags,
                        items = itemsWithTags,
                        filteredItems = fi,
                        filteredWatchlist = fw
                    )
                }
            }
        }
```

- [ ] **Step 4: publish() 把新 items 重新筛选写入 filteredItems/filteredWatchlist**

把 `publish()` (`PortfolioViewModel.kt:535-550`) 替换为：

```kotlin
    private fun publish(result: RecomputeResult) {
        _uiState.update {
            val tagsByCode = _uiState.value.let { st ->
                // tagsByCodeFlow 的最新值不在 RecomputeResult 里；用当前 items 的 tags 兜底
                st.items.associate { it.code to it.tags }
            }
            val itemsWithTags = result.items.map { newItem ->
                newItem.copy(tags = tagsByCode[newItem.code].orEmpty())
            }
            val (fi, fw) = applyPortfolioFilter(
                itemsWithTags, it.watchlist, tagsByCode,
                it.selectedIndustries, it.selectedTags
            )
            it.copy(
                items = itemsWithTags,
                industryGroups = result.industryGroups,
                industryTargetSum = result.industryTargetSum,
                holdingsMarketValue = result.holdingsMarketValue,
                totalCost = result.totalCost,
                totalPnl = result.totalPnl,
                totalPnlRate = result.totalPnlRate,
                targetWeightSum = result.targetWeightSum,
                isLoading = result.isLoading,
                error = null,
                filteredItems = fi,
                filteredWatchlist = fw
            )
        }
    }
```

- [ ] **Step 5: Collector 4（watchlist）也需触发重算筛选**

把 Collector 4 的 `_uiState.update { state -> state.copy(...) }` 末尾（`PortfolioViewModel.kt:304-320`）替换为：

```kotlin
                _uiState.update { state ->
                    val newWatchlist = stocks.filter { it.shares <= 0 }
                    val (fi, fw) = applyPortfolioFilter(
                        state.items, newWatchlist,
                        state.items.associate { it.code to it.tags },
                        state.selectedIndustries, state.selectedTags
                    )
                    state.copy(
                        watchlist = newWatchlist,
                        stockForecasts = forecasts.mapValues { (code, forecast) ->
                            val previous = state.stockForecasts[code]
                            val cachedPrice = cachedPrices[code]
                            forecast.copy(
                                currentPrice = previous?.currentPrice ?: cachedPrice,
                                marketValue = previous?.marketValue
                                    ?: cachedPrice?.let { if (forecast.shares > 0) it * forecast.shares else null }
                            )
                        },
                        forecastTotal = forecastTotal,
                        livingExpenseTargetAmount = livingExpenseTarget,
                        fireProgress = progress,
                        filteredItems = fi,
                        filteredWatchlist = fw
                    )
                }
```

- [ ] **Step 6: 加 4 个 toggle/clear 方法**

在类内（`confirmEditIndustry` 之后）追加：

```kotlin
    fun toggleIndustryFilter(industry: String) {
        _uiState.update { state ->
            val newSel = if (industry in state.selectedIndustries) {
                state.selectedIndustries - industry
            } else state.selectedIndustries + industry
            reapplyFilter(state.copy(selectedIndustries = newSel))
        }
    }

    fun clearIndustryFilter() {
        _uiState.update { reapplyFilter(it.copy(selectedIndustries = emptySet())) }
    }

    fun toggleTagFilter(tag: String) {
        _uiState.update { state ->
            val newSel = if (tag in state.selectedTags) state.selectedTags - tag
            else state.selectedTags + tag
            reapplyFilter(state.copy(selectedTags = newSel))
        }
    }

    fun clearTagFilter() {
        _uiState.update { reapplyFilter(it.copy(selectedTags = emptySet())) }
    }

    private fun reapplyFilter(state: PortfolioUiState): PortfolioUiState {
        val tagsByCode = state.items.associate { it.code to it.tags }
        val (fi, fw) = applyPortfolioFilter(
            state.items, state.watchlist, tagsByCode,
            state.selectedIndustries, state.selectedTags
        )
        return state.copy(filteredItems = fi, filteredWatchlist = fw)
    }
```

- [ ] **Step 7: 编译验证**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 8: 跑 PortfolioFilterTest 仍绿**

Run: `./gradlew :app:testDebugUnitTest --tests "com.stock.dividend.viewmodel.PortfolioFilterTest"`
Expected: BUILD SUCCESSFUL

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/stock/dividend/viewmodel/PortfolioViewModel.kt
git commit -m "feat: wire tags flow + filter state into PortfolioViewModel"
```

---

## Task 9: PortfolioScreen 删除行业配置区块

**Files:**
- Modify: `app/src/main/java/com/stock/dividend/ui/screen/PortfolioScreen.kt`

- [ ] **Step 1: 删除 industryExpanded 局部变量**

删除 `PortfolioScreen.kt:141`：

```kotlin
    var industryExpanded by remember { mutableStateOf(true) }
```

（保留 `holdingsExpanded`）

- [ ] **Step 2: 删除行业配置整个 item 块**

删除 `PortfolioScreen.kt:181-254` 整段（从 `// 行业配置区块` 注释到对应闭合 `}`，包括折叠头 item、提示语 item、饼图 Card item、行业卡片 items）。删除后该位置直接接 `// 个股持仓区块`。

- [ ] **Step 3: 删除 EditIndustryDialog 的调用**

删除 `PortfolioScreen.kt:364-373`：

```kotlin
    uiState.editingIndustry?.let { industry ->
        EditIndustryDialog(
            industry = industry,
            weightInput = uiState.editingIndustryWeightInput,
            error = uiState.editingIndustryWeightError,
            onInputChange = viewModel::onIndustryWeightInputChanged,
            onConfirm = viewModel::confirmEditIndustry,
            onDismiss = viewModel::dismissDialog
        )
    }
```

- [ ] **Step 4: 删除私有 composable IndustryAllocationCard 与 EditIndustryDialog 定义**

删除 `PortfolioScreen.kt:997-1110` 这一段（从 `@OptIn(ExperimentalFoundationApi::class)` `private fun IndustryAllocationCard(` 到 `EditIndustryDialog` 函数末尾的 `}`）。

- [ ] **Step 5: 删除不再使用的 import**

删除 `PortfolioScreen.kt:81`：

```kotlin
import com.stock.dividend.ui.component.IndustryAllocationPieChart
```

**注意不要删** `import com.stock.dividend.viewmodel.IndustryGroup`（如果该 import 仅被已删的 IndustryAllocationCard 用到则删；但 `industryGroups` state 字段类型仍是 `List<IndustryGroup>`，所以 `IndustryGroup` 在 ViewModel 文件里而非 screen，screen 里若 import 了则可删）。先编译，按报错删 import。

`animateFloatAsState`、`rotate`、`KeyboardArrowDown` 仍被 `holdingsExpanded` 折叠头用到，**保留**。

- [ ] **Step 6: 编译验证**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL（若有 unused import 报 warning 不阻断；若有 error 按报错清理 import）

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/stock/dividend/ui/screen/PortfolioScreen.kt
git commit -m "feat: remove industry allocation section from PortfolioScreen"
```

---

## Task 10: PortfolioScreen 加 FilterChipsRow

**Files:**
- Modify: `app/src/main/java/com/stock/dividend/ui/screen/PortfolioScreen.kt`

- [ ] **Step 1: 在 LazyColumn 顶部摘要 cards 之后、个股持仓折叠头之前，加 FilterChipsRow**

在 `PortfolioScreen.kt` 的 LazyColumn 里，`PortfolioSummaryCard` item 之后、个股持仓折叠头 item 之前，插入：

```kotlin
            // 顶部筛选条：行业 + 标签（组内 OR、跨组 AND）
            item {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (uiState.availableIndustries.isNotEmpty()) {
                        FilterChipsRow(
                            options = uiState.availableIndustries,
                            selected = uiState.selectedIndustries,
                            onToggle = viewModel::toggleIndustryFilter,
                            onClear = viewModel::clearIndustryFilter
                        )
                    }
                    if (uiState.availableTags.isNotEmpty()) {
                        FilterChipsRow(
                            options = uiState.availableTags,
                            selected = uiState.selectedTags,
                            onToggle = viewModel::toggleTagFilter,
                            onClear = viewModel::clearTagFilter
                        )
                    }
                }
            }
```

- [ ] **Step 2: 把持仓 items 与 watchlist 改用 filtered 版本**

把 `PortfolioScreen.kt:298-308` 的 `if (holdingsExpanded) { items(items = uiState.items ...) { ... } }` 改为：

```kotlin
            if (holdingsExpanded) {
                items(items = uiState.filteredItems, key = { it.code }) { item ->
                    SwipeToDismissHoldingItem(
                        item = item,
                        onClick = { onStockClick(item.code) },
                        onEditWeight = { viewModel.showEditWeightDialog(item.code, item.targetWeight) },
                        onEditStock = { onEditStock(item.code) },
                        onDeleteStock = { viewModel.deleteStock(item.code) },
                        latestYearlyDividend = uiState.stockForecasts[item.code]?.latestYearlyDividend
                    )
                }
            }
```

把 `PortfolioScreen.kt:328-339` 的 `items(items = uiState.watchlist, ...) { ... }` 改为：

```kotlin
                items(items = uiState.filteredWatchlist, key = { it.code }) { stock ->
                    SwipeToDismissWatchItem(
                        stock = stock,
                        forecastIncome = uiState.stockForecasts[stock.code]?.forecastIncome,
                        marketValue = uiState.stockForecasts[stock.code]?.marketValue,
                        currentPrice = uiState.stockForecasts[stock.code]?.currentPrice,
                        latestYearlyDividend = uiState.stockForecasts[stock.code]?.latestYearlyDividend,
                        onDismiss = { viewModel.deleteStock(stock) },
                        onClick = { onStockClick(stock.code) },
                        onEdit = { onEditStock(stock.code) }
                    )
                }
```

- [ ] **Step 3: 空 items 时也要进入页面（避免被旧逻辑跳过 EmptyStateView）**

`PortfolioScreen.kt:134-139` 的 `if (uiState.items.isEmpty() && uiState.watchlist.isEmpty()) { ... return }` 保持不变（无任何股时显示空态，合理）。

- [ ] **Step 4: 加 FilterChipsRow composable 定义**

在 `PortfolioScreen.kt` 末尾（所有 private fun 之后）追加：

```kotlin
@Composable
private fun FilterChipsRow(
    options: List<String>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isAll = selected.isEmpty()
    androidx.compose.foundation.lazy.LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            androidx.compose.material3.FilterChip(
                selected = isAll,
                onClick = onClear,
                label = { Text("全部", style = MaterialTheme.typography.labelMedium) }
            )
        }
        items(items = options, key = { it }) { opt ->
            androidx.compose.material3.FilterChip(
                selected = opt in selected,
                onClick = { onToggle(opt) },
                label = { Text(opt, style = MaterialTheme.typography.labelMedium) }
            )
        }
    }
}
```

（用 fully-qualified 名避免与现有 import 冲突；若顶部已有 `FilterChip` import 也可直接用。`LazyRow`、`items` 已在文件顶部 import。）

- [ ] **Step 5: 编译验证**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/stock/dividend/ui/screen/PortfolioScreen.kt
git commit -m "feat: add FilterChipsRow for industry+tag AND/OR filtering"
```

---

## Task 11: EditHoldingViewModel 标签 state 与事件

**Files:**
- Modify: `app/src/main/java/com/stock/dividend/viewmodel/EditHoldingViewModel.kt`

- [ ] **Step 1: EditHoldingUiState 加标签字段**

把 `EditHoldingViewModel.kt:17-36` 的 `EditHoldingUiState` 替换为：

```kotlin
data class EditHoldingUiState(
    val stockCode: String = "",
    val stockName: String? = null,
    val totalShares: Int = 0,
    val avgCostPerShare: Double = 0.0,
    val transactions: List<TransactionEntity> = emptyList(),
    val yieldPeriod: String = "3",
    val showAddBuyDialog: Boolean = false,
    val showAddSellDialog: Boolean = false,
    val addSharesInput: String = "",
    val addPriceInput: String = "",
    val addDateInput: String = LocalDate.now().toString(),
    val addInputError: String? = null,
    val showEditTransactionDialog: Boolean = false,
    val editingTransaction: TransactionEntity? = null,
    val editSharesInput: String = "",
    val editPriceInput: String = "",
    val editDateInput: String = "",
    val editInputError: String? = null,
    // ── 标签 ──────────────────────────────────────────
    val tags: List<String> = emptyList(),
    val allTags: List<String> = emptyList(),
    val showAddTagDialog: Boolean = false,
    val addTagInput: String = "",
    val addTagError: String? = null
)
```

- [ ] **Step 2: init 合并订阅 tags**

把 `EditHoldingViewModel.kt:50-67` 的 `init { ... }` 替换为：

```kotlin
    init {
        viewModelScope.launch {
            stockRepository.observeStock(stockCode).collect { stock ->
                if (stock != null) {
                    val transactions = transactionRepository.getByStock(stockCode)
                    val holding = calculateHolding(transactions)

                    _uiState.value = _uiState.value.copy(
                        stockName = stock.name,
                        totalShares = holding.totalShares,
                        avgCostPerShare = holding.avgCostPerShare,
                        transactions = transactions,
                        yieldPeriod = stock.yieldPeriod
                    )
                }
            }
        }
        // 订阅当前股票标签 + 全局已有标签（用于输入建议）
        viewModelScope.launch {
            stockRepository.observeTagsForStock(stockCode).collect { tags ->
                _uiState.value = _uiState.value.copy(tags = tags)
            }
        }
        viewModelScope.launch {
            stockRepository.observeAllTags().collect { all ->
                _uiState.value = _uiState.value.copy(allTags = all)
            }
        }
    }
```

import 区追加：

```kotlin
import kotlinx.coroutines.flow.combine
```

（虽然本 Task 用不到 combine，但保留 import 无害；实际上不需 combine，可省略。本步可跳过加 combine import。）

- [ ] **Step 3: 加标签事件方法**

在 `onYieldPeriodChanged` 之后追加：

```kotlin
    fun showAddTagDialog() {
        _uiState.value = _uiState.value.copy(
            showAddTagDialog = true,
            addTagInput = "",
            addTagError = null
        )
    }

    fun onAddTagInputChanged(input: String) {
        _uiState.value = _uiState.value.copy(addTagInput = input, addTagError = null)
    }

    fun dismissAddTagDialog() {
        _uiState.value = _uiState.value.copy(
            showAddTagDialog = false,
            addTagInput = "",
            addTagError = null
        )
    }

    /** 确认添加标签：去空白、校验长度、去重；命中已有同名标签直接选中。 */
    fun confirmAddTag() {
        val raw = _uiState.value.addTagInput.trim()
        if (raw.isEmpty()) {
            _uiState.value = _uiState.value.copy(addTagError = "标签不能为空")
            return
        }
        if (raw.length > 20) {
            _uiState.value = _uiState.value.copy(addTagError = "标签最长 20 个字符")
            return
        }
        val current = _uiState.value.tags
        if (raw in current) {
            _uiState.value = _uiState.value.copy(showAddTagDialog = false, addTagInput = "")
            return
        }
        _uiState.value = _uiState.value.copy(
            tags = current + raw,
            showAddTagDialog = false,
            addTagInput = "",
            addTagError = null
        )
    }

    fun removeTag(tag: String) {
        _uiState.value = _uiState.value.copy(tags = _uiState.value.tags - tag)
    }
```

- [ ] **Step 4: saveHolding 同时保存标签**

把 `EditHoldingViewModel.kt:73-77` 的 `saveHolding` 替换为：

```kotlin
    fun saveHolding() {
        viewModelScope.launch {
            stockRepository.updateYieldPeriod(stockCode, _uiState.value.yieldPeriod)
            stockRepository.setStockTags(stockCode, _uiState.value.tags)
        }
    }
```

- [ ] **Step 5: dismissDialog 顺便关 addTag 弹窗**

把 `EditHoldingViewModel.kt:101-109` 的 `dismissDialog` 替换为：

```kotlin
    fun dismissDialog() {
        _uiState.value = _uiState.value.copy(
            showAddBuyDialog = false,
            showAddSellDialog = false,
            showEditTransactionDialog = false,
            editingTransaction = null,
            editInputError = null,
            showAddTagDialog = false,
            addTagInput = "",
            addTagError = null
        )
    }
```

- [ ] **Step 6: 编译验证**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/stock/dividend/viewmodel/EditHoldingViewModel.kt
git commit -m "feat: add tag editing state and events to EditHoldingViewModel"
```

---

## Task 12: EditHoldingScreen 标签编辑 UI

**Files:**
- Modify: `app/src/main/java/com/stock/dividend/ui/screen/EditHoldingScreen.kt`

- [ ] **Step 1: 加 import**

在 `EditHoldingScreen.kt` import 区追加：

```kotlin
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
```

- [ ] **Step 2: 在 LazyColumn「股息率档位」item 之前插入标签 Card item**

在 `EditHoldingScreen.kt` 的 LazyColumn 里，`if (uiState.transactions.isNotEmpty()) { ... }` 之后、股息率档位 item 之前，插入：

```kotlin
            item {
                StockTagsCard(
                    tags = uiState.tags,
                    onAddClick = { viewModel.showAddTagDialog() },
                    onRemoveClick = { tag -> viewModel.removeTag(tag) }
                )
            }
```

- [ ] **Step 3: 在 AddTransactionDialog 弹窗区追加 AddTagDialog 触发**

在 `EditHoldingScreen.kt` 末尾的 `if (uiState.showEditTransactionDialog) { ... }` 之后追加：

```kotlin
    if (uiState.showAddTagDialog) {
        AddTagDialog(
            input = uiState.addTagInput,
            error = uiState.addTagError,
            suggestions = uiState.allTags.filter { it !in uiState.tags },
            onInputChange = viewModel::onAddTagInputChanged,
            onConfirm = { viewModel.confirmAddTag() },
            onSuggestionClick = { tag ->
                viewModel.onAddTagInputChanged(tag)
                viewModel.confirmAddTag()
            },
            onDismiss = { viewModel.dismissAddTagDialog() }
        )
    }
```

- [ ] **Step 4: 在文件末尾追加 StockTagsCard 与 AddTagDialog composable**

```kotlin
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StockTagsCard(
    tags: List<String>,
    onAddClick: () -> Unit,
    onRemoveClick: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                text = "标签",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "用于在持仓页按标签筛选",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
            Spacer(modifier = Modifier.height(12.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                tags.forEach { tag ->
                    InputChip(
                        selected = false,
                        onClick = {},
                        label = { Text(tag) },
                        trailingIcon = {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "移除标签 $tag",
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        onDismiss = { onRemoveClick(tag) }
                    )
                }
                AssistChip(
                    onClick = onAddClick,
                    label = { Text("+ 添加标签") },
                    leadingIcon = {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                )
            }
        }
    }
}

@Composable
private fun AddTagDialog(
    input: String,
    error: String?,
    suggestions: List<String>,
    onInputChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onSuggestionClick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加标签", fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                OutlinedTextField(
                    value = input,
                    onValueChange = onInputChange,
                    label = { Text("标签名（最长 20 字）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )
                if (suggestions.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "已有标签",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        suggestions.take(20).forEach { s ->
                            AssistChip(
                                onClick = { onSuggestionClick(s) },
                                label = { Text(s) }
                            )
                        }
                    }
                }
                if (error != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) { Text("添加") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
```

- [ ] **Step 5: 加 Icons.Default.Close import**

在 `EditHoldingScreen.kt` 的 `import androidx.compose.material.icons.filled.Add` 之后追加：

```kotlin
import androidx.compose.material.icons.filled.Close
```

- [ ] **Step 6: 编译验证**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/stock/dividend/ui/screen/EditHoldingScreen.kt
git commit -m "feat: add tag editing card and dialog to EditHoldingScreen"
```

---

## Task 13: version bump

**Files:**
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: 改 versionCode/versionName**

把 `app/build.gradle.kts:17-18` 的：

```kotlin
        versionCode = 5
        versionName = "3.0.2"
```

改为：

```kotlin
        versionCode = 6
        versionName = "3.1.0"
```

- [ ] **Step 2: Commit**

```bash
git add app/build.gradle.kts
git commit -m "chore: bump version to 3.1.0 (versionCode 6) for stock filter feature"
```

---

## Task 14: 全量测试 + APK 构建 + 手动验证

- [ ] **Step 1: 全量单测**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL（所有单测含新增 PortfolioFilterTest、setStockTags、BackupRepository round-trip 均绿）

- [ ] **Step 2: assembleDebug 构建 APK**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL，APK 生成于 `app/build/outputs/apk/debug/app-debug.apk`

- [ ] **Step 3: 安装到设备/模拟器并手测**

安装：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

或在 MCP android-emulator 中 `android_install_app`。

**手动验收清单**（逐条勾选）：

- [ ] 应用正常启动（DB v14 升级成功，无崩溃；既有数据保留）
- [ ] 持仓页：行业配置区块完全消失；摘要/持仓/自选三大块还在
- [ ] 给 3 只股贴不同标签（编辑页 → 标签卡 → + 添加标签 → 输入 → 添加 → 保存 → 返回持仓页）
- [ ] 标签 chip 列表里能看到刚贴的标签
- [ ] 行业 chip 选「银行」→ 列表只剩银行股；自选股同步被筛
- [ ] 标签 chip 选「高息」→ 行业 ∩ 标签 AND 后只剩同时满足者
- [ ] 点「全部」chip 清空对应维度
- [ ] 删除一只股 → 其 stock_tags 自动消失（chip 候选列表也更新）
- [ ] 备份导出 → 含 stockTags；清空数据后导入 → 标签恢复

- [ ] **Step 4: 最终提交（如有手测发现的修补）**

```bash
git add -A
git commit -m "test: full unit + manual verification for stock filter feature"
```

---

## Self-Review

**1. Spec coverage**

| Spec 要求 | 对应 Task |
|---|---|
| 移除行业配置 UI（折叠头/饼图/卡片/对话框） | Task 9 |
| 新增 stock_tags 表 + FK CASCADE + 索引 | Task 1 |
| DAO observeAll/observeAllTags/getTagsForStock/setStockTags/clear | Task 2 + Task 5 |
| AppDatabase v14 + MIGRATION_13_14 + DatabaseModule 注入 + migration | Task 3 + Task 4 |
| BackupContainer.stockTags + BackupRepository export/import | Task 6 |
| PortfolioViewModel tagsByCodeFlow + applyFilter + toggle/clear + state | Task 7 + Task 8 |
| PortfolioScreen FilterChipsRow + 用 filteredItems/Watchlist | Task 10 |
| EditHoldingScreen 标签编辑（FlowRow + InputChip + 弹窗 + 建议列表） | Task 11 + Task 12 |
| PortfolioItem.tags | Task 7 |
| version bump | Task 13 |
| 测试（DAO 行为由 Repository 测覆盖；applyFilter 纯函数测；Backup round-trip 测） | Task 5 Step 7-8、Task 7、Task 6 Step 5-6 |
| 手测清单 | Task 14 Step 3 |

**2. Placeholder scan**

无 TBD/TODO；所有代码块完整。

**3. Type consistency**

- `PortfolioItem.tags: List<String>` —— Task 7 加，Task 8 用
- `applyPortfolioFilter(items, watchlist, tagsByCode, selectedIndustries, selectedTags)` —— Task 7 定义、Task 8 在 publish/Collector4/Collector5/reapplyFilter 四处调用，签名一致
- `StockRepository.observeAllStockTags()/observeAllTags()/observeTagsForStock()/setStockTags()` —— Task 5 定义、Task 8（ViewModel）与 Task 11（EditHoldingVM）调用，签名一致
- `StockTagDao.observeAll()` —— Task 2 定义、Task 5 用（`observeAllStockTags` 委托）
- `toggleIndustryFilter/clearIndustryFilter/toggleTagFilter/clearTagFilter` —— Task 8 定义、Task 10 调用，命名一致
- `EditHoldingUiState.tags/allTags/showAddTagDialog/addTagInput/addTagError` —— Task 11 定义、Task 12 调用
- `BackupContainer.stockTags` —— Task 6 加、Task 6 import/export 用、Task 6 test 断言

无类型/命名漂移。

## Execution Handoff

**Plan complete and saved to `docs/superpowers/plans/2026-07-25-stock-filter-plan.md`. Two execution options:**

**1. Subagent-Driven (recommended)** - 每个 Task 派一个新 subagent，两阶段 review，迭代快

**2. Inline Execution** - 在当前会话按批次执行，checkpoint review

**Which approach?**
