# DeepSeek Responses API 对接文档

> 本文档基于 2026-08-06 真实抓包（curl 流式 + App OkHttp 日志）整理，供后续精细开发参考。
> 所有事实均经实测验证，标注「⚠️ 实测」的为踩坑点。
> 对接代码：`app/src/main/java/com/stock/dividend/data/agent/DeepSeekResponsesProtocol.kt`（请求/响应）
> + `DeepSeekResponsesSse.kt`（流式 SSE）+ `OpenAiCompatibleModel.kt`（HTTP 调用）。

---

## 1. API 概览

| 项 | 值 |
|---|---|
| 端点 | `POST https://api.deepseek.com/responses`（**不带 `/v1`**）⚠️ 实测 |
| 协议 | 与 OpenAI Responses API 兼容（结构一致，非 Chat Completions） |
| 鉴权 | `Authorization: Bearer <API_KEY>` |
| 模型 | **仅 `deepseek-v4-flash`**（推理模型，deepseek-v4-pro 暂不支持） |
| 关键差异 | 与 Chat Completions(`/chat/completions`)是**两套独立格式**，端点/请求体/SSE 都不同 |

### ⚠️ 端点路径坑（已踩并修复）

用户预设 baseUrl 是 `https://api.deepseek.com/v1/`（Chat Completions 兼容路径）。
但 Responses API 端点是 `https://api.deepseek.com/responses`（**不带 /v1**）。
代码须 `baseUrl.trimEnd('/').removeSuffix("/v1") + "/responses"`，否则 404。
见 `OpenAiCompatibleModel.generateContent`。

---

## 2. 请求格式

### 2.1 请求体结构

```jsonc
{
  "model": "deepseek-v4-flash",
  "input": [ /* input items 数组，或纯字符串 */ ],
  "instructions": "系统提示词（可选）",
  "tools": [ /* function / web_search 工具（可选）*/ ],
  "temperature": 1.0,       // 可选，默认 1.0
  "top_p": 1.0,             // 可选
  "max_output_tokens": 8192,// 可选
  "stop": ["..."],          // 可选
  "stream": false           // 是否流式
}
```

### 2.2 input items（核心）

input 是数组时，每个 item 按 `type` 区分。**至少传 input 或 instructions 之一**。

| type | 作用 | 关键字段 |
|---|---|---|
| `message`（可省略 type） | 普通消息 | `role`(user/assistant/system)、`content`(字符串或数组) |
| `function_call` | 模型发起的工具调用（回放历史） | `call_id`、`name`、`arguments`(JSON 字符串) |
| `function_call_output` | 工具执行结果（喂回模型） | `call_id`、`output`(JSON 字符串) |

```jsonc
// 对话消息
{"type":"message","role":"user","content":"600519 现在能买吗？"}

// 工具调用（模型上一轮产出，回放）
{"type":"function_call","call_id":"call_00_xxx","name":"get_stock_info","arguments":"{\"code\":\"600519\"}"}

// 工具结果（执行后喂回）
{"type":"function_call_output","call_id":"call_00_xxx","output":"{\"price\":1306.45}"}
```

### 2.3 tools（工具声明）

工具数组异构，按 `type` 区分：

```jsonc
// function 工具（顶层 name，非嵌套 function.name —— 与 Chat Completions 不同！）
{
  "type": "function",
  "name": "get_stock_info",
  "description": "查询个股实时行情",
  "parameters": { /* JSON Schema */ }
}

// web_search 内置工具（服务端执行，仅 Responses API 可用）
{ "type": "web_search" }
```

⚠️ **function 工具的 name 在顶层**（`{type,name,parameters}`），不是 Chat Completions 的嵌套 `{type:"function",function:{name,...}}`。
`tool_choice` 支持 `auto`(默认)/`required`/`none`/`{type:"web_search"}`(强制)。
`search_context_size`、`user_location` 参数被忽略（不报错但不生效）。

---

## 3. 响应格式（非流式）

```jsonc
{
  "id": "resp_xxx",
  "status": "completed",       // completed / incomplete / failed
  "output": [ /* OutputItem 数组，按 type 区分 */ ],
  "usage": { "input_tokens":87, "output_tokens":35, "total_tokens":122 }
}
```

### 3.1 output item 类型

遍历 output 数组，**按 `type` 分支处理**：

| type | 处理 |
|---|---|
| `message` | 取 `content[].text`（content[].type=`output_text`）→ 文本回复 |
| `function_call` | `call_id`/`name`/`arguments`(JSON 字符串) → 工具调用 |
| `reasoning` | 推理过程（`content[].type=reasoning_text`）→ 思考链，**展示用，不当最终回复** |
| `web_search_call` | 服务端搜索记录（含 `action.queries`/`action.url`）→ 展示用，忽略不影响逻辑 |
| 其他 | 忽略 |

⚠️ **output 顺序不固定**：推理模型会先发 `reasoning`，再发 `message`/`function_call`。
实测一次 web_search 响应含 25 个 item：8 reasoning + 10 web_search_call + 7 message。

---

## 4. 流式 SSE（stream=true）⚠️ 核心，与 Chat Completions 完全不同

### 4.1 wire 格式

**typed events**：每个事件块是两行 + 空行分隔：
```
event: response.output_text.delta
data: {"type":"response.output_text.delta","delta":"你好","sequence_number":14}

```
- `event:` 行 = 事件类型
- `data:` 行 = JSON payload（payload 内也有 `type` 字段，与 event 行名一致）
- 空行分隔事件块
- **流以 `response.completed` 结束，没有 `data: [DONE]`**（与 Chat Completions 不同）

### 4.2 关键事件类型（按处理优先级）

| 事件 | data 关键字段 | 处理 |
|---|---|---|
| `response.output_text.delta` | `delta` | **最终回复文本增量**，累积 |
| `response.reasoning_text.delta` | `delta` | **思考过程增量**，单独展示（→ Part(thought=true)） |
| `response.reasoning_text.done` | `text`(完整)、`item_id` | **一段思考结束信号**，UI 据此停「思考中…」转圈（多轮时每轮结束都发） |
| `response.output_item.added` | `item`(含 type/call_id/name/id) | function_call 开始，记 call_id/name |
| `response.function_call_arguments.delta` | `item_id`、`delta` | **工具调用参数增量**，按 item_id 拼接 |
| `response.output_item.done` | `item`(完整) | function_call 完成，写回拼好的 arguments |
| `response.completed` | `status`、`output`(完整数组) | 流结束，取最终 output |
| `response.failed` / `response.incomplete` | `status`、错误信息 | 失败，转 errorMessage |
| `response.web_search_call.*` | action.queries/url | 搜索进度（展示用，可忽略） |
| `response.created` / `.in_progress` | — | 生命周期，忽略 |

### 4.3 ⚠️ 流式 function_call 坑（已踩并修复）

**`response.completed` 的 output 数组实测只带 `message`，不带 `function_call`！**
（非流式响应的 output 会带 function_call，但流式的 completed 精简了）。

→ **必须在流式过程中自行累积 function_call**：
1. `output_item.added` 时记 `call_id`/`name`（按 item `id` 索引）
2. `function_call_arguments.delta` 按 `item_id` 拼参数
3. `output_item.done` 时写回完整 arguments
4. `finish()` 时把累积的 function_call 并入 output（去重 by item id）

见 `ResponsesSseAccumulator`。**漏掉这步 = 工具调用丢失 = "思考有回复空" bug**。

### 4.4 推理模型特征

deepseek-v4-flash 是推理模型，**每次响应都先发大量 `reasoning_text.delta`**（实测一轮可达 400~3800 个）。
- web_search 时 reasoning 持续 30~60s，期间无 output_text → 用户以为卡住
- 解决：把 reasoning 流式展示为"思考过程"区（→ Part(thought=true)），见 `AiChatRepository.emitEvent`
- **思考结束**：`reasoning_text.done` 事件到达时，Model 推空 thought Part → emitEvent 发 `ThinkingDone` → ViewModel `finalizeThinking()` 停转圈。多轮工具调用时每轮思考结束都会停一次，下次思考增量到达时重新转圈。

---

## 5. 多轮工具调用循环（ADK 驱动）

App 用 ADK Runner 驱动多轮：

```
用户消息 → POST /responses（第1轮）
  ← output 含 function_call（get_stock_info）
  ← ADK 执行工具，拿结果
  → POST /responses（第2轮，input 含 function_call + function_call_output）
  ← output 含更多 function_call 或最终 message
  → ...循环直到 output 无 function_call（isFinalResponse）
```

⚠️ **每轮都走同一个 OpenAiCompatibleModel（useResponsesApi 不变）**。
ADK 的 `Event.isFinalResponse` 判断：无 functionCall + 非 partial → 结束循环。

---

## 6. 实测数据（2026-08-06，sk-1809 key）

| 场景 | 结果 |
|---|---|
| 基础对话 "你好" | ✅ HTTP 200，reasoning(39 delta) + message |
| 单工具 "查询茅台价格" | ✅ 第1轮返回 function_call，第2轮返回完整 message |
| 44 工具评估 "600519能买吗" | ✅ 3 轮 /responses，完整评估（行情/买入线/BOLL/研报） |
| web_search "今天A股" (curl) | ✅ 5 次搜索，reasoning(1352) + 搜索结果回复 |
| 流式耗时 | 单轮 1.4s；web_search 全程 30~60s（reasoning 占大头） |
| chunk 间隔 | 流式最大间隔 1.2s（逐 token 高频），不触发 readTimeout |

---

## 7. 对接代码索引

| 文件 | 职责 |
|---|---|
| `DeepSeekResponsesProtocol.kt` | `buildResponsesRequest`（ADK→请求）、`toLlmResponse`（响应→ADK）、DTO |
| `DeepSeekResponsesSse.kt` | `ResponsesSseLineParser`（event+data 跨行配对）、`ResponsesSseAccumulator`（累积文本/function_call/思考） |
| `OpenAiCompatibleModel.kt` | HTTP 调用（`/responses` 端点去 v1）、流式/非流式分支、reasoning→Part(thought=true) |
| `AiChatRepository.kt` | `emitEvent` 区分 thought(思考)/text(回复)/functionCall(工具) |
| `AiAgentFactory.kt` | `useResponses = config.model == "deepseek-v4-flash"`；`includeWebSearch = useResponses && webSearch开关` |

### 7.1 路径决策矩阵

| 用户配置 | useResponsesApi | 思考过程 | web_search |
|---|---|---|---|
| model=`deepseek-v4-flash` | ✅ true | ✅ 始终展示 | 联网开关控制 |
| model=其他（v4-pro/chat/别厂） | ❌ false | ❌ 无 | ❌ 无 |

---

## 8. 已知限制 & 待优化

1. **web_search 触发依赖消息语义**：模型对模糊措辞（如英文"search news"）可能不触发，需明确中文指令（"帮我搜今天A股"）。非 bug，模型行为。
2. **reasoning 偶发占满 token**：极少数情况 reasoning 过长导致无 output_text → 响应空。已加 errorMessage 透出兜底。
3. **非 DeepSeek 厂商**：不支持 Responses API，强制走 Chat Completions（无思考/搜索）。
4. **readTimeout**：已调到 180s（web_search 全程可达 60s+，给足余量）。

---

## 9. 官方参考

- 指南：https://api-docs.deepseek.com/zh-cn/guides/responses_api
- API 参考：https://api-docs.deepseek.com/zh-cn/api/create-response
- OpenAI Responses 流式事件（DeepSeek 兼容）：https://community.openai.com/t/responses-api-streaming-the-simple-guide-to-events/1363122
