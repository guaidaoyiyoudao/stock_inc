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
| 语言 / JVM | Kotlin / Java 17 toolchain | 2.0.21 |
| 构建 | AGP + KSP + Gradle Kotlin DSL | AGP 8.7.3, KSP 2.0.21-1.0.28 |
| UI | Jetpack Compose + Material Design 3 | BOM 2024.12.01, M3 1.3.1 |
| DI | Hilt | 2.53.1 |
| 本地存储 | Room (SQLite) | 2.6.1，**当前 DB version = 20** |
| 网络 | Retrofit + OkHttp + Gson | 2.11.0 / 4.12.0 |
| 异步 | Coroutines + Flow | 1.9.0 |
| 导航 | Navigation Compose（单 Activity + 多 Composable） | 2.8.5 |
| 图表 | Vico（新图表）+ MPAndroidChart（历史股息率图） | 2.1.3 / 3.1.0 |
| 图片 | Coil3（含 SVG logo） | 3.1.0 |
| OCR | ML Kit 中文识别（Play Services 按需下载模型，保 APK 小） | 16.0.1 |
| desugar | `desugar_jdk_libs`（`java.time` on minSdk 24） | 2.1.4 |

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
        │   │   │   │   ├── AppDatabase.kt       # Room DB（version=20）+ 全部 Migration（红线 #1）
        │   │   │   │   ├── backup/BackupData.kt # 备份/恢复的数据载体（JSON 序列化）
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
        │   │   │   │   │   └── GridPlanDao.kt               # 网格交易计划（2026-08-04 新增）
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
        │   │   │   │       └── GridPlanEntity.kt               # 网格交易计划（仅计划/提示，不下单，2026-08-04 新增）
        │   │   │   │
        │   │   │   ├── remote/                  # Retrofit 接口（DI 在 NetworkModule 装配，见 §4.7/§4.9）
        │   │   │   │   ├── SearchApi.kt         # 东财 searchapi（搜索）
        │   │   │   │   ├── QuoteApi.kt          # 东财 push2 ulist/stock/get（行情，÷100 规则见 §4.9）
        │   │   │   │   ├── MarketApi.kt         # 东财 push2 clist/stock/get（板块/个股/资金流/指数，2026-08-02 新增）
        │   │   │   │   ├── FundamentalApi.kt    # 东财 datacenter（基本面/财务三表/资产负债表/龙虎榜）
        │   │   │   │   ├── DividendApi.kt       # 东财 datacenter（分红明细，回退源）
        │   │   │   │   ├── TencentDividendApi.kt # 腾讯 ifzq（K线/分红，主源，见 §4.9.4）
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
        │   │   │   ├── repository/              # Repository（@Singleton）+ 纯函数计算器（无 Android 依赖）
        │   │   │   │   ├── StockRepository.kt              # 自选股核心：resolveStock/fetchQuotes/fetchQuoteSnapshots/fetchBoll/fetchFundamentals
        │   │   │   │   ├── DividendRepository.kt           # 分红拉取（腾讯主+东财回退）与缓存
        │   │   │   │   ├── DividendIncomeRepository.kt     # 实际股息到账统计
        │   │   │   │   ├── TransactionRepository.kt        # 交易记录
        │   │   │   │   ├── TradeStrategyRepository.kt      # 全局策略库（含 risksFromJson）
        │   │   │   │   ├── NotificationRuleRepository.kt   # 通知/评估规则
        │   │   │   │   ├── KlineRepository.kt              # 腾讯 K线（fetchCloses/fetchKlines）
        │   │   │   │   ├── BondYieldRepository.kt          # 国债（fetch10YBondYield + fetchAllYields 多期限，2026-08-02 扩展）
        │   │   │   │   ├── FundamentalsCacheRepository.kt  # 基本面 7 天缓存编排
        │   │   │   │   ├── FinancialStatementsRepository.kt  # 财务三表 7 天缓存编排（2026-08-02 新增）
        │   │   │   │   ├── MarketDataRepository.kt         # 资金流/板块/行业内个股/指数/龙虎榜/情绪（2026-08-02 新增）
        │   │   │   │   ├── ResearchRepository.kt           # 研报 + 公告（2026-08-02 新增）
        │   │   │   │   ├── FireGoalRepository.kt
        │   │   │   │   ├── LivingExpenseRepository.kt
        │   │   │   │   ├── AchievementRepository.kt
        │   │   │   │   ├── BackupRepository.kt             # 备份/恢复（事务式批量）
        │   │   │   │   ├── WidgetDataRepository.kt         # 桌面小组件数据
        │   │   │   │   ├── ScreenshotStrategyRepository.kt # 截图策略持久化
        │   │   │   │   ├── GridPlanRepository.kt   # 网格交易计划 CRUD（2026-08-04 新增）
        │   │   │   │   ├── LlmConfigRepository.kt          # LLM 配置（provider/key/url）
        │   │   │   │   ├── LlmAnalysisRepository.kt        # 组合级 LLM 解读编排
        │   │   │   │   ├── LlmAnalysisCacheStore.kt       # LLM 解读缓存读写
        │   │   │   │   ├── EvaluatedStock.kt              # 持仓评估聚合数据结构
        │   │   │   │   ├── UserStrategyRef.kt             # 用户策略引用
        │   │   │   │   ├── PortfolioLlmInput.kt           # 组合 LLM 输入装配
        │   │   │   │   ├── StockLlmInput.kt               # 个股 LLM 输入装配
        │   │   │   │   ├── ScreenshotStrategy.kt          # 截图策略数据结构
        │   │   │   │   ├── LlmConfig.kt / LlmProviderPresets.kt  # LLM 配置数据类
        │   │   │   │   ├── LlmAnalysis.kt / LlmCacheKey.kt
        │   │   │   │   ├── StockLlmAnalysis.kt
        │   │   │   │   ├── JsonExtraction.kt              # LLM 响应 JSON 提取（容错）
        │   │   │   │   │
        │   │   │   │   ├── 纯函数计算器（决策/计算逻辑，配单测，见 §4.4）：
        │   │   │   │   ├── BollCalculator.kt              # 收盘价 → BOLL 带（MA20 ± 2σ）
        │   │   │   │   ├── ForecastCalculator.kt          # 历史分红 → 年均每股 + 预测收入
        │   │   │   │   ├── BuyThresholdCalculator.kt     # 10Y 国债 × 倍数 → 买入价
        │   │   │   │   ├── DividendDiscountCalculator.kt # DDM 估值
        │   │   │   │   ├── DripCalculator.kt     # 分红再投资（DRIP）复利模拟（按年再投，可配置再投价，2026-08-04 新增）
        │   │   │   │   ├── DividendMetricsCalculator.kt  # 分红深度（连续年数/CAGR/稳定性，2026-08-02 新增）
        │   │   │   │   ├── HoldingCalculator.kt          # 摊薄成本法持仓成本（已实现盈亏藏入成本）
        │   │   │   │   ├── RealizedPnlCalculator.kt      # FIFO 已实现盈亏（独立于摊薄成本，A 股法定口径，2026-08-04 新增）
        │   │   │   │   ├── DripCalculator.kt     # 分红再投资（DRIP）复利模拟（按年再投，可配置再投价，2026-08-04 新增）
        │   │   │   │   ├── GridCalculator.kt     # 网格交易档位表（等差网格 + 当前价下一档提示，2026-08-04 新增）
        │   │   │   │   ├── GridAnchorCalculator.kt       # 网格智能锚定（BOLL中轨=基准/上轨=上界/目标股息率=下界，2026-08-04 新增）
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
        │   │   │   │   └── Formatters.kt                 # MoneyFormatter/PercentFormatter（纯函数 + 单测）
        │   │   │   │
        │   │   │   ├── agent/                  # AI Agent（Google ADK，AI Tab）
        │   │   │   │   ├── AiAgentFactory.kt           # 工具注册中心（43 工具：31 读 + 12 写，装配 LlmAgent）
        │   │   │   │   ├── AgentInstructionBuilder.kt  # 系统提示词（注入策略库）
        │   │   │   │   ├── AiChatRepository.kt         # AI 会话编排（流式 SSE）
        │   │   │   │   ├── AiTitleGenerator.kt         # 会话标题生成
        │   │   │   │   ├── ConfirmationSummaryBuilder.kt # 写操作确认摘要
        │   │   │   │   ├── OpenAiCompatibleModel.kt    # ADK Model 适配（OpenAI 兼容协议）
        │   │   │   │   ├── OpenAiProtocol.kt / OpenAiDtos.kt / OpenAiSse.kt  # 协议/DTO/SSE 解析
        │   │   │   │   └── tools/                      # Agent 工具（ReadTool/WriteTool 基类 + 43 个工具）
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
        │   │   │   │       ├── StockActionTools.kt    # 8 个写工具（add_stock/update_holding 等，带确认门）
        │   │   │   │       └── FinanceActionTools.kt   # 5 个 FIRE/支出工具（1 读 + 4 写）
        │   │   │   │
        │   │   │   ├── notification/            # 通知/后台任务（WorkManager）
        │   │   │   │   ├── NotificationRuleEvaluator.kt    # 规则匹配纯函数
        │   │   │   │   ├── NotificationCheckCoordinator.kt # 编排（拉行情→评估→发通知）
        │   │   │   │   ├── NotificationCheckWorker.kt      # WorkManager Worker
        │   │   │   │   ├── NotificationScheduler.kt        # 调度（周期/约束）
        │   │   │   │   ├── DividendAlertNotifier.kt        # 通知发送抽象
        │   │   │   │   ├── AndroidDividendAlertNotifier.kt # Android NotificationManager 实现
        │   │   │   │   ├── NotificationChannels.kt         # 通知渠道
        │   │   │   │   └── VivoPermissionIntents.kt        # vivo 后台保活权限引导
        │   │   │   ├── scan/                     # OCR 截图导入
        │   │   │   │   ├── HoldingScreenshotParser.kt  # 截图 → 持仓结构化
        │   │   │   │   ├── TextRecognitionService.kt   # ML Kit 中文识别
        │   │   │   │   └── BitmapLoader.kt            # 图片加载
        │   │   │   └── widget/                    # 桌面小组件（Glance）
        │   │   │       ├── MarketWidget.kt / MarketWidgetReceiver.kt  # 小组件 UI 与入口
        │   │   │       ├── WidgetActionCallback.kt / WidgetEntryPoint.kt
        │   │   │       └── WidgetUiState.kt
        │   │   │
        │   │   ├── di/                          # Hilt Module
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
        │   │   │   │   ├── DividendRateChart.kt / PriceVolumeChart.kt  # 价量/股息率图（Vico）
        │   │   │   │   ├── BollPriceScale.kt / DividendPriceScale.kt    # BOLL/股息率刻度尺
        │   │   │   │   ├── IndustryAllocationPieChart.kt # 行业配比饼图
        │   │   │   │   ├── FireProgressCard.kt / ForecastComparisonCard.kt
        │   │   │   │   ├── CompanyIcon.kt / CompanyLogoMap.kt  # 公司 logo（Coil3 SVG）
        │   │   │   │   ├── EmptyStateView.kt / CompactTopAppBar.kt / AchievementCard.kt / YearSelector.kt
        │   │   │   └── screen/                   # 各页面 Composable（单 Activity + 多 Composable）
        │   │   │       ├── MainScaffold.kt       # 底部导航骨架（Tab 切换）
        │   │   │       ├── HomeScreen.kt         # 首页（概览）
        │   │   │       ├── PortfolioScreen.kt    # 持仓主页（自选/持仓列表 + 下拉刷新 + FAB）
        │   │   │       ├── StockDetailScreen.kt  # 个股详情（行情/股息/BOLL/评估/AI 解读）
        │   │   │       ├── AiChatScreen.kt       # AI Tab（对话式 Agent）
        │   │   │       ├── AddStockScreen.kt / EditHoldingScreen.kt  # 加股/改持仓
        │   │   │       ├── DividendCalendarScreen.kt  # 股息日历
        │   │   │       ├── DividendValuationScreen.kt # 股息估值
        │   │   │       ├── DripSimulationScreen.kt    # 分红再投（DRIP）复利模拟（2026-08-04 新增）
        │   │   │       ├── PortfolioEvaluationScreen.kt  # 持仓一键评估
        │   │   │       ├── ExpenseCoverageScreen.kt    # 支出覆盖率
        │   │   │       ├── ScreenshotImportScreen.kt / PortfolioImportScreen.kt  # 截图/批量导入
        │   │   │       ├── TransactionHistoryScreen.kt  # 全局交易流水 + 复盘备注（2026-08-04 新增）
        │   │   │       ├── GridPlanScreen.kt    # 网格交易计划（档位表 + 下一档提示，仅计划不下单，2026-08-04 新增）
        │   │   │       ├── TradeStrategyListScreen.kt  # 策略库
        │   │   │       ├── NotificationSettingsScreen.kt / StockNotificationSettingsScreen.kt / NotificationReliabilityScreen.kt
        │   │   │       ├── BackupRestoreScreen.kt / FireGoalSetupScreen.kt / OcrDebugScreen.kt
        │   │   │       └── TabRefreshLocal.kt    # 本地刷新辅助
        │   │   │
        │   │   └── viewmodel/                    # @HiltViewModel + UiState（参考 PortfolioViewModel，见 §4.2）
        │   │       ├── PortfolioViewModel.kt     # 持仓主 VM（多 collector + 派生 Flow）
        │   │       ├── StockDetailViewModel.kt   # 个股详情 VM
        │   │       ├── AiChatViewModel.kt        # AI 会话 VM
        │   │       ├── AddStockViewModel.kt / EditHoldingViewModel.kt
        │   │       ├── DividendCalendarViewModel.kt / DividendValuationViewModel.kt
        │   │       ├── DripSimulationViewModel.kt       # 分红再投模拟（参数可调，纯函数重算，2026-08-04 新增）
        │   │       ├── DividendIncomeViewModel.kt
        │   │       ├── ExpenseCoverageViewModel.kt + ExpenseCoverageCalculator.kt  # 支出覆盖率 VM + 纯函数
        │   │       ├── PortfolioImportViewModel.kt / ScreenshotImportViewModel.kt
        │   │       ├── TradeStrategyListViewModel.kt
        │   │       ├── NotificationSettingsViewModel.kt / StockNotificationSettingsViewModel.kt
        │   │       ├── TransactionHistoryViewModel.kt  # 全局交易流水 + 复盘备注（2026-08-04 新增）
        │   │       ├── GridPlanViewModel.kt   # 网格计划列表 + 生成器（参数实时预览，2026-08-04 新增）
        │   │       ├── FireGoalViewModel.kt / BackupViewModel.kt / OcrDebugViewModel.kt
        │   │       ├── AchievementViewModel.kt + AchievementChecker.kt + AchievementDef.kt + AchievementCategory.kt  # 成就
        │   │       └── MarkdownRenderGuard.kt    # Markdown 渲染安全（防注入）
        │   └── res/                              # 资源（字体 inter.ttf 子集化、图标、字符串等）
        │
        └── test/java/com/stock/dividend/         # 单元测试（包结构与 main 对齐，见 §6）
            ├── data/agent/        # 8 个：AgentInstructionBuilderTest / AiChatRepositoryTest / StockAgentToolsTest（43 工具）等
            ├── data/repository/   # 38 个：纯函数（BollCalculatorTest/BuyThresholdCalculatorTest/DividendMetricsCalculatorTest）
            │                       #    + DTO 解析（QuoteSnapshotTest/MarketDtoParseTest/FinancialStatementDtoParseTest/BondYieldResponseParseTest）
            │                       #    + Repository（StockRepositoryTest/DividendRepositoryTest/Robolectric）
            └── viewmodel/         # 19 个：PortfolioViewModelTest 等（Robolectric + MockK + Turbine）
```

**文件规模速览**（2026-08-04）：main 源集约 135 个 .kt，测试约 72 个 .kt；DB 17 张表/20 个 Migration；AI Agent 43 个工具（31 读 + 12 写）。

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
| `BollCalculator` | 收盘价 → BOLL 带（MA20 ± 2σ） |
| `ForecastCalculator` | 历史分红 → 年均每股 + 预测收入 |
| `DividendDiscountCalculator` | DDM 估值 |
| `BuyThresholdCalculator` | 10Y 国债 × 倍数 → 买入价 |
| `DripCalculator` | 分红再投资（DRIP）复利模拟：按年把分红以可配置再投价买入，对比「再投」与「现金分红」两条路径 |
| `HoldingCalculator` | 摊薄成本法持仓成本（卖出盈亏藏入成本，不独立展示） |
| `RealizedPnlCalculator` | FIFO 已实现盈亏（A 股法定口径，独立于摊薄成本法） |
| `DripCalculator` | 分红再投资（DRIP）复利模拟：按年把分红以可配置再投价买入，对比「再投」与「现金分红」两条路径 |
| `GridCalculator` | 网格（纯买入）档位表：买入区间等分档、1/price 反比分配资金、**无卖出档**、「下一档买」提示 |
| `GridAnchorCalculator` | 网格智能锚定：买入起点=min(日/周 BOLL 下轨, 月 BOLL 中轨)、资金用完位=min(三周期下轨最低, 目标股息率底)、参考上界=月 BOLL 上轨 |
| `LlmPromptBuilder` | 评估数据 → LLM prompt（纯函数 + 降级兜底） |
| `LlmAnalysisParser` | LLM 响应 → 结构化结果 |
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

- **DB version 当前 = 18**，`exportSchema = false`。
- 改 schema（加表/加列/改类型）**必须**：① 在 `AppDatabase` 的 `entities`/`version` 同步；② 新增 `MIGRATION_N_(N+1)` 并在 `DatabaseModule` 注册；③ `version` +1。
- 历史迁移全部手写 `ALTER`/`CREATE`，保持这个风格。
- 表名/列名用下划线（`dividend_income_records`、`stockCode`），实体字段用驼峰，靠 Room 注解映射。

### 4.7 网络约定

- 所有 Retrofit client 在 `di/NetworkModule.kt` 统一装配，**共享 OkHttpClient**（自动注入 `Referer`/`User-Agent` 反爬头）。
- 多数据源用 `@Qualifier` 区分（已有 `EastMoneyDividendApi`、`TencentDividendSource`、`LlmClient`）。LLM 走独立 client（60s 超时）且 `@Url` 动态传 base。
- 数据源：东方财富（搜索 `searchapi`、行情 `push2`、数据中心 `datacenter(-web)`）、腾讯 `web.ifzq.gtimg.cn`（K线/BOLL）、10Y 国债、OpenAI 兼容 LLM。

### 4.8 通知 / 后台任务

- `data/notification/`：`NotificationRuleEvaluator`（规则匹配纯函数）、`NotificationCheckCoordinator`（编排）、`NotificationCheckWorker`（WorkManager）、`NotificationScheduler`。
- 评估门槛（min/boost 股息率）**复用 `notification_rules` 表存储**，避免加表（见迁移 9→10、10→11 历史）。

### 4.9 外部数据接口单位与解析纪律 —— 关键（数据准确性）

> 接入任何行情/财务/资金流等外部数据时必读。本节由 2026-08-02 实践教训沉淀，每条均经实测交叉验证。

**核心原则：单位换算只允许「每10股→每股」与展示格式化（宪法原则 III）；其余裸值→真实值的转换必须在 DTO/解析层显式处理，并配真实 JSON fixture 单测锁定。**

#### 4.9.1 东方财富 push2 三接口的单位规则**互不相同**（最大易错点）

| 接口 | URL | `fltt` 参数 | 单位规则 | 代码位置 |
|---|---|---|---|---|
| `ulist.np/get`（批量行情） | `push2.eastmoney.com/api/qt/ulist.np/get` | 无此参数 | **价格/百分比 ×100 整数，需 ÷100**；成交量(手)/成交额(元)/市值(元) 原值不除 | `QuoteApi` + `toQuoteSnapshot`（`QuoteSnapshot.kt`） |
| `clist/get`（板块/个股/资金流列表） | `push2.eastmoney.com/api/qt/clist/get` | `fltt=2` 时 | **全部字段真实值，不 ÷100**（价格带小数、百分比直接是 %、净额是元） | `MarketApi.getClist` + `toMarketList`（`MarketDataRepository.kt`） |
| `stock/get`（单股/指数详情） | `push2.eastmoney.com/api/qt/stock/get` | 无此参数 | **价格/百分比 ×100 整数，需 ÷100**；成交量(手)/成交额(元) 原值不除 | `QuoteApi.getStockInfo` / `MarketApi.getIndexQuote` |

⚠️ **`clist` 与 `ulist`/`stock/get` 的价格单位规则相反**——`ulist` 的 `f2` 是 ×100 整数（`3962`→39.62 元），`clist` 的 `f2` 是真实值（`127.24`→127.24 元）。两个解析函数**必须独立、切勿复用或混用** ÷100 逻辑。

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

#### 4.9.3 同语义数据要换接口时，先验证「字段完整度」

`stock/get` 对资金流字段（f66/f69/f72/f75 等）**返回不完整**（实测只回 f62/f184/f84 等少数）——个股资金流必须改用 `clist`（`fs=m:{market}+t:2+s:{code}`）才能拿到全套净额/占比。**「字段在 stock/get 存在但为空」≠「数据缺失」，可能是接口对该类字段不支持，要换接口而非反复重试。**

#### 4.9.4 腾讯接口作为交叉验证金标准

腾讯行情返回**直接是真实值**（无 ÷100 问题），是验证东财裸值规则的可靠基准：

- **`qt.gtimg.cn/q=sh600519`**（实时行情）：`v_sh600519="1~贵州茅台~600519~1350.60~..."`，第 4 字段=现价、含涨跌额/涨跌幅/成交额/主力净流入。**接入东货行情前，用腾讯同时刻值交叉验证 ÷100 规则是否正确**（见 `QuoteSnapshotTest` 注释）。
- **`web.ifzq.gtimg.cn/`（fqkline）**：前复权 K 线 + 分红明细，`KlineRepository`/`DividendRepository` 使用。注意：单次上限约 640 交易日（≈2.5 年），覆盖 5 年需按日期窗口分块请求（见 `DividendRepository.fetchAllDividendsFromTencent`）。

#### 4.9.5 解析层实践（强制）

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
