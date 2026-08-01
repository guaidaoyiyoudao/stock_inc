# 截图策略分析 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 用户从相册选截图 → 复用 OCR → LLM 提取半结构化买卖策略 → 两步 Review → 持久化；并把策略按 stockCode 回流进个股/组合 AI 分析 prompt。

**Architecture:** 新增 `trade_strategies` Room 表（DB v15→16 + `MIGRATION_15_16`）；OCR 复用 `TextRecognitionService` 拿纯文本，新 `ScreenshotStrategyRepository` 编排 LLM 提取（Prompt/Parser 均为纯函数）；新 `ScreenshotImportViewModel`（两步 Review：OCR 文本可编辑 → 策略字段全可编辑 + 自选股关联）；`TradeStrategyListViewModel` 列表页（设置页入口）；回流：`StockLlmInput`/`EvaluatedStock` 加 `userStrategies`，两个 PromptBuilder 渲染「用户既有策略」段（`sourceNote` 不入 prompt）。

**Tech Stack:** Kotlin 2.0.21 / Room 2.6.1 / Hilt / Compose M3 1.3.1 / Retrofit LLM / JUnit4 + MockK + Truth + Robolectric。

**对应 Spec:** `docs/superpowers/specs/2026-08-01-screenshot-strategy-design.md`

---

## 文件结构（映射 spec §10）

**新增（main）：**
- `data/local/entity/TradeStrategyEntity.kt` — Room 实体 + 方向/状态常量
- `data/local/dao/TradeStrategyDao.kt` — DAO
- `data/repository/ScreenshotStrategy.kt` — 提取结果模型 + `ScreenshotStrategyState` sealed
- `data/repository/ScreenshotStrategyPromptBuilder.kt` — 纯函数 prompt
- `data/repository/ScreenshotStrategyParser.kt` — 纯函数 parser
- `data/repository/ScreenshotStrategyRepository.kt` — LLM 编排
- `data/repository/TradeStrategyRepository.kt` — 持久化封装 + 回流查询 + risks JSON codec + matchTargetStock
- `data/repository/UserStrategyRef.kt` — 回流纯数据
- `viewmodel/ScreenshotImportViewModel.kt` — 导入页 VM + EditableStrategy + StockMatchResult + ImportPhase 扩展
- `viewmodel/TradeStrategyListViewModel.kt` — 列表页 VM
- `ui/screen/ScreenshotImportScreen.kt` — 导入页 UI（两步 Review）
- `ui/screen/TradeStrategyListScreen.kt` — 列表页 UI

**修改（main）：**
- `data/local/AppDatabase.kt` — entities/version/MIGRATION_15_16/dao()
- `di/DatabaseModule.kt` — 注册迁移 + DAO provider
- `data/local/backup/BackupData.kt` — 加 tradeStrategies 字段
- `data/repository/BackupRepository.kt` — 导出/导入加 trade_strategies
- `data/repository/StockLlmInput.kt` — 加 userStrategies
- `data/repository/StockLlmPromptBuilder.kt` — system 语义 + user 渲染
- `data/repository/EvaluatedStock.kt` — 加 userStrategies
- `data/repository/LlmPromptBuilder.kt` — 组合级每股附策略
- `viewmodel/StockDetailViewModel.kt` — analyzeWithLlm 注入并取策略
- `viewmodel/PortfolioViewModel.kt` — analyzeWithLlm 批量取策略
- `ui/navigation/AppNavigation.kt` — Routes + composable
- `ui/screen/MainScaffold.kt` — 设置页入口跳转
- `ui/screen/NotificationSettingsScreen.kt` — SettingsScreen 加策略库入口

**测试：** 与 main 包结构对齐，放 `app/src/test/java/com/stock/dividend/...`

---

## Task 1: 纯数据模型 TradeStrategyEntity + DAO

**Files:**
- Create: `app/src/main/java/com/stock/dividend/data/local/entity/TradeStrategyEntity.kt`
- Create: `app/src/main/java/com/stock/dividend/data/local/dao/TradeStrategyDao.kt`

- [ ] **Step 1: 创建实体**

```kotlin
// data/local/entity/TradeStrategyEntity.kt
package com.stock.dividend.data.local.entity

import androidx.compose.runtime.Stable
import androidx.room.Entity

const val STRATEGY_DIRECTION_BUY = "BUY"
const val STRATEGY_DIRECTION_SELL = "SELL"
const val STRATEGY_DIRECTION_WATCH = "WATCH"
const val STRATEGY_STATUS_ACTIVE = "ACTIVE"
const val STRATEGY_STATUS_ARCHIVED = "ARCHIVED"

@Stable
@Entity(tableName = "trade_strategies")
data class TradeStrategyEntity(
    val id: String,
    val stockCode: String?,
    val targetText: String,
    val direction: String,
    val reasoning: String,
    val risks: String,
    val validUntil: String?,
    val sourceNote: String?,
    val rawOcrText: String,
    val status: String = STRATEGY_STATUS_ACTIVE,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
```

- [ ] **Step 2: 创建 DAO**

```kotlin
// data/local/dao/TradeStrategyDao.kt
package com.stock.dividend.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.stock.dividend.data.local.entity.TradeStrategyEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TradeStrategyDao {
    @Query("SELECT * FROM trade_strategies ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<TradeStrategyEntity>>

    @Query("SELECT * FROM trade_strategies WHERE stockCode = :code AND status = 'ACTIVE' ORDER BY createdAt DESC")
    fun observeByStock(code: String): Flow<List<TradeStrategyEntity>>

    @Query(
        "SELECT * FROM trade_strategies WHERE stockCode = :code AND status = 'ACTIVE' " +
            "AND (validUntil IS NULL OR validUntil >= :today) ORDER BY createdAt DESC"
    )
    suspend fun activeStrategiesFor(code: String, today: String): List<TradeStrategyEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: TradeStrategyEntity)

    @Query("UPDATE trade_strategies SET status = :status, updatedAt = :now WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, now: Long = System.currentTimeMillis())

    @Query("DELETE FROM trade_strategies WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM trade_strategies")
    suspend fun getAllForBackup(): List<TradeStrategyEntity>

    @Query("DELETE FROM trade_strategies")
    suspend fun clear()

    @Insert
    suspend fun insertAll(items: List<TradeStrategyEntity>)
}
```

- [ ] **Step 3: 提交**

```bash
git add app/src/main/java/com/stock/dividend/data/local/entity/TradeStrategyEntity.kt \
        app/src/main/java/com/stock/dividend/data/local/dao/TradeStrategyDao.kt
git commit -m "feat(stock): trade_strategies 实体与 DAO"
```

---

## Task 2: 数据库迁移 v15→v16 + DI 注册

**Files:**
- Modify: `app/src/main/java/com/stock/dividend/data/local/AppDatabase.kt`
- Modify: `app/src/main/java/com/stock/dividend/di/DatabaseModule.kt`

- [ ] **Step 1: AppDatabase 加实体/版本/迁移/dao()**

在 `AppDatabase.kt`：
- import `TradeStrategyEntity` 与 `TradeStrategyDao`
- `entities` 列表末尾加 `TradeStrategyEntity::class`
- `version = 15` → `version = 16`
- `abstract class AppDatabase` 内加 `abstract fun tradeStrategyDao(): TradeStrategyDao`
- companion object 末尾（`MIGRATION_14_15` 之后）加：

```kotlin
val MIGRATION_15_16 = object : Migration(15, 16) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `trade_strategies` (" +
                    "`id` TEXT NOT NULL PRIMARY KEY, " +
                    "`stockCode` TEXT, " +
                    "`targetText` TEXT NOT NULL, " +
                    "`direction` TEXT NOT NULL, " +
                    "`reasoning` TEXT NOT NULL, " +
                    "`risks` TEXT NOT NULL, " +
                    "`validUntil` TEXT, " +
                    "`sourceNote` TEXT, " +
                    "`rawOcrText` TEXT NOT NULL, " +
                    "`status` TEXT NOT NULL DEFAULT 'ACTIVE', " +
                    "`createdAt` INTEGER NOT NULL, " +
                    "`updatedAt` INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_trade_strategies_stockCode` " +
                    "ON `trade_strategies`(`stockCode`)"
        )
    }
}
```

- [ ] **Step 2: DatabaseModule 注册迁移 + DAO provider**

在 `di/DatabaseModule.kt`：
- `addMigrations(...)` 调用末尾追加 `, AppDatabase.MIGRATION_15_16`
- 加 provider（仿现有 DAO provider 风格）：

```kotlin
@Provides
fun provideTradeStrategyDao(db: AppDatabase): TradeStrategyDao = db.tradeStrategyDao()
```
（补对应 import）

- [ ] **Step 3: 构建 APK 验证 Room 编译通过**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL（Room KSP 生成代码无错；schema 校验通过）

- [ ] **Step 4: 提交**

```bash
git add app/src/main/java/com/stock/dividend/data/local/AppDatabase.kt \
        app/src/main/java/com/stock/dividend/di/DatabaseModule.kt
git commit -m "feat(db): trade_strategies 迁移 v15→v16 + DAO 注册"
```

---

## Task 3: 纯函数 — risks JSON codec + matchTargetStock（TDD）

**Files:**
- Create: `app/src/main/java/com/stock/dividend/data/repository/TradeStrategyRepository.kt`（先只放纯函数）
- Test: `app/src/test/java/com/stock/dividend/data/repository/TradeStrategyRepositoryTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
// test/.../TradeStrategyRepositoryTest.kt
package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.local.entity.StockEntity
import com.stock.dividend.data.local.entity.TradeStrategyEntity
import org.junit.Test

class TradeStrategyRepositoryTest {

    @Test
    fun risks_roundTrip() {
        val s = risksToJsonString(listOf("息差收窄", "地产敞口"))
        assertThat(risksFromJson(s)).containsExactly("息差收窄", "地产敞口").inOrder()
    }

    @Test
    fun risks_emptyList_toEmptyArray() {
        assertThat(risksToJsonString(emptyList())).isEqualTo("[]")
        assertThat(risksFromJson("[]")).isEmpty()
    }

    @Test
    fun risks_malformed_returnsEmpty() {
        assertThat(risksFromJson("not json")).isEmpty()
        assertThat(risksFromJson("{")).isEmpty()
    }

    @Test
    fun risks_null_returnsEmpty() {
        assertThat(risksFromJson(null)).isEmpty()
    }

    @Test
    fun match_bySixDigitCode() {
        val stocks = listOf(
            StockEntity(code = "SH.600036", name = "招商银行", marketCode = "1"),
            StockEntity(code = "SZ.000001", name = "平安银行", marketCode = "1")
        )
        val r = matchTargetStock("600036", stocks)
        assertThat(r).isInstanceOf(StockMatchResult.Matched::class.java)
        assertThat((r as StockMatchResult.Matched).code).isEqualTo("SH.600036")
    }

    @Test
    fun match_byNameContains() {
        val stocks = listOf(StockEntity(code = "SH.600036", name = "招商银行", marketCode = "1"))
        val r = matchTargetStock("招商", stocks)
        assertThat((r as StockMatchResult.Matched).code).isEqualTo("SH.600036")
    }

    @Test
    fun match_none_returnsUnmatched() {
        val stocks = listOf(StockEntity(code = "SH.600036", name = "招商银行", marketCode = "1"))
        assertThat(matchTargetStock("999999", stocks)).isEqualTo(StockMatchResult.Unmatched)
    }

    @Test
    fun match_emptyTarget_returnsUnmatched() {
        val stocks = listOf(StockEntity(code = "SH.600036", name = "招商银行", marketCode = "1"))
        assertThat(matchTargetStock("", stocks)).isEqualTo(StockMatchResult.Unmatched)
    }

    @Test
    fun toUserStrategyRef_daysAgo() {
        val now = System.currentTimeMillis()
        val threeDaysAgo = now - 3L * 24 * 3600 * 1000
        val e = TradeStrategyEntity(
            id = "x", stockCode = "SH.600036", targetText = "招商银行",
            direction = "BUY", reasoning = "r", risks = "[]", validUntil = null,
            sourceNote = "研报", rawOcrText = "t", createdAt = threeDaysAgo
        )
        val ref = toUserStrategyRef(e, now)
        assertThat(ref.daysAgo).isEqualTo(3)
        assertThat(ref.direction).isEqualTo("BUY")
        assertThat(ref.sourceNoteIncluded).isFalse()  // sourceNote 不入 ref
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.stock.dividend.data.repository.TradeStrategyRepositoryTest"`
Expected: FAIL（符号未定义）

- [ ] **Step 3: 实现 `UserStrategyRef` + sealed `StockMatchResult` + 顶层纯函数**

```kotlin
// data/repository/UserStrategyRef.kt
package com.stock.dividend.data.repository

/** 回流到 LLM prompt 的用户策略引用（不含 sourceNote，来源不入 prompt）。 */
data class UserStrategyRef(
    val direction: String,       // BUY/SELL/WATCH
    val reasoning: String,
    val risks: List<String>,
    val validUntil: String?,
    val daysAgo: Int
)
```

```kotlin
// viewmodel/ScreenshotImportViewModel.kt（仅先放 sealed，VM 主体 Task 9 再补）
// —— 注意：StockMatchResult 是 UI/VM 层概念，放 viewmodel 包
```

为避免循环依赖，`matchTargetStock` 入参是 `List<StockEntity>`、返回值需 `StockMatchResult`。把 `StockMatchResult` 定义在 `data/repository` 层更合理（匹配逻辑是领域行为）。改放 `TradeStrategyRepository.kt` 同文件：

```kotlin
// data/repository/TradeStrategyRepository.kt（本 Task 仅放纯函数 + sealed）
package com.stock.dividend.data.repository

import com.stock.dividend.data.local.entity.StockEntity
import com.stock.dividend.data.local.entity.TradeStrategyEntity
import org.json.JSONArray
import org.json.JSONException

sealed interface StockMatchResult {
    data class Matched(val code: String, val name: String) : StockMatchResult
    data object Unmatched : StockMatchResult
}

/** risks List<String> → JSON 数组字符串。 */
fun risksToJsonString(risks: List<String>): String {
    val arr = JSONArray()
    risks.forEach { arr.put(it) }
    return arr.toString()
}

/** JSON 数组字符串 → List<String>；畸形/null 返回空。 */
fun risksFromJson(raw: String?): List<String> = runCatching {
    if (raw.isNullOrBlank()) return emptyList()
    val arr = JSONArray(raw)
    (0 until arr.length()).mapNotNull { runCatching { arr.getString(it) }.getOrNull() }
}.getOrDefault(emptyList())

private val sixDigitCodeRegex = Regex("(?<!\\d)\\d{6}(?!\\d)")

/**
 * 用 LLM 提取的标的文本在自选股里模糊匹配：先匹配 6 位代码，再按名称包含。
 */
fun matchTargetStock(targetText: String, stocks: List<StockEntity>): StockMatchResult {
    val t = targetText.trim()
    if (t.isEmpty()) return StockMatchResult.Unmatched
    sixDigitCodeRegex.find(t)?.value?.let { code ->
        stocks.firstOrNull { it.code.endsWith(".$code") || it.code.endsWith(code) }
            ?.let { return StockMatchResult.Matched(it.code, it.name) }
    }
    stocks.firstOrNull { it.name.contains(t) || t.contains(it.name) }
        ?.let { return StockMatchResult.Matched(it.code, it.name) }
    return StockMatchResult.Unmatched
}

/** 实体 → 回流引用（sourceNote 不传入，daysAgo 由 now 计算）。 */
fun toUserStrategyRef(entity: TradeStrategyEntity, now: Long = System.currentTimeMillis()): UserStrategyRef {
    val daysAgo = ((now - entity.createdAt) / (24L * 3600 * 1000)).toInt().coerceAtLeast(0)
    return UserStrategyRef(
        direction = entity.direction,
        reasoning = entity.reasoning,
        risks = risksFromJson(entity.risks),
        validUntil = entity.validUntil,
        daysAgo = daysAgo
    )
}
```

> 注：测试里 `ref.sourceNoteIncluded` 改为不存在 → 修测试断言为只校验 daysAgo/direction。修正测试第 9 个用例最后两行：

```kotlin
val ref = toUserStrategyRef(e, now)
assertThat(ref.daysAgo).isEqualTo(3)
assertThat(ref.direction).isEqualTo("BUY")
```

- [ ] **Step 4: 运行测试验证通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.stock.dividend.data.repository.TradeStrategyRepositoryTest"`
Expected: PASS（9 个用例全绿）

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/stock/dividend/data/repository/UserStrategyRef.kt \
        app/src/main/java/com/stock/dividend/data/repository/TradeStrategyRepository.kt \
        app/src/test/java/com/stock/dividend/data/repository/TradeStrategyRepositoryTest.kt
git commit -m "feat(stock): risks codec + matchTargetStock + toUserStrategyRef 纯函数与测试"
```

---

## Task 4: 纯函数 — ScreenshotStrategyPromptBuilder（TDD）

**Files:**
- Create: `app/src/main/java/com/stock/dividend/data/repository/ScreenshotStrategyPromptBuilder.kt`
- Test: `app/src/test/java/com/stock/dividend/data/repository/ScreenshotStrategyPromptBuilderTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ScreenshotStrategyPromptBuilderTest {

    @Test
    fun system_containsSchemaAndConstraints() {
        val p = ScreenshotStrategyPromptBuilder.build("xx")
        assertThat(p.system).contains("isActionable")
        assertThat(p.system).contains("BUY")
        assertThat(p.system).contains("SELL")
        assertThat(p.system).contains("WATCH")
        assertThat(p.system).contains("绝不编造数据、价格、财报")
        assertThat(p.system).contains("不给具体买卖价格")
    }

    @Test
    fun user_containsFullOcrText_untruncated() {
        val ocr = "招商银行基本面稳健\nROE持续>15%\n建议买入".repeat(50)
        val p = ScreenshotStrategyPromptBuilder.build(ocr)
        assertThat(p.user).contains(ocr)
    }

    @Test
    fun user_emptyOcr_stillLegal() {
        val p = ScreenshotStrategyPromptBuilder.build("")
        assertThat(p.user).contains("截图文本")
        assertThat(p.user).isNotEmpty()
    }
}
```

- [ ] **Step 2: 运行验证失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.stock.dividend.data.repository.ScreenshotStrategyPromptBuilderTest"`
Expected: FAIL（unresolved reference）

- [ ] **Step 3: 实现**

```kotlin
// data/repository/ScreenshotStrategyPromptBuilder.kt
package com.stock.dividend.data.repository

/**
 * 把 OCR 文本构造成 LLM prompt（纯函数）：system 定角色 + JSON schema + 约束；
 * user 直接粘贴 OCR 全文（不截断，研报信息密度高）。空文本仍产出合法 prompt。
 */
object ScreenshotStrategyPromptBuilder {

    data class LlmPrompt(val system: String, val user: String)

    fun build(ocrText: String): LlmPrompt = LlmPrompt(SYSTEM, buildUser(ocrText))

    private val SYSTEM = """
你是一位稳健的中文投资策略整理助手。
【任务】用户给出一截从财经内容（研报/新闻/股吧/聊天等）OCR 出的文本，请提取其中**可执行的买卖策略**。
【输出要求】严格输出 JSON：
{
  "isActionable": true/false,
  "targetText": "涉及的股票名称或代码（原文片段，不确定可合并写）",
  "direction": "BUY" | "SELL" | "WATCH",
  "reasoning": "核心理由≤200字，仅基于原文",
  "risks": ["具体风险点", "..."],
  "validUntil": "YYYY-MM-DD 或 null（无明确期限填 null）"
}
【判定规则】
- 若截图与股票/投资无关、或仅陈述事实无任何买卖倾向 → isActionable=false，其余字段填空/null。
- direction：买入倾向→BUY，卖出/看空→SELL，观望/持有/无明确方向→WATCH。
- reasoning 与 risks 仅据原文归纳，绝不编造数据、价格、财报。
- validUntil：原文有明确到期/止盈时间填日期，否则 null。
【约束】中文；不给具体买卖价格；不复述 OCR 错乱字符。
    """.trim()

    private fun buildUser(ocrText: String): String =
        "【截图文本】\n${if (ocrText.isBlank()) "（空）" else ocrText}"
}
```

- [ ] **Step 4: 运行验证通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.stock.dividend.data.repository.ScreenshotStrategyPromptBuilderTest"`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/stock/dividend/data/repository/ScreenshotStrategyPromptBuilder.kt \
        app/src/test/java/com/stock/dividend/data/repository/ScreenshotStrategyPromptBuilderTest.kt
git commit -m "feat(llm): 截图策略 prompt builder 纯函数与测试"
```

---

## Task 5: 纯函数 — ScreenshotStrategyParser（TDD）

**Files:**
- Create: `app/src/main/java/com/stock/dividend/data/repository/ScreenshotStrategy.kt`（结果模型 + sealed）
- Create: `app/src/main/java/com/stock/dividend/data/repository/ScreenshotStrategyParser.kt`
- Test: `app/src/test/java/com/stock/dividend/data/repository/ScreenshotStrategyParserTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ScreenshotStrategyParserTest {

    @Test
    fun fullJson_actionable() {
        val raw = """{"isActionable":true,"targetText":"招商银行","direction":"BUY","reasoning":"ROE高","risks":["息差"],"validUntil":"2026-09-01"}"""
        val r = ScreenshotStrategyParser.parse(raw)
        assertThat(r).isInstanceOf(ScreenshotStrategyParseResult.Actionable::class.java)
        val s = (r as ScreenshotStrategyParseResult.Actionable).strategy
        assertThat(s.targetText).isEqualTo("招商银行")
        assertThat(s.direction).isEqualTo(ScreenshotStrategy.StrategyDirection.BUY)
        assertThat(s.reasoning).isEqualTo("ROE高")
        assertThat(s.risks).containsExactly("息差")
        assertThat(s.validUntil).isEqualTo("2026-09-01")
    }

    @Test
    fun isActionableFalse_notActionable() {
        val r = ScreenshotStrategyParser.parse("""{"isActionable":false}""")
        assertThat(r).isEqualTo(ScreenshotStrategyParseResult.NotActionable)
    }

    @Test
    fun invalidDirection_fallsBackToWatch() {
        val r = ScreenshotStrategyParser.parse("""{"isActionable":true,"direction":"XXX"}""")
        assertThat((r as ScreenshotStrategyParseResult.Actionable).strategy.direction)
            .isEqualTo(ScreenshotStrategy.StrategyDirection.WATCH)
    }

    @Test
    fun missingRisks_emptyList() {
        val r = ScreenshotStrategyParser.parse("""{"isActionable":true}""")
        assertThat((r as ScreenshotStrategyParseResult.Actionable).strategy.risks).isEmpty()
    }

    @Test
    fun fencedJson_extracted() {
        val raw = "前缀\n```json\n{\"isActionable\":true,\"targetText\":\"x\"}\n```\n后缀"
        val r = ScreenshotStrategyParser.parse(raw)
        assertThat(r).isInstanceOf(ScreenshotStrategyParseResult.Actionable::class.java)
    }

    @Test
    fun pureText_failed() {
        val r = ScreenshotStrategyParser.parse("这不是json")
        assertThat(r).isInstanceOf(ScreenshotStrategyParseResult.Failed::class.java)
    }

    @Test
    fun empty_failed() {
        assertThat(ScreenshotStrategyParser.parse("")).isInstanceOf(ScreenshotStrategyParseResult.Failed::class.java)
    }

    @Test
    fun malformed_doesNotThrow() {
        // 不抛异常即通过
        ScreenshotStrategyParser.parse("{broken")
        ScreenshotStrategyParser.parse("""{"reasoning":}""")
    }
}
```

- [ ] **Step 2: 运行验证失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.stock.dividend.data.repository.ScreenshotStrategyParserTest"`
Expected: FAIL

- [ ] **Step 3: 实现结果模型 + Parser**

```kotlin
// data/repository/ScreenshotStrategy.kt
package com.stock.dividend.data.repository

/** LLM 从截图文本提取的半结构化策略（纯数据，无 Android 依赖）。 */
data class ScreenshotStrategy(
    val targetText: String,
    val direction: StrategyDirection,
    val reasoning: String,
    val risks: List<String>,
    val validUntil: String?
) {
    enum class StrategyDirection { BUY, SELL, WATCH }
}

/** 截图策略分析的 UI/编排状态：五态 + NoStrategy（泛化截图特有，无策略可提取时）。 */
sealed interface ScreenshotStrategyState {
    data object Idle : ScreenshotStrategyState
    data object Loading : ScreenshotStrategyState
    data object NotConfigured : ScreenshotStrategyState
    data class Success(val strategy: ScreenshotStrategy) : ScreenshotStrategyState
    data class NoStrategy(val message: String) : ScreenshotStrategyState
    data class Error(val message: String) : ScreenshotStrategyState
}
```

```kotlin
// data/repository/ScreenshotStrategyParser.kt
package com.stock.dividend.data.repository

import com.google.gson.JsonParser
import com.stock.dividend.data.repository.ScreenshotStrategy.StrategyDirection

sealed interface ScreenshotStrategyParseResult {
    data class Actionable(val strategy: ScreenshotStrategy) : ScreenshotStrategyParseResult
    data object NotActionable : ScreenshotStrategyParseResult
    data class Failed(val rawText: String) : ScreenshotStrategyParseResult
}

object ScreenshotStrategyParser {

    fun parse(rawContent: String): ScreenshotStrategyParseResult {
        if (rawContent.isBlank()) return ScreenshotStrategyParseResult.Failed("")
        val jsonStr = JsonExtraction.extractJsonObject(rawContent) ?: return failed(rawContent)
        return runCatching {
            val obj = JsonParser.parseString(jsonStr).asJsonObject
            if (obj.has("isActionable") && !obj.get("isActionable").asBoolean) {
                ScreenshotStrategyParseResult.NotActionable
            } else {
                ScreenshotStrategyParseResult.Actionable(
                    ScreenshotStrategy(
                        targetText = obj.safeStr("targetText"),
                        direction = parseDirection(obj.safeStr("direction")),
                        reasoning = obj.safeStr("reasoning"),
                        risks = obj.takeIf { it.has("risks") && it.get("risks").isJsonArray }
                            ?.get("risks")?.asJsonArray
                            ?.mapNotNull { runCatching { it.asString }.getOrNull() }
                            ?: emptyList(),
                        validUntil = obj.takeIf { it.has("validUntil") && !it.get("validUntil").isJsonNull }
                            ?.get("validUntil")?.asString
                    )
                )
            }
        }.getOrElse { failed(rawContent) }
    }

    private fun parseDirection(s: String): StrategyDirection = when (s.uppercase()) {
        "BUY" -> StrategyDirection.BUY
        "SELL" -> StrategyDirection.SELL
        "WATCH" -> StrategyDirection.WATCH
        else -> StrategyDirection.WATCH
    }

    private fun failed(raw: String) = ScreenshotStrategyParseResult.Failed(raw)

    private fun com.google.gson.JsonObject.safeStr(key: String): String =
        runCatching { if (has(key) && !get(key).isJsonNull) get(key).asString else "" }.getOrDefault("")
}
```

- [ ] **Step 4: 运行验证通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.stock.dividend.data.repository.ScreenshotStrategyParserTest"`
Expected: PASS（8 用例）

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/stock/dividend/data/repository/ScreenshotStrategy.kt \
        app/src/main/java/com/stock/dividend/data/repository/ScreenshotStrategyParser.kt \
        app/src/test/java/com/stock/dividend/data/repository/ScreenshotStrategyParserTest.kt
git commit -m "feat(llm): 截图策略 parser 纯函数与测试"
```

---

## Task 6: ScreenshotStrategyRepository 编排（TDD）

**Files:**
- Create: `app/src/main/java/com/stock/dividend/data/repository/ScreenshotStrategyRepository.kt`（在 Task 3 文件追加，或独立；此处独立放编排类）
- Test: `app/src/test/java/com/stock/dividend/data/repository/ScreenshotStrategyRepositoryTest.kt`

> 注：Task 3 已创建 `TradeStrategyRepository.kt` 放纯函数。本 Task 的编排类 `ScreenshotStrategyRepository` 与持久化类 `TradeStrategyRepository` 都需 `@Singleton class`，放同一文件易混，故编排类放独立文件 `ScreenshotStrategyRepository.kt`；持久化 `@Singleton class TradeStrategyRepository` 追加到 `TradeStrategyRepository.kt`（Task 7）。

- [ ] **Step 1: 写失败测试**

```kotlin
package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.remote.LlmApi
import com.stock.dividend.data.remote.dto.LlmChatRequest
import com.stock.dividend.data.remote.dto.LlmChatResponse
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class ScreenshotStrategyRepositoryTest {

    private val llmApi = mockk<LlmApi>()
    private val configSource = mockk<LlmConfigSource>()
    private val repo = ScreenshotStrategyRepository(llmApi, configSource)

    private fun cfg(complete: Boolean) = LlmConfig(
        baseUrl = "https://api.x.com", apiKey = "k", model = "m",
        // isComplete 由 LlmConfig 实现；mock 时直接 stub
    )

    @Test
    fun notConfigured() = runTest {
        every { configSource.observeConfig() } returns flowOf(incompleteConfig())
        assertThat(repo.analyze("text")).isEqualTo(ScreenshotStrategyState.NotConfigured)
    }

    @Test
    fun success() = runTest {
        every { configSource.observeConfig() } returns flowOf(completeConfig())
        coEvery { llmApi.chatCompletions(any(), any(), any()) } returns LlmChatResponse(
            content = """{"isActionable":true,"targetText":"招商银行","direction":"BUY"}"""
        )
        val r = repo.analyze("text")
        assertThat(r).isInstanceOf(ScreenshotStrategyState.Success::class.java)
    }

    @Test
    fun notActionable_toNoStrategy() = runTest {
        every { configSource.observeConfig() } returns flowOf(completeConfig())
        coEvery { llmApi.chatCompletions(any(), any(), any()) } returns
            LlmChatResponse(content = """{"isActionable":false}""")
        assertThat(repo.analyze("text")).isInstanceOf(ScreenshotStrategyState.NoStrategy::class.java)
    }

    @Test
    fun emptyContent_error() = runTest {
        every { configSource.observeConfig() } returns flowOf(completeConfig())
        coEvery { llmApi.chatCompletions(any(), any(), any()) } returns LlmChatResponse(content = null)
        val r = repo.analyze("text")
        assertThat(r).isInstanceOf(ScreenshotStrategyState.Error::class.java)
        assertThat((r as ScreenshotStrategyState.Error).message).isEqualTo("LLM 返回为空")
    }

    @Test
    fun http401_toApiKeyError() = runTest {
        every { configSource.observeConfig() } returns flowOf(completeConfig())
        coEvery { llmApi.chatCompletions(any(), any(), any()) } throws
            HttpException(Response.error<Any>(401, mockk(relaxed = true)))
        val r = repo.analyze("text")
        assertThat((r as ScreenshotStrategyState.Error).message).isEqualTo("API key 无效")
    }

    @Test
    fun networkError() = runTest {
        every { configSource.observeConfig() } returns flowOf(completeConfig())
        coEvery { llmApi.chatCompletions(any(), any(), any()) } throws RuntimeException("boom")
        assertThat(repo.analyze("text")).isInstanceOf(ScreenshotStrategyState.Error::class.java)
    }

    // 复用 LlmConfig 真实结构；这里用辅助构造（见 LlmConfig.kt 真实字段）
    private fun completeConfig(): LlmConfig = LlmConfig(
        baseUrl = "https://api.x.com", apiKey = "k", model = "m", providerId = ""
    )
    private fun incompleteConfig(): LlmConfig = LlmConfig(
        baseUrl = "", apiKey = "", model = "", providerId = ""
    )
}
```

> **实现前先核对 `LlmConfig` 真实字段**（`data/repository/LlmConfig.kt`），按真实字段调整 `completeConfig()`/`incompleteConfig()` 构造，确保 `isComplete` 返回 true/false。若字段名不一致，以真实文件为准修正测试。

- [ ] **Step 2: 运行验证失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.stock.dividend.data.repository.ScreenshotStrategyRepositoryTest"`
Expected: FAIL

- [ ] **Step 3: 实现编排**

```kotlin
// data/repository/ScreenshotStrategyRepository.kt
package com.stock.dividend.data.repository

import com.stock.dividend.data.remote.LlmApi
import com.stock.dividend.data.remote.dto.LlmChatRequest
import com.stock.dividend.data.remote.dto.LlmMessage
import kotlinx.coroutines.flow.first
import retrofit2.HttpException
import javax.inject.Inject
import javax.inject.Singleton

/** 编排截图策略提取：读配置 → 构造 prompt → 调用 → 解析 → 映射五态（+ NoStrategy）。 */
@Singleton
class ScreenshotStrategyRepository @Inject constructor(
    private val llmApi: LlmApi,
    private val configSource: LlmConfigSource,
) {
    suspend fun analyze(ocrText: String): ScreenshotStrategyState {
        val config = configSource.observeConfig().first()
        if (!config.isComplete) return ScreenshotStrategyState.NotConfigured

        val prompt = ScreenshotStrategyPromptBuilder.build(ocrText)
        val url = config.baseUrl.trimEnd('/') + "/chat/completions"
        val request = LlmChatRequest(
            model = config.model,
            messages = listOf(
                LlmMessage("system", prompt.system),
                LlmMessage("user", prompt.user)
            )
        )
        return try {
            val content = llmApi.chatCompletions(url, "Bearer ${config.apiKey}", request).content
                ?: return ScreenshotStrategyState.Error("LLM 返回为空")
            when (val parsed = ScreenshotStrategyParser.parse(content)) {
                is ScreenshotStrategyParseResult.Actionable -> ScreenshotStrategyState.Success(parsed.strategy)
                ScreenshotStrategyParseResult.NotActionable -> ScreenshotStrategyState.NoStrategy("未识别到可执行的买卖策略")
                is ScreenshotStrategyParseResult.Failed -> ScreenshotStrategyState.Error("LLM 响应解析失败，请重试")
            }
        } catch (e: HttpException) {
            ScreenshotStrategyState.Error(mapHttpError(e.code()))
        } catch (_: Exception) {
            ScreenshotStrategyState.Error("网络错误，请重试")
        }
    }

    private fun mapHttpError(code: Int): String = when (code) {
        401, 403 -> "API key 无效"
        429 -> "请求过频，稍后重试"
        else -> "分析失败，请重试"
    }
}
```

- [ ] **Step 4: 运行验证通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.stock.dividend.data.repository.ScreenshotStrategyRepositoryTest"`
Expected: PASS（核对真实 `LlmConfig` 后调整测试，确保 6 用例绿）

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/stock/dividend/data/repository/ScreenshotStrategyRepository.kt \
        app/src/test/java/com/stock/dividend/data/repository/ScreenshotStrategyRepositoryTest.kt
git commit -m "feat(llm): 截图策略 ScreenshotStrategyRepository 编排与测试"
```

---

## Task 7: TradeStrategyRepository（持久化封装）

**Files:**
- Modify: `app/src/main/java/com/stock/dividend/data/repository/TradeStrategyRepository.kt`（追加持久化类）

- [ ] **Step 1: 追加持久化类**（保留 Task 3 的顶层纯函数）

在 `TradeStrategyRepository.kt` 末尾追加：

```kotlin
import com.stock.dividend.data.local.dao.TradeStrategyDao
import com.stock.dividend.data.local.entity.STRATEGY_STATUS_ACTIVE
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TradeStrategyRepository @Inject constructor(
    private val strategyDao: TradeStrategyDao
) {
    suspend fun upsert(entity: TradeStrategyEntity) =
        runCatching { strategyDao.upsert(entity) }.getOrNull()  // 红线 #2

    suspend fun activeStrategiesFor(code: String): List<TradeStrategyEntity> =
        runCatching { strategyDao.activeStrategiesFor(code, LocalDate.now().toString()) }
            .getOrDefault(emptyList())
}
```

- [ ] **Step 2: 构建验证**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 提交**

```bash
git add app/src/main/java/com/stock/dividend/data/repository/TradeStrategyRepository.kt
git commit -m "feat(stock): TradeStrategyRepository 持久化封装（吞异常）"
```

---

## Task 8: 备份纳入 trade_strategies

**Files:**
- Modify: `app/src/main/java/com/stock/dividend/data/local/backup/BackupData.kt`
- Modify: `app/src/main/java/com/stock/dividend/data/repository/BackupRepository.kt`
- Test: 扩展 `app/src/test/java/com/stock/dividend/data/repository/BackupRepositoryTest.kt`

- [ ] **Step 1: BackupData 加字段**

```kotlin
// BackupData.kt —— 在 BackupContainer 末尾加（stockTags 之后）
import com.stock.dividend.data.local.entity.TradeStrategyEntity

data class BackupContainer(
    // ... 现有字段 ...
    val stockTags: List<StockTagEntity> = emptyList(),
    val tradeStrategies: List<TradeStrategyEntity> = emptyList()   // 新增，默认空兼容旧备份
)
```

- [ ] **Step 2: BackupRepository 导出/导入**

读 `BackupRepository.kt` 现有对 `notificationRules` 的处理，照抄：
- 导出：注入 `TradeStrategyDao`，`backup.tradeStrategies = strategyDao.getAllForBackup()`。
- 导入：`strategyDao.clear()` 然后 `strategyDao.insertAll(backup.tradeStrategies)`。

具体行号与上下文以 `BackupRepository.kt` 真实代码为准，模仿 `notificationRules` 的 `withTransaction { ... }` 块。

- [ ] **Step 3: 扩展 BackupRepositoryTest round-trip**

在现有测试类加一用例：构造含 `tradeStrategies` 的 `BackupContainer`，导出再导入，断言 `tradeStrategyDao.getAllForBackup()` 与原数据一致。具体断言模仿现有 `notificationRules` round-trip 用例。

- [ ] **Step 4: 运行测试**

Run: `./gradlew :app:testDebugUnitTest --tests "com.stock.dividend.data.repository.BackupRepositoryTest"`
Expected: PASS（含新用例）

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/stock/dividend/data/local/backup/BackupData.kt \
        app/src/main/java/com/stock/dividend/data/repository/BackupRepository.kt \
        app/src/test/java/com/stock/dividend/data/repository/BackupRepositoryTest.kt
git commit -m "feat(backup): trade_strategies 纳入备份/恢复"
```

---

## Task 9: 回流 — StockLlmInput + StockLlmPromptBuilder（TDD）

**Files:**
- Modify: `app/src/main/java/com/stock/dividend/data/repository/StockLlmInput.kt`
- Modify: `app/src/main/java/com/stock/dividend/data/repository/StockLlmPromptBuilder.kt`
- Test: 扩展 `app/src/test/java/com/stock/dividend/data/repository/StockLlmPromptBuilderTest.kt`

- [ ] **Step 1: StockLlmInput 加字段**

在 `StockLlmInput` data class 末尾（`fundamentals` 之后）加：

```kotlin
/** 该股的用户既有策略（来自截图分析，回流进 prompt）；缺失为空。 */
val userStrategies: List<UserStrategyRef> = emptyList(),
```

- [ ] **Step 2: 写失败测试**

在 `StockLlmPromptBuilderTest` 加用例：

```kotlin
@Test
fun userStrategies_rendered_withoutSourceNote() {
    val input = baseInput().copy(
        userStrategies = listOf(
            UserStrategyRef("BUY", "ROE高", listOf("息差收窄"), "2026-09-01", 3)
        )
    )
    val u = StockLlmPromptBuilder.build(input).user
    assertThat(u).contains("用户既有策略")
    assertThat(u).contains("[买入]")
    assertThat(u).contains("ROE高")
    assertThat(u).contains("3天前")
    // sourceNote 不存在该字段，无需断言
}

@Test
fun userStrategies_empty_rendersDash() {
    val u = StockLlmPromptBuilder.build(baseInput()).user
    assertThat(u).contains("用户既有策略")
}

@Test
fun system_containsUserStrategySemantics() {
    val s = StockLlmPromptBuilder.build(baseInput()).system
    assertThat(s).contains("用户既有策略")
}
```

（`baseInput()` 用现有测试里构造 `StockLlmInput` 的辅助方法；若无则就地构造一个最小合法 input。）

- [ ] **Step 3: 运行验证失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.stock.dividend.data.repository.StockLlmPromptBuilderTest"`
Expected: FAIL（新用例）

- [ ] **Step 4: 实现 prompt 渲染**

在 `StockLlmPromptBuilder.kt`：
- `SYSTEM` 的 `【数据语义】` 段末尾追加一行：
  ```
  - 用户既有策略：用户此前从外部内容整理出的观点，属用户个人关注视角，非客观数据；解读时可对照呼应，但不要盲从或简单复述。
  ```
- `buildUser(input)` 末尾（基本面渲染之后）追加：

```kotlin
// 用户既有策略（回流，不含 sourceNote）
sb.append("【用户既有策略（来自截图分析，仅供参照）】")
val us = input.userStrategies
if (us.isNullOrEmpty()) {
    sb.append("—\n")
} else {
    sb.append("\n")
    us.forEach { ref ->
        val dirZh = when (ref.direction) {
            "BUY" -> "买入"; "SELL" -> "卖出"; else -> "观望"
        }
        sb.append("  [$dirZh] ${ref.reasoning} (${ref.daysAgo}天前)\n")
        if (ref.risks.isNotEmpty()) {
            sb.append("    风险: ${ref.risks.joinToString(" / ")}\n")
        }
    }
}
```

- [ ] **Step 5: 运行验证通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.stock.dividend.data.repository.StockLlmPromptBuilderTest"`
Expected: PASS（含新用例，原用例不回归）

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/com/stock/dividend/data/repository/StockLlmInput.kt \
        app/src/main/java/com/stock/dividend/data/repository/StockLlmPromptBuilder.kt \
        app/src/test/java/com/stock/dividend/data/repository/StockLlmPromptBuilderTest.kt
git commit -m "feat(llm): 个股 AI 分析 prompt 回流用户既有策略（无 sourceNote）"
```

---

## Task 10: 回流 — 组合级 EvaluatedStock + LlmPromptBuilder（TDD）

**Files:**
- Modify: `app/src/main/java/com/stock/dividend/data/repository/EvaluatedStock.kt`
- Modify: `app/src/main/java/com/stock/dividend/data/repository/LlmPromptBuilder.kt`
- Test: 扩展 `app/src/test/java/com/stock/dividend/data/repository/LlmPromptBuilderTest.kt`

- [ ] **Step 1: EvaluatedStock 加字段**

在 `EvaluatedStock` 末尾（`reasons` 之后）加：

```kotlin
val userStrategies: List<UserStrategyRef> = emptyList(),
```

- [ ] **Step 2: 写失败测试**

```kotlin
@Test
fun userStrategies_renderedPerStock_withoutSourceNote() {
    val s = EvaluatedStock(
        code = "SH.600036", name = "招商银行", industry = "银行",
        action = HoldingAction.HOLD, priceVsLower = 0.5, dividendYield = 4.0,
        bollBand = null, currentPrice = null, reasons = emptyList(),
        userStrategies = listOf(UserStrategyRef("BUY", "ROE高", emptyList(), null, 5))
    )
    // 调现有 build(...) 测试辅助构造其余参数（参考现有 LlmPromptBuilderTest 用例）
    val u = LlmPromptBuilder.build(listOf(s), emptyMap(), emptyMap(), signals(), thresholds()).user
    assertThat(u).contains("[买入]")
    assertThat(u).contains("5天前")
}
```

（`signals()`/`thresholds()` 用现有测试辅助构造。）

- [ ] **Step 3: 运行验证失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.stock.dividend.data.repository.LlmPromptBuilderTest"`
Expected: FAIL

- [ ] **Step 4: 实现**

在 `LlmPromptBuilder.buildUser` 的每股循环（`stocks.forEach { s -> ... }`）末尾，在 `append("\n")` 之前追加该股策略渲染：

```kotlin
if (s.userStrategies.isNotEmpty()) {
    s.userStrategies.forEach { ref ->
        val dirZh = when (ref.direction) { "BUY"->"买入"; "SELL"->"卖出"; else->"观望" }
        sb.append(" | 既有策略[$dirZh] ${ref.reasoning}(${ref.daysAgo}天前)")
        if (ref.risks.isNotEmpty()) sb.append(" 风险:${ref.risks.joinToString("/")}")
    }
}
```

- [ ] **Step 5: 运行验证通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.stock.dividend.data.repository.LlmPromptBuilderTest"`
Expected: PASS

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/com/stock/dividend/data/repository/EvaluatedStock.kt \
        app/src/main/java/com/stock/dividend/data/repository/LlmPromptBuilder.kt \
        app/src/test/java/com/stock/dividend/data/repository/LlmPromptBuilderTest.kt
git commit -m "feat(llm): 组合级 AI 分析 prompt 回流每股用户策略"
```

---

## Task 11: 回流 — VM 集成（StockDetail + Portfolio）

**Files:**
- Modify: `app/src/main/java/com/stock/dividend/viewmodel/StockDetailViewModel.kt`
- Modify: `app/src/main/java/com/stock/dividend/viewmodel/PortfolioViewModel.kt`
- Test: 扩展 `app/src/test/java/com/stock/dividend/viewmodel/StockDetailViewModelTest.kt`
- Test: 扩展 `app/src/test/java/com/stock/dividend/viewmodel/PortfolioViewModelTest.kt`

- [ ] **Step 1: StockDetailViewModel 注入 + 取策略**

- 构造函数加 `private val strategyRepository: TradeStrategyRepository`（参考 `analyzeWithLlm` 现有 `StockRepository` 注入位置）。
- 在 `buildStockLlmInput(...)` 内，组装 `StockLlmInput(...)` 时给 `userStrategies` 传值。因 `buildStockLlmInput` 是挂起外的私有函数且需调挂起 `activeStrategiesFor`，改为：在 `analyzeWithLlm()` 的 `viewModelScope.launch` 内、`buildStockLlmInput` 调用前，先 `val userStrategies = runCatching { strategyRepository.activeStrategiesFor(stock.code).map { toUserStrategyRef(it) } }.getOrDefault(emptyList())`，然后把它作为参数传入 `buildStockLlmInput`（函数签名加一个参数 `userStrategies: List<UserStrategyRef>`），赋给 `StockLlmInput.userStrategies`。

- [ ] **Step 2: 扩展 StockDetailViewModelTest**

加用例：mock `strategyRepository.activeStrategiesFor(code)` 返回一条策略 → 触发 `analyzeWithLlm` → 用 MockK 的 `slot` 捕获 `StockLlmInput`（或直接验证 prompt 渲染：mock `llmApi.chatCompletions` 捕获 request body，断言 user message 含「既有策略」）。失败时仍正常出分析：mock `activeStrategiesFor` 抛异常 → 分析仍 Success。

- [ ] **Step 3: PortfolioViewModel 注入 + 批量取**

- 构造函数加 `private val strategyRepository: TradeStrategyRepository`。
- 在 `analyzeWithLlm()`（行 589 附近）内，组装 evaluation 后、调 `llmAnalysisRepository.analyze` 前，批量补充策略：

```kotlin
val withStrategies = evaluation?.map { e ->
    val refs = runCatching {
        strategyRepository.activeStrategiesFor(e.code).map { toUserStrategyRef(it) }
    }.getOrDefault(emptyList())
    e.copy(userStrategies = refs)
}
```
将 `withStrategies` 传入后续 analyze 与 state（若 `evaluation` 是 val 且多处引用，统一改名指向新 list）。

- [ ] **Step 4: 扩展 PortfolioViewModelTest**

加用例：mock `strategyRepository.activeStrategiesFor` 返回策略 → analyze 触发 → 捕获 prompt 含「既有策略」。

- [ ] **Step 5: 运行测试**

Run: `./gradlew :app:testDebugUnitTest --tests "com.stock.dividend.viewmodel.StockDetailViewModelTest" --tests "com.stock.dividend.viewmodel.PortfolioViewModelTest"`
Expected: PASS

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/com/stock/dividend/viewmodel/StockDetailViewModel.kt \
        app/src/main/java/com/stock/dividend/viewmodel/PortfolioViewModel.kt \
        app/src/test/java/com/stock/dividend/viewmodel/StockDetailViewModelTest.kt \
        app/src/test/java/com/stock/dividend/viewmodel/PortfolioViewModelTest.kt
git commit -m "feat(vm): 个股/组合 analyzeWithLlm 回流用户策略"
```

---

## Task 12: ViewModel — ScreenshotImportViewModel（两步 Review）

**Files:**
- Create: `app/src/main/java/com/stock/dividend/viewmodel/ScreenshotImportViewModel.kt`
- Test: `app/src/test/java/com/stock/dividend/viewmodel/ScreenshotImportViewModelTest.kt`

> `StockMatchResult` 已在 Task 3 定义于 `data/repository`，VM 直接 import。

- [ ] **Step 1: 定义 phase 枚举与 UiState 草稿类型**

在 `ScreenshotImportViewModel.kt`：

```kotlin
package com.stock.dividend.viewmodel

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stock.dividend.data.repository.ScreenshotStrategy
import com.stock.dividend.data.repository.ScreenshotStrategyState
import com.stock.dividend.data.repository.ScreenshotStrategyRepository
import com.stock.dividend.data.repository.StockMatchResult
import com.stock.dividend.data.repository.TradeStrategyRepository
import com.stock.dividend.data.repository.StockRepository
import com.stock.dividend.data.repository.matchTargetStock
import com.stock.dividend.data.repository.risksToJsonString
import com.stock.dividend.data.local.entity.TradeStrategyEntity
import com.stock.dividend.data.local.entity.STRATEGY_STATUS_ACTIVE
import com.stock.dividend.data.scan.TextRecognitionService
import com.stock.dividend.data.scan.loadSampledBitmap
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

enum class ScreenshotImportPhase {
    Idle, LoadingImage, OcrRunning, ReviewOcr, Analyzing, ReviewStrategy, Done, Error
}

@Stable
data class EditableStrategy(
    val targetText: String,
    val direction: ScreenshotStrategy.StrategyDirection,
    val reasoning: String,
    val risks: MutableList<String>,
    val validUntil: String?
)

@Stable
data class ScreenshotImportUiState(
    val phase: ScreenshotImportPhase = ScreenshotImportPhase.Idle,
    val imageUri: String? = null,
    val editableOcrText: String = "",
    val analysisError: String? = null,
    val editableStrategy: EditableStrategy? = null,
    val matchResult: StockMatchResult = StockMatchResult.Unmatched,
    val sourceNote: String = "",
    val errorMessage: String? = null
)

@HiltViewModel
class ScreenshotImportViewModel @Inject constructor(
    private val textRecognitionService: TextRecognitionService,
    private val screenshotStrategyRepository: ScreenshotStrategyRepository,
    private val strategyRepository: TradeStrategyRepository,
    private val stockRepository: StockRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(ScreenshotImportUiState())
    val uiState: StateFlow<ScreenshotImportUiState> = _uiState.asStateFlow()

    // 见 Step 2-4 方法
}
```

- [ ] **Step 2: 写失败测试（核心流程）**

```kotlin
// test/.../ScreenshotImportViewModelTest.kt —— Robolectric + MockK
package com.stock.dividend.viewmodel

import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.local.entity.StockEntity
import com.stock.dividend.data.repository.ScreenshotStrategy
import com.stock.dividend.data.repository.ScreenshotStrategyState
import com.stock.dividend.data.repository.ScreenshotStrategyRepository
import com.stock.dividend.data.repository.StockMatchResult
import com.stock.dividend.data.repository.TradeStrategyRepository
import com.stock.dividend.data.repository.TradeStrategyEntity  // 若导入名冲突调整
import com.stock.dividend.data.repository.UserStrategyRef
import com.stock.dividend.data.scan.TextRecognitionService
import com.stock.dividend.data.scan.OcrElement
import com.stock.dividend.data.repository.StockRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ScreenshotImportViewModelTest {

    private val ocr = mockk<TextRecognitionService>()
    private val llmRepo = mockk<ScreenshotStrategyRepository>()
    private val strategyRepo = mockk<TradeStrategyRepository>(relaxed = true)
    private val stockRepo = mockk<StockRepository>()
    private lateinit var vm: ScreenshotImportViewModel

    @Before
    fun setup() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        coEvery { stockRepo.allStocksFlow } returns flowOf(
            listOf(StockEntity(code = "SH.600036", name = "招商银行", marketCode = "1"))
        )
        vm = ScreenshotImportViewModel(ocr, llmRepo, strategyRepo, stockRepo, RuntimeEnvironment.getApplication())
    }

    @After fun teardown() { Dispatchers.resetMain() }

    @Test
    fun onImagePicked_stopsAtReviewOcr() = runTest {
        coEvery { ocr.recognize(any()) } returns listOf(
            OcrElement("招商银行 买入", 0f, 0f, 100f, 10f)
        )
        vm.onImagePicked(mockk(relaxed = true))
        advanceUntilIdle()
        assertThat(vm.uiState.value.phase).isEqualTo(ScreenshotImportPhase.ReviewOcr)
        assertThat(vm.uiState.value.editableOcrText).contains("招商银行")
    }

    @Test
    fun startAnalysis_success_reviewStrategy() = runTest {
        // 预置 OCR 文本
        coEvery { ocr.recognize(any()) } returns listOf(OcrElement("招商银行", 0f,0f,10f,10f))
        vm.onImagePicked(mockk(relaxed = true)); advanceUntilIdle()
        coEvery { llmRepo.analyze(any()) } returns ScreenshotStrategyState.Success(
            ScreenshotStrategy("招商银行", ScreenshotStrategy.StrategyDirection.BUY, "ROE高", listOf("息差"), null)
        )
        vm.startAnalysis(); advanceUntilIdle()
        val s = vm.uiState.value
        assertThat(s.phase).isEqualTo(ScreenshotImportPhase.ReviewStrategy)
        assertThat(s.matchResult).isInstanceOf(StockMatchResult.Matched::class.java)
        assertThat(s.editableStrategy!!.direction).isEqualTo(ScreenshotStrategy.StrategyDirection.BUY)
    }

    @Test
    fun startAnalysis_noStrategy_staysReviewOcr() = runTest {
        coEvery { ocr.recognize(any()) } returns listOf(OcrElement("x", 0f,0f,10f,10f))
        vm.onImagePicked(mockk(relaxed = true)); advanceUntilIdle()
        coEvery { llmRepo.analyze(any()) } returns ScreenshotStrategyState.NoStrategy("no")
        vm.startAnalysis(); advanceUntilIdle()
        assertThat(vm.uiState.value.phase).isEqualTo(ScreenshotImportPhase.ReviewOcr)
        assertThat(vm.uiState.value.analysisError).isNotNull()
    }

    @Test
    fun confirmSave_persistsEntity() = runTest {
        coEvery { ocr.recognize(any()) } returns listOf(OcrElement("招商银行", 0f,0f,10f,10f))
        vm.onImagePicked(mockk(relaxed = true)); advanceUntilIdle()
        coEvery { llmRepo.analyze(any()) } returns ScreenshotStrategyState.Success(
            ScreenshotStrategy("招商银行", ScreenshotStrategy.StrategyDirection.BUY, "r", emptyList(), null)
        )
        vm.startAnalysis(); advanceUntilIdle()
        vm.confirmSave(); advanceUntilIdle()
        assertThat(vm.uiState.value.phase).isEqualTo(ScreenshotImportPhase.Done)
        coVerify { strategyRepo.upsert(any()) }
    }
}
```

（注意 import `TradeStrategyEntity` 从 `data.local.entity`；`coVerify` 来自 mockk。）

- [ ] **Step 3: 运行验证失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.stock.dividend.viewmodel.ScreenshotImportViewModelTest"`
Expected: FAIL（方法未实现）

- [ ] **Step 4: 实现 VM 方法**

在 `ScreenshotImportViewModel` 内：

```kotlin
fun onImagePicked(uri: Uri) {
    viewModelScope.launch {
        _uiState.update { it.copy(phase = ScreenshotImportPhase.LoadingImage, imageUri = uri.toString(), errorMessage = null, analysisError = null) }
        try {
            _uiState.update { it.copy(phase = ScreenshotImportPhase.OcrRunning) }
            val bitmap = loadSampledBitmap(context, uri)
            val elements = textRecognitionService.recognize(bitmap)
            val text = elements.joinToString("\n") { it.text }
            if (text.isBlank()) {
                _uiState.update { it.copy(phase = ScreenshotImportPhase.Error, errorMessage = "未识别到文本") }
                return@launch
            }
            _uiState.update { it.copy(phase = ScreenshotImportPhase.ReviewOcr, editableOcrText = text) }
        } catch (e: Exception) {
            _uiState.update { it.copy(phase = ScreenshotImportPhase.Error, errorMessage = "图片识别失败：${e.message ?: "未知错误"}") }
        }
    }
}

fun onOcrTextChanged(t: String) {
    if (_uiState.value.phase == ScreenshotImportPhase.ReviewOcr)
        _uiState.update { it.copy(editableOcrText = t) }
}

fun startAnalysis() {
    if (_uiState.value.phase != ScreenshotImportPhase.ReviewOcr) return
    val ocrText = _uiState.value.editableOcrText
    _uiState.update { it.copy(phase = ScreenshotImportPhase.Analyzing, analysisError = null) }
    viewModelScope.launch {
        when (val r = screenshotStrategyRepository.analyze(ocrText)) {
            is ScreenshotStrategyState.Success -> {
                val s = r.strategy
                val stocks = runCatching { stockRepository.allStocksFlow.first() }.getOrDefault(emptyList())
                val match = matchTargetStock(s.targetText, stocks)
                _uiState.update {
                    it.copy(
                        phase = ScreenshotImportPhase.ReviewStrategy,
                        editableStrategy = EditableStrategy(s.targetText, s.direction, s.reasoning, s.risks.toMutableList(), s.validUntil),
                        matchResult = match,
                        analysisError = null
                    )
                }
            }
            is ScreenshotStrategyState.NoStrategy -> _uiState.update {
                it.copy(phase = ScreenshotImportPhase.ReviewOcr, analysisError = r.message)
            }
            is ScreenshotStrategyState.Error -> _uiState.update {
                it.copy(phase = ScreenshotImportPhase.ReviewOcr, analysisError = r.message)
            }
            is ScreenshotStrategyState.NotConfigured -> _uiState.update {
                it.copy(phase = ScreenshotImportPhase.ReviewOcr, analysisError = "需先在设置配置 LLM")
            }
            ScreenshotStrategyState.Idle, ScreenshotStrategyState.Loading -> Unit
        }
    }
}

// 第二步编辑方法（均用 copy 返回新对象，避免突变 risks）
fun onTargetTextChanged(t: String) = editStrategy { it.copy(targetText = t) }
fun onDirectionChanged(d: ScreenshotStrategy.StrategyDirection) = editStrategy { it.copy(direction = d) }
fun onReasoningChanged(t: String) = editStrategy { it.copy(reasoning = t) }
fun onRiskChanged(i: Int, t: String) = editStrategy { es ->
    es.copy(risks = es.risks.toMutableList().also { it[i] = t })
}
fun addRisk() = editStrategy { es -> es.copy(risks = es.risks.toMutableList().apply { add("") }) }
fun removeRisk(i: Int) = editStrategy { es -> es.copy(risks = es.risks.toMutableList().apply { removeAt(i) }) }
fun onValidUntilChanged(d: String?) = editStrategy { it.copy(validUntil = d) }
fun onSourceNoteChanged(t: String) = _uiState.update { it.copy(sourceNote = t) }
fun onManualMatch(code: String, name: String) = _uiState.update { it.copy(matchResult = StockMatchResult.Matched(code, name)) }
fun clearMatch() = _uiState.update { it.copy(matchResult = StockMatchResult.Unmatched) }

private fun editStrategy(transform: (EditableStrategy) -> EditableStrategy) {
    _uiState.update { st ->
        val cur = st.editableStrategy ?: return@update st
        st.copy(editableStrategy = transform(cur))
    }
}

fun backToOcrReview() {
    _uiState.update { it.copy(phase = ScreenshotImportPhase.ReviewOcr, editableStrategy = null, analysisError = null) }
}

fun confirmSave() {
    val cur = _uiState.value.editableStrategy ?: return
    viewModelScope.launch {
        try {
            val match = _uiState.value.matchResult
            val stockCode = (match as? StockMatchResult.Matched)?.code
            val entity = TradeStrategyEntity(
                id = UUID.randomUUID().toString(),
                stockCode = stockCode,
                targetText = cur.targetText,
                direction = cur.direction.name,
                reasoning = cur.reasoning,
                risks = risksToJsonString(cur.risks.filter { it.isNotBlank() }),
                validUntil = cur.validUntil?.takeIf { it.isNotBlank() },
                sourceNote = _uiState.value.sourceNote.takeIf { it.isNotBlank() },
                rawOcrText = _uiState.value.editableOcrText,
                status = STRATEGY_STATUS_ACTIVE
            )
            strategyRepository.upsert(entity)
            _uiState.update { it.copy(phase = ScreenshotImportPhase.Done, errorMessage = null) }
        } catch (e: Exception) {
            _uiState.update { it.copy(errorMessage = "保存失败：${e.message ?: "未知错误"}") }
        }
    }
}

fun resetToIdle() {
    _uiState.value = ScreenshotImportUiState()
}
```

> 注意 `editStrategy` 里 `it.apply { risks[i] = t }` 会突变；改用纯 copy 更安全：
> `onRiskChanged` 实现：`editStrategy { es -> es.copy(risks = es.risks.toMutableList().also { it[i] = t }) }`；`addRisk`/`removeRisk` 同理用 copy 返回新 list。按此修正。

- [ ] **Step 5: 运行验证通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.stock.dividend.viewmodel.ScreenshotImportViewModelTest"`
Expected: PASS（4 用例）

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/com/stock/dividend/viewmodel/ScreenshotImportViewModel.kt \
        app/src/test/java/com/stock/dividend/viewmodel/ScreenshotImportViewModelTest.kt
git commit -m "feat(vm): ScreenshotImportViewModel 两步 Review 流程与测试"
```

---

## Task 13: ViewModel — TradeStrategyListViewModel

**Files:**
- Create: `app/src/main/java/com/stock/dividend/viewmodel/TradeStrategyListViewModel.kt`
- Test: `app/src/test/java/com/stock/dividend/viewmodel/TradeStrategyListViewModelTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
package com.stock.dividend.viewmodel

import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.local.dao.TradeStrategyDao
import com.stock.dividend.data.local.entity.TradeStrategyEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TradeStrategyListViewModelTest {

    @Test
    fun observeAll_rendersItems() = runTest {
        val dao = mockk<TradeStrategyDao>()
        coEvery { dao.observeAll() } returns flowOf(listOf(
            TradeStrategyEntity("1", "SH.600036", "招商银行", "BUY", "r", "[]", null, null, "ocr")
        ))
        val vm = TradeStrategyListViewModel(dao)
        kotlinx.coroutines.test.advanceUntilIdle()
        assertThat(vm.uiState.value.items).hasSize(1)
    }

    @Test
    fun archive_callsDao() = runTest {
        val dao = mockk<TradeStrategyDao>(relaxed = true)
        val vm = TradeStrategyListViewModel(dao)
        vm.archive("1")
        kotlinx.coroutines.test.advanceUntilIdle()
        coVerify { dao.updateStatus("1", "ARCHIVED", any()) }
    }
}
```

- [ ] **Step 2: 运行验证失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.stock.dividend.viewmodel.TradeStrategyListViewModelTest"`
Expected: FAIL

- [ ] **Step 3: 实现**

```kotlin
// viewmodel/TradeStrategyListViewModel.kt
package com.stock.dividend.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stock.dividend.data.local.dao.TradeStrategyDao
import com.stock.dividend.data.local.entity.STRATEGY_STATUS_ARCHIVED
import com.stock.dividend.data.local.entity.TradeStrategyEntity
import com.stock.dividend.data.repository.risksFromJson
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class StrategyListItem(
    val id: String,
    val stockCode: String?,
    val targetText: String,
    val direction: String,
    val reasoning: String,
    val risks: List<String>,
    val validUntil: String?,
    val sourceNote: String?,
    val createdAt: Long
)

data class TradeStrategyListUiState(val items: List<StrategyListItem> = emptyList())

@HiltViewModel
class TradeStrategyListViewModel @Inject constructor(
    private val strategyDao: TradeStrategyDao
) : ViewModel() {

    val uiState: StateFlow<TradeStrategyListUiState> =
        strategyDao.observeAll().map { list -> list.map { it.toItem() } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TradeStrategyListUiState())

    fun archive(id: String) {
        viewModelScope.launch { strategyDao.updateStatus(id, STRATEGY_STATUS_ARCHIVED) }
    }

    fun delete(id: String) {
        viewModelScope.launch { strategyDao.delete(id) }
    }

    private fun TradeStrategyEntity.toItem() = StrategyListItem(
        id, stockCode, targetText, direction, reasoning, risksFromJson(risks), validUntil, sourceNote, createdAt
    )
}
```

- [ ] **Step 4: 运行验证通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.stock.dividend.viewmodel.TradeStrategyListViewModelTest"`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/stock/dividend/viewmodel/TradeStrategyListViewModel.kt \
        app/src/test/java/com/stock/dividend/viewmodel/TradeStrategyListViewModelTest.kt
git commit -m "feat(vm): TradeStrategyListViewModel 列表/归档/删除"
```

---

## Task 14: 导航路由注册

**Files:**
- Modify: `app/src/main/java/com/stock/dividend/ui/navigation/AppNavigation.kt`

- [ ] **Step 1: 加 Routes + composable**

在 `Routes` object 加：
```kotlin
const val SCREENSHOT_IMPORT = "screenshotImport"
const val TRADE_STRATEGY_LIST = "tradeStrategyList"
```

在 `NavHost` 内加两个 composable（仿 `BACKUP_RESTORE`）：
```kotlin
composable(Routes.TRADE_STRATEGY_LIST) {
    TradeStrategyListScreen(
        onBack = { rootNavController.popBackStack() },
        onAddFromScreenshot = { rootNavController.navigate(Routes.SCREENSHOT_IMPORT) }
    )
}
composable(Routes.SCREENSHOT_IMPORT) {
    ScreenshotImportScreen(
        onBack = { rootNavController.popBackStack() },
        onViewList = { rootNavController.navigate(Routes.TRADE_STRATEGY_LIST) }
    )
}
```
（补 import `TradeStrategyListScreen`、`ScreenshotImportScreen`；两 Screen 在 Task 15/16 创建。）

- [ ] **Step 2: 设置页入口（MainScaffold + SettingsScreen）**

在 `MainScaffold.kt` 行 172-175 的 `SettingsScreen(...)` 调用加：
```kotlin
onOpenStrategyLibrary = { rootNavController.navigate(Routes.TRADE_STRATEGY_LIST) }
```
在 `SettingsScreen` 签名（`NotificationSettingsScreen.kt:70`）加 `onOpenStrategyLibrary: () -> Unit` 参数，并在内容区加一项 `SettingsEntryRow`「策略库」→ 调 `onOpenStrategyLibrary`。

- [ ] **Step 3: 提交（先不构建，Screen 下一 Task 补）**

```bash
git add app/src/main/java/com/stock/dividend/ui/navigation/AppNavigation.kt \
        app/src/main/java/com/stock/dividend/ui/screen/MainScaffold.kt \
        app/src/main/java/com/stock/dividend/ui/screen/NotificationSettingsScreen.kt
git commit -m "feat(nav): 策略库与截图导入路由 + 设置页入口"
```

> 本 Task 因依赖 Screen 文件存在才能编译，实际与 Task 15/16 一起在 Task 16 末尾构建验证。

---

## Task 15: UI — TradeStrategyListScreen

**Files:**
- Create: `app/src/main/java/com/stock/dividend/ui/screen/TradeStrategyListScreen.kt`

- [ ] **Step 1: 实现（复用设计系统 StatusPill/FinanceStatusTone/AppCardDefaults）**

```kotlin
package com.stock.dividend.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stock.dividend.data.local.entity.STRATEGY_DIRECTION_BUY
import com.stock.dividend.data.local.entity.STRATEGY_DIRECTION_SELL
import com.stock.dividend.data.local.entity.STRATEGY_DIRECTION_WATCH
import com.stock.dividend.ui.component.EmptyStateView
import com.stock.dividend.viewmodel.StrategyListItem
import com.stock.dividend.viewmodel.TradeStrategyListViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TradeStrategyListScreen(
    onBack: () -> Unit,
    onAddFromScreenshot: () -> Unit,
    viewModel: TradeStrategyListViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("策略库") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, null) } }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddFromScreenshot) { Icon(Icons.Filled.Add, null) }
        }
    ) { padding ->
        if (state.items.isEmpty()) {
            EmptyStateView(modifier = Modifier.padding(padding), message = "暂无策略，点 + 从截图添加")
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(state.items, key = { it.id }) { item ->
                    StrategyCard(item, onArchive = viewModel::archive, onDelete = viewModel::delete)
                }
            }
        }
    }
}

@Composable
private fun StrategyCard(item: StrategyListItem, onArchive: (String) -> Unit, onDelete: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.targetText, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                AssistChip(onClick = {}, label = { Text(directionZh(item.direction)) })
            }
            Text(item.reasoning, style = MaterialTheme.typography.bodyMedium, maxLines = if (expanded) 10 else 2)
            if (expanded && item.risks.isNotEmpty()) {
                Text("风险：", style = MaterialTheme.typography.labelMedium)
                item.risks.forEach { Text("· $it", style = MaterialTheme.typography.bodySmall) }
            }
            Row {
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起" else "展开") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { onArchive(item.id) }) { Text("归档") }
                TextButton(onClick = { onDelete(item.id) }) { Text("删除") }
            }
        }
    }
}

private fun directionZh(d: String) = when (d) {
    STRATEGY_DIRECTION_BUY -> "买入"
    STRATEGY_DIRECTION_SELL -> "卖出"
    STRATEGY_DIRECTION_WATCH -> "观望"
    else -> "—"
}
```

> `AssistChip` 着色用 `FinanceStatusTone`（绿/红/中性）可后续打磨；初版用默认 chip。`EmptyStateView` 签名以真实组件为准（若有 `message` 参数；若签名不同，调整）。

- [ ] **Step 2: 提交（与 Task 16 一起构建）**

暂不单独提交。

---

## Task 16: UI — ScreenshotImportScreen（两步 Review）+ 全量构建

**Files:**
- Create: `app/src/main/java/com/stock/dividend/ui/screen/ScreenshotImportScreen.kt`

- [ ] **Step 1: 实现（PhotoPicker 仿 PortfolioImportScreen）**

```kotlin
package com.stock.dividend.ui.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stock.dividend.data.repository.ScreenshotStrategy
import com.stock.dividend.viewmodel.ScreenshotImportPhase
import com.stock.dividend.viewmodel.ScreenshotImportViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenshotImportScreen(
    onBack: () -> Unit,
    onViewList: () -> Unit,
    viewModel: ScreenshotImportViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.onImagePicked(uri)
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("截图策略分析") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, null) }
        })
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (state.phase) {
                ScreenshotImportPhase.Idle -> {
                    Button(onClick = {
                        pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }) { Text("选择截图") }
                }
                ScreenshotImportPhase.LoadingImage, ScreenshotImportPhase.OcrRunning -> {
                    CircularProgressIndicator()
                    Text("识别中…")
                }
                ScreenshotImportPhase.ReviewOcr -> ReviewOcrContent(state, viewModel, onRetry = {
                    pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                })
                ScreenshotImportPhase.Analyzing -> { CircularProgressIndicator(); Text("AI 分析中…") }
                ScreenshotImportPhase.ReviewStrategy -> ReviewStrategyContent(state, viewModel)
                ScreenshotImportPhase.Done -> {
                    Text("策略已保存")
                    TextButton(onClick = onViewList) { Text("查看策略库") }
                    TextButton(onClick = viewModel::resetToIdle) { Text("再分析一张") }
                }
                ScreenshotImportPhase.Error -> {
                    Text(state.errorMessage ?: "出错了", color = MaterialTheme.colorScheme.error)
                    Button(onClick = viewModel::resetToIdle) { Text("重新开始") }
                }
            }
        }
    }
}

@Composable
private fun ReviewOcrContent(state: com.stock.dividend.viewmodel.ScreenshotImportUiState, vm: ScreenshotImportViewModel, onRetry: () -> Unit) {
    state.analysisError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    OutlinedTextField(
        value = state.editableOcrText,
        onValueChange = vm::onOcrTextChanged,
        label = { Text("OCR 文本（可编辑修正）") },
        modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp)
    )
    Row {
        Button(onClick = vm::startAnalysis, enabled = state.editableOcrText.isNotBlank()) { Text("AI 提取策略") }
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = onRetry) { Text("重选图片") }
    }
}

@Composable
private fun ReviewStrategyContent(state: com.stock.dividend.viewmodel.ScreenshotImportUiState, vm: ScreenshotImportViewModel) {
    val s = state.editableStrategy ?: return
    OutlinedTextField(s.targetText, vm::onTargetTextChanged, label = { Text("标的") }, modifier = Modifier.fillMaxWidth())
    Text("方向")
    Row {
        ScreenshotStrategy.StrategyDirection.values().forEach { d ->
            FilterChip(selected = s.direction == d, onClick = { vm.onDirectionChanged(d) }, label = { Text(dirZh(d)) })
        }
    }
    OutlinedTextField(s.reasoning, vm::onReasoningChanged, label = { Text("核心理由") }, modifier = Modifier.fillMaxWidth())
    s.risks.forEachIndexed { i, r ->
        OutlinedTextField(r, { vm.onRiskChanged(i, it) }, label = { Text("风险 ${i+1}") }, modifier = Modifier.fillMaxWidth())
    }
    TextButton(onClick = vm::addRisk) { Text("+ 添加风险") }
    // validUntil / sourceNote 用简单文本框（日期选择器可后续打磨）
    OutlinedTextField(s.validUntil ?: "", { vm.onValidUntilChanged(it.ifBlank { null }) }, label = { Text("有效期 YYYY-MM-DD（空=长期）") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(state.sourceNote, vm::onSourceNoteChanged, label = { Text("来源备注（可选）") }, modifier = Modifier.fillMaxWidth())
    Text("关联：${(state.matchResult as? com.stock.dividend.data.repository.StockMatchResult.Matched)?.let { "已关联 ${it.name}" } ?: "未关联自选股"}")
    state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Row {
        Button(onClick = vm::confirmSave) { Text("保存策略") }
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = vm::backToOcrReview) { Text("返回重提") }
    }
}

private fun dirZh(d: ScreenshotStrategy.StrategyDirection) = when (d) {
    ScreenshotStrategy.StrategyDirection.BUY -> "买入"
    ScreenshotStrategy.StrategyDirection.SELL -> "卖出"
    ScreenshotStrategy.StrategyDirection.WATCH -> "观望"
}
```

- [ ] **Step 2: 全量构建验证**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL（Task 14/15/16 路由+两 Screen 编译通过）

- [ ] **Step 3: 提交**

```bash
git add app/src/main/java/com/stock/dividend/ui/screen/ScreenshotImportScreen.kt \
        app/src/main/java/com/stock/dividend/ui/screen/TradeStrategyListScreen.kt
git commit -m "feat(ui): 截图导入页（两步 Review）+ 策略库列表页"
```

---

## Task 17: 全量测试 + 回归验证

**Files:** 无新增（验证）

- [ ] **Step 1: 跑全量单元测试**

Run: `./gradlew :app:testDebugUnitTest`
Expected: 全绿（含本计划所有新测试 + 现有测试回归）

- [ ] **Step 2: 跑全量构建（CI 等价）**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 修复任何回归（若有）**

若现有 `StockDetailViewModelTest`/`PortfolioViewModelTest` 因 `buildStockLlmInput` 签名变化或新构造参数失败，补 mock（Task 11 的 Step 2/4 已覆盖；若有遗漏按错误信息补 `strategyRepository` mock 与 `activeStrategiesFor` 桩）。

- [ ] **Step 4: 最终提交（如有修复）**

```bash
git add -A
git commit -m "test(stock): 修复回流集成测试回归"
```

---

## Self-Review（spec 覆盖核对）

| Spec 要求 | 对应 Task |
|---|---|
| §3.1 trade_strategies 表 | T1 |
| §3.2 DAO（observeAll/observeByStock/activeStrategiesFor/upsert/updateStatus/delete/backup） | T1 |
| §3.3 MIGRATION_15_16 + version 16 + DI 注册 | T2 |
| §3.4 备份纳入 | T8 |
| §4.1 ScreenshotStrategy + ScreenshotStrategyState（含 NoStrategy） | T5 |
| §4.2 PromptBuilder 纯函数 | T4 |
| §4.3 Parser 纯函数 + JsonExtraction 复用 | T5 |
| §4.4 ScreenshotStrategyRepository 编排 | T6 |
| §5.1 ScreenshotImportViewModel 两步 Review + 匹配 | T12（+T3 match） |
| §5.2 TradeStrategyListViewModel | T13 |
| §5.3 TradeStrategyRepository 持久化 | T7 |
| §6 回流（UserStrategyRef + 两 PromptBuilder + 两 VM） | T9/T10/T11 |
| §7.1 ScreenshotImportScreen（两步 Review UI） | T16 |
| §7.2 TradeStrategyListScreen | T15 |
| §7.3 路由 + 设置页入口 | T14 |
| §8 DI（DAO provider + Repository 自动装配） | T2/T7 |
| §9 测试 | 各 Task TDD 步骤 |
| §11 红线（Migration/吞异常/loading 复位/纯函数/中文） | 贯穿各 Task |

**类型一致性核对：** `UserStrategyRef`（T3）→ `StockLlmInput.userStrategies`（T9）→ `EvaluatedStock.userStrategies`（T10）字段名一致；`ScreenshotStrategy.StrategyDirection`（T5）→ `EditableStrategy.direction`（T12）一致；`StockMatchResult`（T3 data 层）→ VM/UI 引用一致；`risksToJsonString`/`risksFromJson`（T3）→ VM 存/取（T12/T13）一致。

**无 placeholder**：所有代码块完整、命令含 expected、文件路径绝对。
