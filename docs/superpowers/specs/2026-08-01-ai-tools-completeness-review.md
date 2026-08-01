# AI Tab 工具完备性评审（2026-08-01）

> 评审对象：AI Tab 当前已集成的全部 Agent 工具（HEAD `c0a5596`，`data/agent/tools/`）。
> 问题：「当时已有工具，是否完整利于 AI 分析股票？」

## 1. 结论

**评审时（25 个工具）不完整，但主链路已闭环；P0+P1+P2 全部补齐后（30 个工具）已达到完整。**
评审时缺的 6 类能力（基本面、K 线/BOLL 原始数据、组合级信号、标签读取、实际股息收入、个股规则/参数写入）已全部实现，见 §4 状态列与 [2026-08-01-adk-ai-tab-design.md](2026-08-01-adk-ai-tab-design.md)。

## 2. 现状清单（评审 25 → 补齐 30 个工具）

> 评审时点为 25 个工具（14 只读 + 11 写）；补齐后为 **30 个工具（18 只读 + 12 写）**，新增项以 ✅ 标注。

### 只读 · 个股/行情（7 → 9）

| 工具 | 返回内容 | 数据源 |
|---|---|---|
| `get_stock_info` | 现价、行业、最近股息率、除权日 | `resolveStock` + `fetchQuotes`（失败回退缓存） |
| `search_stock` | 代码、名称、市场、现价 | `searchStocks` |
| `get_dividend_history` | 历史分红（报告期、每股、股息率、除权日） | `DividendRepository` 缓存 |
| `get_dividend_forecast` | 近 3 年平均每股、年化预测收入、下次除权日 | `ForecastCalculator` |
| `get_valuation` | DDM 内在价值、安全买入价、折溢价、估值状态 | `DividendDiscountCalculator` |
| `get_buy_threshold` | 10Y 国债、倍数、目标/当前股息率、是否达标 | `BondYieldRepository` + `BuyThresholdCalculator` |
| `get_stock_evaluation` | BOLL 日/周/月位置结论 + 股息率门槛 → BUY/HOLD/SELL + reasons | `HoldingRecommender` |
| ✅ `get_stock_fundamentals` | 近 5 期 ROE/负债率/营收净利同比/EPS/派息率/分红方案（支持 forceRefresh） | `FundamentalsCacheRepository` + `enrichPayoutRatio` |
| ✅ `get_kline` | 前复权 OHLCV K 线（日期/开/收/高/低/量，旧→新）+ 收盘序列 + BOLL 上/中/下轨（period/bars 可选） | `KlineRepository.fetchKlines` + `BollCalculator` |

### 只读 · 组合/账户（6 → 8）

| 工具 | 返回内容 | 数据源 |
|---|---|---|
| `get_holdings` | 全量自选/持仓：代码、名称、股数、成本、现价、市值、盈亏、行业、✅标签、✅最后更新时间 | 持仓快照 + 价格缓存 + 标签表 |
| `get_portfolio_summary` | 总市值/成本/盈亏、年化股息预测、FIRE 进度、支出覆盖率 | `ExpenseCoverageCalculator` 等 |
| `get_industry_allocation` | 行业市值占比 vs 目标配比 | `getIndustryTargets` |
| `get_transactions` | 交易记录（可选按股过滤） | `TransactionRepository` |
| `get_notification_rules` | 全局股息率门槛 + 各股价格/股息率规则 | `NotificationRuleRepository` |
| `get_user_strategies` | 全局策略库（投资原则） | `TradeStrategyRepository` |
| ✅ `get_portfolio_signals` | 仓位控制（上轨占比/平均股息率/现金比例）+ 三周期共振买点列表 | `PortfolioAdvisor`（Semaphore(3) 限流，与 App 评估同口径） |
| ✅ `get_dividend_income` | 实际到账：年份/年度合计/单股贡献/记录数/最大单笔；传 year 返回明细 | `DividendIncomeRepository` |

### 只读 · 财务（1）

| 工具 | 返回内容 | 数据源 |
|---|---|---|
| `get_living_expenses` | 全部生活支出（id/名称/金额/周期） | `LivingExpenseRepository` |

### 写 · 股票/持仓（7 → 8，全部确认门）

`add_stock`、`remove_stock`、`update_holding`、`add_transaction`、`set_stock_tags`、`update_industry_target`、`update_notification_rule`（✅ 支持个股股息率提醒）、✅ `update_stock_settings`（买入线倍数 + 预测年限）

### 写 · 财务（4，全部确认门）

`add_living_expense`、`update_living_expense`、`remove_living_expense`、`set_fire_goal`

> 文档漂移已修复：设计文档与 `AiAgentFactory` 注释统一为 30 个（18 读 + 12 写），
> 设计文档工具表已补 `get_living_expenses` 与全部新增工具。

## 3. 评审维度：AI 分析股票需要什么

按「股息追踪」产品核心能力拆解，分析一只/一个组合的股票至少需要：

1. **行情与基本信息**（现价、行业、市场）——✅ 已覆盖
2. **盈利质量与成长**（ROE、资产负债率、营收/净利同比、派息率、分红方案）——❌ 未覆盖
3. **价格走势与位置**（K 线收盘序列、BOLL 上/中/下轨数值、位置百分比）——⚠️ 只有结论，无原始数据
4. **分红历史与预测**（历年每股分红、1/3/5 年均值、预测收入、除权日）——⚠️ 历史 ✅，预测只有 3 年
5. **估值与买入线**（DDM、10Y 国债买入线）——✅ 已覆盖
6. **单股评估**（三周期 BOLL + 股息率门槛 → 买/持/卖）——✅ 已覆盖（复用了程序计算，禁止 LLM 心算）
7. **组合层信号**（仓位控制、三周期共振买点）——❌ 未覆盖
8. **组合持仓与盈亏**（持仓、成本、市值、盈亏、行业配比）——✅ 已覆盖
9. **标签与筛选**（按行业/标签过滤）——❌ 标签只能写不能读
10. **实际股息收入**（历史到账记录、年度合计、单股贡献）——❌ 未覆盖
11. **通知/提醒规则**（价格、股息率阈值）——⚠️ 只读 ✅，个股规则写 ❌
12. **用户投资原则**（策略库）——✅ 已覆盖（系统提示词注入 + 工具读取）

## 4. 差距清单（按影响排序）

| # | 缺口 | 证据（App 已有能力 vs 工具现状） | 对 AI 分析的影响 | 已补齐 |
|---|---|---|---|---|
| 1 | **基本面数据** | `FundamentalsCacheRepository`/`FundamentalsBuilder` 已有近 5 期 ROE/负债率/营收净利同比/派息率/分红方案，并已用于个股 LLM 解读；但 `get_stock_info` 只返回行业 | 无法分析盈利质量、成长性、派息能力、负债风险——「分析股票」最核心的缺失 | ✅ `get_stock_fundamentals`（P0，含 forceRefresh + 派息率补全） |
| 2 | **K 线/BOLL 原始数据** | `KlineRepository.fetchCloses`、`StockRepository.fetchBoll` 已存在；但 `get_stock_evaluation` 只返回 action/bollTone/priceVsLower/reasons，无上/中/下轨数值与价格序列 | AI 只能复述程序结论，无法自主做趋势、回撤、位置论证；用户问「现在离下轨多远」无法回答 | ✅ `get_kline`（P0，收盘序列 + BOLL 上/中/下轨，period/bars 可选） |
| 3 | **组合级信号** | `PortfolioAdvisor` 纯函数已有仓位控制 + 三周期共振买点，并用于组合评估与组合 LLM 解读；无对应工具 | 无法回答「组合整体该不该加仓/减仓」「哪几只三周期共振」 | ✅ `get_portfolio_signals`（P1，与 App 评估同口径 + Semaphore(3) 限流） |
| 4 | **标签读取与筛选** | `StockRepository.observeAllTags/getTagsForStock` 与 `applyPortfolioFilter`（行业/标签筛选）已存在；`get_holdings` 不返回 tags，`set_stock_tags` 只能写 | 标签是 App 核心管理维度，AI 无法按标签问「红利仓有哪些」；写了标签后自己也读不回来 | ✅ `get_holdings` 每项返回 tags（P1） |
| 5 | **实际股息收入记录** | `DividendIncomeRepository` 已有按年/年度合计/单股收入/记录数；`get_portfolio_summary` 只有预测总额 | 无法分析实际到账趋势、年度对比、单股贡献（如「今年分红比去年多多少」） | ✅ `get_dividend_income`（P1，总览 + 按年明细） |
| 6 | **个股通知规则写入** | `NotificationRuleRepository.saveDividendYieldRule(stockCode, ...)` 已存在；`update_notification_rule` 只写全局评估门槛（min/boost yield），参数无 code | 规则读写不对称：能看到个股规则但不能设/改；「帮我给 X 设 5% 股息率提醒」无法执行 | ✅ `update_notification_rule` 增加可选 code/thresholdPercent/enabled（P2） |
| 7 | **买入线倍数/预测年限等个股参数写入** | `StockRepository.updateBuyThresholdMultiplier/updateYieldPeriod` 已存在；工具无对应入口，`get_dividend_forecast` 固定 3 年 | 个性化参数无法通过 AI 维护；预测年限 1/3/5 年只有 App 内 LLM 解读用 | ✅ `update_stock_settings`（P2） |
| 8 | **数据新鲜度信息** | `StockEntity.lastUpdated`、价格缓存时间戳存在；`get_holdings` 等工具不返回 | AI 无法判断数据是否过期（断网/缓存数据 vs 实时） | ✅ `get_holdings`/`get_stock_info` 返回 lastUpdated（P2） |

## 5. 设计边界（不算缺口）

- **备份导入/导出、OCR 截图导入**：设计文档明确排除（文件级高风险操作，AI 不碰）。合理。
- **外部新闻/资金流/宏观数据**：App 本身没有这些数据源，属产品边界，不是工具缺口。
- **成就系统**：非股票分析必需，可不暴露。
- **写操作确认门**：11 个写工具全部带确认门，与设计一致，利于安全但不影响分析能力。

## 6. 完成情况（2026-08-01 已全部落地）

P0/P1/P2 全部补齐，AI Tab 现为 **30 个工具（18 只读 + 12 写，写全部确认门）**，新增 5 个：

1. `get_stock_fundamentals`（P0）——基本面近 5 期 + 派息率补全 + forceRefresh。
2. `get_kline`（P0）——前复权收盘序列 + BOLL 上/中/下轨。
3. `get_portfolio_signals`（P1）——仓位控制 + 三周期共振，Semaphore(3) 限流，与 App 评估同口径。
4. `get_dividend_income`（P1）——实际到账总览/按年明细；`get_holdings` 补 tags + lastUpdated。
5. `update_notification_rule` 扩展个股规则 + `update_stock_settings`（P2）——个股参数写入闭环。

配套：系统提示词补充基本面/K线/组合信号/股息收入域；设计文档与 `AiAgentFactory` 注释统一为 30 个；
工具层单测全绿（`StockAgentToolsTest` 覆盖新增工具成功/失败/非法参数路径，`ConfirmationSummaryBuilderTest` 覆盖新写工具确认摘要）。

结论：**AI Tab 现已具备与 App 内「个股/组合 LLM 解读」同级的分析数据面，可视为「完整利于 AI 分析股票」。**
