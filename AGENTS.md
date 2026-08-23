# stock_inc — AI Agent 开发指南

> 本文件是仓库根的工作区指令文件（`AGENTS.md`，大写），面向在本仓库工作的 AI agent，只保留**长期有效的约定与红线**；历史变更过程细节见 git log 与 `docs/`。

---

## 1. 这是什么项目

**股息追踪（Stock Dividend Tracker）** —— 一个 A 股股息/红利投资追踪 Android App。
核心能力：自选股与持仓管理（支持 ETF/LOF 场内基金）、股息预测与日历、FIRE 财务自由进度、实时行情、持仓一键评估（多周期 BOLL + 股息率门槛 + LLM 解读）、网格交易计划（仅计划不下单）、行业/标签配比、持仓截图 OCR 导入、价格/股息率通知、数据备份恢复。

- 个人工具型应用，遵循 **YAGNI**：三行相似代码优于一个不必要的抽象层。
- **离线优先**：所有数据落 Room，网络只用于刷新，断网必须能看缓存。
- **数据准确性不可妥协**：东方财富原始数据不得换算（仅允许「每10股→每股」单位换算与展示格式化）。

---

## 2. 技术栈（以 `gradle/libs.versions.toml` 为准）

| 维度 | 选型 | 版本 |
|---|---|---|
| 语言 / JVM | Kotlin / Java 17 toolchain | 2.1.20 |
| 构建 | AGP + KSP + Gradle Kotlin DSL | AGP 8.7.3, KSP 2.1.20-1.0.32 |
| UI | Jetpack Compose + Material Design 3 | BOM 2024.12.01, M3 1.3.1 |
| DI | Hilt | 2.53.1 |
| 本地存储 | Room (SQLite) | 2.8.4，**当前 DB version = 31** |
| AI Agent | Google ADK Kotlin（AI Tab，OpenAI 兼容协议适配） | 0.6.0 |
| 网络 | Retrofit + OkHttp + Gson | 2.11.0 / 4.12.0 |
| 异步 | Coroutines + Flow | 1.9.0 |
| 导航 | Navigation Compose（单 Activity + 多 Composable） | 2.8.5 |
| 图表 | Vico（新图表）+ MPAndroidChart（历史股息率图）+ 纯 Canvas 自绘（K线蜡烛图） | 2.1.3 / 3.1.0 |
| 图片 | Coil3（含 SVG logo） | 3.1.0 |
| OCR | ML Kit 中文识别（Play Services 按需下载模型） | 16.0.1 |
| desugar | `desugar_jdk_libs`（`java.time` on minSdk 26） | 2.1.4 |

> ⚠️ **ADK 传递依赖坑**：ADK 会把 `kotlinx-coroutines` 等顶到 2.2/2.3，Kotlin 2.1 编译器无法读取 2.3 元数据导致编译失败。`app/build.gradle.kts` 已用 `resolutionStrategy.force` 把 `kotlin-stdlib` 全系锁到 **2.1.21** 兜底；升级 Kotlin 或 ADK 时务必同步检查这处强制对齐。

**SDK**：`applicationId = com.stock.dividend`，`namespace = com.stock.dividend`，`minSdk = 26`，`compileSdk = 36`，`targetSdk = 35`。

**测试栈**：JUnit4 + MockK + Google Truth + Turbine + `kotlinx-coroutines-test` + **Robolectric**（VM 测试用 `@RunWith(RobolectricTestRunner)` 跑真实 `Context`/`SharedPreferences`）。

---

## 3. 目录结构（包级地图）

```text
app/src/main/java/com/stock/dividend/
├── MainActivity.kt / StockDividendApp.kt  # 唯一 Activity / @HiltAndroidApp + WorkManager 初始化
│
├── data/
│   ├── plane/      # ⭐ MarketDataPlane 数据平面：股市数据获取唯一入口（§4.2A，必读）
│   │               #   + PlanePolicy（TTL 常量）/ DividendFreshnessStore（分红新鲜度记账）/ PlaneInFlight（并发去重）
│   ├── local/      # AppDatabase（version=26 + 全部 Migration，红线 #1）+ backup/（备份载体）+ dao/ + entity/
│   ├── remote/     # Retrofit 接口 + dto/（单位规则见 §4.9；FundDividendApi = ETF 分红专用源）
│   ├── repository/ # ① Repository（@Singleton，写操作与纯本地数据入口）
│   │               # ② 纯函数计算器/Parser（无 Android 依赖，§4.4）：
│   │               #   Boll / Forecast(TTM 口径) / BuyThreshold / Holding(摊薄) / RealizedPnl(FIFO) /
│   │               #   Drip / Grid 系列(Calculator·Anchor·Execution·Backtest) / DividendYieldGrid /
│   │               #   PortfolioRiskDiagnoser / MarketMood / HoldingRecommender / PortfolioAdvisor /
│   │               #   Llm*PromptBuilder·Parser / VisionImport* / FundDividendParser / Formatters
│   ├── agent/      # AI Agent（ADK）：AiAgentFactory（47 工具 = 34 读 + 13 写）+ tools/ +
│   │               #   OpenAI 协议适配（OpenAiCompatibleModel/SSE）+ ChatImages（多模态）+ AiChatRepository（流式会话）
│   ├── notification/ # 规则评估（纯函数）/ 网格到档提醒 / WorkManager 调度 / A 股交易时段守卫
│   ├── scan/       # OCR 截图导入 + BitmapLoader（视觉模型图片编解码）
│   └── widget/     # Glance 桌面小组件
│
├── di/             # Hilt Module：Network / Database / Plane / Notification / Ocr / AiSession
│
├── ui/
│   ├── navigation/ # AppNavigation（路由表 Routes + NavHost）
│   ├── theme/      # 双主题（跟随系统）/ Inter 可变字体 / Shape / ExtendedColors（LocalExtendedColors）
│   ├── component/  # AppComponents（新组件层，优先用）/ DesignSystem（历史兼容）/ 各图表与卡片组件
│   └── screen/     # 各页面 Composable；MainScaffold = 底部导航骨架（起始 Tab=today）
│
└── viewmodel/      # @HiltViewModel + 单 UiState（模式参考 PortfolioViewModel，§4.2）

app/src/test/java/com/stock/dividend/   # 单测，包结构与 main 对齐（§6）
docs/                                   # 设计文档 + audit/（数据一致性审计报告）
```

---

## 4. 架构与代码约定（必须遵守）

### 4.1 分层与依赖方向

`ui(viewmodel) → data/repository → data/local(dao) + data/remote(api)`
单向依赖，**UI 不直接碰 Dao/Api**，一律经 Repository。DI 用 Hilt，仅用于必须跨模块共享的依赖。

### 4.2 ViewModel 模式（参考 `PortfolioViewModel.kt`）

- `@HiltViewModel` + `@Inject constructor`；UI 状态用**单个 `data class XxxUiState`**（`@Stable`），经 `MutableStateFlow` + `asStateFlow()` 暴露。
- 多数据源用**多个独立 collector** 各订阅一个 Flow，避免大 `combine`；衍生 Flow 用 `.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 初始值)`；长链派生用 `flatMapLatest`。
- 用户操作方法命名 `onXxxChanged` / `confirmXxx` / `dismissXxx` / `clearXxx` / `refreshXxx`。

### 4.2A 数据平面（MarketDataPlane）——股市数据获取的唯一入口 ⭐

**任何消费方（ViewModel / Agent 工具 / 通知 Worker / Widget / 编排协调器）获取外部股市数据（行情/股息/K线/BOLL/基本面/财务三表/市场榜单/国债/研报/搜索），一律注入 `data/plane/MarketDataPlane`（@Singleton 门面），禁止直接注入行情类 Repository 或 Api。** 写操作与纯本地域数据（标签/行业目标/交易流水观察）仍走原 Repository/Dao。

统一语义（收敛 2026-08-18 前的多路径不一致）：

- **行情**：任何获取都写透 `price_cache`；`getQuoteSnapshots(stocks, force)` 批量 + 会话去重；`cachedPrices(codes)` 纯缓存读。
- **股息**：`getDps(code)` 自动 ensureDividendsFresh（dividends 表空/距上次成功拉取超 7 天 → 自动拉网；失败 5 分钟退避）；`refreshDividends(code)` 显式强刷。
- **股息率**：全 App 唯一口径 `getCurrentDividendYield(code)` = DPS ÷ 平面现价 × 100；历史曲线仍用 dividends 表除权时点快照。
- **BOLL**：`getBoll(code, period)` 单一路径，内置 Semaphore(3) 限流与 60s 内存缓存，各消费方不再自建。
- **基本面**：`getFundamentals(code, force)` 返回已补派息率产物（enrichPayoutRatio 收敛于平面内）。
- **市场**：指数/板块/榜单/资金流 60s 内存缓存。
- **扶摇独有能力（2026-08-23 全量接入，东财/腾讯无对应、禁用即不可用）**：估值快照 `getValuations`、交易日历 `getTradingDays`（通知节假日守卫/回测对齐）、龙虎榜 `getDragonTigerBoard`（⚠️ change/net_rate 小数分数 ×100）、涨跌停/炸板池与连板天梯 `getLimitUp/Down/BreakPool`+`getLimitUpLadder`（情绪温度计）、热股榜四件套 `getHotStockList/SkyrocketList/HotStockHistory/HotStockRankTrend`、异动原因 `getAnomalyList/Reasons`、集合竞价 `getAuctionSnapshot/ShortTermBenchmark`、同花顺指数目录与成分 `getThsIndexList/getIndexConstituents`、指数日K `getIndexDailyBars`、全量代码表 `getTickerList`；基金域 24 个端点经 `FundDataRepository`（资料/费率、重仓持仓+集中度、行业与资产配置、多周期收益与最大回撤、净值、持有人结构、经理/公司/诊断/资讯/募集/基金三表——`getFundXxx` 系列，fund_type 恒 exchange）。市场类走 60s `cachedMarket`；**原始 JSON 透传方法**（天梯/异动/竞价/经理/诊断等）返回 `JsonObject?`，字段无单位换算需求、面向 Agent/后续 UI 直接消费。**持久缓存（DB v28 `fuyao_cache` + `FuyaoCacheStore` 三语义，离线优先）**：历史不可变数据（交易日历/指数日K/基金持仓·行业·净值·持有人·报告期）合并式永续保留；慢变数据（目录/成分/资料/收益等）覆盖式+失败回退；按日不可变数据（龙虎榜/热股历史/涨跌停池）过去日期缓存优先零网络——断网或未配置 key 时历史数据依然可读，缓存管理页可一键清理。
- **本地观察透传**：`observeAllStocks/observeStock/observeDividends/observeAllDividends`。

旧代码里出现 `stockRepository.fetchQuotes/fetchBoll`、`fundamentalsCacheRepository.getFundamentals`、`dividendDao.getByStock` 等直连调用，一律视为待迁移痕迹。测试参考 `MarketDataPlaneTest`。

### 4.3 Repository 模式（参考 `StockRepository.kt`）

- `@Singleton class XxxRepository @Inject constructor(...)`。
- 网络/DB 失败**吞异常返回安全空值**（`emptyMap()/emptyList()/null`），绝不让异常冒泡到 UI 崩溃（红线 #2）；缓存写入失败同样静默跳过。数据获取失败可埋点 `ErrorLogRepository.record`（失败日志页可见）。
- 返回 `Result<T>` 的方法把异常包成 `Exception(e.toUserMessage(), e)`；事务用 `appDatabase.withTransaction { ... }`。

### 4.4 纯函数优先（重要项目特色）

复杂决策/计算逻辑抽成**无 Android 依赖**的 `object` 或顶层函数，放在 `data/repository/` 下，配单测。现有纯函数速查：

| 纯函数 | 职责 |
|---|---|
| `HoldingRecommender` | 单股评估：BOLL 位置 + 股息率门槛 → BUY/HOLD/SELL |
| `PortfolioAdvisor` | 组合层仓位控制 + 三周期共振买点 |
| `PortfolioRiskDiagnoser` | 组合风险诊断（集中度 HHI+CR / 股息可持续 / 估值水位利差）+ `grade()` 三维红绿灯 |
| `MarketMoodCalculator` | 市场情绪分组：板块列表本地排序取领涨/领跌 TopN |
| `BollCalculator` | 收盘价 → BOLL 带（MA20 ± 2σ） |
| `ForecastCalculator` | 历史分红 → 年均每股 + 预测收入。**口径：`rollingYearlyTotals` 按除权日的滚动 12 个月窗口（TTM），已排期未除权（exDate 已定 ≤365 天）计入，预案不计入；`latestYearlyCashPerShare`(TTM) 与 `calculateAvgCashPerShare`(N 年均) 共享锚点——全 App 股息率/预测收入同源，勿再造平行口径** |
| `BuyThresholdCalculator` | 10Y 国债 × 倍数 → 买入价 |
| `DripCalculator` | 分红再投资复利模拟（再投价为单值简化口径，UI 明示假设） |
| `HoldingCalculator` | 摊薄成本法持仓成本（已实现盈亏藏入成本） |
| `RealizedPnlCalculator` | FIFO 已实现盈亏（A 股法定口径，与摊薄法并存各表各的） |
| `DividendMetricsCalculator` | 分红深度（连续年数/CAGR/稳定性） |
| `GridCalculator` | 网格档位表：等差/等比/按股息率（YIELD，档位价=DPS÷股息率）三模式；资金默认 1/price 反比，可传 `levelWeights` 逐档自定义相对权重；`markTriggeredLevels` 按成交价 ± 半步长**时序重放**标记占用档；**「下一买」提示跳过在持档（计算属性，消费方需先 mark 再读）**；**波段模式（swingMode，DB v29）**：每档拆底仓+波段（`swingRatioPercent` 默认 30%，底仓只买不卖），波段卖出锚按**股息率**定义（卖出价 = DPS÷(买入息−步长pp)，默认回落一档），SELL 命中锚释放波段并计回合（roundTrips/swingProfit 计划口径×波段股数），buyFills/sellFills 供执行层净投入核算 |
| `GridAnchorCalculator` | 三周期 BOLL 智能锚定：买入起点=min(日/周下轨, 月中轨)、资金用完位=min(三周期下轨, 目标股息率底)、参考上界=月上轨 |
| `GridExecutionCalculator` | 网格资金执行跟踪（已投/剩余/加权均价/浮盈/执行偏差/逐档成交明细）；**波段模式净投入口径**（买入−卖出，卖出回款回流弹药库）+ 回合数/波段利润 |
| `GridBacktestCalculator` | 网格历史回测（250 日收盘回放，对照首日一次性买入）；**波段模式双向回合模拟**（跌破买/涨回配对价卖+重挂，T+1 守卫，可传费率假设，回合数/净落袋/费用） |
| `DividendYieldGridCalculator` | 股息率网格线（P=DPS÷股息率，0.5% 整档，最低保证最近档 ±1 档） |
| `MaDcaStrategyCalculator` | **年线定投策略**（首版交易策略，DB v30 `strategy_plans`）：收盘价序列 → 均线/偏离度/信号（低于均线=DCA_WINDOW 定投窗口；≥卖半阈值=SELL_HALF；≥清仓阈值=SELL_ALL，恰达阈值计为触发）；`sellSharesFor` 卖一半按整手向下取整、`dcaBuyShares` 定投金额折整手、`maSeries` 滚动均线（K 线叠加）、`validateParams` 参数校验。⚠️ 命名注意：`trade_strategies` 表是截图导入的策略笔记，交易策略配置表是 `strategy_plans` |
| `StrategyParams` | `strategy_plans.params` JSON 列编解码（DB v31）：7 种新策略类型（止盈/股息率带/双均线/偏离回归/价值平均/估值带/分红再投）统一参数存储，**新增策略类型不再加列**；Gson 绕过构造函数的缺字段/0 值一律回退各类型默认值（decode 兜底），`fromInputs` 编辑器输入校验（中文错误）、`defaultsFor/toInputs` 表单回填 |
| `StrategyCalculators`（7 个 object） | 七种策略计算器（统一输出 `StrategyEvaluation`：action/headline/metrics/sellShares/buyShares/notifyTier）：TakeProfit 摊薄成本涨幅分批止盈 / YieldBand 股息率带三线 / DualMa 快慢线多空+金叉死叉 / MaDeviation 均线偏离分档低吸+回归卖出 / ValueAveraging 目标市值缺口补足超额卖出 / ValuationBand PE/PB 绝对阈值带（扶摇估值快照，无历史百分位）/ DividendReinvest 除权到账金额折股再投提示。共同约定：恰达阈值计触发、整手折算、买入只展示不推送 |
| `StrategyEvaluator` | 策略调度器（纯函数）：`strategyType` 分发到各计算器（MA_DCA 适配既有 MaDcaStrategyCalculator），统一动作枚举 `StrategyAction`（BUY/HOLD/SELL_HALF/SELL_ALL）+ `requiredCloses` 声明日线需求 + `displayName`；配套 `StrategyInputAssembler`（@Singleton）按类型采集输入（日线/DPS/估值/除权/持仓成本），策略页/今日页/通知协调器三处共用 |
| `LlmPromptBuilder` 系列 / `*Parser` | 评估数据 → LLM prompt / LLM 响应 → 结构化（含容错 JSON 提取） |
| `mergeByReportDate` | 不可变历史按报告期合并：远端覆盖同期、缓存独有旧期永续保留（`repairRemote` 支持字段级保底） |
| `applyPortfolioFilter` | 行业/标签筛选 |

**新增决策逻辑时，优先做成这类纯函数，并配单测。**

### 4.5 UI / Compose 约定

> 完整设计系统见 [`DESIGN.md`](DESIGN.md)。动 UI 前必读。

- **组件优先级**：新代码优先用 `ui/component/AppComponents.kt`（`AppCard`/`AmountText`/`PercentText`/`AppButton`/`FinanceMetricRow`/`AppTextField`）；`DesignSystem.kt` 为历史兼容层。
- **⚠️ `AppCard` 不带内边距**：M3 `Card` 默认不 pad 内容，调用方必须在内容外层自加 `padding(16.dp)`（列表行可 `horizontal=16.dp`）——漏了就是内容贴边。
- 财务正负色走 `LocalExtendedColors.current.positive/negative`，禁止新代码 import 裸 `FinanceGreen/FinanceRed`。
- 金额/百分比一律用 `MoneyFormatter`/`PercentFormatter`，禁止私有 `formatXxx`；等宽数字用 `tabularNumberStyle` 或 `AmountText`/`PercentText`。
- 圆角走 `MaterialTheme.shapes`（6/10/14/20/28dp），禁止硬编码 `RoundedCornerShape(N.dp)`。
- 双主题跟随系统深浅色；Inter 可变字体（已子集化，含 tnum）。
- 所有面向用户文本**必须中文**；空状态用 `EmptyStateView`；汇总置顶、列表 Card、刷新下拉、新增 FAB。

### 4.6 数据库（Room）纪律 —— 关键

- **DB version = 31**（22 张表 / `MIGRATION_1_2` … `MIGRATION_30_31`），`exportSchema = false`。
- 改 schema 必须三件事同步：① `AppDatabase` 的 entities/version；② 新增 `MIGRATION_N_(N+1)` 并在 `DatabaseModule` 注册；③ version +1。历史迁移全部手写 `ALTER`/`CREATE`。
- 表名/列名下划线，实体字段驼峰，Room 注解映射。
- **备份恢复注意**：恢复旧版本备份时 Gson 会给缺失字段填 null，可能撞 Room NOT NULL 约束使整个事务失败——`BackupData.normalizeXxx` 按 `dbVersion` 分支修补（先例：normalizeGridPlans）。

### 4.7 网络约定

- 所有 Retrofit client 在 `di/NetworkModule.kt` 统一装配，共享 OkHttpClient（自动注入反爬头）；多数据源用 `@Qualifier` 区分；LLM 走独立 client（180s 读超时）+ `@Url` 动态 base；**同花顺扶摇走 `@FuyaoClient` 独立 client**（`X-api-key` 拦截器每次请求动态读 `FuyaoConfig`，保存即生效；不复用共享 client——其拦截器会给未知域名加东财 Referer）。
- 数据源（**分层：同花顺扶摇为权威主源，东财/腾讯候补**，2026-08-23 起）：
  - **同花顺扶摇 `fuyao.aicubes.cn`**：A 股/ETF·LOF/指数行情快照、搜索、股票分红事件流、ETF·LOF 结构化分红、股票日K（周/月线 `KlineAggregator` 本地聚合）、财务三表、财务指标。**基金 K 线恒腾讯**（扶摇基金日K未复权，语义不符）。key 在设置 → 数据 → 数据源页运行时填写，未配置则扶摇源整体禁用、全走候补，构建不依赖 key。
  - **并行补齐范式**（行情/三表/摘要三域通用）：扶摇与东财**并发**发起不增加时延——扶摇成功时为权威值，东财仅按 `supplementedFrom` 字段级回填扶摇缺失的字段（行情的市值/换手率/量比/PE/PB，三表的存货/应付/固定资产/财务费用/扣非/期末现金，摘要的分红方案/公告股息率）；扶摇失败时在飞的东财结果直接作为降级结果；两源皆败才落 ErrorLog（source="行情"/"同花顺"可区分）。
  - 东方财富（搜索/行情/财务候补 + 分红排期补充 + 板块·榜单·资金流·龙虎榜·研报·公告·国债主源）、腾讯 `web.ifzq.gtimg.cn`（K线/股票分红候补）、基金 f10 页（ETF 分红候补）、OpenAI 兼容 LLM。

### 4.8 通知 / 后台任务

- `data/notification/`：规则评估纯函数 + 编排 Coordinator + WorkManager Worker + 调度器；评估门槛复用 `notification_rules` 表。
- 网格到档提醒：`GridNotifyEvaluator`（迟滞边沿触发，每档只提醒一次）+ 每小时 Worker，前置 `AshareTradingTime` 交易时段守卫。
- 策略卖出阈值提醒：`StrategyNotifyEvaluator`（HALF/ALL **有序升级**边沿触发：升级才提醒、同档/降级静默、脱离卖出区清空复位）复用同一每小时 Worker（`checkStrategies`，经 `StrategyInputAssembler`+`StrategyEvaluator` 统一评估全部策略类型）；买入方向按产品约定**只展示不推送**（今日页/策略页信号）。

### 4.9 外部数据接口单位与解析纪律 —— 关键（数据准确性）

> 接入任何行情/财务/资金流等外部数据前必读。核心原则：**单位换算只允许「每10股→每股」与展示格式化；其余裸值→真实值转换必须在 DTO/解析层显式处理，并配真实 JSON fixture 单测锁定。单位搞错 = 数据全错，比崩溃更隐蔽。**

#### 4.9.1 东方财富 push2 三接口单位规则互不相同（最大易错点）

| 接口 | `fltt` | 单位规则 |
|---|---|---|
| `ulist.np/get`（批量行情） | 无 | **价格/百分比 ×100 整数需 ÷100；场内基金（ETF/LOF）价格类 ×1000 需 ÷1000**；成交量(手)/成交额(元)/市值(元) 原值不除 |
| `clist/get`（列表/资金流） | `fltt=2` | **全部字段真实值，不除** |
| `stock/get`（单股/指数详情） | 无 | 同 `ulist`（÷100，基金价格 ÷1000） |

⚠️ `clist` 与 `ulist`/`stock/get` 的价格规则**相反**，两个解析函数必须独立、切勿混用 ÷100 逻辑。基金价格除数统一封装在 `divPriceScaleOrNull(isFund)`（`QuoteSnapshot.kt`），基金判定用 `FundDividendParser.isExchangeTradedFundCode`（沪 5、深 15/16 开头）。搜索接口口径：场内基金 `Classify="Fund"` 放行（与 A 股同 MktNum 规则），场外 `Classify="OTCFUND"` 排除。

#### 4.9.2 字段编号必须查官方文档，禁止凭直觉推断

资金流字段：净额 f62 主力=f66 超大单+f72 大单（每 +6：66/72/78/84）；净占比 = 净额编号 +3（**f69/f75/f81/f87**，不是 f174/f175）。`f133` = 股息率 %（clist 真实值）。全市场 A 股 fs 串 = `m:0+t:6,m:0+t:80,m:1+t:2,m:1+t:23`。⚠️ clist 只支持单字段排序、不支持服务端条件过滤——筛选只能拉前 N（200）条客户端过滤，口径要在 UI/工具 note 如实标注。拿不准先查东财页面字段或 `WebSearch` 核实。

#### 4.9.3 「字段在但不完整」≠「数据缺失」，要换接口而非重试

`stock/get` 对资金流字段返回不完整，个股资金流必须用 `clist`。⚠️ 实测 clist `fs=m:{market}+t:2+s:{code}` **单股筛选不生效**（返回全市场列表）——必须请求 f12 并按 code 精确匹配，否则拿到的是榜首的数据（张冠李戴）。

#### 4.9.4 腾讯接口作为交叉验证金标准

腾讯行情返回直接是真实值，接入东货行情前用 `qt.gtimg.cn` 同时刻值交叉验证 ÷100 规则。`web.ifzq.gtimg.cn/`（fqkline）：前复权 K 线 + 分红明细，**单次上限约 640 交易日**——覆盖更长区间必须分块请求（分红拉取候补路径用三块各 2 年，不赌超窗截断）。腾讯分红只覆盖股票：**ETF/LOF 分红候补走 `FundDividendApi`（基金 f10 分红送配页 HTML）+ `FundDividendParser`**（仅「每10份→每份」÷10 合规换算）。

#### 4.9.5 同花顺扶摇单位与接口纪律（权威主源，实测 2026-08-23）

- **全部真实值**：价格元 / 百分比 % 原值 / 金额元，无任何 ÷100/÷1000 规则（已与腾讯同刻交叉验证）。唯一换算：行情快照成交量**股→手 ÷100**（`toQuoteSnapshotFromFuyao`，对齐 App `QuoteSnapshot.volume` 语义）、基金分红**每10份 ÷10**（合规项）。
- **统一信封**：业务错误也返回 HTTP 200，`FuyaoEnvelope.code != 0` 即失败（1002 参数/标的错、3001 不存在、5003 数据未就绪、4001 频率超限）；解析后 `check(envelope.isOk)` 抛异常 → 调用方降级候补源。
- ⚠️ **整批毒代码**：ETF 代码传入 A 股批量接口会让**整批**报 1002——股票/基金必须拆分请求（`StockRepository.fetchSnapshotsFromFuyao`）。
- ⚠️ **报告期取 `period_end_ms`**：`report_date_ms` 是公告日（同季度再公告会同值），作报告期会撞期（`FuyaoStatementsBuilder`）。
- 时间戳毫秒 Unix、时区 Asia/Shanghai（`fuyaoMsToDateStringOrNull`）；代码格式 `600519.SH`（`toFuyaoThscodeOrNull` / `fuyaoThscodeToAppCodeOrNull`）；搜索中文必须 URL 编码、同 thscode 有重复行需去重、只支持 SH/SZ。
- **只有日线**（interval=1d，窗口 ≤10 年）：周/月线由 `KlineAggregator` 从日线本地聚合（同时保证三周期前复权基准同源）；基金日K恒未复权，**基金 K 线保持腾讯**。
- 股票分红事件流**只有已除权事件**（无预案/排期状态、无报告期）——报告期由东财 enrich 按除权日对齐回填，「已排期未除权」仍走东财通道（`DividendRepository.enrichAndMergeFromEastMoney`）。
- K 线换源纪律：`kline_cache_meta.source` 记录缓存来源（DB v27），与当前主源不一致时**必须全量重建**（两家前复权基准不同，增量混用会产生价格跳变）；扶摇故障有 10 分钟冷却（防换源判定热循环）。

#### 4.9.6 解析层实践（强制）

1. **每个新 DTO 配真实 JSON fixture 单测**，断言每个字段的解析值与单位。
2. ÷100 / takeIfFinite 等转换封装为 private 扩展集中一处；可空字段用 `Double?.takeIfFinite()` 写法。
3. 金额一律「元」绝对值，缺失字段 null，**绝不臆造**；占比单位 %。
4. 报告期日期归一化：东财 datacenter 的 `REPORT_DATE` 带空格时间后缀，跨表对齐前 `substringBefore(" ")`。
5. **Gson 脏值容错**：clist 对退市/停牌股全字段返回 `"-"`，默认 Gson 抛异常且毒死整批列表——`LenientDoubleDeserializer` 已注册到全部东财/腾讯 Retrofit（"-"→null），新 Retrofit 必须同样注册。

---

## 5. 命令

```bash
./gradlew build                    # CI 跑的就是这条
./gradlew assembleDebug            # 仅 Debug APK
./gradlew test                     # 全部单测
./gradlew :app:testDebugUnitTest --tests "com.stock.dividend.viewmodel.PortfolioViewModelTest"  # 单个测试类

# Release 需签名环境变量（KEYSTORE_FILE/KEYSTORE_PASSWORD/KEY_ALIAS/KEY_PASSWORD），缺失则只构建不签名
# 发版：打 v* 标签触发 .github/workflows/release.yml
```

CI 用 JDK 17 temurin，显式 `USE_CHINA_MIRROR=false` 直连官方仓库；本地网络差可设 `USE_CHINA_MIRROR=true` 走阿里云镜像。

---

## 6. 测试约定

- **纯函数/计算器/Parser**：纯 JUnit4 + Truth，无 Android 依赖。
- **ViewModel**：Robolectric + MockK + `Dispatchers.setMain(StandardTestDispatcher())` + `runTest { advanceUntilIdle() }`。
- **Flow** 用 Turbine `test { }`；测试包结构与 `main` 对齐。
- **改了纯函数或新增决策逻辑，必须补/改对应单测**（git log 大量 `test(scope):` 提交是一贯做法）。

---

## 7. Git 规范

- **Conventional Commits**：`<type>(<scope>): <中文简述>`；type 常用 `feat/fix/test/docs/refactor/ci/chore`；scope 用模块缩写（`llm` `vm` `ui` `stock` `kline` `grid` `ci` 等）。
- **不要主动 commit/push**，除非用户明确要求；在 `master` 上改动前先开分支。
- 主分支 `master`；CI 对 `master` push 触发。

---

## 8. 红线与易错点（高频踩坑）

1. **改 schema 必加 Migration 并 bump version** —— 漏了会 `Room cannot verify the data integrity`。
2. **网络/DB 异常必须吞**返回空集合/null，别让 UI 崩；静默失败要埋点 `ErrorLogRepository.record` 可感知。
3. **`isLoading` 状态显式复位**：请求前置 true，结束（含失败）置 false，否则刷新按钮永久禁用。
4. **自选股（shares=0）也要能拉现价/行业**：价格刷新订阅全量 `allStocksFlow` 而非 `holdingsFlow`，别图省事切回。
5. **并发限流**：批量拉 BOLL 用 `Semaphore(3)`（平面已内置），腾讯接口会拒高频。
6. **纯函数不带 Android 依赖**，否则进不了纯 JVM 单测。
7. **不对东财原始数据做换算**（除「每10股→每股」与展示格式化）。
8. **Release 签名信息是环境变量**，别硬编码进 gradle 或提交进仓库。
9. **依赖版本只改 `libs.versions.toml`**，别在 `build.gradle.kts` 写裸版本号。
10. **取股市数据必须走 `MarketDataPlane`（§4.2A）**：消费方禁止直接注入/调用行情类 Repository 取数方法或行情类 Dao；否则缓存写透/分红自动刷新/BOLL 限流等统一语义全部失效。
11. **外部数据接入必读 §4.9**：三接口单位规则互不相同、资金流字段编号要查文档、新 DTO 必须配实测 fixture 单测并用腾讯 qt 交叉验证。
12. **中文界面与中文注释**：所有用户可见文本中文；注释统一中文。

---

## 9. 常用入口文件速查

| 想做什么 | 先看 |
|---|---|
| 理解整体架构 | `MainActivity.kt` → `AppNavigation.kt` → `MainScaffold.kt` |
| 取股市数据（任何场景） | `data/plane/MarketDataPlane.kt`（§4.2A，唯一入口） |
| 持仓/评估主流程 | `PortfolioViewModel.kt` + `HoldingRecommender.kt` / `PortfolioAdvisor.kt` |
| 加一张数据表 | `AppDatabase.kt`（Migration，红线 #1）+ `dao/` + `entity/` + `DatabaseModule.kt` |
| 加一个网络接口 | `remote/*Api.kt` + `dto/` + `NetworkModule.kt`；**先读 §4.9** |
| 加一个页面 | `ui/screen/XxxScreen.kt` + `viewmodel/XxxViewModel.kt` + 注册 `AppNavigation.kt` |
| 复用 UI 样式 | `DESIGN.md` + `AppComponents.kt` + `ui/theme/` |
| 通知/后台 | `data/notification/` + `StockDividendApp.kt` |
