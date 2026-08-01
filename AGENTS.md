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
| 本地存储 | Room (SQLite) | 2.6.1，**当前 DB version = 15** |
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

```text
app/
├── build.gradle.kts              # 应用模块配置（SDK、签名、依赖）
└── src/
    ├── main/
    │   ├── AndroidManifest.xml
    │   ├── java/com/stock/dividend/
    │   │   ├── MainActivity.kt           # 唯一 Activity
    │   │   ├── StockDividendApp.kt        # @HiltAndroidApp，WorkManager 初始化
    │   │   ├── data/
    │   │   │   ├── local/
    │   │   │   │   ├── AppDatabase.kt     # Room DB + 所有 Migration（version=15）
    │   │   │   │   ├── dao/               # XXXDao 接口
    │   │   │   │   ├── entity/            # @Entity（含常量如 EXPENSE_PERIOD_MONTHLY）
    │   │   │   │   └── backup/BackupData.kt
    │   │   │   ├── remote/
    │   │   │   │   ├── *Api.kt            # Retrofit 接口
    │   │   │   │   └── dto/               # 网络 DTO（东财/腾讯/LLM）
    │   │   │   ├── repository/            # Repository + 纯函数计算器
    │   │   │   ├── notification/          # 通知规则评估、Worker、调度
    │   │   │   └── scan/                  # OCR 截图解析
    │   │   ├── di/                        # Hilt Module（Network/Database/Notification/Ocr）
    │   │   ├── ui/
    │   │   │   ├── component/             # 可复用 Composable + DesignSystem.kt
    │   │   │   ├── screen/                # 各页面 Composable
    │   │   │   ├── navigation/AppNavigation.kt  # 路由表（Routes object）
    │   │   │   └── theme/                 # Color/Shape/Theme/Type
    │   │   └── viewmodel/                 # @HiltViewModel + UiState data class
    │   └── res/
    └── test/java/com/stock/dividend/      # 单元测试，包结构与 main 对齐
gradle/libs.versions.toml         # Version Catalog：依赖版本唯一来源
docs/superpowers/                 # 设计文档（design + plan，superpowers 工作流产出）
.github/workflows/                # android.yml（CI 构建）+ release.yml（打 v* 标签发版）
```

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

- **DB version 当前 = 15**，`exportSchema = false`。
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

---

## 9. 常用入口文件速查

| 想做什么 | 先看 |
|---|---|
| 理解整体架构 | `MainActivity.kt` → `ui/navigation/AppNavigation.kt` → `ui/screen/MainScaffold.kt` |
| 持仓/评估主流程 | `viewmodel/PortfolioViewModel.kt` + `data/repository/HoldingRecommender.kt` / `PortfolioAdvisor.kt` |
| 加一张数据表 | `data/local/AppDatabase.kt`（Migration）+ `dao/` + `entity/` + `di/DatabaseModule.kt` |
| 加一个网络接口 | `data/remote/*Api.kt` + `dto/` + `di/NetworkModule.kt` |
| 加一个页面 | `ui/screen/XxxScreen.kt` + `viewmodel/XxxViewModel.kt` + 注册到 `AppNavigation.kt` |
| 复用 UI 样式 | [`DESIGN.md`](DESIGN.md) + `ui/component/AppComponents.kt`（新组件）/ `DesignSystem.kt`（历史）+ `ui/theme/` |
| 通知/后台 | `data/notification/` + `StockDividendApp.kt`（WorkManager） |

---

## 10. 变更记录

- 2026-07-29：重写本文件，从自动生成的稀薄摘要升级为面向 AI agent 的完整开发指南（技术栈/架构/约定/命令/测试/红线/速查）；移除 spec-kit 工作流章节及所有相关引用。
- 2026-08-01：新增 `DESIGN.md` 设计系统文档（双主题/Inter 字体/核心组件/格式化器）；§4.5 改为引用该文档；落地基建：`AppComponents.kt`（AppCard/AmountText/PercentText 等）+ `Formatters.kt`（MoneyFormatter/PercentFormatter + 26 单测）+ `Gradient.kt`（CompositionLocal 扩展主题）+ 双主题（亮/暗）+ Inter 可变字体（子集化 210KB，含 tnum）。
