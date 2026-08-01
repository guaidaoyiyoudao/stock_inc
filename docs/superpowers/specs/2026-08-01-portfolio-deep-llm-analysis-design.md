# 组合级 AI 解读增强（深度数据 + 双缓存）— 设计文档

**日期:** 2026-08-01
**状态:** Draft
**作者:** brainstorming skill
**关联:**
- `2026-07-26-llm-analysis-design.md`（组合级 AI 解读，已实现）
- `2026-07-29-stock-llm-analysis-design.md`（个股 AI 解读，已实现）
- `2026-07-31-stock-fundamentals-design.md`（个股基本面数据，工作区已实现未提交）

---

## 1. 背景与目标

### 问题

现有组合级 AI 解读（一键评估 → AI 解读）只喂规则评估结果：BOLL 三周期位置、股息率、控仓/共振信号、门槛、用户投资原则。**个股详情页已有的深度数据**（基本面 ROE/负债率/营收净利同比/派息率、1/3/5 年预测、买入线达标）没有进组合 prompt，导致组合解读只能看"位置"，看不到"质地"。

同时两个数据获取/分析链路存在浪费：
- `StockRepository.fetchFundamentals` **无缓存**，每次进详情页或评估组合都重复打 2 次东财接口（季报级慢变数据，不该反复拉）。
- LLM 解读结果**无缓存**，同一组合/同一只股在输入不变时反复点击会重复花钱；断网时也无法查看历史解读。

### 目标

1. 组合级 AI 解读喂入每股深度数据：基本面 + 1/3/5 年预测 + 买入线达标，输出升级为「每股简评 ≤60 字 + 每股风险点列表」。
2. 基本面数据落 Room 缓存（7 天 TTL + 手动刷新），个股详情页与组合评估共用，离线可看。
3. LLM 解读结果落 Room 缓存（prompt 哈希做 key + 24h TTL），组合级与个股级共用，命中直接显示，断网可看。
4. 个股 AI 编排从 `StockDetailViewModel` 迁入 `LlmAnalysisRepository`（新增 `analyzeStock`），VM 不再直接碰 `LlmApi`。

### 非目标 (YAGNI)

- ❌ 不做多轮对话 / 自由问答 / 流式输出（沿用既有一次性解读模式）。
- ❌ 不加密缓存 payload 与 API key（沿用现有 SharedPreferences 明文级别，属另一条 spec 线路）。
- ❌ 不把缓存表纳入备份（可再生成，`BackupData` 不动）。
- ❌ 不做 LLM 结果自动失效的定时任务（TTL + prompt 哈希已覆盖实际需求）。
- ❌ 不写 Compose UI 测试（与既有 spec 一致）。
- ❌ 不让 LLM 替代数值计算（宪法原则 III 不变）。

### 成功标准

1. 一键评估结果页的 AI 解读包含每股深度数据背景，每股输出 brief（≤60 字）+ risks 列表。
2. 基本面第二次读取不再打网络（命中 Room 缓存）；7 天后自动过期重拉；失败回退旧缓存。
3. 相同 prompt 二次点击 AI 解读直接命中缓存（不发 LLM 请求）；「重新分析」可 forceRefresh。
4. 断网时：有缓存的基本面与 LLM 解读均可展示。
5. prompt/parser/缓存 key 均为纯函数并配单测；DB v16→v17 迁移完整；全量单测绿。

---

## 2. 关键决策（来自 brainstorming）

| 决策点 | 选择 | 理由 |
|---|---|---|
| 扩展方向 | 组合级深度解读（单入口增强） | 复用一键评估 → AI 解读链路，避免双入口双 UI（YAGNI） |
| 数据深度 | 全量：基本面 + 1/3/5 年预测 + 买入线 | 与个股详情页口径对齐，解读从"看位置"升级到"看质地" |
| 基本面缓存 | Room 表 + 7 天 TTL + 手动刷新 | 季报级慢变数据；用户明确要求；离线优先 |
| LLM 结果缓存 | Room 表 + prompt 哈希 key + 24h TTL + forceRefresh | 用户明确要求；输入一变自动 miss；断网可看 |
| 输出深度 | 每股 brief ≤60 字 + risks 列表 | 比 40 字一句话有质量，又不像个股四字段那样让组合页过重 |
| 深度数据拉取时机 | 点「AI 解读」时才拉 | 一键评估保持现有速度；首次分析稍慢可接受，之后全走缓存 |
| 实现路径 | 方案一：扩展现有链路 | 改动集中，缓存第一天就是完整形态 |
| 个股编排 | 迁入 `LlmAnalysisRepository.analyzeStock` | 与组合编排共享 LlmApi/config/缓存，VM 依赖更干净 |
| 缓存 key | SHA-256(system + user prompt) | prompt 由全部输入序列化而来，输入一变 key 必变 |
| 缓存表备份 | 不入备份 | 可再生成，避免备份体积膨胀 |

---

## 3. 数据模型

### 3.1 组合级深度输入快照（纯数据）

```kotlin
// data/repository/PortfolioLlmInput.kt
data class PortfolioLlmInput(
    val evaluation: List<EvaluatedStock>,
    val dailyBands: Map<String, BollBand?>,
    val monthlyBands: Map<String, BollBand?>,
    val signals: PortfolioSignals,
    val thresholds: DividendThresholds,
    val userStrategies: List<UserStrategyRef> = emptyList(),
    /** 每股深度数据；缺失的股票无 key（prompt 渲染 "—"）。 */
    val stockDetails: Map<String, PortfolioLlmStockDetail> = emptyMap(),
)

/** 单股深度数据：只放组合级缺的三项；位置/股息率/action 已在 [EvaluatedStock] + bands 中。 */
data class PortfolioLlmStockDetail(
    val fundamentals: Fundamentals? = null,
    val forecast: StockLlmInput.StockLlmForecast? = null,
    val buyThreshold: StockLlmInput.StockLlmBuyThreshold? = null,
)
```

设计要点：`StockLlmForecast` / `StockLlmBuyThreshold` 直接复用 `StockLlmInput` 内嵌类型，不重复建模；组合行已有的字段（action/股息率/三周期位置）不进此类型。

### 3.2 输出模型

```kotlin
// data/repository/LlmAnalysis.kt（修改）
/** 组合级每股解读。 */
data class StockLlmComment(
    val brief: String,        // ≤60 字
    val risks: List<String>,  // 该股具体风险点
)

data class LlmAnalysis(
    val overview: String,
    val stockComments: Map<String, StockLlmComment>,  // 由 Map<String, String> 升级
    val risks: List<String>,
)

sealed interface LlmAnalysisState {
    ...
    data class Success(
        val analysis: LlmAnalysis,
        val analyzedAt: Long? = null,   // epoch ms；null=旧状态（兼容）
        val fromCache: Boolean = false,
        val notice: String? = null,     // 如「刷新失败，显示上次分析结果」
    ) : LlmAnalysisState
}
```

Repository 层返回类型同样携带元数据，VM 直接透传：

```kotlin
sealed interface LlmAnalysisResult {
    data class Success(
        val analysis: LlmAnalysis,
        val analyzedAt: Long? = null,
        val fromCache: Boolean = false,
        val notice: String? = null,
    ) : LlmAnalysisResult
    data object NotConfigured : LlmAnalysisResult
    data class Error(val message: String) : LlmAnalysisResult
}

/** 新增：与 [LlmAnalysisResult] 对称。 */
sealed interface StockLlmAnalysisResult {
    data class Success(
        val analysis: StockLlmAnalysis,
        val analyzedAt: Long? = null,
        val fromCache: Boolean = false,
        val notice: String? = null,
    ) : StockLlmAnalysisResult
    data object NotConfigured : StockLlmAnalysisResult
    data class Error(val message: String) : StockLlmAnalysisResult
}
```

`StockLlmAnalysisState.Success` 同样增加 `analyzedAt` / `fromCache` / `notice`（带默认值，兼容现有构造与测试），VM 从 `StockLlmAnalysisResult` 映射。

### 3.3 缓存表（Room）

```kotlin
// fundamentals_cache
@Entity(tableName = "fundamentals_cache")
data class FundamentalsCacheEntity(
    @PrimaryKey val stockCode: String,
    val payload: String,   // Fundamentals 的 Gson JSON
    val fetchedAt: Long,
)

// llm_analysis_cache
@Entity(tableName = "llm_analysis_cache")
data class LlmAnalysisCacheEntity(
    @PrimaryKey val cacheKey: String,   // SHA-256 hex
    val scope: String,                  // PORTFOLIO / STOCK
    val payload: String,                // LlmAnalysis / StockLlmAnalysis 的 Gson JSON
    val createdAt: Long,
)
```

设计要点：`Fundamentals` 的 periods 是变长对象列表，用 JSON payload 一行一股票，避免建子表；缓存只整体读写，不需要 SQL 过滤字段。`LlmAnalysisCacheEntity.scope` 用于读取时按类型反序列化。

---

## 4. 组件设计

### 4.1 `FundamentalsCacheRepository`（新增）

```kotlin
@Singleton
class FundamentalsCacheRepository @Inject constructor(
    private val fundamentalsCacheDao: FundamentalsCacheDao,
    private val stockRepository: StockRepository,
) {
    suspend fun getFundamentals(stockCode: String, forceRefresh: Boolean = false): Fundamentals?
}
```

行为（全部 `runCatching` 包裹，红线 #2）：
1. `!forceRefresh` 且缓存新鲜（`now - fetchedAt < TTL`，TTL=7 天）→ 直接返回缓存。
2. 过期/缺失/forceRefresh → `stockRepository.fetchFundamentals(code)`；成功 → 写缓存并返回；失败 → 有旧缓存则返回旧缓存，无则 null。

### 4.2 `LlmAnalysisCacheStore` + 缓存 key（新增）

```kotlin
@Singleton
class LlmAnalysisCacheStore @Inject constructor(
    private val llmAnalysisCacheDao: LlmAnalysisCacheDao,
) {
    suspend fun get(cacheKey: String, scope: String): LlmAnalysisCacheEntity?
    suspend fun put(cacheKey: String, scope: String, payload: String)
}

/** 纯函数：SHA-256(system + "\n" + user) → hex；不抛异常（编码失败降级为空串）。 */
object LlmCacheKey {
    fun of(system: String, user: String): String
}
```

新鲜判定（TTL=24h）由调用方（`LlmAnalysisRepository`）统一做，store 只负责读写。

### 4.3 `LlmAnalysisRepository` 扩展（修改）

新增/修改两个编排方法，共享同一缓存流程：

```kotlin
suspend fun analyze(input: PortfolioLlmInput, forceRefresh: Boolean = false): LlmAnalysisResult
suspend fun analyzeStock(input: StockLlmInput, userStrategies: List<UserStrategyRef> = emptyList(), forceRefresh: Boolean = false): StockLlmAnalysisResult
```

统一流程：
1. 构造 prompt（复用两个 PromptBuilder）。
2. `key = LlmCacheKey.of(system, user)`。
3. 查缓存：命中且新鲜（≤24h）且非 forceRefresh → `Success(payload, analyzedAt=createdAt, fromCache=true)`。
4. 配置不完整 → `NotConfigured`（缓存已优先查过，未配置也能看历史缓存）。
5. 调 LLM → 解析 → 写缓存 → `Success(fromCache=false, analyzedAt=now)`。
6. 请求失败：不写缓存；若 forceRefresh 且存在旧缓存 → 回退旧值 + `notice="刷新失败，显示上次分析结果"`；否则 `Error(message)`。

个股编排从 `StockDetailViewModel` 迁入此处（`analyzeStock`），VM 删除 `LlmApi`/`LlmConfigSource` 直连代码，改为注入 `LlmAnalysisRepository`。

### 4.4 `PortfolioViewModel` 装配（修改）

- `forecastMapFlow` 扩展：每股在现有计算基础上顺带算 1/3/5 年预测（循环 `ForecastCalculator.calculateForecastIncome(dividends, shares, years)`，纯本地零网络），写入 `StockForecast.llmForecast: StockLlmInput.StockLlmForecast?`（占位股为 null）。
- 注入 `FundamentalsCacheRepository` 与 `BondYieldRepository`。
- `analyzeWithLlm(forceRefresh: Boolean = false)`：
  - 每股并行（`Semaphore(3)`，与 BOLL 同纪律）组装 `PortfolioLlmStockDetail`：
    - `fundamentals`：`fundamentalsCacheRepository.getFundamentals(code, forceRefresh)`；
    - `forecast`：`stockForecasts[code]?.llmForecast`；
    - `buyThreshold`：`computeBuyThreshold(bondYield, multiplier, latestYearlyCashPerShare, currentPrice)`（国债走 `fetch10YBondYield()`，已有 24h 缓存；每股倍数取 stock 实体字段；数据缺失 → null）。
  - 任一失败 → null → prompt 渲染 "—"，不阻塞。
  - 组装 `PortfolioLlmInput` 调 `llmAnalysisRepository.analyze(input, forceRefresh)`。

### 4.5 Prompt 构造（纯函数，修改 `LlmPromptBuilder`）

`build` 签名改为接收 `PortfolioLlmInput`（内部取现有字段）。每股行在原有位置/股息率后追加**要点式**深度数据（控制 token）：

- 基本面：最新一期 ROE / 负债率 / 营收净利同比 / 派息率 + 分红方案（如有），以及「近 N 期整体上升/下降/平稳」趋势一句；缺失渲染 "—"。
- 预测：1/3/5 年均每股 + 实际样本年数。
- 买入线：目标股息率 + 当前股息率 + 已达标/未达标/无法判定。

system 提示词补充约束：深度数据缺失时解读不得臆测；每股 risks 必须具体。

### 4.6 响应解析（纯函数，修改 `LlmAnalysisParser`）

`stockComments` 值兼容两种形态：
- 对象 `{"brief": "...", "risks": [...]}` → 新结构；
- 字符串（旧模型输出）→ `StockLlmComment(brief = 字符串, risks = emptyList())`；
- 其他/缺失 → 默认空。

其余字段（overview/risks）解析逻辑不变。

### 4.7 UI（修改）

**PortfolioEvaluationScreen**
- `EvaluationCard` 接收 `StockLlmComment?`：渲染 brief + 风险点列表（复用现有 `•` 小号样式）。
- `LlmAnalysisSection` Success 态：头部行加「分析时间（MM-dd HH:mm）· 来自缓存/实时」+「重新分析」按钮（forceRefresh）；`notice` 非空时显示提示文案。
- Loading 文案：「正在拉取深度数据并分析…」（首次可能稍慢）。

**StockDetailScreen**
- AI 解读区块同样加「分析时间 · 来自缓存」+「重新分析」按钮（forceRefresh），渲染 `StockLlmAnalysisState.Success` 新字段。

---

## 5. 数据流

**组合级（一键评估 → AI 解读）：**

```
评估完成 → 点「AI 解读」
  → 每股并行（Semaphore(3)）：
      基本面: fundamentals_cache 命中? → 直接用 : 东财 2 接口 → 写缓存（失败回退旧缓存/—）
      预测:   StockForecast.llmForecast（本地）
      买入线: 国债缓存 + 每股倍数 + 现价 → computeBuyThreshold（本地）
  → PortfolioLlmInput → build prompt → SHA-256
  → llm_analysis_cache 命中且新鲜? → Success(fromCache=true)
  → 未命中 → LLM → parser → 写缓存 → Success(fromCache=false)
  → 失败 → 不写缓存；forceRefresh 时回退旧缓存
```

**个股级（详情页 AI 解读）：**

```
点「AI 解读」→ StockLlmInput（现有装配，fundamentals 走缓存仓库）
  → build prompt → SHA-256 → 缓存命中? → Success(fromCache=true)
  → 未命中 → LLM → parser → 写缓存 → Success(fromCache=false)
```

---

## 6. 错误处理

| 场景 | 行为 |
|---|---|
| 基本面网络失败，有旧缓存 | 返回旧缓存（详情页秒开缓存即此路径） |
| 基本面网络失败，无缓存 | 返回 null，prompt 渲染 "—"，不阻塞分析 |
| LLM 请求失败（普通触发） | 不写缓存，返回 Error（不覆盖旧缓存） |
| LLM 请求失败（forceRefresh） | 有旧缓存 → 回退旧值 + notice「刷新失败，显示上次分析结果」；无缓存 → Error |
| LLM 未配置 | NotConfigured（缓存命中时优先返回缓存，未配置也能看历史结果） |
| prompt 编码/哈希异常 | 缓存 key 降级为空串 → 视为未命中，走正常调用（不崩溃） |
| 缓存反序列化失败 | 视为未命中，正常调用后重新写入 |

---

## 7. 测试策略

### 7.1 纯函数（JUnit4 + Truth，快）

- `LlmPromptBuilderTest`（扩展）：深度数据渲染、缺失 "—" 降级、旧参数行为不回归。
- `LlmAnalysisParserTest`（扩展）：新对象结构、旧字符串兼容、字段缺失补默认。
- `LlmCacheKeyTest`（新增）：同输入同 key、不同输入不同 key、空输入不抛异常。

### 7.2 Repository（MockK / fake DAO）

- `FundamentalsCacheRepositoryTest`（新增）：命中/过期/forceRefresh/失败回退/无缓存。
- `LlmAnalysisRepositoryTest`（扩展）：缓存命中不发请求、未命中调用并写缓存、forceRefresh 绕过、失败不写缓存、失败回退旧缓存 + notice、NotConfigured 时仍可读缓存。
- `analyzeStock` 编排测试（同文件扩展）。

### 7.3 ViewModel（Robolectric + MockK）

- `PortfolioViewModelTest`（扩展）：analyzeWithLlm 装配深度数据（mock 缓存仓库）、forceRefresh 透传、失败降级。
- `StockDetailViewModelTest`（修改/扩展）：改为经 `LlmAnalysisRepository.analyzeStock`，缓存仓库注入 mock。

### 7.4 不写测试

- Compose UI 测试（与既有 spec 一致）。

---

## 8. 文件改动清单

### 新增（main）

- `data/local/entity/FundamentalsCacheEntity.kt`
- `data/local/entity/LlmAnalysisCacheEntity.kt`
- `data/local/dao/FundamentalsCacheDao.kt`
- `data/local/dao/LlmAnalysisCacheDao.kt`
- `data/repository/FundamentalsCacheRepository.kt`
- `data/repository/LlmAnalysisCacheStore.kt`（含 `LlmCacheKey`）
- `data/repository/PortfolioLlmInput.kt`（含 `PortfolioLlmStockDetail`）

### 新增（test）

- `data/repository/FundamentalsCacheRepositoryTest.kt`
- `data/repository/LlmCacheKeyTest.kt`

### 修改

- `data/local/AppDatabase.kt`：entities 注册 + version 16→17 + `MIGRATION_16_17`（建两表）
- `di/DatabaseModule.kt`：注册 `MIGRATION_16_17`
- `data/repository/LlmAnalysis.kt`：`StockLlmComment` + `stockComments` 类型 + Success 新字段
- `data/repository/StockLlmAnalysis.kt`：新增 `StockLlmAnalysisResult`（Success 带元数据）+ State Success 新字段
- `data/repository/LlmAnalysisParser.kt`：新结构 + 旧字符串兼容
- `data/repository/LlmPromptBuilder.kt`：接收 `PortfolioLlmInput` + 深度数据渲染
- `data/repository/LlmAnalysisRepository.kt`：新签名 + 缓存 + `analyzeStock`
- `viewmodel/PortfolioViewModel.kt`：注入 + 装配 + forceRefresh
- `viewmodel/StockDetailViewModel.kt`：迁至 `analyzeStock` + 基本面走缓存仓库
- `ui/screen/PortfolioEvaluationScreen.kt`：每股 brief+risks、时间/缓存标记、重新分析
- `ui/screen/StockDetailScreen.kt`：时间/缓存标记、重新分析
- 既有相关测试（Parser/PromptBuilder/LlmAnalysisRepository/PortfolioViewModel/StockDetailViewModel）

### 不动

- `BackupData.kt` / `BackupRepository`（缓存不进备份）
- `LlmConfig` / `LlmConfigRepository` / `LlmProviderPresets`
- `LlmApi` / `LlmChatRequest` / `LlmChatResponse`
- `FundamentalApi` / `Fundamentals` / `FundamentalsBuilder`（网络与解析不动）
- `BondYieldRepository`（已有缓存，不动）

---

## 9. 风险与未决

1. **prompt 体积**：持仓 >15 只时每股深度数据会使 prompt 明显变大、总评质量可能下降。当前手动触发、单次可接受，不特殊处理（YAGNI）。
2. **缓存 key 敏感性**：key 含 prompt 全文哈希，任何输入变化（含格式微调）都会 miss——这是特性不是缺陷，保证不返回过期解读。
3. **缓存明文**：payload 明文存 Room，与 API key 的 SharedPreferences 明文同级；加密属另一条 spec 线路，不在本次范围。
4. **工作区前置依赖**：基本面/国债/买入线代码目前处于未提交状态（`Fundamentals.kt`、`BondYieldRepository.kt`、`BuyThresholdCalculator.kt` 等），实现本 spec 前需确认这些改动完成且测试绿。
