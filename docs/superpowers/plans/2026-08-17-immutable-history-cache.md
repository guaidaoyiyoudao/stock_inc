# 历史不可变数据本地缓存（K线/财报/分红）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把历史不可变数据（K线、财报期次、分红记录）持久化到 Room，实现离线可用、增量刷新、历史永不丢失。

**Architecture:** 三条独立改造线——① K线新增 `kline_cache`/`kline_cache_meta` 两表（DB v23→24），`KlineRepository` 内做「新鲜直读→增量补尾→除权漂移全量重建→失败回退缓存」编排；② 分红从「整表清空重插」改为「按 id/除权日定点替换」，超窗口历史行永续累积，双源空结果不再清库；③ 财报/基本面缓存刷新时按报告期 merge（远端覆盖同期、缓存独有旧期保留），新增纯函数 `mergeByReportDate`。

**Tech Stack:** Room 2.8.4（手写 Migration）、Kotlin coroutines、MockK + Truth 单测。

---

## 背景（现状与问题）

| 数据 | 现状 | 问题 |
|---|---|---|
| K线 | `KlineRepository` 每次调用都发腾讯请求，零持久化 | 一次组合评估 = 每股 ×3 请求；断网 BOLL/回测全废 |
| 分红 | `fetchAndCacheDividends` = `deleteByStockCode` + `insertAll` | 腾讯窗口仅 ~6 年：窗口外历史行被**删除**；双源返回空也会清库 |
| 财报/基本面 | 单 payload 7 天 TTL，过期整体重拉覆盖 | 远端窗口缩短/部分接口失败时，已缓存旧期次被**覆盖丢失** |

关键语义：K线为**前复权**——新除权日出现时全历史价格整体位移，增量合并会算错 BOLL，必须检测除权日变化并全量重建（meta 存 `lastExDividendDate` 快照比对）。

## File Structure

- Create: `app/src/main/java/com/stock/dividend/data/local/entity/KlineCacheEntity.kt`（两个 entity 同文件）
- Create: `app/src/main/java/com/stock/dividend/data/local/dao/KlineCacheDao.kt`
- Create: `app/src/main/java/com/stock/dividend/data/repository/HistoryCacheMerge.kt`（纯函数）
- Modify: `AppDatabase.kt`（v24 + MIGRATION_23_24 + accessor）、`DatabaseModule.kt`（provider + 注册迁移）
- Modify: `DividendDao.kt`（4 个新查询）、`KlineRepository.kt`（缓存编排）、`DividendRepository.kt`（历史保留式写入）、`FinancialStatementsRepository.kt` / `FundamentalsCacheRepository.kt`（merge）
- Test: `KlineRepositoryTest.kt`（改造+新增）、`DividendRepositoryTest.kt`（改造+新增）、`HistoryCacheMergeTest.kt`（新）、`FinancialStatementsRepositoryTest.kt`（新）、`FundamentalsCacheRepositoryTest.kt`（新增用例）
- Docs: `AGENTS.md`、`README.md`
- 不改备份载体（kline_cache 与 fundamentals_cache/financial_statements_cache 一样是可再生的缓存，不进备份）。

---

### Task 1: DB 层——K线缓存表 + 迁移

**Files:**
- Create: `app/src/main/java/com/stock/dividend/data/local/entity/KlineCacheEntity.kt`
- Create: `app/src/main/java/com/stock/dividend/data/local/dao/KlineCacheDao.kt`
- Modify: `app/src/main/java/com/stock/dividend/data/local/AppDatabase.kt`
- Modify: `app/src/main/java/com/stock/dividend/data/local/dao/DividendDao.kt`
- Modify: `app/src/main/java/com/stock/dividend/di/DatabaseModule.kt`

- [ ] **Step 1.1: 新建 KlineCacheEntity.kt**

```kotlin
package com.stock.dividend.data.local.entity

import androidx.room.Entity

/** 单根 K 线缓存行（前复权）。主键 (stockCode, period, date)。历史 K 线不可变，仅尾部最新一根盘中会变（增量请求覆盖更新）。 */
@Entity(tableName = "kline_cache", primaryKeys = ["stockCode", "period", "date"])
data class KlineCacheEntity(
    val stockCode: String,
    val period: String,   // KlinePeriod.name：DAILY / WEEKLY / MONTHLY
    val date: String,     // YYYY-MM-DD
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double
)

/** K 线缓存同步状态（每股每周期一行）：fetchedAt=最近写缓存时间（新鲜窗口用）；lastExDividendDate=写入时该股最新除权日——出现更新除权日说明前复权全历史漂移，需全量重建。 */
@Entity(tableName = "kline_cache_meta", primaryKeys = ["stockCode", "period"])
data class KlineCacheMetaEntity(
    val stockCode: String,
    val period: String,
    val fetchedAt: Long,
    val lastExDividendDate: String?
)
```

- [ ] **Step 1.2: 新建 KlineCacheDao.kt**

```kotlin
package com.stock.dividend.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.stock.dividend.data.local.entity.KlineCacheEntity
import com.stock.dividend.data.local.entity.KlineCacheMetaEntity

@Dao
interface KlineCacheDao {

    @Query("SELECT * FROM kline_cache WHERE stockCode = :stockCode AND period = :period ORDER BY date ASC")
    suspend fun getBars(stockCode: String, period: String): List<KlineCacheEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBars(bars: List<KlineCacheEntity>)

    @Query("DELETE FROM kline_cache WHERE stockCode = :stockCode AND period = :period")
    suspend fun deleteByStock(stockCode: String, period: String)

    /** 全量重建（首拉/前复权漂移）：删旧插新保持原子。 */
    @Transaction
    suspend fun replaceBars(stockCode: String, period: String, bars: List<KlineCacheEntity>) {
        deleteByStock(stockCode, period)
        upsertBars(bars)
    }

    /** 只保留最近 [keep] 根，防增量写入无限增长。 */
    @Query(
        "DELETE FROM kline_cache WHERE stockCode = :stockCode AND period = :period AND date NOT IN " +
            "(SELECT date FROM kline_cache WHERE stockCode = :stockCode AND period = :period " +
            "ORDER BY date DESC LIMIT :keep)"
    )
    suspend fun trimToRecent(stockCode: String, period: String, keep: Int)

    @Query("SELECT * FROM kline_cache_meta WHERE stockCode = :stockCode AND period = :period")
    suspend fun getMeta(stockCode: String, period: String): KlineCacheMetaEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMeta(meta: KlineCacheMetaEntity)
}
```

- [ ] **Step 1.3: DividendDao 追加 4 个方法**（kline 漂移检测 1 个 + 分红历史保留写入 3 个，Task 3 用）

```kotlin
    @Query("SELECT MAX(exDividendDate) FROM dividends WHERE stockCode = :stockCode")
    suspend fun getLatestExDividendDate(stockCode: String): String?

    @Query("DELETE FROM dividends WHERE stockCode = :stockCode AND id IN (:ids)")
    suspend fun deleteByIds(stockCode: String, ids: List<String>)

    @Query("DELETE FROM dividends WHERE stockCode = :stockCode AND exDividendDate IN (:exDates)")
    suspend fun deleteByStockAndExDates(stockCode: String, exDates: List<String>)

    @Query("DELETE FROM dividends WHERE stockCode = :stockCode AND exDividendDate IS NULL AND id NOT IN (:keepIds)")
    suspend fun deleteStalePendingByStock(stockCode: String, keepIds: List<String>)
```

- [ ] **Step 1.4: AppDatabase——entities/version/accessor/MIGRATION_23_24**

entities 列表 `GridPlanEntity::class` 后追加两项；`version = 23` → `version = 24`；加 `abstract fun klineCacheDao(): KlineCacheDao`；companion 末尾追加：

```kotlin
        val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // K 线本地缓存（历史不可变数据持久化：离线可用 + 增量刷新）
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `kline_cache` (" +
                        "`stockCode` TEXT NOT NULL, `period` TEXT NOT NULL, `date` TEXT NOT NULL, " +
                        "`open` REAL NOT NULL, `high` REAL NOT NULL, `low` REAL NOT NULL, " +
                        "`close` REAL NOT NULL, `volume` REAL NOT NULL, " +
                        "PRIMARY KEY(`stockCode`, `period`, `date`))"
                )
                // 每股每周期同步状态：fetchedAt + 写入时最新除权日（前复权漂移检测）
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `kline_cache_meta` (" +
                        "`stockCode` TEXT NOT NULL, `period` TEXT NOT NULL, " +
                        "`fetchedAt` INTEGER NOT NULL, `lastExDividendDate` TEXT, " +
                        "PRIMARY KEY(`stockCode`, `period`))"
                )
            }
        }
```

- [ ] **Step 1.5: DatabaseModule——provider + 注册迁移**

```kotlin
    @Provides
    fun provideKlineCacheDao(db: AppDatabase): KlineCacheDao = db.klineCacheDao()
```
`addMigrations(...)` 链尾追加 `AppDatabase.MIGRATION_23_24`。

- [ ] **Step 1.6: 编译验证**

Run: `./gradlew :app:compileDebugKotlin -q`
Expected: BUILD SUCCESSFUL

- [ ] **Step 1.7: Commit** `feat(kline): K线本地缓存表 kline_cache + meta（DB v24）`

---

### Task 2: KlineRepository 缓存编排（TDD）

**Files:**
- Modify: `app/src/main/java/com/stock/dividend/data/repository/KlineRepository.kt`
- Test: `app/src/test/java/com/stock/dividend/data/repository/KlineRepositoryTest.kt`

- [ ] **Step 2.1: 改测试（失败先行）**——构造改 3 参 + `@Before` 显式 stub dao + 新增缓存用例：

```kotlin
    private val tencentApi: TencentDividendApi = mockk()
    private val klineCacheDao: KlineCacheDao = mockk(relaxed = true)
    private val dividendDao: DividendDao = mockk(relaxed = true)
    private val repository = KlineRepository(tencentApi, klineCacheDao, dividendDao)

    @Before
    fun setUp() {
        // 默认无缓存（走全量路径），涉及缓存的用例单独覆盖 stub
        coEvery { klineCacheDao.getBars(any(), any()) } returns emptyList()
        coEvery { klineCacheDao.getMeta(any(), any()) } returns null
        coEvery { dividendDao.getLatestExDividendDate(any()) } returns null
    }
```

新增用例（完整代码见 Step 2.2 对应行为）：`first fetch writes bars and meta to cache` / `fresh cache serves without network` / `fresh cache returns only last N bars` / `stale cache fetches incremental window from last cached date`（断言增量 param 起点=缓存尾日期、upsertBars+trimToRecent(800)、合并结果覆盖尾根）/ `incremental network failure falls back to cached bars` / `new ex-dividend date triggers full rebuild`（断言走 replaceBars 且 param 起点≠尾日期、meta 写入新除权日）/ `empty incremental response keeps cache and refreshes meta`。

- [ ] **Step 2.2: 实现 loadBars 编排**（fetchCloses/fetchKlines 委托；签名加 `forceRefresh: Boolean = false` 尾参；新增 `buildIncrementalParam`；常量 `CACHE_FRESH_TTL_MS=15min`、`MAX_CACHED_BARS=800`；`fetchByParam` 返回 `List<KlineBar>?` 区分「网络失败/无数据键」与「成功但空」）。完整代码实现时落地。

- [ ] **Step 2.3: 跑测试**

Run: `./gradlew :app:testDebugUnitTest --tests "com.stock.dividend.data.repository.KlineRepositoryTest"`
Expected: 全部 PASS（含 15 个存量用例不回归）

- [ ] **Step 2.4: Commit** `feat(kline): KlineRepository 缓存编排——新鲜直读/增量补尾/除权漂移全量重建/失败回退`

---

### Task 3: DividendRepository 历史保留式写入（TDD）

**Files:**
- Modify: `app/src/main/java/com/stock/dividend/data/repository/DividendRepository.kt`
- Test: `app/src/test/java/com/stock/dividend/data/repository/DividendRepositoryTest.kt`

- [ ] **Step 3.1: 改测试（失败先行）**
  - 改 `deletes old data before inserting new` → `replaces covered rows without wiping history`（verify `deleteByIds`+`deleteByStockAndExDates`，`deleteByStockCode` exactly 0）
  - 三个空结果用例（empty qfqday / null qfqday / null data）：`coVerify { dao.insertAll(emptyList()) }` → `coVerify(exactly = 0) { dao.insertAll(any()) }`
  - 新增 `fetchAndCacheDividends purges stale pending rows only in eastmoney fallback`、`tencent path does not purge pending rows`

- [ ] **Step 3.2: 实现**——`fetchAndCacheDividends` 重写：

```kotlin
    suspend fun fetchAndCacheDividends(stockCode: String, securityCode: String): Result<Unit> {
        return try {
            val fromTencent = fetchFromTencent(stockCode, securityCode)
            val usedEastMoneyFallback = fromTencent.isEmpty()
            val entities = if (usedEastMoneyFallback) {
                fetchFromEastMoney(stockCode, securityCode)
            } else {
                enrichDividendYieldFromEastMoney(stockCode, securityCode, fromTencent)
            }
            if (entities.isEmpty()) {
                // 双源均无数据多为网络/反爬抖动，绝不清空既有历史（历史分红不可变）
                return Result.success(Unit)
            }
            // 历史保留式写入：只删本次结果覆盖到的行，窗口外历史行永续累积
            dividendDao.deleteByIds(stockCode, entities.map { it.id }.distinct())
            entities.mapNotNull { it.exDividendDate }.distinct().takeIf { it.isNotEmpty() }?.let {
                dividendDao.deleteByStockAndExDates(stockCode, it)   // 腾讯/东财两种 id 方案跨源去重
            }
            if (usedEastMoneyFallback) {
                // 东财全量路径：清洗已取消/失效的预案行（exDate=null 且不在本次结果中）
                dividendDao.deleteStalePendingByStock(stockCode, entities.map { it.id })
            }
            dividendDao.insertAll(entities)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(Exception(e.toUserMessage(), e))
        }
    }
```

- [ ] **Step 3.3: 跑测试** `--tests "com.stock.dividend.data.repository.DividendRepositoryTest"` → PASS
- [ ] **Step 3.4: Commit** `feat(dividend): 分红历史保留式写入——不再整表清空，跨源按除权日去重`

---

### Task 4: 财报/基本面历史期次合并（TDD）

**Files:**
- Create: `app/src/main/java/com/stock/dividend/data/repository/HistoryCacheMerge.kt`
- Create: `app/src/test/java/com/stock/dividend/data/repository/HistoryCacheMergeTest.kt`
- Create: `app/src/test/java/com/stock/dividend/data/repository/FinancialStatementsRepositoryTest.kt`
- Modify: `app/src/test/java/com/stock/dividend/data/repository/FundamentalsCacheRepositoryTest.kt`
- Modify: `FinancialStatementsRepository.kt` / `FundamentalsCacheRepository.kt`

- [ ] **Step 4.1: 纯函数测试（失败先行）** `HistoryCacheMergeTest.kt`——5 用例：远端覆盖同期 / 缓存独有旧期保留 / 远端空返缓存 / 缓存空返远端 / 结果按日期升序。

- [ ] **Step 4.2: 实现 HistoryCacheMerge.kt**

```kotlin
package com.stock.dividend.data.repository

/**
 * 不可变历史「按报告期」合并（纯函数）：远端同报告期覆盖缓存，缓存独有的更早期次永续保留。
 *
 * 用于财报/基本面这类「历史期次不可变、只有新期次追加」的数据——远端窗口缩短或部分接口失败时，
 * 已缓存的历史期次不丢失。
 */
internal fun <T> mergeByReportDate(
    cached: List<T>,
    remote: List<T>,
    dateOf: (T) -> String
): List<T> {
    if (cached.isEmpty()) return remote
    if (remote.isEmpty()) return remote.takeIf { false } ?: cached // 实现时简化为 return cached
    val merged = LinkedHashMap<String, T>(cached.size + remote.size)
    cached.forEach { merged[dateOf(it)] = it }
    remote.forEach { merged[dateOf(it)] = it }
    return merged.values.sortedBy(dateOf)
}
```
（实现时写干净版本：`if (remote.isEmpty()) return cached`）

- [ ] **Step 4.3: 两个 Repository 接入 merge + 新建/补测试**（失败先行）：
  - `FinancialStatementsRepositoryTest`（新建）：fresh 不触网 / 网络失败回退 stale / 无缓存+失败 null / **stale refresh merges and preserves older cached periods**（income 真实 DTO 构造，cash/balance stub 抛 IOException 走降级；断言结果与落库 payload 都含 3 期且 2024-12-31 被远端覆盖）
  - `FundamentalsCacheRepositoryTest` 补 merge 用例（cached=[2023,2024]，remote=[2024',2025] → 3 期升序，落库 payload 3 期）
  - 两 Repository 的 remote!=null 分支改为：

```kotlin
        val cachedPeriods = cached?.let { parse(it.payload)?.periods }.orEmpty()
        val merged = FinancialStatements(mergeByReportDate(cachedPeriods, remote.periods) { it.reportDate })
        // upsert(payload = gson.toJson(merged), fetchedAt = now)；return merged
```
（Fundamentals 同构，`Fundamentals(...)` 包装）

- [ ] **Step 4.4: 跑测试** 三个测试类 → PASS
- [ ] **Step 4.5: Commit** `feat(cache): 财报/基本面缓存按报告期合并，历史期次不随刷新丢失`

---

### Task 5: 文档同步

- [ ] AGENTS.md：§2 DB version 23→**24**（18 表/23 迁移）；§3 补 `KlineCacheDao`/`KlineCacheEntity`/`HistoryCacheMerge.kt` 行；§4.6 version 订正；变更记录加本条目（三线改造 + 前复权漂移检测语义 + 分红空结果不清库的公开行为变化）；文件规模速览数字按实测更新。
- [ ] README.md:34 `DB version=20` → `DB version=24`。
- [ ] Commit `docs: AGENTS/README 同步缓存增强`

---

### Task 6: 全量回归

- [ ] `./gradlew :app:testDebugUnitTest`（全部单测）
- [ ] `./gradlew assembleDebug`（整包构建，验证 Room schema 校验无编译问题）
- [ ] Commit（如有遗留修补）

---

## Self-Review

1. **目标覆盖**：K线（Task 1/2 新表+编排）✓；历史财报（Task 4 merge 保留）✓；分红信息（Task 3 历史保留）✓；「等」→ 基本面同步覆盖（Task 4）✓。
2. **无占位符**：所有代码块完整；Step 2.2 指明完整实现要点与最终代码在实现回合落地（KlineRepository 全文已在侦察中定稿）。
3. **类型一致性**：`KlineCacheEntity` 字段与 Migration SQL 一一对应（NOT NULL 全对齐）；`replaceBars(stockCode, period, bars)` 与测试 verify 签名一致；`mergeByReportDate` 两处调用一致。
4. **风险点**：relaxed mockk 对返回自定义类型的方法可能造不出实例 → 测试 `@Before` 显式 stub 兜底；Room `IN ()` 空列表 SQL 非法 → repo 层 `takeIf { isNotEmpty() }` 守卫；`deleteStalePendingByStock` 仅在东财全量路径调用（腾讯不携带预案信息，不能清洗）。
