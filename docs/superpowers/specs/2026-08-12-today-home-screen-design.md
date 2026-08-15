# 今日首页（Today Home Screen）设计

**日期**：2026-08-12
**状态**：已通过 brainstorming，待用户 review
**关联问题**：「APP 功能多但日活低」——作者自己几乎每天都不打开

---

## 1. 背景与诊断

股息追踪 APP 功能丰富（持仓/股息/FIRE/评估/AI Agent/网格/DRIP 等），但作者日常几乎不打开。

**根因诊断**（经 brainstorming 确认）：
- 起始页是持仓列表（`MainScaffold.kt:137` 的 `startDestination = "portfolio"`），5 个 Tab（持仓/收入/AI/成就/设置）平级。
- 打开第一眼是一堆平等的持仓卡片，用户需**主动**找「今天该看什么」，认知成本高。
- 几次后大脑判定「不值得开」→ 日活下降。

**目标场景**（用户选定）：每天轻量看一眼——像证券 app 一样扫一眼，但要更快、更聚焦自己的持仓/信号。

**已否决的反方向**：
- 不走「红利投资本质低频、不该追求日活」——用户明确想要日常钩子。
- 不走「强行加行情/资讯」——会把它变成劣化版雪球/东财，背叛「股息追踪」初心。

## 2. 方案选择

三个候选方向（经 brainstorming 对比）：

| 方案 | 形态 | 取舍 |
|---|---|---|
| **A. 今日首页 Tab** ✨选定 | 新增「今日」Tab 作起始页，一屏聚合今日一瞥 | 治本、可控、不依赖 LLM |
| B. 顶部摘要带 | 持仓列表顶部插今日摘要卡 | 改动小，但是补丁；首屏仍被列表主导 |
| C. AI 晨报起始页 | 起始页 = AI 每天生成的晨报 | 差异化最强，但依赖 LLM 可用性/成本 |

**选定 A**，并将 C 的精神（AI 解读）以轻量形式（顶部一句话总结）融入 A，作为 A 的演进而非替代。

## 3. 首屏内容设计

`TodayScreen` 一屏三块（自上而下）：

### ① AI 一句话总结（顶部，钩子）
- 每日盘后（15:45）定时生成一次并缓存，打开即见、零等待、离线可用、成本可控
- 一句话 ≤50 字，例：
  - 「组合 +0.8% 跑赢大盘，长安银行跌破买入门槛，建议关注。」
  - 「今日无信号，组合平静。」
- **数据准确性（宪法 III）**：AI 只解读，不生成新数字；展示数字仍来自卡片本身
- 输入：② 组合表现 + ③ 信号列表（结构化数据）
- 兜底：未配置 / 缓存缺失 / 失败 → **该卡不显示**（不占位、不报错、不阻塞首屏）

### ② 组合表现 + 大盘对照
| 字段 | 来源（全部已有） |
|---|---|
| 总市值 | `PortfolioViewModel` holdingsFlow + 现价 |
| 今日盈亏 ¥/%（昨收→现价） | `PriceCacheEntity`（有昨收） |
| 累计盈亏 ¥/%（相对成本） | `HoldingCalculator` |
| 大盘锚点：上证/沪深300 今日 % | `MarketDataRepository.getIndexQuote` |
| 对照结论：「今日跑赢沪深300 X.XX%」 | 纯计算 |

- 交互：点卡片 → 持仓 Tab
- 时间锚点：**固定今日**（YAGNI；红利投资者不应被短期波动干扰，要切换后续再加）

### ③ 信号卡（差异化价值）
三类信号，每条一行（股票名 + 信号 + 当前值→阈值 + 「查看」）：
- **买入触发**：股价跌破买入门槛（`BuyThresholdCalculator`）/ 跌破 BOLL 下轨 / 股息率高于门槛 —— 复用 `HoldingRecommender`（覆盖自选股 + 持仓，红线 #4）
- **网格下一档**：现价触及网格计划「下一档买」（`GridCalculator.nextBuyHint`）
- **分红倒计时**：未来 30 天内有除权日（基于 `dividends` 表）

- 交互：点信号 → 对应股 `stockDetail`（网格信号 → `gridPlanFor`）
- 空状态：「今日无信号，组合平静」（诚实空状态，宪法 III）

### 砍掉的内容（YAGNI）
- 原设计第 ④ 块「分红预览」（最近一笔 + 年度同比）：低频，不该占每日首屏。年度汇总留在股息收入 Tab；**关键分红信息仍以「分红倒计时」信号形式出现在 ③**。

## 4. 信息架构 / 导航变更

**Tab 结构（5→5，腾位给「今日」）**：

| 旧 | 新 |
|---|---|
| 持仓（起始） | **今日（起始）** |
| 股息收入 | 持仓 |
| AI | 股息收入 |
| 成就 | AI |
| 设置 | 设置 |

- **「成就」降级** → 设置页二级入口（成就本质是使用彩蛋，低频）
- **起始页切换安全性**：
  - 只改 `startDestination: portfolio → today`，`portfolio` 仍是 Tab
  - 所有二级路由（`stockDetail` / `editHolding` / `gridPlanFor` / `dividendValuation` / `dripSimulation` / `gridPlan`）不变
  - 通知 deepLink（跳 stockDetail）不受影响

## 5. 实现架构

### 数据流 —— `TodayViewModel`（参考 `PortfolioViewModel` §4.2，多 collector）

```
TodayViewModel
 ├─ collector A: holdingsFlow + 价格 → 组合市值 / 今日盈亏 / 累计盈亏
 ├─ collector B: getIndexQuote(上证/沪深300) → 大盘对照
 ├─ collector C: 持仓+自选+BOLL+门槛+网格+分红日历 → TodaySignalAggregator → 信号列表
 └─ collector D: LlmAnalysisCacheStore(按日期读) → AI 简报
```

### 新增纯函数（§4.4，配单测）

| 文件 | 输入 → 输出 |
|---|---|
| `TodaySignalAggregator` | 持仓+自选（现价/BOLL/门槛/股息率）+ 网格计划 + 30 天分红日历 → 排序信号列表（复用 `HoldingRecommender` / `GridCalculator.nextBuyHint`） |
| `TodayBriefingPromptBuilder` | 组合表现 + 信号列表 → LLM prompt（约束「一句话 ≤50 字、只解读不臆造数字」） |
| `TodayBriefingParser` | LLM 响应 → 一句话字符串（容错，复用 `JsonExtraction` 风格） |

### AI 简报定时生成 —— `TodayBriefingWorker`（参考 `NotificationCheckWorker` §4.8）
- 每日盘后定时（15:45，A 股 15:00 收盘后留 15 分钟数据稳定）
- 流程：拉行情 → `TodaySignalAggregator` → `TodayBriefingPromptBuilder` → LLM → `TodayBriefingParser` → 写 `LlmAnalysisCacheDao`（key=`today_briefing_YYYY-MM-DD`）
- **失败静默**（红线 #2）

### 错误处理（宪法 V + 红线 #2）
- 行情失败 → `PriceCacheEntity` 缓存 + 「数据可能延迟」小字
- AI 缓存缺失 / 未配置 → AI 卡不显示
- 信号聚合异常 → 吞掉返回空，「今日无信号」
- 新用户全空 → 引导加股

## 6. 测试策略（§6）

- **纯函数**：`TodaySignalAggregator` / `TodayBriefingPromptBuilder` / `TodayBriefingParser` → JUnit4 + Truth
- **VM**：`TodayViewModelTest` → Robolectric + MockK + Turbine
- **Worker**：`TodayBriefingWorkerTest` → MockK，验证「拉数据 → 生成 → 缓存」编排

## 7. schema 影响

**不改 schema**。DB version 保持 **20**。
- AI 简报复用 `LlmAnalysisCacheDao`（key 加日期前缀）
- 信号聚合是纯函数，不落库

## 8. 后续演进（非本期，仅备忘）

- 方案 C「AI 晨报」可作为今日页的增强（AI 卡片从一句话扩展为多段解读）
- 时间锚点切换（本周 / 本月 / 今年）
- FIRE 进度条 / 组合健康度若日后需要，可下沉到今日页
