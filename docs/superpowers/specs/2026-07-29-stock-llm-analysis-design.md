# 个股 AI 解读 (Stock LLM Analysis) — 设计文档

**日期:** 2026-07-29
**状态:** Draft
**作者:** brainstorming skill
**关联:** `2026-07-26-llm-analysis-design.md`（组合级 AI 解读，已实现）

---

## 1. 背景与目标

### 问题
现有 LLM 能力（见 `2026-07-26-llm-analysis-design.md`，已实现）**仅在组合评估页**生效：用户在持仓页点"一键评估"后，可对**整个组合**触发一次 AI 解读，得到组合总评 + 每只股 ≤40 字简评 + 风险点。

短板：
- **`StockDetailScreen`（个股详情页）完全没有 LLM**。该页是数据最丰富的单股视图（分红率趋势、1/3/5 年预测、买入线、分红记录），却无任何自然语言解读。
- 组合级的"每只股 ≤40 字简评"过短，无法对单只股票做有深度的判断（估值贵/便宜、分红可持续性）。
- 用户在看某一只股票详情时，想问"这股现在能不能买/贵不贵"，得返回组合页重新评估，体验割裂。

### 目标
1. 在 `StockDetailScreen` 新增「✨ AI 解读」区块，用户**手动触发**一次 LLM 调用。
2. 把**这一只股票**的股息能力（分红率趋势 / 预测 / 买入线达标）+ 三周期 BOLL 价格位置喂给已配置的 LLM，产出结构化解读：(1) 估值判断、(2) 股息可持续性、(3) 一句话结论、(4) 风险点。
3. **最大化复用**组合级已建好的 LLM 基建：`LlmApi` / `LlmConfigSource` / `LlmConfigRepository` / `LlmProviderPresets` / `LlmChatRequest` / `LlmAnalysisState` 五态 UI 模型。**无新 DI 模块、无 DB 变更、无新依赖。**

### 非目标 (YAGNI)
- ❌ 不做多轮对话 / 自由问答 / 流式输出（组合级也没有，避免引入 stream/history 基建）。
- ❌ 不缓存解读结果（每次按需重算，与组合级一致）。
- ❌ 不让 LLM 替代 `ForecastCalculator` / `BollCalculator` / `computeBuyThreshold` 的数值计算（宪法原则 III：东财原始数据不换算，LLM 只解读、不算数）。
- ❌ 不加密 API key（沿用现有明文 SharedPreferences，属另一条 spec §9 线路，本设计不动）。
- ❌ 不写 Compose UI 测试（与组合级一致，spec §7.7）。
- ❌ 不自动触发（手动按钮，控成本/延迟）。

### 成功标准
1. `StockDetailScreen` 出现 AI 解读区块，状态模型复用五态模式（Idle/Loading/NotConfigured/Success/Error，见 §3.3 采用独立但对称的 `StockLlmAnalysisState`）。
2. 复用现有 `LlmApi` + `LlmConfigSource`（配置/预设/设置页完全不变）。
3. prompt 与 parser 均为**纯函数**并配单测（遵循项目"纯函数优先 + 必配单测"约定，§4.4 / §6）。
4. 不发送成本价等敏感信息（隐私约束与组合级一致）。
5. 构建 + 全量单测绿。

---

## 2. 关键决策（来自 brainstorming）

| 决策点 | 选择 | 理由 |
|---|---|---|
| 扩展方向 | 个股 AI 解读（而非自由问答/infra） | `StockDetailScreen` 是最明显的功能缺口；数据最丰富却无 AI；最大化复用组合级基建 |
| 数据深度 | 完整·股息 + 三周期 BOLL（日/周/月） | 与组合级体验对齐；`fetchBoll(code, period)` 已就绪 |
| 并行新文件 vs 扩展 `LlmAnalysisRepository` | **并行新纯函数 + 独立编排** | 组合级输入是 `List<EvaluatedStock>` + `PortfolioSignals`；个股级是单股快照。数据形状不同，硬塞进一个 builder 参数会膨胀。遵循"三行相似代码优于不必要的抽象层" |
| 输出 schema | `{valuation, dividendSustainability, action, risks[]}` | 与组合级 `{overview, stockComments, risks}` 语义不同（个股无 overview/无多股），强行复用 `LlmAnalysis` 会变可空大杂烩 |
| JSON 提取逻辑 | 抽成 `JsonExtraction` 工具，两 parser 共用 | fenced/裸/兜底逻辑完全一致，是合理去重 |
| UI 状态模型 | **复用** `LlmAnalysisState` 五态 | 五态语义通用；仅 `Success` 的 payload 用 `StockLlmAnalysis`（见 §3.3 方案二） |
| VM 集成点 | 直接在 `StockDetailViewModel` 内编排（已注入 `StockRepository`） | `fetchBoll`/`fetchQuotes` 已可得，无需新 repository 层 |
| BOLL 并发 | 单股 `async`×3 `awaitAll`，无 `Semaphore` | 仅 3 个请求，无批量限流必要 |
| 触发方式 | 详情页手动"AI 解读"按钮 | 与组合级一致，用户决定何时花 token |
| 隐私 | 不喂成本价、持仓金额 | 与组合级一致 |

---

## 3. 数据模型

### 3.1 输入快照（纯数据，无 Android 依赖，便于单测构造）

```kotlin
// data/repository/StockLlmInput.kt
data class StockLlmInput(
    val code: String,
    val name: String,
    val industry: String?,
    val currentPrice: Double?,
    val dividendRatePoints: List<Double>,           // 近年分红率%序列（升序），可空
    val latestDividendYield: Double?,               // 最新一期股息率%
    val forecast: StockLlmForecast?,                // 1/3/5年预测摘要，可空
    val buyThreshold: StockLlmBuyThreshold?,        // 买入线达标情况，可空
    val bollDaily: StockLlmBollPosition?,           // 三周期价格位置，可空
    val bollWeekly: StockLlmBollPosition?,
    val bollMonthly: StockLlmBollPosition?,
) {
    /** 1/3/5 年预测：年均每股 + 实际样本年数。 */
    data class StockLlmForecast(
        val avgCashPerShare1Y: Double,
        val avgCashPerShare3Y: Double,
        val avgCashPerShare5Y: Double,
        val actualYears: Int,
    )

    /** 买入线：目标股息率% + 现状股息率% + 是否达标（null=现价缺失）。 */
    data class StockLlmBuyThreshold(
        val targetYieldPercent: Double,
        val currentYieldPercent: Double?,
        val reached: Boolean?,
    )

    /** 单周期 BOLL 价格位置：0=在下轨（便宜），100=在上轨（贵）。已在 VM 算好，prompt 不喂 BOLL 原始数值。 */
    data class StockLlmBollPosition(
        val priceVsLowerPercent: Int,   // clamp 0..100
    )
}
```

设计要点：所有字段可空，缺数据 VM 传 null，prompt 渲染为"—"。`priceVsLowerPercent` 在 VM 内算好（复用 `LlmPromptBuilder` 现有的 `ratioVsLower` 同款逻辑），避免 prompt 里塞 BOLL 的 middle/upper/lower 原始数值（LLM 不需要，且减少 token）。

### 3.2 输出模型

```kotlin
// data/repository/StockLlmAnalysis.kt
data class StockLlmAnalysis(
    val valuation: String,               // ≤120字：当前价格贵/便宜/合理，结合三周期 BOLL 位置
    val dividendSustainability: String,  // ≤120字：分红率趋势（升/降/稳）与可持续性
    val action: String,                  // ≤20字：一句话定性结论（如"可逢低关注"/"暂观望"/"持有"），不给具体价
    val risks: List<String>,             // 具体风险点
)
```

### 3.3 UI 状态模型 —— 复用 `LlmAnalysisState`，方案二

现有 `LlmAnalysisState.Success(analysis: LlmAnalysis)` 的 payload 是组合级的 `LlmAnalysis`。两种复用方式：

- **方案 A（已否决）**：把 `LlmAnalysis` 改成可同时承载两种 schema 的联合类型 → 污染组合级，回归风险大。
- **方案 B（采用）**：个股级独立定义 `StockLlmAnalysisState` sealed interface，**结构与 `LlmAnalysisState` 完全对称**（Idle/Loading/NotConfigured/Success/Error），`Success` payload 为 `StockLlmAnalysis`。两个 sealed 类型五态语义一致，UI 渲染模式可共享（§5）。

```kotlin
// data/repository/StockLlmAnalysis.kt
sealed interface StockLlmAnalysisState {
    data object Idle : StockLlmAnalysisState
    data object Loading : StockLlmAnalysisState
    data object NotConfigured : StockLlmAnalysisState
    data class Success(val analysis: StockLlmAnalysis) : StockLlmAnalysisState
    data class Error(val message: String) : StockLlmAnalysisState
}
```

> 不复用 `LlmAnalysisState` 本体（payload 类型不同），但保持对称结构，UI 可用近乎一致的 `when` 分支渲染。

---

## 4. 组件设计

### 4.1 JSON 提取工具（去重，纯函数）

抽出现有 `LlmAnalysisParser.extractJsonObject` 的逻辑为公共纯函数，两 parser 共用：

```kotlin
// data/repository/JsonExtraction.kt
object JsonExtraction {
    /** 从可能含前后文/```json 围栏/裸对象的文本中提取首个 JSON 对象字符串；无则 null。 */
    fun extractJsonObject(raw: String): String?
}
```
兜底链：以 `{` 开头 → 原样；``` ```json fenced ``` → 取捕获组；否则取首个 `{` 到末个 `}` 子串；都没有 → null。

`LlmAnalysisParser` 改为调用 `JsonExtraction.extractJsonObject`（行为不变，仅去重），现有 `LlmAnalysisParserTest` 应继续全绿。

### 4.2 Prompt 构造（纯函数）

```kotlin
// data/repository/StockLlmPromptBuilder.kt
object StockLlmPromptBuilder {
    data class LlmPrompt(val system: String, val user: String)
    fun build(input: StockLlmInput): LlmPrompt
}
```

**system prompt**（单股分析角色）：
```text
你是一位稳健、客观的中文分红股投资分析助手。

【任务】
基于用户提供的一只股票的股息数据与价格位置，输出该股的自然语言解读。

【数据语义（仅供理解，不要复述规则公式）】
- 距下轨%：0=价格在 BOLL 下轨（便宜），100=在上轨（贵）；给出 日/周/月 三周期，据此判断多周期共振
- 分红率%：当年现金分红 / 当年股价（逐年序列反映分红力度趋势）
- 股息率%：最新一期年现金分红 / 现价
- 预测：基于历史分红的线性平均，非承诺；实际样本年数越少越不可靠
- 买入线：股息率达到「国债收益率×倍数」时视为低估信号

【输出要求】严格输出 JSON：
{
  "valuation": "估值判断≤120字：结合三周期位置判断当前贵/便宜/合理",
  "dividendSustainability": "分红可持续性≤120字：结合分红率趋势与预测样本",
  "action": "一句话结论≤20字：如可逢低关注/暂观望/持有等定性",
  "risks": ["具体风险点", "..."]
}

【约束】
1. 仅基于提供数据，绝不编造价格/股息率/财报/未给出的信息。
2. 中文，专业易懂，避免绝对化断言（"一定""必定"）。
3. 不给明确买卖时点或具体价格目标；这是解读，不是指令。
4. 缺失数据用"—"表示的部分，解读中不要臆测。
5. 风险要点具体，不泛泛而谈；不复述规则逻辑。
```

**user message**：序列化单股快照——
```
【标的】600036 招商银行 [银行]
【现价】¥12.34
【分红率趋势】2021:3.2% | 2022:3.5% | 2023:3.8% | 2024:4.1%（近4年，整体上升）
【最新股息率】4.1%
【预测】1年均每股 ¥1.80 / 3年均每股 ¥1.65 / 5年均每股 ¥1.50（实际样本 4 年）
【买入线】目标股息率 6.5%，当前 4.1%，未达标
【BOLL 位置】日距下轨 30% / 周距下轨 25% / 月距下轨 60%
```
缺失项渲染"—"。**不含成本价、持仓股数、持仓金额。**

### 4.3 响应解析（纯函数）

```kotlin
// data/repository/StockLlmAnalysisParser.kt
object StockLlmAnalysisParser {
    fun parse(rawContent: String): StockLlmAnalysis  // 永不抛异常
}
```
兜底链：空 → 全字段空；`JsonExtraction.extractJsonObject` → gson 解析四字段（缺字段补默认）；任一异常 → 整段文本塞 `valuation`，其余空。

### 4.4 ViewModel 集成

`StockDetailViewModel` 扩展（已注入 `StockRepository`，新增注入 `LlmApi` + `LlmConfigSource`）：
- `StockDetailUiState` 加 `llmAnalysis: StockLlmAnalysisState = Idle`。
- **`fun analyzeWithLlm()`**：
  1. 取当前 `stock`；为 null 或 `dividends` 为空 → 早返回（按钮禁用，防御性）。
  2. 置 `Loading`。
  3. 并发拉日/周/月 BOLL：`async { stockRepository.fetchBoll(stockCode, KlinePeriod.XXX) }` × 3，`awaitAll`（单股无 `Semaphore`）。失败的周期 → null。
  4. 现价：复用 `refreshBuyThreshold` 已拉的或 `fetchQuotes(listOf(stock))`。
  5. 组装 `StockLlmInput`（从 `uiState` 现有 `dividendRatePoints`/`allForecasts`/`buyThreshold` + BOLL + 现价；`priceVsLowerPercent` 在此算）。
  6. 读 `configSource.observeConfig().first()`；`!isComplete` → `NotConfigured`。
  7. `StockLlmPromptBuilder.build(input)` → 拼 `url = baseUrl.trimEnd('/') + "/chat/completions"` → `try { llmApi.chatCompletions(...) }` → `StockLlmAnalysisParser.parse` → `Success`。
  8. 错误映射同组合级（401/403→"API key 无效"，429→"请求过频，稍后重试"，其它→"分析失败，请重试"，异常→"网络错误，请重试"，空返回→"LLM 返回为空"）。
- **`fun clearLlmAnalysis()`**：复位 `Idle`。

### 4.5 UI

`StockDetailScreen` 在「分红率趋势」区块后、「分红记录」前插入 `StockLlmAnalysisSection`（新 private composable）：
- `Idle`：`OutlinedButton("✨ AI 解读")`（`Icons.Filled.AutoAwesome`），`enabled = dividends.isNotEmpty()`。
- `NotConfigured`：按钮置灰 + 小字"需先在设置配置 LLM"。
- `Loading`：`CircularProgressIndicator` + "AI 分析中…"。
- `Success`：`ElevatedCard` 标题"✨ AI 解读"，分行展示 `valuation` / `dividendSustainability` / `action`（`action` 高亮 pill）/ `risks`（bullet）+ 免责小字"仅供参考，不构成投资建议。"
- `Error`：错误文案（`colorScheme.error`）+ "重试" `TextButton`。

样式复用 `ui/component/DesignSystem.kt`（`AppCardDefaults` / `SectionHeader` / `StatusPill` / `FinanceStatusTone`）。

---

## 5. 数据流

```
进入 StockDetailScreen（已有 uiState：stock/dividends/dividendRatePoints/allForecasts/buyThreshold）
点"✨ AI 解读"
  → StockDetailViewModel.analyzeWithLlm()
  → 置 Loading
  → 并发 async×3 拉 日/周/月 BOLL（fetchBoll，失败→null）
  → 拉现价（fetchQuotes）
  → 组装 StockLlmInput（priceVsLowerPercent 在此算）
  → 读 LlmConfig → !isComplete? → NotConfigured
  → StockLlmPromptBuilder.build(input)
  → LlmApi.chatCompletions(@Url, "Bearer <key>", LlmChatRequest)   ← 复用现有
  → StockLlmAnalysisParser.parse()
  → 映射 StockLlmAnalysisState → 渲染
```

---

## 6. 错误处理

| 场景 | 行为 |
|---|---|
| LLM 配置缺失 | `NotConfigured`，UI 引导去设置 |
| 网络失败 / 超时 | `Error("网络错误，请重试")`，可重试 |
| HTTP 401 / 403 | `Error("API key 无效")` |
| HTTP 429 | `Error("请求过频，稍后重试")` |
| 非法 JSON | 降级：整段塞 `valuation`，其余空，不报错 |
| LLM 返回空 content | `Error("LLM 返回为空")` |
| 某周期 BOLL 拉取失败 | 该周期 `priceVsLowerPercent` 缺失（"—"），不阻塞分析 |
| 无股息数据 | "AI 解读"按钮禁用 |
| Tencent 限流 | 单股仅 3 请求，失败降级，不阻塞 |

---

## 7. 测试策略

JUnit4 + Truth + MockK + kotlinx-coroutines-test（+ Robolectric for VM），**TDD**。

### 7.1 `JsonExtractionTest`（纯函数，新增）
- 以 `{` 开头 → 原样。
- ``` ```json fenced ``` → 取对象。
- ``` ``` 无语言标记 fenced → 取对象。
- 裸 `{...}` 夹前后文 → 截取。
- 无 `{}` → null。
- 空串 → null。

### 7.2 `StockLlmPromptBuilderTest`（纯函数）
- system 含 JSON schema 四字段（`valuation`/`dividendSustainability`/`action`/`risks`）与约束"仅基于"。
- user 含代码/名称/现价/三周期距下轨/分红率趋势/最新股息率/预测/买入线。
- **断言不含成本价/cost/持仓金额。**
- 某周期 BOLL 为 null → 该周期显示"—"。
- 分红率点为空 → 趋势显示"—"/"无"，仍产出合法 prompt。
- forecast/buyThreshold 为 null → 对应行显示"—"，仍合法。

### 7.3 `StockLlmAnalysisParserTest`（纯函数）
- 完整 JSON → 完整 `StockLlmAnalysis`（四字段）。
- 缺 `risks` → 空列表。
- 缺 `action` → 空串。
- ``` ```json fenced ``` → 提取并解析。
- 纯文本 → 降级（`valuation=原文`，其余空）。
- 畸形 JSON → 不抛异常。

### 7.4 `LlmAnalysisParserTest`（回归）
- 重构去重后，现有 6 个用例继续全绿（行为不变）。

### 7.5 `StockDetailViewModelTest`（新增，Robolectric + MockK）
- mock `StockRepository`/`DividendRepository`/`BondYieldRepository`/`LlmApi`/`LlmConfigSource`。
- `analyzeWithLlm()`：配置完整 + mock `fetchBoll` 三周期 + `chatCompletions` 返回合法 JSON → state `Success`，四字段正确。
- 配置缺失 → `NotConfigured`。
- `chatCompletions` 抛 `HttpException(401)` → `Error("API key 无效")`。
- `clearLlmAnalysis()` → 复位 `Idle`。
- 无股息数据 → `analyzeWithLlm()` 早返回不调 api。

### 7.6 不写测试
- Compose UI；真实 LLM / Tencent 三周期调用（手动验证）。

---

## 8. 文件改动清单

### 新增
- `data/repository/JsonExtraction.kt`（去重纯函数）
- `data/repository/StockLlmInput.kt`（输入快照，含三个嵌套 data class）
- `data/repository/StockLlmAnalysis.kt`（结果模型 + `StockLlmAnalysisState` sealed）
- `data/repository/StockLlmPromptBuilder.kt`（纯函数 prompt）
- `data/repository/StockLlmAnalysisParser.kt`（纯函数解析）
- 测试：`JsonExtractionTest`、`StockLlmPromptBuilderTest`、`StockLlmAnalysisParserTest`、`StockDetailViewModelTest`

### 修改
- `data/repository/LlmAnalysisParser.kt` — `extractJsonObject` 改调 `JsonExtraction`（去重，行为不变）。
- `viewmodel/StockDetailViewModel.kt` — 注入 `LlmApi`+`LlmConfigSource`；`uiState.llmAnalysis`；`analyzeWithLlm()`/`clearLlmAnalysis()`；三周期 BOLL 并发拉取 + `StockLlmInput` 组装。
- `ui/screen/StockDetailScreen.kt` — 插入 `StockLlmAnalysisSection`（五态）。

### 不动
- `LlmApi` / `LlmChatRequest` / `LlmChatResponse` / `LlmConfigRepository` / `LlmProviderPresets` / `LlmConfig` / `NetworkModule`（全复用）。
- `BollCalculator` / `KlineRepository` / `StockRepository.fetchBoll`（已就绪）。
- DB schema（`AppDatabase` 保持 v15，无需迁移）。
- 组合级 LLM 代码（`LlmPromptBuilder`/`LlmAnalysisRepository`/`PortfolioViewModel.analyzeWithLlm`）。

---

## 9. 风险与未决

- **每次分析 +3 次网络请求**（日/周/月 BOLL）。单股场景无 `Semaphore` 限流必要；失败周期降级为"—"，不阻塞。
- **BOLL 非持久化**：每次分析重拉 3 周期；后续可加日内缓存（与组合级 spec §9 同一条线路）。
- **JSON 模式兼容性**：三家厂商均支持 `response_format: json_object`；纯文本兜底覆盖。
- **LLM 幻觉**：prompt 约束"仅基于提供数据"；UI 注明"仅供参考，不构成投资建议"。
- **输出 schema 与组合级不同**：刻意不复用 `LlmAnalysis`，避免污染组合级；两 `*AnalysisState` 结构对称，UI 渲染模式可共享但类型独立。
- **VM 编排在 VM 内而非独立 repository**：单股场景逻辑简单（拉 BOLL + 组装 + 调 api），不必引入 `StockLlmAnalysisRepository`（YAGNI）。若后续多处复用可再抽。
