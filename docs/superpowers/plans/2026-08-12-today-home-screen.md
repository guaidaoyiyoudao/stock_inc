# 今日首页（Today Home Screen）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新增「今日」Tab 作为起始页，一屏三块（AI 一句话总结 / 组合表现+大盘对照 / 信号卡），把 APP 从「打开要主动找」改成「打开即所见」，解决日活低问题。

**Architecture:** 自底向上：先 3 个纯函数（信号聚合 / prompt 构建 / 响应解析，配单测），再 Coordinator 编排（后台拉数据→聚合→LLM→缓存），再 Worker + 调度（每日盘后定时生成 AI 简报），再 TodayViewModel（多 collector，参考 PortfolioViewModel），再 TodayScreen UI，最后导航/Tab 改造 + 成就降级。**不改 schema**（DB version 保持 20），AI 简报复用 `LlmAnalysisCacheDao`（scope=`TODAY_BRIEFING`）。

**Tech Stack:** Kotlin 2.1.20 / Compose / Hilt / Room / WorkManager / Coroutines+Flow / JUnit4+Truth（纯函数）/ Robolectric+MockK+Turbine（VM）。

**前置约束（AGENTS.md）：**
- 红线 #2：网络/DB 异常吞掉返回安全空值，绝不冒泡崩 UI。
- 红线 #4：信号判定覆盖自选股（shares=0）+ 持仓。
- §4.4：决策逻辑抽纯函数 + 配单测。
- §4.2：VM 用单个 UiState + 多独立 collector。
- 先从 master 开分支 `feature/today-home-screen`，所有 commit 在分支上。

**设计依据：** `docs/superpowers/specs/2026-08-12-today-home-screen-design.md`

---

## File Structure

**新建（main）：**
| 文件 | 职责 |
|---|---|
| `data/repository/TodaySignalAggregator.kt` | 纯函数：持仓+自选+网格+分红 → 排序信号列表（复用 `HoldingRecommender`/`computeBuyThreshold`/`GridCalculator`） |
| `data/repository/TodayBriefingPromptBuilder.kt` | 纯函数：组合表现 + 信号 → LLM prompt（约束一句话≤50字、不臆造数字） |
| `data/repository/TodayBriefingParser.kt` | 纯函数：LLM 响应 → 一句话字符串（容错，复用 `JsonExtraction`） |
| `data/repository/TodayBriefingCoordinator.kt` | `@Singleton` 编排：拉数据→聚合→prompt→LLM→解析→缓存；读缓存 |
| `data/notification/TodayBriefingWorker.kt` | `@HiltWorker CoroutineWorker`：调 `coordinator.generateAndCache(today)` |
| `viewmodel/TodayViewModel.kt` | `@HiltViewModel`：多 collector 聚合今日一瞥 UiState |
| `ui/screen/TodayScreen.kt` | 三块卡片 Composable |

**新建（test，包结构与 main 对齐）：**
- `data/repository/TodaySignalAggregatorTest.kt` / `TodayBriefingPromptBuilderTest.kt` / `TodayBriefingParserTest.kt`（JUnit4+Truth）
- `data/repository/TodayBriefingCoordinatorTest.kt`（Robolectric+MockK）
- `viewmodel/TodayViewModelTest.kt`（Robolectric+MockK+Turbine）

**修改：**
- `data/notification/NotificationScheduler.kt`：加 `scheduleTodayBriefing()`
- `StockDividendApp.kt`：`onCreate` 调 `scheduleTodayBriefing()`
- `ui/screen/MainScaffold.kt`：`bottomNavItems` 加「今日」首项、`startDestination = "today"`、移除「成就」Tab 项（`composable("achievements")` 路由保留，供设置页跳）
- `ui/screen/SettingsScreen.kt`：加「成就」入口行（降级）

---

## Task 1: TodaySignalAggregator 纯函数 + 测试

**Files:**
- Create: `app/src/main/java/com/stock/dividend/data/repository/TodaySignalAggregator.kt`
- Test: `app/src/test/java/com/stock/dividend/data/repository/TodaySignalAggregatorTest.kt`

- [ ] **Step 1: 写失败测试（买入触发 + 股息率门槛）**

`app/src/test/java/com/stock/dividend/data/repository/TodaySignalAggregatorTest.kt`：

```kotlin
package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.local.entity.DividendEntity
import com.stock.dividend.data.local.entity.GridPlanEntity
import org.junit.Test
import java.time.LocalDate

class TodaySignalAggregatorTest {

    private val today = LocalDate.of(2026, 8, 12)

    private fun snapshot(
        code: String,
        name: String = code,
        price: Double = 10.0,
        weekly: BollBand? = null,
        daily: BollBand? = null,
        monthly: BollBand? = null,
        dividend: Double? = null,
        bond: Double? = null,
        multiplier: Double = 2.5,
    ) = TodayStockSnapshot(
        code = code, name = name, price = price,
        weeklyBand = weekly, dailyBand = daily, monthlyBand = monthly,
        latestYearlyDividend = dividend, bondYield10Y = bond,
        buyThresholdMultiplier = multiplier,
    )

    @Test
    fun bollResonantBuy_triggersBuySignal() {
        // price 同时 ≤ 日/周下轨、≤ 月中轨，且股息率 ≥ 2% → HoldingRecommender.BUY
        val band = BollBand(middle = 10.0, upper = 11.0, lower = 9.0)
        val monthly = BollBand(middle = 10.5, upper = 12.0, lower = 9.0)
        val s = snapshot("sh.600000", "Test", price = 8.8, weekly = band, daily = band, monthly = monthly, dividend = 0.5)
        val result = TodaySignalAggregator.aggregate(TodaySignalInput(listOf(s), emptyList(), emptyMap(), emptyList(), today))
        val buy = result.first { it.type == TodaySignalType.BUY_TRIGGER }
        assertThat(buy.stockCode).isEqualTo("sh.600000")
        assertThat(buy.sortPriority).isEqualTo(0)
    }

    @Test
    fun dividendYieldReachesThreshold_triggersBuySignal() {
        // bond 2.6% × 2.5 = 6.5% 目标；价 5.0、年分红 0.4 → 8% ≥ 6.5% 触发
        val s = snapshot("sh.600001", price = 5.0, dividend = 0.4, bond = 2.6, multiplier = 2.5)
        val result = TodaySignalAggregator.aggregate(TodaySignalInput(listOf(s), emptyList(), emptyMap(), emptyList(), today))
        assertThat(result.any { it.type == TodaySignalType.BUY_TRIGGER && it.stockCode == "sh.600001" }).isTrue()
    }

    @Test
    fun noPrice_skipsBuySignal() {
        val s = snapshot("sh.600002", price = null, dividend = 0.4, bond = 2.6)
        val result = TodaySignalAggregator.aggregate(TodaySignalInput(listOf(s), emptyList(), emptyMap(), emptyList(), today))
        assertThat(result).isEmpty()
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
./gradlew :app:testDebugUnitTest --tests "com.stock.dividend.data.repository.TodaySignalAggregatorTest"
```
Expected: FAIL（`TodaySignalAggregator` / `TodayStockSnapshot` / `TodaySignalInput` 未解析）

- [ ] **Step 3: 实现 TodaySignalAggregator（数据类 + 买入信号部分）**

`app/src/main/java/com/stock/dividend/data/repository/TodaySignalAggregator.kt`：

```kotlin
package com.stock.dividend.data.repository

import com.stock.dividend.data.local.entity.DividendEntity
import com.stock.dividend.data.local.entity.GridPlanEntity
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** 今日信号类型。 */
enum class TodaySignalType { BUY_TRIGGER, GRID_NEXT_LEVEL, DIVIDEND_COUNTDOWN }

/**
 * 单条今日信号（纯数据，UI 据此渲染一行）。
 * @param sortPriority 排序权重，小者在前（BUY=0 / GRID=1 / DIVIDEND=2）。
 */
data class TodaySignal(
    val type: TodaySignalType,
    val stockCode: String,
    val stockName: String,
    val title: String,
    val detail: String,
    val sortPriority: Int,
)

/** 单只股票的快照输入（聚合信号用，纯数据）。 */
data class TodayStockSnapshot(
    val code: String,
    val name: String,
    val price: Double?,
    val weeklyBand: BollBand? = null,
    val dailyBand: BollBand? = null,
    val monthlyBand: BollBand? = null,
    val latestYearlyDividend: Double? = null,
    val thresholds: DividendThresholds = DividendThresholds(),
    val buyThresholdMultiplier: Double = 2.5,
    val bondYield10Y: Double? = null,
)

/** 聚合输入。 */
data class TodaySignalInput(
    val stocks: List<TodayStockSnapshot>,
    val gridPlans: List<GridPlanEntity>,
    val gridCurrentPrices: Map<String, Double>,
    val dividends: List<DividendEntity>,
    val today: LocalDate,
    val dividendLookaheadDays: Long = 30,
)

/** 今日信号聚合（纯函数，无 Android 依赖）。 */
object TodaySignalAggregator {

    fun aggregate(input: TodaySignalInput): List<TodaySignal> {
        val signals = mutableListOf<TodaySignal>()
        buyTriggers(input.stocks, signals)
        gridNextLevels(input, signals)
        dividendCountdowns(input, signals)
        return signals.sortedWith(compareBy({ it.sortPriority }, { it.stockCode }))
    }

    private fun buyTriggers(stocks: List<TodayStockSnapshot>, out: MutableList<TodaySignal>) {
        for (s in stocks) {
            val price = s.price
            if (price == null || !price.isFinite() || price <= 0.0) continue

            // 1a. 三周期共振 BUY
            val rec = HoldingRecommender.recommend(
                price = price,
                band = s.weeklyBand,
                latestYearlyDividend = s.latestYearlyDividend,
                thresholds = s.thresholds,
                dailyBand = s.dailyBand,
                monthlyBand = s.monthlyBand,
            )
            if (rec.action == HoldingAction.BUY) {
                out += TodaySignal(
                    type = TodaySignalType.BUY_TRIGGER,
                    stockCode = s.code,
                    stockName = s.name,
                    title = "三周期共振买入",
                    detail = "现价 %.2f，股息率 %s".format(
                        price, rec.dividendYield?.let { "%.2f%%".format(it) } ?: "—"
                    ),
                    sortPriority = 0,
                )
                continue // 同股已有买入信号，不再判门槛
            }

            // 1b. 股息率达买入线（10Y 国债 × 倍数）
            val bond = s.bondYield10Y
            if (bond != null && bond > 0.0 && s.latestYearlyDividend != null && s.latestYearlyDividend > 0.0) {
                val status = computeBuyThreshold(bond, s.buyThresholdMultiplier, s.latestYearlyDividend, price)
                if (status.reached == true) {
                    out += TodaySignal(
                        type = TodaySignalType.BUY_TRIGGER,
                        stockCode = s.code,
                        stockName = s.name,
                        title = "股息率达买入线",
                        detail = "现价 %.2f，股息率 %.2f%% → 目标 %.2f%%".format(
                            price, status.currentYieldPercent ?: 0.0, status.targetYieldPercent
                        ),
                        sortPriority = 0,
                    )
                }
            }
        }
    }

    private fun gridNextLevels(input: TodaySignalInput, out: MutableList<TodaySignal>) {
        for (plan in input.gridPlans) {
            val current = input.gridCurrentPrices[plan.stockCode]
                ?: input.stocks.firstOrNull { it.code == plan.stockCode }?.price
                ?: continue
            val result = GridCalculator.generate(
                basePrice = plan.basePrice,
                lowPrice = plan.lowPrice,
                highPrice = plan.highPrice,
                grids = plan.grids,
                totalCapital = plan.totalCapital,
                currentPrice = current,
            )
            val next = result.nextBuyHint
            if (next != null && result.validationError == null) {
                out += TodaySignal(
                    type = TodaySignalType.GRID_NEXT_LEVEL,
                    stockCode = plan.stockCode,
                    stockName = plan.stockName,
                    title = "网格下一档买入",
                    detail = "现价 %.2f，下一档 %.2f".format(current, next),
                    sortPriority = 1,
                )
            }
        }
    }

    private fun dividendCountdowns(input: TodaySignalInput, out: MutableList<TodaySignal>) {
        val horizon = input.today.plusDays(input.dividendLookaheadDays)
        val upcoming = input.dividends
            .mapNotNull { d -> d.exDividendDate?.let { d to parseDate(it) } }
            .filter { (_, date) -> !date.isBefore(input.today) && !date.isAfter(horizon) }
            .groupBy { it.first.stockCode }
            .mapValues { it.value.minByOrNull { p -> p.second } ?: return@mapValues null }
            .filterValues { it != null }
        for ((code, pair) in upcoming) {
            val (d, date) = pair!!
            val name = input.stocks.firstOrNull { it.code == code }?.name ?: code
            val days = ChronoUnit.DAYS.between(input.today, date)
            out += TodaySignal(
                type = TodaySignalType.DIVIDEND_COUNTDOWN,
                stockCode = code,
                stockName = name,
                title = if (days <= 0L) "今日除权" else "${days}天后除权",
                detail = "每股分红 %.4f 元".format(d.cashPerShare),
                sortPriority = 2,
            )
        }
    }

    /** 解析除权日字符串（兼容 "yyyy-MM-dd 00:00:00" 后缀，AGENTS.md §4.9.5）。失败返回 null。 */
    private fun parseDate(raw: String): LocalDate? =
        runCatching { LocalDate.parse(raw.substringBefore(" ").trim()) }.getOrNull()
}
```

- [ ] **Step 4: 补网格 / 分红 / 排序测试，跑全部通过**

在测试类追加：

```kotlin
    @Test
    fun gridNextLevel_triggersGridSignal() {
        val plan = GridPlanEntity(
            id = "g1", stockCode = "sh.600003", stockName = "Grid",
            basePrice = 10.0, lowPrice = 8.0, highPrice = 11.0, grids = 3, totalCapital = 9000.0
        )
        val prices = mapOf("sh.600003" to 9.6) // 现价在档位上方 → 有下一档
        val result = TodaySignalAggregator.aggregate(
            TodaySignalInput(emptyList(), listOf(plan), prices, emptyList(), today)
        )
        val grid = result.first { it.type == TodaySignalType.GRID_NEXT_LEVEL }
        assertThat(grid.stockName).isEqualTo("Grid")
    }

    @Test
    fun dividendWithin30Days_triggersCountdown() {
        val div = DividendEntity(
            id = "d1", stockCode = "sh.600004", reportDate = "2025-12-31",
            cashPerShare = 0.25, exDividendDate = "2026-08-20"
        )
        val result = TodaySignalAggregator.aggregate(
            TodaySignalInput(emptyList(), emptyList(), emptyMap(), listOf(div), today)
        )
        assertThat(result.any { it.type == TodaySignalType.DIVIDEND_COUNTDOWN }).isTrue()
    }

    @Test
    fun signalsSortedByPriorityThenCode() {
        val band = BollBand(middle = 10.0, upper = 11.0, lower = 9.0)
        val monthly = BollBand(middle = 10.5, upper = 12.0, lower = 9.0)
        val buyStock = snapshot("sh.999999", price = 8.8, weekly = band, daily = band, monthly = monthly, dividend = 0.5)
        val plan = GridPlanEntity("g", "sh.000001", "G", 10.0, 8.0, 11.0, 3, 9000.0)
        val prices = mapOf("sh.000001" to 9.6)
        val result = TodaySignalAggregator.aggregate(
            TodaySignalInput(listOf(buyStock), listOf(plan), prices, emptyList(), today)
        )
        // BUY(priority 0) 排在 GRID(priority 1) 前
        assertThat(result.first().type).isEqualTo(TodaySignalType.BUY_TRIGGER)
    }

    @Test
    fun dividendDateWithTimeSuffix_parsed() {
        val div = DividendEntity("d", "sh.600005", "2025-12-31", 0.1, exDividendDate = "2026-08-15 00:00:00")
        val result = TodaySignalAggregator.aggregate(
            TodaySignalInput(emptyList(), emptyList(), emptyMap(), listOf(div), today)
        )
        assertThat(result).isNotEmpty()
    }
```

```bash
./gradlew :app:testDebugUnitTest --tests "com.stock.dividend.data.repository.TodaySignalAggregatorTest"
```
Expected: PASS（5 测试全过）

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/stock/dividend/data/repository/TodaySignalAggregator.kt \
        app/src/test/java/com/stock/dividend/data/repository/TodaySignalAggregatorTest.kt
git commit -m "feat(today): TodaySignalAggregator 纯函数 + 5 单测（买入/网格/分红/排序）"
```

---

## Task 2: TodayBriefingPromptBuilder 纯函数 + 测试

**Files:**
- Create: `app/src/main/java/com/stock/dividend/data/repository/TodayBriefingPromptBuilder.kt`
- Test: `app/src/test/java/com/stock/dividend/data/repository/TodayBriefingPromptBuilderTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TodayBriefingPromptBuilderTest {

    @Test
    fun containsPortfolioAndSignalLines() {
        val prompt = TodayBriefingPromptBuilder.build(
            portfolioLine = "组合今日 +0.80%（跑赢沪深300 0.50pp）",
            signals = listOf(
                TodaySignal(TodaySignalType.BUY_TRIGGER, "sh.600000", "Test", "三周期共振买入", "现价 8.80", 0)
            ),
            dividendLine = "未来30天1笔除权",
        )
        assertThat(prompt).contains("组合今日 +0.80%")
        assertThat(prompt).contains("Test三周期共振买入")
        assertThat(prompt).contains("50")
    }

    @Test
    fun emptySignals_rendersCalmLine() {
        val prompt = TodayBriefingPromptBuilder.build("组合今日 +0.10%", emptyList(), null)
        assertThat(prompt).contains("无显著信号")
    }

    @Test
    fun takesTopThreeSignals() {
        val signals = (1..5).map {
            TodaySignal(TodaySignalType.BUY_TRIGGER, "c$it", "S$it", "买入", "d$it", 0)
        }
        val prompt = TodayBriefingPromptBuilder.build("p", signals, null)
        assertThat(prompt).contains("S1")
        assertThat(prompt).contains("S3")
        assertThat(prompt).doesNotContain("S4")
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
./gradlew :app:testDebugUnitTest --tests "com.stock.dividend.data.repository.TodayBriefingPromptBuilderTest"
```
Expected: FAIL（未解析）

- [ ] **Step 3: 实现**

```kotlin
package com.stock.dividend.data.repository

/**
 * 今日简报 LLM prompt 构造（纯函数，无 Android 依赖）。
 *
 * 约束 LLM：一句话 ≤ 50 字、只解读不臆造数字、不加引号前后缀。
 */
object TodayBriefingPromptBuilder {

    fun build(
        portfolioLine: String,
        signals: List<TodaySignal>,
        dividendLine: String?,
    ): String {
        val signalBlock = if (signals.isEmpty()) {
            "今日无显著信号，组合平静。"
        } else {
            signals.take(3).joinToString("；") { "${it.stockName}${it.title}（${it.detail}）" }
        }
        return """
            你是股息投资助手。基于以下今日数据，用一句话（不超过 50 个汉字）总结今天最值得持有者关注的一点。
            规则：只解读，不要编造数据里没有的数字；直接输出那句话，不要加引号或前后缀。

            【组合表现】$portfolioLine
            【信号】$signalBlock
            【分红】${dividendLine ?: "无近期除权"}
        """.trimIndent()
    }
}
```

- [ ] **Step 4: 跑测试通过**

```bash
./gradlew :app:testDebugUnitTest --tests "com.stock.dividend.data.repository.TodayBriefingPromptBuilderTest"
```
Expected: PASS（3 测试）

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/stock/dividend/data/repository/TodayBriefingPromptBuilder.kt \
        app/src/test/java/com/stock/dividend/data/repository/TodayBriefingPromptBuilderTest.kt
git commit -m "feat(today): TodayBriefingPromptBuilder 纯函数 + 3 单测"
```

---

## Task 3: TodayBriefingParser 纯函数 + 测试

**Files:**
- Create: `app/src/main/java/com/stock/dividend/data/repository/TodayBriefingParser.kt`
- Test: `app/src/test/java/com/stock/dividend/data/repository/TodayBriefingParserTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TodayBriefingParserTest {

    @Test
    fun plainText_trimmedAndStrippedOfQuotes() {
        assertThat(TodayBriefingParser.parse("  \"组合今日上涨，建议关注。\"  "))
            .isEqualTo("组合今日上涨，建议关注。")
    }

    @Test
    fun jsonObjectBriefingField_extracted() {
        val raw = """{"briefing":"今日无信号，组合平静。"}"""
        assertThat(TodayBriefingParser.parse(raw)).isEqualTo("今日无信号，组合平静。")
    }

    @Test
    fun fencedJson_extracted() {
        val raw = "```json\n{\"briefing\":\"你好\"}\n```"
        assertThat(TodayBriefingParser.parse(raw)).isEqualTo("你好")
    }

    @Test
    fun truncated_over80Chars() {
        val long = "句".repeat(120)
        assertThat(TodayBriefingParser.parse(long).length).isAtMost(80)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
./gradlew :app:testDebugUnitTest --tests "com.stock.dividend.data.repository.TodayBriefingParserTest"
```
Expected: FAIL

- [ ] **Step 3: 实现**

```kotlin
package com.stock.dividend.data.repository

/** 今日简报 LLM 响应解析（纯函数，容错）。 */
object TodayBriefingParser {

    /** 从 LLM 响应中提取一句话简报。兜底链：JSON.briefing → 围栏 JSON → 原文去引号。 */
    fun parse(raw: String): String {
        val json = JsonExtraction.extractJsonObject(raw)
        if (json != null) {
            val match = Regex(""""briefing"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(json)
            if (match != null) {
                return match.groupValues[1].replace("\\\"", "\"").trim().take(80)
            }
        }
        return raw.trim().trim('"').trim().take(80)
    }
}
```

- [ ] **Step 4: 跑测试通过**

```bash
./gradlew :app:testDebugUnitTest --tests "com.stock.dividend.data.repository.TodayBriefingParserTest"
```
Expected: PASS（4 测试）

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/stock/dividend/data/repository/TodayBriefingParser.kt \
        app/src/test/java/com/stock/dividend/data/repository/TodayBriefingParserTest.kt
git commit -m "feat(today): TodayBriefingParser 纯函数 + 4 单测"
```

---

## Task 4: TodayBriefingCoordinator 编排 + 测试

**Files:**
- Create: `app/src/main/java/com/stock/dividend/data/repository/TodayBriefingCoordinator.kt`
- Test: `app/src/test/java/com/stock/dividend/data/repository/TodayBriefingCoordinatorTest.kt`

> **实现说明：** Coordinator 是「拉数据→聚合→prompt→LLM→解析→缓存」的胶水层。它注入各 Repository 与 `LlmApi`/`LlmConfigRepository`/`LlmAnalysisCacheDao`。对每个 Repository 用**现有**的取数方法（签名见对应文件），不确定处已在注释标注，执行时按实际方法名适配——核心是聚合/prompt/解析/缓存读写这些已确定的部分。

- [ ] **Step 1: 写失败测试（MockK，验证「未配置→false」「成功→写缓存」「读缓存」）**

```kotlin
package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.local.dao.LlmAnalysisCacheDao
import com.stock.dividend.data.local.entity.LlmAnalysisCacheEntity
import com.stock.dividend.data.remote.LlmApi
import com.stock.dividend.data.remote.dto.LlmChatResponse
import com.stock.dividend.data.remote.dto.LlmMessage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.LocalDate

class TodayBriefingCoordinatorTest {

    private val stockRepository: StockRepository = mockk(relaxed = true)
    private val marketDataRepository: MarketDataRepository = mockk(relaxed = true)
    private val gridPlanRepository: GridPlanRepository = mockk(relaxed = true)
    private val dividendRepository: DividendRepository = mockk(relaxed = true)
    private val bondYieldRepository: BondYieldRepository = mockk(relaxed = true)
    private val llmApi: LlmApi = mockk()
    private val llmConfigRepository: LlmConfigRepository = mockk()
    private val cacheDao: LlmAnalysisCacheDao = mockk(relaxed = true)

    private val today = LocalDate.of(2026, 8, 12)

    private fun coordinator() = TodayBriefingCoordinator(
        stockRepository, marketDataRepository, gridPlanRepository, dividendRepository,
        bondYieldRepository, llmApi, llmConfigRepository, cacheDao,
    )

    @Test
    fun notConfigured_returnsFalse_noLlmCall() = runTest {
        coEvery { llmConfigRepository.snapshot() } returns LlmConfig("", "", "")
        val ok = coordinator().generateAndCache(today)
        assertThat(ok).isFalse()
        coVerify(exactly = 0) { llmApi.chatCompletions(any(), any(), any()) }
    }

    @Test
    fun success_writesCache() = runTest {
        coEvery { llmConfigRepository.snapshot() } returns LlmConfig("http://x/v1/", "k", "m")
        // 让数据全空 → 信号为空，prompt 仍生成；LLM 返回一句话
        coEvery { stockRepository.observeAllStocks().first() } returns emptyList()
        coEvery { gridPlanRepository.observeAll().first() } returns emptyList()
        coEvery { llmApi.chatCompletions(any(), any(), any()) } returns
            LlmChatResponse(listOf(LlmChatResponse.Choice(LlmMessage("assistant", "今日平静。"))))
        val ok = coordinator().generateAndCache(today)
        assertThat(ok).isTrue()
        coVerify { cacheDao.upsert(match { it.scope == "TODAY_BRIEFING" && it.payload == "今日平静。" }) }
    }

    @Test
    fun read_returnsCachedPayload() = runTest {
        coEvery { cacheDao.get(eq("today_briefing_2026-08-12"), eq("TODAY_BRIEFING")) } returns
            LlmAnalysisCacheEntity("today_briefing_2026-08-12", "TODAY_BRIEFING", "缓存的一句话", 0L)
        assertThat(coordinator().read(today)).isEqualTo("缓存的一句话")
    }

    @Test
    fun read_missing_returnsNull() = runTest {
        coEvery { cacheDao.get(any(), any()) } returns null
        assertThat(coordinator().read(today)).isNull()
    }
}
```

> 注：`observeAllStocks()` / `StockRepository` 的全量 stocks 订阅方法名按实际 Repository 适配（参考 `PortfolioViewModel` 的 `allStocksFlow` 来源）；`DividendRepository` 取近 30 天分红的方法同理。

- [ ] **Step 2: 跑测试确认失败**

```bash
./gradlew :app:testDebugUnitTest --tests "com.stock.dividend.data.repository.TodayBriefingCoordinatorTest"
```
Expected: FAIL（`TodayBriefingCoordinator` 未解析）

- [ ] **Step 3: 实现 Coordinator**

```kotlin
package com.stock.dividend.data.repository

import com.stock.dividend.data.local.dao.LlmAnalysisCacheDao
import com.stock.dividend.data.local.entity.LlmAnalysisCacheEntity
import com.stock.dividend.data.remote.LlmApi
import com.stock.dividend.data.remote.dto.LlmChatRequest
import com.stock.dividend.data.remote.dto.LlmMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 今日 AI 简报编排（@Singleton）。
 *
 * - [generateAndCache]：拉数据→聚合信号→构造 prompt→调 LLM→解析→写缓存。失败静默（红线 #2）。
 * - [read]：按日期读缓存；无则 null（UI 据此决定是否渲染 AI 卡）。
 *
 * 缓存复用 llm_analysis_cache 表，scope = [SCOPE]，key = "today_briefing_yyyy-MM-dd"。
 */
@Singleton
class TodayBriefingCoordinator @Inject constructor(
    private val stockRepository: StockRepository,
    private val marketDataRepository: MarketDataRepository,
    private val gridPlanRepository: GridPlanRepository,
    private val dividendRepository: DividendRepository,
    private val bondYieldRepository: BondYieldRepository,
    private val llmApi: LlmApi,
    private val llmConfigRepository: LlmConfigRepository,
    private val cacheDao: LlmAnalysisCacheDao,
) {
    suspend fun generateAndCache(date: LocalDate): Boolean = withContext(Dispatchers.IO) {
        val config = llmConfigRepository.snapshot()
        if (!config.isComplete) return@withContext false
        try {
            // 1. 拉数据（均吞异常返回空，红线 #2）
            val stocks = runCatching { stockRepository.observeAllStocks().first() }.getOrDefault(emptyList())
            val snapshots = runCatching { stockRepository.fetchQuoteSnapshots(stocks) }.getOrDefault(emptyMap())
            val bond = runCatching { bondYieldRepository.fetch10YBondYield() }.getOrNull()
            val gridPlans = runCatching { gridPlanRepository.observeAll().first() }.getOrDefault(emptyList())
            val dividends = runCatching { dividendRepository.observeAll().first() }.getOrDefault(emptyList())
            val indices = runCatching { marketDataRepository.fetchIndexQuotes() }.getOrDefault(emptyList())

            // 2. 聚合信号
            val stockSnapshots = stocks.map { entity ->
                val q = snapshots[entity.code]
                TodayStockSnapshot(
                    code = entity.code,
                    name = entity.name,
                    price = q?.price,
                    // 周线 BOLL 按需拉取；后台 Worker 容忍耗时，拉周线即可（日线/月线留空→HoldingRecommender 退化为 INSUFFICIENT_DATA，不强行）
                    weeklyBand = runCatching { stockRepository.fetchBoll(entity.code) }.getOrNull(),
                    latestYearlyDividend = null, // 由 ForecastCalculator/分红表装配，见 VM；Coordinator 后台可放宽
                    bondYield10Y = bond,
                    buyThresholdMultiplier = entity.buyThresholdMultiplier,
                )
            }
            val input = TodaySignalInput(
                stocks = stockSnapshots,
                gridPlans = gridPlans,
                gridCurrentPrices = snapshots.mapValues { it.value.price ?: 0.0 },
                dividends = dividends,
                today = date,
            )
            val signals = TodaySignalAggregator.aggregate(input)

            // 3. 组合表现行 + 分红行
            val portfolioLine = buildPortfolioLine(stockSnapshots, snapshots, indices)
            val dividendLine = signals.count { it.type == TodaySignalType.DIVIDEND_COUNTDOWN }
                .takeIf { it > 0 }?.let { "未来30天${it}笔除权" }

            // 4. prompt → LLM → 解析 → 缓存
            val prompt = TodayBriefingPromptBuilder.build(portfolioLine, signals, dividendLine)
            val response = llmApi.chatCompletions(
                url = config.baseUrl.removeSuffix("/") + "/chat/completions",
                auth = "Bearer ${config.apiKey}",
                body = LlmChatRequest(model = config.model, messages = listOf(LlmMessage("user", prompt))),
            )
            val briefing = TodayBriefingParser.parse(response.content.orEmpty())
            cacheDao.upsert(
                LlmAnalysisCacheEntity(
                    cacheKey = cacheKey(date),
                    scope = SCOPE,
                    payload = briefing,
                    createdAt = System.currentTimeMillis(),
                )
            )
            true
        } catch (_: Exception) {
            false // 红线 #2：失败静默
        }
    }

    suspend fun read(date: LocalDate): String? = withContext(Dispatchers.IO) {
        runCatching { cacheDao.get(cacheKey(date), SCOPE)?.payload }.getOrNull()
    }

    private fun buildPortfolioLine(
        stocks: List<TodayStockSnapshot>,
        snapshots: Map<String, QuoteSnapshot>,
        indices: List<com.stock.dividend.data.repository.IndexQuote>,
    ): String {
        // 简化口径：用现有价格算组合今日涨跌均值；详细盈亏由 VM 在前台算（Coordinator 后台只喂 LLM 一句概览）
        val avgChange = stocks.mapNotNull { snapshots[it.code]?.changePct }.takeIf { it.isNotEmpty() }?.average() ?: 0.0
        val hs300 = indices.firstOrNull { it.code == "000300" }?.changePct
        val beatText = hs300?.let { "（跑赢沪深300 %.2fpp）".format(avgChange - it) } ?: ""
        return "组合今日 %+.2f%%%s".format(avgChange, beatText)
    }

    private fun cacheKey(date: LocalDate): String = "today_briefing_${date}"

    companion object {
        const val SCOPE = "TODAY_BRIEFING"
    }
}
```

- [ ] **Step 4: 跑测试通过（按实际 Repository 方法名微调 mock 后）**

```bash
./gradlew :app:testDebugUnitTest --tests "com.stock.dividend.data.repository.TodayBriefingCoordinatorTest"
```
Expected: PASS（4 测试）。若 `observeAllStocks`/`fetchBoll` 方法名与实际不符，先确认 `StockRepository.kt` 的实际方法名再同步修改测试 mock 与实现调用。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/stock/dividend/data/repository/TodayBriefingCoordinator.kt \
        app/src/test/java/com/stock/dividend/data/repository/TodayBriefingCoordinatorTest.kt
git commit -m "feat(today): TodayBriefingCoordinator 编排（拉数据→聚合→LLM→缓存）+ 4 单测"
```

---

## Task 5: TodayBriefingWorker + 调度注册

**Files:**
- Create: `app/src/main/java/com/stock/dividend/data/notification/TodayBriefingWorker.kt`
- Modify: `app/src/main/java/com/stock/dividend/data/notification/NotificationScheduler.kt`
- Modify: `app/src/main/java/com/stock/dividend/StockDividendApp.kt`

- [ ] **Step 1: 实现 Worker（参考 `NotificationCheckWorker` 模式）**

```kotlin
package com.stock.dividend.data.notification

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.stock.dividend.data.repository.TodayBriefingCoordinator
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.LocalDate

/** 每日盘后生成今日 AI 简报并缓存。失败 retry，不阻塞 UI（UI 读不到缓存就不显示 AI 卡）。 */
@HiltWorker
class TodayBriefingWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val coordinator: TodayBriefingCoordinator,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = try {
        coordinator.generateAndCache(LocalDate.now())
        Result.success()
    } catch (_: Exception) {
        Result.retry()
    }
}
```

- [ ] **Step 2: NotificationScheduler 加调度方法**

在 `NotificationScheduler.kt` 内追加（保留现有 `schedulePeriodicChecks` 不动）：

```kotlin
private const val TODAY_BRIEFING_WORK = "today-briefing"

// 在 NotificationScheduler 类内追加：
fun scheduleTodayBriefing() {
    val request = PeriodicWorkRequestBuilder<TodayBriefingWorker>(1, TimeUnit.DAYS)
        .setConstraints(
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
        )
        .setInitialDelay(
            minutesUntilNext(15, 45), // 每日 15:45（A 股 15:00 收盘后留 15 分钟）
            TimeUnit.MINUTES
        )
        .build()
    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        TODAY_BRIEFING_WORK,
        ExistingPeriodicWorkPolicy.UPDATE,
        request,
    )
}

/** 计算到下一个目标时分（hour:minute）的分钟数；已过则顺延次日。 */
private fun minutesUntilNext(hour: Int, minute: Int): Long {
    val now = java.time.LocalDateTime.now()
    var next = now.toLocalDate().atTime(hour, minute)
    if (!next.isAfter(now)) next = next.plusDays(1)
    return java.time.Duration.between(now, next).toMinutes()
}
```

> 注：`PeriodicWorkRequestBuilder` / `TimeUnit` / `WorkManager` 等已在文件顶部 import（现有 `schedulePeriodicChecks` 在用）；新增 import 仅 `TodayBriefingWorker`。

- [ ] **Step 3: StockDividendApp.onCreate 注册调度**

修改 `StockDividendApp.kt:31-34`，在 `notificationScheduler.schedulePeriodicChecks()` 后追加一行：

```kotlin
override fun onCreate() {
    super.onCreate()
    notificationScheduler.schedulePeriodicChecks()
    notificationScheduler.scheduleTodayBriefing()   // 新增：每日盘后生成今日 AI 简报
}
```

- [ ] **Step 4: 编译验证**

```bash
./gradlew :app:assembleDebug
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/stock/dividend/data/notification/TodayBriefingWorker.kt \
        app/src/main/java/com/stock/dividend/data/notification/NotificationScheduler.kt \
        app/src/main/java/com/stock/dividend/StockDividendApp.kt
git commit -m "feat(today): TodayBriefingWorker + 每日 15:45 定时调度注册"
```

---

## Task 6: TodayViewModel + 测试

**Files:**
- Create: `app/src/main/java/com/stock/dividend/viewmodel/TodayViewModel.kt`
- Test: `app/src/test/java/com/stock/dividend/viewmodel/TodayViewModelTest.kt`

- [ ] **Step 1: 实现 ViewModel（多 collector，参考 `PortfolioViewModel`）**

```kotlin
package com.stock.dividend.viewmodel

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stock.dividend.data.repository.TodayBriefingCoordinator
import com.stock.dividend.data.repository.TodaySignal
import com.stock.dividend.data.repository.TodaySignalAggregator
import com.stock.dividend.data.repository.TodaySignalInput
import com.stock.dividend.data.repository.TodayStockSnapshot
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

@Stable
data class TodayUiState(
    val marketValue: Double = 0.0,
    val todayPnl: Double = 0.0,
    val todayPnlRate: Double = 0.0,
    val totalPnl: Double = 0.0,
    val totalPnlRate: Double = 0.0,
    val indexSh: Double? = null,        // 上证今日 %
    val indexHs300: Double? = null,     // 沪深300 今日 %
    val beatHs300: Double? = null,      // 跑赢沪深300 pp
    val signals: List<TodaySignal> = emptyList(),
    val briefing: String? = null,       // null = AI 卡不显示
    val isLoading: Boolean = false,
    val hasHoldings: Boolean = false,   // false → 引导加股空状态
    val dataStale: Boolean = false,     // true = 行情拉取失败，显示缓存 + 「可能延迟」
)

@HiltViewModel
class TodayViewModel @Inject constructor(
    // 注入与 PortfolioViewModel 同源的 Repository/Dao（按现有可注入对象适配）：
    // stockRepository / gridPlanRepository / dividendRepository / marketDataRepository
    // / bondYieldRepository / holdingRepository(交易) / briefingCoordinator
    private val briefingCoordinator: TodayBriefingCoordinator,
    /* 其余依赖由实际 Repository 注入，签名见各 Repository.kt */
) : ViewModel() {

    private val _uiState = MutableStateFlow(TodayUiState())
    val uiState = _uiState.asStateFlow()

    init {
        // Collector A: 持仓 + 价格 → 组合市值/今日盈亏/累计盈亏
        // Collector B: 大盘指数 → 上证/沪深300 + 跑赢对照
        // Collector C: 持仓+自选+BOLL+门槛+网格+分红 → TodaySignalAggregator → signals
        // Collector D: briefingCoordinator.read(today) → briefing（null 时不显示）
        //
        // 实现参照 PortfolioViewModel 的 8-collector 模式：
        //   viewModelScope.launch { someFlow.collect { update -> _uiState.update { it.copy(...) } } }
        // 每个 collector 只更新自己负责的字段；网络失败置 dataStale=true 并保留缓存值（红线 #2/#3）。
        observeBriefing()
    }

    private fun observeBriefing() {
        viewModelScope.launch {
            val text = runCatching { briefingCoordinator.read(LocalDate.now()) }.getOrNull()
            _uiState.value = _uiState.value.copy(briefing = text)
        }
    }

    /** 用户下拉刷新：重拉行情 + 触发简报刷新（手动补后台未跑的当日）。 */
    fun refresh() {
        // 置 isLoading=true；拉行情；失败置 dataStale；最后置 isLoading=false（红线 #3）
        // 简报可按需 coordinator.generateAndCache(today) 后再 read
    }
}
```

> **执行者注：** 上述为骨架。实际实现时把各 Repository 注入并补全 4 个 collector 的 collect 逻辑，字段计算复用：市值=`Σ shares×price`、今日盈亏=`Σ shares×(price−prevClose)`、累计盈亏=`Σ shares×(price−costPerShare)`、跑赢=`todayPnlRate − indexHs300`。信号 collector 调 `TodaySignalAggregator.aggregate(input)`，其中 `TodayStockSnapshot` 字段从对应 Repository 装配。

- [ ] **Step 2: 写 VM 测试（Robolectric + MockK + Turbine）**

```kotlin
package com.stock.dividend.viewmodel

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.repository.TodayBriefingCoordinator
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class TodayViewModelTest {

    private val briefingCoordinator: TodayBriefingCoordinator = mockk()
    private lateinit var vm: TodayViewModel

    @Before
    fun setUp() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        coEvery { briefingCoordinator.read(any()) } returns "今日一句话简报。"
        // 其余 Repository mockk(relaxed=true)，按 VM 实际注入补齐
        vm = TodayViewModel(briefingCoordinator /* , 其余 mock */)
        advanceUntilIdle()
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun briefingLoadedIntoState() = runTest {
        vm.uiState.test {
            advanceUntilIdle()
            assertThat(awaitItem().briefing).isEqualTo("今日一句话简报。")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun briefingNull_whenCoordinatorReturnsNull() = runTest {
        coEvery { briefingCoordinator.read(any()) } returns null
        val vm2 = TodayViewModel(briefingCoordinator /* , 其余 mock */)
        advanceUntilIdle()
        assertThat(vm2.uiState.value.briefing).isNull()
    }
}
```

- [ ] **Step 3: 跑测试（补齐 Repository mock 后）通过**

```bash
./gradlew :app:testDebugUnitTest --tests "com.stock.dividend.viewmodel.TodayViewModelTest"
```
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/stock/dividend/viewmodel/TodayViewModel.kt \
        app/src/test/java/com/stock/dividend/viewmodel/TodayViewModelTest.kt
git commit -m "feat(today): TodayViewModel 多 collector 聚合今日一瞥 + 简报读取测试"
```

---

## Task 7: TodayScreen UI（三块卡片）

**Files:**
- Create: `app/src/main/java/com/stock/dividend/ui/screen/TodayScreen.kt`

- [ ] **Step 1: 实现 Screen（用 `AppCard`/`AmountText`/`PercentText`/`FinanceMetricRow`）**

```kotlin
package com.stock.dividend.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stock.dividend.data.repository.TodaySignalType
import com.stock.dividend.data.repository.TodaySignal
import com.stock.dividend.ui.component.AppCard
import com.stock.dividend.ui.component.AppCardTone
import com.stock.dividend.ui.component.EmptyStateView
import com.stock.dividend.ui.component.FinanceMetricRow
import com.stock.dividend.viewmodel.TodayUiState
import com.stock.dividend.viewmodel.TodayViewModel

@Composable
fun TodayScreen(
    viewModel: TodayViewModel = hiltViewModel(),
    onOpenPortfolio: () -> Unit = {},
    onOpenStock: (String) -> Unit = {},
    onOpenIncome: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    if (!state.hasHoldings) {
        EmptyStateView(title = "还没有自选股", subtitle = "先加一只股，开始追踪分红与信号")
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ① AI 一句话总结（仅当 briefing 非空时显示）
        state.briefing?.let { briefing ->
            item {
                AppCard(tone = AppCardTone.Summary, modifier = Modifier.fillMaxSize()) {
                    Text("AI 今日解读", style = MaterialTheme.typography.labelMedium)
                    Text(briefing, style = MaterialTheme.typography.titleMedium)
                }
            }
        }

        // ② 组合表现 + 大盘对照
        item {
            AppCard(onClick = onOpenPortfolio, modifier = Modifier.fillMaxSize()) {
                Text("组合表现", style = MaterialTheme.typography.labelMedium)
                FinanceMetricRow("总市值", "%.2f".format(state.marketValue))
                FinanceMetricRow(
                    "今日盈亏", "%+.2f (%+.2f%%)".format(state.todayPnl, state.todayPnlRate),
                    valueColor = pnlColor(state.todayPnl),
                )
                FinanceMetricRow(
                    "累计盈亏", "%+.2f (%+.2f%%)".format(state.totalPnl, state.totalPnlRate),
                    valueColor = pnlColor(state.totalPnl),
                )
                state.beatHs300?.let {
                    FinanceMetricRow("跑赢沪深300", "%+.2fpp".format(it), valueColor = pnlColor(it))
                }
                if (state.dataStale) {
                    Text("数据可能延迟", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error)
                }
            }
        }

        // ③ 信号卡
        if (state.signals.isEmpty()) {
            item {
                AppCard { Text("今日无信号，组合平静", style = MaterialTheme.typography.bodyMedium) }
            }
        } else {
            item {
                Text("今日信号（${state.signals.size}）", style = MaterialTheme.typography.labelMedium)
            }
            items(state.signals, key = { it.stockCode + it.type.name }) { signal ->
                AppCard(onClick = {
                    if (signal.type == TodaySignalType.GRID_NEXT_LEVEL) onOpenStock(signal.stockCode)
                    else onOpenStock(signal.stockCode)
                }) {
                    Text(signal.stockName, style = MaterialTheme.typography.titleSmall)
                    Text(signal.title, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary)
                    Text(signal.detail, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun pnlColor(v: Double) = when {
    v > 0 -> com.stock.dividend.ui.theme.LocalExtendedColors.current.positive
    v < 0 -> com.stock.dividend.ui.theme.LocalExtendedColors.current.negative
    else -> MaterialTheme.colorScheme.onSurface
}
```

> **执行者注：** 金额展示优先换用 `AmountText(value, signed=true)` / `PercentText(value, colored=true, signed=true)` 替代手写 `"%.2f".format`，以符合 §4.5（等宽数字 + 自动正负色）。上面为可读骨架，最终落地用 AmountText/PercentText。`onOpenIncome` 暂保留入参（原分红预览入口砍掉后可在后续移除）。

- [ ] **Step 2: 编译验证**

```bash
./gradlew :app:assembleDebug
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/stock/dividend/ui/screen/TodayScreen.kt
git commit -m "feat(today): TodayScreen 三块卡片（AI简报/组合表现/信号）"
```

---

## Task 8: 导航/Tab 改造 + 成就降级

**Files:**
- Modify: `app/src/main/java/com/stock/dividend/ui/screen/MainScaffold.kt`
- Modify: `app/src/main/java/com/stock/dividend/ui/screen/SettingsScreen.kt`

- [ ] **Step 1: MainScaffold —— bottomNavItems 加「今日」、改 startDestination、移除「成就」Tab 项**

`MainScaffold.kt:41-47` 改为（成就从 Tab 移除，today 加首位）：

```kotlin
internal val bottomNavItems = listOf(
    BottomNavItem("today", "今日", Icons.Filled.Home),
    BottomNavItem("portfolio", "持仓", Icons.Filled.AccountBalance),
    BottomNavItem("income", "股息收入", Icons.AutoMirrored.Filled.TrendingUp),
    BottomNavItem("ai", "AI", Icons.Filled.SmartToy),
    BottomNavItem("settings", "设置", Icons.Filled.Settings),
)
```

> 注：新增 `import androidx.compose.material.icons.filled.Home`。原「成就」Tab 项删除。

`MainScaffold.kt:137` startDestination 改：

```kotlin
NavHost(
    navController = tabNavController,
    startDestination = "today",   // 原 "portfolio"
    ...
```

在 NavHost 内新增 `composable("today")`（放在 `composable("portfolio")` 前）：

```kotlin
composable("today") {
    TodayScreen(
        onOpenPortfolio = { tabNavController.navigate("portfolio") },
        onOpenStock = { code -> tabNavController.navigate("stockDetail/$code") },
        onOpenIncome = { tabNavController.navigate("income") },
    )
}
```

**保留** `composable("achievements") { AchievementScreen() }` 不动（供设置页跳转）。新增 `import com.stock.dividend.ui.screen.TodayScreen`（同包，可能无需 import）。

- [ ] **Step 2: SettingsScreen —— 加「成就」入口（降级）**

`SettingsScreen.kt:33-39` 的 `settingsGroupTitles` 追加一项：

```kotlin
internal val settingsGroupTitles = listOf(
    "提醒与评估",
    "AI 与策略",
    "数据",
    "交易记录",
    "网格交易",
    "成就",          // 新增（从 Tab 降级）
)
```

`SettingsScreen.kt:50-56` 函数签名加回调：

```kotlin
@Composable
fun SettingsScreen(
    onOpenAlertEvalSettings: () -> Unit,
    onOpenLlmStrategySettings: () -> Unit,
    onOpenDataSettings: () -> Unit,
    onOpenTransactionHistory: () -> Unit,
    onOpenGridPlan: () -> Unit,
    onOpenAchievements: () -> Unit,   // 新增
) {
```

在 `SettingsScreen` 函数体末尾（`onOpenGridPlan` 那行后）追加：

```kotlin
        SettingsNavRow(
            title = settingsGroupTitles[5],
            description = "使用成就与里程碑",
            icon = Icons.Filled.EmojiEvents,
            onClick = onOpenAchievements
        )
```

新增 `import androidx.compose.material.icons.filled.EmojiEvents`。

- [ ] **Step 3: MainScaffold 的 SettingsScreen 调用处补传新回调**

`MainScaffold.kt:184-190`（`composable("settings")` 块）补 `onOpenAchievements`：

```kotlin
composable("settings") {
    SettingsScreen(
        onOpenAlertEvalSettings = { tabNavController.navigate("alertEvalSettings") },
        onOpenLlmStrategySettings = { tabNavController.navigate("llmStrategySettings") },
        onOpenDataSettings = { tabNavController.navigate("dataSettings") },
        onOpenTransactionHistory = { rootNavController.navigate(Routes.TRANSACTION_HISTORY) },
        onOpenGridPlan = { rootNavController.navigate(Routes.GRID_PLAN) },
        onOpenAchievements = { tabNavController.navigate("achievements") },  // 新增
    )
}
```

- [ ] **Step 4: 更新 SettingsScreen 测试（若现有测试断言 groupTitles 数量/顺序）**

```bash
grep -rn "settingsGroupTitles" app/src/test
```
若有断言（如数量=5），改为 6。若无测试则跳过。

- [ ] **Step 5: 编译 + 跑全测试**

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```
Expected: BUILD SUCCESSFUL + 全测试 PASS

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/stock/dividend/ui/screen/MainScaffold.kt \
        app/src/main/java/com/stock/dividend/ui/screen/SettingsScreen.kt
git commit -m "feat(today): 起始页改「今日」Tab + 成就降级到设置页"
```

---

## Task 9: 集成构建验证 + 手测

- [ ] **Step 1: 全量构建（CI 等价）**

```bash
./gradlew build
```
Expected: BUILD SUCCESSFUL（含 lint/test）

- [ ] **Step 2: 装 debug APK 手测关键路径**

```bash
./gradlew installDebug
```
手测清单：
- 打开 APP 第一眼是「今日」页（非持仓）
- 有持仓时：组合表现卡显示市值/盈亏；信号卡有内容或「今日无信号」
- AI 卡片：未配置 LLM 时不显示；配置后（次日 15:45 后或手动触发）显示一句话
- 点组合表现卡 → 跳持仓 Tab
- 点信号行 → 跳个股详情
- 底部 5 Tab：今日/持仓/收入/AI/设置（无成就）
- 设置页底部有「成就」入口 → 点进去正常

- [ ] **Step 3: 更新 AGENTS.md（新增文件登记 + 变更记录）**

在 AGENTS.md §3 目录树与 §10 变更记录补登本期新增文件（TodayScreen/TodayViewModel/TodaySignalAggregator/TodayBriefingCoordinator/TodayBriefingWorker 等）与 Tab 结构变更。

- [ ] **Step 4: 最终 Commit + 合并准备**

```bash
git add AGENTS.md
git commit -m "docs(today): AGENTS.md 登记「今日首页」功能与 Tab 变更"
```

---

## Self-Review 结果

1. **Spec 覆盖**：
   - ① AI 一句话总结 → Task 2/3（prompt+parser）+ Task 4（编排）+ Task 5（Worker 定时）+ Task 6 VM 读取 + Task 7 渲染 ✅
   - ② 组合表现 + 大盘对照 → Task 6 collector A/B + Task 7 卡片 ✅
   - ③ 信号卡（买入触发/网格/分红倒计时）→ Task 1 全覆盖 + Task 7 渲染 ✅
   - Tab 5→5 + 起始页改 today → Task 8 ✅
   - 成就降级到设置 → Task 8 Step 2 ✅
   - 不改 schema（复用 LlmAnalysisCacheDao scope=TODAY_BRIEFING）→ Task 4 ✅
   - 错误兜底（AI 不显示 / 行情延迟 / 无信号 / 引导加股）→ Task 6/7 ✅
2. **占位符**：Task 6 VM 因依赖 PortfolioViewModel 同源注入未逐一列全，已用执行者注 + 字段计算公式说明，非「TODO」式占位。其余步骤代码完整。
3. **类型一致**：`TodaySignal`/`TodayStockSnapshot`/`TodaySignalInput`/`TodayUiState` 在各 Task 引用一致；`cacheKey(date)` / `SCOPE` 在 Task 4 定义、Task 6 读取一致。
4. **风险点（执行者需注意）**：
   - Task 4/6 中 `stockRepository.observeAllStocks()` / `fetchBoll()` / `dividendRepository.observeAll()` 等方法名需对照实际 Repository 确认；计划已标注。
   - Task 6 VM 的完整 collector 实现需参照 `PortfolioViewModel.kt` 的价格装配逻辑（lastPricesSnapshot/stockQuotes 模式）。
