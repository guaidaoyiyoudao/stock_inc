# 一键评估 LLM 解读增强 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在现有"一键评估"之上叠加 (1) 日/周/月多周期 BOLL 数据层、(2) `PortfolioAdvisor` 确定性策略信号（仓位控制 + 三周期共振买点）、(3) OpenAI 兼容的 LLM 自然语言解读层。

**Architecture:** 规则与策略都是确定性纯函数（可测、不幻觉）；LLM 只做"解读员"——把规则评估 + 策略信号序列化喂给一次批量调用，产出组合总评/单股简评/风险。LLM 配置存 SharedPreferences，用户自选 DeepSeek/智谱/通义。三层完全解耦，LLM 失败不影响规则结果。

**Tech Stack:** Kotlin 2.0 + Jetpack Compose, Hilt, Retrofit + OkHttp + Gson, Coroutines + Flow, JUnit4 + Truth + MockK + kotlinx-coroutines-test。

**Spec:** `docs/superpowers/specs/2026-07-26-llm-analysis-design.md`

---

## File Structure

### New files (main)
| File | Responsibility |
|---|---|
| `data/repository/LlmConfig.kt` | `LlmConfig` data class + `isComplete` |
| `data/repository/LlmAnalysis.kt` | `LlmAnalysis`, `LlmAnalysisResult`, `LlmAnalysisState` |
| `data/repository/LlmProviderPresets.kt` | DeepSeek/智谱/通义 预设 |
| `data/repository/LlmConfigRepository.kt` | SharedPreferences 读写 + Flow |
| `data/repository/LlmPromptBuilder.kt` | 纯函数：评估结果+信号 → system/user message |
| `data/repository/LlmAnalysisParser.kt` | 纯函数：LLM 响应 → `LlmAnalysis`（含降级） |
| `data/repository/LlmAnalysisRepository.kt` | 编排：config→prompt→调用→解析 |
| `data/repository/PortfolioAdvisor.kt` | 纯函数：策略信号（仓位控制 + 共振买点） |
| `data/remote/LlmApi.kt` | Retrofit `@Url` 全路径 chat completions |
| `data/remote/dto/LlmChatRequest.kt` | OpenAI 兼容请求 DTO |
| `data/remote/dto/LlmChatResponse.kt` | OpenAI 兼容响应 DTO |

### New files (test)
`PortfolioAdvisorTest`, `LlmPromptBuilderTest`, `LlmAnalysisParserTest`, `LlmAnalysisRepositoryTest`, `LlmConfigRepositoryTest`

### Modified files
`data/remote/dto/TencentKlineResponse.kt`（加 `qfqmonth`）, `data/repository/KlineRepository.kt`（`KlinePeriod` + `fetchCloses`）, `data/repository/StockRepository.kt`（`fetchBoll(code, period)`）, `di/NetworkModule.kt`（`LlmApi` + 60s client）, `viewmodel/PortfolioViewModel.kt`（多周期拉取 + 信号 + `analyzeWithLlm` + state）, `ui/screen/PortfolioEvaluationScreen.kt`（信号区 + AI 区块）, `ui/screen/NotificationSettingsScreen.kt`（LLM 配置）, `viewmodel/NotificationSettingsViewModel.kt`（LLM 配置读写）。

### Untouched
`HoldingRecommender`, `BollCalculator`, `AppDatabase`（v14 不迁移）。

---

## Task 1: 加 `qfqmonth` 字段到 TencentKlineResponse

**Files:**
- Modify: `app/src/main/java/com/stock/dividend/data/remote/dto/TencentKlineResponse.kt:20-23`

- [ ] **Step 1: 加月线字段**

把 `StockData` 改为：
```kotlin
data class StockData(
    val qfqday: List<List<*>>?,
    val qfqweek: List<List<*>>? = null,
    val qfqmonth: List<List<*>>? = null
)
```

- [ ] **Step 2: 编译验证**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/stock/dividend/data/remote/dto/TencentKlineResponse.kt
git commit -m "feat(kline): DTO 加 qfqmonth 月线字段"
```

---

## Task 2: KlineRepository 支持日/周/月周期

**Files:**
- Modify: `app/src/main/java/com/stock/dividend/data/repository/KlineRepository.kt`
- Test: `app/src/test/java/com/stock/dividend/data/repository/KlineRepositoryTest.kt`

- [ ] **Step 1: 加 KlinePeriod enum + 改 buildParam/fetchCloses**

替换 `KlineRepository` 主体为：
```kotlin
package com.stock.dividend.data.repository

import com.stock.dividend.data.remote.TencentDividendApi
import com.stock.dividend.di.TencentDividendSource
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/** 腾讯 fqkline 周期。paramType 进请求 param，responseKey 对应响应 JSON 键。 */
enum class KlinePeriod(val paramType: String, val responseKey: String) {
    DAILY("day", "qfqday"),
    WEEKLY("week", "qfqweek"),
    MONTHLY("month", "qfqmonth");

    /** 取 [bars] 根所需的回看日历天数（含余量）。 */
    internal fun lookbackDays(bars: Int): Int = when (this) {
        DAILY -> bars * 7 / 5 + 30   // 交易日→日历，+buffer
        WEEKLY -> bars * 7
        MONTHLY -> bars * 31
    }
}

@Singleton
class KlineRepository @Inject constructor(
    @TencentDividendSource private val tencentApi: TencentDividendApi
) {
    suspend fun fetchCloses(
        stockCode: String,
        period: KlinePeriod,
        bars: Int = DEFAULT_BARS
    ): List<Double> {
        val tencentCode = stockCode.toTencentCode() ?: return emptyList()
        val param = buildParam(tencentCode, period, bars)
        val response = try {
            tencentApi.getKline(param)
        } catch (_: Exception) {
            return emptyList()
        }
        val stockData = response.data?.values?.firstOrNull() ?: return emptyList()
        val klines = when (period) {
            KlinePeriod.DAILY -> stockData.qfqday
            KlinePeriod.WEEKLY -> stockData.qfqweek ?: stockData.qfqday
            KlinePeriod.MONTHLY -> stockData.qfqmonth ?: stockData.qfqweek
        } ?: return emptyList()
        return klines.mapNotNull { row ->
            (row.getOrNull(CLOSE_INDEX) as? String)?.toDoubleOrNull()
                ?.takeIf { it.isFinite() && it > 0.0 }
        }
    }

    /** 周线兼容封装（旧调用点不改）。 */
    suspend fun fetchWeeklyCloses(stockCode: String, weeks: Int = DEFAULT_BARS): List<Double> =
        fetchCloses(stockCode, KlinePeriod.WEEKLY, weeks)

    internal fun buildParam(tencentCode: String, period: KlinePeriod, bars: Int): String {
        val today = LocalDate.now()
        val start = today.minusDays((period.lookbackDays(bars) * BUFFER_FACTOR).toLong())
        return "$tencentCode,${period.paramType},${start.iso()},${today.iso()},$KLINE_COUNT,$ADJUST_QFQ"
    }

    companion object {
        const val DEFAULT_BARS = 40
        const val KLINE_COUNT = 640
        const val CLOSE_INDEX = 2
        const val BUFFER_FACTOR = 2
        const val ADJUST_QFQ = "qfq"
    }
}

private fun String.toTencentCode(): String? = when {
    startsWith("sh.", ignoreCase = true) -> "sh" + substringAfter(".")
    startsWith("sz.", ignoreCase = true) -> "sz" + substringAfter(".")
    else -> null
}

private fun LocalDate.iso(): String = format(DateTimeFormatter.ISO_LOCAL_DATE)
```

- [ ] **Step 2: 更新现有 buildParam 测试 + 加月线测试**

`KlineRepositoryTest.kt` 已存在（9 个测试，用旧 `buildParam(code, weeks=...)` 签名）。两处改动：

(a) 把现有 `buildParam uses week type qfq adjust and sh tencent code` 测试里的调用改为新签名：
```kotlin
    @Test
    fun `buildParam uses week type qfq adjust and sh tencent code`() {
        val param = repository.buildParam("sh600036", KlinePeriod.WEEKLY, 40)
        // 其余断言不变：startsWith("sh600036,week,")、endsWith(",640,qfq")、6 段、parts[1]==week、parts[5]==qfq、两段 ISO 日期
    }
```

(b) 追加月线测试：
```kotlin
    @Test
    fun `monthly buildParam uses month type and long lookback`() {
        val param = repository.buildParam("sh600036", KlinePeriod.MONTHLY, 40)
        assertThat(param).startsWith("sh600036,month,")
        assertThat(param).endsWith(",640,qfq")
    }
```
（`mockk` / `assertThat` / `Test` 已在文件 import 中，无需新增。）

- [ ] **Step 3: Run tests**

Run: `./gradlew :app:testDebugUnitTest --tests "com.stock.dividend.data.repository.KlineRepositoryTest"`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/stock/dividend/data/repository/KlineRepository.kt app/src/test/java/com/stock/dividend/data/repository/KlineRepositoryTest.kt
git commit -m "feat(kline): KlineRepository 支持日/周/月周期"
```

---

## Task 3: StockRepository.fetchBoll(code, period)

**Files:**
- Modify: `app/src/main/java/com/stock/dividend/data/repository/StockRepository.kt:287-300`

- [ ] **Step 1: 加带 period 的重载**

把 `// ---------- BOLL 带（周线）----------` 段替换为：
```kotlin
    // ---------- BOLL 带 ----------

    /**
     * 拉取 [stockCode] 指定 [period] 的收盘价并计算 BOLL 带（MA20 ± 2σ）。
     * 网络失败或收盘价不足 20 根返回 null。
     */
    suspend fun fetchBoll(stockCode: String, period: KlinePeriod = KlinePeriod.WEEKLY): BollBand? {
        val closes = try {
            klineRepository.fetchCloses(stockCode, period)
        } catch (_: Exception) {
            return null
        }
        return BollCalculator.calculate(closes)
    }
```
（旧 `fetchBoll(stockCode)` 调用点因默认参数 `= KlinePeriod.WEEKLY` 无需改。）

- [ ] **Step 2: 编译验证**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/stock/dividend/data/repository/StockRepository.kt
git commit -m "feat(stock): fetchBoll 支持 period 参数"
```

---

## Task 4: PortfolioAdvisor（纯函数，TDD）

**Files:**
- Create: `app/src/main/java/com/stock/dividend/data/repository/PortfolioAdvisor.kt`
- Test: `app/src/test/java/com/stock/dividend/data/repository/PortfolioAdvisorTest.kt`

> **前置（已完成）：** `EvaluatedStock` 从 `viewmodel` 包迁移到 `data/repository/EvaluatedStock.kt`（领域 DTO）。这样 `PortfolioAdvisor` / `LlmPromptBuilder` / `LlmAnalysisRepository`（都在 `data.repository`）与之同包，**无需 import**，避免 data 层反向依赖 viewmodel。`PortfolioViewModel` 与 `PortfolioEvaluationScreen` 改为 `import com.stock.dividend.data.repository.EvaluatedStock`。

- [ ] **Step 1: 写失败测试**

`PortfolioAdvisorTest.kt`：
```kotlin
package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PortfolioAdvisorTest {

    private fun stock(
        code: String,
        priceVsLower: Double = 0.1,
        yield: Double? = 3.0,
        price: Double = 10.0,
        band: BollBand? = null
    ) = EvaluatedStock(
        code = code, name = code, industry = "",
        action = HoldingAction.HOLD, priceVsLower = priceVsLower,
        dividendYield = yield, bollBand = band, currentPrice = price,
        reasons = emptyList()
    )

    private val lowerBand = BollBand(middle = 10.0, upper = 11.0, lower = 9.0)
    private val midBand = BollBand(middle = 12.0, upper = 13.0, lower = 11.0)

    @Test
    fun `position control triggers when majority at upper and low yield`() {
        val stocks = listOf(
            stock("a", priceVsLower = 0.95, yield = 1.5), stock("b", priceVsLower = 0.95, yield = 1.5),
            stock("c", priceVsLower = 0.2, yield = 1.0)
        )
        val sig = PortfolioAdvisor.evaluate(stocks, emptyMap(), emptyMap())
        assertThat(sig.positionControl.triggered).isTrue()
        assertThat(sig.positionControl.targetCashPercent).isEqualTo(15)
        assertThat(sig.positionControl.upperBandRatio).isWithin(0.01).of(2.0 / 3.0)
    }

    @Test
    fun `position control not triggered when yield high`() {
        val stocks = listOf(stock("a", 0.95, yield = 4.0), stock("b", 0.95, yield = 4.0))
        val sig = PortfolioAdvisor.evaluate(stocks, emptyMap(), emptyMap())
        assertThat(sig.positionControl.triggered).isFalse()
    }

    @Test
    fun `position control not triggered when few at upper`() {
        val stocks = listOf(stock("a", 0.95), stock("b", 0.2), stock("c", 0.2))
        val sig = PortfolioAdvisor.evaluate(stocks, emptyMap(), emptyMap())
        assertThat(sig.positionControl.triggered).isFalse()
    }

    @Test
    fun `resonant buy signal when daily lower weekly lower monthly below middle`() {
        // price 8.5 <= daily.lower 9, <= weekly.lower 9, < monthly.middle 12
        val stocks = listOf(stock("a", price = 8.5, band = lowerBand))
        val daily = mapOf("a" to lowerBand)
        val monthly = mapOf("a" to midBand)
        val sig = PortfolioAdvisor.evaluate(stocks, daily, monthly)
        assertThat(sig.buySignals).hasSize(1)
        assertThat(sig.buySignals.first().code).isEqualTo("a")
        assertThat(sig.buySignals.first().resonant).isTrue()
    }

    @Test
    fun `no resonant signal when monthly at or above middle`() {
        // price 8.5: daily<=9 ✓, weekly<=9 ✓, but monthly middle=8.0 → 8.5 >= 8.0 → not below middle
        val stocks = listOf(stock("a", price = 8.5, band = lowerBand))
        val daily = mapOf("a" to lowerBand)
        val monthly = mapOf("a" to BollBand(middle = 8.0, upper = 9.0, lower = 7.0))
        val sig = PortfolioAdvisor.evaluate(stocks, daily, monthly)
        assertThat(sig.buySignals).isEmpty()
    }

    @Test
    fun `missing monthly band skips resonance for that stock`() {
        val stocks = listOf(stock("a", price = 8.5, band = lowerBand))
        val daily = mapOf("a" to lowerBand)
        val monthly = mapOf<String, BollBand?>("a" to null)
        val sig = PortfolioAdvisor.evaluate(stocks, daily, monthly)
        assertThat(sig.buySignals).isEmpty()
    }

    @Test
    fun `empty stocks yields no signals`() {
        val sig = PortfolioAdvisor.evaluate(emptyList(), emptyMap(), emptyMap())
        assertThat(sig.positionControl.triggered).isFalse()
        assertThat(sig.buySignals).isEmpty()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.stock.dividend.data.repository.PortfolioAdvisorTest"`
Expected: FAIL（`PortfolioAdvisor` 未定义）

- [ ] **Step 3: 写实现**

`PortfolioAdvisor.kt`：
```kotlin
package com.stock.dividend.data.repository

/** 策略参数（先硬编码默认值，后续可做设置项）。 */
data class PortfolioAdvisorConfig(
    val minUpperBandRatio: Double = 0.5,
    val maxAvgDividendYield: Double = 2.0,
    val upperProximityThreshold: Double = 0.9,
    val targetCashPercent: Int = 15,
)

data class PositionControlSignal(
    val triggered: Boolean,
    val upperBandRatio: Double,
    val avgDividendYield: Double,
    val targetCashPercent: Int,
)

data class MultiTimeframeBuySignal(
    val code: String,
    val dailyAtLower: Boolean,
    val weeklyAtLower: Boolean,
    val monthlyBelowMiddle: Boolean,
    val resonant: Boolean,
)

data class PortfolioSignals(
    val positionControl: PositionControlSignal,
    val buySignals: List<MultiTimeframeBuySignal>,
)

/**
 * 组合策略信号（纯函数，无 Android 依赖）。
 *
 * 仓位控制：上轨占比 ≥ [PortfolioAdvisorConfig.minUpperBandRatio] 且
 *   平均股息率 < [PortfolioAdvisorConfig.maxAvgDividendYield] → 触发，建议现金 ≥ targetCashPercent。
 * 三周期共振：日下轨 + 周下轨 + 月中轨以下 同时成立。周线取自 [EvaluatedStock.bollBand]，
 *   日/月取自传入的 band map；任一周期数据缺失则该股跳过。
 */
object PortfolioAdvisor {

    fun evaluate(
        evaluatedStocks: List<EvaluatedStock>,
        dailyBands: Map<String, BollBand?>,
        monthlyBands: Map<String, BollBand?>,
        config: PortfolioAdvisorConfig = PortfolioAdvisorConfig(),
    ): PortfolioSignals {
        val positionControl = computePositionControl(evaluatedStocks, config)
        val buySignals = evaluatedStocks.mapNotNull { stock ->
            val price = stock.currentPrice
            val daily = dailyBands[stock.code]
            val weekly = stock.bollBand
            val monthly = monthlyBands[stock.code]
            if (price == null || price <= 0.0 || daily == null || weekly == null || monthly == null) {
                null
            } else {
                val dailyAtLower = price <= daily.lower
                val weeklyAtLower = price <= weekly.lower
                val monthlyBelowMiddle = price < monthly.middle
                val resonant = dailyAtLower && weeklyAtLower && monthlyBelowMiddle
                if (resonant) MultiTimeframeBuySignal(stock.code, dailyAtLower, weeklyAtLower, monthlyBelowMiddle, resonant)
                else null
            }
        }
        return PortfolioSignals(positionControl, buySignals)
    }

    private fun computePositionControl(
        stocks: List<EvaluatedStock>,
        config: PortfolioAdvisorConfig
    ): PositionControlSignal {
        if (stocks.isEmpty()) return PositionControlSignal(false, 0.0, 0.0, config.targetCashPercent)
        val upperCount = stocks.count { it.priceVsLower.isFinite() && it.priceVsLower >= config.upperProximityThreshold }
        val upperBandRatio = upperCount.toDouble() / stocks.size
        val yields = stocks.mapNotNull { it.dividendYield?.takeIf { y -> y.isFinite() } }
        val avgYield = if (yields.isNotEmpty()) yields.average() else 0.0
        val triggered = upperBandRatio >= config.minUpperBandRatio && avgYield < config.maxAvgDividendYield
        return PositionControlSignal(triggered, upperBandRatio, avgYield, config.targetCashPercent)
    }
}
```

- [ ] **Step 4: Run tests to verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.stock.dividend.data.repository.PortfolioAdvisorTest"`
Expected: PASS（7 tests）

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/stock/dividend/data/repository/PortfolioAdvisor.kt app/src/test/java/com/stock/dividend/data/repository/PortfolioAdvisorTest.kt
git commit -m "feat(advisor): PortfolioAdvisor 仓位控制 + 三周期共振买点"
```

---

## Task 5: LlmConfig + LlmProviderPresets

**Files:**
- Create: `app/src/main/java/com/stock/dividend/data/repository/LlmConfig.kt`
- Create: `app/src/main/java/com/stock/dividend/data/repository/LlmProviderPresets.kt`
- Create: `app/src/main/java/com/stock/dividend/data/repository/LlmAnalysis.kt`

- [ ] **Step 1: 写三个数据文件**

`LlmConfig.kt`：
```kotlin
package com.stock.dividend.data.repository

/** 用户配置的 LLM 端点（OpenAI 兼容）。 */
data class LlmConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
) {
    val isComplete: Boolean
        get() = baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()
}
```

`LlmProviderPresets.kt`：
```kotlin
package com.stock.dividend.data.repository

/** 国内可用的 OpenAI 兼容厂商预设。用户选定后自动填 baseUrl + 默认 model。 */
enum class LlmProviderPreset(
    val displayName: String,
    val baseUrl: String,
    val defaultModel: String,
) {
    DEEPSEEK("DeepSeek", "https://api.deepseek.com/v1/", "deepseek-chat"),
    ZHIPU("智谱 GLM", "https://open.bigmodel.cn/api/paas/v4/", "glm-4-flash"),
    QWEN("通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1/", "qwen-turbo"),
    CUSTOM("自定义", "", "");

    companion object {
        fun apply(provider: LlmProviderPreset, current: LlmConfig): LlmConfig =
            if (provider == CUSTOM) current
            else current.copy(baseUrl = provider.baseUrl, model = provider.defaultModel)
    }
}
```

`LlmAnalysis.kt`：
```kotlin
package com.stock.dividend.data.repository

data class LlmAnalysis(
    val overview: String,
    val stockComments: Map<String, String>,
    val risks: List<String>,
)

sealed interface LlmAnalysisResult {
    data class Success(val analysis: LlmAnalysis) : LlmAnalysisResult
    data object NotConfigured : LlmAnalysisResult
    data class Error(val message: String) : LlmAnalysisResult
}

sealed interface LlmAnalysisState {
    data object Idle : LlmAnalysisState
    data object Loading : LlmAnalysisState
    data object NotConfigured : LlmAnalysisState
    data class Success(val analysis: LlmAnalysis) : LlmAnalysisState
    data class Error(val message: String) : LlmAnalysisState
}
```

- [ ] **Step 2: 编译验证**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/stock/dividend/data/repository/LlmConfig.kt app/src/main/java/com/stock/dividend/data/repository/LlmProviderPresets.kt app/src/main/java/com/stock/dividend/data/repository/LlmAnalysis.kt
git commit -m "feat(llm): LlmConfig / 预设 / 结果数据类"
```

---

## Task 6: LlmConfigRepository（SharedPreferences）

**Files:**
- Create: `app/src/main/java/com/stock/dividend/data/repository/LlmConfigRepository.kt`

> **不写单测：** 项目未配置 Robolectric（全仓库纯 JUnit4 + MockK）。为 3-key SharedPreferences 包装类引入 Robolectric 低 ROI；逻辑极薄（读写 3 string + listener Flow），靠 T13 设置 UI + T14 手动验证覆盖。**已在实现中顺带定义 `LlmConfigSource` 接口 + `@Binds` 绑定**（供 T10 测试用 fake 绕开 SP），T10 不再改本文件。

- [ ] **Step 1: ~~写失败测试~~（已废弃：本任务不写单测，下方测试代码仅作历史参考）**

`LlmConfigRepositoryTest.kt`：
```kotlin
package com.stock.dividend.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LlmConfigRepositoryTest {
    private lateinit var ctx: Context
    private lateinit var repo: LlmConfigRepository

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        ctx.getSharedPreferences("llm_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        repo = LlmConfigRepository(ctx)
    }

    @Test
    fun `default config is empty`() = runTest {
        val cfg = repo.observeConfig().first()
        assertThat(cfg.isComplete).isFalse()
    }

    @Test
    fun `saved config is observable`() = runTest {
        repo.saveConfig(LlmConfig("https://api.deepseek.com/v1/", "sk-x", "deepseek-chat"))
        val cfg = repo.observeConfig().first()
        assertThat(cfg.apiKey).isEqualTo("sk-x")
        assertThat(cfg.model).isEqualTo("deepseek-chat")
        assertThat(cfg.isComplete).isTrue()
    }

    @Test
    fun `clear resets to empty`() = runTest {
        repo.saveConfig(LlmConfig("u", "k", "m"))
        repo.clearConfig()
        assertThat(repo.observeConfig().first().isComplete).isFalse()
    }
}
```

> **Note:** 项目需已包含 Robolectric。若 `app/build.gradle.kts` 无 `androidTest`/`testImplementation("org.robolectric:robolectric")`，先在 `dependencies` 的 `testImplementation` 加 `testImplementation("org.robolectric:robolectric:4.13")`（版本与 Gradle/AGP 兼容即可），并在 `android { testOptions { unitTests { isIncludeAndroidResources = true } } }` 开启。

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.stock.dividend.data.repository.LlmConfigRepositoryTest"`
Expected: FAIL（`LlmConfigRepository` 未定义）

- [ ] **Step 3: 写实现**

`LlmConfigRepository.kt`：
```kotlin
package com.stock.dividend.data.repository

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart
import javax.inject.Inject
import javax.inject.Singleton

/** 用 SharedPreferences 持久化 [LlmConfig]（key 仅存本机，未加密——见 spec §9）。 */
@Singleton
class LlmConfigRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun observeConfig(): Flow<LlmConfig> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key in KEYS) trySend(snapshot())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.onStart { emit(snapshot()) }.distinctUntilChanged()

    fun snapshot(): LlmConfig = LlmConfig(
        baseUrl = prefs.getString(KEY_BASE_URL, "").orEmpty(),
        apiKey = prefs.getString(KEY_API_KEY, "").orEmpty(),
        model = prefs.getString(KEY_MODEL, "").orEmpty(),
    )

    suspend fun saveConfig(config: LlmConfig) {
        prefs.edit()
            .putString(KEY_BASE_URL, config.baseUrl)
            .putString(KEY_API_KEY, config.apiKey)
            .putString(KEY_MODEL, config.model)
            .apply()
    }

    suspend fun clearConfig() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS_NAME = "llm_prefs"
        private const val KEY_BASE_URL = "llm_base_url"
        private const val KEY_API_KEY = "llm_api_key"
        private const val KEY_MODEL = "llm_model"
        private val KEYS = setOf(KEY_BASE_URL, KEY_API_KEY, KEY_MODEL)
    }
}
```

- [ ] **Step 4: Run tests to verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.stock.dividend.data.repository.LlmConfigRepositoryTest"`
Expected: PASS（3 tests）

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/stock/dividend/data/repository/LlmConfigRepository.kt app/src/test/java/com/stock/dividend/data/repository/LlmConfigRepositoryTest.kt app/build.gradle.kts
git commit -m "feat(llm): LlmConfigRepository + Robolectric 测试"
```

---

## Task 7: LLM DTO + LlmApi + NetworkModule

**Files:**
- Create: `app/src/main/java/com/stock/dividend/data/remote/dto/LlmChatRequest.kt`
- Create: `app/src/main/java/com/stock/dividend/data/remote/dto/LlmChatResponse.kt`
- Create: `app/src/main/java/com/stock/dividend/data/remote/LlmApi.kt`
- Modify: `app/src/main/java/com/stock/dividend/di/NetworkModule.kt`

- [ ] **Step 1: 写 DTO**

`LlmChatRequest.kt`：
```kotlin
package com.stock.dividend.data.remote.dto

import com.google.gson.annotations.SerializedName

data class LlmMessage(val role: String, val content: String)

data class LlmResponseFormat(val type: String = "json_object")

data class LlmChatRequest(
    val model: String,
    val messages: List<LlmMessage>,
    val temperature: Double = 0.3,
    @SerializedName("response_format") val responseFormat: LlmResponseFormat = LlmResponseFormat(),
)
```

`LlmChatResponse.kt`：
```kotlin
package com.stock.dividend.data.remote.dto

data class LlmChatResponse(val choices: List<Choice> = emptyList()) {
    data class Choice(val message: LlmMessage? = null)
    val content: String? get() = choices.firstOrNull()?.message?.content
}
```

- [ ] **Step 2: 写 LlmApi**

`LlmApi.kt`：
```kotlin
package com.stock.dividend.data.remote

import com.stock.dividend.data.remote.dto.LlmChatRequest
import com.stock.dividend.data.remote.dto.LlmChatResponse
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Url

/**
 * OpenAI 兼容 chat completions。baseUrl 由用户配置、动态变化，故用 @Url 传全路径，
 * Retrofit 实例的 baseUrl 仅作占位（http://localhost/）。
 */
interface LlmApi {
    @POST
    suspend fun chatCompletions(
        @Url url: String,
        @Header("Authorization") auth: String,
        @Body body: LlmChatRequest,
    ): LlmChatResponse
}
```

- [ ] **Step 3: NetworkModule 加 LlmApi + 60s client**

在 `NetworkModule.kt` 顶部加注解：
```kotlin
/** 标记 LLM 专用 client（60s 超时，LLM 响应慢）。 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class LlmClient
```

在 `object NetworkModule {` 内（`provideQuoteApi` 之后）加：
```kotlin
    @Provides
    @Singleton
    @LlmClient
    fun provideLlmOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .addInterceptor(
                HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
            )
            .build()
    }

    @Provides
    @Singleton
    fun provideLlmApi(@LlmClient client: OkHttpClient): LlmApi {
        return Retrofit.Builder()
            .baseUrl("http://localhost/")   // 占位；实际 URL 走 @Url
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(LlmApi::class.java)
    }
```
并加 `import com.stock.dividend.data.remote.LlmApi`。

- [ ] **Step 4: 编译验证**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/stock/dividend/data/remote/ app/src/main/java/com/stock/dividend/di/NetworkModule.kt
git commit -m "feat(llm): DTO + LlmApi(@Url) + 60s client"
```

---

## Task 8: LlmPromptBuilder（纯函数，TDD）

**Files:**
- Create: `app/src/main/java/com/stock/dividend/data/repository/LlmPromptBuilder.kt`
- Test: `app/src/test/java/com/stock/dividend/data/repository/LlmPromptBuilderTest.kt`

- [ ] **Step 1: 写失败测试**

`LlmPromptBuilderTest.kt`：
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
    ) = LlmPromptBuilder.build(stocks, daily, monthly, signals, DividendThresholds())

    @Test
    fun `system prompt states JSON schema and constraints`() {
        val p = prompt(listOf(stock("600036")))
        assertThat(p.system).contains("overview")
        assertThat(p.system).contains("stockComments")
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
        // price 9.5；日 band(9..11)→距下轨 25%；周 priceVsLower=0.1→10%；月 band middle=12→<中轨
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
        val p = prompt(listOf(stock("600036")))  // 空 daily/monthly map
        assertThat(p.user).contains("日距下轨 —")
    }

    @Test
    fun `empty stocks still produces valid prompt`() {
        val p = prompt(emptyList())
        assertThat(p.system).isNotEmpty()
        assertThat(p.user).isNotEmpty()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.stock.dividend.data.repository.LlmPromptBuilderTest"`
Expected: FAIL（`LlmPromptBuilder` 未定义）

- [ ] **Step 3: 写实现**

`LlmPromptBuilder.kt`：
```kotlin
package com.stock.dividend.data.repository

/**
 * 把规则评估结果 + 策略信号序列化为 LLM prompt（纯函数）。
 * system 定角色 + JSON schema + 约束；user 放结构化数据。不喂成本价等敏感信息。
 */
object LlmPromptBuilder {

    data class LlmPrompt(val system: String, val user: String)

    fun build(
        evaluatedStocks: List<EvaluatedStock>,
        dailyBands: Map<String, BollBand?>,
        monthlyBands: Map<String, BollBand?>,
        signals: PortfolioSignals,
        thresholds: DividendThresholds,
    ): LlmPrompt = LlmPrompt(SYSTEM, buildUser(evaluatedStocks, dailyBands, monthlyBands, signals, thresholds))

    private const val SYSTEM = """
你是一位稳健、客观的中文分红股投资分析助手。

【任务】
基于用户提供的持仓评估数据与策略信号（已由规则引擎判定），输出自然语言解读。

【数据语义（仅供理解，不要复述规则公式）】
- action=买：价格处于周线 BOLL 下轨附近（偏低），且股息率达门槛
- action=卖：价格处于周线 BOLL 上轨附近（偏高）
- action=持有：价格在中轨附近或股息率不足以触发买
- 距下轨%：0=在下轨（便宜），100=在上轨（贵）；每只股给出 日/周/月 三周期的距下轨%，据此判断多周期共振
- 股息率%：年现金分红 / 现价
- 仓位控制信号：多数股票抵达上轨 + 整体股息偏低 → 建议控仓、现金 ≥ 目标%
- 三周期共振买点：日下轨 + 周下轨 + 月中轨以下 同时成立

【输出要求】严格输出 JSON：
{"overview":"组合整体解读≤150字","stockComments":{"<code>":"该股≤40字"},"risks":["具体风险点"]}

【约束】
1. 仅基于提供数据，绝不编造价格/股息率/财报/未给出的信息。
2. 中文，专业易懂，避免绝对化断言。
3. 不给明确买卖时点或价格目标；这是解读，不是指令。
4. 仓位控制信号触发时，overview 必须明确提示控仓与现金 ≥ 目标%。
5. 三周期共振买点的股票要在 stockComments 中点名。
6. 风险要点具体，不泛泛而谈；不复述规则逻辑。
""".trim()

    private fun buildUser(
        stocks: List<EvaluatedStock>,
        dailyBands: Map<String, BollBand?>,
        monthlyBands: Map<String, BollBand?>,
        signals: PortfolioSignals,
        thresholds: DividendThresholds,
    ): String {
        val sb = StringBuilder()
        sb.append("【门槛】最低股息率 ${thresholds.minYieldPercent}%，加分股息率 ${thresholds.boostYieldPercent}%\n")
        sb.append("【持仓评估】\n")
        if (stocks.isEmpty()) sb.append("（无）\n")
        stocks.forEach { s ->
            val actionZh = when (s.action) {
                HoldingAction.BUY -> "买"
                HoldingAction.SELL -> "卖"
                HoldingAction.HOLD -> "持有"
                HoldingAction.INSUFFICIENT_DATA -> "数据不足"
            }
            val daily = ratioVsLower(s.currentPrice, dailyBands[s.code])
            val weekly = if (s.priceVsLower.isFinite()) "${(s.priceVsLower * 100).toInt()}%" else "—"
            val monthly = ratioVsLower(s.currentPrice, monthlyBands[s.code])
            sb.append("- ${s.code} ${s.name} [${s.industry}]：$actionZh，股息率 ${s.dividendYield?.let { "${"%.1".format(it)}%" } ?: "—"}")
            sb.append(" | 日距下轨 $daily / 周距下轨 $weekly / 月距下轨 $monthly\n")
        }
        sb.append("【策略信号】\n")
        val pc = signals.positionControl
        if (pc.triggered) {
            sb.append("- 控仓：触发（上轨占比 ${"%.0f".format(pc.upperBandRatio * 100)}%，平均股息率 ${"%.1f".format(pc.avgDividendYield)}%），建议现金 ≥ ${pc.targetCashPercent}%\n")
        } else {
            sb.append("- 控仓：未触发\n")
        }
        if (signals.buySignals.isNotEmpty()) {
            sb.append("- 三周期共振买点：${signals.buySignals.joinToString("、") { it.code }}\n")
        } else {
            sb.append("- 三周期共振买点：无\n")
        }
        return sb.toString()
    }

    /** (price - lower) / (upper - lower) → "X%"，clamp 0..100；band/price 无效返回 "—"。 */
    private fun ratioVsLower(price: Double?, band: BollBand?): String {
        if (price == null || price <= 0.0 || band == null || band.upper <= band.lower) return "—"
        val r = ((price - band.lower) / (band.upper - band.lower) * 100).toInt().coerceIn(0, 100)
        return "$r%"
    }
}
```

- [ ] **Step 4: Run tests to verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.stock.dividend.data.repository.LlmPromptBuilderTest"`
Expected: PASS（8 tests）

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/stock/dividend/data/repository/LlmPromptBuilder.kt app/src/test/java/com/stock/dividend/data/repository/LlmPromptBuilderTest.kt
git commit -m "feat(llm): LlmPromptBuilder 纯函数 + 测试"
```

---

## Task 9: LlmAnalysisParser（纯函数，TDD）

**Files:**
- Create: `app/src/main/java/com/stock/dividend/data/repository/LlmAnalysisParser.kt`
- Test: `app/src/test/java/com/stock/dividend/data/repository/LlmAnalysisParserTest.kt`

- [ ] **Step 1: 写失败测试**

`LlmAnalysisParserTest.kt`：
```kotlin
package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LlmAnalysisParserTest {

    @Test
    fun `parses full json`() {
        val raw = """{"overview":"组合偏防御","stockComments":{"600036":"低估"},"risks":["银行占比高"]}"""
        val a = LlmAnalysisParser.parse(raw)
        assertThat(a.overview).isEqualTo("组合偏防御")
        assertThat(a.stockComments["600036"]).isEqualTo("低估")
        assertThat(a.risks).containsExactly("银行占比高")
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

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.stock.dividend.data.repository.LlmAnalysisParserTest"`
Expected: FAIL（未定义）

- [ ] **Step 3: 写实现**（用 Gson，避开 `org.json` 在本地单测的 stub 限制）

`LlmAnalysisParser.kt`：
```kotlin
package com.stock.dividend.data.repository

import com.google.gson.JsonParser

/**
 * 解析 LLM 返回内容为 [LlmAnalysis]（纯函数，永不抛异常）。
 * 兜底链：完整 JSON → 字段缺失补默认 → ```json 代码块提取 → 纯文本降级。
 */
object LlmAnalysisParser {

    fun parse(rawContent: String): LlmAnalysis {
        val trimmed = rawContent.trim()
        if (trimmed.isEmpty()) return LlmAnalysis("", emptyMap(), emptyList())
        val jsonStr = extractJsonObject(trimmed) ?: return LlmAnalysis(trimmed, emptyMap(), emptyList())
        return try {
            val obj = JsonParser.parseString(jsonStr).asJsonObject
            val overview = obj.get("overview")?.takeIf { !it.isJsonNull }?.asString ?: trimmed
            val comments = buildMap {
                obj.get("stockComments")?.takeIf { it.isJsonObject }?.asJsonObject?.entrySet()?.forEach { (k, v) ->
                    if (!v.isJsonNull) put(k, v.asString)
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

    private fun extractJsonObject(raw: String): String? {
        if (raw.startsWith("{")) return raw
        val fence = Regex("""```(?:json)?\s*(\{.*?})\s*```""", RegexOption.DOT_MATCHES_ALL).find(raw)
        if (fence != null) return fence.groupValues[1]
        val first = raw.indexOf('{')
        val last = raw.lastIndexOf('}')
        return if (first in 0 until last) raw.substring(first, last + 1) else null
    }
}
```

- [ ] **Step 4: Run tests to verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.stock.dividend.data.repository.LlmAnalysisParserTest"`
Expected: PASS（6 tests）

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/stock/dividend/data/repository/LlmAnalysisParser.kt app/src/test/java/com/stock/dividend/data/repository/LlmAnalysisParserTest.kt
git commit -m "feat(llm): LlmAnalysisParser 纯函数 + 降级兜底"
```

---

## Task 10: LlmAnalysisRepository（TDD + fake）

**Files:**
- Create: `app/src/main/java/com/stock/dividend/data/repository/LlmAnalysisRepository.kt`
- Test: `app/src/test/java/com/stock/dividend/data/repository/LlmAnalysisRepositoryTest.kt`

- [ ] **Step 1: 写失败测试**

`LlmAnalysisRepositoryTest.kt`：
```kotlin
package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.remote.LlmApi
import com.stock.dividend.data.remote.dto.LlmChatRequest
import com.stock.dividend.data.remote.dto.LlmChatResponse
import com.stock.dividend.data.remote.dto.LlmMessage
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

    private fun repo(config: LlmConfig, api: LlmApi): LlmAnalysisRepository =
        LlmAnalysisRepository(api, TestConfigSource(flowOf(config)))

    @Test
    fun `returns NotConfigured when key missing`() = runTest {
        val r = repo(LlmConfig("https://x/", "", "m"), api { _, _, _ -> resp(""""x"""") })
            .analyze(listOf(stock), emptyMap(), emptyMap(), signals, DividendThresholds())
        assertThat(r).isInstanceOf(LlmAnalysisResult.NotConfigured::class.java)
    }

    @Test
    fun `returns NotConfigured when stocks empty`() = runTest {
        val r = repo(LlmConfig("https://x/", "k", "m"), api { _, _, _ -> resp(""""x"""") })
            .analyze(emptyList(), emptyMap(), emptyMap(), signals, DividendThresholds())
        assertThat(r).isInstanceOf(LlmAnalysisResult.NotConfigured::class.java)
    }

    @Test
    fun `returns Success on valid response`() = runTest {
        val api = api { _, _, _ -> resp("""{"overview":"ok","stockComments":{},"risks":[]}""") }
        val r = repo(LlmConfig("https://api.deepseek.com/v1/", "k", "deepseek-chat"), api)
            .analyze(listOf(stock), emptyMap(), emptyMap(), signals, DividendThresholds())
        assertThat(r).isInstanceOf(LlmAnalysisResult.Success::class.java)
        assertThat((r as LlmAnalysisResult.Success).analysis.overview).isEqualTo("ok")
    }

    @Test
    fun `http 401 maps to API key error`() = runTest {
        val api = api { _, _, _ -> throw httpErr(401) }
        val r = repo(LlmConfig("https://x/", "k", "m"), api).analyze(listOf(stock), emptyMap(), emptyMap(), signals, DividendThresholds())
        assertThat((r as LlmAnalysisResult.Error).message).isEqualTo("API key 无效")
    }

    @Test
    fun `io exception maps to network error`() = runTest {
        val api = api { _, _, _ -> throw java.io.IOException("timeout") }
        val r = repo(LlmConfig("https://x/", "k", "m"), api).analyze(listOf(stock), emptyMap(), emptyMap(), signals, DividendThresholds())
        assertThat((r as LlmAnalysisResult.Error).message).contains("网络")
    }

    private fun resp(content: String) = LlmChatResponse(
        listOf(LlmChatResponse.Choice(LlmMessage("assistant", content)))
    )

    private fun httpErr(code: Int) = HttpException(
        Response.error<Any>(code, "".toResponseBody("application/json".toMediaTypeOrNull()))
    )
}
```

> **Test seam:** `LlmAnalysisRepository` 依赖 `LlmConfigSource`（只读接口，`fun observeConfig(): Flow<LlmConfig>`），生产实现是 `LlmConfigRepository`（Step 3 让它 `implements LlmConfigSource`），测试用上面的 `TestConfigSource`。这样仓储单测不碰 Android `SharedPreferences`。

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.stock.dividend.data.repository.LlmAnalysisRepositoryTest"`
Expected: FAIL（`LlmAnalysisRepository` 未定义）

- [ ] **Step 3: 写实现**

> `LlmConfigSource` 接口 + `LlmConfigRepository implements LlmConfigSource` + `@Binds` 绑定已在 Task 6 完成，本任务**不再改** `LlmConfigRepository.kt`，直接依赖 `LlmConfigSource`。

`LlmAnalysisRepository.kt`：
```kotlin
package com.stock.dividend.data.repository

import com.stock.dividend.data.remote.LlmApi
import com.stock.dividend.data.remote.dto.LlmChatRequest
import com.stock.dividend.data.remote.dto.LlmMessage
import kotlinx.coroutines.flow.first
import retrofit2.HttpException
import javax.inject.Inject
import javax.inject.Singleton

/** 编排 LLM 解读：读配置 → 构造 prompt → 调用 → 解析。 */
@Singleton
class LlmAnalysisRepository @Inject constructor(
    private val llmApi: LlmApi,
    private val configSource: LlmConfigSource,
) {
    suspend fun analyze(
        evaluatedStocks: List<EvaluatedStock>,
        dailyBands: Map<String, BollBand?>,
        monthlyBands: Map<String, BollBand?>,
        signals: PortfolioSignals,
        thresholds: DividendThresholds,
    ): LlmAnalysisResult {
        if (evaluatedStocks.isEmpty()) return LlmAnalysisResult.NotConfigured
        val config = configSource.observeConfig().first()
        if (!config.isComplete) return LlmAnalysisResult.NotConfigured

        val prompt = LlmPromptBuilder.build(evaluatedStocks, dailyBands, monthlyBands, signals, thresholds)
        val url = config.baseUrl.trimEnd('/') + "/chat/completions"
        val request = LlmChatRequest(
            model = config.model,
            messages = listOf(
                LlmMessage("system", prompt.system),
                LlmMessage("user", prompt.user),
            ),
        )
        return try {
            val content = llmApi.chatCompletions(url, "Bearer ${config.apiKey}", request).content
                ?: return LlmAnalysisResult.Error("LLM 返回为空")
            LlmAnalysisResult.Success(LlmAnalysisParser.parse(content))
        } catch (e: HttpException) {
            LlmAnalysisResult.Error(mapHttpError(e.code()))
        } catch (_: Exception) {
            LlmAnalysisResult.Error("网络错误，请重试")
        }
    }

    private fun mapHttpError(code: Int): String = when (code) {
        401, 403 -> "API key 无效"
        429 -> "请求过频，稍后重试"
        else -> "分析失败，请重试"
    }
}
```

- [ ] **Step 4: Run tests to verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.stock.dividend.data.repository.LlmAnalysisRepositoryTest"`
Expected: PASS（5 tests）

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/stock/dividend/data/repository/LlmAnalysisRepository.kt app/src/main/java/com/stock/dividend/data/repository/LlmConfigRepository.kt app/src/test/java/com/stock/dividend/data/repository/LlmAnalysisRepositoryTest.kt
git commit -m "feat(llm): LlmAnalysisRepository 编排 + 测试"
```

---

## Task 11: PortfolioViewModel 集成（多周期 + 信号 + LLM）

**Files:**
- Modify: `app/src/main/java/com/stock/dividend/viewmodel/PortfolioViewModel.kt`

- [ ] **Step 1: UiState 加两个字段**

在 `PortfolioUiState` 的 `evaluation` 字段后加：
```kotlin
    /** 组合策略信号（评估后产出）。 */
    val portfolioSignals: PortfolioSignals? = null,
    /** 日线 BOLL（评估期产出，供 prompt 三周期位置用）。 */
    val dailyBands: Map<String, BollBand?> = emptyMap(),
    /** 月线 BOLL（评估期产出，供 prompt 三周期位置用）。 */
    val monthlyBands: Map<String, BollBand?> = emptyMap(),
    /** LLM 解读状态。 */
    val llmAnalysis: LlmAnalysisState = LlmAnalysisState.Idle
```
顶部加 import：
```kotlin
import com.stock.dividend.data.repository.KlinePeriod
import com.stock.dividend.data.repository.LlmAnalysisRepository
import com.stock.dividend.data.repository.LlmAnalysisResult
import com.stock.dividend.data.repository.LlmAnalysisState
import com.stock.dividend.data.repository.PortfolioAdvisor
import com.stock.dividend.data.repository.PortfolioSignals
```

- [ ] **Step 2: 注入 LlmAnalysisRepository**

在 `PortfolioViewModel` 构造参数（`notificationRuleRepository` 之后）加：
```kotlin
    private val llmAnalysisRepository: LlmAnalysisRepository,
```

- [ ] **Step 3: 改 evaluateVisibleHoldings 拉日/月 BOLL 并算信号**

把现有 `evaluateVisibleHoldings()` 整体替换为：
```kotlin
    fun evaluateVisibleHoldings() {
        viewModelScope.launch {
            val visible = _uiState.value.filteredItems
            if (visible.isEmpty()) {
                _uiState.update {
                    it.copy(isEvaluating = false, evaluation = emptyList(),
                        portfolioSignals = null, dailyBands = emptyMap(), monthlyBands = emptyMap(),
                        llmAnalysis = LlmAnalysisState.Idle)
                }
                return@launch
            }
            _uiState.update {
                it.copy(isEvaluating = true, llmAnalysis = LlmAnalysisState.Idle,
                    portfolioSignals = null, dailyBands = emptyMap(), monthlyBands = emptyMap())
            }
            val thresholds = _evalThresholds.value
            val semaphore = Semaphore(3)  // 每只股 3 次 BOLL 请求，降到 3 并发防限流

            data class EvalRow(val stock: EvaluatedStock, val daily: BollBand?, val monthly: BollBand?)

            val rows = visible.map { item ->
                async {
                    semaphore.withPermit {
                        val weekly = ensureBollLoaded(item.code)
                        val daily = fetchBollForPeriod(item.code, KlinePeriod.DAILY)
                        val monthly = fetchBollForPeriod(item.code, KlinePeriod.MONTHLY)
                        val forecast = _uiState.value.stockForecasts[item.code]
                        val price = forecast?.currentPrice ?: item.currentPrice ?: 0.0
                        val recommendation = HoldingRecommender.recommend(
                            price = price, band = weekly,
                            latestYearlyDividend = forecast?.latestYearlyDividend,
                            thresholds = thresholds
                        )
                        val evaluated = EvaluatedStock(
                            code = item.code, name = item.name, industry = item.industry,
                            action = recommendation.action, priceVsLower = recommendation.priceVsLower,
                            dividendYield = recommendation.dividendYield, bollBand = weekly,
                            currentPrice = price.takeIf { it > 0.0 }, reasons = recommendation.reasons
                        )
                        EvalRow(evaluated, daily, monthly)
                    }
                }
            }.awaitAll()

            val sorted = rows.map { it.stock }.sortedWith(
                compareBy<EvaluatedStock> { it.action.priority() }.thenBy { it.priceVsLower }
            )
            val dailyBands = rows.associate { it.stock.code to it.daily }
            val monthlyBands = rows.associate { it.stock.code to it.monthly }
            val signals = PortfolioAdvisor.evaluate(sorted, dailyBands, monthlyBands)
            _uiState.update {
                it.copy(isEvaluating = false, evaluation = sorted,
                    portfolioSignals = signals, dailyBands = dailyBands, monthlyBands = monthlyBands)
            }
        }
    }

    private suspend fun fetchBollForPeriod(code: String, period: KlinePeriod): BollBand? = try {
        stockRepository.fetchBoll(code, period)
    } catch (_: Exception) { null }
```

- [ ] **Step 4: clearEvaluation 清信号**

替换 `clearEvaluation()`：
```kotlin
    fun clearEvaluation() {
        _uiState.update {
            it.copy(evaluation = null, isEvaluating = false,
                portfolioSignals = null, dailyBands = emptyMap(), monthlyBands = emptyMap(),
                llmAnalysis = LlmAnalysisState.Idle)
        }
    }
```

- [ ] **Step 5: 加 analyzeWithLlm / clearLlmAnalysis**

在 `clearEvaluation()` 之后加：
```kotlin
    /** 触发 LLM 解读（结果页"AI 解读"按钮）。 */
    fun analyzeWithLlm() {
        val current = _uiState.value
        val evaluation = current.evaluation
        val signals = current.portfolioSignals
        if (evaluation.isNullOrEmpty() || signals == null) return  // 按钮已禁用，防御
        val dailyBands = current.dailyBands
        val monthlyBands = current.monthlyBands
        viewModelScope.launch {
            _uiState.update { it.copy(llmAnalysis = LlmAnalysisState.Loading) }
            val result = llmAnalysisRepository.analyze(evaluation, dailyBands, monthlyBands, signals, _evalThresholds.value)
            val state = when (result) {
                is LlmAnalysisResult.Success -> LlmAnalysisState.Success(result.analysis)
                LlmAnalysisResult.NotConfigured -> LlmAnalysisState.NotConfigured
                is LlmAnalysisResult.Error -> LlmAnalysisState.Error(result.message)
            }
            _uiState.update { it.copy(llmAnalysis = state) }
        }
    }

    fun clearLlmAnalysis() {
        _uiState.update { it.copy(llmAnalysis = LlmAnalysisState.Idle) }
    }
```

- [ ] **Step 6: 编译验证**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/stock/dividend/viewmodel/PortfolioViewModel.kt
git commit -m "feat(vm): 多周期 BOLL + 策略信号 + LLM 解读接入"
```

---

## Task 12: PortfolioEvaluationScreen 加信号区 + AI 区块

**Files:**
- Modify: `app/src/main/java/com/stock/dividend/ui/screen/PortfolioEvaluationScreen.kt`

- [ ] **Step 1: 加两个 Composable**

在文件末尾追加：
```kotlin
@Composable
private fun PortfolioSignalsSection(signals: PortfolioSignals) {
    val pc = signals.positionControl
    if (pc.triggered) {
        Surface(
            tonalElevation = 2.dp,
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.errorContainer,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
        ) {
            Column(Modifier.padding(12.dp)) {
                Text("⚠ 建议控制仓位", style = MaterialTheme.typography.titleSmall)
                Text(
                    "多数股票处于上轨（${"%.0f".format(pc.upperBandRatio * 100)}%），平均股息率 ${"%.1f".format(pc.avgDividendYield)}%。\n建议保留现金 ≥ ${pc.targetCashPercent}%。",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
    if (signals.buySignals.isNotEmpty()) {
        Surface(
            tonalElevation = 2.dp,
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.tertiaryContainer,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
        ) {
            Column(Modifier.padding(12.dp)) {
                Text("▲ 三周期共振买点", style = MaterialTheme.typography.titleSmall)
                Text(
                    signals.buySignals.joinToString("、") { it.code },
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun LlmAnalysisSection(
    state: LlmAnalysisState,
    onAnalyze: () -> Unit,
    onRetry: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        when (state) {
            is LlmAnalysisState.Idle, LlmAnalysisState.NotConfigured -> {
                OutlinedButton(onClick = onAnalyze, enabled = state is LlmAnalysisState.Idle) {
                    Icon(Icons.Filled.AutoAwesome, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("AI 解读")
                }
                if (state is LlmAnalysisState.NotConfigured) {
                    Text("需先在设置配置 LLM", style = MaterialTheme.typography.labelSmall)
                }
            }
            LlmAnalysisState.Loading -> {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("AI 分析中…")
            }
            is LlmAnalysisState.Success -> {
                val a = state.analysis
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("✨ AI 解读", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(4.dp))
                        Text(a.overview, style = MaterialTheme.typography.bodyMedium)
                        if (a.risks.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            a.risks.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "仅供参考，买卖建议以规则评估为准。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            is LlmAnalysisState.Error -> {
                Text(state.message, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = onRetry) { Text("重试") }
            }
        }
    }
}
```
顶部补 import（按编译提示加）：`fillMaxWidth`, `padding`, `size`, `height`, `width`, `Column`, `Row`, `Spacer`, `RoundedCornerShape`, `MaterialTheme`, `Surface`, `ElevatedCard`, `OutlinedButton`, `TextButton`, `CircularProgressIndicator`, `Icon`, `Icons`, `AutoAwesome`, 以及 `PortfolioSignals`, `LlmAnalysisState`。

- [ ] **Step 2: 在结果内容顶部插入信号区 + AI 区块**

在 `EvaluationContent`（渲染摘要 pills 之后、分组卡片列表之前）插入：
```kotlin
        uiState.portfolioSignals?.let { signals -> PortfolioSignalsSection(signals) }
        LlmAnalysisSection(
            state = uiState.llmAnalysis,
            onAnalyze = viewModel::analyzeWithLlm,
            onRetry = viewModel::analyzeWithLlm,
        )
```
并在单股 `EvaluationCard`（渲染 `reasons` 之后）追加 AI 简评行：
```kotlin
        val comment = (uiState.llmAnalysis as? LlmAnalysisState.Success)?.analysis?.stockComments?.get(stock.code)
        if (comment != null) {
            Text("AI：$comment", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
```

- [ ] **Step 3: 编译验证**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL（按编译提示补 import）

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/stock/dividend/ui/screen/PortfolioEvaluationScreen.kt
git commit -m "feat(ui): 评估结果页加策略信号区 + AI 解读区块"
```

---

## Task 13: 设置页加 LLM 配置

**Files:**
- Modify: `app/src/main/java/com/stock/dividend/ui/screen/NotificationSettingsScreen.kt`
- Modify: `app/src/main/java/com/stock/dividend/viewmodel/NotificationSettingsViewModel.kt`

- [ ] **Step 1: ViewModel 加 LLM 配置读写**

在 `NotificationSettingsViewModel` 加注入 `llmConfigRepository: LlmConfigRepository`，并加：
```kotlin
    val llmConfigState: StateFlow<LlmConfig> =
        llmConfigRepository.observeConfig().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LlmConfig("", "", ""))

    private val _provider = MutableStateFlow(LlmProviderPreset.CUSTOM)
    val provider: StateFlow<LlmProviderPreset> = _provider

    fun setProvider(p: LlmProviderPreset) {
        _provider.value = p
        viewModelScope.launch {
            val updated = LlmProviderPreset.apply(p, llmConfigRepository.snapshot())
            llmConfigRepository.saveConfig(updated)
        }
    }

    fun saveLlmConfig(baseUrl: String, apiKey: String, model: String) {
        viewModelScope.launch {
            llmConfigRepository.saveConfig(LlmConfig(baseUrl.trim(), apiKey.trim(), model.trim()))
        }
    }
```
加 import：`LlmConfig`, `LlmConfigRepository`, `LlmProviderPreset`, `stateIn`, `SharingStarted`, `MutableStateFlow`, `StateFlow`。

- [ ] **Step 2: 设置页加 LlmConfigSettingsContent**

在 `NotificationSettingsScreen.kt` 末尾追加 Composable：
```kotlin
@Composable
fun LlmConfigSettingsContent(viewModel: NotificationSettingsViewModel) {
    val config by viewModel.llmConfigState.collectAsState()
    val provider by viewModel.provider.collectAsState()
    var apiKey by remember(config.apiKey) { mutableStateOf(config.apiKey) }
    var baseUrl by remember(config.baseUrl) { mutableStateOf(config.baseUrl) }
    var model by remember(config.model) { mutableStateOf(config.model) }
    var showKey by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text("LLM 配置", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text("用于一键评估的 AI 解读。Key 仅存本机。", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(12.dp))
        LlmProviderPreset.entries.forEach { p ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = provider == p, onClick = {
                    viewModel.setProvider(p)
                    if (p != LlmProviderPreset.CUSTOM) {
                        baseUrl = p.baseUrl; model = p.defaultModel
                    }
                })
                Text(p.displayName)
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(value = baseUrl, onValueChange = { baseUrl = it },
            label = { Text("Base URL") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(value = apiKey, onValueChange = { apiKey = it },
            label = { Text("API Key") },
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "隐藏" else "显示") }
            },
            modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(value = model, onValueChange = { model = it },
            label = { Text("Model") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        Button(onClick = { viewModel.saveLlmConfig(baseUrl, apiKey, model) }) {
            Text("保存")
        }
    }
}
```
补 import：`collectAsState`, `remember`, `mutableStateOf`, `RadioButton`, `Alignment`, `VisualTransformation`, `PasswordVisualTransformation`, `OutlinedTextField`, `Button`, `LlmProviderPreset`。

- [ ] **Step 3: 设置入口列表加一项**

在 `settingsEntries`（或等价的设置入口列表）加一项指向 `LlmConfigSettingsContent`（仿现有"评估门槛"入口的模式：点击导航/展开到该 Composable）。

- [ ] **Step 4: 编译验证**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/stock/dividend/ui/screen/NotificationSettingsScreen.kt app/src/main/java/com/stock/dividend/viewmodel/NotificationSettingsViewModel.kt
git commit -m "feat(settings): LLM 配置入口（厂商预设 + key + model）"
```

---

## Task 14: 全量构建 + 手动验证

- [ ] **Step 1: 全量单测**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL，新增 5 个测试类全绿。

- [ ] **Step 2: 构建 APK**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 手动验证（真机/模拟器）**

1. 设置页配置 DeepSeek（填 apiKey）→ 保存。
2. 持仓页点"一键评估" → 结果页出现：
   - 顶部摘要（现有）。
   - 多数股在上轨 + 股息低时 → **控仓信号卡**出现；否则不出现。
   - 若有股满足三周期共振 → **共振买点卡**出现（稀有，可构造低价场景验证）。
   - "AI 解读"按钮。
3. 点"AI 解读" → Loading → 成功显示 overview + risks + 单股 AI 简评行；底部免责声明在。
4. 未配置 key 时点 → "需先在设置配置 LLM"。
5. 断网/错误 key → Error 文案 + 重试。
6. 规则评估卡片始终可见（LLM 失败不影响）。

- [ ] **Step 4: 最终提交（如有验证修复）**

```bash
git add -A
git commit -m "chore(llm): 手动验证修复"
```

---

## Self-Review 记录

- **Spec 覆盖**：多周期 BOLL（T1-3）、PortfolioAdvisor 仓位+共振（T4）、LLM 配置/预设/状态（T5）、配置仓储（T6）、网络层 DTO+Api+DI（T7）、prompt（T8）、parser（T9）、编排（T10）、VM 集成（T11）、UI 信号+AI（T12）、设置（T13）、验证（T14）——spec §3-§9 全覆盖。
- **占位符**：T10 测试中标注的 `TestConfigSource` / SAM→object 改写已给出完整代码，无 TBD。
- **类型一致**：`PortfolioAdvisor.evaluate(stocks, dailyBands, monthlyBands)` 在 T4/T11 一致；`LlmAnalysisRepository.analyze(evaluation, dailyBands, monthlyBands, signals, thresholds)` 在 T10/T11 一致；`LlmPromptBuilder.build(stocks, dailyBands, monthlyBands, signals, thresholds)` 在 T8/T10 一致；`fetchBoll(code, period)` 在 T3/T11 一致；`LlmConfigSource` 接口在 T6/T10 一致。
- **已知偏离 spec**：T11 把日/月 BOLL 作为评估期局部 map（不入 UiState），比 spec §4.1 的 `_stockBandsByPeriod` 更简单且不违反非目标（不缓存多周期）。
```
