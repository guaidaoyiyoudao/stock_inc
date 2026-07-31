# 个股基本面数据 (Stock Fundamentals) — 设计文档

**日期:** 2026-07-31
**状态:** Draft
**作者:** brainstorming skill
**关联:** `2026-07-29-stock-llm-analysis-design.md`（个股 AI 解读，已实现）、`2026-07-26-llm-analysis-design.md`（组合级 AI 解读，已实现）

---

## 1. 背景与目标

### 问题
现有 LLM 能力（见 `2026-07-29-stock-llm-analysis-design.md`，已实现）在 `StockDetailScreen` 输出含「**分红可持续性**」字段的解读，但其输入 `StockLlmInput` **只含分红数据 + 三周期 BOLL**（见 `StockLlmInput.kt`）。分红历史无法回答可持续性——决定分红能否持续的是盈利能力（ROE）、成长性（营收/净利同比）、杠杆（资产负债率）与派息率（分红/盈利）。当前 AI 在缺乏这些数据时只能泛泛而谈，是旗舰功能最明显的短板。

### 目标
1. 从东方财富数据中心（`RPT_LICO_FN_CPD`）拉取单股**近 5 期**财务摘要，拿到 ROE / 资产负债率 / 营收同比 / 净利同比 / 基本 EPS，并据此计算**派息率**（EPS_DIV ÷ BASIC_EPS）。
2. 在 `StockDetailScreen` 新增「基本面」卡片，**进页面即展示**（独立于 AI 解读），让用户直接看到基本面趋势。
3. 把基本面数据喂给已配置的 LLM，让「分红可持续性」字段有真实依据；**prompt builder / system prompt 扩展为纯函数 + 单测**。
4. **最大化复用**：datacenter-web 调用模式、共享 OkHttpClient、五态 UI 模型、`StockLlmAnalysisParser`、设计系统组件。**无新 DB 表、无新 baseUrl、无新依赖。**

### 非目标 (YAGNI)
- ❌ 不缓存基本面到 DB（财报每季度一更，每次现拉成本极低；加表要 Migration 违反 YAGNI）。
- ❌ 不做多股/组合级基本面（组合级 LLM 输入是 `List<EvaluatedStock>`，形态不同，本设计只增强个股级）。
- ❌ 不让 LLM 替代数值计算（宪法原则 III）：ROE/派息率等全部由纯函数算出喂给 LLM，LLM 只解读。
- ❌ 不缓存 AI 解读结果（沿用个股 AI 解读现有约定，每次按需重算）。
- ❌ 不做 Compose UI 测试（与个股 AI 解读一致）。

### 成功标准
1. `StockDetailScreen` 出现基本面卡片，进页面自动加载，三态（加载中/空/成功）健壮。
2. 5 个基本面指标（ROE / 负债率 / 营收同比 / 净利同比 / 派息率）的拉取、解析、派息率计算均为**纯函数并配单测**。
3. `StockLlmInput` 新增 `fundamentals` 字段；`StockLlmPromptBuilder` 扩展为纯函数 + 单测；输出 schema 与 parser **零改动**复用。
4. 网络/解析失败吞异常返回 null（红线 #2），绝不崩 UI；`fundamentalsLoading` 成功/失败都复位（红线 #3）。
5. 构建 + 全量单测绿。

---

## 2. 关键决策（来自 brainstorming）

| 决策点 | 选择 | 理由 |
|---|---|---|
| 拉取期数 | 近 **5 期**（A 股每年 4 报告期，≈1.25 年） | 单点 ROE 无意义，趋势才能判可持续性；5 期够看趋势且不过度增加 token |
| 指标集 | ROE / 资产负债率 / 营收同比 / 净利同比 / 派息率（5 项） | AI 判断「贵/便宜 + 可持续」的核心依据；一个 DTO 全拿到，边际信息递减 |
| 数据源 | 东财 `RPT_LICO_FN_CPD`（主要财务指标） | 字段名已交叉验证；复用现有 datacenter-web 模式，零新依赖 |
| 派息率 | 计算 `EPS_DIV ÷ BASIC_EPS` | 东财无直接字段；EPS_DIV 复用现有股息接口，BASIC_EPS 在财务摘要里 |
| 缓存策略 | **不缓存（每次现拉）** | 财报低频；加表 Migration 成本 > 收益 |
| 基本面展示时机 | **进页面即独立展示**（方案 b） | 基本面有独立价值，不该藏在 AI 解读后；用户决策 |
| 卡片位置 | 「分红率趋势」之后、「AI 解读」之前 | 逻辑顺序：过去的分红 → 支撑分红的盈利 → 综合解读 |
| 趋势表默认期数 | 默认 **3 期**，可展开全部 5 期 | 小屏体验；3 期够看趋势，5 期全显过长 |
| 数据模型 | `Fundamentals` 提升为**顶层领域模型**（非 `StockLlmInput` 嵌套） | 同时服务 UI 展示 + LLM 输入，不再仅是 LLM 内部结构 |
| AI 复用 | `analyzeWithLlm()` 直接读 `_uiState.fundamentals`，不重复拉 | 零重复请求；加载未完则降级 null |

---

## 3. 数据层

### 3.1 网络（新增 `FundamentalApi`，照抄 `DividendApi`）

```kotlin
// data/remote/FundamentalApi.kt
interface FundamentalApi {
    @GET("api/data/v1/get")
    suspend fun getFundamentals(
        @Query("reportName") reportName: String = "RPT_LICO_FN_CPD",
        @Query("columns") columns: String = "ALL",
        @Query("filter") filter: String,                     // (SECURITY_CODE="000001")
        @Query("sortColumns") sortColumns: String = "REPORTDATE",
        @Query("sortTypes") sortTypes: String = "-1",        // 最新期在前
        @Query("pageSize") pageSize: String = "5",           // 近 5 期
        @Query("pageNumber") pageNumber: String = "1",
        @Query("source") source: String = "WEB",
        @Query("client") client: String = "WEB"
    ): FundamentalResponse
}
```

### 3.2 DTO（`data/remote/dto/FundamentalResponse.kt`）

复用东财 datacenter 响应壳（与 `DividendResponse` 同构）：`{ result, success, code, message }`，`result.data: List<Item>`。每个 `Item` 字段（已交叉验证）：

| 领域含义 | 东财字段 | 可空 |
|---|---|---|
| 报告期 | `REPORTDATE` | 否 |
| 加权 ROE（%） | `WEIGHTAVG_ROE` | 是 |
| 资产负债率（%） | `DEBT_ASSET_RATIO` | 是 |
| 营收同比（%） | `YSTZ` | 是 |
| 净利同比（%） | `SJLTZ` | 是 |
| 基本 EPS（元） | `BASIC_EPS` | 是 |

> 派息率（payoutRatio）由纯函数计算（见 3.4），DTO 不含此字段。EPS_DIV（每股派息）来自现有股息接口的对应报告期。

### 3.3 DI（`NetworkModule` 复用，零新 baseUrl/client）

```kotlin
@Qualifier @Retention(AnnotationRetention.BINARY)
annotation class EastMoneyFundamentalApi

@Provides @Singleton @EastMoneyFundamentalApi
fun provideFundamentalApi(client: OkHttpClient): FundamentalApi {
    return Retrofit.Builder()
        .baseUrl(DATA_BASE_URL)            // 复用 datacenter-web
        .client(client)                     // 复用共享 client（Referer/UA 已注入）
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(FundamentalApi::class.java)
}
```

### 3.4 领域模型与纯函数（新建 `data/repository/Fundamentals.kt`）

```kotlin
/** 单股基本面（近 N 期）；纯数据，无 Android 依赖。 */
data class Fundamentals(
    /** 升序（旧→新），便于趋势判断与渲染。 */
    val periods: List<Period>
) {
    data class Period(
        val reportDate: String,          // "2024-12-31"
        val roe: Double?,                // 加权净资产收益率 %
        val debtToAssetRatio: Double?,   // 资产负债率 %
        val revenueYoy: Double?,         // 营收同比 %
        val netProfitYoy: Double?,       // 净利同比 %
        val payoutRatio: Double?         // 派息率 %（enrichPayoutRatio 填充；初始 null）
    )
}

/**
 * DTO → Fundamentals 解析（纯函数，带单测）。
 * 只解析 ROE/负债率/营收净利同比；payoutRatio 在此为 null，
 * 由 [enrichPayoutRatio] 用股息接口的 EPS_DIV 补全（职责分离，避免 Repository 交叉依赖）。
 */
object FundamentalsBuilder {
    /**
     * @param items 东财返回的财务摘要项（无序，按 REPORTDATE 排序后保留最新 maxN 期）
     * @param maxN 最多保留期数，默认 5
     */
    fun build(
        items: List<FundamentalResponse.Item>,
        maxN: Int = 5
    ): Fundamentals?
}

/**
 * 用股息接口的 EPS_DIV 补全派息率（纯函数，带单测）。
 * @param fundamentals Repository 返回的原始基本面（payoutRatio 字段会被覆盖）
 * @param cashPerShareByDate 各报告期 → 每股派息映射（VM 已持有的股息数据）
 */
fun enrichPayoutRatio(
    fundamentals: Fundamentals,
    cashPerShareByDate: Map<String, Double>
): Fundamentals
```

**派息率计算规则**（纯函数，配单测）：`payoutRatio = EPS_DIV ÷ BASIC_EPS × 100`；`BASIC_EPS` 缺失/为 0/为负，或该报告期无对应 EPS_DIV → `payoutRatio = null`（不臆造）。

### 3.5 Repository（`StockRepository` 加方法）

> **职责分离**：`fetchFundamentals` 只负责拉财务摘要并解析，**不依赖 `DividendRepository`**（避免 Repository 间交叉注入）。派息率的补全由 VM 用已持有的股息数据经纯函数 `enrichPayoutRatio` 完成（见 §6.3）。

```kotlin
suspend fun fetchFundamentals(code: String): Fundamentals? {
    return runCatching {
        val securityCode = code.substringAfter(".")
        val filter = """(SECURITY_CODE="$securityCode")"""
        val resp = fundamentalApi.getFundamentals(filter = filter)
        FundamentalsBuilder.build(resp.result?.data.orEmpty())
    }.getOrNull()   // 红线 #2：失败吞异常返回 null
}
```

---

## 4. LLM 扩展（纯函数 + 单测）

### 4.1 `StockLlmInput` 加字段

```kotlin
data class StockLlmInput(
    // ... 现有字段不动 ...
    val fundamentals: Fundamentals?      // 新增，可空；缺失渲染 "—"
)
```

### 4.2 `StockLlmPromptBuilder` 扩展

**system prompt `【数据语义】` 增补**：
```
- ROE%：净资产收益率，反映赚钱效率，持续下滑是分红可持续性的危险信号
- 资产负债率%：越高杠杆越大，>70% 需警惕（行业差异大，结合行业判断）
- 营收/净利同比%：正负与趋势反映成长性，持续负增长会侵蚀分红能力
- 派息率%：分红/盈利，>80% 或持续上升而盈利不增，分红可能不可持续
```

`【输出要求】` 中 `dividendSustainability` 字段提示升级为「**结合 ROE/派息率/成长性趋势**」。

**`buildUser` 末尾追加**（纯函数渲染）：
```
【基本面（近5期）】
  2023-12-31: ROE 11.2% / 负债率 62% / 营收+8% / 净利+5% / 派息率 30%
  2024-03-31: ROE 9.8% / 负债率 65% / 营收+3% / 净利-2% / 派息率 35%
  ...（最新）
缺失指标或整段缺失渲染 "—"。
```

### 4.3 Parser 零改动

输出 schema 不变 `{valuation, dividendSustainability, action, risks[]}`，**`StockLlmAnalysisParser` 与 `JsonExtraction` 完全复用**，无新增。

---

## 5. UI 层

### 5.1 卡片位置与结构

插在「分红率趋势」item 之后、「AI 解读」item 之前（`StockDetailScreen.kt` LazyColumn）。逻辑顺序：分红数据（过去）→ 基本面（支撑未来的盈利）→ AI 综合解读。

### 5.2 `FundamentalsCard` Composable（私有，就近放于 `StockDetailScreen.kt`）

**视觉**（严格复用设计系统组件）：
```
┌─ SectionHeader("基本面（近 N 期）") ─ actionText="更新" ──┐
│  Card (listCardColors, ListPadding)                       │
│                                                            │
│  最新期突出行（大字 FinanceMetric 横排）：                   │
│    24Q4 · ROE 11.2% · 负债率 62% · 派息率 30%              │
│    营收同比 +8%（绿）  净利同比 -2%（红）  ← 财务正负色       │
│                                                            │
│  趋势小表（默认 3 期，actionText"展开全部"切 5 期）：        │
│    期次   ROE    负债率   营收    净利    派息率             │
│    24Q2  12.0%   60%    +10%   +7%    28%                │
│    24Q3  11.5%   61%    +9%    +6%    29%                │
│    24Q4  11.2%   62%    +8%    +5%    30%   ← 最新         │
└────────────────────────────────────────────────────────────┘
```

**组件选用**：容器 `Card(AppCardDefaults.listCardColors())`；指标用 `FinanceMetric`；同比正负色用 `valueColor = FinanceGreen/FinanceRed`；趋势表 `Column{ Row{} }`（YAGNI，不上第三方表格库）。

### 5.3 三态（红线 #2/#3）

- **加载中**（`fundamentalsLoading=true`）：显示卡片骨架 + "加载中…"，不隐藏卡片。
- **空/失败**（`fundamentals=null && loading=false`）：占位 "暂无基本面数据"。
- **成功**：渲染上述结构。

### 5.4 纯函数支持（配单测）

- `Fundamentals.formatPeriod(date): String` — "2024-12-31" → "24Q4"。
- `Fundamentals.trend(metric): Trend` — 某指标趋势方向（升/降/平/不足）。

---

## 6. ViewModel 编排

### 6.1 `StockDetailUiState` 加两字段

```kotlin
val fundamentals: Fundamentals? = null,
val fundamentalsLoading: Boolean = true,
```

### 6.2 独立 collector 加载（AGENTS §4.2 多独立 collector 约定）

```kotlin
init {
    // ... 现有 combine(stock, dividends) collector 不动 ...

    // 新增：独立加载基本面，与分红 collector 解耦
    viewModelScope.launch {
        val result = runCatching { stockRepository.fetchFundamentals(stockCode) }.getOrNull()
        _uiState.value = _uiState.value.copy(
            fundamentals = result,
            fundamentalsLoading = false   // 成功/失败都复位（红线 #3）
        )
    }
}
```

### 6.3 派息率补全 + AI 解读复用（零重复请求）

**派息率补全**：基本面 collector（§6.2）拿到 Repository 返回的 `Fundamentals`（payoutRatio 为 null）后，用 VM 已订阅的 `dividendsFlow` 数据经纯函数 `enrichPayoutRatio` 补全，再写入 `uiState.fundamentals`。这保证无论 UI 展示还是 LLM 输入，用的都是补全后的数据。

**AI 解读复用**：`analyzeWithLlm()` 中 `buildStockLlmInput(...)` 直接从 `_uiState.value.fundamentals` 取（已含派息率），**删掉原方案 a 的第 4 个 async**。AI 触发时若基本面未加载完 → `fundamentals=null`，AI 照常出（可持续性段标注数据不足），属可接受降级。`buildStockLlmInput` 签名无需改（fundamentals 从 state 取，非新参数）。

> 注：派息率补全需要基本面与股息数据**都已就绪**。collector 内可用简单策略：基本面回来时若 `dividendsFlow` 还无数据，先存原始（payoutRatio=null）；`dividendsFlow` 后续回填时再次 `enrichPayoutRatio` 刷新。两种顺序都收敛到补全态，不阻塞 UI。

### 6.4 新增 `refreshFundamentals()`

手动刷新：重置 `fundamentalsLoading=true` → 重新拉 → 复位。供卡片 "更新" action 调用。

---

## 7. 测试

### 7.1 纯函数（JUnit4 + Truth，快）
- `FundamentalsBuilderTest`：DTO 解析、REPORTDATE 排序、maxN 截断（保留最新 N 期）、整段缺失返回 null；payoutRatio 在此阶段恒为 null（由 `enrichPayoutRatio` 负责）。
- `enrichPayoutRatioTest`：BASIC_EPS 缺失/为 0/为负 → null；报告期无对应 EPS_DIV → null；正常计算 `EPS_DIV ÷ BASIC_EPS × 100`；不影响其他指标字段。
- `FundamentalsTest`（`formatPeriod` / `trend`）：报告期格式化、趋势判定（升/降/平/样本不足）。
- `StockLlmPromptBuilderTest`：扩展现有测试——含 fundamentals 时 user 段渲染 5 期、缺失时渲染 "—"；system 段含新增语义。
- `StockLlmAnalysisParserTest`：**无需改动**（schema 未变）。

### 7.2 ViewModel（Robolectric + MockK）
- 扩展 `StockDetailViewModelTest`：mock `fetchFundamentals` 返回正常/抛异常/null，断言 `fundamentals`/`fundamentalsLoading` 状态流转；`analyzeWithLlm` 触发时 `_uiState.fundamentals` 被正确塞入 `StockLlmInput`。

### 7.3 不写 Compose UI 测试
与个股 AI 解读现有约定一致。

---

## 8. 实现顺序（建议）

1. 纯函数先行：`Fundamentals.kt` + `FundamentalsBuilder` + 单测（可独立验证）。
2. 网络层：`FundamentalApi` + DTO + `NetworkModule` qualifier。
3. Repository：`StockRepository.fetchFundamentals`（吞异常）。
4. LLM 扩展：`StockLlmInput` 加字段 + `StockLlmPromptBuilder` 扩展 + 单测。
5. VM：`StockDetailUiState` 字段 + 独立 collector + `analyzeWithLlm` 复用 + `refreshFundamentals` + 测试。
6. UI：`FundamentalsCard` + 三态 + 插入 LazyColumn + actionText 展开/更新。

---

## 9. 红线自查

| 红线 | 本设计如何遵守 |
|---|---|
| #1 schema 改必加 Migration | **无 DB 变更**，不触发 |
| #2 网络/DB 异常必须吞 | `fetchFundamentals` 包 `runCatching{}.getOrNull()`，返回 null 不崩 UI |
| #3 isLoading 必须复位 | `fundamentalsLoading` 在成功/失败分支都置 false |
| #6 纯函数不带 Android 依赖 | `Fundamentals`/`FundamentalsBuilder`/`formatPeriod`/`trend` 均纯 Kotlin，可进纯 JVM 单测 |
| #7 不对东财原始数据换算 | 仅「派息率 = EPS_DIV ÷ BASIC_EPS」属派生指标（非换算东财字段语义），与项目「每10股→每股」同性质；其余指标原样透传 |
| #9 依赖版本只改 toml | 无新依赖，不触发 |
| #10 中文界面 | 卡片文案、prompt 全中文 |
