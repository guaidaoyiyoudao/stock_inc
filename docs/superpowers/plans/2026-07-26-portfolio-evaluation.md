# 一键评估持仓 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在持仓页加"一键评估"入口，对当前筛选后可见的持仓股，根据周线 BOLL 位置 + 用户可调股息率门槛给出"买/卖/持有/数据不足"建议，并在独立结果页展示可解释的理由。

**Architecture:** 把 `BollPriceScale.kt` 里现有的 `private bollTone()` 提取成纯函数 `HoldingRecommender.recommend(...)`，加上股息率软门槛参数；`PortfolioViewModel` 加批量评估方法（复用已有 `stockBands`/`stockForecasts` 缓存）；新增独立 Compose 结果页 + NavHost 路由；门槛通过复用 `notification_rules` 表的两个新 type 持久化，无需 DB 迁移。

**Tech Stack:** Kotlin 2.0.21, Jetpack Compose, Material 3, Hilt, Room (Flow), Coroutines + StateFlow. 测试: JUnit4 + Truth + MockK + kotlinx-coroutines-test。

**Spec:** `docs/superpowers/specs/2026-07-26-portfolio-evaluation-design.md`

---

## File Structure

**新增文件:**
- `app/src/main/java/com/stock/dividend/data/repository/HoldingRecommender.kt` — 纯函数 + 数据类（`HoldingAction`, `DividendThresholds`, `HoldingRecommendation`, `BollTone`）。无 Android 依赖。
- `app/src/main/java/com/stock/dividend/ui/screen/PortfolioEvaluationScreen.kt` — 结果页 Composable。
- `app/src/test/java/com/stock/dividend/data/repository/HoldingRecommenderTest.kt` — 纯函数测试。

**修改文件:**
- `app/src/main/java/com/stock/dividend/ui/component/BollPriceScale.kt` — 删除本地 `BollTone` 与 `bollTone()`，改 import 并调用 `HoldingRecommender` 的共享版本。
- `app/src/main/java/com/stock/dividend/data/local/entity/NotificationRuleEntity.kt` — 加两个 type 常量。
- `app/src/main/java/com/stock/dividend/data/repository/NotificationRuleRepository.kt` — 加 `observeEvalThresholds()` / `saveEvalThresholds()`。
- `app/src/main/java/com/stock/dividend/viewmodel/PortfolioViewModel.kt` — 加 `EvaluatedStock`、评估状态字段、`evaluateVisibleHoldings()`、门槛 Flow 收集、构造器注入 `NotificationRuleRepository`。
- `app/src/main/java/com/stock/dividend/ui/screen/PortfolioScreen.kt` — 标题栏加"一键评估" TextButton + `onNavigateToEvaluation` 回调参数。
- `app/src/main/java/com/stock/dividend/ui/screen/NotificationSettingsScreen.kt` — SettingsScreen 加"评估门槛"编辑区。
- `app/src/main/java/com/stock/dividend/viewmodel/NotificationSettingsViewModel.kt` — 加评估门槛状态字段与 save/load。
- `app/src/main/java/com/stock/dividend/ui/screen/MainScaffold.kt` — NavHost 加 `portfolioEvaluation` 路由，共享 PortfolioViewModel。
- `app/src/test/java/com/stock/dividend/data/repository/NotificationRuleRepositoryTest.kt` — 加 eval threshold 用例。
- `app/src/test/java/com/stock/dividend/viewmodel/PortfolioViewModelTest.kt` — 加构造器参数 + 评估用例。

**不动:** `BollCalculator.kt`, `KlineRepository.kt`, `StockRepository.fetchBoll`, `AppDatabase` schema (无迁移)。

---

## Test Commands

单元测试（不需设备）:
```bash
./gradlew :app:testDebugUnitTest --tests "<FQN 或通配>"
# 例: 跑 HoldingRecommender 全部用例
./gradlew :app:testDebugUnitTest --tests "com.stock.dividend.data.repository.HoldingRecommenderTest"
# 跑某个 ViewModel 用例
./gradlew :app:testDebugUnitTest --tests "com.stock.dividend.viewmodel.PortfolioViewModelTest.evaluateVisibleHoldings*"
```

跑全部单元测试（编译检查）:
```bash
./gradlew :app:testDebugUnitTest
```

只编译，不跑测试（快速验证改动是否过编译）:
```bash
./gradlew :app:compileDebugKotlin
```

---

## Task 1: 提取 HoldingRecommender 纯函数（含 BollTone）

**目标:** 把 `BollPriceScale.kt` 里 `private enum BollTone` 和 `private fun bollTone()` 提取成共享的纯函数模块，供评估逻辑和 BollPriceScale 共用。本任务**只迁移现有逻辑**，不改判断规则——评估门槛在 Task 2 加。

**Files:**
- Create: `app/src/main/java/com/stock/dividend/data/repository/HoldingRecommender.kt`
- Modify: `app/src/main/java/com/stock/dividend/ui/component/BollPriceScale.kt:157,218-228`
- Test: `app/src/test/java/com/stock/dividend/data/repository/HoldingRecommenderTest.kt`

- [ ] **Step 1: 写 `HoldingRecommender.kt`（先只放共享元素 + 占位 recommend）**

Create `app/src/main/java/com/stock/dividend/data/repository/HoldingRecommender.kt`:

```kotlin
package com.stock.dividend.data.repository

import kotlin.math.abs

/**
 * 周线 BOLL 带 → 价位 tone。与 [com.stock.dividend.ui.component.BollPriceScale] 共用，
 * 保证评估逻辑和卡片渲染用同一套判断。
 */
enum class BollTone { Buy, Current, Sell }

/**
 * 评估最终建议动作。
 * - [BUY] / [SELL] / [HOLD]：基于 boll 位置 + 股息率门槛得出的结论；
 * - [INSUFFICIENT_DATA]：boll 数据或现价无效，无法评估。
 */
enum class HoldingAction { BUY, SELL, HOLD, INSUFFICIENT_DATA }

/**
 * 评估用的股息率门槛（百分比）。
 * @param minYieldPercent 低于此值时不给"买"（即使 boll 在下轨）。
 * @param boostYieldPercent 高于此值时把中轨附近的"持有"上调为"买"。
 */
data class DividendThresholds(
    val minYieldPercent: Double = DEFAULT_MIN_YIELD,
    val boostYieldPercent: Double = DEFAULT_BOOST_YIELD
) {
    companion object {
        const val DEFAULT_MIN_YIELD = 2.0
        const val DEFAULT_BOOST_YIELD = 5.0
    }
}

/**
 * 单股评估结果（纯数据，UI 据此渲染）。
 */
data class HoldingRecommendation(
    val action: HoldingAction,
    val bollTone: BollTone,
    /** (price - lower) / (upper - lower)：0=下轨, 1=上轨。band 无效时为 NaN。 */
    val priceVsLower: Double,
    /** 股息率 %；latestYearlyDividend 或 price 无效时为 null。 */
    val dividendYield: Double?,
    /** 人话理由（每条 < 30 字），供结果页直接展示。 */
    val reasons: List<String>
)

/**
 * 持仓评估纯函数（无 Android 依赖）。
 *
 * 决策步骤：
 * 1. band/price 无效 → [HoldingAction.INSUFFICIENT_DATA]；
 * 2. 基础 tone 由 [bollTone] 决定（沿用 BollPriceScale 既有逻辑）；
 * 3. 股息率软门槛（仅当 latestYearlyDividend 非空时应用）：
 *    - tone=Buy 且 yield < minYield → 降级 HOLD；
 *    - tone=Current 且 yield ≥ boostYield → 升级 BUY；
 *    - SELL 不受股息率影响。
 */
object HoldingRecommender {

    fun recommend(
        price: Double,
        band: BollBand?,
        latestYearlyDividend: Double?,
        thresholds: DividendThresholds = DividendThresholds()
    ): HoldingRecommendation {
        if (band == null || !price.isFinite() || price <= 0.0) {
            return HoldingRecommendation(
                action = HoldingAction.INSUFFICIENT_DATA,
                bollTone = BollTone.Current,
                priceVsLower = Double.NaN,
                dividendYield = null,
                reasons = listOf(if (band == null) "周线 boll 数据不足" else "无有效现价")
            )
        }
        val tone = bollTone(price, band.upper, band.middle, band.lower)
        val span = (band.upper - band.lower).takeIf { it > 0.0 } ?: 1.0
        val priceVsLower = ((price - band.lower) / span).coerceIn(0.0, 1.0)
        val yieldPct = if (latestYearlyDividend != null && latestYearlyDividend > 0.0) {
            latestYearlyDividend / price * 100.0
        } else null

        val reasons = mutableListOf<String>()
        reasons += bollPositionReason(tone, priceVsLower)

        var action = when (tone) {
            BollTone.Buy -> HoldingAction.BUY
            BollTone.Sell -> HoldingAction.SELL
            BollTone.Current -> HoldingAction.HOLD
        }

        if (yieldPct != null) {
            // 降级：在下轨但股息率偏低
            if (tone == BollTone.Buy && yieldPct < thresholds.minYieldPercent) {
                action = HoldingAction.HOLD
                reasons += "股息率偏低 (${formatYield(yieldPct)}%)"
            }
            // 升级：中轨附近但股息率较高
            else if (tone == BollTone.Current && yieldPct >= thresholds.boostYieldPercent) {
                action = HoldingAction.BUY
                reasons += "股息率较高 (${formatYield(yieldPct)}%)"
            }
        }

        return HoldingRecommendation(
            action = action,
            bollTone = tone,
            priceVsLower = priceVsLower,
            dividendYield = yieldPct,
            reasons = reasons
        )
    }

    /**
     * boll 位置 → tone（与原 BollPriceScale.bollTone 逻辑完全一致，确保评估与卡片渲染同源）。
     * - price <= lower → Buy
     * - price >= upper → Sell
     * - 否则按到中轨的偏离：dev < 0.30 → Current；偏低 → Buy；偏高 → Sell。
     */
    fun bollTone(price: Double, upper: Double, middle: Double, lower: Double): BollTone {
        if (price <= lower) return BollTone.Buy
        if (price >= upper) return BollTone.Sell
        val halfSpan = ((upper - lower) / 2.0).takeIf { it > 0.0 } ?: return BollTone.Current
        val dev = abs(price - middle) / halfSpan
        return when {
            dev < 0.30 -> BollTone.Current
            price < middle -> BollTone.Buy
            else -> BollTone.Sell
        }
    }

    private fun bollPositionReason(tone: BollTone, priceVsLower: Double): String {
        val pct = (priceVsLower * 100).toInt()
        return when (tone) {
            BollTone.Buy -> "价格接近下轨 (${pct}%)"
            BollTone.Sell -> "价格接近上轨 (${pct}%)"
            BollTone.Current -> "价格在中轨附近 (${pct}%)"
        }
    }

    private fun formatYield(y: Double): String = "%.1f".format(y)
}
```

- [ ] **Step 2: 写测试 `HoldingRecommenderTest.kt`（先写，验证 Task 1 的 bollTone 迁移 + recommend 基础逻辑）**

Create `app/src/test/java/com/stock/dividend/data/repository/HoldingRecommenderTest.kt`:

```kotlin
package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HoldingRecommenderTest {

    // 中轨 10，半带宽 1 → lower=9, upper=11

    @Test
    fun `price below lower returns BUY with high yield`() {
        val band = BollBand(middle = 10.0, upper = 11.0, lower = 9.0)
        val r = HoldingRecommender.recommend(
            price = 8.8, band = band, latestYearlyDividend = 0.50 // ~5.7%
        )
        assertThat(r.action).isEqualTo(HoldingAction.BUY)
        assertThat(r.bollTone).isEqualTo(BollTone.Buy)
        assertThat(r.reasons).isNotEmpty()
    }

    @Test
    fun `price below lower but low yield downgrades to HOLD`() {
        val band = BollBand(middle = 10.0, upper = 11.0, lower = 9.0)
        val r = HoldingRecommender.recommend(
            price = 8.8, band = band, latestYearlyDividend = 0.10 // ~1.1% < 2
        )
        assertThat(r.action).isEqualTo(HoldingAction.HOLD)
        assertThat(r.reasons.any { it.contains("股息率偏低") }).isTrue()
    }

    @Test
    fun `price at upper returns SELL regardless of high yield`() {
        val band = BollBand(middle = 10.0, upper = 11.0, lower = 9.0)
        val r = HoldingRecommender.recommend(
            price = 11.5, band = band, latestYearlyDividend = 1.00 // 高股息率
        )
        assertThat(r.action).isEqualTo(HoldingAction.SELL)
        assertThat(r.bollTone).isEqualTo(BollTone.Sell)
    }

    @Test
    fun `price near middle with low yield returns HOLD`() {
        val band = BollBand(middle = 10.0, upper = 11.0, lower = 9.0)
        val r = HoldingRecommender.recommend(
            price = 10.0, band = band, latestYearlyDividend = 0.10 // ~1%
        )
        assertThat(r.action).isEqualTo(HoldingAction.HOLD)
        assertThat(r.bollTone).isEqualTo(BollTone.Current)
    }

    @Test
    fun `price near middle with high yield upgrades to BUY`() {
        val band = BollBand(middle = 10.0, upper = 11.0, lower = 9.0)
        val r = HoldingRecommender.recommend(
            price = 10.0, band = band, latestYearlyDividend = 0.60 // 6% >= 5
        )
        assertThat(r.action).isEqualTo(HoldingAction.BUY)
        assertThat(r.reasons.any { it.contains("股息率较高") }).isTrue()
    }

    @Test
    fun `null band returns INSUFFICIENT_DATA with boll reason`() {
        val r = HoldingRecommender.recommend(price = 10.0, band = null, latestYearlyDividend = 0.5)
        assertThat(r.action).isEqualTo(HoldingAction.INSUFFICIENT_DATA)
        assertThat(r.reasons.any { it.contains("boll") }).isTrue()
        assertThat(r.dividendYield).isNull()
    }

    @Test
    fun `non-positive price returns INSUFFICIENT_DATA`() {
        val band = BollBand(middle = 10.0, upper = 11.0, lower = 9.0)
        val r = HoldingRecommender.recommend(price = 0.0, band = band, latestYearlyDividend = 0.5)
        assertThat(r.action).isEqualTo(HoldingAction.INSUFFICIENT_DATA)
    }

    @Test
    fun `NaN price returns INSUFFICIENT_DATA`() {
        val band = BollBand(middle = 10.0, upper = 11.0, lower = 9.0)
        val r = HoldingRecommender.recommend(
            price = Double.NaN, band = band, latestYearlyDividend = 0.5
        )
        assertThat(r.action).isEqualTo(HoldingAction.INSUFFICIENT_DATA)
    }

    @Test
    fun `null dividend yield does not apply thresholds and follows pure boll tone`() {
        val band = BollBand(middle = 10.0, upper = 11.0, lower = 9.0)
        // 在下轨：无股息率数据 → 不降级，保持 BUY
        val r = HoldingRecommender.recommend(price = 8.8, band = band, latestYearlyDividend = null)
        assertThat(r.action).isEqualTo(HoldingAction.BUY)
        assertThat(r.dividendYield).isNull()
    }

    @Test
    fun `custom thresholds take effect`() {
        val band = BollBand(middle = 10.0, upper = 11.0, lower = 9.0)
        // yield ~2.5%：默认门槛(min=2)不降级，但 min=3 时降级
        val rDefault = HoldingRecommender.recommend(
            price = 8.8, band = band, latestYearlyDividend = 0.22, // 2.5%
            thresholds = DividendThresholds()
        )
        val rStrict = HoldingRecommender.recommend(
            price = 8.8, band = band, latestYearlyDividend = 0.22,
            thresholds = DividendThresholds(minYieldPercent = 3.0, boostYieldPercent = 6.0)
        )
        assertThat(rDefault.action).isEqualTo(HoldingAction.BUY)
        assertThat(rStrict.action).isEqualTo(HoldingAction.HOLD)
    }

    @Test
    fun `priceVsLower is 0 at lower and 1 at upper`() {
        val band = BollBand(middle = 10.0, upper = 11.0, lower = 9.0)
        val rLower = HoldingRecommender.recommend(price = 9.0, band = band, latestYearlyDividend = null)
        val rUpper = HoldingRecommender.recommend(price = 11.0, band = band, latestYearlyDividend = null)
        assertThat(rLower.priceVsLower).isWithin(1e-9).of(0.0)
        assertThat(rUpper.priceVsLower).isWithin(1e-9).of(1.0)
    }

    // ── bollTone 直接测试（迁移自 BollPriceScale）──────────────────────

    @Test
    fun `bollTone returns Buy at or below lower`() {
        assertThat(HoldingRecommender.bollTone(9.0, 11.0, 10.0, 9.0)).isEqualTo(BollTone.Buy)
        assertThat(HoldingRecommender.bollTone(8.5, 11.0, 10.0, 9.0)).isEqualTo(BollTone.Buy)
    }

    @Test
    fun `bollTone returns Sell at or above upper`() {
        assertThat(HoldingRecommender.bollTone(11.0, 11.0, 10.0, 9.0)).isEqualTo(BollTone.Sell)
        assertThat(HoldingRecommender.bollTone(12.0, 11.0, 10.0, 9.0)).isEqualTo(BollTone.Sell)
    }

    @Test
    fun `bollTone returns Current within 30 percent of middle`() {
        // middle=10, halfSpan=1, 30% 阈值内 = 9.7~10.3
        assertThat(HoldingRecommender.bollTone(10.1, 11.0, 10.0, 9.0)).isEqualTo(BollTone.Current)
    }

    @Test
    fun `bollTone returns Buy when below middle beyond threshold`() {
        // price=9.5: dev=0.5 > 0.30, 偏低 → Buy
        assertThat(HoldingRecommender.bollTone(9.5, 11.0, 10.0, 9.0)).isEqualTo(BollTone.Buy)
    }

    @Test
    fun `bollTone returns Sell when above middle beyond threshold`() {
        assertThat(HoldingRecommender.bollTone(10.5, 11.0, 10.0, 9.0)).isEqualTo(BollTone.Sell)
    }
}
```

- [ ] **Step 3: 运行测试，确认通过**

Run:
```bash
./gradlew :app:testDebugUnitTest --tests "com.stock.dividend.data.repository.HoldingRecommenderTest"
```
Expected: BUILD SUCCESSFUL, 全部用例 PASS。

- [ ] **Step 4: 改造 `BollPriceScale.kt` 调用共享版本，删除本地副本**

在 `app/src/main/java/com/stock/dividend/ui/component/BollPriceScale.kt` 中：

1. 删除文件末尾（line 157 起的 `private enum class BollTone` 和 line 218 起的 `private fun bollTone(...)`）。
2. 在 imports 区（line 24 后）加：
   ```kotlin
   import com.stock.dividend.data.repository.BollTone
   import com.stock.dividend.data.repository.bollTone
   ```
   注意：`HoldingRecommender.bollTone` 是 `object` 内的成员函数，调用形如 `HoldingRecommender.bollTone(...)`。但 enum 是顶级可直接 import。最简单：保留 `toneColor` 对 `BollTone` 的引用不变，把 line 100 的 `bollTone(price, upper, middle, lower)` 改成 `HoldingRecommender.bollTone(price, upper, middle, lower)`，并 import：
   ```kotlin
   import com.stock.dividend.data.repository.BollTone
   import com.stock.dividend.data.repository.HoldingRecommender
   ```
3. line 100 处改：
   ```kotlin
   val tone = HoldingRecommender.bollTone(price, upper, middle, lower)
   ```

- [ ] **Step 5: 编译 + 跑 BollPriceScale 相关测试（若有），确认无回归**

Run:
```bash
./gradlew :app:compileDebugKotlin
./gradlew :app:testDebugUnitTest
```
Expected: BUILD SUCCESSFUL（BollPriceScale 无单测，靠编译 + HoldingRecommenderTest 覆盖原 bollTone 逻辑）。

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/stock/dividend/data/repository/HoldingRecommender.kt \
        app/src/main/java/com/stock/dividend/ui/component/BollPriceScale.kt \
        app/src/test/java/com/stock/dividend/data/repository/HoldingRecommenderTest.kt
git commit -m "refactor: 提取 HoldingRecommender 纯函数,共享 bollTone 逻辑"
```

---

## Task 2: 评估门槛的持久化（NotificationRuleRepository 扩展）

**目标:** 复用 `notification_rules` 表加两个 type（`EVAL_MIN_YIELD`、`EVAL_BOOST_YIELD`），无需 DB 迁移；Repository 暴露 `observeEvalThresholds(): Flow<DividendThresholds>` 和 `saveEvalThresholds(min, boost)`。

**Files:**
- Modify: `app/src/main/java/com/stock/dividend/data/local/entity/NotificationRuleEntity.kt:7-10`
- Modify: `app/src/main/java/com/stock/dividend/data/repository/NotificationRuleRepository.kt`
- Test: `app/src/test/java/com/stock/dividend/data/repository/NotificationRuleRepositoryTest.kt`

- [ ] **Step 1: 写失败测试（在 NotificationRuleRepositoryTest 末尾 `private fun rule(...)` 之前插入）**

在 `app/src/test/java/com/stock/dividend/data/repository/NotificationRuleRepositoryTest.kt` 中，文件顶部 import 加：
```kotlin
import com.stock.dividend.data.local.entity.EVAL_MIN_YIELD
import com.stock.dividend.data.local.entity.EVAL_BOOST_YIELD
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
```
在 `private fun rule(...)` 之前插入：
```kotlin
    // ── 评估门槛 (eval thresholds) ─────────────────────────────────

    @Test
    fun `observeEvalThresholds returns defaults when no rows exist`() = runTest {
        coEvery { dao.observeGlobalRule(EVAL_MIN_YIELD) } returns flowOf(null)
        coEvery { dao.observeGlobalRule(EVAL_BOOST_YIELD) } returns flowOf(null)

        val thresholds = repository.observeEvalThresholds().first()

        assertThat(thresholds.minYieldPercent).isEqualTo(DividendThresholds.DEFAULT_MIN_YIELD)
        assertThat(thresholds.boostYieldPercent).isEqualTo(DividendThresholds.DEFAULT_BOOST_YIELD)
    }

    @Test
    fun `observeEvalThresholds reads persisted rows`() = runTest {
        coEvery { dao.observeGlobalRule(EVAL_MIN_YIELD) } returns flowOf(
            rule(id = "eval-min", type = EVAL_MIN_YIELD, stockCode = null, threshold = 3.0)
        )
        coEvery { dao.observeGlobalRule(EVAL_BOOST_YIELD) } returns flowOf(
            rule(id = "eval-boost", type = EVAL_BOOST_YIELD, stockCode = null, threshold = 6.5)
        )

        val thresholds = repository.observeEvalThresholds().first()

        assertThat(thresholds.minYieldPercent).isEqualTo(3.0)
        assertThat(thresholds.boostYieldPercent).isEqualTo(6.5)
    }

    @Test
    fun `observeEvalThresholds falls back to default when only one row exists`() = runTest {
        coEvery { dao.observeGlobalRule(EVAL_MIN_YIELD) } returns flowOf(
            rule(id = "eval-min", type = EVAL_MIN_YIELD, stockCode = null, threshold = 4.0)
        )
        coEvery { dao.observeGlobalRule(EVAL_BOOST_YIELD) } returns flowOf(null)

        val thresholds = repository.observeEvalThresholds().first()

        assertThat(thresholds.minYieldPercent).isEqualTo(4.0)
        assertThat(thresholds.boostYieldPercent).isEqualTo(DividendThresholds.DEFAULT_BOOST_YIELD)
    }

    @Test
    fun `saveEvalThresholds writes both rows with stable ids`() = runTest {
        coEvery { dao.getGlobalRule(EVAL_MIN_YIELD) } returns null
        coEvery { dao.getGlobalRule(EVAL_BOOST_YIELD) } returns null

        repository.saveEvalThresholds(minYieldPercent = 2.5, boostYieldPercent = 5.5, now = 1000L)

        coVerify {
            dao.upsert(match {
                it.type == EVAL_MIN_YIELD && it.stockCode == null &&
                    it.thresholdPercent == 2.5 && it.id == "global-eval-min-yield"
            })
        }
        coVerify {
            dao.upsert(match {
                it.type == EVAL_BOOST_YIELD && it.stockCode == null &&
                    it.thresholdPercent == 5.5 && it.id == "global-eval-boost-yield"
            })
        }
    }
```

- [ ] **Step 2: 运行测试，确认失败**

Run:
```bash
./gradlew :app:testDebugUnitTest --tests "com.stock.dividend.data.repository.NotificationRuleRepositoryTest"
```
Expected: FAIL — `EVAL_MIN_YIELD` / `EVAL_BOOST_YIELD` 未定义，`observeEvalThresholds` / `saveEvalThresholds` 未解析。

- [ ] **Step 3: 加 type 常量**

在 `app/src/main/java/com/stock/dividend/data/local/entity/NotificationRuleEntity.kt` line 10 后加：
```kotlin
const val EVAL_MIN_YIELD = "EVAL_MIN_YIELD"
const val EVAL_BOOST_YIELD = "EVAL_BOOST_YIELD"
```

- [ ] **Step 4: 实现 Repository 方法**

在 `app/src/main/java/com/stock/dividend/data/repository/NotificationRuleRepository.kt` 中：

顶部 import 加：
```kotlin
import com.stock.dividend.data.local.entity.EVAL_BOOST_YIELD
import com.stock.dividend.data.local.entity.EVAL_MIN_YIELD
import kotlinx.coroutines.flow.combine
```

在 `saveRule(...)` 之前（line 64 前）插入：
```kotlin
    // ── 评估门槛（一键评估用，复用 notification_rules 表，无 DB 迁移）──

    fun observeEvalThresholds(): Flow<DividendThresholds> =
        combine(
            dao.observeGlobalRule(EVAL_MIN_YIELD),
            dao.observeGlobalRule(EVAL_BOOST_YIELD)
        ) { minRule, boostRule ->
            DividendThresholds(
                minYieldPercent = minRule?.thresholdPercent
                    ?: DividendThresholds.DEFAULT_MIN_YIELD,
                boostYieldPercent = boostRule?.thresholdPercent
                    ?: DividendThresholds.DEFAULT_BOOST_YIELD
            )
        }

    suspend fun saveEvalThresholds(
        minYieldPercent: Double,
        boostYieldPercent: Double,
        now: Long = System.currentTimeMillis()
    ) {
        saveRule(
            type = EVAL_MIN_YIELD,
            stockCode = null,
            enabled = true,
            thresholdValue = minYieldPercent,
            now = now
        )
        saveRule(
            type = EVAL_BOOST_YIELD,
            stockCode = null,
            enabled = true,
            thresholdValue = boostYieldPercent,
            now = now
        )
    }
```

修复 `defaultRuleId`（line 94-99）让它识别新 type 的稳定 id。把现有 `defaultRuleId` 替换为：
```kotlin
    private fun defaultRuleId(type: String, stockCode: String?): String {
        // 已有稳定 id 的 type（避免迁移后 id 变化导致重复行）
        val stableTypeIds = mapOf(
            NOTIFICATION_RULE_TYPE_DIVIDEND_YIELD_THRESHOLD to "dividend-yield-threshold",
            EVAL_MIN_YIELD to "eval-min-yield",
            EVAL_BOOST_YIELD to "eval-boost-yield"
        )
        val base = stableTypeIds[type] ?: type.lowercase()
        return stockCode?.let { "stock-$it-$base" } ?: "global-$base"
    }
```
> 注意：`NOTIFICATION_RULE_TYPE_DIVIDEND_YIELD_THRESHOLD` 原有的 `global-dividend-yield-threshold` id 保留（base="dividend-yield-threshold" → "global-dividend-yield-threshold"），保持向后兼容。

- [ ] **Step 5: 运行测试，确认通过（含原有用例不回归）**

Run:
```bash
./gradlew :app:testDebugUnitTest --tests "com.stock.dividend.data.repository.NotificationRuleRepositoryTest"
```
Expected: 全部 PASS（包括原有 `saves global dividend yield threshold with stable id` 仍通过，因为 id 字符串未变）。

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/stock/dividend/data/local/entity/NotificationRuleEntity.kt \
        app/src/main/java/com/stock/dividend/data/repository/NotificationRuleRepository.kt \
        app/src/test/java/com/stock/dividend/data/repository/NotificationRuleRepositoryTest.kt
git commit -m "feat: 评估门槛持久化 (复用 notification_rules 表,无迁移)"
```

---

## Task 3: PortfolioViewModel 注入门槛 + 批量评估

**目标:** 给 `PortfolioViewModel` 注入 `NotificationRuleRepository`，收集门槛 Flow；加 `EvaluatedStock` 数据类、评估状态字段、`evaluateVisibleHoldings()` 方法。复用已有 `_stockBands` 缓存，未加载的 code 触发加载后参与评估。

**Files:**
- Modify: `app/src/main/java/com/stock/dividend/viewmodel/PortfolioViewModel.kt`
- Test: `app/src/test/java/com/stock/dividend/viewmodel/PortfolioViewModelTest.kt`

- [ ] **Step 1: 写失败测试**

在 `app/src/test/java/com/stock/dividend/viewmodel/PortfolioViewModelTest.kt` 中：

顶部 import 加：
```kotlin
import com.stock.dividend.data.repository.DividendThresholds
import com.stock.dividend.data.repository.NotificationRuleRepository
import com.stock.dividend.data.repository.HoldingAction
import io.mockk.every
import kotlinx.coroutines.flow.MutableStateFlow
```

类成员区（line 41 附近 `notificationCheckCoordinator` 之后）加：
```kotlin
    private val notificationRuleRepository: NotificationRuleRepository = mockk()
```

`setUp()` 中（line 68 前）加：
```kotlin
        every { notificationRuleRepository.observeEvalThresholds() } returns
            MutableStateFlow(DividendThresholds())
```

修改 `createViewModel()`（line 505-512），在 `notificationCheckCoordinator` 之后加 `notificationRuleRepository` 参数：
```kotlin
    private fun createViewModel() = PortfolioViewModel(
        stockRepository,
        dividendDao,
        livingExpenseRepository,
        transactionDao,
        notificationCheckCoordinator,
        notificationRuleRepository,
        context
    )
```

在 `loadBoll caches null on failure...` 测试之后（line 503 后、`createViewModel` 之前）插入评估用例：
```kotlin
    // ── evaluateVisibleHoldings：一键评估筛选后的持仓 ─────────────────

    @Test
    fun `evaluateVisibleHoldings sets isEvaluating then produces sorted results`() = runTest {
        val band = BollBand(middle = 10.0, upper = 11.0, lower = 9.0)
        stocksFlow.value = listOf(
            stock("sh.600036", shares = 100, costPerShare = 10.0, industry = "银行"),
            stock("sz.000001", shares = 200, costPerShare = 5.0, industry = "银行")
        )
        coEvery { stockRepository.fetchBoll("sh.600036") } returns band
        coEvery { stockRepository.fetchBoll("sz.000001") } returns null // 数据不足
        // 给 sh.600036 一个现价 + 股息
        every { dividendDao.observeByStock("sh.600036") } returns MutableStateFlow(
            listOf(dividend("sh.600036", 0.50))
        )
        coEvery { stockRepository.fetchQuotes(any()) } returns mapOf("sh.600036" to 8.8)

        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.evaluateVisibleHoldings()
        // 评估中应置 isEvaluating=true（在协程 launch 后、awaitAll 完成前）
        // advanceUntilIdle 后应完成
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.isEvaluating).isFalse()
        assertThat(state.evaluation).isNotNull()
        val eval = state.evaluation!!
        // sh.600036: price 8.8 < lower 9 → BUY；sz.000001: band null → INSUFFICIENT_DATA
        assertThat(eval.map { it.code to it.action }).containsExactly(
            "sh.600036" to HoldingAction.BUY,
            "sz.000001" to HoldingAction.INSUFFICIENT_DATA
        )
        // 排序：BUY 在 INSUFFICIENT_DATA 之前
        assertThat(eval.first().action).isEqualTo(HoldingAction.BUY)
    }

    @Test
    fun `evaluateVisibleHoldings skips when no visible items`() = runTest {
        stocksFlow.value = emptyList()
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.evaluateVisibleHoldings()
        testDispatcher.scheduler.advanceUntilIdle()

        val eval = viewModel.uiState.value.evaluation
        assertThat(eval).isNotNull()
        assertThat(eval).isEmpty()
    }

    @Test
    fun `evaluateVisibleHoldings respects custom thresholds`() = runTest {
        val band = BollBand(middle = 10.0, upper = 11.0, lower = 9.0)
        stocksFlow.value = listOf(stock("sh.600036", shares = 100, costPerShare = 10.0))
        coEvery { stockRepository.fetchBoll("sh.600036") } returns band
        every { dividendDao.observeByStock("sh.600036") } returns MutableStateFlow(
            listOf(dividend("sh.600036", 0.22)) // yield ~2.5% at price 8.8
        )
        coEvery { stockRepository.fetchQuotes(any()) } returns mapOf("sh.600036" to 8.8)
        // 严格门槛：min=3 → 2.5% 应降级 HOLD
        every { notificationRuleRepository.observeEvalThresholds() } returns
            MutableStateFlow(DividendThresholds(minYieldPercent = 3.0, boostYieldPercent = 6.0))

        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.evaluateVisibleHoldings()
        testDispatcher.scheduler.advanceUntilIdle()

        val eval = viewModel.uiState.value.evaluation!!
        assertThat(eval.first().action).isEqualTo(HoldingAction.HOLD)
    }
```

在文件末尾 `private fun stock(...)` 之后加 dividend helper：
```kotlin
    private fun dividend(stockCode: String, cashPerShare: Double) = DividendEntity(
        // 字段按 DividendEntity 实际构造器填；如缺字段用默认值
        id = 0,
        stockCode = stockCode,
        exDate = 0L,
        cashPerShare = cashPerShare
    )
```
> ⚠️ 实施时先 `grep "data class DividendEntity" app/src/main/java/com/stock/dividend/data/local/entity/DividendEntity.kt` 查看实际构造器签名，按真实字段填齐（其他字段用默认值或合理占位）。如果构造太复杂，可改用 `mockk<DividendEntity>(relaxed=true)` 但设 `every { it.cashPerShare } returns cashPerShare`、`every { it.stockCode } returns stockCode`。

- [ ] **Step 2: 运行测试，确认失败**

Run:
```bash
./gradlew :app:testDebugUnitTest --tests "com.stock.dividend.viewmodel.PortfolioViewModelTest"
```
Expected: 编译失败 — `PortfolioViewModel` 构造器缺 `notificationRuleRepository` 参数；`evaluateVisibleHoldings` / `evaluation` / `isEvaluating` 未定义。

- [ ] **Step 3: 改 PortfolioViewModel 构造器 + 加 import**

在 `app/src/main/java/com/stock/dividend/viewmodel/PortfolioViewModel.kt` 顶部 import 区加：
```kotlin
import com.stock.dividend.data.repository.DividendThresholds
import com.stock.dividend.data.repository.HoldingAction
import com.stock.dividend.data.repository.HoldingRecommendation
import com.stock.dividend.data.repository.HoldingRecommender
import com.stock.dividend.data.repository.NotificationRuleRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.distinctUntilChanged
```

修改构造器（line 142-149），在 `notificationCheckCoordinator` 之后加参数：
```kotlin
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PortfolioViewModel @Inject constructor(
    private val stockRepository: StockRepository,
    private val dividendDao: DividendDao,
    private val livingExpenseRepository: LivingExpenseRepository,
    private val transactionDao: TransactionDao,
    private val notificationCheckCoordinator: NotificationCheckCoordinator,
    private val notificationRuleRepository: NotificationRuleRepository,
    @ApplicationContext context: Context
) : ViewModel() {
```

- [ ] **Step 4: 加 EvaluatedStock 数据类**

在 `PortfolioUiState` 数据类定义之前（line 94 前）加：
```kotlin
/** 一只股票的评估结果（结果页直接渲染）。 */
@Stable
data class EvaluatedStock(
    val code: String,
    val name: String,
    val industry: String,
    val action: HoldingAction,
    val priceVsLower: Double,
    val dividendYield: Double?,
    val bollBand: BollBand?,
    val currentPrice: Double?,
    val reasons: List<String>
)
```

- [ ] **Step 5: 加评估状态字段到 PortfolioUiState**

在 `PortfolioUiState`（line 95-138）的 `filteredWatchlist` 字段后（line 137 后、闭合 `)` 前）加：
```kotlin
    ,
    // ── 一键评估 ────────────────────────────────────────────────────
    /** 评估进行中。 */
    val isEvaluating: Boolean = false,
    /** 评估结果；null = 未评估过，空列表 = 评估过但当前筛选下无股票。 */
    val evaluation: List<EvaluatedStock>? = null
```

- [ ] **Step 6: 收集门槛 Flow + 加 evaluateVisibleHoldings()**

在 `init {}` 块最后（Collector 6 之后、闭合 `}` 之前，约 line 392 后）加 Collector 7：
```kotlin
        // Collector 7: 评估门槛（用户在设置里改的 min/boost）变化 → 缓存到 _evalThresholds
        viewModelScope.launch {
            notificationRuleRepository.observeEvalThresholds()
                .distinctUntilChanged()
                .collect { thresholds ->
                    _evalThresholds.value = thresholds
                }
        }
```

在类成员区（`_stockBands` 定义 line 164 附近）加：
```kotlin
    /** 用户配置的评估门槛（由设置页写入 notification_rules）。 */
    private val _evalThresholds = MutableStateFlow(DividendThresholds())
```

在 `loadBoll` 方法之后（line 420 后）加：
```kotlin
    /**
     * 一键评估当前筛选后可见的持仓股。对每只：
     *  1. 确保 boll 已加载（复用 [_stockBands] 缓存，缺则触发 fetchBoll）；
     *  2. 取 stockForecasts 的现价/股息；
     *  3. 调 [HoldingRecommender.recommend] 得建议；
     *  4. 按 action 优先级（BUY→HOLD→SELL→INSUFFICIENT_DATA）排序。
     *
     * 并发用 [Semaphore] 限流到 4，避免一次性几十个 Tencent 请求被拒。
     */
    fun evaluateVisibleHoldings() {
        viewModelScope.launch {
            val visible = _uiState.value.filteredItems
            if (visible.isEmpty()) {
                _uiState.update { it.copy(isEvaluating = false, evaluation = emptyList()) }
                return@launch
            }
            _uiState.update { it.copy(isEvaluating = true) }
            val thresholds = _evalThresholds.value
            val semaphore = Semaphore(4)

            val results = visible.map { item ->
                async {
                    semaphore.withPermit {
                        val band = ensureBollLoaded(item.code)
                        val forecast = _uiState.value.stockForecasts[item.code]
                        val price = forecast?.currentPrice ?: item.currentPrice ?: 0.0
                        val recommendation = HoldingRecommender.recommend(
                            price = price,
                            band = band,
                            latestYearlyDividend = forecast?.latestYearlyDividend,
                            thresholds = thresholds
                        )
                        EvaluatedStock(
                            code = item.code,
                            name = item.name,
                            industry = item.industry,
                            action = recommendation.action,
                            priceVsLower = recommendation.priceVsLower,
                            dividendYield = recommendation.dividendYield,
                            bollBand = band,
                            currentPrice = price.takeIf { it > 0.0 },
                            reasons = recommendation.reasons
                        )
                    }
                }
            }.awaitAll()

            val sorted = results.sortedWith(
                compareBy<EvaluatedStock> { it.action.priority() }
                    .thenBy { it.priceVsLower }
            )
            _uiState.update { it.copy(isEvaluating = false, evaluation = sorted) }
        }
    }

    /** 清除评估结果（结果页"清除结果"按钮用）。 */
    fun clearEvaluation() {
        _uiState.update { it.copy(evaluation = null, isEvaluating = false) }
    }

    /**
     * 确保 [code] 的 boll 已加载（[_stockBands] 有 key 即返回，含 null）；
     * 否则触发 fetchBoll 并等待结果。
     */
    private suspend fun ensureBollLoaded(code: String): BollBand? {
        _stockBands.value[code]?.let { return it }
        // 占位防并发重复请求
        _stockBands.update { it + (code to null) }
        val band = try {
            stockRepository.fetchBoll(code)
        } catch (_: Exception) {
            null
        }
        _stockBands.update { it + (code to band) }
        return band
    }
```

文件末尾（`applyPortfolioFilter` 函数之后）加 action 优先级扩展函数：
```kotlin
/** 评估排序优先级：BUY < HOLD < SELL < INSUFFICIENT_DATA。 */
private fun HoldingAction.priority(): Int = when (this) {
    HoldingAction.BUY -> 0
    HoldingAction.HOLD -> 1
    HoldingAction.SELL -> 2
    HoldingAction.INSUFFICIENT_DATA -> 3
}
```

- [ ] **Step 7: 运行测试，确认通过**

Run:
```bash
./gradlew :app:testDebugUnitTest --tests "com.stock.dividend.viewmodel.PortfolioViewModelTest"
```
Expected: 全部 PASS（包括原有 loadBoll/deleteStock 等用例不回归）。

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/stock/dividend/viewmodel/PortfolioViewModel.kt \
        app/src/test/java/com/stock/dividend/viewmodel/PortfolioViewModelTest.kt
git commit -m "feat(vm): PortfolioViewModel 加一键评估 + 门槛注入"
```

---

## Task 4: 评估门槛设置 UI（SettingsScreen + ViewModel）

**目标:** 在 SettingsScreen 加"评估门槛"编辑区（两个 OutlinedTextField + 保存按钮），让用户改 min/boost 阈值。状态合并进现有 `NotificationSettingsViewModel`。

**Files:**
- Modify: `app/src/main/java/com/stock/dividend/viewmodel/NotificationSettingsViewModel.kt`
- Modify: `app/src/main/java/com/stock/dividend/ui/screen/NotificationSettingsScreen.kt`

> 本任务无单测（与仓库"设置屏不写单测"惯例一致；阈值校验逻辑简单，靠手动验证）。但门槛的持久化已在 Task 2 用 `NotificationRuleRepositoryTest` 覆盖。

- [ ] **Step 1: 扩展 NotificationSettingsUiState**

在 `app/src/main/java/com/stock/dividend/viewmodel/NotificationSettingsViewModel.kt` 中：

顶部 import 加：
```kotlin
import com.stock.dividend.data.repository.DividendThresholds
```

修改 `NotificationSettingsUiState`（line 14-20），在 `saved` 后加字段：
```kotlin
@Stable
data class NotificationSettingsUiState(
    val enabled: Boolean = false,
    val thresholdInput: String = "5.0",
    val thresholdError: String? = null,
    val saved: Boolean = false,
    // ── 评估门槛 ──
    val evalMinInput: String = DividendThresholds.DEFAULT_MIN_YIELD.toString(),
    val evalBoostInput: String = DividendThresholds.DEFAULT_BOOST_YIELD.toString(),
    val evalError: String? = null,
    val evalSaved: Boolean = false
)
```

- [ ] **Step 2: 在 ViewModel init 中观察门槛 Flow**

在 `init {}` 块（line 29-39）后再加一个 collector：
```kotlin
        viewModelScope.launch {
            repository.observeEvalThresholds().collect { thresholds ->
                _uiState.value = _uiState.value.copy(
                    evalMinInput = thresholds.minYieldPercent.toString(),
                    evalBoostInput = thresholds.boostYieldPercent.toString(),
                    evalError = null
                )
            }
        }
```

- [ ] **Step 3: 加 input 更新 + save 方法**

在 `save()` 方法之后（line 68 后）加：
```kotlin
    fun updateEvalMin(value: String) {
        _uiState.value = _uiState.value.copy(evalMinInput = value, evalError = null, evalSaved = false)
    }

    fun updateEvalBoost(value: String) {
        _uiState.value = _uiState.value.copy(evalBoostInput = value, evalError = null, evalSaved = false)
    }

    fun saveEvalThresholds() {
        val state = _uiState.value
        val min = state.evalMinInput.toDoubleOrNull()
        val boost = state.evalBoostInput.toDoubleOrNull()
        if (min == null || min <= 0.0) {
            _uiState.value = state.copy(
                evalError = "最低股息率需为大于 0 的数字", evalSaved = false
            )
            return
        }
        if (boost == null || boost < min) {
            _uiState.value = state.copy(
                evalError = "加分股息率需 ≥ 最低股息率", evalSaved = false
            )
            return
        }
        viewModelScope.launch {
            repository.saveEvalThresholds(
                minYieldPercent = min,
                boostYieldPercent = boost
            )
            _uiState.value = _uiState.value.copy(evalSaved = true, evalError = null)
        }
    }
```

- [ ] **Step 4: 在 SettingsScreen 加评估门槛 UI**

在 `app/src/main/java/com/stock/dividend/ui/screen/NotificationSettingsScreen.kt` 中，`SettingsScreen` composable 内（line 93 `NotificationSettingsContent(...)` 调用之后、`SettingsEntryRow(settingsEntries[1], ...)` 之前）插入：
```kotlin
        Text(
            text = "评估门槛",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        EvalThresholdSettingsContent(
            state = state,
            onMinChange = viewModel::updateEvalMin,
            onBoostChange = viewModel::updateEvalBoost,
            onSave = viewModel::saveEvalThresholds
        )
```

在文件末尾（`SettingsEntryRow` 之后）加新 composable：
```kotlin
@Composable
private fun EvalThresholdSettingsContent(
    state: com.stock.dividend.viewmodel.NotificationSettingsUiState,
    onMinChange: (String) -> Unit,
    onBoostChange: (String) -> Unit,
    onSave: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "一键评估时：股息率低于「最低」不给买；达到「加分」可把持有上调为买",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = state.evalMinInput,
            onValueChange = onMinChange,
            label = { Text("最低股息率 (%)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = state.evalBoostInput,
            onValueChange = onBoostChange,
            label = { Text("加分股息率 (%)") },
            singleLine = true,
            isError = state.evalError != null,
            supportingText = {
                Text(state.evalError ?: "例如：2.0 / 5.0")
            },
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = onSave,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("保存")
        }
        if (state.evalSaved) {
            Text(
                text = "已保存",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}
```

- [ ] **Step 5: 编译验证**

Run:
```bash
./gradlew :app:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL。

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/stock/dividend/viewmodel/NotificationSettingsViewModel.kt \
        app/src/main/java/com/stock/dividend/ui/screen/NotificationSettingsScreen.kt
git commit -m "feat(settings): 评估门槛编辑 UI (min/boost 股息率)"
```

---

## Task 5: 结果页 Composable (PortfolioEvaluationScreen)

**目标:** 新建独立结果页，展示评估摘要（按 action 计数的 StatusPills）+ 分组卡片列表 + 重新评估/清除按钮 + loading/空状态。

**Files:**
- Create: `app/src/main/java/com/stock/dividend/ui/screen/PortfolioEvaluationScreen.kt`

> 无单测（与仓库 UI 层惯例一致）。逻辑层已被 Task 1/3 覆盖。

- [ ] **Step 1: 创建结果页文件**

Create `app/src/main/java/com/stock/dividend/ui/screen/PortfolioEvaluationScreen.kt`:

```kotlin
package com.stock.dividend.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stock.dividend.data.repository.BollBand
import com.stock.dividend.data.repository.HoldingAction
import com.stock.dividend.ui.component.AppCardDefaults
import com.stock.dividend.ui.component.EmptyStateView
import com.stock.dividend.ui.component.FinanceStatusTone
import com.stock.dividend.ui.component.StatusPill
import com.stock.dividend.viewmodel.EvaluatedStock
import com.stock.dividend.viewmodel.PortfolioViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortfolioEvaluationScreen(
    onBack: () -> Unit,
    viewModel: PortfolioViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("持仓评估") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        when {
            uiState.isEvaluating -> LoadingState(Modifier.padding(padding))
            uiState.evaluation == null -> NoEvaluationState(
                Modifier.padding(padding),
                onBack = onBack
            )
            uiState.evaluation!!.isEmpty() -> EmptyEvaluationState(
                Modifier.padding(padding),
                onReevaluate = viewModel::evaluateVisibleHoldings
            )
            else -> EvaluationContent(
                Modifier.padding(padding),
                evaluated = uiState.evaluation!!,
                onReevaluate = viewModel::evaluateVisibleHoldings,
                onClear = viewModel::clearEvaluation
            )
        }
    }
}

@Composable
private fun LoadingState(modifier: Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text(
                "正在拉取周线 boll 数据…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun NoEvaluationState(modifier: Modifier, onBack: () -> Unit) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "尚未评估",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "返回持仓页点击「一键评估」",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = onBack) { Text("返回") }
        }
    }
}

@Composable
private fun EmptyEvaluationState(
    modifier: Modifier,
    onReevaluate: () -> Unit
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "当前筛选下无持仓股",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onReevaluate) { Text("重新评估") }
        }
    }
}

@Composable
private fun EvaluationContent(
    modifier: Modifier,
    evaluated: List<EvaluatedStock>,
    onReevaluate: () -> Unit,
    onClear: () -> Unit
) {
    val counts = evaluated.groupBy { it.action }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = AppCardDefaults.PageHorizontalPadding,
            end = AppCardDefaults.PageHorizontalPadding,
            top = 12.dp,
            bottom = AppCardDefaults.BottomNavigationPadding
        ),
        verticalArrangement = Arrangement.spacedBy(AppCardDefaults.SectionSpacing)
    ) {
        item { EvaluationSummary(counts) }
        // 按 action 优先级分组渲染
        listOf(HoldingAction.BUY, HoldingAction.HOLD, HoldingAction.SELL, HoldingAction.INSUFFICIENT_DATA)
            .filter { counts[it]?.isNotEmpty() == true }
            .forEach { action ->
                item {
                    SectionHeader(action)
                }
                items(items = counts[action].orEmpty(), key = { it.code }) { stock ->
                    EvaluationCard(stock)
                }
            }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = onReevaluate,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null)
                    Spacer(Modifier.padding(end = 4.dp))
                    Text("重新评估")
                }
                OutlinedButton(
                    onClick = onClear,
                    modifier = Modifier.weight(1f)
                ) { Text("清除结果") }
            }
        }
    }
}

@Composable
private fun EvaluationSummary(counts: Map<HoldingAction, List<EvaluatedStock>>) {
    fun count(a: HoldingAction) = counts[a]?.size ?: 0
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SummaryPill("买 ${count(HoldingAction.BUY)}", FinanceStatusTone.Positive, Modifier.weight(1f))
        SummaryPill("持有 ${count(HoldingAction.HOLD)}", FinanceStatusTone.Neutral, Modifier.weight(1f))
        SummaryPill("卖 ${count(HoldingAction.SELL)}", FinanceStatusTone.Negative, Modifier.weight(1f))
        if (count(HoldingAction.INSUFFICIENT_DATA) > 0) {
            SummaryPill(
                "数据不足 ${count(HoldingAction.INSUFFICIENT_DATA)}",
                FinanceStatusTone.Warning,
                Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun SummaryPill(text: String, tone: FinanceStatusTone, modifier: Modifier = Modifier) {
    StatusPill(text = text, tone = tone, modifier = modifier)
}

@Composable
private fun SectionHeader(action: HoldingAction) {
    val title = when (action) {
        HoldingAction.BUY -> "买入信号"
        HoldingAction.HOLD -> "持有"
        HoldingAction.SELL -> "卖出信号"
        HoldingAction.INSUFFICIENT_DATA -> "数据不足"
    }
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
    )
}

@Composable
private fun EvaluationCard(stock: EvaluatedStock) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stock.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "${stock.code}${if (stock.industry.isNotBlank()) " · ${stock.industry}" else ""}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                StatusPill(
                    text = actionLabel(stock.action),
                    tone = actionTone(stock.action)
                )
            }
            // 第二行：boll 位置 + 股息率
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = if (stock.bollBand != null && stock.priceVsLower.isFinite()) {
                        "距下轨 ${(stock.priceVsLower * 100).toInt()}%"
                    } else {
                        "boll —"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = if (stock.dividendYield != null) {
                        "股息率 %.1f%%".format(stock.dividendYield)
                    } else {
                        "股息率 —"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // 第三行：理由列表
            stock.reasons.forEach { reason ->
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        "·",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.padding(end = 4.dp))
                    Text(
                        reason,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

private fun actionLabel(a: HoldingAction) = when (a) {
    HoldingAction.BUY -> "买"
    HoldingAction.HOLD -> "持有"
    HoldingAction.SELL -> "卖"
    HoldingAction.INSUFFICIENT_DATA -> "数据不足"
}

private fun actionTone(a: HoldingAction) = when (a) {
    HoldingAction.BUY -> FinanceStatusTone.Positive
    HoldingAction.HOLD -> FinanceStatusTone.Neutral
    HoldingAction.SELL -> FinanceStatusTone.Negative
    HoldingAction.INSUFFICIENT_DATA -> FinanceStatusTone.Warning
}
```

- [ ] **Step 2: 编译验证**

Run:
```bash
./gradlew :app:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL。

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/stock/dividend/ui/screen/PortfolioEvaluationScreen.kt
git commit -m "feat(ui): 持仓评估结果页 (摘要+分组卡片)"
```

---

## Task 6: 接线 — 入口按钮 + NavHost 路由

**目标:** PortfolioScreen 标题栏加"一键评估" TextButton；MainScaffold NavHost 加 `portfolioEvaluation` 路由，共享 PortfolioViewModel（拿到持仓页的同一 VM 实例，复用缓存）。

**Files:**
- Modify: `app/src/main/java/com/stock/dividend/ui/screen/PortfolioScreen.kt`
- Modify: `app/src/main/java/com/stock/dividend/ui/screen/MainScaffold.kt`

> 无单测（接线层）。集成靠手动验证。

- [ ] **Step 1: PortfolioScreen 加回调参数 + 按钮**

在 `app/src/main/java/com/stock/dividend/ui/screen/PortfolioScreen.kt` 中：

1. 函数签名（line 104-112）加参数：
   ```kotlin
   @OptIn(ExperimentalMaterial3Api::class)
   @Composable
   fun PortfolioScreen(
       snackbarHostState: SnackbarHostState,
       onAddStockClick: () -> Unit,
       onStockClick: (String) -> Unit,
       onEditStock: (String) -> Unit,
       onImportFromScreenshot: () -> Unit,
       onFireCardClick: () -> Unit = {},
       onNavigateToEvaluation: () -> Unit = {},   // 新增
       viewModel: PortfolioViewModel = hiltViewModel()
   ) {
   ```

2. 标题栏 Row（line 227-262）在 "添加股票" `TextButton` 之前插入"一键评估"按钮。把现有：
   ```kotlin
                   // 添加股票入口（来自原自选 tab）
                   TextButton(onClick = onAddStockClick) {
   ```
   改为：
   ```kotlin
                   // 一键评估入口
                   TextButton(
                       onClick = {
                           viewModel.evaluateVisibleHoldings()
                           onNavigateToEvaluation()
                       },
                       enabled = !uiState.isEvaluating && uiState.filteredItems.isNotEmpty()
                   ) {
                       Icon(
                           imageVector = Icons.Filled.Analytics,
                           contentDescription = null
                       )
                       Spacer(modifier = Modifier.width(4.dp))
                       Text(
                           text = "一键评估",
                           style = MaterialTheme.typography.labelLarge
                       )
                   }
                   // 添加股票入口（来自原自选 tab）
                   TextButton(onClick = onAddStockClick) {
   ```

3. imports 区加：
   ```kotlin
   import androidx.compose.material.icons.filled.Analytics
   ```

- [ ] **Step 2: MainScaffold NavHost 加路由 + 传回调**

在 `app/src/main/java/com/stock/dividend/ui/screen/MainScaffold.kt` 中：

1. `portfolio` composable（line 127-136）加 `onNavigateToEvaluation`：
   ```kotlin
            composable("portfolio") {
                PortfolioScreen(
                    snackbarHostState = snackbarHostState,
                    onAddStockClick = { tabNavController.navigate("addStock") },
                    onStockClick = { code -> tabNavController.navigate("stockDetail/$code") },
                    onEditStock = { code -> tabNavController.navigate("editHolding/$code") },
                    onImportFromScreenshot = { tabNavController.navigate("portfolioImport") },
                    onFireCardClick = { rootNavController.navigate(Routes.EXPENSE_COVERAGE) },
                    onNavigateToEvaluation = { tabNavController.navigate("portfolioEvaluation") }
                )
            }
   ```

2. 在 `portfolio` composable 之后、`composable("income")` 之前（line 137 前）加评估路由。**关键**：评估页要拿到持仓页的同一个 `PortfolioViewModel` 实例（复用 `stockBands`/`stockForecasts` 缓存），所以用 `hiltViewModel(parentEntry)` 显式绑定到 `portfolio` 的 back-stack entry，而非默认的当前 entry：
   ```kotlin
            composable("portfolioEvaluation") {
                val parentEntry = remember(it) {
                    tabNavController.getBackStackEntry("portfolio")
                }
                PortfolioEvaluationScreen(
                    onBack = { tabNavController.popBackStack() },
                    viewModel = hiltViewModel(parentEntry)
                )
            }
   ```
   确保顶部 import 区已有（若缺则补）：
   ```kotlin
   import androidx.hilt.navigation.compose.hiltViewModel
   ```
   `remember` 已由 `import androidx.compose.runtime.*`（line 20）覆盖。

- [ ] **Step 3: 编译验证**

Run:
```bash
./gradlew :app:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL。

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/stock/dividend/ui/screen/PortfolioScreen.kt \
        app/src/main/java/com/stock/dividend/ui/screen/MainScaffold.kt
git commit -m "feat(nav): 持仓页加一键评估入口 + 评估结果页路由"
```

---

## Task 7: 端到端集成验证

**目标:** 装机跑一遍完整流程，确认功能正常 + 无回归。

- [ ] **Step 1: 跑全部单元测试**

Run:
```bash
./gradlew :app:testDebugUnitTest
```
Expected: BUILD SUCCESSFUL，全部用例 PASS（含原有用例不回归）。

- [ ] **Step 2: 构建 + 安装到设备/模拟器**

Run:
```bash
./gradlew :app:installDebug
```
或用 android-emulator MCP 工具 `android_build_and_run`。

- [ ] **Step 3: 手动验证流程**

按顺序确认：
1. 打开 app → 持仓 tab → 看到"个股持仓"标题行有"一键评估"按钮（与"+ 添加股票"并列）。
2. 用行业/标签筛选器收窄范围（可选）。
3. 点"一键评估" → 跳转到评估结果页。
4. 结果页应显示：
   - 顶部摘要 pills（买 X / 持有 Y / 卖 Z）；
   - 按 BUY→HOLD→SELL→INSUFFICIENT_DATA 分组的卡片；
   - 每张卡片有名字/代码/行业、StatusPill、距下轨%、股息率%、理由列表。
5. 点"重新评估" → loading → 刷新结果。
6. 点"清除结果" → 回到"尚未评估"空状态。
7. 返回持仓页 → 进设置 tab → 看到"评估门槛"区，改 min/boost → 保存 → 回持仓页重新评估 → 确认门槛生效。
8. 网络异常时：评估应仍完成，相关股票显示"数据不足"。

- [ ] **Step 4: 截图存档（可选）**

用 `adb exec-out screencap -p > evaluation_result.png` 留档。

- [ ] **Step 5: 最终 commit（如有手动调整）**

```bash
git add -A
git commit -m "chore: 端到端验证通过" --allow-empty
```

---

## Self-Review Notes

实施完成后，对照 spec 自检：
- [ ] spec §3.2 决策步骤：HoldingRecommenderTest 覆盖每条（Task 1）
- [ ] spec §4.2 排序：PortfolioViewModelTest 验证 BUY 在前（Task 3）
- [ ] spec §6.1 无 DB 迁移：AppDatabase version 仍为 14，确认未动
- [ ] spec §3.3 BollPriceScale 共用 HoldingRecommender：编译 + 视觉无变化（Task 1 Step 5 + Task 7）
- [ ] 入口、路由、结果页、设置项全部接线（Task 4/5/6）
