# 一键评估持仓 (Portfolio Evaluation) — 设计文档

**日期:** 2026-07-26
**状态:** Draft (待用户审核)
**作者:** brainstorming skill

---

## 1. 背景与目标

### 问题
持仓页 (`PortfolioScreen`) 现有每只股票的 boll 带图 (`BollPriceScale`)，但买/卖/持有的判断逻辑 (`bollTone()`) 被 `private` 锁在单个 composable 内，用户无法一眼看到整个持仓的全局建议，也没有把股息率纳入决策。

### 目标
提供"一键评估"功能：用户点击后，对**当前筛选后可见**的持仓股票，根据 **boll 带位置 + 股息率门槛**给出每只股票的"买 / 卖 / 持有 / 数据不足"建议，并在**独立结果页**展示可解释的理由。

### 非目标 (YAGNI)
- ❌ 不做评分加权打分（0-100 分），保持规则可解释
- ❌ 不评估自选股（shares=0）
- ❌ 不在卡片上加持久徽章（结果走独立页）
- ❌ 不改 DB schema（复用现有 `notification_rules` 表）
- ❌ 不写 Compose UI 测试（与现有仓库惯例一致）

---

## 2. 关键决策（来自 brainstorming）

| 决策点 | 选择 | 理由 |
|---|---|---|
| 评估范围 | 当前筛选后可见的持仓股 | 省 API 请求；用户可用现有筛选器收窄范围 |
| 决策规则 | boll 主导 + 股息率软门槛 | 复用已验证的 `bollTone` 逻辑，门槛可调 |
| 入口位置 | "个股持仓"标题栏 TextButton | 与现有 "+ 添加股票" 一致，不占 FAB 槽 |
| 结果展示 | 独立路由 `portfolioEvaluation` | 容纳更多决策细节，不打乱卡片布局 |
| 门槛存储 | 复用 `notification_rules` 表 + 新 type | 无需 DB 迁移；沿用 Room + Flow 模式 |
| 门槛配置 UI | SettingsScreen 新增入口 | 与现有通知阈值设置一致 |
| 规则位置 | 纯函数 `HoldingRecommender` | 易测；评估页与 boll 图共享单点真相 |
| 评估状态挂载 | 共享 `PortfolioViewModel` | 复用 `stockBands`/`stockForecasts` 缓存 |

---

## 3. 评估规则（核心）

### 3.1 数据模型

```kotlin
// data/repository/HoldingRecommender.kt

enum class HoldingAction { BUY, SELL, HOLD, INSUFFICIENT_DATA }

data class DividendThresholds(
    val minYieldPercent: Double = 2.0,   // 低于此不给"买"
    val boostYieldPercent: Double = 5.0, // 高于此把"持有"上调为"买"
)

data class HoldingRecommendation(
    val action: HoldingAction,
    val bollTone: BollTone,              // Buy / Current / Sell（提取自 BollPriceScale）
    val priceVsLower: Double,            // (price - lower) / (upper - lower)，0=下轨, 1=上轨, 可能为 NaN
    val dividendYield: Double?,          // 股息率 %，无法算则 null
    val reasons: List<String>,           // 人话解释，给结果页用
)

object HoldingRecommender {
    fun recommend(
        price: Double,
        band: BollBand?,
        latestYearlyDividend: Double?,
        thresholds: DividendThresholds,
    ): HoldingRecommendation
}
```

### 3.2 决策步骤

1. **数据有效性检查**
   - `band == null` 或 `price` 非正/非有限 → `INSUFFICIENT_DATA`，理由 "周线 boll 数据不足" 或 "无有效报价"

2. **基础 tone**（沿用 `BollPriceScale.bollTone` 现有逻辑，提取为共享内部步骤）
   - `price <= lower` → `BUY`
   - `price >= upper` → `SELL`
   - 否则算偏离 `dev = |price - middle| / ((upper - lower) / 2)`：
     - `dev < 0.30` → `Current`（HOLD）
     - `price < middle` → `BUY`
     - 否则 → `SELL`

3. **股息率软门槛**（仅当 `latestYearlyDividend != null` 且 `price > 0` 时应用）
   - 计算 `yield = latestYearlyDividend / price * 100`
   - **降级**：若基础 tone 是 `BUY` 且 `yield < minYieldPercent` → 改 `HOLD`，加理由 "虽在下轨但股息率偏低 (${yield}%)"
   - **升级**：若基础 tone 是 `Current`(HOLD) 且 `yield >= boostYieldPercent` → 改 `BUY`，加理由 "股息率较高 (${yield}%)，boll 中轨附近"
   - `SELL` 不受股息率影响（高股息率不抵过热价位）

4. **reasons 汇总**
   - 至少包含一条 boll 位置描述（如 "价格接近下轨"）
   - 门槛触发时附加对应理由
   - 每条简短（< 30 字），供 UI 直接展示

### 3.3 与 BollPriceScale 的关系

`BollPriceScale.kt` 里现有的 `private enum BollTone` + `private fun bollTone()` 提取到 `HoldingRecommender.kt` 作为顶层（或 internal）共享元素：
- `BollTone` enum 提升为 `data/repository` 包内可见
- `bollTone(price, upper, middle, lower)` 作为 `HoldingRecommender` 的 internal 步骤函数
- `BollPriceScale` 改为调用 `HoldingRecommender` 暴露的 tone 计算，保持渲染逻辑不变
- 保证评估页与 boll 图用的是**同一套** boll 判断逻辑

---

## 4. 数据流与状态

### 4.1 评估数据类（ViewModel 层）

```kotlin
// 在 PortfolioViewModel.kt 内，或新建 viewmodel/EvaluatedStock.kt
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
    val reasons: List<String>,
)
```

### 4.2 PortfolioViewModel 扩展

新增字段（合并到现有 `PortfolioUiState`）：
- `isEvaluating: Boolean = false`
- `evaluation: List<EvaluatedStock>? = null`  // null = 未评估过

新增方法：
```kotlin
fun evaluateVisibleHoldings() {
    viewModelScope.launch {
        _uiState.update { it.copy(isEvaluating = true) }
        val visible = applyPortfolioFilter(...).items  // 当前筛选后的持仓
        val thresholds = _evalThresholds.value
        val results = visible.map { item ->
            async {
                val band = ensureBollLoaded(item.code)   // 见下
                val forecast = stockForecasts[item.code]
                HoldingRecommender.recommend(
                    price = forecast?.currentPrice ?: item.currentPrice ?: 0.0,
                    band = band,
                    latestYearlyDividend = forecast?.latestYearlyDividend,
                    thresholds = thresholds,
                ).toEvaluatedStock(item)
            }
        }.awaitAll()
        val sorted = results.sortedWith(
            compareBy<EvaluatedStock> { it.action.priority() }  // BUY < HOLD < SELL < INSUFFICIENT_DATA
                .thenBy { it.priceVsLower }                    // 越靠近下轨越前
        )
        _uiState.update { it.copy(isEvaluating = false, evaluation = sorted) }
    }
}
```

`HoldingAction.priority()` 返回 Int（BUY=0, HOLD=1, SELL=2, INSUFFICIENT_DATA=3），定义在 enum 上或顶层扩展函数。

**`ensureBollLoaded(code)`：** 包装现有 `loadBoll`，若 `stockBands` 无 key 则触发加载并等结果；已有 key（含 null）直接返回。

**并发与限流：** 用 `async` + `awaitAll` 并发，但用 `Semaphore(4)` 限流，避免一次性几十个 Tencent 请求被拒。

**排序：** 结果按 `action` 优先级（BUY → HOLD → SELL → INSUFFICIENT_DATA），同组内按 `priceVsLower` 升序（越靠近下轨越前）。

**门槛读取：** `init` 里 `notificationRuleRepository.observeEvalThresholds().collect { _evalThresholds.value = it }`。

### 4.3 导航

`MainScaffold.kt` NavHost 加：
```kotlin
composable("portfolioEvaluation") {
    // 关键：拿到 portfolio tab 的 back-stack entry，共享同一个 PortfolioViewModel
    val parentEntry = remember(it) { navController.getBackStackEntry("portfolio") }
    PortfolioEvaluationScreen(
        viewModel = hiltViewModel(parentEntry),
        onBack = { navController.popBackStack() },
    )
}
```

`PortfolioScreen` 接收回调 `onNavigateToEvaluation: () -> Unit`，"一键评估"按钮点击后调 `viewModel.evaluateVisibleHoldings()` 紧接 `onNavigateToEvaluation()`。

---

## 5. UI 结构

### 5.1 入口（PortfolioScreen）

"个股持仓" section header 行（现有 "+ 添加股票" TextButton 旁）加：
```kotlin
TextButton(
    onClick = {
        viewModel.evaluateVisibleHoldings()
        onNavigateToEvaluation()
    },
    enabled = !uiState.isEvaluating && visibleItems.isNotEmpty(),
) {
    Icon(Icons.Filled.Analytics, null)
    Spacer(Modifier.width(4.dp))
    Text("一键评估")
}
```
评估中按钮禁用，旁边可加 14dp `CircularProgressIndicator`。

### 5.2 结果页 PortfolioEvaluationScreen

```
┌────────────────────────────────────┐
│ ←  持仓评估                         │  TopAppBar
├────────────────────────────────────┤
│  买 3   持有 5   卖 1   数据不足 0  │  摘要 StatusPills
├────────────────────────────────────┤
│  ▼ 买入信号                         │  section header (FinanceGreen)
│  ┌──────────────────────────────┐  │
│  │ 招商银行  600036  [银行]      │  │
│  │                  [买] (大号)  │  │  EvaluationCard
│  │ 距下轨 5%   股息率 5.2%       │  │
│  │ • 价格接近下轨                │  │
│  │ • 股息率较高 (5.2%)           │  │
│  └──────────────────────────────┘  │
│  ...                                │
├────────────────────────────────────┤
│  ▼ 持有                             │
│  ...                                │
├────────────────────────────────────┤
│  [重新评估]      [清除结果]         │  底部固定 Button 行
└────────────────────────────────────┘
```

**状态：**
- `isEvaluating == true` → `CircularProgressIndicator` + "正在拉取周线 boll 数据…"
- `evaluation == null` → 空状态 "未评估，返回持仓页点击'一键评估'"
- `evaluation!!.isEmpty()` → "当前筛选下无持仓股"

**EvaluationCard 元素：**
- 左：股票名 (titleMedium) + 代码 (labelSmall) + 行业 chip (现有 `"$shares 股"` Box 风格)
- 右：大号 `StatusPill`（FinanceStatusTone.Positive/Warning/Negative/Neutral → BUY/HOLD/SELL/INSUFFICIENT_DATA），文字 "买/持有/卖/数据不足"
- 第二行：`priceVsLower` → "距下轨 X%"（X = priceVsLower * 100，仅 upper>lower 时显示）；股息率 "股息率 X%"（dividendYield == null 时显示 "股息率 —"）
- 第三行：reasons 每条前缀小圆点 (bullet)，`labelSmall`

**颜色：** 全部走 `FinanceStatusTone` + `DesignSystem.StatusPill`，与现有 FinanceRed/Green 一致。

---

## 6. 设置项：股息率门槛

### 6.1 存储

复用 `notification_rules` 表，新增两个 type 常量（**无需 DB 迁移**，`type` 是 string 列）：
```kotlin
// NotificationRuleEntity.kt
companion object {
    const val NOTIFICATION_RULE_TYPE_DIVIDEND_YIELD_THRESHOLD = "DIVIDEND_YIELD_THRESHOLD"
    const val EVAL_MIN_YIELD = "EVAL_MIN_YIELD"        // 新增
    const val EVAL_BOOST_YIELD = "EVAL_BOOST_YIELD"    // 新增
}
```
两条 global 行（`stockCode = null`），`thresholdPercent` 字段存阈值。

### 6.2 Repository 扩展

`NotificationRuleRepository.kt` 新增：
```kotlin
fun observeEvalThresholds(): Flow<DividendThresholds>   // 读两条行，缺省用默认值
suspend fun saveEvalThresholds(min: Double, boost: Double)
```

### 6.3 设置 UI

`SettingsScreen` (`NotificationSettingsScreen.kt`) 的 `settingsEntries` 加一项 "评估门槛"，点击展开/进入编辑区（仿现有 `NotificationSettingsContent`）：
- `OutlinedTextField("最低股息率 (%)")` → `minYieldPercent`，默认 2.0，校验 `> 0`
- `OutlinedTextField("加分股息率 (%)")` → `boostYieldPercent`，默认 5.0，校验 `>= minYieldPercent`
- `Button("保存")` → 调 `saveEvalThresholds(...)`
- 用 `OutlinedTextField` + `toDoubleOrNull()` 模式（仓库内**无 Slider 使用**，保持一致）

---

## 7. 测试策略

沿用 JUnit4 + Truth + MockK + kotlinx-coroutines-test，**TDD：先写测试再写实现**。

### 7.1 HoldingRecommenderTest.kt（纯函数，最重要）

仿 `BollCalculatorTest` 风格，覆盖：
- boll 下轨 + 高股息率 → `BUY`
- boll 下轨 + 低股息率 (< min) → `HOLD`（降级触发）
- boll 上轨 → `SELL`（股息率不影响，即使高股息率）
- boll 中轨 + 高股息率 (≥ boost) → `BUY`（升级触发）
- boll 中轨 + 低股息率 → `HOLD`
- boll 中轨上方但 < upper（dev ≥ 0.30, price > middle）→ `SELL`
- `band == null` → `INSUFFICIENT_DATA`，理由含 "boll"
- `price` 非法 (≤0 或 NaN) → `INSUFFICIENT_DATA`
- `latestYearlyDividend == null` → 不应用门槛，结果 == 纯 boll tone
- `reasons` 非空，门槛触发时含 "股息率"
- 自定义 `DividendThresholds` 生效（min=3, boost=6）

### 7.2 NotificationRuleRepositoryTest 扩展

仿已有 threshold 测试：
- `observeEvalThresholds` 默认值（无行时返回 DividendThresholds(2.0, 5.0)）
- `saveEvalThresholds` 后 observe 收到新值
- 只有一行存在时，缺的那条用默认

### 7.3 PortfolioViewModelTest 扩展

仿现有 `loadBoll` 测试 (lines 458-503)：
- `evaluateVisibleHoldings()` mock `fetchBoll` 返回不同 band，断言 `uiState.evaluation` 列表与 action 正确
- 评估中 `isEvaluating == true`，完成后 false
- 结果排序：BUY 在前
- `INSUFFICIENT_DATA` 当 band 为 null 时产生
- 门槛 Flow 变化后重新评估生效

### 7.4 不写测试的部分

- Compose UI（与仓库惯例一致）
- `BollPriceScale` 重构后渲染不变（手动验证）

---

## 8. 文件改动清单

### 新增
- `app/src/main/java/com/stock/dividend/data/repository/HoldingRecommender.kt`
- `app/src/main/java/com/stock/dividend/ui/screen/PortfolioEvaluationScreen.kt`
- `app/src/main/java/com/stock/dividend/ui/component/EvaluationCard.kt`（或内联进 screen）
- `app/src/test/java/com/stock/dividend/data/repository/HoldingRecommenderTest.kt`

### 修改
- `app/src/main/java/com/stock/dividend/ui/component/BollPriceScale.kt` — `BollTone` + `bollTone()` 提取，改为调 `HoldingRecommender`
- `app/src/main/java/com/stock/dividend/data/local/entity/NotificationRuleEntity.kt` — 加 2 个 type 常量
- `app/src/main/java/com/stock/dividend/data/repository/NotificationRuleRepository.kt` — 加 observe/save eval thresholds
- `app/src/main/java/com/stock/dividend/viewmodel/PortfolioViewModel.kt` — 加评估 state + `evaluateVisibleHoldings()` + 门槛 Flow 收集 + `EvaluatedStock` 数据类
- `app/src/main/java/com/stock/dividend/ui/screen/PortfolioScreen.kt` — 标题栏加"一键评估" TextButton + 导航回调
- `app/src/main/java/com/stock/dividend/ui/screen/NotificationSettingsScreen.kt` — SettingsScreen 加"评估门槛"入口和编辑 UI
- `app/src/main/java/com/stock/dividend/ui/screen/MainScaffold.kt` — NavHost 加 `portfolioEvaluation` 路由，共享 PortfolioViewModel
- `app/src/test/java/com/stock/dividend/data/repository/NotificationRuleRepositoryTest.kt` — 扩展 eval threshold 用例
- `app/src/test/java/com/stock/dividend/viewmodel/PortfolioViewModelTest.kt` — 扩展评估用例

### 不动
- `BollCalculator.kt`、`KlineRepository.kt`、`StockRepository.fetchBoll`（已够用）
- DB schema（`AppDatabase` version 保持 14，无需迁移）
- `MainScaffold` FAB（不动）

---

## 9. 风险与未决

- **Tencent API 限流：** 并发拉几十只 boll 可能被拒。用 `Semaphore(4)` 限流缓解；INSUFFICIENT_DATA 作为兜底。
- **PortfolioViewModel 共享：** 需确认 `portfolio` 路由在 `portfolioEvaluation` 之前确实在 back stack 中。实施时验证 `getBackStackEntry("portfolio")` 不抛异常。
- **门槛语义：** `EVAL_MIN_YIELD` / `EVAL_BOOST_YIELD` 与现有 `DIVIDEND_YIELD_THRESHOLD`（通知用）语义不同，type 字符串区分；如未来混淆可考虑加文档注释。
