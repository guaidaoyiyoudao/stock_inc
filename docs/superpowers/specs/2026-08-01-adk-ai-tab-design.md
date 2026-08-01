# AI Tab（ADK Agent 聊天 + 30 个股票/财务工具）— 设计文档

**日期:** 2026-08-01
**状态:** Draft
**作者:** brainstorming skill
**关联:**
- `2026-07-26-llm-analysis-design.md`（既有 OpenAI 兼容 LLM 配置链路，已实现）
- `2026-08-01-portfolio-deep-llm-analysis-design.md`（LLM 分析编排，已实现）

---

## 1. 背景与目标

### 问题

App 目前只在「一键评估/截图策略」里以一次性提示词方式使用 LLM，没有自由对话入口。用户希望接入
[Android Agent Development Kit（ADK）](https://developer.android.com/ai/adk)，新增一个中间位置的
「AI」底部 Tab，用于与 LLM 多轮聊天，并让 Agent 能调用股票工具（获取持有股票、个股信息等），
且能**操作 App 数据**（添加自选、添加生活支出等）。

### 目标

1. 集成 ADK for Kotlin（`google/adk-kotlin`，Android 官方 Agent 框架）0.6.0。
2. 底部导航新增「AI」Tab（第 3 位，即「持仓 / 股息收入 / **AI** / 日历 / 成就 / 设置」），提供聊天界面：
   消息气泡、流式打字效果、发送中禁用输入、错误提示。
3. Agent 模型复用用户已在设置页配置的 OpenAI 兼容 LLM（DeepSeek / 智谱 / 通义 / 自定义
   baseUrl + apiKey + model），**不新增任何外部 key**。
4. 单 Agent 提供 **30 个工具**：只读 18 个（行情/个股/基本面/K线/组合/策略/收入查询）+ 写操作 12 个
   （股票/持仓/支出/FIRE 目标，全部带用户确认门）。
5. 写操作工具执行前必须弹确认卡片，用户点「确认」后才真正写入 App 数据（ADK 内置确认门）。
6. 多轮上下文：同一次进程内连续对话（ADK `InMemorySessionService`），跨轮可追问。

### 非目标（YAGNI）

- ❌ 不做会话持久化（进程重启丢对话；后续可换 ADK `RoomSessionService`，本期不引入 Room 2.8.4）。
- ❌ 不接 Gemini API key / Gemini Nano / Firebase AI（用户无对应 key，且国内网络不适配）。
- ❌ 不引入 ADK KSP `@Tool` 处理器（手写 `BaseTool`/`FunctionTool` 即可，少一个 KSP 版本耦合面）。
- ❌ 不做多会话列表、清空会话按钮、语音输入、Markdown 渲染增强。
- ❌ 不做子代理拆分（用户明确：所用模型均为大模型，工具数量不影响 function calling）。
- ❌ 不做备份导入导出类工具（文件级高风险操作，AI 不碰）。
- ❌ 不改既有 LLM 分析链路（`LlmApi`/`LlmChatRequest` 不动）。
- ❌ 不做 LLM 调用结果缓存（聊天不可缓存；工具数据本身走现有仓库缓存）。

### 成功标准

1. 依赖升级后全量单测与构建绿（Kotlin/KSP/minSdk 迁移不破坏现有功能）。
2. AI Tab 可对话：问「我的持仓怎么样」→ 调 `get_holdings`/`get_portfolio_summary` 返回真实数据。
3. 问个股（「600519 现在什么价/能买吗/值多少钱」）→ 调 `get_stock_info`/`get_stock_evaluation`/
   `get_valuation`；问搜索 → `search_stock`。
4. 说「把 600519 加进自选」「记一笔每月 3000 元房租」「把持仓改成 100 股」→ 先弹确认卡片，
   确认后才写入；取消则不写入。
5. 回答流式显示（打字机效果）；未配置 LLM 时显示引导并可一键跳设置 Tab。
6. 网络/解析失败不崩溃，UI 出现可感知错误提示（项目红线 2）。

---

## 2. 关键决策（来自 brainstorming）

| 决策点 | 选择 | 理由 |
|---|---|---|
| Agent 框架 | Google ADK Kotlin `com.google.adk:google-adk-kotlin-core:0.6.0`（Android variant） | 用户指定；官方 Android 一等公民，框架提供 agent 循环/会话/流式事件/确认门 |
| 模型后端 | 复用设置页 OpenAI 兼容配置 + 自写 `Model` 适配器 | ADK 官方只有 Gemini 模型；适配器约 200 行纯映射代码，可单测；无需新 key |
| 替代方案 | 换 Semantic Kernel for Java / OpenAI Java SDK / 自制 loop | 调研后排除：SK Java 重且 Android 无官方示例；OpenAI SDK 无 agent 循环；自制 loop 缺框架能力（用户已选 ADK） |
| Agent 架构 | **单 `LlmAgent` + 30 个工具** | 用户明确：模型均为大模型，工具数量不影响 function calling；不做子代理，实现与事件映射最简单 |
| 工具实现 | 只读工具手写 `BaseTool` 子类；写工具继承 `FunctionTool(requiresConfirmation=true)` | 不引 ADK KSP processor；`FunctionTool` 内置确认门（暂停回合 + `adk_request_confirmation` 事件），写操作必须用户确认 |
| 工具范围 | A 全部只读（13）+ B 全部写操作（11） | 用户逐项确认「A+B 都要」 |
| 会话 | `InMemorySessionService` 单例（固定 appName/userId/sessionId） | 满足进程内多轮；规避 ADK 传递的 Room 2.8.4 与项目 Room 2.6.1 冲突 |
| 工具数据来源 | 现有 Repository（缓存优先，行情单股刷新；写操作走现有校验） | 遵循「离线优先、不新增表、不破坏数据准确性」 |
| 数值计算 | 一律走工具内纯函数（预测/估值/评估/买入线），LLM 不得心算 | 宪法原则 III：数据准确性不可妥协 |
| Tab 位置 | 底部第 3 位（持仓、股息收入、AI、日历、成就、设置） | 用户要求「放在中间」 |

---

## 3. 调研结论（ADK 事实，来自 google/adk-kotlin 源码与 Maven 制品）

- Android 制品 `google-adk-kotlin-core-android` **minSdk = 26**（AAR manifest 实测），官方文档写
  minSdk 24 但制品为准；本项目 minSdk 需 24 → 26。
- Kotlin metadata `mv=[2,1,0]`：消费方 Kotlin 编译器必须 ≥ 2.1.0（项目现 2.0.21，需升级）。
- 官方 `Model` 实现只有 `Gemini`（GenAI SDK）；`Model` 接口为：
  `fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse>`，可自由实现。
- `InMemoryRunner.runAsync(userId, sessionId, newMessage, runConfig)` 返回 `Flow<Event>`；
  `StreamingMode.SSE` 时事件带 `partial` 标记，UI 可打字机式渲染。
- 工具确认门：`FunctionTool`（抽象类）内置 `requiresConfirmation` 门——首次调用时经
  `ToolContext.requestConfirmation()` 记录待确认项并返回错误占位；框架随后发出合成事件
  `adk_request_confirmation`（`args.originalFunctionCall` 内嵌原调用，id 进 `longRunningToolIds`
  使回合暂停）。下一回合用户以 `FunctionResponse(id=合成调用 id, name="adk_request_confirmation",
  response={"confirmed": true/false})` 恢复，`RequestConfirmationProcessor` 重放原工具调用并放行/拒绝。
- 写工具只需继承 `FunctionTool` 并实现 `execute(context, args)`，确认门无需自己写。
- ADK core 传递依赖 Room 2.8.4（仅 `RoomSessionService` 用到）；本期排除 `androidx.room`，
  项目 Room 2.6.1 不动。

---

## 4. 依赖与工程升级

### `gradle/libs.versions.toml`

- `kotlin`: `2.0.21` → `2.1.20`（≥ ADK 元数据 2.1；Compose 编译器插件随 catalog 同步）
- `ksp`: `2.0.21-1.0.28` → `2.1.20-1.0.32`（KSP1 线，Hilt 2.53.1 / Room 2.6.1 兼容，不用动）
- 新增 `adk-kotlin-core = { group = "com.google.adk", name = "google-adk-kotlin-core", version = "0.6.0" }`
- 新增测试依赖 `mockwebserver = { group = "com.squareup.okhttp3", name = "mockwebserver", version.ref = "okhttp" }`

### `app/build.gradle.kts`

- `minSdk = 24` → `26`
- `implementation(libs.adk.kotlin.core) { exclude(group = "androidx.room") }`
- `testImplementation(libs.mockwebserver)`

> 不升级 Hilt / Room / Compose BOM / AGP，避免连带风险。

---

## 5. 组件设计

### 5.1 协议适配层（`data/agent/`，纯函数优先）

**`OpenAiDtos.kt`**：OpenAI Chat Completions 专用 DTO（Gson 注解）：
`OpenAiChatRequest`（model/messages/tools/temperature/top_p/max_tokens/stop/stream）、
`OpenAiMessage`（role/content/tool_calls/tool_call_id）、`OpenAiTool`（type/function/parameters）、
`OpenAiChatResponse`（choices[]/finish_reason）、`OpenAiSseChunk`（delta/content/tool_calls）。
不复用 `data/remote/dto/LlmChatRequest.kt`（其固定带 `response_format=json_object`，聊天场景不应带）。

**`OpenAiProtocol.kt`**（纯函数，全部可单测）：

请求映射 `toOpenAiRequest(llmRequest, config): OpenAiChatRequest`：
- `config.systemInstruction` 文本 → `messages[0] {role:"system"}`
- `contents`：`Role.USER → "user"`、`Role.MODEL → "assistant"`；`Part.text` → `content`
- `Part.functionCall` → 同条 assistant 消息的 `tool_calls`（id/name/args JSON）
- `Part.functionResponse` → `{role:"tool", tool_call_id, content: response 的 JSON 文本}`
- `config.tools[].functionDeclarations` → OpenAI `tools[]`（JSON Schema 转换）
- `temperature / topP / maxOutputTokens / stopSequences` 透传；URL = `config.baseUrl + "chat/completions"`

Schema 转换 `toOpenAiSchema(schema: Schema): Map<String, Any?>`：
`Type.OBJECT/STRING/NUMBER/INTEGER/BOOLEAN/ARRAY → object/string/number/integer/boolean/array`，
透传 `properties / required / description / enum / items`。

响应映射 `toLlmResponse(response, modelName): LlmResponse`：
- `message.content` → `Part(text)`；`message.tool_calls` → `Part(FunctionCall(name, args 解析, id))`
- `finish_reason`：`stop/tool_calls → STOP`、`length → MAX_TOKENS`、`content_filter → SAFETY`、其余 `OTHER`

SSE 解析 `parseSseLines(lines, ...)`（纯函数，逐行 `data:`，`[DONE]` 结束）：
- 累积 `delta.content` 文本；有增量时产出 `LlmResponse(partial=true)` 事件
- 按 `index` 累积 `delta.tool_calls`（id/name/arguments 字符串拼接）
- 流结束产出 `LlmResponse(partial=false)`：完整文本 + 完整 `FunctionCall` 列表

**`OpenAiCompatibleModel.kt`**：实现 `com.google.adk.kt.models.Model`：
- 构造注入：`@LlmClient OkHttpClient`（复用现有 60s 超时 LLM client）+ 当前 `LlmConfig`
- `generateContent(request, stream)` 返回 `Flow`；非流式单请求，流式 SSE（OkHttp 同步 call 挂到
  `flow { }` 内，flow 取消即 cancel call）；`name = config.model`
- 请求头 `Authorization: Bearer <apiKey>`；超时/异常直接上抛，由上层统一转错误事件

### 5.2 工具全集（`data/agent/tools/`）

单 `LlmAgent`，30 个工具按领域拆 4 个文件（仅为可读性，不构成子代理）。全部返回 JSON-native Map。
数值类结果一律来自现有纯函数/仓库，禁止模型自行换算。

**只读 · 个股/行情（`MarketDataTools.kt`，9 个）**

| 工具 | 参数 | 数据源 |
|---|---|---|
| `get_stock_info` | `code`（`600519`/`sh600519`/名称） | `resolveStock` + `fetchQuotes` + 基本面/股息率缓存 |
| `search_stock` | `query` | `StockRepository.searchStocks` |
| `get_dividend_history` | `code` | `DividendRepository` 缓存 |
| `get_dividend_forecast` | `code` | `ForecastCalculator` + 分红缓存 |
| `get_valuation` | `code` | `DividendDiscountCalculator` + 现价 |
| `get_buy_threshold` | `code` | `BondYieldRepository.fetch10YBondYield` + `BuyThresholdCalculator` + 现价/股息率 |
| `get_stock_evaluation` | `code` | `HoldingRecommender`（BOLL 日/周/月 + 股息率门槛） |
| `get_stock_fundamentals` | `code`、`forceRefresh?` | `FundamentalsCacheRepository` + `enrichPayoutRatio`（近 5 期 ROE/负债率/同比/派息率/分红方案） |
| `get_kline` | `code`、`period?`、`bars?` | `KlineRepository.fetchKlines` + `BollCalculator`（前复权 OHLCV K 线 + BOLL 上/中/下轨） |

**只读 · 组合/账户（`PortfolioDataTools.kt`，8 个）**

| 工具 | 参数 | 数据源 |
|---|---|---|
| `get_holdings` | 无 | 持仓快照 + `getCachedPrices`（全量自选含观察仓） |
| `get_portfolio_summary` | 无 | 持仓市值/成本/盈亏 + `ForecastCalculator` 年化股息 + FIRE 覆盖率（`ExpenseCoverageCalculator`） |
| `get_industry_allocation` | 无 | 持仓市值分行业 + `getIndustryTargets` 目标权重 |
| `get_transactions` | `code?`（可选） | `TransactionRepository.getAll` / `getByStock` |
| `get_notification_rules` | 无 | `NotificationRuleRepository` 全局 + 个股阈值 |
| `get_user_strategies` | 无 | `TradeStrategyRepository.activeStrategies` |
| `get_portfolio_signals` | 无 | `PortfolioAdvisor`（仓位控制 + 三周期共振买点；日/周/月 BOLL，Semaphore(3) 限流） |
| `get_dividend_income` | `year?` | `DividendIncomeRepository`（实际到账：年度合计/单股贡献/明细） |

**写 · 股票/持仓（`StockActionTools.kt`，8 个，全部确认门）**

| 工具 | 参数 | 行为 |
|---|---|---|
| `add_stock` | `code` 必填、`shares=0`、`costPerShare=0` | `resolveStock` → `addStock` |
| `remove_stock` | `code` 必填 | `removeStock`（删除自选，破坏性，确认文案注明） |
| `update_holding` | `code`、`shares`、`costPerShare` | `updateShares` + `updateCostPerShare` |
| `add_transaction` | `code`、`type`（BUY/SELL）、`shares`、`price`、`date=today` | `TransactionRepository.addTransaction` + `recomputeHolding` |
| `set_stock_tags` | `code`、`tags`（数组） | `StockRepository.setStockTags` |
| `update_industry_target` | `industry`、`weight` | `StockRepository.updateIndustryTarget` |
| `update_notification_rule` | `code?`、`minYield`、`boostYield`、`thresholdPercent?`、`enabled?` | 无 `code` 写全局评估门槛（`saveEvalThresholds`）；带 `code` 写个股股息率提醒（`saveDividendYieldRule`） |
| `update_stock_settings` | `code`、`buyThresholdMultiplier?`、`yieldPeriod?` | `updateBuyThresholdMultiplier` + `updateYieldPeriod`（倍数 >0，年限 ∈ {1,3,5}） |

**读/写 · 财务（`FinanceActionTools.kt`，5 个；写全部确认门）**

| 工具 | 参数 | 行为 |
|---|---|---|
| `get_living_expenses` | 无 | `LivingExpenseRepository.observeExpenses`（读支出列表，改/删前先取 id） |
| `add_living_expense` | `name`、`amount`、`period=MONTHLY` | `LivingExpenseRepository.addExpense` |
| `update_living_expense` | `id`、`name`、`amount`、`period` | `LivingExpenseRepository.updateExpense` |
| `remove_living_expense` | `id` | `LivingExpenseRepository.deleteExpense`（破坏性，确认文案注明） |
| `set_fire_goal` | `amount` | `FireGoalRepository.saveGoal` |

写工具统一继承 `FunctionTool`（`requiresConfirmation = true`）：
- 首次调用被确认门拦截：不执行 `execute`，回合暂停，发出 `adk_request_confirmation` 事件
  （`args.originalFunctionCall` 内嵌原调用，供 UI 生成确认摘要）
- 确认后 UI 以 `FunctionResponse` 恢复回合，门放行执行 `execute`；取消则返回拒绝错误

参数校验规则：金额 > 0、股数 ≥ 0、代码可解析、周期 ∈ {MONTHLY, YEARLY}；非法时工具返回
`{"error": 中文原因}`，模型转述，不写入。

### 5.3 会话编排（`data/agent/AiChatRepository.kt` + `AiAgentFactory.kt`）

**`AiAgentFactory`**：构造注入全部工具（Hilt），`create(config): LlmAgent` 组装
`LlmAgent(name="ai_tab_agent", model=OpenAiCompatibleModel(config, okHttpClient),
instruction=中文系统指令, tools=listOf(30 个工具))`；每次发送用最新 `config` 快照重建（幂等）。

**`AiChatRepository`**：`@Singleton`，注入 `LlmConfigSource`、`AiAgentFactory`、
`@LlmClient OkHttpClient`：
- 持有单例 `InMemorySessionService`（`appName="stock-dividend-ai"`、`userId="local-user"`、
  `sessionId="ai-tab"`），跨轮次共享上下文。
- `observeConfigured(): Flow<Boolean>`：`configSource.observeConfig()` 映射 `isComplete`。
- `send(text): Flow<AiChatEvent>`：`InMemoryRunner(appName, agent=factory.create(snapshot),
  sessionService=单例)` + `runAsync(newMessage=Content(USER, text), RunConfig(SSE))` 事件映射：
  - `partial` 且含文本 → `AiChatEvent.Partial(text)`
  - 非 `partial` 且含文本 → `AiChatEvent.Final(text)`
  - 事件含普通 `functionCall` → `AiChatEvent.ToolStatus(工具名)`
  - 事件含 `functionCall.name == "adk_request_confirmation"` → `AiChatEvent.ConfirmationRequest(...)`
    （解析 `args.originalFunctionCall` 生成确认摘要；本轮结束等待确认）
  - 任何异常 → `AiChatEvent.Error(用户可读中文消息)`（吞异常红线）
- `confirm(requestId, confirmed): Flow<AiChatEvent>`：`newMessage=Content(USER,
  [Part(functionResponse=FunctionResponse(id=requestId, name="adk_request_confirmation",
  response=mapOf("confirmed" to confirmed)))])` 再次 `runAsync`，事件映射与 `send` 相同

系统指令（中文）：
> 你是「股息追踪」App 的 AI 投资助手。涉及持仓、个股行情、估值、股息、买入线、行业配比、
> 通知规则或用户策略时，必须调用对应工具获取真实数据，禁止编造股票代码、价格、收益率与
> 计算结果。添加/修改/删除自选、持仓、交易、支出、FIRE 目标、标签、行业目标、通知规则等
> 写操作，必须调用对应工具并等待用户确认。回答简洁中文，可用 Markdown；涉及投资建议时提示仅供参考。

### 5.4 ViewModel（`viewmodel/AiChatViewModel.kt`）

按项目 ViewModel 约定：`@HiltViewModel` + 单个 `@Stable AiChatUiState` + `MutableStateFlow`：

```kotlin
@Stable
data class AiChatUiState(
    val messages: List<ChatMessageUi> = emptyList(),
    val isSending: Boolean = false,
    val llmConfigured: Boolean = false,
    val input: String = "",
    val pendingConfirmation: ConfirmationUi? = null,
)

data class ChatMessageUi(val role: ChatRole, val text: String)  // USER / AGENT / SYSTEM

data class ConfirmationUi(
    val requestId: String,
    val toolName: String,
    val summary: String,  // 如「添加自选：600519 贵州茅台」「删除支出：房租 3000 元/月」
)
```

- Collector 1：`observeConfigured()` → 更新 `llmConfigured`
- `onInputChanged(text)` / `onSend()`：追加用户消息 → `isSending=true` → `repository.send().collect`：
  `Partial` 更新/新建 agent 气泡、`Final` 覆盖为最终文本、`ToolStatus` 追加系统气泡（不落库）、
  `ConfirmationRequest` 设置 `pendingConfirmation`、`Error` 追加 SYSTEM 气泡；
  `finally` 里显式 `isSending=false`（红线 3）
- `onConfirm(pending)` / `onReject(pending)`：`pendingConfirmation=null` → 复用同一收集逻辑跑
  `repository.confirm(requestId, confirmed)`；确认期间输入栏保持禁用
- `onGoSettings()` 由 UI 回调切 Tab，不占 VM 状态

### 5.5 UI（`ui/screen/AiChatScreen.kt`）

- `LazyColumn` 消息列表（用户右对齐、Agent 左对齐 Card、系统消息灰色小字），自动滚到底部
- 底部输入行：`OutlinedTextField` + 发送按钮；`isSending` 时禁用并显示进度指示
- 确认卡片：`pendingConfirmation != null` 时在输入栏上方显示 Card（工具名 + 参数摘要 +
  「确认」/「取消」按钮）；确认处理中按钮禁用
- 空状态：问候语 + 示例问题（「试试问：我的持仓怎么样？」）
- 未配置 LLM：`EmptyStateView` +「去设置配置 LLM」按钮 → 切到 settings Tab
- 复用 `DesignSystem` 的 Card 样式与 `FinanceGreen/FinanceRed` 正负色（如展示盈亏时）

### 5.6 导航（`ui/screen/MainScaffold.kt`）

- `bottomNavItems` 在 index 2 插入 `BottomNavItem("ai", "AI", Icons.Filled.SmartToy)`
- `NavHost` 增加 `composable("ai") { AiChatScreen(onGoSettings = { 切到 settings Tab }) }`
- 现有 5 Tab 变 6 Tab，`selectedTabIndex` 逻辑不变

---

## 6. 数据流

```text
用户输入 → AiChatViewModel.onSend()
  → AiChatRepository.send(text)
      → AiAgentFactory.create(最新 LlmConfig) → LlmAgent(model + 30 工具)
      → InMemoryRunner.runAsync(sessionId="ai-tab", StreamingMode.SSE)
          → LlmAgent 调 model.generateContent(stream=true)
              → OpenAiCompatibleModel: POST {baseUrl}/chat/completions (stream SSE)
                  → 模型返回 text 增量 / tool_calls
          → 有 functionCall → 执行工具（只读：读 Repository；写：先过确认门）
          → 结果回传模型 → 继续生成最终文本
      → Event 流映射为 Partial/Final/ToolStatus/ConfirmationRequest/Error
  → UiState.messages 更新 → Compose 渲染气泡
```

多轮：`InMemorySessionService` 保留历史事件；下一次 `runAsync` 自动带上文。

写操作确认分支：

```text
模型请求任一写工具
  → FunctionTool 确认门拦截（requiresConfirmation=true）
  → 回合暂停，发出 adk_request_confirmation 事件（含原调用参数）
  → VM pendingConfirmation → UI 确认卡片
      → 用户点「确认」→ confirm(requestId, true) → FunctionResponse 恢复回合
          → 门放行 → execute() 写入 Repository → 模型总结结果
      → 用户点「取消」→ confirm(requestId, false) → 返回拒绝错误 → 模型告知「已取消」
```

---

## 7. 错误处理

| 场景 | 处理 |
|---|---|
| LLM 未配置 | UI 引导页，不进入发送流程 |
| 网络超时/失败、HTTP 非 2xx、SSE 解析失败 | `AiChatRepository` catch → `Error` 事件 → SYSTEM 气泡「请求失败：xxx」；不崩溃 |
| 工具内部异常（仓库吞异常返回空值） | 工具返回 `mapOf("error" to "查询失败")`，模型据此回答 |
| 流式中途取消 | flow 取消 → OkHttp call cancel；VM `finally` 复位 `isSending` |
| 模型不支持工具调用 | 工具 schema 不生效，模型只回文本；不阻塞普通聊天 |
| 写工具被确认门拦截 | 本轮正常结束并等待确认卡片；不执行任何写入 |
| 用户取消写操作 | 返回拒绝错误，模型告知已取消；不执行任何写入 |
| 写参数非法（金额≤0、股数<0、代码解析失败、周期非法） | 工具返回 `{"error": 中文原因}`，模型转述，不写入 |

---

## 8. 测试策略

| 测试 | 类型 | 要点 |
|---|---|---|
| `OpenAiProtocolTest` | 纯 JUnit + Truth | 请求映射（system/tools/tool 消息）、Schema 转换、响应映射、finishReason |
| `OpenAiSseParserTest` | 纯 JUnit + Truth | `data:` 行解析、`[DONE]`、文本增量 partial、tool_calls 按 index 累积 |
| `OpenAiCompatibleModelTest` | JUnit + MockWebServer | 非流式/流式请求体与响应、Authorization 头、取消 |
| `StockAgentToolsTest` | JUnit + MockK | 30 个工具的 declaration/run，仓库 mock，空数据/异常/非法参数路径；写工具确认门：未确认不执行、确认后执行、拒绝不执行 |
| `AiChatRepositoryTest` | JUnit + 假 Model | 用脚本化假 `Model`（预置 tool_calls/文本流）跑真实 `InMemoryRunner`：工具调用回路、确认暂停/恢复、事件映射、异常转 Error |
| `AiChatViewModelTest` | Robolectric + MockK | 发送流程、流式 Partial/Final 更新、确认卡片出现与确认/取消恢复、Error 气泡、`isSending` 复位、未配置态 |

依赖注入写法沿用项目惯例（`LlmAnalysisRepositoryTest` 风格：fake `LlmConfigSource`）。

---

## 9. 文件改动清单

**新增（main）**
- `app/src/main/java/com/stock/dividend/data/agent/OpenAiDtos.kt`
- `app/src/main/java/com/stock/dividend/data/agent/OpenAiProtocol.kt`
- `app/src/main/java/com/stock/dividend/data/agent/OpenAiCompatibleModel.kt`
- `app/src/main/java/com/stock/dividend/data/agent/AiAgentFactory.kt`
- `app/src/main/java/com/stock/dividend/data/agent/AiChatRepository.kt`
- `app/src/main/java/com/stock/dividend/data/agent/tools/MarketDataTools.kt`
- `app/src/main/java/com/stock/dividend/data/agent/tools/PortfolioDataTools.kt`
- `app/src/main/java/com/stock/dividend/data/agent/tools/StockActionTools.kt`
- `app/src/main/java/com/stock/dividend/data/agent/tools/FinanceActionTools.kt`
- `app/src/main/java/com/stock/dividend/viewmodel/AiChatViewModel.kt`
- `app/src/main/java/com/stock/dividend/ui/screen/AiChatScreen.kt`

**新增（test）**
- `app/src/test/java/com/stock/dividend/data/agent/OpenAiProtocolTest.kt`
- `app/src/test/java/com/stock/dividend/data/agent/OpenAiSseParserTest.kt`
- `app/src/test/java/com/stock/dividend/data/agent/OpenAiCompatibleModelTest.kt`
- `app/src/test/java/com/stock/dividend/data/agent/StockAgentToolsTest.kt`
- `app/src/test/java/com/stock/dividend/data/agent/AiChatRepositoryTest.kt`
- `app/src/test/java/com/stock/dividend/viewmodel/AiChatViewModelTest.kt`

**修改**
- `gradle/libs.versions.toml`（kotlin/ksp 版本、adk 依赖、mockwebserver）
- `app/build.gradle.kts`（minSdk 26、adk 依赖 + room exclude）
- `app/src/main/java/com/stock/dividend/ui/screen/MainScaffold.kt`（Tab + 路由）

---

## 10. 风险与未决

1. **各家 OpenAI 兼容厂商对流式 tool_calls 兼容性**（DeepSeek/GLM/Qwen 主流均支持）：若个别厂商
   流式工具异常，文本聊天不受影响，工具路径报错可见；后续可加「工具调用时回退非流式」。
2. **ADK 0.6.0 是新版本**：API 若与示例有出入，以本地源码 `google/adk-kotlin` 为准锁定使用。
3. **minSdk 24 → 26**：放弃 Android 7.x（2016 年设备）；个人工具，可接受，需在发布说明注明。
4. **对话不持久化**：进程被杀即丢，属有意取舍；`RoomSessionService` 留作后续（需同步升级 Room）。
5. **依赖升级面**：Kotlin 2.1.20 + KSP 2.1.20-1.0.32 为 KSP1 线，Hilt/Room 保持不动；
   若构建期出现 KSP 兼容问题，兜底方案是升 KSP2 线（2.1.20-2.0.1）并同步升 Hilt/Room。
6. **30 个工具对 function calling 精度的影响**：用户明确所用模型均为大模型，接受此取舍；
   写操作仍有确认门兜底，只读误调无副作用。
7. **确认流依赖 ADK 暂停/恢复机制**：已在源码确认 `RequestConfirmationProcessor` + `FunctionTool`
   行为；若个别厂商模型在工具返回错误后自行继续生成而非暂停，确认卡片仍会出现，但以 UI 状态为准，
   模型文本可能与实际写入不一致（写操作仍受确认门保护）。
