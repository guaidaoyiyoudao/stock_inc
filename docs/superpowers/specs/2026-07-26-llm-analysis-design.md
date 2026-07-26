# 一键评估 LLM 解读增强 (LLM Analysis) — 设计文档

**日期:** 2026-07-26
**状态:** Draft (待用户审核)
**作者:** brainstorming skill

---

## 1. 背景与目标

### 问题
现有"一键评估"（见 `2026-07-26-portfolio-evaluation-design.md`，已实现）是**纯规则引擎**：`HoldingRecommender` 基于**周线** BOLL 位置 + 股息率门槛，输出每只股的买/持/卖建议和模板化 `reasons`。短板：

- 无法做**组合层面综合判断**（整体偏防御/进攻、集中度、互补性）。
- 缺少**组合层策略信号**（如"多数股过热 → 控仓"）。
- 只有**单一周期（周线）**，无法判定经典的**日/周/月三周期共振买点**。
- `reasons` 是机械短语，缺乏连贯解读。

### 目标
1. **多周期 BOLL**：在周线基础上新增**日 / 月 BOLL**，为三周期共振提供数据。
2. **策略信号层**（确定性）：新增 `PortfolioAdvisor`，产出两类结构化信号：
   - **仓位控制信号**：多数股票到上轨 + 整体股息率偏低 → 提示控仓，现金 ≥ 15%。
   - **三周期共振买点**：某股同时满足 `日下轨 + 周下轨 + 月中轨以下` → 提示买入。
3. **LLM 解读**：用户**手动触发**一次 LLM 调用，把规则评估 + 策略信号作为结构化上下文，产出 (1) 组合总评、(2) 每只股 1-2 句解读、(3) 风险提示。规则/策略与 LLM **完全解耦**。

### 非目标 (YAGNI)
- ❌ 不让 LLM 判定 BOLL 位置/共振条件（确定性规则负责，LLM 只解读）
- ❌ 不替换 `HoldingRecommender`（周线买卖建议仍由它出；共振买点是**额外**信号）
- ❌ 不每次评估自动调 LLM（手动触发，控成本）
- ❌ 不缓存/持久化分析结果与多周期 BOLL（每次评估重拉，后续可加缓存）
- ❌ 不回测策略信号准确率（信号是辅助提示，非交易指令）
- ❌ 不加密 API key（与现有 SharedPreferences 惯例一致，标记后续改进）
- ❌ 不写 Compose UI 测试

---

## 2. 关键决策（来自 brainstorming）

| 决策点 | 选择 | 理由 |
|---|---|---|
| LLM 定位 | 规则之上的补充解读层（A1 解读员） | 规则/策略是买卖基准，LLM 不与之冲突；独立视角走 `risks` 字段 |
| 策略信号 | **确定性代码**（`PortfolioAdvisor`），非 prompt | 条件精确可判定；可测、可靠、不幻觉 |
| 调用粒度 | **单次批量**（整个组合一次 LLM 调用） | 1 次成本最低；唯一能做组合视角的方式 |
| 触发方式 | 结果页手动"AI 解读"按钮 | 用户决定何时花 token |
| 多周期 BOLL | 日 / 周 / 月 三周期（新增日、月） | 用户的三周期共振策略需要；腾讯 fqkline 原生支持周期参数 |
| LLM 协议 | OpenAI 兼容 `chat/completions` | DeepSeek/智谱/通义均兼容；用户自选厂商 |
| 提供商 | 用户配置（baseUrl + apiKey + model） | 开发者不承担成本；隐私由用户掌控 |
| 配置存储 | **SharedPreferences**（`LlmConfigRepository`） | 项目已用；`notification_rules` 无 string 列且语义是"规则"；无需 DB 迁移 |
| Prompt/解析 | 纯函数（`LlmPromptBuilder` / `LlmAnalysisParser`） | 对标 `HoldingRecommender`，无 Android 依赖，可单测 |
| 动态 baseUrl | Retrofit `@Url` 全路径 | 绕开 Retrofit 静态 baseUrl 限制 |
| 输出格式 | JSON（`response_format: json_object`）+ 纯文本兜底 | 结构化解析；JSON 模式失败时降级 |
| 状态挂载 | 共享 `PortfolioViewModel` | 与 `evaluation` 同生命周期 |

---

## 3. 数据模型

### 3.1 LLM 配置与分析结果

```kotlin
// data/repository/LlmConfig.kt
data class LlmConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
) {
    val isComplete: Boolean
        get() = baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()
}

// data/repository/LlmAnalysis.kt
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
    data class Success(val analysis: LlmAnalysis) : LlmAnalysisState
    data object NotConfigured : LlmAnalysisState
    data class Error(val message: String) : LlmAnalysisState
}
```

### 3.2 策略信号（PortfolioAdvisor 产出）

```kotlin
// data/repository/PortfolioAdvisor.kt

/** 仓位控制信号（组合层） */
data class PositionControlSignal(
    val triggered: Boolean,
    val upperBandRatio: Double,      // 处于/接近上轨的股票占比 [0,1]
    val avgDividendYield: Double,    // 平均股息率 %
    val targetCashPercent: Int = 15, // 建议现金占比下限
)

/** 单股三周期共振买点 */
data class MultiTimeframeBuySignal(
    val code: String,
    val dailyAtLower: Boolean,
    val weeklyAtLower: Boolean,
    val monthlyBelowMiddle: Boolean,
    val resonant: Boolean,           // 三者同时满足
)

data class PortfolioSignals(
    val positionControl: PositionControlSignal,
    val buySignals: List<MultiTimeframeBuySignal>,  // 仅含 resonant=true 的
)

/** 策略参数（可后续做配置项，先硬编码默认值） */
data class PortfolioAdvisorConfig(
    val minUpperBandRatio: Double = 0.5,  // 上轨占比 ≥ 此值视为"多数过热"
    val maxAvgDividendYield: Double = 2.0,// 平均股息率 < 此值视为"股息偏低"
    val upperProximityThreshold: Double = 0.9, // priceVsLower ≥ 此值视为"抵达上轨"
    val targetCashPercent: Int = 15,
)
```

---

## 4. 组件设计

### 4.1 多周期 BOLL 数据层

**`KlineRepository`** 扩展——参数化周期（腾讯 fqkline 的 `kind` 参数支持日/周/月）：
```kotlin
enum class KlinePeriod { DAILY, WEEKLY, MONTHLY }

class KlineRepository(...) {
    suspend fun fetchCloses(code: String, period: KlinePeriod): List<Double>  // 升序
}
```
现有 `fetchWeeklyCloses` 改为 `fetchCloses(code, WEEKLY)` 的薄封装（保持向后兼容）。

**`StockRepository`** 扩展：
```kotlin
suspend fun fetchBoll(code: String, period: KlinePeriod): BollBand?
```
复用 `BollCalculator.calculate`（不足 20 根返回 null）。`fetchBoll(code)`（周线）保留为旧调用点的兼容封装。

**ViewModel 缓存**：`_stockBands`（周线）扩展为 `_stockBandsByPeriod: Map<KlinePeriod, Map<String, BollBand?>>`，`ensureBollLoaded(code, period)` 同理防重试。

### 4.2 配置层

**`LlmConfigRepository`**（`data/repository/LlmConfigRepository.kt`）—— 持有 `SharedPreferences("llm_prefs")`：
```kotlin
class LlmConfigRepository(context: Context) {
    fun observeConfig(): Flow<LlmConfig>
    suspend fun saveConfig(config: LlmConfig)
    suspend fun clearConfig()
}
```
读写 key：`llm_base_url` / `llm_api_key` / `llm_model`。

**`LlmProviderPresets`**（`data/repository/LlmProviderPresets.kt`）：

| 厂商 | baseUrl | 默认 model |
|---|---|---|
| DeepSeek | `https://api.deepseek.com/v1/` | `deepseek-chat` |
| 智谱 GLM | `https://open.bigmodel.cn/api/paas/v4/` | `glm-4-flash` |
| 通义千问 | `https://dashscope.aliyuncs.com/compatible-mode/v1/` | `qwen-turbo` |

### 4.3 网络层

**`LlmApi`**（`data/remote/LlmApi.kt`）—— `@Url` 传全路径绕开静态 baseUrl：
```kotlin
interface LlmApi {
    @POST
    suspend fun chatCompletions(
        @Url url: String,
        @Header("Authorization") auth: String,  // "Bearer <apiKey>"
        @Body body: LlmChatRequest,
    ): LlmChatResponse
}
```
DTO（`data/remote/dto/LlmChatRequest.kt`、`LlmChatResponse.kt`）：标准 OpenAI 结构，`response_format: {type: "json_object"}`，`temperature: 0.3`。

**DI**（`di/NetworkModule.kt`）：新增独立 `OkHttpClient`（**60s 超时**）+ Retrofit（baseUrl 占位），`@Provides LlmApi`。

### 4.4 策略信号层（`PortfolioAdvisor`，纯函数）

```kotlin
object PortfolioAdvisor {
    fun evaluate(
        evaluatedStocks: List<EvaluatedStock>,
        dailyBands: Map<String, BollBand?>,
        weeklyBands: Map<String, BollBand?>,
        monthlyBands: Map<String, BollBand?>,
        config: PortfolioAdvisorConfig = PortfolioAdvisorConfig(),
    ): PortfolioSignals
}
```

**仓位控制判定**：
- `upperBandRatio = count(priceVsLower >= upperProximityThreshold) / total`（`priceVsLower` 取自 `EvaluatedStock`，周线）。
- `avgDividendYield = non-null yields 的平均`。
- `triggered = upperBandRatio >= minUpperBandRatio && avgDividendYield < maxAvgDividendYield`。
- 触发时 `targetCashPercent = 15`，附理由数值。

**三周期共振判定**（每股，需 `currentPrice`）：
- `dailyAtLower = currentPrice <= dailyBand.lower`
- `weeklyAtLower = currentPrice <= weeklyBand.lower`
- `monthlyBelowMiddle = currentPrice < monthlyBand.middle`
- `resonant = dailyAtLower && weeklyAtLower && monthlyBelowMiddle`
- 任一周期 band 为 null → 该股不产出信号（数据不足）。
- `buySignals` 只收 `resonant == true` 的。

**注意**：共振买点是**纯位置信号**，不套股息率门槛（与 `HoldingRecommender` 的周线买卖不同，互为补充）。

### 4.5 Prompt 构造（纯函数）

**`LlmPromptBuilder`**（`data/repository/LlmPromptBuilder.kt`）：
```kotlin
object LlmPromptBuilder {
    data class LlmPrompt(val system: String, val user: String)
    fun build(
        evaluatedStocks: List<EvaluatedStock>,
        signals: PortfolioSignals,
        thresholds: DividendThresholds,
    ): LlmPrompt
}
```

**system prompt**（A1 解读员，含数据语义不含规则内部）：
```text
你是一位稳健、客观的中文分红股投资分析助手。

【任务】
基于用户提供的持仓评估数据与策略信号（已由规则引擎判定），输出自然语言解读。

【数据语义（仅供理解，不要复述规则公式）】
- action=买：价格处于周线 BOLL 下轨附近（偏低），且股息率达门槛
- action=卖：价格处于周线 BOLL 上轨附近（偏高）
- action=持有：价格在中轨附近或股息率不足以触发买
- 距下轨%：0=在下轨（便宜），100=在上轨（贵）
- 股息率%：年现金分红 / 现价
- 仓位控制信号：多数股票抵达上轨 + 整体股息偏低 → 建议控仓、现金 ≥ 15%
- 三周期共振买点：日下轨 + 周下轨 + 月中轨以下 同时成立

【输出要求】严格输出 JSON：
{
  "overview": "组合整体解读，≤150字：整体偏防御/进攻、集中度、是否需控仓",
  "stockComments": { "<股票代码>": "该股1-2句解读，≤40字，结合 action/共振/数据" },
  "risks": ["具体风险点，如'银行板块占比过高'", "..."]
}

【约束】
1. 仅基于提供数据，绝不编造价格/股息率/财报/未给出的信息。
2. 中文，专业易懂，避免绝对化断言（"一定""必定"）。
3. 不给明确买卖时点或价格目标；这是解读，不是指令。
4. 仓位控制信号触发时，overview 必须明确提示控仓与现金≥15%。
5. 三周期共振买点的股票要在 stockComments 中点名。
6. 风险要点具体，不泛泛而谈；不复述规则逻辑。
```

**user message**：序列化每只股的 `code/name/industry/action/距下轨%/股息率%/reasons` + `signals`（仓位信号数值 + 共振买点 code 列表）+ `thresholds`。**不喂**成本价、持仓金额、账户信息。

### 4.6 响应解析（纯函数）

**`LlmAnalysisParser`**（`data/repository/LlmAnalysisParser.kt`）：
```kotlin
object LlmAnalysisParser {
    fun parse(rawContent: String): LlmAnalysis  // 永不抛异常
}
```
兜底链：完整 JSON → 字段缺失补默认 → ```` ```json fenced ```` 提取 → 纯文本降级（`overview=原文`，其余空）。

### 4.7 编排 Repository

**`LlmAnalysisRepository`**（`data/repository/LlmAnalysisRepository.kt`）：
```kotlin
class LlmAnalysisRepository(
    private val llmApi: LlmApi,
    private val configRepository: LlmConfigRepository,
) {
    suspend fun analyze(
        evaluatedStocks: List<EvaluatedStock>,
        signals: PortfolioSignals,
        thresholds: DividendThresholds,
    ): LlmAnalysisResult
}
```
编排：读 config → `!isComplete` → `NotConfigured` → 空列表 → `NotConfigured` → `LlmPromptBuilder.build` → 拼 `url = baseUrl.trimEnd('/') + "chat/completions"` → `try { llmApi.chatCompletions } catch mapError` → `LlmAnalysisParser.parse` → `Success`。

错误映射：`IOException` → "网络错误，请重试"；`HttpException(401/403)` → "API key 无效"；`HttpException(429)` → "请求过频，稍后重试"；其他 → "分析失败，请重试"。

### 4.8 ViewModel 集成

`PortfolioViewModel` 扩展：
- `PortfolioUiState` 加 `llmAnalysis: LlmAnalysisState = Idle`、`portfolioSignals: PortfolioSignals? = null`。
- 注入 `LlmAnalysisRepository`。
- **`evaluateVisibleHoldings()` 扩展**：评估时除周线 BOLL 外，并发拉**日 / 月 BOLL**（`Semaphore` 降到 **3** 并发，因每只股从 1 请求变 3 请求），评估完调 `PortfolioAdvisor.evaluate` → 写入 `portfolioSignals`。
- `fun analyzeWithLlm()`：取 `evaluation` + `portfolioSignals`；**为 null 或空时不调用 repository**（按钮禁用，防御性早返回），否则调 `repository.analyze`，`result` → `LlmAnalysisState`。
- `fun clearLlmAnalysis()`。
- `evaluateVisibleHoldings()` / `clearEvaluation()` 触发时清空 `llmAnalysis` 与 `portfolioSignals`。

### 4.9 UI

**`PortfolioEvaluationScreen`** 加：
1. **策略信号区**（顶部摘要下方，AI 区块上方）：
   - 仓位控制触发 → 高亮 Card"⚠ 建议控制仓位，现金 ≥ 15%（上轨占比 X%、平均股息 Y%）"。
   - 共振买点 → Card"▲ 三周期共振买点：600036、000858"（可点击跳回卡片）。
   - 未触发 → 不显示（或折叠）。
2. **AI 解读区块**（策略信号下方）：
   - `Idle`：`OutlinedButton("AI 解读")`，`Icons.Filled.AutoAwesome`。
   - `NotConfigured`：副标题"需先在设置配置 LLM"。
   - `Loading`：`CircularProgressIndicator` + "AI 分析中…"。
   - `Success`：Card 内 `overview` + `risks`（bullet）+ 小字免责"仅供参考，买卖以规则评估为准"。单股 `stockComments[code]` 追加到对应 `EvaluationCard` 底部 "AI：…"。
   - `Error`：文案 + "重试"。

**设置页**（`NotificationSettingsScreen.kt`）加 `LlmConfigSettingsContent`：厂商下拉 → 自动填 baseUrl+model；apiKey（password）；model 可改；"测试连接"；"保存"。

---

## 5. 数据流

```
一键评估（evaluateVisibleHoldings）
  → 对每只股并发(Semaphore=3) 拉 日/周/月 BOLL
  → HoldingRecommender.recommend(周线)  → EvaluatedStock 列表（现有）
  → PortfolioAdvisor.evaluate(日/周/月 bands)  → PortfolioSignals（新）
  → uiState.evaluation + uiState.portfolioSignals

点"AI 解读"
  → analyzeWithLlm()
  → LlmAnalysisRepository.analyze(evaluation, signals, thresholds)
      → 读 LlmConfig → !isComplete? → NotConfigured
      → LlmPromptBuilder.build(evaluation, signals, thresholds)
      → LlmApi.chatCompletions(@Url, body)
      → LlmAnalysisParser.parse()
  → 映射 LlmAnalysisState → 渲染
```

---

## 6. 错误处理

| 场景 | 行为 |
|---|---|
| LLM 配置缺失 | `NotConfigured`，UI 引导去设置 |
| 网络失败 / 超时 | `Error("网络错误，请重试")`，可重试 |
| HTTP 401 / 403 | `Error("API key 无效")`，引导去设置 |
| HTTP 429 | `Error("请求过频，稍后重试")` |
| 非法 JSON | 降级纯文本 `overview`，不报错 |
| 某股日/月 BOLL 拉取失败 | 该股 `MultiTimeframeBuySignal` 缺失（数据不足），不影响其他股 |
| 多周期 Tencent 限流 | `Semaphore(3)` 限流；失败的周期 band=null，共振判定跳过该股 |
| 空持仓 / 规则未跑 | "AI 解读"按钮禁用；规则结果始终可见 |

---

## 7. 测试策略

JUnit4 + Truth + MockK + kotlinx-coroutines-test，**TDD**。

### 7.1 `PortfolioAdvisorTest`（纯函数，新增最重要）
- 仓位控制：`upperBandRatio=0.6, avgYield=1.5` → `triggered=true, targetCashPercent=15`。
- 仓位控制：`upperBandRatio=0.3` → `triggered=false`（上轨占比不足）。
- 仓位控制：`avgYield=3.0` → `triggered=false`（股息不低）。
- 共振：某股三周期均满足 → `resonant=true`，进 `buySignals`。
- 共振：月 band 为 null → 该股不产出信号。
- 共振：仅日下轨+周下轨、月>中轨 → `resonant=false`。
- 自定义 `PortfolioAdvisorConfig` 生效。

### 7.2 `LlmPromptBuilderTest`（纯函数）
- 输入 `EvaluatedStock` + `PortfolioSignals` → system 含 JSON schema；user 含每只股 action/距下轨/股息率 + 信号数值。
- 仓位信号触发 → user message 含"现金 ≥ 15%"上下文。
- 空列表也能产出合法 prompt。
- 不含成本价等敏感字段。

### 7.3 `LlmAnalysisParserTest`（纯函数）
- 完整 JSON → 完整 `LlmAnalysis`。
- 字段缺失 → 补默认。
- ```` ```json fenced ```` → 提取并解析。
- 纯文本 / 非法 JSON → 降级，不抛异常。

### 7.4 `LlmAnalysisRepositoryTest`（fake `LlmApi`）
- 配置完整 + 成功 → `Success`。
- 配置缺失 / 空列表 → `NotConfigured`。
- `IOException` → `Error("网络错误…")`。
- `HttpException(401)` → `Error("API key 无效")`。

### 7.5 `LlmConfigRepositoryTest`
- 保存后 `observe` 收到新值；`clearConfig` 后空默认。

### 7.6 `PortfolioViewModelTest` 扩展
- `evaluateVisibleHoldings()` mock 日/周/月 `fetchBoll` → `evaluation` + `portfolioSignals` 正确。
- `analyzeWithLlm()` 成功 → state `Success`。
- 仓位信号触发 → `portfolioSignals.positionControl.triggered == true`。
- 评估重跑 → `llmAnalysis` 清空。

### 7.7 不写测试
- Compose UI；真实 LLM / Tencent 多周期调用（手动验证）。

---

## 8. 文件改动清单

### 新增
- `data/repository/LlmConfig.kt`
- `data/repository/LlmAnalysis.kt`（含 `LlmAnalysisResult`）
- `data/repository/LlmConfigRepository.kt`
- `data/repository/LlmProviderPresets.kt`
- `data/repository/LlmPromptBuilder.kt`
- `data/repository/LlmAnalysisParser.kt`
- `data/repository/LlmAnalysisRepository.kt`
- `data/repository/PortfolioAdvisor.kt`（含 `PortfolioSignals` / `PositionControlSignal` / `MultiTimeframeBuySignal` / `PortfolioAdvisorConfig`）
- `data/remote/LlmApi.kt`
- `data/remote/dto/LlmChatRequest.kt`、`LlmChatResponse.kt`
- 对应测试：`PortfolioAdvisorTest`、`LlmPromptBuilderTest`、`LlmAnalysisParserTest`、`LlmAnalysisRepositoryTest`、`LlmConfigRepositoryTest`

### 修改
- `data/repository/KlineRepository.kt` — `fetchCloses(code, period)` 参数化周期（日/周/月）；旧 `fetchWeeklyCloses` 改为兼容封装。
- `data/repository/StockRepository.kt` — `fetchBoll(code, period)`；旧 `fetchBoll(code)` 兼容封装。
- `di/NetworkModule.kt` — 加 `LlmApi` provides + 独立 60s OkHttpClient。
- `viewmodel/PortfolioViewModel.kt` — 加 `llmAnalysis` / `portfolioSignals` state、多周期 BOLL 拉取（Semaphore=3）、`PortfolioAdvisor.evaluate` 调用、`analyzeWithLlm()` / `clearLlmAnalysis()`、注入 repository、评估重跑清空。
- `ui/screen/PortfolioEvaluationScreen.kt` — 加策略信号区 + AI 解读区块 + 单股 AI 简评行。
- `ui/screen/NotificationSettingsScreen.kt` — 加 LLM 配置入口与编辑 UI。
- `viewmodel/NotificationSettingsViewModel.kt` — 加 LLM 配置读写（或新建 `LlmConfigViewModel`）。
- `viewmodel/PortfolioViewModelTest.kt` — 扩展多周期 + 信号 + LLM 用例。

### 不动
- `HoldingRecommender` / `BollCalculator`（周线买卖逻辑原样）。
- DB schema（`AppDatabase` 保持 v14，无需迁移）。
- 现有评估入口/结果页骨架。

---

## 9. 风险与未决

- **API 调用量 3 倍**：每只股从 1 次周线 BOLL 变 **3 次**（日/周/月）。`Semaphore(3)` 限流缓解 Tencent 拒绝风险；失败的周期降级为"数据不足"，不阻塞评估。
- **多周期数据非持久化**：每次评估重拉 3 周期；后续可加 BOLL 缓存（日内有效期）。
- **共振买点稀有**：三周期同时满足较少见，可能长期无信号——属正常，UI 不显示即可。
- **API key 明文存 SharedPreferences**：后续可换 `EncryptedSharedPreferences`；UI 配置页标注"key 仅存本机"。
- **JSON 模式兼容性**：三家厂商均支持 `response_format: json_object`，行为细节有差异；纯文本兜底覆盖。
- **LLM 幻觉**：prompt 约束"仅基于提供数据"，解读仅供辅助参考，买卖以规则为准（UI 注明免责）。
- **成本/延迟**：单次批量 LLM 调用，20 只持仓输入 ~2.5k token、输出 ~1k token，DeepSeek 约 ¥0.006/次；可接受。
- **策略参数硬编码**：`PortfolioAdvisorConfig`（0.5 / 2.0 / 0.9 / 15）先硬编码；后续可做设置项。
