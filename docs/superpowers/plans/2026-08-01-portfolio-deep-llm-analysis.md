# 组合级 AI 解读增强（深度数据 + 双缓存）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 组合级 AI 解读接入每股深度数据（基本面 + 1/3/5 年预测 + 买入线），输出每股「简评 + 风险点」；基本面与 LLM 结果均落 Room 缓存（离线可看、省 token）。

**Architecture:** 复用现有「一键评估 → AI 解读」链路：`PortfolioViewModel.analyzeWithLlm` 装配每股深度数据（基本面走新缓存仓库、预测走本地计算、买入线走国债缓存），`LlmPromptBuilder` 升级为接收 `PortfolioLlmInput`；`LlmAnalysisRepository` 统一加 LLM 结果缓存（prompt 哈希 key + 24h TTL + forceRefresh）并新增 `analyzeStock` 承接个股编排。DB v16→v17 新增两张缓存表。

**Tech Stack:** Kotlin, Jetpack Compose, Hilt, Room 2.6.1, Gson, JUnit4 + Truth + MockK + Robolectric, Coroutines。

---

## 执行约定（先读）

1. **提交策略**：仓库 `AGENTS.md §7.1` 要求「不要主动 commit/push，除非用户明确要求」。因此每个任务末尾的「提交（可选）」步骤**默认跳过**，仅当用户明确要求提交时才执行；执行时按示例命令提交。
2. **工作区基线**：当前工作区存在大量未提交改动（基本面/国债/买入线等，均为本 spec 的前置依赖）。执行任务时**不得覆盖或回滚这些改动**；Task 0 先确认基线测试绿。
3. **红线**：改 schema 必须迁移 + version+1（Task 1 已含）；网络/DB 异常吞掉不崩 UI；`isLoading` 显式复位；不换算东财原始数据；所有用户可见文案中文；纯函数不带 Android 依赖。
4. 每个任务以「写失败测试 → 跑失败 → 最小实现 → 跑绿」推进；改纯函数/决策逻辑必须同步单测（AGENTS §6）。

## File Structure

### 新增（main）

- `app/src/main/java/com/stock/dividend/data/local/entity/FundamentalsCacheEntity.kt` — 基本面缓存行
- `app/src/main/java/com/stock/dividend/data/local/entity/LlmAnalysisCacheEntity.kt` — LLM 结果缓存行
- `app/src/main/java/com/stock/dividend/data/local/dao/FundamentalsCacheDao.kt` — 按 stockCode 读/写
- `app/src/main/java/com/stock/dividend/data/local/dao/LlmAnalysisCacheDao.kt` — 按 key+scope 读/写
- `app/src/main/java/com/stock/dividend/data/repository/LlmCacheKey.kt` — SHA-256 纯函数
- `app/src/main/java/com/stock/dividend/data/repository/PortfolioLlmInput.kt` — 组合级深度输入快照
- `app/src/main/java/com/stock/dividend/data/repository/LlmAnalysisCacheStore.kt` — 缓存序列化存取
- `app/src/main/java/com/stock/dividend/data/repository/FundamentalsCacheRepository.kt` — 基本面缓存编排

### 新增（test）

- `app/src/test/java/com/stock/dividend/data/repository/LlmCacheKeyTest.kt`
- `app/src/test/java/com/stock/dividend/data/repository/LlmAnalysisCacheStoreTest.kt`
- `app/src/test/java/com/stock/dividend/data/repository/FundamentalsCacheRepositoryTest.kt`

### 修改

- `app/src/main/java/com/stock/dividend/data/local/AppDatabase.kt` — entities + version 17 + MIGRATION_16_17
- `app/src/main/java/com/stock/dividend/di/DatabaseModule.kt` — 注册迁移 + 两个 DAO provider
- `app/src/main/java/com/stock/dividend/data/repository/LlmAnalysis.kt` — StockLlmComment + Result 元数据
- `app/src/main/java/com/stock/dividend/data/repository/StockLlmAnalysis.kt` — StockLlmAnalysisResult + State 元数据
- `app/src/main/java/com/stock/dividend/data/repository/LlmAnalysisParser.kt` — 新结构 + 旧字符串兼容
- `app/src/main/java/com/stock/dividend/data/repository/LlmPromptBuilder.kt` — 接收 PortfolioLlmInput + 深度数据
- `app/src/main/java/com/stock/dividend/data/repository/LlmAnalysisRepository.kt` — 缓存编排 + analyzeStock
- `app/src/main/java/com/stock/dividend/viewmodel/PortfolioViewModel.kt` — 装配深度数据 + forceRefresh
- `app/src/main/java/com/stock/dividend/viewmodel/StockDetailViewModel.kt` — 迁 analyzeStock + 基本面缓存
- `app/src/main/java/com/stock/dividend/ui/screen/PortfolioEvaluationScreen.kt` — brief+risks、时间/缓存/重新分析
- `app/src/main/java/com/stock/dividend/ui/screen/StockDetailScreen.kt` — 时间/缓存/重新分析
- 测试：`LlmAnalysisParserTest`、`LlmPromptBuilderTest`、`LlmAnalysisRepositoryTest`、`PortfolioViewModelTest`、`StockDetailViewModelTest`

### 不动

- `BackupData.kt` / `BackupRepository`（缓存不进备份）
- `LlmConfig` / `LlmConfigRepository` / `LlmProviderPresets`
- `LlmApi` / `LlmChatRequest` / `LlmChatResponse`
- `FundamentalApi` / `Fundamentals` / `FundamentalsBuilder`
- `BondYieldRepository` / `BuyThresholdCalculator`

---

## Task 0: 基线验证（未提交改动就绪性）

**Files:**
- 无

- [ ] **Step 1: 跑全量单测确认基线**

Run: `./gradlew :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`（或只有与本任务无关的既有失败，若有需先停下与用户确认——工作区有未提交改动，可能是半成品）。

- [ ] **Step 2: 记录工作区状态（不修改）**

Run: `git status --short`
Expected: 与上一会话一致的大量 M/?? 文件（基本面、债券、买入线相关）。**不要**清理、回滚或提交。

- [ ] **Step 3: 提交（可选，需用户同意）**

```bash
git add -A && git commit -m "chore: 基线确认（未提交改动保持原样）"
```

> 默认跳过；仅当用户明确要求提交时执行。

---

## Task 1: DB v16→v17（两张缓存表 + DAO + 迁移）

**Files:**
- Create: `app/src/main/java/com/stock/dividend/data/local/entity/FundamentalsCacheEntity.kt`
- Create: `app/src/main/java/com/stock/dividend/data/local/entity/LlmAnalysisCacheEntity.kt`
- Create: `app/src/main/java/com/stock/dividend/data/local/dao/FundamentalsCacheDao.kt`
- Create: `app/src/main/java/com/stock/dividend/data/local/dao/LlmAnalysisCacheDao.kt`
- Modify: `app/src/main/java/com/stock/dividend/data/local/AppDatabase.kt`
- Modify: `app/src/main/java/com/stock/dividend/di/DatabaseModule.kt`

- [ ] **Step 1: 创建两个 Entity**

`app/src/main/java/com/stock/dividend/data/local/entity/FundamentalsCacheEntity.kt`:

```kotlin
package com.stock.dividend.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 单股基本面缓存（季报级慢变数据，7 天 TTL；payload 为 Fundamentals 的 Gson JSON）。 */
@Entity(tableName = "fundamentals_cache")
data class FundamentalsCacheEntity(
    @PrimaryKey
    val stockCode: String,
    val payload: String,
    val fetchedAt: Long
)
```

`app/src/main/java/com/stock/dividend/data/local/entity/LlmAnalysisCacheEntity.kt`:

```kotlin
package com.stock.dividend.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** LLM 解读结果缓存（prompt 哈希 key；scope=PORTFOLIO/STOCK；payload 为对应分析的 Gson JSON）。 */
@Entity(tableName = "llm_analysis_cache")
data class LlmAnalysisCacheEntity(
    @PrimaryKey
    val cacheKey: String,
    val scope: String,
    val payload: String,
    val createdAt: Long
)
```

- [ ] **Step 2: 创建两个 DAO**

`app/src/main/java/com/stock/dividend/data/local/dao/FundamentalsCacheDao.kt`:

```kotlin
package com.stock.dividend.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.stock.dividend.data.local.entity.FundamentalsCacheEntity

@Dao
interface FundamentalsCacheDao {
    @Query("SELECT * FROM fundamentals_cache WHERE stockCode = :stockCode")
    suspend fun get(stockCode: String): FundamentalsCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: FundamentalsCacheEntity)

    @Query("DELETE FROM fundamentals_cache")
    suspend fun clear()
}
```

`app/src/main/java/com/stock/dividend/data/local/dao/LlmAnalysisCacheDao.kt`:

```kotlin
package com.stock.dividend.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.stock.dividend.data.local.entity.LlmAnalysisCacheEntity

@Dao
interface LlmAnalysisCacheDao {
    @Query("SELECT * FROM llm_analysis_cache WHERE cacheKey = :cacheKey AND scope = :scope")
    suspend fun get(cacheKey: String, scope: String): LlmAnalysisCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: LlmAnalysisCacheEntity)

    @Query("DELETE FROM llm_analysis_cache")
    suspend fun clear()
}
```

- [ ] **Step 3: 修改 AppDatabase**

在 import 区追加：

```kotlin
import com.stock.dividend.data.local.dao.FundamentalsCacheDao
import com.stock.dividend.data.local.dao.LlmAnalysisCacheDao
import com.stock.dividend.data.local.entity.FundamentalsCacheEntity
import com.stock.dividend.data.local.entity.LlmAnalysisCacheEntity
```

entities 列表追加两行（`TradeStrategyEntity::class` 之后）：

```kotlin
        TradeStrategyEntity::class,
        FundamentalsCacheEntity::class,
        LlmAnalysisCacheEntity::class
```

`version = 16` 改为 `version = 17`。

抽象 DAO 追加：

```kotlin
    abstract fun fundamentalsCacheDao(): FundamentalsCacheDao
    abstract fun llmAnalysisCacheDao(): LlmAnalysisCacheDao
```

companion object 内、`MIGRATION_15_16` 之后追加：

```kotlin
        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 基本面缓存：季报级慢变数据，7 天 TTL
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `fundamentals_cache` (" +
                        "`stockCode` TEXT NOT NULL PRIMARY KEY, " +
                        "`payload` TEXT NOT NULL, " +
                        "`fetchedAt` INTEGER NOT NULL)"
                )
                // LLM 解读结果缓存：prompt 哈希 key，24h TTL
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `llm_analysis_cache` (" +
                        "`cacheKey` TEXT NOT NULL PRIMARY KEY, " +
                        "`scope` TEXT NOT NULL, " +
                        "`payload` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL)"
                )
            }
        }
```

- [ ] **Step 4: 修改 DatabaseModule**

import 追加：

```kotlin
import com.stock.dividend.data.local.dao.FundamentalsCacheDao
import com.stock.dividend.data.local.dao.LlmAnalysisCacheDao
```

`.addMigrations(...)` 行尾的 `MIGRATION_15_16)` 改为 `MIGRATION_15_16, AppDatabase.MIGRATION_16_17)`。

provider 追加：

```kotlin
    @Provides
    fun provideFundamentalsCacheDao(db: AppDatabase): FundamentalsCacheDao = db.fundamentalsCacheDao()

    @Provides
    fun provideLlmAnalysisCacheDao(db: AppDatabase): LlmAnalysisCacheDao = db.llmAnalysisCacheDao()
```

- [ ] **Step 5: 编译验证**

Run: `./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: 提交（可选，需用户同意）**

```bash
git add app/src/main/java/com/stock/dividend/data/local/entity/FundamentalsCacheEntity.kt app/src/main/java/com/stock/dividend/data/local/entity/LlmAnalysisCacheEntity.kt app/src/main/java/com/stock/dividend/data/local/dao/FundamentalsCacheDao.kt app/src/main/java/com/stock/dividend/data/local/dao/LlmAnalysisCacheDao.kt app/src/main/java/com/stock/dividend/data/local/AppDatabase.kt app/src/main/java/com/stock/dividend/di/DatabaseModule.kt && git commit -m "feat(db): 基本面与 LLM 结果缓存表（v16→v17）"
```

---

## Task 2: LlmCacheKey 纯函数

**Files:**
- Create: `app/src/test/java/com/stock/dividend/data/repository/LlmCacheKeyTest.kt`
- Create: `app/src/main/java/com/stock/dividend/data/repository/LlmCacheKey.kt`

- [ ] **Step 1: 写失败测试**

`app/src/test/java/com/stock/dividend/data/repository/LlmCacheKeyTest.kt`:

```kotlin
package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LlmCacheKeyTest {

    @Test
    fun `same inputs produce same 64-hex key`() {
        val a = LlmCacheKey.of("sys", "user")
        val b = LlmCacheKey.of("sys", "user")
        assertThat(a).isEqualTo(b)
        assertThat(a).hasLength(64)
        assertThat(a).matches(Regex("[0-9a-f]{64}"))
    }

    @Test
    fun `different inputs produce different keys`() {
        assertThat(LlmCacheKey.of("sys", "user"))
            .isNotEqualTo(LlmCacheKey.of("sys", "user2"))
        assertThat(LlmCacheKey.of("sys", "user"))
            .isNotEqualTo(LlmCacheKey.of("sys2", "user"))
    }

    @Test
    fun `empty strings do not throw`() {
        assertThat(LlmCacheKey.of("", "")).isEqualTo(LlmCacheKey.of("", ""))
    }
}
```

- [ ] **Step 2: 跑测试验证失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.stock.dividend.data.repository.LlmCacheKeyTest"`
Expected: FAIL（`LlmCacheKey` 未定义，编译错误）。

- [ ] **Step 3: 最小实现**

`app/src/main/java/com/stock/dividend/data/repository/LlmCacheKey.kt`:

```kotlin
package com.stock.dividend.data.repository

import java.security.MessageDigest

/**
 * LLM 结果缓存 key：SHA-256(system + "\n" + user) 的 hex（纯函数）。
 * prompt 由全部输入序列化而来，输入一变 key 必变，保证不返回过期解读。
 */
object LlmCacheKey {

    fun of(system: String, user: String): String = try {
        MessageDigest.getInstance("SHA-256")
            .digest((system + "\n" + user).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    } catch (_: Exception) {
        ""
    }
}
```

- [ ] **Step 4: 跑测试验证通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.stock.dividend.data.repository.LlmCacheKeyTest"`
Expected: PASS（3 个用例全绿）。

- [ ] **Step 5: 提交（可选，需用户同意）**

```bash
git add app/src/test/java/com/stock/dividend/data/repository/LlmCacheKeyTest.kt app/src/main/java/com/stock/dividend/data/repository/LlmCacheKey.kt && git commit -m "feat(llm): LlmCacheKey 纯函数与测试"
```

---

## Task 3: 输出模型与解析器升级（新 schema + 旧兼容）

**Files:**
- Modify: `app/src/main/java/com/stock/dividend/data/repository/LlmAnalysis.kt`
- Modify: `app/src/main/java/com/stock/dividend/data/repository/StockLlmAnalysis.kt`
- Modify: `app/src/main/java/com/stock/dividend/data/repository/LlmAnalysisParser.kt`
- Modify: `app/src/test/java/com/stock/dividend/data/repository/LlmAnalysisParserTest.kt`
- Modify: `app/src/main/java/com/stock/dividend/ui/screen/PortfolioEvaluationScreen.kt`（仅最小适配，保持编译）

- [ ] **Step 1: 写失败测试（更新 Parser 测试到新 schema）**

`app/src/test/java/com/stock/dividend/data/repository/LlmAnalysisParserTest.kt` 整体替换为：

```kotlin
package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LlmAnalysisParserTest {

    @Test
    fun `parses full json with structured stock comments`() {
        val raw = """{"overview":"组合偏防御","stockComments":{"600036":{"brief":"低估可关注","risks":["银行占比高"]}},"risks":["整体股息率偏低"]}"""
        val a = LlmAnalysisParser.parse(raw)
        assertThat(a.overview).isEqualTo("组合偏防御")
        assertThat(a.stockComments["600036"]?.brief).isEqualTo("低估可关注")
        assertThat(a.stockComments["600036"]?.risks).containsExactly("银行占比高")
        assertThat(a.risks).containsExactly("整体股息率偏低")
    }

    @Test
    fun `legacy string stock comments map to brief`() {
        val raw = """{"overview":"x","stockComments":{"600036":"低估"},"risks":[]}"""
        val a = LlmAnalysisParser.parse(raw)
        assertThat(a.stockComments["600036"]?.brief).isEqualTo("低估")
        assertThat(a.stockComments["600036"]?.risks).isEmpty()
    }

    @Test
    fun `missing risks in stock comment yields empty list`() {
        val raw = """{"overview":"x","stockComments":{"600036":{"brief":"ok"}}}"""
        val a = LlmAnalysisParser.parse(raw)
        assertThat(a.stockComments["600036"]?.brief).isEqualTo("ok")
        assertThat(a.stockComments["600036"]?.risks).isEmpty()
    }

    @Test
    fun `missing risks yields empty list`() {
        val raw = """{"overview":"x","stockComments":{}}"""
        val a = LlmAnalysisParser.parse(raw)
        assertThat(a.risks).isEmpty()
    }

    @Test
    fun `missing stockComments yields empty map`() {
        val raw = """{"overview":"x","risks":[]}"""
        val a = LlmAnalysisParser.parse(raw)
        assertThat(a.stockComments).isEmpty()
    }

    @Test
    fun `json fenced in code block is extracted`() {
        val raw = "```json\n{\"overview\":\"fenced\"}\n```"
        assertThat(LlmAnalysisParser.parse(raw).overview).isEqualTo("fenced")
    }

    @Test
    fun `plain text falls back to overview`() {
        val raw = "这只是一段纯文本解读，没有 JSON。"
        val a = LlmAnalysisParser.parse(raw)
        assertThat(a.overview).isEqualTo(raw)
        assertThat(a.stockComments).isEmpty()
        assertThat(a.risks).isEmpty()
    }

    @Test
    fun `malformed json does not throw`() {
        val raw = """{"overview": broken"""
        val a = LlmAnalysisParser.parse(raw)
        assertThat(a.overview).isNotEmpty()
    }
}
```

- [ ] **Step 2: 跑测试验证失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.stock.dividend.data.repository.LlmAnalysisParserTest"`
Expected: FAIL（编译失败：`stockComments` 仍为 `Map<String, String>`，`?.brief` 不存在）。

- [ ] **Step 3: 修改数据模型**

`app/src/main/java/com/stock/dividend/data/repository/LlmAnalysis.kt` 整体替换为：

```kotlin
package com.stock.dividend.data.repository

/** 组合级每股解读（升级版：brief ≤60 字 + 该股风险点）。 */
data class StockLlmComment(
    val brief: String,
    val risks: List<String>,
)

data class LlmAnalysis(
    val overview: String,
    val stockComments: Map<String, StockLlmComment>,
    val risks: List<String>,
)

sealed interface LlmAnalysisResult {
    data class Success(
        val analysis: LlmAnalysis,
        /** epoch ms；null=旧路径未携带。 */
        val analyzedAt: Long? = null,
        val fromCache: Boolean = false,
        /** 如「刷新失败，显示上次分析结果」。 */
        val notice: String? = null,
    ) : LlmAnalysisResult
    data object NotConfigured : LlmAnalysisResult
    data class Error(val message: String) : LlmAnalysisResult
}

sealed interface LlmAnalysisState {
    data object Idle : LlmAnalysisState
    data object Loading : LlmAnalysisState
    data object NotConfigured : LlmAnalysisState
    data class Success(
        val analysis: LlmAnalysis,
        val analyzedAt: Long? = null,
        val fromCache: Boolean = false,
        val notice: String? = null,
    ) : LlmAnalysisState
    data class Error(val message: String) : LlmAnalysisState
}
```

`app/src/main/java/com/stock/dividend/data/repository/StockLlmAnalysis.kt` 整体替换为：

```kotlin
package com.stock.dividend.data.repository

/**
 * 个股 LLM 解读结果（结构化）。schema 与组合级 [LlmAnalysis] 语义不同：
 * 个股无 overview/无多股，故独立定义而非污染组合级模型。
 */
data class StockLlmAnalysis(
    /** ≤120字：结合三周期 BOLL 位置判断当前价格贵/便宜/合理。 */
    val valuation: String,
    /** ≤120字：结合分红率趋势与预测样本判断分红可持续性。 */
    val dividendSustainability: String,
    /** ≤20字：一句话定性结论（如"可逢低关注"/"暂观望"/"持有"），不给具体价。 */
    val action: String,
    /** 具体风险点。 */
    val risks: List<String>,
)

/** 个股级编排返回类型（与 [LlmAnalysisResult] 对称，Success 携带缓存元数据）。 */
sealed interface StockLlmAnalysisResult {
    data class Success(
        val analysis: StockLlmAnalysis,
        val analyzedAt: Long? = null,
        val fromCache: Boolean = false,
        val notice: String? = null,
    ) : StockLlmAnalysisResult
    data object NotConfigured : StockLlmAnalysisResult
    data class Error(val message: String) : StockLlmAnalysisResult
}

/**
 * 个股 LLM 解读的 UI 状态。结构与组合级 [LlmAnalysisState] 完全对称（五态语义一致），
 * 但 Success 的 payload 是 [StockLlmAnalysis]，故独立定义以保持类型清晰。
 */
sealed interface StockLlmAnalysisState {
    data object Idle : StockLlmAnalysisState
    data object Loading : StockLlmAnalysisState
    data object NotConfigured : StockLlmAnalysisState
    data class Success(
        val analysis: StockLlmAnalysis,
        val analyzedAt: Long? = null,
        val fromCache: Boolean = false,
        val notice: String? = null,
    ) : StockLlmAnalysisState
    data class Error(val message: String) : StockLlmAnalysisState
}
```

- [ ] **Step 4: 修改 Parser**

`app/src/main/java/com/stock/dividend/data/repository/LlmAnalysisParser.kt` 整体替换为：

```kotlin
package com.stock.dividend.data.repository

import com.google.gson.JsonParser

/**
 * 解析 LLM 返回内容为 [LlmAnalysis]（纯函数，永不抛异常）。
 * 兜底链：完整 JSON → 字段缺失补默认 → ```json 代码块提取 → 纯文本降级。
 * stockComments 兼容两种形态：新对象 {"brief","risks"} 与旧字符串（→brief，risks 空）。
 */
object LlmAnalysisParser {

    fun parse(rawContent: String): LlmAnalysis {
        val trimmed = rawContent.trim()
        if (trimmed.isEmpty()) return LlmAnalysis("", emptyMap(), emptyList())
        val jsonStr = JsonExtraction.extractJsonObject(trimmed) ?: return LlmAnalysis(trimmed, emptyMap(), emptyList())
        return try {
            val obj = JsonParser.parseString(jsonStr).asJsonObject
            val overview = obj.get("overview")?.takeIf { !it.isJsonNull }?.asString ?: trimmed
            val comments = buildMap {
                obj.get("stockComments")?.takeIf { it.isJsonObject }?.asJsonObject?.entrySet()?.forEach { (k, v) ->
                    if (!v.isJsonNull) {
                        val comment = when {
                            v.isJsonObject -> {
                                val c = v.asJsonObject
                                StockLlmComment(
                                    brief = c.get("brief")?.takeIf { !it.isJsonNull }?.asString ?: "",
                                    risks = buildList {
                                        c.get("risks")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { r ->
                                            if (!r.isJsonNull) add(r.asString)
                                        }
                                    }
                                )
                            }
                            v.isJsonPrimitive && v.asJsonPrimitive.isString ->
                                StockLlmComment(brief = v.asString, risks = emptyList())
                            else -> null
                        }
                        if (comment != null) put(k, comment)
                    }
                }
            }
            val risks = buildList {
                obj.get("risks")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { v ->
                    if (!v.isJsonNull) add(v.asString)
                }
            }
            LlmAnalysis(overview, comments, risks)
        } catch (_: Exception) {
            LlmAnalysis(trimmed, emptyMap(), emptyList())
        }
    }
}
```

- [ ] **Step 5: 最小适配 EvaluationCard（保持主代码编译）**

`PortfolioEvaluationScreen.kt`：

```kotlin
private fun EvaluationCard(stock: EvaluatedStock, aiComment: StockLlmComment? = null) {
```

尾部渲染（原 `if (!aiComment.isNullOrEmpty()) { Text("AI：$aiComment", ...) }`）替换为：

```kotlin
            if (aiComment != null && aiComment.brief.isNotBlank()) {
                Text(
                    aiComment.brief,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
```

并在文件 import 区追加 `import com.stock.dividend.data.repository.StockLlmComment`。

- [ ] **Step 6: 跑测试验证通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.stock.dividend.data.repository.LlmAnalysisParserTest"`
Expected: PASS（8 个用例全绿）。

- [ ] **Step 7: 编译全量主代码**

Run: `./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 8: 提交（可选，需用户同意）**

```bash
git add app/src/main/java/com/stock/dividend/data/repository/LlmAnalysis.kt app/src/main/java/com/stock/dividend/data/repository/StockLlmAnalysis.kt app/src/main/java/com/stock/dividend/data/repository/LlmAnalysisParser.kt app/src/test/java/com/stock/dividend/data/repository/LlmAnalysisParserTest.kt app/src/main/java/com/stock/dividend/ui/screen/PortfolioEvaluationScreen.kt && git commit -m "feat(llm): 组合级输出升级为每股 brief+risks（兼容旧字符串）"
```

---

## Task 4: PortfolioLlmInput + PromptBuilder 升级

**Files:**
- Create: `app/src/main/java/com/stock/dividend/data/repository/PortfolioLlmInput.kt`
- Modify: `app/src/main/java/com/stock/dividend/data/repository/LlmPromptBuilder.kt`（整体替换）
- Modify: `app/src/test/java/com/stock/dividend/data/repository/LlmPromptBuilderTest.kt`（整体替换）

- [ ] **Step 1: 创建输入快照数据类**

`app/src/main/java/com/stock/dividend/data/repository/PortfolioLlmInput.kt`:

```kotlin
package com.stock.dividend.data.repository

/**
 * 组合级 LLM 解读的完整输入快照（纯数据，无 Android 依赖，便于单测构造）。
 * 包含既有评估参数 + 每股深度数据（基本面/1-3-5年预测/买入线）。
 */
data class PortfolioLlmInput(
    val evaluation: List<EvaluatedStock>,
    val dailyBands: Map<String, BollBand?>,
    val monthlyBands: Map<String, BollBand?>,
    val signals: PortfolioSignals,
    val thresholds: DividendThresholds,
    val userStrategies: List<UserStrategyRef> = emptyList(),
    /** 每股深度数据；缺失的股票无 key（prompt 渲染 "—"）。 */
    val stockDetails: Map<String, PortfolioLlmStockDetail> = emptyMap(),
)

/** 单股深度数据：只放组合级缺的三项；位置/股息率/action 已在 [EvaluatedStock] + bands 中。 */
data class PortfolioLlmStockDetail(
    val fundamentals: Fundamentals? = null,
    val forecast: StockLlmInput.StockLlmForecast? = null,
    val buyThreshold: StockLlmInput.StockLlmBuyThreshold? = null,
)
```

- [ ] **Step 2: 写失败测试（Builder 新 API）**

`app/src/test/java/com/stock/dividend/data/repository/LlmPromptBuilderTest.kt` 整体替换为：

```kotlin
package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LlmPromptBuilderTest {

    private fun stock(code: String, action: HoldingAction = HoldingAction.BUY) = EvaluatedStock(
        code = code, name = "n$code", industry = "银行",
        action = action, priceVsLower = 0.1, dividendYield = 4.2,
        bollBand = null, currentPrice = 10.0, reasons = listOf("接近下轨")
    )

    private val noSignals = PortfolioSignals(
        positionControl = PositionControlSignal(false, 0.0, 0.0, 15),
        buySignals = emptyList()
    )

    private fun prompt(
        stocks: List<EvaluatedStock>,
        signals: PortfolioSignals = noSignals,
        daily: Map<String, BollBand?> = emptyMap(),
        monthly: Map<String, BollBand?> = emptyMap(),
        details: Map<String, PortfolioLlmStockDetail> = emptyMap(),
    ) = LlmPromptBuilder.build(
        PortfolioLlmInput(
            evaluation = stocks,
            dailyBands = daily,
            monthlyBands = monthly,
            signals = signals,
            thresholds = DividendThresholds(),
            stockDetails = details
        )
    )

    @Test
    fun `system prompt states JSON schema and constraints`() {
        val p = prompt(listOf(stock("600036")))
        assertThat(p.system).contains("overview")
        assertThat(p.system).contains("stockComments")
        assertThat(p.system).contains("brief")
        assertThat(p.system).contains("risks")
        assertThat(p.system).contains("仅基于")
    }

    @Test
    fun `user message includes each stock code action and metrics`() {
        val p = prompt(listOf(stock("600036")))
        assertThat(p.user).contains("600036")
        assertThat(p.user).contains("买")
        assertThat(p.user).contains("4.2")
    }

    @Test
    fun `position control signal surfaces cash hint in user message`() {
        val sig = PortfolioSignals(
            positionControl = PositionControlSignal(true, 0.6, 1.5, 15),
            buySignals = emptyList()
        )
        val p = prompt(listOf(stock("600036")), sig)
        assertThat(p.user).contains("15")
        assertThat(p.user).contains("控仓")
    }

    @Test
    fun `resonant buy codes listed in user message`() {
        val sig = PortfolioSignals(
            positionControl = PositionControlSignal(false, 0.0, 0.0, 15),
            buySignals = listOf(
                MultiTimeframeBuySignal("600036", true, true, true, true)
            )
        )
        val p = prompt(listOf(stock("600036")), sig)
        assertThat(p.user).contains("600036")
        assertThat(p.user).contains("共振")
    }

    @Test
    fun `user message excludes cost basis`() {
        val p = prompt(listOf(stock("600036")))
        assertThat(p.user).doesNotContain("成本")
        assertThat(p.user).doesNotContain("cost")
    }

    @Test
    fun `three-period positions appear when bands present`() {
        val daily = mapOf("600036" to BollBand(middle = 10.0, upper = 11.0, lower = 9.0))
        val monthly = mapOf("600036" to BollBand(middle = 12.0, upper = 14.0, lower = 10.0))
        val s = stock("600036").copy(currentPrice = 9.5, bollBand = BollBand(10.0, 11.0, 9.0))
        val p = prompt(listOf(s), daily = daily, monthly = monthly)
        assertThat(p.user).contains("日距下轨")
        assertThat(p.user).contains("周距下轨")
        assertThat(p.user).contains("月距下轨")
    }

    @Test
    fun `missing bands show dash for that period`() {
        val p = prompt(listOf(stock("600036")))
        assertThat(p.user).contains("日距下轨 —")
    }

    @Test
    fun `empty stocks still produces valid prompt`() {
        val p = prompt(emptyList())
        assertThat(p.system).isNotEmpty()
        assertThat(p.user).isNotEmpty()
    }

    @Test
    fun `deep data rendered per stock`() {
        val detail = PortfolioLlmStockDetail(
            fundamentals = Fundamentals(
                periods = listOf(
                    Fundamentals.Period(
                        reportDate = "2025-03-31", roe = 12.0, debtToAssetRatio = 60.0,
                        revenueYoy = 8.0, netProfitYoy = 5.0, payoutRatio = 25.0,
                        dividendPlan = "10派3.60元(含税)"
                    )
                )
            ),
            forecast = StockLlmInput.StockLlmForecast(
                avgCashPerShare1Y = 0.5, avgCashPerShare3Y = 0.6,
                avgCashPerShare5Y = 0.7, actualYears = 5
            ),
            buyThreshold = StockLlmInput.StockLlmBuyThreshold(
                targetYieldPercent = 6.5, currentYieldPercent = 4.2, reached = false
            )
        )
        val p = prompt(listOf(stock("600036")), details = mapOf("600036" to detail))
        assertThat(p.user).contains("ROE 12.0%")
        assertThat(p.user).contains("负债率 60.0%")
        assertThat(p.user).contains("营收 +8.0%")
        assertThat(p.user).contains("净利 +5.0%")
        assertThat(p.user).contains("派息率 25.0%")
        assertThat(p.user).contains("10派3.60元(含税)")
        assertThat(p.user).contains("1年均 ¥0.50")
        assertThat(p.user).contains("5年均 ¥0.70")
        assertThat(p.user).contains("样本 5 年")
        assertThat(p.user).contains("目标 6.5%")
        assertThat(p.user).contains("未达标")
    }

    @Test
    fun `missing deep data shows dashes`() {
        val p = prompt(listOf(stock("600036")))
        assertThat(p.user).contains("基本面 — / 预测 — / 买入线 —")
    }

    @Test
    fun `fundamentals with missing metrics render dash`() {
        val detail = PortfolioLlmStockDetail(
            fundamentals = Fundamentals(
                periods = listOf(
                    Fundamentals.Period("2025-03-31", roe = null, debtToAssetRatio = null,
                        revenueYoy = null, netProfitYoy = null, payoutRatio = null)
                )
            )
        )
        val p = prompt(listOf(stock("600036")), details = mapOf("600036" to detail))
        assertThat(p.user).contains("ROE —")
        assertThat(p.user).contains("派息率 —")
    }

    @Test
    fun `userStrategies rendered globally without sourceNote`() {
        val input = PortfolioLlmInput(
            evaluation = listOf(stock("600036")),
            dailyBands = emptyMap(),
            monthlyBands = emptyMap(),
            signals = noSignals,
            thresholds = DividendThresholds(),
            userStrategies = listOf(UserStrategyRef("BUY", "ROE高", emptyList(), null, 5))
        )
        val p = LlmPromptBuilder.build(input)
        assertThat(p.user).contains("用户投资原则")
        assertThat(p.user).contains("[买入]")
        assertThat(p.user).contains("5天前")
    }

    @Test
    fun `system contains user strategy semantics`() {
        val p = prompt(listOf(stock("600036")))
        assertThat(p.system).contains("用户投资原则")
    }
}
```

- [ ] **Step 3: 跑测试验证失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.stock.dividend.data.repository.LlmPromptBuilderTest"`
Expected: FAIL（编译错误：`LlmPromptBuilder.build` 仍是旧签名，不接受 `PortfolioLlmInput`）。

- [ ] **Step 4: 实现 Builder**

`app/src/main/java/com/stock/dividend/data/repository/LlmPromptBuilder.kt` 整体替换为：

```kotlin
package com.stock.dividend.data.repository

/**
 * 把规则评估结果 + 策略信号 + 每股深度数据（基本面/预测/买入线）序列化为 LLM prompt（纯函数）。
 * system 定角色 + JSON schema + 约束；user 放结构化数据。不喂成本价等敏感信息。
 */
object LlmPromptBuilder {

    data class LlmPrompt(val system: String, val user: String)

    fun build(input: PortfolioLlmInput): LlmPrompt = LlmPrompt(SYSTEM, buildUser(input))

    private val SYSTEM = """
你是一位稳健、客观的中文分红股投资分析助手。

【任务】
基于用户提供的持仓评估数据、策略信号与每股深度数据（已由规则引擎判定），输出自然语言解读。

【数据语义（仅供理解，不要复述规则公式）】
- action=买：日下轨+周下轨+月中轨及以下 三周期共振，且股息率达最低门槛
- action=卖：价格处于周线 BOLL 上轨附近（偏高）
- action=持有：未达三周期共振（中轨、仅单一周期偏低、或股息率不足）
- 距下轨%：0=在下轨（便宜），100=在上轨（贵）；每只股给出 日/周/月 三周期的距下轨%，据此判断多周期共振
- 股息率%：年现金分红 / 现价
- 仓位控制信号：多数股票抵达上轨 + 整体股息偏低 → 建议控仓、现金 ≥ 目标%
- 三周期共振买点：与 action=买 同源（日下轨 + 周下轨 + 月中轨及以下 同时成立）
- 基本面：ROE/负债率/营收净利同比/派息率为最新报告期数据；趋势为近 N 期整体方向，供判断质地与分红可持续性
- 预测：基于历史分红的线性平均，非承诺；实际样本年数越少越不可靠
- 买入线：股息率达到「国债收益率×倍数」时视为低估信号
- 用户投资原则：用户此前从外部内容整理出的整体投资观点，对所有标的通用，属用户个人视角，非客观数据；解读时可对照呼应，但不要盲从或简单复述。

【输出要求】严格输出 JSON：
{"overview":"组合整体解读≤150字","stockComments":{"<code>":{"brief":"该股≤60字","risks":["该股具体风险点"]}},"risks":["具体风险点"]}

【约束】
1. 仅基于提供数据，绝不编造价格/股息率/财报/未给出的信息；缺失数据标"—"的部分不要臆测。
2. 中文，专业易懂，避免绝对化断言。
3. 不给明确买卖时点或价格目标；这是解读，不是指令。
4. 仓位控制信号触发时，overview 必须明确提示控仓与现金 ≥ 目标%。
5. 三周期共振买点的股票要在 stockComments 中点名。
6. 每股 brief 需结合基本面/预测/买入线等深度数据，风险点要具体，不复述规则逻辑。
""".trim()

    private fun buildUser(input: PortfolioLlmInput): String {
        val stocks = input.evaluation
        val sb = StringBuilder()
        sb.append("【门槛】买入需三周期共振且股息率 ≥ ${input.thresholds.minYieldPercent}%\n")
        sb.append("【持仓评估】\n")
        if (stocks.isEmpty()) sb.append("（无）\n")
        stocks.forEach { s ->
            val actionZh = when (s.action) {
                HoldingAction.BUY -> "买"
                HoldingAction.SELL -> "卖"
                HoldingAction.HOLD -> "持有"
                HoldingAction.INSUFFICIENT_DATA -> "数据不足"
            }
            val daily = ratioVsLower(s.currentPrice, input.dailyBands[s.code])
            val weekly = if (s.priceVsLower.isFinite()) "${(s.priceVsLower * 100).toInt()}%" else "—"
            val monthly = ratioVsLower(s.currentPrice, input.monthlyBands[s.code])
            sb.append("- ${s.code} ${s.name} [${s.industry}]：$actionZh，股息率 ${s.dividendYield?.let { "${"%.1f".format(it)}%" } ?: "—"}")
            sb.append(" | 日距下轨 $daily / 周距下轨 $weekly / 月距下轨 $monthly")
            appendDeepData(sb, input.stockDetails[s.code])
            sb.append("\n")
        }
        sb.append("【策略信号】\n")
        val pc = input.signals.positionControl
        if (pc.triggered) {
            sb.append("- 控仓：触发（上轨占比 ${"%.0f".format(pc.upperBandRatio * 100)}%，平均股息率 ${"%.1f".format(pc.avgDividendYield)}%），建议现金 ≥ ${pc.targetCashPercent}%\n")
        } else {
            sb.append("- 控仓：未触发\n")
        }
        if (input.signals.buySignals.isNotEmpty()) {
            sb.append("- 三周期共振买点：${input.signals.buySignals.joinToString("、") { it.code }}\n")
        } else {
            sb.append("- 三周期共振买点：无\n")
        }
        sb.append("【用户投资原则（来自截图分析，全局，仅供参照）】")
        if (input.userStrategies.isEmpty()) {
            sb.append("—\n")
        } else {
            sb.append("\n")
            input.userStrategies.forEach { ref ->
                val dirZh = when (ref.direction) { "BUY" -> "买入"; "SELL" -> "卖出"; else -> "观望" }
                sb.append("- [$dirZh] ${ref.reasoning}(${ref.daysAgo}天前)")
                if (ref.risks.isNotEmpty()) sb.append(" 风险:${ref.risks.joinToString("/")}")
                sb.append("\n")
            }
        }
        return sb.toString()
    }

    /** 每股深度数据要点式渲染；缺失渲染 "—"，不阻塞。 */
    private fun appendDeepData(sb: StringBuilder, detail: PortfolioLlmStockDetail?) {
        if (detail == null) {
            sb.append(" | 基本面 — / 预测 — / 买入线 —")
            return
        }
        appendFundamentals(sb, detail.fundamentals)
        appendForecast(sb, detail.forecast)
        appendBuyThreshold(sb, detail.buyThreshold)
    }

    private fun appendFundamentals(sb: StringBuilder, f: Fundamentals?) {
        if (f == null || f.periods.isEmpty()) {
            sb.append(" | 基本面 —")
            return
        }
        val latest = f.periods.last()
        sb.append(" | 基本面: ROE ${fmtPercent(latest.roe)} / 负债率 ${fmtPercent(latest.debtToAssetRatio)}")
        sb.append(" / 营收 ${fmtYoy(latest.revenueYoy)} / 净利 ${fmtYoy(latest.netProfitYoy)}")
        sb.append(" / 派息率 ${fmtPercent(latest.payoutRatio)}")
        latest.dividendPlan?.takeIf { it.isNotBlank() }?.let { sb.append(" / $it") }
        sb.append("（近${f.periods.size}期${roeTrendZh(f.periods)}）")
    }

    private fun appendForecast(sb: StringBuilder, f: StockLlmInput.StockLlmForecast?) {
        if (f == null) {
            sb.append(" / 预测 —")
            return
        }
        sb.append(" / 预测: 1年均 ¥${"%.2f".format(f.avgCashPerShare1Y)}")
        sb.append(" / 3年均 ¥${"%.2f".format(f.avgCashPerShare3Y)}")
        sb.append(" / 5年均 ¥${"%.2f".format(f.avgCashPerShare5Y)}（样本 ${f.actualYears} 年）")
    }

    private fun appendBuyThreshold(sb: StringBuilder, bt: StockLlmInput.StockLlmBuyThreshold?) {
        if (bt == null) {
            sb.append(" / 买入线 —")
            return
        }
        sb.append(" / 买入线: 目标 ${"%.1f".format(bt.targetYieldPercent)}%")
        sb.append("，当前 ${bt.currentYieldPercent?.let { "${"%.1f".format(it)}%" } ?: "—"}")
        val reachedZh = when (bt.reached) {
            true -> "已达标"
            false -> "未达标"
            null -> "无法判定"
        }
        sb.append("，$reachedZh")
    }

    /** ROE 序列趋势（非空值首末比较，近似描述；样本 <2 期为"数据不足"）。 */
    private fun roeTrendZh(periods: List<Fundamentals.Period>): String {
        val roes = periods.mapNotNull { it.roe?.takeIf { v -> v.isFinite() } }
        return when {
            roes.size < 2 -> "数据不足"
            roes.last() > roes.first() + 0.3 -> "ROE整体上升"
            roes.first() > roes.last() + 0.3 -> "ROE整体下降"
            else -> "ROE整体平稳"
        }
    }

    private fun fmtPercent(v: Double?): String = when {
        v == null || !v.isFinite() -> "—"
        else -> "${"%.1f".format(v)}%"
    }

    private fun fmtYoy(v: Double?): String = when {
        v == null || !v.isFinite() -> "—"
        else -> "${if (v >= 0) "+" else ""}${"%.1f".format(v)}%"
    }

    /** (price - lower) / (upper - lower) → "X%"，clamp 0..100；band/price 无效返回 "—"。 */
    private fun ratioVsLower(price: Double?, band: BollBand?): String {
        if (price == null || price <= 0.0 || band == null || band.upper <= band.lower) return "—"
        val r = ((price - band.lower) / (band.upper - band.lower) * 100).toInt().coerceIn(0, 100)
        return "$r%"
    }
}
```

- [ ] **Step 5: 跑测试验证通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.stock.dividend.data.repository.LlmPromptBuilderTest"`
Expected: PASS（16 个用例全绿）。

- [ ] **Step 6: 提交（可选，需用户同意）**

```bash
git add app/src/main/java/com/stock/dividend/data/repository/PortfolioLlmInput.kt app/src/main/java/com/stock/dividend/data/repository/LlmPromptBuilder.kt app/src/test/java/com/stock/dividend/data/repository/LlmPromptBuilderTest.kt && git commit -m "feat(llm): 组合 prompt 接入每股深度数据"
```

---

## Task 5: FundamentalsCacheRepository

**Files:**
- Create: `app/src/test/java/com/stock/dividend/data/repository/FundamentalsCacheRepositoryTest.kt`
- Create: `app/src/main/java/com/stock/dividend/data/repository/FundamentalsCacheRepository.kt`

- [ ] **Step 1: 写失败测试**

`app/src/test/java/com/stock/dividend/data/repository/FundamentalsCacheRepositoryTest.kt`:

```kotlin
package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.stock.dividend.data.local.dao.FundamentalsCacheDao
import com.stock.dividend.data.local.entity.FundamentalsCacheEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

class FundamentalsCacheRepositoryTest {

    private val dao: FundamentalsCacheDao = mockk()
    private val stockRepository: StockRepository = mockk()
    private val gson = Gson()

    private val fundamentals = Fundamentals(
        periods = listOf(Fundamentals.Period("2025-03-31", 12.0, 60.0, 8.0, 5.0, payoutRatio = 25.0))
    )

    private fun entity(fetchedAt: Long = System.currentTimeMillis()) = FundamentalsCacheEntity(
        stockCode = "sh.600036",
        payload = gson.toJson(fundamentals),
        fetchedAt = fetchedAt
    )

    private fun repo() = FundamentalsCacheRepository(dao, stockRepository)

    @Test
    fun `fresh cache returns without network`() = runTest {
        coEvery { dao.get("sh.600036") } returns entity()
        val result = repo().getFundamentals("sh.600036")
        assertThat(result).isNotNull()
        assertThat(result!!.periods[0].roe).isEqualTo(12.0)
        coVerify(exactly = 0) { stockRepository.fetchFundamentals(any()) }
    }

    @Test
    fun `stale cache triggers network and writes through`() = runTest {
        coEvery { dao.get("sh.600036") } returns entity(fetchedAt = System.currentTimeMillis() - 8L * 24 * 60 * 60 * 1000)
        coEvery { stockRepository.fetchFundamentals("sh.600036") } returns fundamentals
        coEvery { dao.upsert(any()) } returns Unit

        val result = repo().getFundamentals("sh.600036")
        assertThat(result).isNotNull()
        coVerify { stockRepository.fetchFundamentals("sh.600036") }
        coVerify { dao.upsert(any()) }
    }

    @Test
    fun `network failure falls back to stale cache`() = runTest {
        coEvery { dao.get("sh.600036") } returns entity(fetchedAt = System.currentTimeMillis() - 8L * 24 * 60 * 60 * 1000)
        coEvery { stockRepository.fetchFundamentals("sh.600036") } returns null

        val result = repo().getFundamentals("sh.600036")
        assertThat(result).isNotNull()
        assertThat(result!!.periods[0].roe).isEqualTo(12.0)
    }

    @Test
    fun `no cache and network failure yields null`() = runTest {
        coEvery { dao.get("sh.600036") } returns null
        coEvery { stockRepository.fetchFundamentals("sh.600036") } returns null

        val result = repo().getFundamentals("sh.600036")
        assertThat(result).isNull()
    }

    @Test
    fun `network exception is swallowed and stale cache returned`() = runTest {
        coEvery { dao.get("sh.600036") } returns entity(fetchedAt = System.currentTimeMillis() - 8L * 24 * 60 * 60 * 1000)
        coEvery { stockRepository.fetchFundamentals("sh.600036") } throws RuntimeException("boom")

        val result = repo().getFundamentals("sh.600036")
        assertThat(result).isNotNull()
    }

    @Test
    fun `forceRefresh bypasses fresh cache`() = runTest {
        coEvery { dao.get("sh.600036") } returns entity()
        coEvery { stockRepository.fetchFundamentals("sh.600036") } returns fundamentals
        coEvery { dao.upsert(any()) } returns Unit

        repo().getFundamentals("sh.600036", forceRefresh = true)
        coVerify { stockRepository.fetchFundamentals("sh.600036") }
    }
}
```

- [ ] **Step 2: 跑测试验证失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.stock.dividend.data.repository.FundamentalsCacheRepositoryTest"`
Expected: FAIL（`FundamentalsCacheRepository` 未定义）。

- [ ] **Step 3: 最小实现**

`app/src/main/java/com/stock/dividend/data/repository/FundamentalsCacheRepository.kt`:

```kotlin
package com.stock.dividend.data.repository

import com.google.gson.Gson
import com.stock.dividend.data.local.dao.FundamentalsCacheDao
import com.stock.dividend.data.local.entity.FundamentalsCacheEntity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 单股基本面缓存编排：新鲜（≤7 天）直接返回；过期/缺失走 [StockRepository.fetchFundamentals]
 * 并写缓存；网络失败回退旧缓存、无缓存则 null。全程吞异常（红线 #2）。
 */
@Singleton
class FundamentalsCacheRepository @Inject constructor(
    private val fundamentalsCacheDao: FundamentalsCacheDao,
    private val stockRepository: StockRepository,
) {
    private val gson = Gson()

    suspend fun getFundamentals(stockCode: String, forceRefresh: Boolean = false): Fundamentals? {
        val cached = runCatching { fundamentalsCacheDao.get(stockCode) }.getOrNull()
        if (!forceRefresh && cached != null && isFresh(cached.fetchedAt)) {
            return parse(cached.payload)
        }

        val remote = runCatching { stockRepository.fetchFundamentals(stockCode) }.getOrNull()
        if (remote != null) {
            runCatching {
                fundamentalsCacheDao.upsert(
                    FundamentalsCacheEntity(
                        stockCode = stockCode,
                        payload = gson.toJson(remote),
                        fetchedAt = System.currentTimeMillis()
                    )
                )
            }
            return remote
        }
        return cached?.let { parse(it.payload) }
    }

    private fun parse(payload: String): Fundamentals? =
        runCatching { gson.fromJson(payload, Fundamentals::class.java) }.getOrNull()

    private fun isFresh(fetchedAt: Long): Boolean =
        System.currentTimeMillis() - fetchedAt < CACHE_TTL_MS

    companion object {
        const val CACHE_TTL_MS = 7L * 24 * 60 * 60 * 1000
    }
}
```

- [ ] **Step 4: 跑测试验证通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.stock.dividend.data.repository.FundamentalsCacheRepositoryTest"`
Expected: PASS（6 个用例全绿）。

- [ ] **Step 5: 提交（可选，需用户同意）**

```bash
git add app/src/test/java/com/stock/dividend/data/repository/FundamentalsCacheRepositoryTest.kt app/src/main/java/com/stock/dividend/data/repository/FundamentalsCacheRepository.kt && git commit -m "feat(llm): 基本面缓存仓库（7天TTL+回退）与测试"
```

---

## Task 6: LlmAnalysisCacheStore

**Files:**
- Create: `app/src/test/java/com/stock/dividend/data/repository/LlmAnalysisCacheStoreTest.kt`
- Create: `app/src/main/java/com/stock/dividend/data/repository/LlmAnalysisCacheStore.kt`

- [ ] **Step 1: 写失败测试**

`app/src/test/java/com/stock/dividend/data/repository/LlmAnalysisCacheStoreTest.kt`:

```kotlin
package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.local.dao.LlmAnalysisCacheDao
import com.stock.dividend.data.local.entity.LlmAnalysisCacheEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Test

class LlmAnalysisCacheStoreTest {

    private val dao: LlmAnalysisCacheDao = mockk()
    private fun store() = LlmAnalysisCacheStore(dao)

    private val portfolio = LlmAnalysis(
        overview = "组合偏防御",
        stockComments = mapOf("600036" to StockLlmComment("低估", listOf("银行占比高"))),
        risks = listOf("整体股息率偏低")
    )

    @Test
    fun `portfolio entry round trip`() = runTest {
        coEvery { dao.get("k", "PORTFOLIO") } returns LlmAnalysisCacheEntity(
            "k", "PORTFOLIO",
            """{"overview":"组合偏防御","stockComments":{"600036":{"brief":"低估","risks":["银行占比高"]}},"risks":["整体股息率偏低"]}""",
            123L
        )
        val hit = store().getPortfolio("k")
        assertThat(hit).isNotNull()
        assertThat(hit!!.createdAt).isEqualTo(123L)
        assertThat(hit.analysis.overview).isEqualTo("组合偏防御")
        assertThat(hit.analysis.stockComments["600036"]?.brief).isEqualTo("低估")
        assertThat(hit.analysis.risks).containsExactly("整体股息率偏低")
    }

    @Test
    fun `corrupt payload yields null`() = runTest {
        coEvery { dao.get("k", "PORTFOLIO") } returns LlmAnalysisCacheEntity("k", "PORTFOLIO", "not json", 1L)
        assertThat(store().getPortfolio("k")).isNull()
    }

    @Test
    fun `miss yields null`() = runTest {
        coEvery { dao.get("k", "PORTFOLIO") } returns null
        assertThat(store().getPortfolio("k")).isNull()
    }

    @Test
    fun `stock put serializes with scope STOCK`() = runTest {
        coEvery { dao.upsert(any()) } returns Unit
        val entitySlot = slot<LlmAnalysisCacheEntity>()
        coEvery { dao.upsert(capture(entitySlot)) } returns Unit
        val analysis = StockLlmAnalysis("偏低", "稳", "可关注", listOf("波动"))

        store().putStock("key", analysis, 456L)

        assertThat(entitySlot.captured.cacheKey).isEqualTo("key")
        assertThat(entitySlot.captured.scope).isEqualTo("STOCK")
        assertThat(entitySlot.captured.createdAt).isEqualTo(456L)
        assertThat(entitySlot.captured.payload).contains("valuation")
        assertThat(entitySlot.captured.payload).contains("波动")
    }
}
```

- [ ] **Step 2: 跑测试验证失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.stock.dividend.data.repository.LlmAnalysisCacheStoreTest"`
Expected: FAIL（`LlmAnalysisCacheStore` 未定义）。

- [ ] **Step 3: 最小实现**

`app/src/main/java/com/stock/dividend/data/repository/LlmAnalysisCacheStore.kt`:

```kotlin
package com.stock.dividend.data.repository

import com.google.gson.Gson
import com.stock.dividend.data.local.dao.LlmAnalysisCacheDao
import com.stock.dividend.data.local.entity.LlmAnalysisCacheEntity
import javax.inject.Inject
import javax.inject.Singleton

/** 组合级缓存命中结果。 */
data class PortfolioCacheEntry(val analysis: LlmAnalysis, val createdAt: Long)

/** 个股级缓存命中结果。 */
data class StockCacheEntry(val analysis: StockLlmAnalysis, val createdAt: Long)

/**
 * LLM 解读结果缓存读写（Room + Gson）。只负责序列化与存取，
 * 新鲜判定（24h TTL）与回退策略由 [LlmAnalysisRepository] 统一处理。
 * 缓存写入失败静默跳过（红线 #2）；反序列化失败视为未命中。
 */
@Singleton
class LlmAnalysisCacheStore @Inject constructor(
    private val llmAnalysisCacheDao: LlmAnalysisCacheDao,
) {
    private val gson = Gson()

    suspend fun getPortfolio(cacheKey: String): PortfolioCacheEntry? {
        val entity = runCatching { llmAnalysisCacheDao.get(cacheKey, SCOPE_PORTFOLIO) }.getOrNull() ?: return null
        val analysis = runCatching { gson.fromJson(entity.payload, LlmAnalysis::class.java) }.getOrNull() ?: return null
        return PortfolioCacheEntry(analysis, entity.createdAt)
    }

    suspend fun getStock(cacheKey: String): StockCacheEntry? {
        val entity = runCatching { llmAnalysisCacheDao.get(cacheKey, SCOPE_STOCK) }.getOrNull() ?: return null
        val analysis = runCatching { gson.fromJson(entity.payload, StockLlmAnalysis::class.java) }.getOrNull() ?: return null
        return StockCacheEntry(analysis, entity.createdAt)
    }

    suspend fun putPortfolio(cacheKey: String, analysis: LlmAnalysis, createdAt: Long) {
        put(cacheKey, SCOPE_PORTFOLIO, gson.toJson(analysis), createdAt)
    }

    suspend fun putStock(cacheKey: String, analysis: StockLlmAnalysis, createdAt: Long) {
        put(cacheKey, SCOPE_STOCK, gson.toJson(analysis), createdAt)
    }

    private suspend fun put(cacheKey: String, scope: String, payload: String, createdAt: Long) {
        runCatching {
            llmAnalysisCacheDao.upsert(LlmAnalysisCacheEntity(cacheKey, scope, payload, createdAt))
        }
    }

    companion object {
        const val SCOPE_PORTFOLIO = "PORTFOLIO"
        const val SCOPE_STOCK = "STOCK"
    }
}
```

- [ ] **Step 4: 跑测试验证通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.stock.dividend.data.repository.LlmAnalysisCacheStoreTest"`
Expected: PASS（4 个用例全绿）。

- [ ] **Step 5: 提交（可选，需用户同意）**

```bash
git add app/src/test/java/com/stock/dividend/data/repository/LlmAnalysisCacheStoreTest.kt app/src/main/java/com/stock/dividend/data/repository/LlmAnalysisCacheStore.kt && git commit -m "feat(llm): LLM 结果缓存存取（Gson+Room）与测试"
```

---

## Task 7: LlmAnalysisRepository 缓存编排 + analyzeStock

**Files:**
- Modify: `app/src/main/java/com/stock/dividend/data/repository/LlmAnalysisRepository.kt`（整体替换）
- Modify: `app/src/test/java/com/stock/dividend/data/repository/LlmAnalysisRepositoryTest.kt`（整体替换）

- [ ] **Step 1: 写失败测试**

`app/src/test/java/com/stock/dividend/data/repository/LlmAnalysisRepositoryTest.kt` 整体替换为：

```kotlin
package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.remote.LlmApi
import com.stock.dividend.data.remote.dto.LlmChatRequest
import com.stock.dividend.data.remote.dto.LlmChatResponse
import com.stock.dividend.data.remote.dto.LlmMessage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

/** 测试用配置源：直接喂固定 [LlmConfig]，绕开 SharedPreferences。 */
private class TestConfigSource(private val flow: Flow<LlmConfig>) : LlmConfigSource {
    override fun observeConfig(): Flow<LlmConfig> = flow
}

/** suspend 接口不能用 SAM lambda，用匿名对象包一层。 */
private fun api(block: suspend (String, String, LlmChatRequest) -> LlmChatResponse): LlmApi =
    object : LlmApi {
        override suspend fun chatCompletions(url: String, auth: String, body: LlmChatRequest) =
            block(url, auth, body)
    }

class LlmAnalysisRepositoryTest {

    private val stock = EvaluatedStock(
        code = "600036", name = "招行", industry = "银行",
        action = HoldingAction.BUY, priceVsLower = 0.1, dividendYield = 4.0,
        bollBand = null, currentPrice = 10.0, reasons = emptyList()
    )
    private val signals = PortfolioSignals(
        PositionControlSignal(false, 0.0, 0.0, 15), emptyList()
    )
    private val input = PortfolioLlmInput(
        evaluation = listOf(stock),
        dailyBands = emptyMap(),
        monthlyBands = emptyMap(),
        signals = signals,
        thresholds = DividendThresholds()
    )
    private val stockInput = StockLlmInput(
        code = "600036", name = "招行", industry = "银行",
        currentPrice = 10.0, dividendRatePoints = listOf(4.0),
        latestDividendYield = 4.0, forecast = null, buyThreshold = null,
        bollDaily = null, bollWeekly = null, bollMonthly = null, fundamentals = null
    )

    private val cacheStore: LlmAnalysisCacheStore = mockk {
        coEvery { getPortfolio(any()) } returns null
        coEvery { getStock(any()) } returns null
        coEvery { putPortfolio(any(), any(), any()) } just runs
        coEvery { putStock(any(), any(), any()) } just runs
    }

    private fun repo(config: LlmConfig, api: LlmApi): LlmAnalysisRepository =
        LlmAnalysisRepository(api, TestConfigSource(flowOf(config)), cacheStore)

    @Test
    fun `returns NotConfigured when key missing`() = runTest {
        val r = repo(LlmConfig("https://x/", "", "m"), api { _, _, _ -> resp(""""x"""") })
            .analyze(input)
        assertThat(r).isInstanceOf(LlmAnalysisResult.NotConfigured::class.java)
    }

    @Test
    fun `returns NotConfigured when stocks empty`() = runTest {
        val r = repo(LlmConfig("https://x/", "k", "m"), api { _, _, _ -> resp(""""x"""") })
            .analyze(input.copy(evaluation = emptyList()))
        assertThat(r).isInstanceOf(LlmAnalysisResult.NotConfigured::class.java)
    }

    @Test
    fun `returns Success on valid response and writes cache`() = runTest {
        val api = api { _, _, _ -> resp("""{"overview":"ok","stockComments":{},"risks":[]}""") }
        val r = repo(LlmConfig("https://api.deepseek.com/v1/", "k", "deepseek-chat"), api)
            .analyze(input)
        assertThat(r).isInstanceOf(LlmAnalysisResult.Success::class.java)
        assertThat((r as LlmAnalysisResult.Success).analysis.overview).isEqualTo("ok")
        assertThat(r.fromCache).isFalse()
        coVerify { cacheStore.putPortfolio(any(), any(), any()) }
    }

    @Test
    fun `fresh cache hit returns without calling api`() = runTest {
        coEvery { cacheStore.getPortfolio(any()) } returns PortfolioCacheEntry(
            LlmAnalysis("cached", emptyMap(), emptyList()), System.currentTimeMillis()
        )
        var calls = 0
        val api = api { _, _, _ -> calls++; resp("""{"overview":"x"}""") }
        val r = repo(LlmConfig("https://x/", "k", "m"), api).analyze(input)
        assertThat(r).isInstanceOf(LlmAnalysisResult.Success::class.java)
        assertThat((r as LlmAnalysisResult.Success).analysis.overview).isEqualTo("cached")
        assertThat(r.fromCache).isTrue()
        assertThat(calls).isEqualTo(0)
    }

    @Test
    fun `forceRefresh bypasses fresh cache`() = runTest {
        coEvery { cacheStore.getPortfolio(any()) } returns PortfolioCacheEntry(
            LlmAnalysis("cached", emptyMap(), emptyList()), System.currentTimeMillis()
        )
        val api = api { _, _, _ -> resp("""{"overview":"fresh","stockComments":{},"risks":[]}""") }
        val r = repo(LlmConfig("https://x/", "k", "m"), api).analyze(input, forceRefresh = true)
        assertThat((r as LlmAnalysisResult.Success).analysis.overview).isEqualTo("fresh")
        assertThat(r.fromCache).isFalse()
    }

    @Test
    fun `forceRefresh failure falls back to stale cache with notice`() = runTest {
        coEvery { cacheStore.getPortfolio(any()) } returns PortfolioCacheEntry(
            LlmAnalysis("old", emptyMap(), emptyList()), 1L
        )
        val api = api { _, _, _ -> throw java.io.IOException("timeout") }
        val r = repo(LlmConfig("https://x/", "k", "m"), api).analyze(input, forceRefresh = true)
        assertThat(r).isInstanceOf(LlmAnalysisResult.Success::class.java)
        assertThat((r as LlmAnalysisResult.Success).analysis.overview).isEqualTo("old")
        assertThat(r.fromCache).isTrue()
        assertThat(r.notice).contains("刷新失败")
    }

    @Test
    fun `plain failure with no cache maps to network error`() = runTest {
        val api = api { _, _, _ -> throw java.io.IOException("timeout") }
        val r = repo(LlmConfig("https://x/", "k", "m"), api).analyze(input)
        assertThat((r as LlmAnalysisResult.Error).message).contains("网络")
        coVerify(exactly = 0) { cacheStore.putPortfolio(any(), any(), any()) }
    }

    @Test
    fun `http 401 maps to API key error`() = runTest {
        val api = api { _, _, _ -> throw httpErr(401) }
        val r = repo(LlmConfig("https://x/", "k", "m"), api).analyze(input)
        assertThat((r as LlmAnalysisResult.Error).message).isEqualTo("API key 无效")
    }

    @Test
    fun `not configured still returns fresh cache`() = runTest {
        coEvery { cacheStore.getPortfolio(any()) } returns PortfolioCacheEntry(
            LlmAnalysis("cached", emptyMap(), emptyList()), System.currentTimeMillis()
        )
        val r = repo(LlmConfig("", "", ""), api { _, _, _ -> resp(""""x"""") }).analyze(input)
        assertThat(r).isInstanceOf(LlmAnalysisResult.Success::class.java)
        assertThat((r as LlmAnalysisResult.Success).fromCache).isTrue()
    }

    // ===== analyzeStock =====

    @Test
    fun `analyzeStock returns Success and writes stock cache`() = runTest {
        val api = api { _, _, _ -> resp("""{"valuation":"偏低","dividendSustainability":"稳","action":"可关注","risks":[]}""") }
        val r = repo(LlmConfig("https://x/", "k", "m"), api).analyzeStock(stockInput)
        assertThat(r).isInstanceOf(StockLlmAnalysisResult.Success::class.java)
        assertThat((r as StockLlmAnalysisResult.Success).analysis.valuation).isEqualTo("偏低")
        coVerify { cacheStore.putStock(any(), any(), any()) }
    }

    @Test
    fun `analyzeStock cache hit returns without calling api`() = runTest {
        coEvery { cacheStore.getStock(any()) } returns StockCacheEntry(
            StockLlmAnalysis("cached", "", "", emptyList()), System.currentTimeMillis()
        )
        var calls = 0
        val api = api { _, _, _ -> calls++; resp(""""x"""") }
        val r = repo(LlmConfig("https://x/", "k", "m"), api).analyzeStock(stockInput)
        assertThat(r).isInstanceOf(StockLlmAnalysisResult.Success::class.java)
        assertThat((r as StockLlmAnalysisResult.Success).analysis.valuation).isEqualTo("cached")
        assertThat(r.fromCache).isTrue()
        assertThat(calls).isEqualTo(0)
    }

    @Test
    fun `analyzeStock returns NotConfigured when key missing`() = runTest {
        val r = repo(LlmConfig("https://x/", "", "m"), api { _, _, _ -> resp(""""x"""") })
            .analyzeStock(stockInput)
        assertThat(r).isInstanceOf(StockLlmAnalysisResult.NotConfigured::class.java)
    }

    private fun resp(content: String) = LlmChatResponse(
        listOf(LlmChatResponse.Choice(LlmMessage("assistant", content)))
    )

    private fun httpErr(code: Int) = HttpException(
        Response.error<Any>(code, "".toResponseBody("application/json".toMediaTypeOrNull()))
    )
}
```

- [ ] **Step 2: 跑测试验证失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.stock.dividend.data.repository.LlmAnalysisRepositoryTest"`
Expected: FAIL（编译错误：`analyze` 旧签名、构造参数少 `cacheStore`、`analyzeStock` 不存在）。

- [ ] **Step 3: 实现 Repository**

`app/src/main/java/com/stock/dividend/data/repository/LlmAnalysisRepository.kt` 整体替换为：

```kotlin
package com.stock.dividend.data.repository

import com.stock.dividend.data.remote.LlmApi
import com.stock.dividend.data.remote.dto.LlmChatRequest
import com.stock.dividend.data.remote.dto.LlmMessage
import kotlinx.coroutines.flow.first
import retrofit2.HttpException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 编排 LLM 解读：读配置 → 构造 prompt → 查缓存（prompt 哈希 key，24h TTL）→ 调用 → 解析 → 写缓存。
 * 组合级 [analyze] 与个股级 [analyzeStock] 共享同一缓存流程。
 */
@Singleton
class LlmAnalysisRepository @Inject constructor(
    private val llmApi: LlmApi,
    private val configSource: LlmConfigSource,
    private val cacheStore: LlmAnalysisCacheStore,
) {
    suspend fun analyze(
        input: PortfolioLlmInput,
        forceRefresh: Boolean = false,
    ): LlmAnalysisResult {
        if (input.evaluation.isEmpty()) return LlmAnalysisResult.NotConfigured
        val prompt = LlmPromptBuilder.build(input)
        val key = LlmCacheKey.of(prompt.system, prompt.user)

        val cached = if (!forceRefresh) cacheStore.getPortfolio(key) else null
        if (cached != null && isFresh(cached.createdAt)) {
            return LlmAnalysisResult.Success(cached.analysis, cached.createdAt, fromCache = true)
        }

        val config = configSource.observeConfig().first()
        if (!config.isComplete) return LlmAnalysisResult.NotConfigured

        return try {
            val content = call(config, prompt)
            val analysis = LlmAnalysisParser.parse(content)
            cacheStore.putPortfolio(key, analysis, System.currentTimeMillis())
            LlmAnalysisResult.Success(analysis)
        } catch (e: HttpException) {
            fallbackOrError(cached, forceRefresh, mapHttpError(e.code()))
        } catch (_: Exception) {
            fallbackOrError(cached, forceRefresh, "网络错误，请重试")
        }
    }

    suspend fun analyzeStock(
        input: StockLlmInput,
        userStrategies: List<UserStrategyRef> = emptyList(),
        forceRefresh: Boolean = false,
    ): StockLlmAnalysisResult {
        val prompt = StockLlmPromptBuilder.build(input, userStrategies)
        val key = LlmCacheKey.of(prompt.system, prompt.user)

        val cached = if (!forceRefresh) cacheStore.getStock(key) else null
        if (cached != null && isFresh(cached.createdAt)) {
            return StockLlmAnalysisResult.Success(cached.analysis, cached.createdAt, fromCache = true)
        }

        val config = configSource.observeConfig().first()
        if (!config.isComplete) return StockLlmAnalysisResult.NotConfigured

        return try {
            val content = callStock(config, prompt)
            val analysis = StockLlmAnalysisParser.parse(content)
            cacheStore.putStock(key, analysis, System.currentTimeMillis())
            StockLlmAnalysisResult.Success(analysis)
        } catch (e: HttpException) {
            if (forceRefresh && cached != null) {
                StockLlmAnalysisResult.Success(cached.analysis, cached.createdAt, fromCache = true, notice = REFRESH_FALLBACK_NOTICE)
            } else {
                StockLlmAnalysisResult.Error(mapHttpError(e.code()))
            }
        } catch (_: Exception) {
            if (forceRefresh && cached != null) {
                StockLlmAnalysisResult.Success(cached.analysis, cached.createdAt, fromCache = true, notice = REFRESH_FALLBACK_NOTICE)
            } else {
                StockLlmAnalysisResult.Error("网络错误，请重试")
            }
        }
    }

    private suspend fun call(config: LlmConfig, prompt: LlmPromptBuilder.LlmPrompt): String {
        val url = config.baseUrl.trimEnd('/') + "/chat/completions"
        val request = LlmChatRequest(
            model = config.model,
            messages = listOf(
                LlmMessage("system", prompt.system),
                LlmMessage("user", prompt.user),
            ),
        )
        return llmApi.chatCompletions(url, "Bearer ${config.apiKey}", request).content
            ?: throw IllegalStateException("LLM 返回为空")
    }

    private suspend fun callStock(config: LlmConfig, prompt: StockLlmPromptBuilder.LlmPrompt): String {
        val url = config.baseUrl.trimEnd('/') + "/chat/completions"
        val request = LlmChatRequest(
            model = config.model,
            messages = listOf(
                LlmMessage("system", prompt.system),
                LlmMessage("user", prompt.user),
            ),
        )
        return llmApi.chatCompletions(url, "Bearer ${config.apiKey}", request).content
            ?: throw IllegalStateException("LLM 返回为空")
    }

    /** 组合级失败回退：仅 forceRefresh 时回退旧缓存（带提示），否则原样报错。 */
    private fun fallbackOrError(
        cached: PortfolioCacheEntry?,
        forceRefresh: Boolean,
        errorMessage: String,
    ): LlmAnalysisResult = if (forceRefresh && cached != null) {
        LlmAnalysisResult.Success(cached.analysis, cached.createdAt, fromCache = true, notice = REFRESH_FALLBACK_NOTICE)
    } else {
        LlmAnalysisResult.Error(errorMessage)
    }

    private fun isFresh(createdAt: Long): Boolean =
        System.currentTimeMillis() - createdAt < CACHE_TTL_MS

    private fun mapHttpError(code: Int): String = when (code) {
        401, 403 -> "API key 无效"
        429 -> "请求过频，稍后重试"
        else -> "分析失败，请重试"
    }

    companion object {
        const val CACHE_TTL_MS = 24L * 60 * 60 * 1000
        private const val REFRESH_FALLBACK_NOTICE = "刷新失败，显示上次分析结果"
    }
}
```

- [ ] **Step 4: 跑测试验证通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.stock.dividend.data.repository.LlmAnalysisRepositoryTest"`
Expected: PASS（12 个用例全绿）。

- [ ] **Step 5: 编译全量（此时旧调用点会报错，下一步修复）**

Run: `./gradlew :app:compileDebugKotlin`
Expected: FAIL（`PortfolioViewModel.analyzeWithLlm` 仍按旧签名调用 `analyze`）。此失败已知，Task 9 修复；如想保持每步绿，可先继续 Task 9 再回到本步验证。

- [ ] **Step 6: 提交（可选，需用户同意）**

```bash
git add app/src/main/java/com/stock/dividend/data/repository/LlmAnalysisRepository.kt app/src/test/java/com/stock/dividend/data/repository/LlmAnalysisRepositoryTest.kt && git commit -m "feat(llm): LLM 结果缓存编排 + analyzeStock"
```

---

## Task 8: StockForecast 增加 1/3/5 年预测

**Files:**
- Modify: `app/src/main/java/com/stock/dividend/viewmodel/PortfolioViewModel.kt`（StockForecast 字段 + forecastMapFlow）
- Modify: `app/src/test/java/com/stock/dividend/viewmodel/PortfolioViewModelTest.kt`（新增用例）

- [ ] **Step 1: 写失败测试**

在 `PortfolioViewModelTest.kt` 末尾（`private fun dividend` 之前）追加：

```kotlin
    @Test
    fun `stockForecasts include 1-3-5 year llm forecast`() = runTest {
        val dividends = listOf(
            DividendEntity(id = "sh.600036_2022", stockCode = "sh.600036", reportDate = "2022-12-31", cashPerShare = 0.2),
            DividendEntity(id = "sh.600036_2023", stockCode = "sh.600036", reportDate = "2023-12-31", cashPerShare = 0.3),
            DividendEntity(id = "sh.600036_2024", stockCode = "sh.600036", reportDate = "2024-12-31", cashPerShare = 0.4)
        )
        every { dividendDao.observeByStock("sh.600036") } returns MutableStateFlow(dividends)
        stocksFlow.value = listOf(stock("sh.600036", shares = 100, costPerShare = 10.0))

        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        val llm = viewModel.uiState.value.stockForecasts["sh.600036"]?.llmForecast
        assertThat(llm).isNotNull()
        assertThat(llm!!.avgCashPerShare1Y).isEqualTo(0.4)
        assertThat(llm.avgCashPerShare3Y).isWithin(1e-9).of(0.3)
        assertThat(llm.avgCashPerShare5Y).isWithin(1e-9).of(0.3)  // 样本不足回退基准值
        assertThat(llm.actualYears).isEqualTo(3)
    }
```

- [ ] **Step 2: 跑测试验证失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.stock.dividend.viewmodel.PortfolioViewModelTest"`
Expected: FAIL（`llmForecast` 字段不存在，编译错误）。

- [ ] **Step 3: 修改 StockForecast 与 forecastMapFlow**

`PortfolioViewModel.kt` 中 `StockForecast` 数据类追加字段（`latestYearlyDividend` 之后）：

```kotlin
    /** 1/3/5 年每股预测（组合级 LLM 深度数据用）；无足够股息数据时为 null。 */
    val llmForecast: StockLlmInput.StockLlmForecast? = null
```

import 区追加：

```kotlin
import com.stock.dividend.data.repository.StockLlmInput
```

`forecastMapFlow` 的 lambda（`dividendDao.observeByStock(stock.code).map { dividends ->` 内）整体替换为：

```kotlin
                dividendDao.observeByStock(stock.code).map { dividends ->
                    val years = stock.yieldPeriod.toIntOrNull() ?: 3
                    val result = ForecastCalculator.calculateForecastIncome(
                        dividends, stock.shares, years
                    )
                    // 1/3/5 年窗口（本地纯计算）；样本不足的窗口回退到首个可用值
                    val llmForecast = listOf(1, 3, 5).mapNotNull { y ->
                        ForecastCalculator.calculateForecastIncome(dividends, stock.shares, y)
                            ?.let { y to it.avgCashPerShare }
                    }.toMap().let { m ->
                        val base = m.values.firstOrNull() ?: return@let null
                        StockLlmInput.StockLlmForecast(
                            avgCashPerShare1Y = m[1] ?: base,
                            avgCashPerShare3Y = m[3] ?: base,
                            avgCashPerShare5Y = m[5] ?: base,
                            actualYears = result?.actualYears ?: 0
                        )
                    }
                    val forecast = result?.let {
                        StockForecast(
                            shares = stock.shares,
                            avgCashPerShare = it.avgCashPerShare,
                            // shares=0 的自选股 forecastIncome 恒为 0（shares * avg = 0），不计入合计
                            forecastIncome = stock.shares * it.avgCashPerShare,
                            actualYears = it.actualYears,
                            latestYearlyDividend = ForecastCalculator.latestYearlyCashPerShare(dividends),
                            llmForecast = llmForecast
                        )
                    } ?: StockForecast(
                        // 占位：result 为 null（shares<=0 或无足够股息记录）时仍要为自选卡保留槽位，
                        // 否则 currentPrice/latestYearlyDividend 无处挂载，自选股卡片现价永远为空。
                        shares = stock.shares,
                        avgCashPerShare = 0.0,
                        forecastIncome = 0.0,
                        actualYears = 0,
                        latestYearlyDividend = ForecastCalculator.latestYearlyCashPerShare(dividends),
                        llmForecast = llmForecast
                    )
                    stock.code to forecast
                }
```

- [ ] **Step 4: 跑测试验证通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.stock.dividend.viewmodel.PortfolioViewModelTest"`
Expected: PASS（含新增用例）。

- [ ] **Step 5: 提交（可选，需用户同意）**

```bash
git add app/src/main/java/com/stock/dividend/viewmodel/PortfolioViewModel.kt app/src/test/java/com/stock/dividend/viewmodel/PortfolioViewModelTest.kt && git commit -m "feat(vm): StockForecast 携带 1-3-5 年预测"
```

---

## Task 9: PortfolioViewModel 深度数据装配 + forceRefresh

**Files:**
- Modify: `app/src/main/java/com/stock/dividend/viewmodel/PortfolioViewModel.kt`
- Modify: `app/src/test/java/com/stock/dividend/viewmodel/PortfolioViewModelTest.kt`

- [ ] **Step 1: 写失败测试**

`PortfolioViewModelTest.kt` 顶部字段区追加：

```kotlin
    private val fundamentalsCacheRepository: FundamentalsCacheRepository = mockk {
        coEvery { getFundamentals(any(), any()) } returns null
    }
    private val bondYieldRepository: BondYieldRepository = mockk {
        coEvery { fetch10YBondYield(any()) } returns BondYieldRepository.DEFAULT_YIELD
    }
```

import 区追加：

```kotlin
import com.stock.dividend.data.repository.BondYieldRepository
import com.stock.dividend.data.repository.Fundamentals
import com.stock.dividend.data.repository.FundamentalsCacheRepository
import com.stock.dividend.data.repository.LlmAnalysisResult
import com.stock.dividend.data.repository.LlmAnalysisState
import com.stock.dividend.data.repository.PortfolioLlmInput
import com.stock.dividend.data.repository.StockLlmInput
```

`createViewModel()` 改为：

```kotlin
    private fun createViewModel() = PortfolioViewModel(
        stockRepository,
        dividendDao,
        livingExpenseRepository,
        transactionDao,
        notificationCheckCoordinator,
        notificationRuleRepository,
        llmAnalysisRepository,
        tradeStrategyRepository,
        fundamentalsCacheRepository,
        bondYieldRepository,
        context
    )
```

测试类内追加（放在 `private fun createViewModel` 之前）：

```kotlin
    private fun deepSetup() {
        stocksFlow.value = listOf(
            stock("sh.600036", shares = 100, costPerShare = 10.0, industry = "银行")
        )
        val dividends = listOf(
            DividendEntity(id = "sh.600036_2022", stockCode = "sh.600036", reportDate = "2022-12-31", cashPerShare = 0.2),
            DividendEntity(id = "sh.600036_2023", stockCode = "sh.600036", reportDate = "2023-12-31", cashPerShare = 0.3),
            DividendEntity(id = "sh.600036_2024", stockCode = "sh.600036", reportDate = "2024-12-31", cashPerShare = 0.4)
        )
        every { dividendDao.observeByStock("sh.600036") } returns MutableStateFlow(dividends)
        coEvery { stockRepository.fetchQuotes(any()) } returns mapOf("sh.600036" to 10.0)
        coEvery { stockRepository.fetchBoll(any()) } returns BollBand(10.0, 12.0, 8.0)
        coEvery { stockRepository.fetchBoll(any(), any()) } returns BollBand(10.0, 12.0, 8.0)
        coEvery { bondYieldRepository.fetch10YBondYield(any()) } returns 2.5
        coEvery { fundamentalsCacheRepository.getFundamentals("sh.600036", false) } returns Fundamentals(
            periods = listOf(Fundamentals.Period("2024-12-31", 12.0, 60.0, 8.0, 5.0, payoutRatio = 25.0))
        )
    }

    @Test
    fun `analyzeWithLlm assembles deep data and passes to repository`() = runTest {
        deepSetup()
        val inputSlot = slot<PortfolioLlmInput>()
        coEvery { llmAnalysisRepository.analyze(capture(inputSlot), any()) } returns LlmAnalysisResult.Success(
            LlmAnalysis("ok", emptyMap(), emptyList())
        )

        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.evaluateVisibleHoldings()
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.analyzeWithLlm()
        testDispatcher.scheduler.advanceUntilIdle()

        val input = inputSlot.captured
        assertThat(input.stockDetails["sh.600036"]?.fundamentals).isNotNull()
        assertThat(input.stockDetails["sh.600036"]?.forecast?.avgCashPerShare1Y).isEqualTo(0.4)
        assertThat(input.stockDetails["sh.600036"]?.buyThreshold?.reached).isEqualTo(false)
        coVerify { fundamentalsCacheRepository.getFundamentals("sh.600036", false) }
        coVerify { llmAnalysisRepository.analyze(input, false) }
    }

    @Test
    fun `analyzeWithLlm passes forceRefresh to fundamentals and repository`() = runTest {
        deepSetup()
        coEvery { llmAnalysisRepository.analyze(any(), any()) } returns LlmAnalysisResult.Success(
            LlmAnalysis("ok", emptyMap(), emptyList())
        )

        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.evaluateVisibleHoldings()
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.analyzeWithLlm(forceRefresh = true)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { fundamentalsCacheRepository.getFundamentals("sh.600036", true) }
        coVerify { llmAnalysisRepository.analyze(any(), true) }
    }

    @Test
    fun `analyzeWithLlm maps cached success metadata to state`() = runTest {
        deepSetup()
        coEvery { llmAnalysisRepository.analyze(any(), any()) } returns LlmAnalysisResult.Success(
            LlmAnalysis("cached", emptyMap(), emptyList()),
            analyzedAt = 123L,
            fromCache = true
        )

        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.evaluateVisibleHoldings()
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.analyzeWithLlm()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value.llmAnalysis as LlmAnalysisState.Success
        assertThat(state.analysis.overview).isEqualTo("cached")
        assertThat(state.fromCache).isTrue()
        assertThat(state.analyzedAt).isEqualTo(123L)
    }

    @Test
    fun `analyzeWithLlm degrades when fundamentals fail`() = runTest {
        deepSetup()
        coEvery { fundamentalsCacheRepository.getFundamentals(any(), any()) } throws RuntimeException("boom")
        val inputSlot = slot<PortfolioLlmInput>()
        coEvery { llmAnalysisRepository.analyze(capture(inputSlot), any()) } returns LlmAnalysisResult.Success(
            LlmAnalysis("ok", emptyMap(), emptyList())
        )

        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.evaluateVisibleHoldings()
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.analyzeWithLlm()
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(inputSlot.captured.stockDetails["sh.600036"]?.fundamentals).isNull()
        assertThat(viewModel.uiState.value.llmAnalysis).isInstanceOf(LlmAnalysisState.Success::class.java)
    }
```

补充 import（文件顶部）：

```kotlin
import io.mockk.slot
```

- [ ] **Step 2: 跑测试验证失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.stock.dividend.viewmodel.PortfolioViewModelTest"`
Expected: FAIL（编译错误：构造函数缺参数 / `analyzeWithLlm(forceRefresh)` 不存在）。

- [ ] **Step 3: 修改 ViewModel**

构造函数追加两个参数（`tradeStrategyRepository` 之后、`context` 之前）：

```kotlin
    private val fundamentalsCacheRepository: FundamentalsCacheRepository,
    private val bondYieldRepository: BondYieldRepository,
```

import 区追加：

```kotlin
import com.stock.dividend.data.repository.BondYieldRepository
import com.stock.dividend.data.repository.FundamentalsCacheRepository
import com.stock.dividend.data.repository.PortfolioLlmInput
import com.stock.dividend.data.repository.PortfolioLlmStockDetail
import com.stock.dividend.data.repository.StockLlmInput
import com.stock.dividend.data.repository.computeBuyThreshold
```

`analyzeWithLlm()` 整体替换为：

```kotlin
    /** 触发 LLM 解读（结果页"AI 解读"按钮；重新分析传 forceRefresh=true）。 */
    fun analyzeWithLlm(forceRefresh: Boolean = false) {
        val current = _uiState.value
        val evaluation = current.evaluation
        val signals = current.portfolioSignals
        if (evaluation.isNullOrEmpty() || signals == null) return  // 按钮已禁用，防御
        val dailyBands = current.dailyBands
        val monthlyBands = current.monthlyBands
        viewModelScope.launch {
            _uiState.update { it.copy(llmAnalysis = LlmAnalysisState.Loading) }
            // 回流全局用户投资原则（失败降级空，不阻塞分析，红线 #2）
            val userStrategies = runCatching {
                tradeStrategyRepository.activeStrategies().map { toUserStrategyRef(it) }
            }.getOrDefault(emptyList())
            // 每股深度数据：基本面（缓存优先）/ 预测（本地）/ 买入线（国债缓存 + 本地算）
            val stockDetails = buildStockDetails(evaluation, forceRefresh)
            val input = PortfolioLlmInput(
                evaluation = evaluation,
                dailyBands = dailyBands,
                monthlyBands = monthlyBands,
                signals = signals,
                thresholds = _evalThresholds.value,
                userStrategies = userStrategies,
                stockDetails = stockDetails
            )
            val result = llmAnalysisRepository.analyze(input, forceRefresh)
            val state = when (result) {
                is LlmAnalysisResult.Success -> LlmAnalysisState.Success(
                    result.analysis, result.analyzedAt, result.fromCache, result.notice
                )
                LlmAnalysisResult.NotConfigured -> LlmAnalysisState.NotConfigured
                is LlmAnalysisResult.Error -> LlmAnalysisState.Error(result.message)
            }
            _uiState.update { it.copy(llmAnalysis = state) }
        }
    }

    /** 每股深度数据装配：基本面走缓存仓库，预测取本地快照，买入线本地计算。全部失败降级 null。 */
    private suspend fun buildStockDetails(
        evaluation: List<EvaluatedStock>,
        forceRefresh: Boolean
    ): Map<String, PortfolioLlmStockDetail> {
        if (evaluation.isEmpty()) return emptyMap()
        val bondYield = runCatching { bondYieldRepository.fetch10YBondYield(forceRefresh) }
            .getOrDefault(BondYieldRepository.DEFAULT_YIELD)
        val semaphore = Semaphore(3)
        val forecasts = _uiState.value.stockForecasts
        val multipliers = lastAllStocksSnapshot.associate { it.code to it.buyThresholdMultiplier }
        return evaluation.map { stock ->
            async {
                semaphore.withPermit {
                    val fundamentals = runCatching {
                        fundamentalsCacheRepository.getFundamentals(stock.code, forceRefresh)
                    }.getOrNull()
                    val forecast = forecasts[stock.code]?.llmForecast
                    val multiplier = multipliers[stock.code] ?: StockEntity.DEFAULT_BUY_THRESHOLD_MULTIPLIER
                    val latestDps = forecasts[stock.code]?.latestYearlyDividend
                    val buyThreshold = computeBuyThreshold(
                        bondYield = bondYield,
                        multiplier = multiplier,
                        latestYearlyCashPerShare = latestDps,
                        currentPrice = stock.currentPrice
                    ).takeIf { it.targetYieldPercent > 0.0 }?.let {
                        StockLlmInput.StockLlmBuyThreshold(
                            targetYieldPercent = it.targetYieldPercent,
                            currentYieldPercent = it.currentYieldPercent,
                            reached = it.reached
                        )
                    }
                    stock.code to PortfolioLlmStockDetail(
                        fundamentals = fundamentals,
                        forecast = forecast,
                        buyThreshold = buyThreshold
                    )
                }
            }
        }.awaitAll().toMap()
    }
```

- [ ] **Step 4: 跑测试验证通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.stock.dividend.viewmodel.PortfolioViewModelTest"`
Expected: PASS（含 4 个新增用例）。

- [ ] **Step 5: 提交（可选，需用户同意）**

```bash
git add app/src/main/java/com/stock/dividend/viewmodel/PortfolioViewModel.kt app/src/test/java/com/stock/dividend/viewmodel/PortfolioViewModelTest.kt && git commit -m "feat(vm): 组合 AI 解读装配深度数据 + forceRefresh"
```

---

## Task 10: StockDetailViewModel 迁移（analyzeStock + 基本面缓存）

**Files:**
- Modify: `app/src/main/java/com/stock/dividend/viewmodel/StockDetailViewModel.kt`
- Modify: `app/src/test/java/com/stock/dividend/viewmodel/StockDetailViewModelTest.kt`

- [ ] **Step 1: 修改测试字段与 setUp（先改测试=先红）**

`StockDetailViewModelTest.kt` 字段区：

```kotlin
    private val llmApi: LlmApi = mockk()
    private val llmConfigSource: LlmConfigSource = mockk()
```

替换为：

```kotlin
    private val llmAnalysisRepository: LlmAnalysisRepository = mockk {
        coEvery { analyzeStock(any(), any(), any()) } returns StockLlmAnalysisResult.Success(
            StockLlmAnalysis("", "", "", emptyList())
        )
    }
    private val fundamentalsCacheRepository: FundamentalsCacheRepository = mockk {
        coEvery { getFundamentals(any(), any()) } returns null
    }
```

`setUp()` 内删除：

```kotlin
        // 默认未配置 LLM，现有用例不受影响
        every { llmConfigSource.observeConfig() } returns flowOf(LlmConfig("", "", ""))
        // 默认基本面拉取返回 null（runCatching 兜底，现有用例不依赖基本面）
        coEvery { stockRepository.fetchFundamentals(any()) } returns null
```

`createViewModel()` 与其余所有构造调用点统一替换参数：

```kotlin
            llmApi = llmApi,
            llmConfigSource = llmConfigSource,
```

→

```kotlin
            llmAnalysisRepository = llmAnalysisRepository,
            fundamentalsCacheRepository = fundamentalsCacheRepository,
```

（全文共 8 处 `llmApi = llmApi,` 块，逐一替换。）

import 区调整：

```kotlin
import com.stock.dividend.data.remote.LlmApi
import com.stock.dividend.data.remote.dto.LlmChatRequest
import com.stock.dividend.data.remote.dto.LlmChatResponse
import com.stock.dividend.data.remote.dto.LlmMessage
import com.stock.dividend.data.repository.LlmConfig
import com.stock.dividend.data.repository.LlmConfigSource
```

替换为：

```kotlin
import com.stock.dividend.data.repository.FundamentalsCacheRepository
import com.stock.dividend.data.repository.LlmAnalysisRepository
import com.stock.dividend.data.repository.StockLlmAnalysisResult
import com.stock.dividend.data.repository.StockLlmInput
```

删除不再使用的 `retrofit2.HttpException`、`retrofit2.Response`、`okhttp3.*` import（`httpException` helper 一并删除）。

- [ ] **Step 2: 替换 AI 解读测试区**

`// region 个股 AI 解读（analyzeWithLlm）` 到 `// endregion` 之间的全部测试替换为：

```kotlin
    // region 个股 AI 解读（analyzeWithLlm → LlmAnalysisRepository.analyzeStock）

    /** 与 [createViewModel] 类似，但同时填充 stockFlow，使 uiState.stock 非空（AI 解读的前置条件）。 */
    private fun createViewModelWithStock(
        dividends: List<DividendEntity> = makeDividends(2)
    ): StockDetailViewModel {
        stockFlow.value = StockEntity("sz.000001", "测试银行", "0", shares = 1000, industry = "银行")
        return createViewModel(dividends = dividends)
    }

    @Test
    fun `analyzeWithLlm returns NotConfigured when repository reports it`() = runTest {
        coEvery { llmAnalysisRepository.analyzeStock(any(), any(), any()) } returns StockLlmAnalysisResult.NotConfigured
        val viewModel = createViewModelWithStock()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.analyzeWithLlm()
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.llmAnalysis).isEqualTo(StockLlmAnalysisState.NotConfigured)
    }

    @Test
    fun `analyzeWithLlm maps success to Success state with cache metadata`() = runTest {
        coEvery { llmAnalysisRepository.analyzeStock(any(), any(), any()) } returns StockLlmAnalysisResult.Success(
            StockLlmAnalysis("偏低", "稳", "可关注", listOf("波动")),
            analyzedAt = 123L,
            fromCache = true
        )

        val viewModel = createViewModelWithStock()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.analyzeWithLlm()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value.llmAnalysis
        assertThat(state).isInstanceOf(StockLlmAnalysisState.Success::class.java)
        val success = state as StockLlmAnalysisState.Success
        assertThat(success.analysis.valuation).isEqualTo("偏低")
        assertThat(success.analysis.action).isEqualTo("可关注")
        assertThat(success.analysis.risks).containsExactly("波动")
        assertThat(success.analyzedAt).isEqualTo(123L)
        assertThat(success.fromCache).isTrue()
    }

    @Test
    fun `analyzeWithLlm fetches three-period boll before delegating`() = runTest {
        coEvery { stockRepository.fetchBoll("sz.000001", KlinePeriod.DAILY) } returns
            com.stock.dividend.data.repository.BollBand(middle = 10.0, upper = 11.0, lower = 9.0)
        coEvery { stockRepository.fetchBoll("sz.000001", KlinePeriod.WEEKLY) } returns
            com.stock.dividend.data.repository.BollBand(middle = 10.0, upper = 11.0, lower = 9.0)
        coEvery { stockRepository.fetchBoll("sz.000001", KlinePeriod.MONTHLY) } returns
            com.stock.dividend.data.repository.BollBand(middle = 12.0, upper = 14.0, lower = 10.0)

        val viewModel = createViewModelWithStock()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.analyzeWithLlm()
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.llmAnalysis).isInstanceOf(StockLlmAnalysisState.Success::class.java)
        coVerify { stockRepository.fetchBoll("sz.000001", KlinePeriod.DAILY) }
        coVerify { stockRepository.fetchBoll("sz.000001", KlinePeriod.WEEKLY) }
        coVerify { stockRepository.fetchBoll("sz.000001", KlinePeriod.MONTHLY) }
        coVerify { llmAnalysisRepository.analyzeStock(any(), any(), false) }
    }

    @Test
    fun `analyzeWithLlm maps repository error to Error state`() = runTest {
        coEvery { llmAnalysisRepository.analyzeStock(any(), any(), any()) } returns
            StockLlmAnalysisResult.Error("API key 无效")

        val viewModel = createViewModelWithStock()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.analyzeWithLlm()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value.llmAnalysis
        assertThat(state).isInstanceOf(StockLlmAnalysisState.Error::class.java)
        assertThat((state as StockLlmAnalysisState.Error).message).isEqualTo("API key 无效")
    }

    @Test
    fun `clearLlmAnalysis resets state to Idle`() = runTest {
        val viewModel = createViewModelWithStock()
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.analyzeWithLlm()
        testDispatcher.scheduler.advanceUntilIdle()
        assertThat(viewModel.uiState.value.llmAnalysis).isInstanceOf(StockLlmAnalysisState.Success::class.java)

        viewModel.clearLlmAnalysis()

        assertThat(viewModel.uiState.value.llmAnalysis).isEqualTo(StockLlmAnalysisState.Idle)
    }

    @Test
    fun `analyzeWithLlm early returns without delegating when no dividends`() = runTest {
        val viewModel = createViewModelWithStock(dividends = emptyList())
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.analyzeWithLlm()
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.llmAnalysis).isEqualTo(StockLlmAnalysisState.Idle)
        coVerify(exactly = 0) { llmAnalysisRepository.analyzeStock(any(), any(), any()) }
    }

    @Test
    fun `analyzeWithLlm degrades missing boll periods without blocking`() = runTest {
        // 三周期全失败（setUp 默认 fetchBoll 返回 null）；repository 默认返回 Success
        val viewModel = createViewModelWithStock()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.analyzeWithLlm()
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.llmAnalysis).isInstanceOf(StockLlmAnalysisState.Success::class.java)
        assertThat(
            (viewModel.uiState.value.llmAnalysis as StockLlmAnalysisState.Success).analysis.valuation
        ).isEqualTo("")
    }

    @Test
    fun `analyzeWithLlm passes forceRefresh to repository`() = runTest {
        val viewModel = createViewModelWithStock()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.analyzeWithLlm(forceRefresh = true)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { llmAnalysisRepository.analyzeStock(any(), any(), true) }
    }

    // endregion
```

- [ ] **Step 3: 替换基本面测试区（改走缓存仓库）**

`// region 基本面加载与派息率补全` 到 `// endregion` 之间的全部测试替换为：

```kotlin
    // region 基本面加载与派息率补全（经 FundamentalsCacheRepository）

    @Test
    fun `fundamentals load and payout ratio enriched from dividends`() = runTest {
        // 原始基本面（payoutRatio=null，basicEps=1.20）——经缓存仓库返回
        coEvery { fundamentalsCacheRepository.getFundamentals("sz.000001", false) } returns Fundamentals(
            periods = listOf(
                Fundamentals.Period("2024-12-31", 10.0, 60.0, 8.0, 5.0, basicEps = 1.20, payoutRatio = null)
            )
        )
        // 对应报告期的每股派息 0.30 → 派息率 0.30/1.20*100 = 25
        val dividends = listOf(
            DividendEntity(
                id = "sz.000001_2024-12-31", stockCode = "sz.000001",
                reportDate = "2024-12-31", cashPerShare = 0.30, dividendYield = 5.0
            )
        )

        val viewModel = createViewModelWithStock(dividends = dividends)
        testDispatcher.scheduler.advanceUntilIdle()

        val fundamentals = viewModel.uiState.value.fundamentals
        assertThat(fundamentals).isNotNull()
        assertThat(fundamentals!!.periods).hasSize(1)
        assertThat(fundamentals.periods[0].payoutRatio).isEqualTo(25.0)
        assertThat(viewModel.uiState.value.fundamentalsLoading).isFalse()
    }

    @Test
    fun `fundamentals degrade to null when cache repository throws and loading flag resets`() = runTest {
        coEvery { fundamentalsCacheRepository.getFundamentals(any(), any()) } throws RuntimeException("network")

        val viewModel = createViewModelWithStock()
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.fundamentals).isNull()
        // 红线 #3：失败也要复位 loading
        assertThat(viewModel.uiState.value.fundamentalsLoading).isFalse()
    }

    @Test
    fun `refreshFundamentals forces refresh through cache repository`() = runTest {
        coEvery { fundamentalsCacheRepository.getFundamentals("sz.000001", false) } returns Fundamentals(
            periods = listOf(Fundamentals.Period("2024-12-31", 12.0, 60.0, 8.0, 5.0, basicEps = 1.0, payoutRatio = null))
        )
        coEvery { fundamentalsCacheRepository.getFundamentals("sz.000001", true) } returns Fundamentals(
            periods = listOf(Fundamentals.Period("2025-03-31", 11.0, 61.0, 6.0, 4.0, basicEps = 1.0, payoutRatio = null))
        )

        val viewModel = createViewModelWithStock()
        testDispatcher.scheduler.advanceUntilIdle()
        val before = viewModel.uiState.value.fundamentals

        viewModel.refreshFundamentals()
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.fundamentals).isNotEqualTo(before)
        assertThat(viewModel.uiState.value.fundamentals!!.periods[0].reportDate).isEqualTo("2025-03-31")
        coVerify { fundamentalsCacheRepository.getFundamentals("sz.000001", true) }
    }

    // endregion
```

- [ ] **Step 4: 替换「当年累计股息率」测试（改为断言输入快照）**

原 `latest dividend yield sums multiple dividends in the same year for prompt` 测试整体替换为：

```kotlin
    @Test
    fun `latest dividend yield sums multiple dividends in the same year for prompt`() = runTest {
        val inputSlot = slot<StockLlmInput>()
        coEvery { llmAnalysisRepository.analyzeStock(capture(inputSlot), any(), any()) } returns
            StockLlmAnalysisResult.Success(StockLlmAnalysis("ok", "", "", emptyList()))

        // 同一年（2024）两笔分红：2.0% + 3.0% = 5.0%（累计股息率）
        val dividends = listOf(
            DividendEntity(
                id = "sz.000001_2024-06-30", stockCode = "sz.000001",
                reportDate = "2024-06-30", cashPerShare = 0.10, dividendYield = 2.0
            ),
            DividendEntity(
                id = "sz.000001_2024-12-31", stockCode = "sz.000001",
                reportDate = "2024-12-31", cashPerShare = 0.15, dividendYield = 3.0
            )
        )

        val viewModel = createViewModelWithStock(dividends = dividends)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.analyzeWithLlm()
        testDispatcher.scheduler.advanceUntilIdle()

        // 喂给仓库的输入快照应为当年累计 5.0%，而非单笔 2.0% 或 3.0%
        assertThat(inputSlot.captured.latestDividendYield).isEqualTo(5.0)
    }
```

补充 import：`import io.mockk.slot`。

- [ ] **Step 5: 修改 ViewModel 主代码**

构造函数参数：

```kotlin
    private val llmApi: LlmApi,
    private val llmConfigSource: LlmConfigSource,
```

替换为：

```kotlin
    private val llmAnalysisRepository: LlmAnalysisRepository,
    private val fundamentalsCacheRepository: FundamentalsCacheRepository,
```

import 区删除：

```kotlin
import com.stock.dividend.data.remote.LlmApi
import com.stock.dividend.data.repository.LlmConfig
import com.stock.dividend.data.repository.LlmConfigSource
import com.stock.dividend.data.remote.dto.LlmChatRequest
import com.stock.dividend.data.remote.dto.LlmMessage
import com.stock.dividend.data.repository.StockLlmAnalysisParser
import com.stock.dividend.data.repository.StockLlmPromptBuilder
import retrofit2.HttpException
import kotlinx.coroutines.flow.first
```

追加：

```kotlin
import com.stock.dividend.data.repository.FundamentalsCacheRepository
import com.stock.dividend.data.repository.LlmAnalysisRepository
import com.stock.dividend.data.repository.StockLlmAnalysisResult
```

`loadFundamentals()` 内 `stockRepository.fetchFundamentals(stockCode)` 替换为 `fundamentalsCacheRepository.getFundamentals(stockCode)`。

`refreshFundamentals()` 内 `loadFundamentals()` 替换为：

```kotlin
    fun refreshFundamentals() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(fundamentalsLoading = true)
            val result = runCatching { fundamentalsCacheRepository.getFundamentals(stockCode, forceRefresh = true) }
                .getOrNull()
            rawFundamentals = result
            recomputeFundamentals()
            _uiState.value = _uiState.value.copy(fundamentalsLoading = false)
        }
    }
```

`analyzeWithLlm()` 整体替换为：

```kotlin
    /**
     * 触发个股 AI 解读：并发拉日/周/月 BOLL + 现价，组装 [StockLlmInput]，
     * 委托 [LlmAnalysisRepository.analyzeStock]（内部处理配置/缓存/错误）。
     * 失败的周期/现价降级为 null（"—"），不阻塞分析。重新分析传 forceRefresh=true。
     */
    fun analyzeWithLlm(forceRefresh: Boolean = false) {
        val state = _uiState.value
        val stock = state.stock ?: return
        if (state.dividends.isEmpty()) return
        if (_uiState.value.llmAnalysis is StockLlmAnalysisState.Loading) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(llmAnalysis = StockLlmAnalysisState.Loading)

            // 并发拉三周期 BOLL（单股仅 3 请求，无需 Semaphore 限流）；失败降级 null
            val (dailyBand, weeklyBand, monthlyBand) = listOf(
                async { runCatching { stockRepository.fetchBoll(stockCode, KlinePeriod.DAILY) }.getOrNull() },
                async { runCatching { stockRepository.fetchBoll(stockCode, KlinePeriod.WEEKLY) }.getOrNull() },
                async { runCatching { stockRepository.fetchBoll(stockCode, KlinePeriod.MONTHLY) }.getOrNull() }
            ).awaitAll().let { Triple(it[0] as BollBand?, it[1] as BollBand?, it[2] as BollBand?) }

            // 现价：现拉一次（buyThreshold 的字段无法可靠反推现价）；失败降级 null
            val currentPrice = runCatching {
                stockRepository.fetchQuotes(listOf(stock))[stock.code]
            }.getOrNull()

            val input = buildStockLlmInput(stock, state, currentPrice, dailyBand, weeklyBand, monthlyBand)
            // 回流全局用户投资原则（失败降级空，不阻塞分析，红线 #2）
            val userStrategies = runCatching {
                tradeStrategyRepository.activeStrategies().map { toUserStrategyRef(it) }
            }.getOrDefault(emptyList())

            val result = llmAnalysisRepository.analyzeStock(input, userStrategies, forceRefresh)
            val uiState = when (result) {
                is StockLlmAnalysisResult.Success -> StockLlmAnalysisState.Success(
                    result.analysis, result.analyzedAt, result.fromCache, result.notice
                )
                StockLlmAnalysisResult.NotConfigured -> StockLlmAnalysisState.NotConfigured
                is StockLlmAnalysisResult.Error -> StockLlmAnalysisState.Error(result.message)
            }
            _uiState.value = _uiState.value.copy(llmAnalysis = uiState)
        }
    }
```

删除不再使用的 `mapLlmHttpError(...)` 私有函数。

- [ ] **Step 6: 跑测试验证通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.stock.dividend.viewmodel.StockDetailViewModelTest"`
Expected: PASS（全部用例，含重写后的 AI/基本面区）。

- [ ] **Step 7: 全量编译**

Run: `./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 8: 提交（可选，需用户同意）**

```bash
git add app/src/main/java/com/stock/dividend/viewmodel/StockDetailViewModel.kt app/src/test/java/com/stock/dividend/viewmodel/StockDetailViewModelTest.kt && git commit -m "feat(vm): 个股 AI 解读迁 analyzeStock + 基本面走缓存"
```

---

## Task 11: 组合评估页 UI（brief + risks + 时间/缓存/重新分析）

**Files:**
- Modify: `app/src/main/java/com/stock/dividend/ui/screen/PortfolioEvaluationScreen.kt`

- [ ] **Step 1: EvaluationCard 渲染 brief + risks**

`EvaluationCard` 尾部（Step 5 的临时实现）替换为：

```kotlin
            if (aiComment != null && (aiComment.brief.isNotBlank() || aiComment.risks.isNotEmpty())) {
                HorizontalDivider()
                Text(
                    aiComment.brief,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                aiComment.risks.forEach { risk ->
                    Row(verticalAlignment = Alignment.Top) {
                        Text(
                            "•",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.padding(end = 4.dp))
                        Text(
                            risk,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
```

> 若 `HorizontalDivider` 不可用（M3 版本），改用 `Spacer(Modifier.height(8.dp))` + 顶部细线可省略；保持编译通过即可。

- [ ] **Step 2: LlmAnalysisSection 加时间/缓存/notice/重新分析**

签名改为：

```kotlin
private fun LlmAnalysisSection(
    state: LlmAnalysisState,
    onAnalyze: () -> Unit,
    onRetry: () -> Unit,
    onReanalyze: () -> Unit
)
```

Success 分支在 `Text(a.overview, ...)` 之前插入头部行：

```kotlin
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    buildString {
                        append("✨ AI 解读")
                        state.analyzedAt?.let {
                            append(" · ")
                            append(formatAnalysisTime(it))
                        }
                        if (state.fromCache) append(" · 缓存")
                    },
                    style = MaterialTheme.typography.titleSmall
                )
                TextButton(onClick = onReanalyze) { Text("重新分析") }
            }
```

原 `Text("✨ AI 解读", style = ...)` 行删除。`notice` 非空时在 overview 之后追加：

```kotlin
                    state.notice?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
```

文件内新增时间格式化纯函数（放文件末尾）：

```kotlin
private fun formatAnalysisTime(epochMillis: Long): String =
    DateTimeFormatter.ofPattern("MM-dd HH:mm")
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(epochMillis))
```

import 追加：

```kotlin
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
```

- [ ] **Step 3: 调用链补参数**

`EvaluationContent` 签名追加 `onReanalyze: () -> Unit`，`LlmAnalysisSection(...)` 调用处改为：

```kotlin
            LlmAnalysisSection(
                state = llmAnalysis,
                onAnalyze = onAnalyze,
                onRetry = onAnalyze,
                onReanalyze = onReanalyze
            )
```

顶部调用 `EvaluationContent(...)` 处追加：

```kotlin
                onReanalyze = { viewModel.analyzeWithLlm(forceRefresh = true) }
```

Loading 文案 `"AI 分析中…"` 改为 `"正在拉取深度数据并分析…"`。

- [ ] **Step 4: 编译验证**

Run: `./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: 提交（可选，需用户同意）**

```bash
git add app/src/main/java/com/stock/dividend/ui/screen/PortfolioEvaluationScreen.kt && git commit -m "feat(ui): 评估结果页每股 brief+risks + 时间/缓存/重新分析"
```

---

## Task 12: 个股详情页 UI（时间/缓存/重新分析）

**Files:**
- Modify: `app/src/main/java/com/stock/dividend/ui/screen/StockDetailScreen.kt`

- [ ] **Step 1: StockLlmAnalysisSection 签名与 Success 分支**

签名改为：

```kotlin
private fun StockLlmAnalysisSection(
    state: StockLlmAnalysisState,
    hasDividends: Boolean,
    onAnalyze: () -> Unit,
    onRetry: () -> Unit,
    onReanalyze: () -> Unit
)
```

Success 分支头部 `Text("✨ AI 解读", ...)` 替换为：

```kotlin
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            buildString {
                                append("✨ AI 解读")
                                state.analyzedAt?.let {
                                    append(" · ")
                                    append(formatAnalysisTime(it))
                                }
                                if (state.fromCache) append(" · 缓存")
                            },
                            style = MaterialTheme.typography.titleSmall
                        )
                        TextButton(onClick = onReanalyze) { Text("重新分析") }
                    }
```

`notice` 非空时在 `a.action` 之后追加：

```kotlin
                    state.notice?.let {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
```

文件末尾新增：

```kotlin
private fun formatAnalysisTime(epochMillis: Long): String =
    DateTimeFormatter.ofPattern("MM-dd HH:mm")
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(epochMillis))
```

import 追加 `java.time.Instant` / `java.time.ZoneId` / `java.time.format.DateTimeFormatter`。

- [ ] **Step 2: 调用处补参数**

`StockLlmAnalysisSection(...)` 调用处（约 272 行）追加：

```kotlin
                        onReanalyze = { viewModel.analyzeWithLlm(forceRefresh = true) }
```

Loading 文案 `"AI 分析中…"` 改为 `"正在拉取深度数据并分析…"`。

- [ ] **Step 3: 编译验证**

Run: `./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: 提交（可选，需用户同意）**

```bash
git add app/src/main/java/com/stock/dividend/ui/screen/StockDetailScreen.kt && git commit -m "feat(ui): 个股 AI 区块加时间/缓存/重新分析"
```

---

## Task 13: 全量验证

**Files:**
- 无

- [ ] **Step 1: 全量单测**

Run: `./gradlew :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`（全部用例绿）。

- [ ] **Step 2: 全量构建**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: 自查红线**

- DB version=17 且 `MIGRATION_16_17` 已注册（`DatabaseModule.addMigrations` 含之）。
- 缓存表未进 `BackupData`。
- `isLoading`/`fundamentalsLoading` 成功失败均复位。
- 无成本价/敏感数据进 prompt（`buildStockDetails` 只用现价/股息/基本面/预测/国债）。
- 所有新 UI 文案中文。
- 纯函数（Builder/Parser/Key/Calculator）无 Android 依赖。

- [ ] **Step 4: 提交（可选，需用户同意）**

```bash
git add -A && git commit -m "feat(llm): 组合级 AI 解读增强（深度数据 + 双缓存）"
```

---

## Self-Review 记录

**Spec 覆盖：**
- §1 全量深度数据 → Task 4（prompt）+ Task 8/9（装配）+ Task 10（个股迁移）
- §2 基本面缓存 → Task 1（表）+ Task 5（仓库）+ Task 10（详情页接入）
- §3 LLM 结果缓存 → Task 1（表）+ Task 6（store）+ Task 7（编排）+ Task 11/12（UI 标记）
- §4 输出升级（brief+risks）→ Task 3（模型/parser）+ Task 11（UI）
- §5 迁移 v16→v17 → Task 1；缓存不入备份 → 未改 BackupData
- §6 错误处理 → Task 7（回退/notice）+ Task 5（基本面回退）
- §7 测试 → 各任务 TDD 步骤 + Task 13 全量

**占位符扫描：** 无 TBD/TODO；唯一柔性点是 Task 11 的 `HorizontalDivider` 兼容说明（有替代方案，不算占位）。

**类型一致性：**
- `PortfolioLlmInput`/`PortfolioLlmStockDetail` 定义于 Task 4，Task 7/9 使用一致。
- `StockLlmComment` 定义于 Task 3，Parser/UI 使用一致。
- `analyze(input, forceRefresh)` / `analyzeStock(input, userStrategies, forceRefresh)` 在 Task 7 定义，Task 9/10 调用一致。
- `FundamentalsCacheRepository.getFundamentals(code, forceRefresh)` Task 5 定义，Task 9/10 使用一致。
- `StockForecast.llmForecast` Task 8 定义，Task 9 使用一致。
- UI 回调 `onReanalyze` 在 Task 11/12 的签名与调用处一致。
