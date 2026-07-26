# 一键评估 LLM 解读增强 (LLM Analysis) — 设计文档

**日期:** 2026-07-26
**状态:** Draft (待用户审核)
**作者:** brainstorming skill

---

## 1. 背景与目标

### 问题
现有"一键评估"（见 `2026-07-26-portfolio-evaluation-design.md`，已实现）是**纯规则引擎**：`HoldingRecommender` 基于 BOLL 位置 + 股息率门槛，输出每只股的买/持/卖建议和模板化 `reasons`。规则的短板：

- 无法做**组合层面综合判断**（整体偏防御/进攻、集中度、互补性）。
- `reasons` 是机械短语，缺乏对单只股的连贯解读。

### 目标
在评估结果页加"AI 解读"：用户**手动触发**一次 LLM 调用，把规则评估的结构化结果作为上下文，产出 (1) 组合总评、(2) 每只股 1-2 句解读、(3) 风险提示。规则与 LLM **完全解耦**——LLM 失败不影响规则结果展示。

### 非目标 (YAGNI)
- ❌ 不替代规则引擎（买卖建议仍由 `HoldingRecommender` 出，LLM 只解读）
- ❌ 不每次评估自动调 LLM（手动触发，控成本）
- ❌ 不做对话/多轮交互
- ❌ 不做流式输出（一次性返回）
- ❌ 不缓存/持久化分析结果（重开需重跑）
- ❌ 不加密 API key（与现有 SharedPreferences 惯例一致，标记后续改进）
- ❌ 不写 Compose UI 测试（与仓库惯例一致）

---

## 2. 关键决策（来自 brainstorming）

| 决策点 | 选择 | 理由 |
|---|---|---|
| 定位 | 规则之上的补充解读层 | 规则稳定可测；LLM 补"组合视角"这块规则做不到的 |
| 调用粒度 | **单次批量**（整个组合一次调用） | 1 次成本最低；唯一能让 LLM 看到全局、做组合判断的方式 |
| 触发方式 | 结果页手动"AI 解读"按钮 | 用户决定何时花 token；不绑定评估流程 |
| LLM 协议 | OpenAI 兼容 `chat/completions` | DeepSeek/智谱/通义均兼容；用户自选厂商 |
| 提供商 | 用户配置（baseUrl + apiKey + model） | 开发者不承担成本；隐私由用户掌控 |
| 配置存储 | **SharedPreferences**（`LlmConfigRepository`） | 项目已用；`notification_rules` 表无 string 列且语义是"规则"；无需 DB 迁移、无需新依赖 |
| Prompt/解析 | 纯函数（`LlmPromptBuilder` / `LlmAnalysisParser`） | 对标 `HoldingRecommender`/`BollCalculator`，无 Android 依赖，可单测 |
| 动态 baseUrl | Retrofit `@Url` 全路径 | 绕开 Retrofit 静态 baseUrl 限制 |
| 输出格式 | JSON（`response_format: json_object`）+ 纯文本兜底 | 结构化解析；JSON 模式失败时降级 |
| 状态挂载 | 共享 `PortfolioViewModel` | 与 `evaluation` 同生命周期，结果页直接读 |

---

## 3. 数据模型

```kotlin
// data/repository/LlmConfig.kt
data class LlmConfig(
    val baseUrl: String,   // 如 "https://api.deepseek.com/v1/"
    val apiKey: String,
    val model: String,     // 如 "deepseek-chat"
) {
    val isComplete: Boolean
        get() = baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()
}

// data/repository/LlmAnalysis.kt
data class LlmAnalysis(
    val overview: String,                    // 组合总评（1 段）
    val stockComments: Map<String, String>,  // code -> 1-2 句解读
    val risks: List<String>,                 // 风险提示
)

sealed interface LlmAnalysisResult {
    data class Success(val analysis: LlmAnalysis) : LlmAnalysisResult
    data object NotConfigured : LlmAnalysisResult      // 缺 baseUrl/apiKey/model
    data class Error(val message: String) : LlmAnalysisResult
}

// ViewModel 层 UI state（并入 PortfolioUiState）
sealed interface LlmAnalysisState {
    data object Idle : LlmAnalysisState
    data object Loading : LlmAnalysisState
    data class Success(val analysis: LlmAnalysis) : LlmAnalysisState
    data object NotConfigured : LlmAnalysisState
    data class Error(val message: String) : LlmAnalysisState
}
```

---

## 4. 组件设计

### 4.1 配置层

**`LlmConfigRepository`**（`data/repository/LlmConfigRepository.kt`）—— 包内持有 `SharedPreferences("llm_prefs")`：
```kotlin
class LlmConfigRepository(context: Context) {
    fun observeConfig(): Flow<LlmConfig>   // SharedPreferences 变更 → Flow
    suspend fun saveConfig(config: LlmConfig)
    suspend fun clearConfig()
}
```
- 用 `callbackFlow` + `OnSharedPreferenceChangeListener` 实现响应式；或简单 `MutableStateFlow` 镜像。
- 读写三个 key：`llm_base_url` / `llm_api_key` / `llm_model`。

**`LlmProviderPresets`**（`data/repository/LlmProviderPresets.kt`）—— 厂商预设，用户选定后自动填默认值，只需补 apiKey：

| 厂商 | baseUrl | 默认 model |
|---|---|---|
| DeepSeek | `https://api.deepseek.com/v1/` | `deepseek-chat` |
| 智谱 GLM | `https://open.bigmodel.cn/api/paas/v4/` | `glm-4-flash` |
| 通义千问 | `https://dashscope.aliyuncs.com/compatible-mode/v1/` | `qwen-turbo` |

### 4.2 网络层

**`LlmApi`**（`data/remote/LlmApi.kt`）—— OpenAI 兼容 chat completions，`@Url` 传全路径绕开静态 baseUrl：
```kotlin
interface LlmApi {
    @POST
    suspend fun chatCompletions(
        @Url url: String,                       // baseUrl + "chat/completions"
        @Header("Authorization") auth: String,  // "Bearer <apiKey>"
        @Body body: LlmChatRequest,
    ): LlmChatResponse
}
```

**DTO**（`data/remote/dto/LlmChatRequest.kt`、`LlmChatResponse.kt`）—— 标准 OpenAI 结构：
- Request: `model`, `messages: List<{role, content}>`, `response_format: {type: "json_object"}`, `temperature`（建议 0.3，求稳）。
- Response: `choices[0].message.content`（String）。

**DI**（`di/NetworkModule.kt`）—— 新增**独立 OkHttpClient（60s 超时）** + Retrofit（baseUrl 占位 `http://localhost/`，实际走 `@Url`），`@Provides LlmApi`。不复用现有 10s 超时的 client，因 LLM 响应常 10-30s。

### 4.3 Prompt 构造（纯函数）

**`LlmPromptBuilder`**（`data/repository/LlmPromptBuilder.kt`）：
```kotlin
object LlmPromptBuilder {
    data class LlmPrompt(val system: String, val user: String)
    fun build(evaluatedStocks: List<EvaluatedStock>, thresholds: DividendThresholds): LlmPrompt
}
```
- **system**：角色"稳健的中文分红股投资助手"；约束"仅基于提供数据，不编造价格/股息率/财报；中文；简短（overview ≤150 字，单股 ≤40 字）"；规定输出 JSON schema：
  ```json
  {"overview": "...", "stockComments": {"600036": "..."}, "risks": ["..."]}
  ```
- **user**：序列化每只股的 `code / name / industry / action / 距下轨% / 股息率% / reasons`，并附 `thresholds` 上下文（让 LLM 知道门槛含义）。
- **隐私**：不喂成本价、持仓金额、账户信息——只用规则评估已产出的公开字段。

### 4.4 响应解析（纯函数）

**`LlmAnalysisParser`**（`data/repository/LlmAnalysisParser.kt`）：
```kotlin
object LlmAnalysisParser {
    fun parse(rawContent: String): LlmAnalysis  // 永不抛异常
}
```
兜底链：
1. 完整 JSON → 完整 `LlmAnalysis`。
2. 字段缺失 → 缺项用默认（`risks` 空、`stockComments` 空 map，`overview` 取原文）。
3. ```` ```json ... ``` ```` fenced 代码块 → 先提取再解析。
4. 彻底非法 JSON → `overview = 原文`，`risks` / `stockComments` 空。

### 4.5 编排 Repository

**`LlmAnalysisRepository`**（`data/repository/LlmAnalysisRepository.kt`）：
```kotlin
class LlmAnalysisRepository(
    private val llmApi: LlmApi,
    private val configRepository: LlmConfigRepository,
) {
    suspend fun analyze(
        evaluatedStocks: List<EvaluatedStock>,
        thresholds: DividendThresholds,
    ): LlmAnalysisResult
}
```
编排：读 config → `!isComplete` → `NotConfigured` → 空列表 → `NotConfigured` → `LlmPromptBuilder.build` → 拼 `url = baseUrl.trimEnd('/') + "chat/completions"` → `try { llmApi.chatCompletions(...) } catch(e) { mapError(e) }` → `LlmAnalysisParser.parse` → `Success`。

错误映射：`IOException` → "网络错误，请重试"；`HttpException(401/403)` → "API key 无效"；`HttpException(429)` → "请求过频，稍后重试"；其他 → "分析失败，请重试"。

### 4.6 ViewModel 集成

`PortfolioViewModel` 扩展：
- `PortfolioUiState` 加 `llmAnalysis: LlmAnalysisState = Idle`。
- 注入 `LlmAnalysisRepository`。
- `fun analyzeWithLlm()`：`viewModelScope.launch`，取当前 `uiState.evaluation`；**为 null 或空列表时不调用 repository**（UI 已禁用按钮，此处为防御性早返回，state 保持 `Idle`），仅当非空时调 `repository.analyze`，`result` → `LlmAnalysisState`。
- `fun clearLlmAnalysis()`。
- `evaluateVisibleHoldings()` / `clearEvaluation()` 触发时**清空** `llmAnalysis`（避免错配旧解读）。

### 4.7 UI

**`PortfolioEvaluationScreen`** 加 AI 区块（顶部摘要下方、分组列表上方）：

```
┌────────────────────────────────────┐
│  买 3   持有 5   卖 1   数据不足 0  │  摘要 (现有)
├────────────────────────────────────┤
│  ✨ AI 解读        [AI 解读] ←按钮  │  Idle/NotConfigured
│  ┌──────────────────────────────┐  │
│  │ overview 段落…               │  │  Success
│  │ • 风险1                       │  │
│  │ • 风险2                       │  │
│  │ （仅供参考，买卖以规则为准）  │  │
│  └──────────────────────────────┘  │
├────────────────────────────────────┤
│  ▼ 买入信号                         │
│  EvaluationCard ...                 │
│   └ AI：单股解读（如有）            │  ← Success 时追加
```

状态：
- `Idle`：`OutlinedButton("AI 解读")`，图标 `Icons.Filled.AutoAwesome`。
- `NotConfigured`：按钮副标题"需先在设置配置 LLM"。
- `Loading`：`CircularProgressIndicator` + "AI 分析中…"，按钮禁用。
- `Success`：Card 内 `overview` + `risks`（bullet）；底部小字免责声明"仅供参考，买卖建议以规则评估为准"。单股 `stockComments[code]` 追加到对应 `EvaluationCard` 底部一行 "AI：…"。
- `Error`：错误文案 + "重试"按钮。

**设置页**（`NotificationSettingsScreen.kt`）加 `LlmConfigSettingsContent`：
- 厂商下拉（DeepSeek / 智谱 / 通义 / 自定义）→ 选定自动填 baseUrl + 默认 model。
- `apiKey` 输入框（`password` visualTransformation）。
- `model` 输入框（可改）。
- "测试连接"按钮 → 调一次极简 prompt（如"回复 ok"）→ Toast 成功/失败。
- "保存"按钮。

---

## 5. 数据流

```
点"AI 解读"
  → PortfolioViewModel.analyzeWithLlm()
  → LlmAnalysisRepository.analyze(evaluation, thresholds)
      → 读 LlmConfig  →  !isComplete? → NotConfigured
      → LlmPromptBuilder.build()
      → LlmApi.chatCompletions(@Url, body)
      → LlmAnalysisParser.parse()
  → 映射 LlmAnalysisState
  → PortfolioEvaluationScreen 渲染
```

---

## 6. 错误处理

| 场景 | 行为 |
|---|---|
| 配置缺失（baseUrl/apiKey/model 任一空） | `NotConfigured`，UI 引导去设置 |
| 网络失败 / 超时 | `Error("网络错误，请重试")`，可重试 |
| HTTP 401 / 403（key 错） | `Error("API key 无效")`，引导去设置 |
| HTTP 429（限流） | `Error("请求过频，稍后重试")` |
| 非法 JSON | 降级纯文本 `overview`，不报错 |
| 空持仓 / 规则未跑 | "AI 解读"按钮禁用 |
| 规则结果始终可见 | LLM 任何状态都不影响规则卡片展示 |

---

## 7. 测试策略

JUnit4 + Truth + MockK + kotlinx-coroutines-test，**TDD：先写测试再写实现**。

### 7.1 `LlmPromptBuilderTest`（纯函数）
- 输入若干 `EvaluatedStock` → system 含 JSON schema；user 含每只股 action/距下轨/股息率。
- 空列表也能产出合法 prompt。
- 不包含成本价等敏感字段（断言文本不含 "cost" / "成本"）。

### 7.2 `LlmAnalysisParserTest`（纯函数，最重要）
- 完整 JSON → 完整 `LlmAnalysis`。
- 缺 `risks` → `risks` 空。
- 缺 `stockComments` → 空 map。
- ```` ```json fenced ```` → 能提取并解析。
- 纯文本（非 JSON）→ `overview` = 原文，其余空。
- 彻底非法 JSON → 降级，**不抛异常**。

### 7.3 `LlmAnalysisRepositoryTest`（fake `LlmApi`）
- 配置完整 + 成功响应 → `Success`。
- 配置缺失 → `NotConfigured`。
- `api` 抛 `IOException` → `Error("网络错误…")`。
- `api` 抛 `HttpException(401)` → `Error("API key 无效")`。
- 空列表 → `NotConfigured`。

### 7.4 `LlmConfigRepositoryTest`
- 保存后 `observe` 收到新值；`clearConfig` 后收到空默认值。

### 7.5 `PortfolioViewModelTest` 扩展
- `analyzeWithLlm()` 成功 → state `Success`。
- 返回 `NotConfigured` → state `NotConfigured`。
- `evaluateVisibleHoldings()` 重跑 → `llmAnalysis` 被清空。

### 7.6 不写测试
- Compose UI（手动验证）。
- 真实 LLM 调用（设置页"测试连接"手动验证）。

---

## 8. 文件改动清单

### 新增
- `app/src/main/java/com/stock/dividend/data/repository/LlmConfig.kt`
- `app/src/main/java/com/stock/dividend/data/repository/LlmAnalysis.kt`（含 `LlmAnalysisResult`）
- `app/src/main/java/com/stock/dividend/data/repository/LlmConfigRepository.kt`
- `app/src/main/java/com/stock/dividend/data/repository/LlmProviderPresets.kt`
- `app/src/main/java/com/stock/dividend/data/repository/LlmPromptBuilder.kt`
- `app/src/main/java/com/stock/dividend/data/repository/LlmAnalysisParser.kt`
- `app/src/main/java/com/stock/dividend/data/repository/LlmAnalysisRepository.kt`
- `app/src/main/java/com/stock/dividend/data/remote/LlmApi.kt`
- `app/src/main/java/com/stock/dividend/data/remote/dto/LlmChatRequest.kt`
- `app/src/main/java/com/stock/dividend/data/remote/dto/LlmChatResponse.kt`
- 对应测试：`LlmPromptBuilderTest`、`LlmAnalysisParserTest`、`LlmAnalysisRepositoryTest`、`LlmConfigRepositoryTest`

### 修改
- `app/src/main/java/com/stock/dividend/di/NetworkModule.kt` — 加 `LlmApi` provides + 独立 60s OkHttpClient。
- `app/src/main/java/com/stock/dividend/viewmodel/PortfolioViewModel.kt` — 加 `llmAnalysis` state、`analyzeWithLlm()` / `clearLlmAnalysis()`、注入 `LlmAnalysisRepository`、评估重跑时清空 llmAnalysis。
- `app/src/main/java/com/stock/dividend/ui/screen/PortfolioEvaluationScreen.kt` — 加 AI 解读区块 + 单股 AI 简评行。
- `app/src/main/java/com/stock/dividend/ui/screen/NotificationSettingsScreen.kt` — 加 LLM 配置入口与编辑 UI。
- `app/src/main/java/com/stock/dividend/viewmodel/NotificationSettingsViewModel.kt` — 加 LLM 配置读写（或新建 `LlmConfigViewModel`）。
- `app/src/test/java/com/stock/dividend/viewmodel/PortfolioViewModelTest.kt` — 扩展 LLM 用例。

### 不动
- `HoldingRecommender` / `BollCalculator` / `KlineRepository` / `StockRepository.fetchBoll`（规则链路原样）。
- DB schema（`AppDatabase` 保持 v14，无需迁移）。
- 现有评估入口/结果页骨架。

---

## 9. 风险与未决

- **API key 明文存 SharedPreferences**：与现有惯例一致但非最佳；后续可换 `EncryptedSharedPreferences`。UI 在配置页标注"key 仅存本机"。
- **JSON 模式兼容性**：DeepSeek / 智谱 / 通义均支持 `response_format: json_object`，但行为细节（如是否强制 JSON）有差异；纯文本兜底链覆盖。
- **LLM 幻觉**：prompt 约束"仅基于提供数据"，但无法完全杜绝；解读仅供辅助参考，买卖建议以规则为准（UI 注明免责）。
- **成本/延迟**：单次批量，20 只持仓估算输入 ~2k token、输出 ~1k token，DeepSeek 约 ¥0.005/次；可接受。持仓极多（>50）时 prompt 变长，仍可控。
- **超时**：LLM 响应可能 10-30s → 独立 60s timeout + Loading 态 + 按钮可再次点击重试。
- **测试连接的 key 校验**：仅校验"能通"，不验证额度；用户实际调用时可能因余额不足失败，错误文案兜底。
