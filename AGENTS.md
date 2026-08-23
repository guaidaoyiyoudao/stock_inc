# stock_inc — AI Agent 开发指南

> ZCode 读取的**工作区指令文件**就是本文件：仓库根目录的 `AGENTS.md`（大写）。
> 用户级默认指令在 `~/.zcode/AGENTS.md`，会先于本文件加载；本文件可针对本项目收窄或覆盖它。

本文件面向在本仓库工作的 AI agent（ZCode / Claude / Codex 等），描述项目全貌、约定与红线。

---

## 1. 这是什么项目

**股息追踪（Stock Dividend Tracker）** —— 一个 A 股股息/红利投资追踪 Android App。
核心能力：自选股与持仓管理、股息预测与日历、FIRE 财务自由进度、实时行情、持仓一键评估（多周期 BOLL + 股息率门槛 + LLM 解读）、行业/标签配比与筛选、持仓截图 OCR 导入、价格/股息率通知、数据备份恢复。

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
| 本地存储 | Room (SQLite) | 2.8.4，**当前 DB version = 28** |
| AI Agent | Google ADK Kotlin（AI Tab，OpenAI 兼容协议适配） | 0.6.0 |
| 网络 | Retrofit + OkHttp + Gson | 2.11.0 / 4.12.0 |
| 异步 | Coroutines + Flow | 1.9.0 |
| 导航 | Navigation Compose（单 Activity + 多 Composable） | 2.8.5 |
| 图表 | Vico（新图表）+ MPAndroidChart（历史股息率图） | 2.1.3 / 3.1.0 |
| 图片 | Coil3（含 SVG logo） | 3.1.0 |
| OCR | ML Kit 中文识别（Play Services 按需下载模型，保 APK 小） | 16.0.1 |
| desugar | `desugar_jdk_libs`（`java.time` on minSdk 24） | 2.1.4 |

> ⚠️ **ADK 传递依赖坑**：ADK 会把 `kotlinx-coroutines` 等顶到 2.2/2.3，Kotlin 2.1 编译器无法读取 2.3 元数据导致编译失败。`app/build.gradle.kts` 已用 `resolutionStrategy.force` 把 `kotlin-stdlib` 全系锁到 **2.1.21** 兜底；升级 Kotlin 或 ADK 时务必同步检查这处强制对齐（见 `build.gradle.kts:136-143`）。

**SDK**：`applicationId = com.stock.dividend`，`namespace = com.stock.dividend`，`minSdk = 24`，`compileSdk = 36`，`targetSdk = 35`。

**测试栈**：JUnit4 + MockK + Google Truth + Turbine + `kotlinx-coroutines-test` + **Robolectric**（VM 测试用 `@RunWith(RobolectricTestRunner)` 跑真实 `Context`/`SharedPreferences`，`app/build.gradle.kts` 已开 `isIncludeAndroidResources = true`）。

---

## 3. 目录结构

> 下列为真实文件清单（含 2026-08-02 新增的 AI 工具/财务三表/资金流等），每个文件标注作用。改代码前先定位对应文件。

```text
stock_inc/
├── AGENTS.md                     # 本文件（AI agent 开发指南，必读）
├── DESIGN.md                     # 设计系统文档（双主题/Inter 字体/组件/格式化器）
├── README.md                     # 项目说明
├── CLAUDE.md                     # Claude 专属提示（可空）
├── build.gradle.kts / settings.gradle.kts   # 根构建配置
├── gradle/libs.versions.toml     # Version Catalog：依赖版本唯一来源（红线 #9）
├── docs/superpowers/             # 设计文档（specs/ + plans/，superpowers 工作流产出）
├── .github/workflows/            # android.yml（CI 构建）+ release.yml（打 v* 标签发版）
└── app/
    ├── build.gradle.kts          # 应用模块配置（SDK、签名、依赖；applicationId=com.stock.dividend）
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml
        │   ├── java/com/stock/dividend/
        │   │   ├── MainActivity.kt              # 唯一 Activity，承载所有 Compose 页面
        │   │   ├── StockDividendApp.kt          # @HiltAndroidApp，WorkManager 初始化
        │   │   │
        │   │   ├── data/
        │   │   │   ├── local/
        │   │   │   │   ├── AppDatabase.kt       # Room DB（version=26）+ 全部 Migration（红线 #1）
        │   │   │   │   ├── backup/BackupData.kt # 备份/恢复的数据载体（JSON 序列化；除 Room 表外，2026-08-03 起额外覆盖 LLM 与 AI 助手的 SharedPreferences 配置）
        │   │   │   │   ├── dao/                 # Room DAO 接口（@Dao）
        │   │   │   │   │   ├── StockDao.kt                  # 自选股（含 observeAll/observeByCode/getByCode）
        │   │   │   │   │   ├── DividendDao.kt               # 历史分红记录
        │   │   │   │   │   ├── DividendIncomeRecordDao.kt   # 实际股息到账记录（按年统计）
        │   │   │   │   │   ├── TransactionDao.kt            # 买卖交易记录
        │   │   │   │   │   ├── StockTagDao.kt               # 股票标签（多对多）
        │   │   │   │   │   ├── IndustryTargetDao.kt         # 行业目标配比
        │   │   │   │   │   ├── PriceCacheDao.kt             # 实时价格缓存（冷启动兜底）
        │   │   │   │   │   ├── SearchCacheDao.kt            # 搜索结果缓存
        │   │   │   │   │   ├── FundamentalsCacheDao.kt      # 基本面缓存（7 天 TTL）
        │   │   │   │   │   ├── FinancialStatementsCacheDao.kt  # 财务三表缓存（7 天 TTL，2026-08-02 新增）
        │   │   │   │   │   ├── NotificationRuleDao.kt       # 通知/评估规则
        │   │   │   │   │   ├── TradeStrategyDao.kt          # 截图策略分析产出的全局策略
        │   │   │   │   │   ├── LlmAnalysisCacheDao.kt       # LLM 解读结果缓存（24h TTL）
        │   │   │   │   │   ├── FireGoalDao.kt               # FIRE 财务自由目标
        │   │   │   │   │   ├── LivingExpenseItemDao.kt      # 生活支出项
        │   │   │   │   │   ├── AchievementDao.kt            # 成就解锁记录
        │   │   │   │   │   ├── GridPlanDao.kt               # 网格交易计划（2026-08-04 新增）
        │   │   │   │   │   ├── KlineCacheDao.kt             # K线缓存（getBars/upsertBars/replaceBars/trimToRecent/meta，2026-08-17 新增）
        │   │   │   │   │   └── ErrorLogDao.kt               # 失败日志（observeAll/latest/count/clearAll/trimToRecent，2026-08-20 新增）
        │   │   │   │   └── entity/             # @Entity（表名下划线、字段驼峰，Room 注解映射）
        │   │   │   │       ├── StockEntity.kt              # stocks：含 shares/costPerShare/industry/buyThresholdMultiplier
        │   │   │   │       ├── DividendEntity.kt          # dividends：报告期/每股分红/股息率/除权日
        │   │   │   │       ├── DividendIncomeRecordEntity.kt
        │   │   │   │       ├── TransactionEntity.kt
        │   │   │   │       ├── StockTagEntity.kt
        │   │   │   │       ├── IndustryTargetEntity.kt
        │   │   │   │       ├── PriceCacheEntity.kt
        │   │   │   │       ├── SearchCacheEntity.kt
        │   │   │   │       ├── FundamentalsCacheEntity.kt       # payload=Fundamentals JSON
        │   │   │   │       ├── FinancialStatementsCacheEntity.kt  # payload=FinancialStatements JSON（2026-08-02 新增）
        │   │   │   │       ├── NotificationRuleEntity.kt
        │   │   │   │       ├── TradeStrategyEntity.kt
        │   │   │   │       ├── LlmAnalysisCacheEntity.kt
        │   │   │   │       ├── FireGoalEntity.kt
        │   │   │   │       ├── LivingExpenseItemEntity.kt      # 含 EXPENSE_PERIOD_MONTHLY 常量
        │   │   │   │       ├── AchievementEntity.kt
        │   │   │   │       ├── GridPlanEntity.kt               # 网格交易计划（仅计划/提示，不下单；含 levelWeights 自定义档位资金比例 JSON 列 + GridLevelWeights 编解码，2026-08-19 扩展）
        │   │   │   │       └── KlineCacheEntity.kt             # kline_cache + kline_cache_meta（K线永久缓存 + 除权漂移检测状态，2026-08-17 新增）
        │   │   │   │       └── ErrorLogEntity.kt               # error_logs 失败日志（时间/分类/来源/摘要/异常详情，2026-08-20 新增）
        │   │   │   │
        │   │   │   ├── remote/                  # Retrofit 接口（DI 在 NetworkModule 装配，见 §4.7/§4.9）
        │   │   │   │   ├── SearchApi.kt         # 东财 searchapi（搜索）
        │   │   │   │   ├── QuoteApi.kt          # 东财 push2 ulist/stock/get（行情，÷100 规则见 §4.9）
        │   │   │   │   ├── MarketApi.kt         # 东财 push2 clist/stock/get（板块/个股/资金流/指数，2026-08-02 新增）
        │   │   │   │   ├── FundamentalApi.kt    # 东财 datacenter（基本面/财务三表/资产负债表/龙虎榜）
        │   │   │   │   ├── DividendApi.kt       # 东财 datacenter（分红明细，回退源）
        │   │   │   │   ├── TencentDividendApi.kt # 腾讯 ifzq（K线/分红，主源，见 §4.9.4）
        │   │   │   │   ├── FundDividendApi.kt    # 东财基金 f10 分红送配页（场内 ETF/LOF 分红唯一源，HTML 原文，2026-08-22 新增）
        │   │   │   │   ├── BondYieldApi.kt      # 东财 datacenter（国债收益率，含多期限/LPR）
        │   │   │   │   ├── ResearchApi.kt       # 东财 reportapi（研报）+ np-anotice（公告）（2026-08-02 新增）
        │   │   │   │   ├── LlmApi.kt            # OpenAI 兼容 LLM（@Url 动态 base，60s 超时）
        │   │   │   │   └── dto/                 # 网络 DTO（@SerializedName 映射裸字段，单位见 §4.9）
        │   │   │   │       ├── StockSearchResponse.kt
        │   │   │   │       ├── QuoteResponse.kt           # ulist 行情（×100 整数，÷100）
        │   │   │   │       ├── StockInfoResponse.kt       # 个股详情（行业等）
        │   │   │   │       ├── MarketClistResponse.kt     # clist 列表 + IndexQuoteResponse（真实值不除，2026-08-02 新增）
        │   │   │   │       ├── FundamentalResponse.kt     # 主要财务指标 RPT_LICO_FN_CPD
        │   │   │   │       ├── BalanceSheetResponse.kt    # 资产负债表（仅负债率，补全用）
        │   │   │   │       ├── FinancialStatementResponse.kt  # 三表全量（利润/现金流/资产负债全字段，2026-08-02 新增）
        │   │   │   │       ├── DividendResponse.kt
        │   │   │   │       ├── TencentKlineResponse.kt
        │   │   │   │       ├── BondYieldResponse.kt       # 国债多期限 + LPR + 中美利差（2026-08-02 扩展）
        │   │   │   │       ├── DragonTigerResponse.kt     # 龙虎榜（2026-08-02 新增）
        │   │   │   │       ├── ResearchReportResponse.kt  # 研报（2026-08-02 新增）
        │   │   │   │       ├── StockAnnouncementResponse.kt  # 公告（2026-08-02 新增）
        │   │   │   │       ├── LlmChatRequest.kt / LlmChatResponse.kt  # LLM 请求/响应
        │   │   │   │
        │   │   │   ├── plane/                     # ⭐ 数据平面（MarketDataPlane 门面：所有股市数据读取唯一入口，见 §4.2A）
        │   │   │   │   ├── MarketDataPlane.kt        # @Singleton 门面：行情/股息/BOLL/基本面/市场/国债/搜索 + 会话缓存/并发去重
        │   │   │   │   ├── PlanePolicy.kt            # TTL/新鲜度策略常量（行情 10s、BOLL/市场 60s、分红 7 天、退避 5 分钟）
        │   │   │   │   ├── DividendFreshnessStore.kt # 分红 per-stock 最后成功/尝试时间戳（SharedPreferences，接口 + prefs 实现）
        │   │   │   │   └── PlaneInFlight.kt           # InFlightMap：同 key 并发请求合并（Mutex + lazy Deferred）
        │   │   │   ├── repository/              # Repository（@Singleton）+ 纯函数计算器（无 Android 依赖）
        │   │   │   │   ├── StockRepository.kt              # 自选股核心：resolveStock/fetchQuotes/fetchQuoteSnapshots/fetchBoll/fetchFundamentals
        │   │   │   │   ├── DividendRepository.kt           # 分红拉取（腾讯主+东财回退）+ 历史保留式写入（窗口外历史永续累积，2026-08-17 改造）
        │   │   │   │   ├── DividendIncomeRepository.kt     # 实际股息到账统计
        │   │   │   │   ├── TransactionRepository.kt        # 交易记录
        │   │   │   │   ├── TradeStrategyRepository.kt      # 全局策略库（含 risksFromJson）
        │   │   │   │   ├── NotificationRuleRepository.kt   # 通知/评估规则
        │   │   │   │   ├── KlineRepository.kt              # 腾讯 K线 + 永久缓存编排（尾部每日一次增量补尾/除权漂移全量重建/断网回退，2026-08-17 改造）
        │   │   │   │   ├── BondYieldRepository.kt          # 国债（fetch10YBondYield + fetchAllYields 多期限，2026-08-02 扩展）
        │   │   │   │   ├── FundamentalsCacheRepository.kt  # 基本面 7 天缓存编排（刷新按报告期 merge，历史期次不丢，2026-08-17 改造）
        │   │   │   │   ├── FinancialStatementsRepository.kt  # 财务三表 7 天缓存编排（同上 merge 语义，2026-08-02 新增）
        │   │   │   │   ├── MarketDataRepository.kt         # 资金流/板块/行业内个股/指数/龙虎榜/情绪（2026-08-02 新增）
        │   │   │   │   ├── ResearchRepository.kt           # 研报 + 公告（2026-08-02 新增）
        │   │   │   │   ├── FireGoalRepository.kt
        │   │   │   │   ├── LivingExpenseRepository.kt
        │   │   │   │   ├── AchievementRepository.kt
        │   │   │   │   ├── BackupRepository.kt             # 备份/恢复（事务式批量）
│   │   │   │   ├── CacheManagementRepository.kt    # 缓存管理：7 类持久缓存条目统计 + 按种类清理（CacheKind 含 permanent 永久缓存标记与中文说明，2026-08-19 新增）
│   │   │   │   ├── ErrorLogRepository.kt           # 失败日志记录门面：60s 同源同摘要防抖/保留最近 200 条/自身失败吞掉；数据获取失败埋点统一入口（2026-08-20 新增）
        │   │   │   │   ├── WidgetDataRepository.kt         # 桌面小组件数据
        │   │   │   │   ├── ScreenshotStrategyRepository.kt # 截图策略持久化
        │   │   │   │   ├── VisionImportRepository.kt      # 视觉导入编排（图片→GLM-4.6V→结构化行，自动重试 5 次，2026-08-16 新增）
        │   │   │   │   ├── GridPlanRepository.kt   # 网格交易计划 CRUD（2026-08-04 新增）
        │   │   │   │   ├── LlmConfigRepository.kt          # LLM 配置（provider/key/url）+ 视觉模型配置（vision_api_key/vision_model，全局智谱 key 回退）
        │   │   │   │   ├── LlmAnalysisRepository.kt        # 组合级 LLM 解读编排
        │   │   │   │   ├── LlmAnalysisCacheStore.kt       # LLM 解读缓存读写
        │   │   │   │   ├── EvaluatedStock.kt              # 持仓评估聚合数据结构
        │   │   │   │   ├── UserStrategyRef.kt             # 用户策略引用
        │   │   │   │   ├── PortfolioLlmInput.kt           # 组合 LLM 输入装配
        │   │   │   │   ├── StockLlmInput.kt               # 个股 LLM 输入装配
        │   │   │   │   ├── ScreenshotStrategy.kt          # 截图策略数据结构
        │   │   │   │   ├── LlmConfig.kt / LlmProviderPresets.kt  # LLM 配置数据类
        │   │   │   │   ├── AiAgentConfig.kt / AiAgentConfigRepository.kt  # AI 助手设置（系统提示词/温度/输出长度，SharedPreferences 存储，2026-08-02 新增）
        │   │   │   │   ├── LlmAnalysis.kt / LlmCacheKey.kt
        │   │   │   │   ├── StockLlmAnalysis.kt
        │   │   │   │   ├── JsonExtraction.kt              # LLM 响应 JSON 提取（容错）
        │   │   │   │   ├── TodaySignalAggregator.kt       # 今日信号聚合纯函数（买入触发/网格下一档/分红倒计时）
        │   │   │   │   ├── TodayBriefingCoordinator.kt    # 今日 AI 简报编排（拉数据→信号→prompt→LLM→按日缓存）
        │   │   │   │   ├── TodayBriefingPromptBuilder.kt / TodayBriefingParser.kt  # 简报 prompt 构造/解析（纯函数）
        │   │   │   │   ├── PortfolioDiagnosisAssembler.kt # 组合诊断共享装配器（今日页体检卡与 diagnose_portfolio 工具同源，2026-08-15 新增）
        │   │   │   │   │
        │   │   │   │   ├── 纯函数计算器（决策/计算逻辑，配单测，见 §4.4）：
        │   │   │   │   ├── BollCalculator.kt              # 收盘价 → BOLL 带（MA20 ± 2σ）
        │   │   │   │   ├── ForecastCalculator.kt          # 历史分红 → 年均每股 + 预测收入
        │   │   │   │   ├── BuyThresholdCalculator.kt     # 10Y 国债 × 倍数 → 买入价
        │   │   │   │   ├── DripCalculator.kt     # 分红再投资（DRIP）复利模拟（按年再投，可配置再投价，2026-08-04 新增）
        │   │   │   │   ├── DividendMetricsCalculator.kt  # 分红深度（连续年数/CAGR/稳定性，2026-08-02 新增）
        │   │   │   │   ├── HoldingCalculator.kt          # 摊薄成本法持仓成本（已实现盈亏藏入成本）
        │   │   │   │   ├── RealizedPnlCalculator.kt      # FIFO 已实现盈亏（独立于摊薄成本，A 股法定口径，2026-08-04 新增）
        │   │   │   │   ├── DripCalculator.kt     # 分红再投资（DRIP）复利模拟（按年再投，可配置再投价，2026-08-04 新增）
        │   │   │   │   ├── GridCalculator.kt     # 网格交易档位表（等差网格 + 当前价下一档提示，2026-08-04 新增）
        │   │   │   │   ├── GridAnchorCalculator.kt       # 网格智能锚定（BOLL中轨=基准/上轨=上界/目标股息率=下界，2026-08-04 新增）
        │   │   │   │   ├── GridExecutionCalculator.kt    # 网格资金执行跟踪（已投入/剩余/已买股数/加权均价/浮盈/执行偏差/逐档成交明细/弹药库汇总）
        │   │   │   │   ├── GridBacktestCalculator.kt     # 网格历史回测（日收盘价回放，对照首日一次性买入，2026-08-16 新增）
        │   │   │   │   ├── DividendYieldGridCalculator.kt # 股息率网格线（价=DPS÷股息率，0.5% 整档取区间内，2026-08-19 新增）
        │   │   │   │   ├── MarketMoodCalculator.kt      # 市场情绪分组：板块列表 → 领涨/领跌 TopN 两端（2026-08-15 新增）
        │   │   │   │   ├── HoldingRecommender.kt         # 单股评估：BOLL+股息率门槛 → BUY/HOLD/SELL
        │   │   │   │   ├── PortfolioAdvisor.kt           # 组合层仓位控制 + 三周期共振买点
        │   │   │   │   ├── Fundamentals.kt               # 基本面数据类 + Builder/enrichPayoutRatio/趋势
        │   │   │   │   ├── FinancialStatements.kt        # 三表数据类 + Builder（2026-08-02 新增）
        │   │   │   │   ├── QuoteSnapshot.kt              # 行情快照数据类 + toQuoteSnapshot（÷100）
        │   │   │   │   ├── LlmPromptBuilder.kt           # 组合评估 LLM prompt
        │   │   │   │   ├── StockLlmPromptBuilder.kt      # 个股评估 LLM prompt
        │   │   │   │   ├── ScreenshotStrategyPromptBuilder.kt  # 截图策略 LLM prompt
        │   │   │   │   ├── LlmAnalysisParser.kt          # LLM 响应 → 结构化（组合）
        │   │   │   │   ├── StockLlmAnalysisParser.kt     # LLM 响应 → 结构化（个股）
        │   │   │   │   ├── ScreenshotStrategyParser.kt   # 截图策略 JSON 解析
        │   │   │   │   ├── VisionImportParser.kt        # 视觉模型响应 → 持仓/交易行（日期/方向归一化，2026-08-16 新增）
        │   │   │   │   ├── VisionImportPromptBuilder.kt # 视觉解析 prompt（持仓/历史成交两种 schema + 同花顺列口径，2026-08-16 新增）
        │   │   │   │   ├── HistoryCacheMerge.kt         # mergeByReportDate：不可变历史按报告期合并（远端覆盖同期/缓存独有旧期保留，2026-08-17 新增）
        │   │   │   │   ├── FundDividendParser.kt        # 场内基金识别（沪5/深15·16）+ 基金 f10 分红 HTML 解析纯函数（2026-08-22 新增）
        │   │   │   │   └── Formatters.kt                 # MoneyFormatter/PercentFormatter（纯函数 + 单测）
        │   │   │   │
        │   │   │   ├── agent/                  # AI Agent（Google ADK，AI Tab）
        │   │   │   │   ├── AiAgentFactory.kt           # 工具注册中心（47 工具：34 读 + 13 写，装配 LlmAgent）
        │   │   │   │   ├── AgentInstructionBuilder.kt  # 系统提示词（注入策略库）
        │   │   │   │   ├── AiChatRepository.kt         # AI 会话编排（流式 SSE；带图发送/历史图片回读/多模态探测，2026-08-22 扩展）
        │   │   │   │   ├── AiTitleGenerator.kt         # 会话标题生成
        │   │   │   │   ├── ChatImages.kt              # 聊天图片编解码（data URL↔ADK Part）+ MultimodalModelDetector 模型名探测（2026-08-22 新增）
        │   │   │   │   ├── ConfirmationSummaryBuilder.kt # 写操作确认摘要
        │   │   │   │   ├── OpenAiCompatibleModel.kt    # ADK Model 适配（OpenAI 兼容协议）
        │   │   │   │   ├── OpenAiProtocol.kt / OpenAiDtos.kt / OpenAiSse.kt  # 协议/DTO/SSE 解析（DTO 含多模态 content 数组，2026-08-22 扩展）
        │   │   │   │   ├── ToolDisplayName.kt          # 工具调用展示名（英文→中文映射，2026-08-03 新增）
        │   │   │   │   └── tools/                      # Agent 工具（ReadTool/WriteTool 基类 + 47 个工具）
        │   │   │   │       ├── ToolBases.kt            # ReadTool（只读）/ WriteTool（带确认门）抽象基类
        │   │   │   │       ├── ToolArgs.kt             # 参数解析扩展（stringArg/intArg/...）+ refreshPrice/toEntity 共用
        │   │   │   │       ├── MarketDataTools.kt      # 9 个行情工具（get_stock_info/get_kline/get_stock_fundamentals 等）
        │   │   │   │       ├── CapitalFlowTools.kt     # 4 个工具：资金流/估值指标/龙虎榜/市场情绪（2026-08-02 新增）
        │   │   │   │       ├── FinancialStatementTools.kt  # get_financial_statements（2026-08-02 新增）
        │   │   │   │       ├── DividendMetricsTools.kt # get_dividend_metrics（2026-08-02 新增）
        │   │   │   │       ├── IndustryComparisonTools.kt  # 行业列表/行业内对比（2026-08-02 新增）
        │   │   │   │       ├── ResearchTools.kt        # 研报/公告（2026-08-02 新增）
        │   │   │   │       ├── MarketBreadthTools.kt   # 指数/ETF/国债收益率（2026-08-02 新增）
        │   │   │   │       ├── PortfolioDataTools.kt   # 8 个组合工具（get_holdings/get_portfolio_signals 等）
        │   │   │   │       ├── PortfolioAnalysisTools.kt # 3 个分析工具：get_market_ranking 全市场榜单/compare_stocks 多股对比/diagnose_portfolio 组合诊断（2026-08-15 新增）
        │   │   │   │       ├── GridTools.kt             # get_grid_plans 网格计划查询（参数/下一档/执行进度，2026-08-15 新增）
        │   │   │   │       ├── StockActionTools.kt    # 8 个写工具（add_stock/update_holding 等，带确认门）
        │   │   │   │       ├── StrategyActionTools.kt  # add_trade_strategy（全局策略库写工具，带确认门，2026-08-02 新增）
        │   │   │   │       └── FinanceActionTools.kt   # 5 个 FIRE/支出工具（1 读 + 4 写）
        │   │   │   │
        │   │   │   ├── notification/            # 通知/后台任务（WorkManager）
        │   │   │   │   ├── NotificationRuleEvaluator.kt    # 规则匹配纯函数
        │   │   │   │   ├── NotificationCheckCoordinator.kt # 编排（拉行情→评估→发通知）
        │   │   │   │   ├── NotificationCheckWorker.kt      # WorkManager Worker（每日规则检查）
        │   │   │   │   ├── GridNotificationWorker.kt     # 网格到档检查 Worker（每小时，2026-08-15 新增）
        │   │   │   │   ├── GridNotifyEvaluator.kt        # 网格到档提醒评估纯函数（迟滞边沿触发，2026-08-15 新增）
        │   │   │   │   ├── AshareTradingTime.kt          # A 股交易时段守卫纯函数（周一至五 9:15–15:15，2026-08-16 新增）
        │   │   │   │   ├── NotificationScheduler.kt        # 调度（周期/约束）
        │   │   │   │   ├── DividendAlertNotifier.kt        # 通知发送抽象
        │   │   │   │   ├── AndroidDividendAlertNotifier.kt # Android NotificationManager 实现
        │   │   │   │   ├── NotificationChannels.kt         # 通知渠道
        │   │   │   │   └── VivoPermissionIntents.kt        # vivo 后台保活权限引导
        │   │   │   ├── scan/                     # OCR 截图导入
        │   │   │   │   ├── HoldingScreenshotParser.kt  # 截图 → 持仓结构化
        │   │   │   │   ├── TextRecognitionService.kt   # ML Kit 中文识别
        │   │   │   │   └── BitmapLoader.kt            # 图片加载 + bitmapToJpegDataUrl（JPEG base64，视觉模型入参）
        │   │   │   └── widget/                    # 桌面小组件（Glance）
        │   │   │       ├── MarketWidget.kt / MarketWidgetReceiver.kt  # 小组件 UI 与入口
        │   │   │       ├── WidgetActionCallback.kt / WidgetEntryPoint.kt
        │   │   │       └── WidgetUiState.kt
        │   │   │
        │   │   ├── di/                          # Hilt Module
        │   │   │   ├── PlaneModule.kt           # 数据平面绑定（DividendFreshnessStore → prefs 实现，2026-08-18 新增）
        │   │   │   ├── NetworkModule.kt         # Retrofit/OkHttp 装配（@Qualifier + 反爬头，见 §4.7/§4.9）
        │   │   │   ├── DatabaseModule.kt        # Room DB + DAO + Migration 注册
        │   │   │   ├── NotificationModule.kt    # 通知相关绑定
        │   │   │   ├── OcrModule.kt             # ML Kit OCR 绑定
        │   │   │   └── AiSessionModule.kt       # AI 会话作用域绑定
        │   │   │
        │   │   ├── ui/
        │   │   │   ├── navigation/AppNavigation.kt   # 路由表（Routes object）+ NavHost
        │   │   │   ├── theme/                     # 双主题（StockDividendTheme，跟随系统深浅色）
        │   │   │   │   ├── Color.kt / Gradient.kt  # 颜色 + ExtendedColors（财务正负色 LocalExtendedColors）
        │   │   │   │   ├── Shape.kt              # 圆角（6/10/14/20/28dp，禁止硬编码）
        │   │   │   │   ├── Type.kt               # Inter 可变字体 + tnum 等宽数字
        │   │   │   │   └── Theme.kt              # M3 主题组装
        │   │   │   ├── component/                # 可复用 Composable（详见 DESIGN.md）
        │   │   │   │   ├── AppComponents.kt      # 新组件层：AppCard/AmountText/PercentText/AppButton（新代码优先用）
        │   │   │   │   ├── DesignSystem.kt       # 历史组件层：AppCardDefaults/SectionHeader/FinanceMetric（兼容）
        │   │   │   │   ├── DesignSystemPreview.kt
        │   │   │   │   ├── StockCard.kt / DividendSummaryCard.kt / IncomeSummaryCard.kt  # 摘要卡片
        │   │   │   │   ├── IncomeBreakdownChart.kt / IncomeTimelineCard.kt / IncomeTrendChart.kt  # 收入图表
        │   │   │   │   ├── DividendRateChart.kt          # 股息率历史图（MPAndroidChart）
        │   │   │   │   ├── KlineYieldChart.kt            # 30日K线蜡烛 + 股息率网格水平线（纯 Canvas 自绘 + 底部成交量条，2026-08-19 新增，替代 PriceVolumeChart）
        │   │   │   │   ├── BollPriceScale.kt / DividendPriceScale.kt    # BOLL/股息率刻度尺
        │   │   │   │   ├── IndustryAllocationPieChart.kt # 行业配比饼图
        │   │   │   │   ├── FireProgressCard.kt / ForecastComparisonCard.kt
        │   │   │   │   ├── CompanyIcon.kt / CompanyLogoMap.kt  # 公司 logo（Coil3 SVG）
        │   │   │   │   ├── EmptyStateView.kt / CompactTopAppBar.kt / AchievementCard.kt / YearSelector.kt
        │   │   │   └── screen/                   # 各页面 Composable（单 Activity + 多 Composable）
        │   │   │       ├── MainScaffold.kt       # 底部导航骨架（Tab 切换，起始 Tab=today）
        │   │   │       ├── TodayScreen.kt       # 今日首页（起始 Tab）：AI 简报/组合表现/市场环境/组合体检/股息现金流/信号（金融分析师晨报分区）
        │   │   │       ├── HomeScreen.kt         # 股息收入页（收入+日历二级 Tab）+ AchievementScreen 成就页
        │   │   │       ├── PortfolioScreen.kt    # 持仓主页（自选/持仓列表 + 下拉刷新 + FAB）
        │   │   │       ├── StockDetailScreen.kt  # 个股详情（行情/股息/BOLL/评估/AI 解读）
        │   │   │       ├── AiChatScreen.kt       # AI Tab（对话式 Agent）
        │   │   │       ├── AddStockScreen.kt / EditHoldingScreen.kt  # 加股/改持仓
        │   │   │       ├── DividendCalendarScreen.kt  # 股息日历
        │   │   │       ├── DripSimulationScreen.kt    # 分红再投（DRIP）复利模拟（2026-08-04 新增）
        │   │   │       ├── PortfolioEvaluationScreen.kt  # 持仓一键评估
        │   │   │       ├── ExpenseCoverageScreen.kt    # 支出覆盖率
        │   │   │       ├── ScreenshotImportScreen.kt / PortfolioImportScreen.kt  # 截图/批量导入
        │   │   │       ├── TransactionHistoryScreen.kt  # 全局交易流水 + 复盘备注（2026-08-04 新增；顶栏相机入口截图导入）
        │   │   │       ├── TransactionImportScreen.kt  # 交易记录截图导入（AI 视觉解析→行级核对→批量入库，2026-08-16 新增）
        │   │   │       ├── GridPlanScreen.kt    # 网格交易计划（档位表 + 下一档提示，仅计划不下单，2026-08-04 新增）
        │   │   │       ├── TradeStrategyListScreen.kt  # 策略库
        │   │   │       ├── SettingsScreen.kt   # 设置入口（纯入口列表，按「提醒评估/AI策略/数据」归类，2026-08-02 重构）
        │   │   │       ├── AlertEvalSettingsScreen.kt / LlmStrategySettingsScreen.kt / DataSettingsScreen.kt  # 设置 3 个二级详情页（2026-08-02 重构）
        │   │   │       ├── AiSettingsScreen.kt   # AI 助手设置（系统提示词/温度/输出长度，会话旁入口，2026-08-02 新增）
        │   │   │       ├── NotificationSettingsScreen.kt / StockNotificationSettingsScreen.kt / NotificationReliabilityScreen.kt
        │   │   │       ├── CacheManagementScreen.kt   # 缓存管理（7 类缓存条目数 + 永久/短期标记 + 单类/全部清理确认，2026-08-19 新增）
        │   │   │       ├── ErrorLogScreen.kt         # 失败日志页（列表/展开堆栈/全部清理，2026-08-20 新增）
│   │   │       ├── BackupRestoreScreen.kt / FireGoalSetupScreen.kt / OcrDebugScreen.kt
        │   │   │       └── TabRefreshLocal.kt    # 本地刷新辅助
        │   │   │
        │   │   └── viewmodel/                    # @HiltViewModel + UiState（参考 PortfolioViewModel，见 §4.2）
        │   │       ├── TodayViewModel.kt      # 今日首页 VM（A 持仓/B 刷新→价格+市场+体检并行/C 简报/D 股息现金流 四 collector）
        │   │       ├── PortfolioViewModel.kt     # 持仓主 VM（多 collector + 派生 Flow）
        │   │       ├── StockDetailViewModel.kt   # 个股详情 VM
        │   │       ├── AiChatViewModel.kt        # AI 会话 VM
        │   │       ├── AiSettingsViewModel.kt   # AI 助手设置 VM（系统提示词/温度/输出长度，2026-08-02 新增）
│   │       ├── CacheManagementViewModel.kt  # 缓存管理 VM（条目加载/确认清理/联动清平面内存缓存，2026-08-19 新增）
        │       ├── ErrorLogViewModel.kt      # 失败日志 VM（observeAll 响应式/分类中文映射/展开/清理确认，2026-08-20 新增）
        │   │       ├── AddStockViewModel.kt / EditHoldingViewModel.kt
        │   │       ├── DividendCalendarViewModel.kt
        │   │       ├── DripSimulationViewModel.kt       # 分红再投模拟（参数可调，纯函数重算，2026-08-04 新增）
        │   │       ├── DividendIncomeViewModel.kt
        │   │       ├── ExpenseCoverageViewModel.kt + ExpenseCoverageCalculator.kt  # 支出覆盖率 VM + 纯函数
        │   │       ├── PortfolioImportViewModel.kt / ScreenshotImportViewModel.kt
        │   │       ├── TradeStrategyListViewModel.kt
        │   │       ├── NotificationSettingsViewModel.kt / StockNotificationSettingsViewModel.kt
        │   │       ├── TransactionHistoryViewModel.kt  # 全局交易流水 + 复盘备注（2026-08-04 新增）
        │   │       ├── TransactionImportViewModel.kt  # 交易记录截图导入 VM（2026-08-16 新增）
        │   │       ├── GridPlanViewModel.kt   # 网格计划列表 + 生成器（参数实时预览，2026-08-04 新增）
        │   │       ├── FireGoalViewModel.kt / BackupViewModel.kt / OcrDebugViewModel.kt
        │   │       ├── AchievementViewModel.kt + AchievementChecker.kt + AchievementDef.kt + AchievementCategory.kt  # 成就
        │   │       └── MarkdownRenderGuard.kt    # Markdown 渲染安全（防注入）
        │   └── res/                              # 资源（字体 inter.ttf 子集化、图标、字符串等）
        │
        └── test/java/com/stock/dividend/         # 单元测试（包结构与 main 对齐，见 §6）
            ├── data/agent/        # 10 个：AgentInstructionBuilderTest / AiChatRepositoryTest / StockAgentToolsTest（46 工具）/ PortfolioAnalysisToolsTest（3 新工具）/ ToolDisplayNameTest 等
            ├── data/repository/   # 58 个：纯函数（BollCalculatorTest/BuyThresholdCalculatorTest/CacheManagementRepositoryTest/PortfolioRiskDiagnoserTest）
            │                       #    + DTO 解析（QuoteSnapshotTest/MarketDtoParseTest/FinancialStatementDtoParseTest/BondYieldResponseParseTest）
            │                       #    + Repository（StockRepositoryTest/DividendRepositoryTest/Robolectric）
            └── viewmodel/         # 24 个：PortfolioViewModelTest / CacheManagementViewModelTest 等（Robolectric + MockK + Turbine）
```

**文件规模速览**（2026-08-20）：main 源集约 285 个 .kt，测试约 115 个 .kt；DB 20 张表/25 个 Migration（version=26）；AI Agent 47 个工具（34 读 + 13 写）。

---

## 4. 架构与代码约定（必须遵守）

### 4.1 分层与依赖方向

`ui(viewmodel) → data/repository → data/local(dao) + data/remote(api)`
单向依赖，**UI 不直接碰 Dao/Api**，一律经 Repository。DI 用 Hilt，但「仅用于必须跨模块共享的依赖」（宪法原则 IV）。

### 4.2 ViewModel 模式（参考 `PortfolioViewModel.kt`）

- `@HiltViewModel` + `@Inject constructor`，构造注入 Repository/Dao。
- UI 状态用 **单个 `data class XxxUiState`**（标注 `@Stable`），通过 `MutableStateFlow` + `asStateFlow()` 暴露 `StateFlow`。
- 多数据源用**多个独立 collector**（`viewModelScope.launch { ... .collect }`）各自订阅一个 Flow，避免一个大 `combine` 难维护；每个 collector 只更新自己负责的字段。
- 衍生 Flow 用 `.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 初始值)`。
- 长链派生用 `flatMapLatest`（标注 `@OptIn(ExperimentalCoroutinesApi::class)`）。
- 用户操作方法命名 `onXxxChanged` / `confirmXxx` / `dismissXxx` / `clearXxx` / `refreshXxx`。

### 4.2A 数据平面（MarketDataPlane）——股市数据获取的唯一入口 ⭐ 2026-08-18 起

**任何消费方（ViewModel / Agent 工具 / 通知 Worker / Widget / 编排协调器）获取外部股市数据（行情/股息/K线/BOLL/基本面/财务三表/市场榜单/国债/研报/搜索），一律注入 `data/plane/MarketDataPlane`（@Singleton 门面），禁止直接注入行情类 Repository 或 Api。** 写操作（加股/改持仓/交易/网格计划 CRUD）与纯本地域数据（标签/行业目标/交易流水观察）仍走原 Repository/Dao。

```
消费方（VM / Agent 工具 / Worker / Widget / 协调器）
        ↓ 唯一取数入口
MarketDataPlane（data/plane/）
        ├─ 内存会话缓存 + InFlightMap 并发去重（行情 10s 窗口 / BOLL·市场 60s TTL）
        ├─ 持久缓存：price_cache（写透）/ kline_cache / fundamentals_cache / dividends / search_cache
        └─ 网络源：StockRepository / DividendRepository / KlineRepository / …（收编为内部协作对象）
```

**统一语义（收敛 2026-08-18 前的多路径不一致）**：
- 行情：任何获取都**写透 price_cache**（主 UI 刷新后 Widget/通知冷启动兜底价一致）；`getQuoteSnapshots(stocks, force)` 批量 + 会话去重；`cachedPrices(codes)` 纯缓存读。
- 股息：`getDps(code)` **自动 ensureDividendsFresh**（dividends 表空/距上次成功拉取超 7 天 → 自动发网刷新；失败 5 分钟退避，SharedPreferences 记账，见 `DividendFreshnessStore`）——修复「网格页拿不到股息率」的根因（此前只读本地、无人触发刷新）。`refreshDividends(code)` 为显式强刷（详情页手动刷新）。
- 当前股息率：**全 App 唯一口径** `getCurrentDividendYield(code)` = DPS ÷ 平面现价 × 100；历史股息率曲线仍用 dividends 表除权时点快照。
- BOLL：`getBoll(code, period)` 单一路径（K线仓 + BollCalculator），内置 **Semaphore(3) 限流**与 60s 内存缓存（各消费方不再自建限流/缓存）。
- 基本面：`getFundamentals(code, force)` 返回**已补派息率**的产物（enrichPayoutRatio 收敛于平面内）；分红更新后可用 `enrichFundamentals(f, code)` 幂等重算（不重读缓存）。
- 市场：指数/板块/榜单/资金流 60s 内存缓存（今日页 + 简报 + 工具同会话共享一次请求）。
- 本地观察透传：`observeAllStocks/observeStock/observeDividends/observeAllDividends`（只读页面免于同时注入两个入口）。
- **扶摇独有能力（2026-08-23 全量接入，东财/腾讯无对应、禁用即不可用）**：估值/交易日历/龙虎榜（⚠️ change/net_rate 小数分数 ×100）/涨跌停·炸板池·连板天梯/热股榜四件套/异动/集合竞价/同花顺指数目录·成分·指数日K/代码表 + 基金域 24 端点（`FundDataRepository`）。市场类走 60s `cachedMarket`；原始 JSON 透传方法返回 `JsonObject?`。**持久缓存（DB v28 `fuyao_cache` + `FuyaoCacheStore` 三语义：合并式/覆盖式/按日缓存优先）**——历史不可变数据离线可读。

**平面文件**：`MarketDataPlane.kt`（门面）+ `PlanePolicy.kt`（TTL 常量）+ `DividendFreshnessStore.kt`（分红新鲜度 prefs 记账）+ `PlaneInFlight.kt`（同 key 并发请求合并）；DI 绑定 `di/PlaneModule.kt`。测试参考 `MarketDataPlaneTest`（16 用例：写透/去重/新鲜度/退避/口径）。

**给 Agent 的迁移口径**：新增消费方取数 → 注入 plane 并用上述方法；旧代码里出现 `stockRepository.fetchQuotes/fetchQuoteSnapshots/fetchBoll`、`dividendRepository.observeDividends().first()`、`fundamentalsCacheRepository.getFundamentals`、`dividendDao.getByStock` 等直连调用，一律视为待迁移痕迹（StockRepository/KlineRepository 等的这些方法保留仅为平面的网络源实现）。

### 4.3 Repository 模式（参考 `StockRepository.kt`）

- `@Singleton class XxxRepository @Inject constructor(...)`。
- 网络/DB 失败**吞异常返回安全空值**（`emptyMap()` / `emptyList()` / `null`），**绝不让异常冒泡到 UI 崩溃**（宪法原则 V）。缓存写入失败同样静默跳过。
- 返回 `Result<T>` 的方法（如 `searchStocks`/`addStock`）把异常包成 `Exception(e.toUserMessage(), e)`。
- 需要事务的地方用 `appDatabase.withTransaction { ... }`。

### 4.4 纯函数优先（重要项目特色）

复杂决策/计算逻辑**抽成无 Android 依赖的 `object` 或顶层函数**，放在 `data/repository/` 下，便于单测且可在多复用点共享：

| 文件 | 职责 |
|---|---|
| `HoldingRecommender` | 单股评估：BOLL 位置 tone + 股息率门槛 → BUY/HOLD/SELL |
| `PortfolioAdvisor` | 组合层仓位控制 + 三周期共振买点 |
| `PortfolioRiskDiagnoser` | 组合风险全景诊断：①集中度（行业/个股 HHI+CR、股息来源前 3）②股息可持续性（连续分红<3 年权重、派息率>100% 名单）③估值水位（加权股息率 vs 10Y 国债利差）+ 规则化建议；`grade()` 输出三维度 OK/WARN/BAD 红绿灯（阈值与建议规则同源，2026-08-15 新增 grade） |
| `MarketMoodCalculator` | 市场情绪分组：clist 板块列表按涨跌幅本地排序取两端 TopN（领涨/领跌，口径同 get_market_sentiment 工具，2026-08-15 新增） |
| `BollCalculator` | 收盘价 → BOLL 带（MA20 ± 2σ） |
| `ForecastCalculator` | 历史分红 → 年均每股 + 预测收入 |
| `BuyThresholdCalculator` | 10Y 国债 × 倍数 → 买入价 |
| `DripCalculator` | 分红再投资（DRIP）复利模拟：按年把分红以可配置再投价买入，对比「再投」与「现金分红」两条路径 |
| `HoldingCalculator` | 摊薄成本法持仓成本（卖出盈亏藏入成本，不独立展示） |
| `RealizedPnlCalculator` | FIFO 已实现盈亏（A 股法定口径，独立于摊薄成本法） |
| `DividendMetricsCalculator` | 分红深度（连续年数/CAGR/稳定性，2026-08-02 新增） |
| `GridCalculator` | 网格（纯买入）档位表：等差/等比分档或**按股息率分档**（档位价=DPS÷股息率，YIELD）、资金默认 1/price 反比分配、**可传 levelWeights 逐档自定义资金比例**（相对权重归一化，2026-08-19 新增）、**无卖出档**、「下一档买」提示 |
| `GridAnchorCalculator` | 网格智能锚定：买入起点=min(日/周 BOLL 下轨, 月 BOLL 中轨)、资金用完位=min(三周期下轨最低, 目标股息率底)、参考上界=月 BOLL 上轨 |
| `GridExecutionCalculator` | 网格资金执行跟踪：已投入金额/剩余可投/已买股数/加权均价/浮盈浮盈率/进度（与 markTriggeredLevels 同口径命中，2026-08-05 新增） |
| `LlmPromptBuilder` | 评估数据 → LLM prompt（纯函数 + 降级兜底） |
| `LlmAnalysisParser` | LLM 响应 → 结构化结果 |
| `mergeByReportDate`（顶层函数） | 不可变历史按报告期合并：远端覆盖同期、缓存独有旧期永续保留（财报/基本面缓存共用，2026-08-17 新增） |
| `applyPortfolioFilter`（顶层函数） | 行业/标签筛选 |

**新增决策逻辑时，优先做成这类纯函数，并配单测。**

### 4.5 UI / Compose 约定

> **完整设计系统文档见 [`DESIGN.md`](DESIGN.md)**（双主题/Inter 字体/核心组件/格式化器/迁移指南）。动 UI 前必读。

- 设计系统双层：
  - **新组件层**（`ui/component/AppComponents.kt`）：`AppCard`（三态）/ `AmountText`（金融专用，tnum+正负色）/ `PercentText` / `AppButton` / `FinanceMetricRow` —— **新代码优先用这些**。
  - **历史组件层**（`ui/component/DesignSystem.kt`）：`AppCardDefaults` / `SectionHeader` / `FinanceMetric` / `StatusPill`，保留兼容。
- **财务正负色走 `LocalExtendedColors.current.positive/negative`**（`ui/theme/Gradient.kt`），跟随深浅色；旧的裸 `FinanceGreen`/`FinanceRed` 常量仅历史代码兼容，**新代码不要 import**。
- **金额/百分比一律用 `MoneyFormatter` / `PercentFormatter`**（`data/repository/Formatters.kt`，纯函数 + 单测），禁止再写私有 `formatXxx`。
- **等宽数字**：金额/百分比展示加 `tabularNumberStyle`（`ui/theme/Type.kt`），或直接用 `AmountText`/`PercentText`（已内置）。
- **圆角走 `MaterialTheme.shapes`**（`Shape.kt`：6/10/14/20/28dp），禁止硬编码 `RoundedCornerShape(N.dp)`。
- 双主题：`StockDividendTheme` 跟随系统深浅色（亮色温润近白，暗色带蓝调近黑）。
- 字体：Inter 可变字体（`res/font/inter.ttf`，已子集化 latin+tnum）。
- 所有面向用户的文本**必须中文**（宪法 Design Standards）。
- 空状态用 `EmptyStateView`，汇总数据置顶，列表用 Card 区分，刷新用下拉刷新，新增入口用 FAB。

### 4.6 数据库（Room）纪律 —— 关键

- **DB version 当前 = 28**，`exportSchema = false`（21 张表 / 27 个迁移步 `MIGRATION_1_2` … `MIGRATION_27_28`）。
- 改 schema（加表/加列/改类型）**必须**：① 在 `AppDatabase` 的 `entities`/`version` 同步；② 新增 `MIGRATION_N_(N+1)` 并在 `DatabaseModule` 注册；③ `version` +1。
- 历史迁移全部手写 `ALTER`/`CREATE`，保持这个风格。
- 表名/列名用下划线（`dividend_income_records`、`stockCode`），实体字段用驼峰，靠 Room 注解映射。

### 4.7 网络约定

- 所有 Retrofit client 在 `di/NetworkModule.kt` 统一装配，**共享 OkHttpClient**（自动注入 `Referer`/`User-Agent` 反爬头）。
- 多数据源用 `@Qualifier` 区分（已有 `EastMoneyDividendApi`、`TencentDividendSource`、`LlmClient`）。LLM 走独立 client（60s 超时）且 `@Url` 动态传 base。
- 数据源（**分层：同花顺扶摇为权威主源，东财/腾讯候补**，2026-08-23 起）：
  - **同花顺扶摇 `fuyao.aicubes.cn`**（行情快照/指数/搜索/股票+ETF·LOF 分红/股票日K/财务三表/财务指标）：`X-api-key` 认证（`FuyaoConfig` SharedPreferences，设置 → 数据 → 数据源页填写，未配置则整体禁用走候补）；**东财并行补齐缺失字段**，扶摇失败整体降级；**股票日K主源（周/月线 `KlineAggregator` 本地聚合），基金 K 线恒腾讯**（扶摇基金日K未复权）。
  - 东方财富（搜索/行情/财务候补 + 分红排期补充 + 板块·榜单·资金流·龙虎榜·研报·公告）、腾讯 `web.ifzq.gtimg.cn`（K线/股票分红候补）、10Y 国债、OpenAI 兼容 LLM。

### 4.8 通知 / 后台任务

- `data/notification/`：`NotificationRuleEvaluator`（规则匹配纯函数）、`NotificationCheckCoordinator`（编排）、`NotificationCheckWorker`（WorkManager）、`NotificationScheduler`。
- 评估门槛（min/boost 股息率）**复用 `notification_rules` 表存储**，避免加表（见迁移 9→10、10→11 历史）。

### 4.9 外部数据接口单位与解析纪律 —— 关键（数据准确性）

> 接入任何行情/财务/资金流等外部数据时必读。本节由 2026-08-02 实践教训沉淀，每条均经实测交叉验证。

**核心原则：单位换算只允许「每10股→每股」与展示格式化（宪法原则 III）；其余裸值→真实值的转换必须在 DTO/解析层显式处理，并配真实 JSON fixture 单测锁定。**

#### 4.9.1 东方财富 push2 三接口的单位规则**互不相同**（最大易错点）

| 接口 | URL | `fltt` 参数 | 单位规则 | 代码位置 |
|---|---|---|---|---|
| `ulist.np/get`（批量行情） | `push2.eastmoney.com/api/qt/ulist.np/get` | 无此参数 | **价格/百分比 ×100 整数，需 ÷100；场内基金（ETF/LOF）价格类 ×1000 需 ÷1000**；成交量(手)/成交额(元)/市值(元) 原值不除 | `QuoteApi` + `toQuoteSnapshot`（`QuoteSnapshot.kt`） |
| `clist/get`（板块/个股/资金流列表） | `push2.eastmoney.com/api/qt/clist/get` | `fltt=2` 时 | **全部字段真实值，不 ÷100**（价格带小数、百分比直接是 %、净额是元） | `MarketApi.getClist` + `toMarketList`（`MarketDataRepository.kt`） |
| `stock/get`（单股/指数详情） | `push2.eastmoney.com/api/qt/stock/get` | 无此参数 | **价格/百分比 ×100 整数，需 ÷100；场内基金（ETF/LOF）价格类 ×1000 需 ÷1000**；成交量(手)/成交额(元) 原值不除 | `QuoteApi.getStockInfo` / `MarketApi.getIndexQuote` + `toIndexQuote` |

⚠️ **`clist` 与 `ulist`/`stock/get` 的价格单位规则相反**——`ulist` 的 `f2` 是 ×100 整数（`3962`→39.62 元），`clist` 的 `f2` 是真实值（`127.24`→127.24 元）。两个解析函数**必须独立、切勿复用或混用** ÷100 逻辑。

⚠️ **同一接口内，价格类除数还随标的类型变（2026-08-22 实测，腾讯 qt 同时刻交叉验证）**：场内基金（ETF/LOF）报价 3 位小数，`ulist`/`stock/get` 的价格类字段（f2/f4/f15-f18、f43-f46/f60）裸值为 **×1000**（510880 f2=3387→3.387、513100 f2=2195→2.195），股票仍 ×100（600519 f2=127283→1272.83）；百分比类（f3/f170 等）两类标的均 ×100。除数选择统一封装在 `divPriceScaleOrNull(isFund)`（`QuoteSnapshot.kt`），基金判定用 `FundDividendParser.isExchangeTradedFundCode`（裸 6 位：5/15/16 开头，两市无股票冲突号段）。

#### 4.9.2 字段编号必须查官方文档，禁止凭直觉推断

东财资金流字段有固定编号规律，**不要猜**（曾因写错字段导致占位值被当真实数据）：

| 净额（元，不除） | 占比（%，clist 不除） |
|---|---|
| f62 主力 = f66 超大单 + f72 大单 | f184 主力 |
| f66 超大单 | **f69** 超大单 |
| f72 大单 | **f75** 大单 |
| f78 中单 | **f81** 中单 |
| f84 小单 | **f87** 小单 |

规律：净额 f6x/7x/8x（66/72/78/84，每 +6）；净占比 = 净额 +3（69/75/81/87）。⚠️ 不是 f174/f175/f185/f192。拿不准时**先查东财资金流向页面字段或用 `WebSearch` 核实**。

其他已实测确认的榜单字段（2026-08-15）：**f133 = 股息率 %（clist 真实值）**，交叉验证方式为「近 12 月每股分红合计 ÷ clist f2 现价」（汇洁股份 1.10÷7.53≈14.6% 与 f133=14.61 吻合）；全市场 A 股 fs 串 = `m:0+t:6,m:0+t:80,m:1+t:2,m:1+t:23`（约 5500 只，见 `MarketDataRepository.fetchMarketRanking`）。⚠️ clist 只支持单字段排序，**不支持条件过滤**——「股息率≥X 且 PE≤Y」类筛选只能按排序拉前 N（现取 200）条客户端过滤，满足条件但排在候选集外的会漏（`get_market_ranking` 工具的 `note` 字段已如实标注该口径）。

#### 4.9.3 同语义数据要换接口时，先验证「字段完整度」

`stock/get` 对资金流字段（f66/f69/f72/f75 等）**返回不完整**（实测只回 f62/f184/f84 等少数）——个股资金流必须改用 `clist`（`fs=m:{market}+t:2+s:{code}`）才能拿到全套净额/占比。**「字段在 stock/get 存在但为空」≠「数据缺失」，可能是接口对该类字段不支持，要换接口而非反复重试。**

#### 4.9.4 腾讯接口作为交叉验证金标准

腾讯行情返回**直接是真实值**（无 ÷100 问题），是验证东财裸值规则的可靠基准：

- **`qt.gtimg.cn/q=sh600519`**（实时行情）：`v_sh600519="1~贵州茅台~600519~1350.60~..."`，第 4 字段=现价、含涨跌额/涨跌幅/成交额/主力净流入。**接入东货行情前，用腾讯同时刻值交叉验证 ÷100 规则是否正确**（见 `QuoteSnapshotTest` 注释）。
- **`web.ifzq.gtimg.cn/`（fqkline）**：前复权 K 线 + 分红明细，`KlineRepository`/`DividendRepository` 使用。注意：单次上限约 640 交易日（≈2.5 年），覆盖 5 年需按日期窗口分块请求（见 `DividendRepository.fetchAllDividendsFromTencent`）。

#### 4.9.5 同花顺扶摇单位与接口纪律（权威主源，实测 2026-08-23）

- **全部真实值**：价格/百分比/金额无 ÷100/÷1000 规则（腾讯同刻交叉验证）；唯一换算：快照成交量**股→手 ÷100**、K线成交量**股→手 ÷100**（审计 M2）、基金分红**每10份 ÷10**。
- **统一信封**：业务错误也 HTTP 200，`FuyaoEnvelope.code != 0` 即失败（1002 标的/参数错、3001 不存在、5003 未就绪、429/4001 频率超限）→ 降级候补源。
- ⚠️ **整批毒代码**：ETF 混入 A 股批量接口整批报 1002，股票/基金必须拆分请求。
- ⚠️ **报告期取 `period_end_ms`**（`report_date_ms` 是公告日）；**营业总收入扶摇无此口径**（=营业收入，审计 M1，由东财并行回填）；财务指标 value 为字符串数值。
- **只有日线**：周/月线本地聚合；基金日K恒未复权→基金 K 线保持腾讯。K线换源须全量重建（`kline_cache_meta.source`）+ 故障冷却 10 分钟。

#### 4.9.6 解析层实践（强制）

1. **每个新 DTO 必须配真实 JSON fixture 单测**——fixture 取自实测响应（脱敏裁剪无关字段），断言每个字段的解析值与单位。例：`MarketDtoParseTest`、`FinancialStatementDtoParseTest`、`BondYieldResponseParseTest`、`QuoteSnapshotTest`。
2. **÷100 / takeIfFinite 等转换封装为 private 扩展**，集中一处，禁止散落多份复制。注意可空性：可空字段（`Double?`）调扩展要用 `Double?.takeIfFinite()`（接收者也声明可空），`item.field?.takeIfFinite()` 的写法才编译通过。
3. **金额一律「元」绝对值**（非每股、非万元），缺失字段为 null，**绝不臆造**（宪法原则 III）。占比字段单位「%」，真实值即展示值。
4. **报告期日期归一化**：东财 datacenter 三表的 `REPORT_DATE`/`REPORTDATE` 实测带 ` 00:00:00` 后缀，跨表对齐前必须 `substringBefore(" ")` 去后缀（见 `FinancialStatementsBuilder`）。
5. **Gson 对 `"-"`（停牌占位）转 `Double` 会抛异常**：生产层用 `runCatching` 吞掉（红线 #2）；测试层不要断言 `"-"`→null（Gson 默认不容错），改用「字段缺失→null」的 fixture。

---

## 5. 命令

```bash
# 构建（CI 跑的就是这条）
./gradlew build

# 仅 Debug APK
./gradlew assembleDebug

# Release（需签名环境变量；缺失则只构建不签名）
KEYSTORE_FILE=... KEYSTORE_PASSWORD=... KEY_ALIAS=... KEY_PASSWORD=... ./gradlew assembleRelease

# 跑单元测试
./gradlew test            # 全部
./gradlew :app:testDebugUnitTest   # 仅 debug 单测

# 跑单个测试类
./gradlew :app:testDebugUnitTest --tests "com.stock.dividend.viewmodel.PortfolioViewModelTest"

# 发版：打 v* 标签触发 .github/workflows/release.yml
git tag v3.2.0 && git push origin v3.2.0
```

CI（`android.yml`）用 JDK 17 temurin，且显式 `USE_CHINA_MIRROR=false` 直连官方仓库以绕开镜像 502。**本地若网络差可设 `USE_CHINA_MIRROR=true` 走阿里云镜像。**

---

## 6. 测试约定

- **纯函数/计算器/Parser**：纯 JUnit4 + Truth，无 Android 依赖，快。例：`BollCalculatorTest`、`HoldingRecommenderTest`、`LlmAnalysisParserTest`、`BuyThresholdCalculatorTest`。
- **ViewModel**：`@RunWith(RobolectricTestRunner)` + MockK mock 掉 Repository/Dao，`Dispatchers.setMain(StandardTestDispatcher())`，`runTest { ... advanceUntilIdle() }`。例：`PortfolioViewModelTest`。
- **Flow**：用 Turbine `test { ... }`。
- 测试包结构与 `main` 对齐（`app/src/test/java/com/stock/dividend/...`）。
- **改了纯函数或新增决策逻辑，必须补/改对应单测**（项目一贯做法，见 git log 大量 `test(scope):` 提交）。

---

## 7. Git 规范

### 7.1 Commit 规范（Conventional Commits，已严格执行）

格式：`<type>(<scope>): <中文简述>`。常见 type：`feat` / `fix` / `test` / `docs` / `refactor` / `ci` / `chore`。scope 用模块缩写：`llm` `vm` `ui` `settings` `stock` `kline` `boll` `advisor` `nav` `plan` `ci` 等。简述用中文，动词开头。

示例（取自真实 log）：
- `feat(llm): LlmAnalysisRepository 编排 + 测试`
- `fix(plan): T8 SYSTEM 去 const（.trim() 非编译期常量）`
- `test(stock): fetchBoll 测试迁移到 fetchCloses(_, WEEKLY)`
- `feat(ui): 评估结果页加策略信号区 + AI 解读区块`

> **不要主动 commit/push**，除非用户明确要求。在 `master` 上改动前先开分支。

### 7.2 分支

默认/主分支是 `master`。CI 对 `master` 和 `001-stock-dividend-tracker` 分支的 push 触发。

---

## 8. 红线与易错点（agent 高频踩坑提醒）

1. **改 schema 必加 Migration 并 bump version** —— 漏了会 `IllegalStateException: Room cannot verify the data integrity`。
2. **网络/DB 异常必须吞**，返回空集合/null，别让 UI 崩。但要在日志或 UI 上有可感知的兜底（如「加载失败，显示缓存」）。
3. **`isLoading` 状态需显式复位**：进入网络请求前置 true，结束（含失败）后置 false，否则刷新按钮可能被永久禁用（见 `PortfolioViewModel` Collector 2 注释）。
4. **自选股（shares=0）也要能拉现价/行业**：价格刷新订阅的是全量 `allStocksFlow` 而非 `holdingsFlow`，下游 recompute 才用持仓快照。改这块时别图省事切回 `holdingsFlow`。
5. **并发限流**：批量拉 BOLL 用 `Semaphore(3)`，腾讯接口会拒高频。评估一只股发 3 次（日/周/月）请求。
6. **纯函数不要带 Android 依赖**，否则进不了纯 JVM 单测。
7. **不要对东财原始数据做换算**（除「每10股→每股」与展示格式化），宪法原则 III。
8. **Release 签名信息是环境变量**（`KEYSTORE_*`），别硬编码进 gradle 或提交进仓库。
9. **依赖版本只改 `libs.versions.toml`**，别在 `build.gradle.kts` 写裸版本号。
10. **中文界面**：所有用户可见文本中文；注释项目内统一用中文（与现有代码一致）。
12. **取股市数据必须走 `MarketDataPlane`（§4.2A）**：消费方禁止直接注入/调用行情类 Repository 的取数方法（fetchQuotes/fetchQuoteSnapshots/fetchBoll/getFundamentals/fetchCapitalFlow…）或行情类 Dao；新数据消费一律经平面，否则缓存写透/分红自动刷新/BOLL 限流等统一语义全部失效。
11. **外部数据接入必读 §4.9**：东财 `ulist`/`clist`/`stock/get` 三接口单位规则互不相同（clist 不除、其余 ÷100），资金流字段编号要查文档（占比是 f69/f75/f81/f87 不是 f174/f175），新 DTO 必须配实测 fixture 单测并用腾讯 qt 交叉验证。**单位搞错 = 数据全错，比崩溃更隐蔽。**

---

## 9. 常用入口文件速查

| 想做什么 | 先看 |
|---|---|
| 理解整体架构 | `MainActivity.kt` → `ui/navigation/AppNavigation.kt` → `ui/screen/MainScaffold.kt` |
| 持仓/评估主流程 | `viewmodel/PortfolioViewModel.kt` + `data/repository/HoldingRecommender.kt` / `PortfolioAdvisor.kt` |
| 加一张数据表 | `data/local/AppDatabase.kt`（Migration）+ `dao/` + `entity/` + `di/DatabaseModule.kt` |
| 加一个网络接口 | `data/remote/*Api.kt` + `dto/` + `di/NetworkModule.kt`；**接入行情/财务/资金流等外部数据前必读 [§4.9](#49-外部数据接口单位与解析纪律--关键数据准确性)**（单位规则/字段编号/fixture 单测） |
| 加一个页面 | `ui/screen/XxxScreen.kt` + `viewmodel/XxxViewModel.kt` + 注册到 `AppNavigation.kt` |
| 复用 UI 样式 | [`DESIGN.md`](DESIGN.md) + `ui/component/AppComponents.kt`（新组件）/ `DesignSystem.kt`（历史）+ `ui/theme/` |
| 通知/后台 | `data/notification/` + `StockDividendApp.kt`（WorkManager） |

---

## 10. 变更记录
- 2026-08-22（三）：**缓存管理页 UI 重构——布局仿系统「存储空间」页三段式**（纯 UI 改造，VM/仓库/导航零改动）。① **用量总览卡**（替代原纯文字「缓存说明」卡）：总量大字（tnum）+ 永久/短期分组小计 + **按类着色的分段占比条**（`CacheSegmentBar`，宽度=条目数占比、全 0 渲染灰底空态条）+ 压缩版策略说明。② **分类明细卡**（原每类一张大卡 → 单卡多行 + 图标位缩进分隔线，系统设置分组列表样式）：每行 = 彩色圆形图标（`cacheKindIcon` 七类各配 Material 图标 + `cacheKindColor` 主题色角色映射，自动跟随深浅色）+ 名称 + 永久/短期小圆角 pill 徽标 + 右侧条目数（tnum）+ 清理按钮；各类 description 从行内移至清理确认弹窗（弹窗本就展示，行内更紧凑）。③ 首次进入加载态（对齐 ErrorLogScreen 模式）；确认弹窗/Snackbar/清理联动逻辑不变。④ 新纯函数 `cacheSegmentFractions`（统计→分段，零条目剔除、fraction 归一、全 0 返空）+3 单测，全量 1308 过。**不改 schema**。
- 2026-08-22（二）：**支持 ETF/LOF 场内基金——可搜索、加自选/持仓、看行情 K 线、收分红全链路**（此前 `searchStocks` 的 `Classify=="AStock"` 过滤把基金全部挡掉，用户加不了 ETF）。① **搜索放行**（`StockRepository.searchStocks`）：实测 searchapi 口径——场内基金 `Classify="Fund"` 且 `MktNum` 为 "1"(沪)/"0"(深)（510300/159915/161907 实测），与 A 股同市场规则；场外基金 `Classify="OTCFUND"`（MktNum="150"，如易方达消费行业 110022）不可行情交易，继续排除。加股/批量导入/Agent `add_stock`/截图导入全走同一搜索，无需各自改动。② **行情除数修复（重要，§4.9.1 新规则）**：实测（push2delay + 腾讯 qt 同时刻交叉验证）`ulist` 与 `stock/get` 的**价格类字段（f2/f4/f15-f18、f43-f46/f60）对场内基金是 ×1000**（510880 f2=3387→3.387、513100 f2=2195→2.195），股票仍 ×100（600519 f2=127283→1272.83）；百分比类两类均 ×100。新 `Double?.divPriceScaleOrNull(isFund)` 封装（`QuoteSnapshot.kt`），`toQuoteSnapshot`/`toIndexQuote`/`searchStocks` 补价三处接入——顺带修复存量 bug：`get_etf_info` 工具此前对 ETF ÷100（价格大 10 倍）且 `guessMarketByCode6` 把 5 开头沪市 ETF 判成深市 secid（`0.510880`，请求即空）。③ **ETF 分红专用源**：腾讯 fqkline 分红仅覆盖股票（510880 六年 640 行实测 0 条分红行）、东财 RPT_SHAREBONUS_DET 对 ETF 返回空——新 `data/remote/FundDividendApi`（基金 f10 分红送配页 `fundf10.eastmoney.com/fhsp_{code}.html`，服务端渲染 HTML，ScalarsConverter 返回原文；未分红基金返回「暂无分红」空表）+ 纯函数 `FundDividendParser`（§4.4 惯例：cfxq 表解析——年份/权益登记日/除息日/每10份派现金/发放日，仅「每10份→每份」÷10 合规换算，送份额/缺除息日/零金额跳过，id=`code_除息日` 腾讯方案；`isExchangeTradedFund`/`isExchangeTradedFundCode` 判定——沪 5 开头、深 15/16 开头，两市无股票冲突号段）；`DividendRepository.fetchAndCacheDividends` 对场内基金分流到该源（失败静默空、不误清历史，与腾讯主源同语义），红利 ETF 510880 年度分红、红利ETF联接 161907 按月分红均正常入库，DPS/股息率/日历/今日信号/K 线股息率网格线全链路自动可用。④ **降级路径核验**：基本面/财务三表（datacenter 对 ETF 空 → null → UI 隐藏）、行业 f127（空 → 不写入）、资金流（clist 匹配不到行 → null）均天然优雅降级，零改动。⑤ 测试 +20（FundDividendParser 9：识别真值表/裸代码无冲突/真实 fhsp fixture 解析含 ÷10/暂无分红空/送份额跳过/缺除息日跳过/垃圾输入不抛/按除息日去重；QuoteSnapshot 4：基金价格 ÷1000/涨跌额 ÷1000/百分比仍 ÷100/深市基金；MarketDataRepositoryParse 2：toIndexQuote 基金 ÷1000+指数不误判；StockRepository 1：Fund+MktNum 放行/OTCFUND 排除；DividendRepository 4：分流到 f10 不打腾讯东财/解析入库/空表无写入/网络失败静默），全量 1305 过。**不改 schema**；新依赖 `converter-scalars`（libs.versions.toml 红线 #9）。
- 2026-08-22：**AI 聊天支持发送图片（多模态）——识别持仓/成交截图直接导入**。① **多模态探测**：新纯函数 `MultimodalModelDetector`（`data/agent/ChatImages.kt`，按模型名启发式匹配主流多模态家族：GLM-4v/4.5v/4.6v、GPT-4o/4.1/5、Qwen-VL/QVQ、Claude 3/4/5、Gemini、deepseek-vl、`vision`/`-vl`/`pixtral` 通用兜底；deepseek-chat/v4-flash、glm-4-flash、qwen-plus 等纯文本模型不误判），`AiChatRepository.observeMultimodal()` 响应式暴露给 VM；当前模型不支持时点「加图」按钮给出可解释提示（SYSTEM 气泡引导切换多模态模型）而非静默禁用，误判兜底靠服务端 400 经 Error 事件透出。② **协议层**：`OpenAiMessage.content` 放宽为 `Any`（String 或多模态数组，Gson 按运行时类型序列化），新增 `OpenAiContentPart`/`OpenAiImageUrl` DTO；`buildOpenAiRequest` 把用户消息中 inlineData 图片 Part 转为 OpenAI `content:[{type:"text"},{type:"image_url"}]` 数组（纯文本消息保持字符串形态、请求体最小改动；纯图片 content 修复被「空文本跳过」分支吞掉的问题）。③ **编解码**：同文件 `imageDataUrlToPart`/`Part.imageDataUrl()`（kotlin.io.encoding.Base64，纯 JVM 可单测）——data URL ↔ ADK `Part(inlineData=Blob)`，图片随会话经 ADK SessionService 持久化，重开历史会话缩略图可回读（`AiSessionMessage.images`）。④ **发送链路**：`AiChatRepository.send(sessionId, text, imageDataUrls)` 构建文本+图片混合 Content；选图复用截图导入同一套 `loadSampledBitmap`（最长边 2048 下采样）+ `bitmapToJpegDataUrl`（1600px/80% JPEG，单张 150-400KB）；单轮上限 3 张（超出 SYSTEM 提示）；支持纯图片（无文字）发送，会话切换清空待发送。⑤ **UI**：输入栏新增「发送图片」按钮（Photo Picker）、待发送缩略图行（64dp、右上角移除）、用户气泡内图片渲染（data URL 本地解码、解码失败占位图标）；`ChatMessageUi.images` 全链路透传。⑥ **导入编排**：系统提示词新增「图片识别与导入」段——持仓截图→表格复述→`add_stock`（新股票，shares>0 自动记一笔买入）/`update_holding`（已持有）逐只走确认门；成交截图→`add_transaction` 逐笔记入（日期缺失用今天并告知）；识别不清的字段如实说明、禁止编造代码/价格/股数（宪法原则 III 延伸到图片输入）。**不改 schema、无新网络接口**（复用既有 LLM 通道）。测试 +19（编解码/探测 6、协议 3、指令 1、仓库 2、VM 7），全量 1285 过。
  - 补记（同日）：DeepSeek 上线官方多模态模型 `deepseek-v4-flash-vision-exp`（支持图片输入，OpenAI 格式 Chat Completions，见官方 pricing 页）——探测器显式收录 `deepseek-v4-flash-vision` 前缀（此前靠 `vision` 兜底已能命中，显式化防兜底被收紧后漏判；`deepseek-v4-flash`/`v4-pro`/`chat` 仍为纯文本）；「不支持图片」提示与设置页 Model 输入框 supportingText 均补 DeepSeek Vision 示例。vision-exp 走 Chat Completions 路径（非 Responses），图片链路天然可用。
- 2026-08-20（晚二）：**新增「失败日志」页（设置 → 数据 → 失败日志）——关键的静默失败集中可见、可清理**。背景：红线 #2 要求网络/DB 失败一律吞掉返回空值，但「数据获取失败」被吞后用户完全无感（页面悄悄降级为空数据/缓存/默认值，比崩溃更隐蔽）——现把这类失败持久化收集到日志页。① **数据层**：DB v25→26（新 `error_logs` 表：timestamp/category/source/message/detail，`MIGRATION_25_26` 建表）+ `ErrorLogDao`（observeAll/latest/count/clearAll/trimToRecent）+ `ErrorLogEntity`。② **记录门面** `ErrorLogRepository`：`record(source, message, throwable?, category)`——**60s 同 source+message 防抖**（退避重试/下拉刷新连点不刷屏）、每次插入后 `trimToRecent(200)` 防表膨胀、detail 堆栈裁 12 帧/2000 字符；**记录自身全程吞异常**（日志代码不能成为新故障源，红线 #2 对日志自身同样适用）；分类枚举 `ErrorLogCategory`（NETWORK 数据获取/DATABASE 本地存储/LLM AI 调用，raw 存储 label 展示）。③ **埋点范围**（数据获取失败，均为原本静默吞掉的失败路径，成功路径零改动）：`MarketDataPlane`（分红刷新失败 ensureDividendsFresh+refreshDividends、日/周/月 K 线 fetchCloses、基本面、财务三表）+ `StockRepository.fetchQuoteSnapshots`（行情，含标的数）+ `BondYieldRepository`（国债远端失败回退缓存/默认值——精度损失不可见）+ `MarketDataRepository`（资金流/板块列表/行业内个股/全市场榜单/指数行情/龙虎榜）；搜索与 LLM 失败原本就以 Result/错误提示对用户可见，不重复收集。④ **UI**：`ErrorLogViewModel`（observeAll 响应式订阅，清理后自动重发射；collect 异常退出也复位 isLoading 红线 #3；category raw→中文 label，未知 raw 原样回退；单展开切换）+ `ErrorLogScreen`（说明卡明示收集范围与 200 条上限 / LazyColumn 列表卡：来源+分类+摘要+时间，有 detail 点击展开等宽堆栈 / 「清理全部日志」确认弹窗 + Snackbar / 空状态）+ `DataSettingsScreen` 入口行（Warning 图标）+ MainScaffold `errorLogs` 路由。⑤ 测试 +20（ErrorLogRepository 9：插入含 detail+修剪/无异常 detail=null/防抖窗口内跳过/窗口外重记/不同源或摘要不防抖/detail 截长/record 吞 DB 异常/clearAll 吞/count 失败返 0；VM 6：加载映射中文/未知 category 回退/collect 异常复位 loading/单展开切换/清理确认全流程 message/dismiss 后 confirm 为 no-op；埋点 3：plane K 线失败+分红刷新失败、repo 行情失败均 verify record；时间格式化 2），全量 1266 过（净增 20）。**埋点只在失败路径上，不改变任何既有行为**。
- 2026-08-20（晚）：**修复「已公告实施、明天除权」的年度分红不计入股息率**（用户实测：海尔智家 2025 年度分红 10派8.9151 除权日 2026-08-21、ASSIGN_PROGRESS=实施分配，股息率只算了 2025 中报那一半）。根因两层：① **数据层结构性缺失**——腾讯分红嵌在历史 K 线数组第 7 元素里，只有已除权日的记录（未来的 K 线不存在），而东财明细（含未来已排期记录）仅在腾讯完全无数据时才作回退，`enrichDividendYieldFromEastMoney` 拉了同一份东财数据却只用来补 dividendYield 字段——未来除权记录从未入库；② **计算层显式排除**——`rollingYearlyTotals` 的 `takeIf { !it.isAfter(today) }` 把未来除权日全部挡在 TTM 窗口外。修复：① `DividendRepository.enrichAndMergeFromEastMoney`（原 enrich 升级，同一东财请求两用、零新增网络）：按除权日对齐补股息率快照之外，把「exDate 已定且腾讯按除权日没有」的**已排期未除权**记录转实体合并入库（东财→实体转换抽 `toEastMoneyEntity` 与回退路径共用；预案 exDate=null 金额可能变，不合并）；② `rollingYearlyTotals` 窗口锚点从 today 前移至 `max(today, 入窗记录最大除权日)`，已排期除权日（未来 ≤365 天护栏，防脏数据漂移）视同已派入窗——海尔场景 TTM=(0.2692+0.89151)=1.16071 全款，`latestYearlyCashPerShare`/`calculateAvgCashPerShare` 共享锚点不变量保持。未来记录入库后股息日历/今日信号倒计时同步可见。**已知限制**：分红 7 天 TTL 内已排期记录不进库（实施公告→除权日窗口通常 1~2 周，7 天大多赶得上；赶不上时详情页手动刷新立即修复）。测试 +4（ForecastCalculator 3：海尔实测金标准/近未来计入含锚点挤出语义/远未来排除；DividendRepository 2：合并含预案不并入/零合并回归），全量 1246 过。**不改 schema**。
- 2026-08-20：**数据一致性审计（真实接口交叉验证）+ 全量修复**。审计报告见 `docs/audit/2026-08-20-数据一致性审计报告.md`（14 项实测验证通过 + 2 高危/9 中危/13 低危发现）。当日修复闭环（单测 1216→1242 全绿，净增 26）：① **H1 资金流行归属校验**——实测发现 clist `fs=m:1+t:2+s:{code}` 单股筛选**不生效**（返回全市场列表按 fid 排序），`fetchCapitalFlow` 此前 `pz=1+firstOrNull()` 且 fields 无 f12，拿到的是「当日涨幅第一名」的资金流（张冠李戴）；修复为请求 f12 按 `it.code == code6` 精确匹配。② **H2 腾讯分红截断洞**——实测 3 年分块窗口（≈730 交易日）超 640 根上限时腾讯锚定最新端**截头约 4 个月**（中国移动 2023-09-01 的 10派22.247 永久丢失、9 笔丢 1 笔）；分块改**三块各 2 年**（<640 必完整，与 KlineRepository「不赌超窗截断」纪律对齐）。③ **M1/M6 数值脏值容错 Gson**——实测 clist 对退市/停牌股全字段返回 "-"，默认 Gson 抛 NumberFormatException 且**整批 diff 解析失败**（一条脏记录毒死整个列表）；新 `data/remote/LenientDoubleDeserializer.kt`（"-"→null）注册到全部东财/腾讯 Retrofit（LLM 不共享）；东财分红裸调用的网络异常**保持传播**（双源失败用户可感知，不吞）。④ **M2 派息率年度合计口径**——`enrichPayoutRatio` 签名改 `Map<Int, Double>`（分红年→年度合计），仅挂年报期 12-31（中期 EPS 是半年值，除年度分红会得约两倍错误值）；腾讯 nd 年度/东财真实报告期按「前 4 位年份」归一（`cashPerShareByDividendYear` 扩展收敛于平面）——修复半年派息股派息率约低估一半。⑤ **M3 国债失败不锁死缓存**——冷启动失败路径不再把 DEFAULT_YIELD/旧缓存写入 memoryCache/prefs（此前进程存活期永远假基准 2.5%），旧缓存回退不刷 updated_at，首行 10Y null 向后扫备选行。⑥ **M5 merge 字段级保底**——`mergeByReportDate` 新增 `repairRemote` 参数（远端同期 null 字段回退缓存值），基本面/三表两调用点全字段保底（防「子接口失败→整期覆盖抹掉缓存齐全字段且被持久化」）。⑦ **M7** 诊断装配器逐股前置 `ensureDividendsFresh`（与 getDps 同新鲜度策略）。⑧ **M8 解析函数回归锁**——`toMarketList`/`toIndexQuote` 提为顶层 internal + 新建 `MarketDataRepositoryParseTest`（clist 不除/stock/get ÷100 规则反转锁定，含实测样本断言）。⑨ 其余：searchStocks 复用 div100OrNull（L1）、DTO 死字段清理与 f47 移除（L3/L4）、rollingYearlyTotals 非正金额统一过滤（L11）、漂移检测过滤未来除权日（M4-3）。**不修项及理由**见审计报告第〇节（M4-1 送转漂移盲区/M4-2 混纪元窗口/L5 北交所前缀等 5 项，均记录为已知限制）。
- 2026-08-19：**网格交易系统支持逐档自定义资金比例**（此前固定 1/price 反比分配，越便宜买越多——现可对单个档位单独配置比例，如底部档加大弹药/首档轻仓试探）。① **计算层**：`GridCalculator.generate` 新增尾参 `levelWeights: List<Double>?`（与档位同序、从最便宜档起的**相对权重**，无需合计 100，计算时归一化）；null = 反比默认（全链路旧数据/旧调用零影响）；长度≠grids 或含非正 → 参数错误「各档资金比例须为正数且与档数一致」（档位价/下一档提示不受权重影响，只改资金分配）。② **持久层**：DB v24→25（grid_plans 加可空列 `levelWeights`（JSON 数组字符串如 "[20.0,30.0,50.0]"），`MIGRATION_24_25`）；实体文件内新增 `GridLevelWeights` 编解码纯函数（toJson/parse：格式损坏/含 0 负数/空数组 → null 回退反比，绝不让脏数据炸档位计算；+5 单测）。③ **生成器 UI**：`GridPlanScreen` 新增「资金分配」区——「反比（默认）/自定义比例」FilterChip，切自定义时**以当前预览的反比权重百分比预填**（用户从合理基线微调而非从零手填，参数不全时按均分 100/n）；逐档输入行（档位价 + 比例输入框，YIELD 计划附股息率）+ 合计提示（相对比例语义，不强制 100）；预览区自定义模式下逐档展示股数/金额；改档数时权重输入随档数伸缩（截断/均分补位）。④ **VM**：UiState 加 `customWeights`/`levelWeightInputs`；非法输入（空/非数字）折算 0 → generate 报参数错误 → 预览可见报错 + 保存被阻（沿用「参数无效：…」口径，不静默）；savePlan 序列化 JSON 入库、editPlan 由存档反解回填（编辑预览与存库分配一致）；重锚定确认经 plan.copy 保留权重（资金意图不随价格重算）。⑤ **全链路透传**（否则通知/信号/小组件/Agent/回测的股数与执行进度与网格页不一致）：GridNotifyEvaluator/TodaySignalAggregator/WidgetDataRepository/GridTools（get_grid_plans 补 allocation 口径字段）/PortfolioAnalysisTools/GridBacktestCalculator/列表装配/回测入口全部传 `GridLevelWeights.parse(plan.levelWeights)`。⑥ **备份防御**：normalizeGridPlans 对损坏 JSON/档数不匹配的 levelWeights 置 null（回退反比），脏数据不让整个计划不可用；可空列无 Gson NOT NULL 撞库风险，旧备份恢复零修补。⑦ 计划卡档位表标题对自定义计划加「· 自定义比例」标记。测试净增 +20（GridCalculator 6：金标准/相对归一化/价格与下一档不变/档数不匹配/非正报错/等比与 YIELD 兼容；编解码 5；VM 7：预填/预览重算/序列化保存/非法保存阻断/编辑回填/档数伸缩/列表渲染/重锚定保留；回测 1；备份 1），全量单测过。**网格仍仅计划与提示，不自动下单**。
- 2026-08-19：**新增「缓存管理」页（设置 → 数据 → 缓存管理）——缓存可见、可清，永久缓存策略明示**。① 新仓库 `CacheManagementRepository`（+`CacheKind` 枚举：PRICE/SEARCH/KLINE/FUNDAMENTALS/STATEMENTS/DIVIDENDS/LLM_ANALYSIS 七类，含 `permanent` 永久缓存标记与中文 label/description——K线/财务指标/三表/分红四类为历史不可变数据即永久缓存，price/search/LLM 为可随时重建的短期缓存）：`loadStats` 逐类 COUNT 统计（单 DAO 失败记 0 不拖累其余，吞异常红线 #2）+ `clear(kind)`/`clearAll` 全表清理；**清理 dividends 联动清 `DividendFreshnessStore` 记账**（接口/实现新增 `clear()`——否则退避时间戳残留，清库后 5 分钟内 getDps 吃闭门羹不重新拉网）。② `MarketDataPlane` 新增 `clearSessionCaches()`（清行情 10s/BOLL·市场 60s 内存会话缓存——堵「持久缓存清了但内存窗口还剩旧值」的死角）。③ 7 个缓存 DAO 补 `count()`（KlineCacheDao 另补 `clearAll()`=bars+meta 事务删除）——纯 @Query 增量，**不改 schema、无迁移**。④ 新 `CacheManagementViewModel`（条目加载/确认弹窗状态/单类清理/全部清理/一次性 message，`isLoading`/`isClearing` 显式复位红线 #3）+ `CacheManagementScreen`（说明卡明示永久缓存策略 + 每类条目数与「永久缓存/短期缓存」徽标 + 清理确认弹窗防误触 + 全部清理 + Snackbar 反馈；条目数千分位走 `formatEntryCount`）+ `DataSettingsScreen` 入口行 + MainScaffold `cacheManagement` 路由。清理范围只含可再生缓存，自选股/持仓/交易等用户数据不涉及。测试 +18（仓库 10：统计/逐类清理/dividends 联动/permanent 策略锁定/标签非空；VM 6：Robolectric；平面 1：清内存后同窗口重新发网；页面纯函数 1），全量 1184 过。

- 2026-08-19：**个股详情页「近期走势」升级为 30 日 K 线蜡烛图 + 股息率网格水平线**（替代原 Vico 成交量柱图）。① 新纯函数 `DividendYieldGridCalculator`（§4.4 惯例，+7 单测）：档位价 `P = 年度每股分红(DPS) ÷ 股息率`（与 GridAnchorCalculator 股息底/DividendPriceScale 同公式），围绕现价隐含股息率对齐 0.5% 步长网格、仅保留 30 日价格区间内整档（典型 3~7 条），边界等值保留（EPS 容差防浮点误伤），现价缺失退区间中点锚定。② 新组件 `KlineYieldChart`（纯 Compose Canvas 自绘——Vico 开源版无蜡烛图层、MPAndroidChart 不适配双主题；+4 internal 纯函数单测）：蜡烛主图（影线+实体、十字星实体最矮 1dp、涨绿跌红随 ExtendedColors）+ 金色（tertiary）虚线股息率线带右侧自适应 gutter 标签「6.5% ¥9.23」（PercentFormatter/MoneyFormatter）+ 底部成交量迷你柱（涨跌着色减淡，保留旧图信息）+ 首末 MM-dd 日期 + 价格摘要行；DPS 缺失时图例降级「暂无分红数据，未画股息率线」，蜡烛照常渲染。③ `StockDetailViewModel`：UiState 加 `dps`（分红 collector 内经 `ForecastCalculator.latestYearlyCashPerShare` 反应式派生，手动刷新分红后自动更新），init 追加 `plane.ensureDividendsFresh`（空表/超 7 天自动拉网，§4.2A）；现价档位锚定取 `quote?.price ?: klines.last().close`（+3 VM 单测）。④ 删除 `PriceVolumeChart.kt` 及其测试（仅详情页一处使用，信息已并入新图）。⑤ **永久缓存加固**（应「这些数据需要永久缓存」核查发现的缺口）：`KlineRepository` 全量拉取（首拉/强刷/除权重建）的回看窗口此前跟随调用方 bars——小窗口调用者（详情页 30 根）触发的重建只落 ~98 根浅历史，`replaceBars` 覆盖已有深缓存且不自愈（增量只向前追加），网格回测（250 根）被静默截短；修复为固定 `FULL_FETCH_BARS=250` 深窗口（折算 ≈543 交易日 < 腾讯单次上限 640，不赌超窗截尾行为），与调用方请求条数解耦（+2 回归单测）。⑥ **股息率档位最低 3 档保证**（应「至少显示 3 个挡位，包括上下两个挡位以及当前最近的一个挡位」）：`DividendYieldGridCalculator` 此前仅返回 K 线区间内档位，窄区间时缩水到 1~2 条线；现无条件保证「离现价最近档 ± 1 档」（某侧越股息率下限时向另一侧补足），区间外档位照常返回；`KlineYieldChart` Y 轴范围改为蜡烛区间 ∪ 档位价并集以容纳区间外档位，图例删去不再可达的「无整档」降级文案（计算器测试 7→10 用例）。⑦ **修复半年派息股股息率只算一半**（用户实测：中国移动股息率不对，只计入一半分红）：根因——`ForecastCalculator.latestYearlyCashPerShare` 按 `reportDate` **日历年**分组取最新年，而半年派息股（中期约 11 月除权、末期约次年 6 月除权）一个完整派息年度的款项跨日历年劈进两组，最新年组只剩一笔；修复为**优先按除权日取最近 12 个月（TTM）合计**（窗口 `(today-1y, today]`，未来除权日与未除权预案不计入——属前瞻而非已派），TTM 为空时回退原口径（兼容年度一次派息超 12 个月未除权/仅预案/数据陈旧，函数加 `today` 参数默认 `LocalDate.now()` 供测试注入）；经此单点修复，`plane.getDps`/`getCurrentDividendYield`/买入线/网格锚定/组合诊断/今日简报/K 线股息率网格线全链路口径自动矫正（+5 单测：中国移动跨年场景金标准/预案与未来除权排除/三种回退）。⑧ **年度分红口径全 App 统一收敛**（应「采用统一的数据接入，统一数据平面就是为了解决不同地方的数据差异」——平面统一了数据接入，计算口径也须收敛）：抽取 `rollingYearlyTotals` 共享实现（按除权日划分的滚动 12 个月窗口，窗口 k=`(today-(k+1)年, today-k年]`，未来除权日/未除权预案不计入，空窗口=停派年跳过），`latestYearlyCashPerShare`（TTM）与 `calculateAvgCashPerShare`（N 年均，驱动预测股息收入卡）共用同一锚点——修复后「股息率」与「预测收入」两处数字同源，不再出现一边全款一边半款；`calculateAvgCashPerShare`/`calculateForecastIncome` 增 `today` 参数（默认 `LocalDate.now()`）供测试注入；无可用除权日数据仍回退报告期日历年分组（存量 13 用例零变化）。锁定不变量单测：`latestYearlyCashPerShare(d) == calculateAvgCashPerShare(d, 1).avgCashPerShare`（+3 单测：CM 三年均值金标准/预案不进窗口/口径一致性）。**不改 schema、无新网络请求**（K 线/DPS 全走平面既有链路）。

- 2026-08-18：**新增数据平面（MarketDataPlane）并全量迁移消费方——股市数据获取唯一入口**。背景：网格页拿不到股息率暴露「数据获取路径不统一」（详情页手动拉分红 vs 网格只读本地；主 UI 走 fetchQuoteSnapshots 不写 price_cache vs Widget 只读缓存；股息率 4 种口径；enrichPayoutRatio 3 处重复；Semaphore(3) 5 处手写；4 VM + 3 仓库直注 Dao）。落地：① 新包 `data/plane/`（门面 + 策略 + 分红新鲜度 prefs 记账 + 并发去重），统一语义——行情写透 price_cache、getDps 自动 ensureDividendsFresh（空/超 7 天拉网、失败 5 分钟退避）、getCurrentDividendYield 唯一股息率口径、getBoll 内置限流+缓存、getFundamentals 返回已补派息率产物、市场数据 60s 内存缓存；`StockRepository.fetchQuoteSnapshots` 补写透缓存、`fetchQuotes` 降级为其薄封装，`DividendRepository` 增 getDividends/getAllWithExDate/observeAllDividends，`StockRepository` 增 getStock。② **全量迁移消费方**：9 个 VM（Portfolio/Today/StockDetail/GridPlan/Drip/AddStock/DividendCalendar/PortfolioImport 等，写操作留原仓库）、协调器（TodayBriefingCoordinator 补齐自认缺位的 latestYearlyDividend 口径、PortfolioDiagnosisAssembler 删自建 enrich/限流）、通知（NotificationCheckCoordinator）、Widget（refreshPrices 走平面）、Agent 全部 34 个读工具 + AiAgentFactory（GetValuationMetricsTool 双请求合并、GetPortfolioSignalsTool 逐股单请求改批量、GetKlineTool BOLL 并入平面路径）。③ 测试：新增 MarketDataPlaneTest 16 用例，迁移 14 个测试文件到 plane mock；全量 1154 过。**不改 schema**（分红新鲜度用 SharedPreferences）。


- 2026-07-29：重写本文件，从自动生成的稀薄摘要升级为面向 AI agent 的完整开发指南（技术栈/架构/约定/命令/测试/红线/速查）；移除 spec-kit 工作流章节及所有相关引用。
- 2026-08-01：新增 `DESIGN.md` 设计系统文档（双主题/Inter 字体/核心组件/格式化器）；§4.5 改为引用该文档；落地基建：`AppComponents.kt`（AppCard/AmountText/PercentText 等）+ `Formatters.kt`（MoneyFormatter/PercentFormatter + 26 单测）+ `Gradient.kt`（CompositionLocal 扩展主题）+ 双主题（亮/暗）+ Inter 可变字体（子集化 210KB，含 tnum）。
- 2026-08-02：新增 §4.9「外部数据接口单位与解析纪律」+ §8 红线 #11，沉淀数据准确性实践经验（东财 push2 三接口单位规则差异/资金流字段编号/腾讯交叉验证/fixture 单测）；落地 AI Agent 新增 13 个股票信息工具（估值指标/资金流/财务三表/分红深度/行业对比/资讯研报/市场广度），DB version 15→18（新增 `financial_statements_cache` 表），配套纯函数 `DividendMetricsCalculator` + 真实 fixture 解析单测。修正：AGENTS.md 原写 DB version=15 已过时，实际 18（含历史 16/17 的 trade_strategies、fundamentals_cache、llm_analysis_cache 表）。
- 2026-08-02：§3 目录结构从「目录级简述」升级为「完整文件清单 + 每个文件作用」，覆盖 main 全部 ~130 个 .kt（data/local|remote|repository|agent|notification|scan|widget、di、ui/component|screen|theme|navigation、viewmodel）+ test ~65 个 .kt，含本次新增的财务三表/资金流/分红深度等文件。新增「文件规模速览」速记。
- 2026-08-04：新增「已实现盈亏（FIFO）」功能。落地纯函数 `RealizedPnlCalculator`（先进先出结转，A 股个人转让所得法定口径）+ 11 单测；`TransactionDao.observeAll()` / `TransactionRepository.observeAll()` 响应式全量交易流水；`PortfolioViewModel` Collector 8 订阅 → UiState 注入组合级合计 + 个股已实现盈亏；`PortfolioScreen` 摘要卡「累计已实现盈亏」行 + 持仓卡「已实现」指标。**不改 schema**（复用 transactions 表），与 `HoldingCalculator` 摊薄成本法并存（摊薄用于持仓成本展示、FIFO 用于已实现盈亏展示）。
- 2026-08-04：新增「全局交易流水 + 交易笔记（复盘）」功能。DB version 18→19（transactions 表加可空 `note` 列，`MIGRATION_18_19`）；`TransactionHistoryViewModel`（combine 全量交易 + 股票名映射 → 按日期倒序流水，累计买卖金额汇总）+ `TransactionHistoryScreen`（流水卡片 + 资金流水汇总 + 备注编辑弹窗 + 专属空状态）+ 5 单测；`EditHoldingViewModel`/`TransactionSheet` 新增/编辑交易均支持备注字段，`TransactionCard` 展示备注；导航 `Routes.TRANSACTION_HISTORY` + 设置页「交易记录」入口。备份自动覆盖 note（BackupContainer 直存 TransactionEntity）。
- 2026-08-04：新增「分红再投资（DRIP）复利模拟」功能。落地纯函数 `DripCalculator`（按年把分红以可配置再投价全额买入，逐年扩股；对比「分红再投」与「现金分红」两条路径的期末市值与超额收益）+ 9 单测（含手算金标准/涨跌场景/再投禁用退化/年份过滤/窗口截取）；`DripSimulationViewModel`（复用 dividends 数据，参数可调即时重算）+ `DripSimulationScreen`（汇总卡 + 参数卡 + 逐年明细表 + 诚实的简化口径说明）；导航 `dripSimulation/{code}` + 个股详情页「分红再投模拟」入口。**不改 schema**（复用 dividends 表）。再投价采用单值简化口径（非逐日真实价），UI 明确标注假设，遵守宪法原则 III（不臆造数据）。
- 2026-08-04：新增「网格交易计划（计算器）」功能。DB version 19→20（新增 `grid_plans` 表，`MIGRATION_19_20`）；落地纯函数 `GridCalculator`（等差网格分档：[low,high] 等分 grids 份，1/price 反比分配资金，低价多配；当前价「下一档买/卖」提示；A 股 100 股整手取整）+ 13 单测；`GridPlanEntity`/`GridPlanDao`/`GridPlanRepository`（CRUD）；`GridPlanViewModel`（列表 + 生成器参数实时预览 + 保存/编辑/删除）+ `GridPlanScreen`（计划卡 + 下一档提示 + 档位表 + 生成器 ModalBottomSheet + 专属空状态）+ 5 VM 单测；导航 `Routes.GRID_PLAN` + 设置页「网格交易」入口；备份覆盖 grid_plans。**重要定位**：仅做档位生成与提示，**不联网下单**——网格实际执行由用户在券商端手动完成。
- 2026-08-04：网格结合股息 + 布林带。落地纯函数 `GridAnchorCalculator`（基准价=BOLL 中轨、上界=BOLL 上轨、下界=用户目标股息率对应价 `P=D/(yield/100)`，取 min(BOLL下轨, 股息底)；**到达目标股息率=网格资金用完位**）+ 9 单测；`GridPlanViewModel.autoAnchor()`：拉周线 BOLL + 历史分红，按用户目标股息率自动填充基准/上下界 + 锁定来源说明；`GridPlanScreen` 生成器新增「目标股息率」输入 + 「自动锁定」按钮 + 锚定结果卡片（说明下界由技术面/价值底哪侧决定）；+3 VM 单测（成功锚定/数据不足降级/未选标的报错）。语义：用户调高目标股息率 → 下界价更低 → 同资金买到更多股（更深安全垫），网格区间不再凭空手填。
- 2026-08-04：网格入口上个股详情页 + 按股独立设置。`StockDetailScreen` 新增「网格交易计划」入口卡（`onOpenGridPlan`）；新路由 `gridPlanFor/{code}`；`GridPlanViewModel` 经 `SavedStateHandle["code"]` 读取入口标的——命中自选股时**自动打开生成器、预选该股并立即触发 BOLL+股息率智能锚定**（每只股票独立锚定到自己的 BOLL/分红，参数互不串扰）；+2 VM 单测（携带 code 自动预选+锚定/未知 code 不触发）。
- 2026-08-04：修复「网格设置后不能保存」。根因：保存按钮 enabled 表达式 `preview?.validationError == null` 在 **preview=null（参数未填全/非法）时误判为 true** → 按钮可点但 `savePlan()` 里 `toDoubleOrNull() ?: return` **静默 return**，点击毫无反应。修复：① 按钮在 preview=null 时禁用（`preview != null && ...`）；② `savePlan` 参数校验失败改为设置**可见 `saveError`**（不静默）；③ UI 在保存按钮上方展示 `saveError`；+1 回归单测（参数不完整 → saveError 可见且不落库）。
- 2026-08-04：网格改**纯买入模型**（收息仓定位，杜绝「买了涨了就卖」）。`GridCalculator` 移除卖出档语义：买入区间 `[资金用完位, 买入起点]` 等分 grids 档、**档位全部为 BUY**（无 sellLevels/nextSellHint），资金 1/price 反比（越便宜买越多），`highPrice` 降级为「参考上界（超过不追买）」；`nextBuyHint`=现价下方最近买入档、现价跌破资金用完位返回 null。`GridAnchorCalculator` 改**三周期 BOLL 锚定**：买入起点 = min(日 BOLL 下轨, 周 BOLL 下轨, 月 BOLL 中轨)（**「月线中枢及以下」防守型建仓**，而非一回到中枢就买）、资金用完位 = min(三周期下轨最低, 目标股息率底)、参考上界 = 月 BOLL 上轨；周期缺失跳过。`GridPlanViewModel.autoAnchor()` 并发拉日/周/月三周期 BOLL。UI：去「下一卖」提示、档位表全「买」、预览区展示「参考上界（不追买）」、锚定卡说明三周期来源。测试重写：GridCalculator 12 / GridAnchor 10（含「股息底 ≥ 起点 → null 提示调高目标股息率」）/ VM 11。
- 2026-08-04：网格关联个股交易记录显示触发状态。`GridCalculator.markTriggeredLevels()` 纯函数：**BUY 成交价落在档位触发区间（档位价 ± 半步长）→ 标记该档 `triggered`**（SELL 不参与判定，纯买入模型语义）+ 5 单测；`GridPlanViewModel` 注入 `TransactionRepository`，combine 全量交易按股票分组注入；`GridPlanScreen` 档位表已触发档显示「买✓」+ 价格淡化、档位表标题行显示「已触发 N/总档」进度。**不改 schema**（复用 transactions 表）。
- 2026-08-05：网格三大增强（执行闭环/一键记账/动态重锚定预警）。① **资金执行跟踪**：`GridExecutionCalculator` 纯函数（已投入金额/剩余可投/已买股数/加权均价/浮盈浮盈率/进度百分比，与 markTriggeredLevels 同口径命中）+ 6 单测；计划卡展示「执行进度条 + 已投入/剩余可投/浮盈 + 已买均价」，有买入才显示。② **下一档一键记账**：网格页「下一买 ¥X」旁「记账」按钮 → 跳 `editHolding/{code}?buyPrice=&buyShares=`，`EditHoldingViewModel` 读 query 参数自动打开买入表单预填档位价/建议股数（闭环执行链路）+ 2 单测；`editHolding` 路由加可选 query 参数（旧无参跳转兼容）。③ **动态重锚定预警**：现价高于买入起点 >15% 时计划卡显示「⚠ 行情偏离当初锚定，建议重新锁定」（行情已远离当初的 BOLL 支撑位，计划可能失真）。**不改 schema**。`GridPlanScreen` 增加 `onAddTransaction` 回调（全局入口不接，个股入口经 `gridPlanFor` 路由接入）。
- 2026-08-02（补登）：新增 **AI 助手设置页**（用户可见功能）。`AiSettingsScreen`（系统提示词/温度/输出长度）+ `AiSettingsViewModel` + `AiAgentConfig`/`AiAgentConfigRepository`（SharedPreferences 存储）；`AiAgentFactory` 注入 `agentConfigSource`，把 systemPrompt/temperature/maxTokens 经 `GenerateContentConfig` 透传到 OpenAI 请求。AI 会话页右上角入口。原 §3 目录树漏登，本次补上。
- 2026-08-02（补登）：`add_trade_strategy` 工具 + `StrategyActionTools.kt`。Agent 工具数 **43→44**（31 读 + 13 写），写工具新增「全局策略库自动提取」能力；系统提示词引导 Agent 在解读截图/持仓时主动调用。原 §3/§4.4 漏登，本次补上。
- 2026-08-03（补登）：备份/恢复扩展覆盖 **LLM 与 AI 助手的 SharedPreferences 配置**（provider/key/url + 系统提示词/温度/输出长度）。`BackupData.kt` 数据载体除 Room 表外现含这部分；恢复时一并写回。原 §3 BackupData 注释未提，本次补上。这是备份边界的公开行为变化（用户恢复后期望值改变）。
- 2026-08-05（文档同步）：对照近 7 天代码变更对齐 AGENTS.md。① §2 技术栈：Kotlin 2.0.21→**2.1.20**、KSP 2.0.21-1.0.28→**2.1.20-1.0.32**、Room 2.6.1→**2.8.4**；新增「AI Agent | Google ADK Kotlin | 0.6.0」行 + ADK 传递依赖 stdlib 对齐坑（stdlib 锁 2.1.21）。② §4.6：DB version 18→**20**（17 表/19 迁移）。③ §3/§4.4：补 `StrategyActionTools.kt`/`AiSettings*`/`AiAgentConfig*`/`ToolDisplayName.kt`/3 个设置二级页/`DividendMetricsCalculator`/`GridExecutionCalculator`；工具数订正为 44；§4.4 删重复 `DripCalculator` 行。④ §3 文件规模速览与 test 目录注释数字按实测订正（main≈247/test≈85；agent 9/repo 44/vm 22）。
- 2026-08-15：Agent 组合分析三工具（工具数 **44→47**：34 读 + 13 写）。① `get_market_ranking`：全市场 A 股榜单（clist 全市场 fs 串 + f133 股息率字段，**经实测交叉验证**并配 fixture 单测；支持股息率/涨幅/市值/PE/PB/换手 6 维排序 + 股息率下限/PE 上限客户端过滤，返回 `note` 如实说明「仅前 200 名候选集」口径）。② `compare_stocks`：多股对比（2-8 只），默认快照（单次 ulist 批量）+ 本地分红深度（连续年数/CAGR/变异系数，零请求）+ 持仓盈亏，`deep=true` 加日/周/月三周期 BOLL 共振评估（BUY/HOLD/SELL 程序计算，Semaphore(3) 限流）；股息率按现价实时算（与 get_stock_info 同口径）。③ `diagnose_portfolio`：组合风险全景诊断，新纯函数 `PortfolioRiskDiagnoser`（§4.4 惯例）+16 单测——集中度（行业/个股 HHI+CR、股息来源前 3）/股息可持续性（连续分红<3 年权重、派息率>100% 名单经 enrichPayoutRatio 装配）/估值水位（加权股息率 vs 10Y 国债利差）+ 规则化中文建议。系统提示词加三工具编排引导（找高股息→榜单、比较个股→对比、组合体检→诊断+信号+行业配比串联）。新增 `PortfolioAnalysisTools.kt`（工具层）+ `PortfolioAnalysisToolsTest.kt`（11 测试）。§4.9.2 补 f133/全市场 fs/客户端过滤口径知识。**不改 schema**。
- 2026-08-15：今日页「金融分析师视角」三区块（把只活在 Agent 工具层的能力提升为首页常驻）。① **市场环境卡**：四大指数（上证/深证/沪深300/创业板，fetchIndexQuotes 过滤）2×2 + 领涨/领跌板块 Top3 + 主力净流入板块 Top3（新纯函数 `MarketMoodCalculator`，口径同 get_market_sentiment：一次 clist(CHANGE,30) 本地排序两端 + 一次 clist(INFLOW,3)）；数据全空整节隐藏；**无持仓时也渲染**（看大盘不需要持仓）。② **组合体检卡**：新 `PortfolioRiskDiagnoser.grade()`（集中度/股息可持续/估值水位三维度 OK/WARN/BAD，阈值与建议规则同源）+ 摘要三行红绿灯 + 首条建议，点击展开完整数字（HHI/CR/股息来源/派息率超标名单/利差）与全部建议；新装配器 `PortfolioDiagnosisAssembler`（@Singleton）收敛「持仓+现价→DiagnoseHolding」装配，`diagnose_portfolio` 工具与今日页**共用同一实现**（今日页复用已刷新行情不重复拉价，fundamentals 读取 Semaphore(3) 限流）。③ **股息现金流卡**：本年已到账（observeTotalByYear）大字 + 全年预测（observeForecastTotal）进度条 + 差额，点击跳收入 Tab（MainScaffold 传 onOpenIncome）。`TodayViewModel` Collector B 在价格刷新后**并行**补算信号/市场/体检（async+awaitAll，各源吞异常互不拖累），新增 Collector D 响应式订阅现金流；组合表现卡删去与市场卡重复的上证/沪深300 行（保留「跑赢沪深300」相对表现）。④ **AI 简报喂料增强**：`TodayBriefingCoordinator` 追加体检行（股息率/10Y 国债/利差/单股最大权重）与市场行（领涨领跌板块），`TodayBriefingPromptBuilder` 加可选参数（null 省略块），Worker 与前台共用 Assembler。测试：PortfolioRiskDiagnoserTest 补 grade 8 用例、新 MarketMoodCalculatorTest 5 用例、新 PortfolioDiagnosisAssemblerTest 7 用例、TodayViewModelTest 补 4 用例、TodayBriefingPromptBuilder/CoordinatorTest 补喂料用例。**不改 schema**。
- 2026-08-16：`BollPriceScale`（周线 BOLL 刻度尺）增强——**现价点按带内真实比例定位横轴，价签跟随点移动**。新增「现价位置轴」（BoxWithConstraints 40dp）：轨道（0=下轨…1=上轨）+ 下轨→现价已走区间 tone 色半透明着色 + 中轨参考刻痕（BOLL 上下轨对称于中轨即 50%）+ 现价圆点按 `fraction = (price-lower)/(upper-lower)` 真实偏移（两端各让半个点直径防裁切，贴边=破轨）。**价签（价格文字）上移到轴上、中心与点中心同 x 跟随移动**（首版价签固定在底部行居中，与点上下错位——用户实测反馈）；价签宽度用 `rememberTextMeasurer` 精确测量后在两端 `coerceIn` 钳制防裁切。底部行简化为「现价落点（下轨 ↔ 上轨）」+ 三态状态：带内 x% / 破上轨 ↑（negative）/ 破下轨 ↓（positive）。组件内部改动，`StockCard`/`PortfolioScreen` 两处调用无需变更。
- 2026-08-16：**修复「下一买」指向已买档**（用户实测反馈：已买过一档且现价回升到其上方，下一买仍提示该档）。根因：`GridCalculator.nextBuyHint` 只取「现价下方最近档」未排除已触发档——纯买入网格每档只买一次，现价回升后旧档不该再提示。修复：`GridResult.nextBuyHint` 从构造参数改为**计算属性**（新增 `currentPrice` 字段），动态取「现价下方最近的**未触发**档」，`markTriggeredLevels` 标记后自动重算、永不失效；null 增加第三种语义「下方档全部已买」。**全链路对齐**（此前三处消费方直接 generate 不标 triggered，同样会踩坑）：今日信号 `TodaySignalInput.gridTransactionsByStock`（TodayViewModel/TodayBriefingCoordinator 注入 TransactionRepository 装配）、小组件 `gridNextHints`、`get_grid_plans` 均补 markTriggeredLevels。UI 三态文案区分「已到/跌破资金用完位」vs「下方档位已全部买入」（收起摘要/展开下一档行/刻度尺三处）。⚠️ 计算属性无法智能转换（custom getter），消费处需先存局部 val。测试 +4（跳过已买档/下方全买 null/聚合器排除/小组件排除）+ 1 处存量预期修正（get_grid_plans 下一档 8.67→8.0）。
- 2026-08-16：网格计划卡改为**默认收起、点击展开**（多计划并览优化）。收起态只保留：头部（名称▸/参数摘要/编辑/删除/箭头）+ 一行核心摘要（现价 · 下一买¥X（距下一档%）· 已触发 n/m 档 · 已投入）+ 重锚定预警单行；刻度尺/股息展望/执行摘要/档位表/回测/到档开关全部折叠进展开态。展开状态用 `rememberSaveable(plan.id)`（随 LazyColumn item key 持久化，滚动/重组不丢）。头部参数摘要顺带补「等比」标记。
- 2026-08-16：**网格交易系统二期（14 项完善）**。① **等比网格**：DB v21→22（grid_plans 加 `gridType`（ARITH/GEOM，NOT NULL DEFAULT 'ARITH'）+ `targetYieldPercent`（可空，重锚定用户意图），`MIGRATION_21_22`）；`GridCalculator.generate` 尾参 `gridType`（等比 = low×(base/low)^(i/(n-1))，stepPercent 语义=每档步长%；**Evaluator/今日信号/VM/Agent 工具全链路传参**，否则 GEOM 计划档位算错）；生成器等差/等比 FilterChip。② **一键重锚定**：stalenessHint 预警块加「重新锁定」→ 重拉三周期 BOLL+分红（targetYield = 存档值 ?: 由现用完位反推）→ 新旧三价确认弹窗 → 保存（保留 createdAt、重置提醒状态）。③ **历史回测**：新纯函数 `GridBacktestCalculator`（250 交易日收盘价回放，收盘≤档位价即触发、**按档位价成交假设**，对照首日一次性买入的 costSavingPct）+ VM `backtestPlan`（按需点击拉 K 线，`KlineRepository.fetchKlines(code, DAILY, 250)` 单请求）+ 计划卡回测区块（口径声明在 UI 明示）。④ **网格股息展望**：`dividendOutlook`（Σ档位股数×年 DPS → 年股息与占资金收益率），收息定位的终极答案；计划卡绿色提示行。⑤ **弹药库汇总**：`summarizeAmmo` 纯函数 + 列表顶部合计卡。⑥ **档位刻度尺**：新组件 `GridLevelScale`（价格轴左低右高、已触发✓淡化、下一买 primary 高亮、底部「距下一档 x%」；参照 DividendPriceScale 的 tick/fraction 模式）。⑦ **逐档成交明细**：`levelFills` 纯函数，档位表已触发行尾注「✓ MM/dd ×股数」。⑧ **实际持仓口径**：执行摘要并排「网格累计买入 M 股 · 当前实际持仓 N 股」（卖出后不再混淆）。⑨ **通知 dedupKey**：`sendNotificationRuleAlert` 接口默认参 `dedupKey`（Android 实现 id=（stockCode+dedupKey).hashCode()），Coordinator 传 "grid-{planId}"——同股多套网格互不覆盖；⚠️ MockK 陷阱：验证块未写出的参数走**默认值**而非 any 匹配，须显式写全。⑩ **交易时段守卫**：`AshareTradingTime`（周一至五 9:15–15:15 含头尾，午休不细分）+ `GridNotificationWorker` 前置跳过，盘外零请求。⑪ **通知权限可见**：VM 注入 DividendAlertNotifier，权限被关时计划卡警示行。⑫ **备份版本化修补（首个按 dbVersion 分支先例）**：`normalizeGridPlans`——v20 备份缺 notifyEnabled（Gson→false）恢复为 true、缺 gridType（Gson→**null 会撞 Room NOT NULL 约束使整个恢复事务失败**）兜底 ARITH；⚠️ 踩坑：`copy()` 未指定的参数读原对象 null 值会触发非空参数检查 NPE，必须单次 copy 显式传全。⑬ **小组件下一档**：WidgetUiState+`GridNextHint`（price_cache 现价本地算，零新增网络），**无持仓仅有网格计划也展示**；refreshPrices 拉价范围并入网格标的（修复自选股缓存价死角）。⑭ **诊断串联**：diagnose_portfolio 输出 `gridUninvestedCash`（Σ剩余弹药，注明不改现金比例判定口径）。测试净增 35（GridBacktest 6/AshareTradingTime 4/等比与展望 6/fills 与弹药 5/重锚定与回测与弹药权限 VM 7/备份归一化 3/小组件 3/诊断与 dedupKey 等）。
- 2026-08-15：**完善网格交易系统（四方向）**。① **下一档到价推送通知**：DB version 20→21（grid_plans 加 `notifyEnabled` 开关 + `lastNotifiedLevelPrice` 去重状态，`MIGRATION_20_21`）；新纯函数 `GridNotifyEvaluator`（data/notification，迟滞边沿触发：到达=「档位价≥现价」中最便宜档、每档只提醒一次、现价回升过上次提醒档后复位可再提醒、已实际买入的档不唠叨）+11 单测；`NotificationCheckCoordinator.checkGridPlans()`（按计划维度拉价，**自选未持仓也可提醒**）+5 单测；复用 `sendNotificationRuleAlert` 管线发「网格到达买入档」通知（零接口变更，GRID_NEXT_LEVEL_ALERT 路由 PRICE_EVENTS 渠道）；新 `GridNotificationWorker` + `NotificationScheduler.scheduleGridChecks()`（每小时独立周期任务）+ `StockDividendApp` 注册；计划卡新增「到档提醒」开关（toggleNotify 不动 updatedAt 防列表重排）；编辑计划保存时重置提醒状态。⚠️ Dao `updateNotifiedLevel` 特意不更新 updatedAt（通知回写不得导致列表重排）。② **AI Agent 网格工具**：`get_grid_plans`（GetGridPlansTool，工具数 **46→47**：34 读 + 13 写）——计划参数/现价/下一档价与股数/执行进度/提醒开关 + note 声明不自动下单；注册/展示名「查询网格计划」/系统提示词网格引导；+3 工具测试。③ **细节修缮**：修复编辑计划 `createdAt` 被刷新为当前时间的 bug（editingCreatedAt 保留原值，+回归单测）；`GridPlanEntity` KDoc 与空态文案对齐纯买入/反比权重定位；今日页 GRID_NEXT_LEVEL 信号点击直达 `gridPlanFor/{code}` 网格页（其余信号仍跳个股详情）。④ **执行复盘统计**：`GridExecutionCalculator` 新增 `avgDeviationPercent`（金额加权平均偏差，正=成交价高于档位价）/`worstDeviationPercent`（最差一次）+3 单测；计划卡执行摘要新增偏差行（正偏差警示色/负偏差 positive 色）。**踩坑记录**：Kotlin `buildMap` 中 `put` 返回旧值（首次为 null），`x?.let { put(k1,v); … put(k2,v2) } ?: put(k1, null)` 的 elvis 会被 put 返回值误触发覆盖——先取局部变量再 put，勿依赖 let 链返回值。
- 2026-08-15：**移除 DDM 内在价值评估功能**（整链路删除，工具数 **47→46**：33 读 + 13 写）。删除：纯函数 `DividendDiscountCalculator` + 单测、`DividendValuationViewModel` + 单测、`DividendValuationScreen`（股息折现估值页）+ 字段帮助单测、Agent 工具 `get_valuation`（GetValuationTool）+ 工具测试、`ToolDisplayName` 的 get_valuation 映射、`MainScaffold` 的 `dividendValuation/{code}` 路由、`StockDetailScreen` 顶栏「估值」按钮与「股息折现估值」入口卡；系统提示词去 DDM 字样。**不改 schema**；PE/PB/市值估值（get_valuation_metrics、ValuationCard）与买入线（get_buy_threshold）不受影响。
- 2026-08-16：**集成 GLM-4.6V-Flash 视觉模型：同花顺持仓 + 交易记录截图智能导入**。① **多模态 DTO**：`LlmMessage.content` String→Any（Gson 按运行时类型序列化，文本路径零改动；响应侧 `LlmChatResponse.content` 用 `as? String` 兜底）；新增 `LlmContentPart`/`LlmImageUrl`（image_url=data:image/jpeg;base64,…）；`responseFormat` 改可空（视觉请求省略——vision 模型对 response_format 支持不稳）+ 可空 `max_tokens`（视觉传 4096 防长表截断）。② **视觉配置**：`llm_prefs` 新增 `vision_api_key`/`vision_model`（默认 glm-4.6v-flash，baseUrl 固定智谱）；**key 为空且全局 LLM 也是智谱时自动复用全局 key**（存量智谱用户零配置）；设置页「AI 与策略」新增「视觉识别模型」分组。③ **视觉导入编排** `VisionImportRepository`：bitmap→1600px/80% JPEG base64（`bitmapToJpegDataUrl`）→ content parts 请求 → `VisionImportParser`（纯函数：日期 20260801/2026年8月1日/MM-dd 归一化、方向「证券买入/卖出」→BUY/SELL、非交易行 type=null 交用户选、数字字符串去千分位）；**自动重试 5 次**（可重试：网络错/429/5xx/返回格式异常；401/403 直接报错；指数退避 1/2/4/8/8s，onRetry 回调 UI 显示「正在重试 n/5」）。④ **持仓导入双引擎**：`PortfolioImportScreen` 顶部 FilterChip「本地识别（不上传）/AI 智能识别」，视觉已配置默认 AI；隐私文案按引擎切换（⚠ 原「图片不会上传」承诺改为按模式如实标注）。⑤ **交易记录导入**（新页面）：`TransactionImportViewModel/Screen` + 路由 `transactionImport` + 交易流水页顶栏相机入口；`StockRepository.importTransactions`（事务内按日期升序：resolveStock→缺股建自选 0 股→**五元组去重**（同股同日同向同价同股数跳过）→插入→recomputeHolding，note="截图导入"）；手续费不计入（无该列，UI 明示）。测试净增 33：Parser 12/Repository 10（含重试链 401 不重试/IO 重试成功/耗尽 6 次请求/5xx 可重试/Invalid 重试/Empty 不重试）/配置回退 4/StockRepo importTransactions 3/PortfolioImport VM AI 引擎 3/TransactionImport VM 6/BitmapLoader 2；修复 `TodayBriefingCoordinatorTest` 因 content:Any 的编译连带。**不改 schema**。
- 2026-08-17：**网格支持按股息率分档（YIELD 模式）**。用户输入股息率区间（如 5.5%→6.5%）+ 档数，每档买入价 = 年度每股分红(DPS) ÷ 该档股息率（`P = DPS/(yield/100)`，与 GridAnchorCalculator 股息底同公式），股息率等差递增 → 价格双曲线递减，天然满足 low < base。① **计算层**：`GridType` 第三种 `YIELD("YIELD")`（fromRaw 三分支）；`GridCalculator.generate` 尾参 `dps`（YIELD 必填，null/≤0 → validationError；股息率由两端价格反推 dps/base、dps/low，中间档股息率等差，首末档价格精确闭合）；`GridLevel.yieldPercent`/`GridResult.yieldStepPercent` 仅 YIELD 填充。② **持久层**：DB v22→23（grid_plans 加 `dpsPerShare REAL` 可空——**建计划时的 DPS 快照**，分红变化不使档位漂移；normalizeGridPlans 无需修补：可空列 Gson→null 不撞约束，旧备份不存在 gridType="YIELD"）。③ **VM**：UiState 加 yieldStartInput/yieldEndInput/generatorDps；`onStockSelected` 拉本地分红 DPS（竞态防护：仅选中标的未变时写入）；recalculatePreview YIELD 分支换算三价（参考上界=买入起点，股息率低于起始%即不追买）；savePlan 存 gridType="YIELD"+dpsPerShare 快照+targetYieldPercent=结束股息率；**editPlan 回填股息率区间（由存档 DPS 反推，档位与存库一致）+ 顺手补 targetYieldInput 既有回填缺口**；YIELD 一键重锚定=不拉 BOLL、重拉最新 DPS 沿原股息率区间重算三价（ReanchorDiff.newDpsPerShare 随确认保存，分红增长→网格整体上移）。④ **UI**：生成器第三个 FilterChip「按股息率」（YIELD 下隐藏锚定区与三价输入，改显起始/结束股息率+DPS 信息行三态：未选标的/有 DPS/无分红数据警示）；档位表标题 YIELD 展示「股息率 a%→b%（每档 +x）」；档位行最后一列 YIELD 以「息 x.xx%」替换偏离% 列；计划卡副标题「按股息率 a%→b%」标记。⑤ **全链路透传**（否则 YIELD 计划在通知/信号/小组件/Agent/回测中报「需要分红数据」）：GridNotifyEvaluator/TodaySignalAggregator/WidgetDataRepository/GridTools（get_grid_plans 补 yieldRange 描述）/PortfolioAnalysisTools/GridBacktestCalculator 均传 `dps = plan.dpsPerShare`。测试 +12（GridCalculatorTest 7：金标准 5.5/6.0/6.5 用例、端点精确、dps 校验、反比权重、nextBuyHint/触发标记兼容、非 YIELD null；GridPlanViewModelTest 5：预览换算、保存实体断言、无分红数据可见错误、编辑回填、YIELD 重锚定）。**不改备份载体**（直存实体自动覆盖新列）。
- 2026-08-17：**历史不可变数据本地缓存（缓存增强三线改造）**——K线/财报/分红全部持久化到 Room，离线可用、历史永不丢失。① **K线永久缓存**（最大缺口：此前零持久化，每次 BOLL/回测都发腾讯请求）：DB v23→24（新增 `kline_cache`（PK=stockCode+period+date 的 OHLCV 行）+ `kline_cache_meta`（每股每周期 fetchedAt + lastExDividendDate），`MIGRATION_23_24`）；`KlineRepository` 缓存编排（签名不变、全调用点自动受益，新增 `forceRefresh` 尾参）：尾部已覆盖「本周期正在形成的最新一根」（新纯函数 `klineTailIsCurrent`：日线=今天/周线=本周/月线=本月）**或今日已同步过 → 零网络直读**；尾部落后且今日未同步 → **每日最多一次**小窗口增量补尾（从最后一根含拉到今天，覆盖盘中变动的尾根，`buildIncrementalParam`）；**前复权漂移检测**——meta 存写入时最新除权日，与 dividends 表比对，出现新除权日（除权后全历史价格整体位移，增量合并会算错 BOLL）→ 强制全量重建（约每股每年 1-2 次）；`trimToRecent(800)` 防增量无限增长；网络失败回退缓存（断网 BOLL/回测/图表仍可用）。② **分红历史保留式写入**：原「deleteByStockCode 整表清空+重插」有两大历史丢失风险——腾讯窗口仅 ~6 年窗口外历史被删、双源空结果（多为反爬抖动）也清库；改为**按 id/除权日定点删除**（`deleteByIds`/`deleteByStockAndExDates`，腾讯 id=code_exDate 与东财 id=code_reportDate 两种方案跨源去重）+insertAll，窗口外历史行永续累积；双源空结果不清库（`Result.success` 直返）；东财全量路径额外 `deleteStalePendingByStock` 清洗失效预案行（exDate=null 且不在本次结果中；腾讯不携带预案信息不清洗）。⚠️ Room `IN ()` 空列表是非法 SQL，repo 层 `takeIf { isNotEmpty() }` 守卫。③ **财报/基本面历史期次合并**：新纯函数 `mergeByReportDate`（远端同报告期覆盖缓存、缓存独有旧期永续保留、升序返回）——原 7 天 TTL 过期整体覆盖会因远端窗口缩短/部分接口失败丢旧期次，现 `FundamentalsCacheRepository`/`FinancialStatementsRepository` 刷新时 merge 后落库（TTL 仍 7 天，变的只是不再丢历史）。**不改备份载体**（kline_cache 与其他 cache 表一致视为可再生缓存，不进备份）。测试：KlineRepositoryTest 改造+新增 10 用例（首拉落库/尾部完整零网络/本周周线零网络/当日已同步跳过/增量窗口起点/断网回退/除权全量重建/空响应标记已同步/klineTailIsCurrent 边界）、DividendRepositoryTest 改造+新增 3 用例（定点替换不清史/东财路径清洗预案/腾讯路径不清洗）、新 HistoryCacheMergeTest 5 用例、新 FinancialStatementsRepositoryTest 4 用例、FundamentalsCacheRepositoryTest +1 merge 用例。
- 2026-08-23：接入同花顺扶摇官方金融数据 API 为**权威第一数据源**（行情/指数/搜索/股票+ETF·LOF 分红/股票日K+周月线聚合/财务三表/财务指标），东财/腾讯候补并行补齐（`supplementedFrom` 范式）；key 设置页运行时填写；K线换源全量重建（DB v26→27）。同日全量接入扶摇独有能力（估值/日历/龙虎榜/涨跌停/热榜/异动/竞价/指数目录·成分·指数日K/代码表 + 基金域 24 端点，数据平面 +~50 方法）；落地永久缓存层 `fuyao_cache`（DB v27→28，`FuyaoCacheStore` 三语义）离线可读。横向验证三方同刻 31 PASS/0 FAIL（docs/audit/2026-08-23），修复 M1 营业总收入口径、M2 K线成交量股→手。
