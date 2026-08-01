# 截图策略分析 (Screenshot Strategy) — 设计文档

**日期:** 2026-08-01
**状态:** Draft
**作者:** brainstorming skill
**关联:** `2026-07-29-stock-llm-analysis-design.md`（个股 AI 解读，已实现）、`2026-07-26-llm-analysis-design.md`（组合级 AI 解读，已实现）、`PortfolioImportViewModel`（持仓截图 OCR 导入，已实现）

---

## 1. 背景与目标

### 问题
现有 OCR 基建（`TextRecognitionService` + `HoldingScreenshotParser` + `PortfolioImportViewModel`）只服务于**持仓截图导入**：解析券商持仓页的「名称/股数/成本」结构化表格，落成持仓数据。它**不能**处理用户在日常投资中更常见的另一类信息源——研报、分析师观点、财经新闻、股吧帖子、聊天截图等**自由文本**——从中提炼出**买卖策略**并持久化沉淀。

同时，已有的两个 AI 分析（个股 `StockLlmPromptBuilder`、组合 `LlmPromptBuilder`）只看冷冰冰的股息/BOLL/基本面数据，**不知道用户此前对这只股提取过的策略观点**，解读与用户实际关注的信息源割裂。

### 目标
1. 新增「截图策略分析」：用户从相册选截图 → OCR（复用 `TextRecognitionService`）→ 纯文本喂 LLM → 提取**半结构化买卖策略**（方向 + 理由 + 风险 + 有效期，**不含价格**，规避 LLM 幻觉价格）。
2. **两步 Review**：① OCR 文本可人工修正；② LLM 提取的策略字段全可编辑。降低 OCR/LLM 错误导致的脏数据风险。（策略全局，不关联个股。）
3. 持久化到新 Room 表 `trade_strategies`，提供独立「策略库」列表页（设置页入口）管理（查看/归档/删除）。
4. **回流**：个股 AI 分析与组合 AI 分析触发时，查**全部活跃策略**（全局，不过滤个股），结构化塞入 prompt，作为「用户投资原则」上下文——策略对所有股票生效。`sourceNote`（用户自填来源）**不入 prompt**，仅 DB 存 + 列表页展示。
5. **最大化复用**：OCR 全套、`LlmApi`+`LlmConfigSource`、五态 UI 模型、`JsonExtraction`、设计系统组件、备份机制。**无新依赖、无新 baseUrl、无新 LLM client。**

### 非目标 (YAGNI)
- ❌ 不复用 `HoldingScreenshotParser`（那是结构化持仓表格专用，本场景是自由文本）。
- ❌ 不提取具体买卖价格/目标价（LLM 幻觉风险，半结构化只到「方向+理由」粒度）。
- ❌ 不自动触发 LLM（手动点「AI 提取」，控成本/延迟，与个股 AI 解读一致）。
- ❌ 不做按方向/标的筛选过滤（先 YAGNI，列表足够大再加）。
- ❌ 不做 Compose UI 测试（与个股 AI 解读约定一致）。
- ❌ 不做多轮对话/自由问答/流式输出。
- ❌ 不缓存 OCR 文本/策略草稿到 DB（OCR 文本随策略一并存入 `rawOcrText` 字段，无需独立缓存表）。

### 成功标准
1. 设置页有「策略库」入口 → 列表页 → FAB「从截图添加」→ 导入页，端到端跑通：选图 → OCR → 编辑文本 → AI 提取 → 编辑策略 → 保存 → 列表可见。
2. LLM 提取层（Prompt/Parser）与回流渲染均为**纯函数并配单测**（AGENTS §4.4 / §6）。
3. 两步 Review 的字段编辑（文本/方向/风险/有效期/关联）全部可用。
4. 个股 AI 分析与组合 AI 分析的 prompt 含「用户投资原则」段（全局策略回流）；无策略时渲染「—」，不报错。
5. 网络/DB/LLM 异常全部吞，绝不崩 UI（红线 #2）；loading 态在成功/失败分支都复位（红线 #3）。
6. 新表加 `MIGRATION_15_16` 并 bump version（红线 #1），纳入备份/恢复。
7. 构建 + 全量单测绿。

---

## 2. 关键决策（来自 brainstorming）

| 决策点 | 选择 | 理由 |
|---|---|---|
| 截图内容类型 | **泛化（研报/新闻/股吧/聊天等）** | 用户确认；OCR 出纯文本，LLM 自由提取，提不到标 `NoStrategy` |
| 策略结构 | **半结构化（方向+理由+风险+有效期，无价格）** | 用户确认；规避 LLM 幻觉价格；提取成功率与展示清晰度兼顾 |
| 策略归属 | **全局投资原则，不绑定个股（无 stockCode）** | 用户确认：策略对所有股票生效（如"大盘破3000加仓"）；`targetText` 仅描述语境，不关联自选股 |
| 截图获取 | **仅相册选图（PhotoPicker）** | 与持仓导入一致；股息追踪场景用户多截屏而非拍照 |
| 流程 | **两步 Review（方案 B）** | 用户明确选择；OCR 错字 + LLM 错提均可人工纠正，准确率优先 |
| LLM 依赖 | **严格依赖，复用五态** | 未配置/失败报错可重试，不降级手动填写（手动填写在 Review 步已覆盖） |
| 编排层 | **新 `ScreenshotStrategyRepository`** | 输入是单一 OCR 字符串，与组合级 `List<EvaluatedStock>` 形态不同，遵循「三行相似代码优于不必要抽象层」 |
| 风险点存储 | **JSON 数组字符串** | 与项目「复杂字段序列化为字符串」既有做法一致，避免加关联表 |
| 回流 | **截图策略 → 两个 AI 分析 prompt** | 让 AI 解读呼应用户既有观点；`sourceNote` 不入 prompt |
| 入口 | **设置页「策略库」→ 列表页 FAB → 导入页** | 列表是主视图，导入是动作 |

---

## 3. 数据模型与持久化

### 3.1 新 Room 表 `trade_strategies`（DB v15 → v16）

```kotlin
// data/local/entity/TradeStrategyEntity.kt
@Entity(tableName = "trade_strategies")
data class TradeStrategyEntity(
    @PrimaryKey val id: String,                    // UUID
    val targetText: String,                        // 策略的标的/语境描述（如"招商银行"/"银行业"/"大盘"），仅记录语境，不关联个股
    val direction: String,                         // BUY / SELL / WATCH
    val reasoning: String,                         // 核心理由（LLM 提取，≤200 字）
    val risks: String,                             // 风险点 JSON 数组字符串（["..",".."]），空 "[]"
    val validUntil: String?,                       // ISO 日期 "2026-09-01"；null=长期
    val sourceNote: String?,                       // 来源备注（用户可选填），不入 LLM prompt
    val rawOcrText: String,                        // 原始 OCR 文本，便于回溯
    val status: String = "ACTIVE",                 // ACTIVE / ARCHIVED（用户标记达成/失效）
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

const val STRATEGY_DIRECTION_BUY = "BUY"
const val STRATEGY_DIRECTION_SELL = "SELL"
const val STRATEGY_DIRECTION_WATCH = "WATCH"
const val STRATEGY_STATUS_ACTIVE = "ACTIVE"
const val STRATEGY_STATUS_ARCHIVED = "ARCHIVED"
```

> `direction`/`status` 用字符串常量（与 `NotificationRuleEntity.type` 同风格），不引入枚举映射开销。`risks` 存 JSON 字符串（与项目「复杂字段序列化为字符串」既有做法一致，避免加关联表）。
>
> **策略是全局投资原则，不绑定个股**（无 `stockCode` 字段）：策略对所有股票生效，作为用户整体投资视角沉淀。`targetText` 仅是 LLM 提取的标的/语境描述（如「招商银行」「银行业」「大盘」），用于人类阅读，不作关联键。

### 3.2 DAO（新增 `TradeStrategyDao`）

```kotlin
// data/local/dao/TradeStrategyDao.kt
@Dao
interface TradeStrategyDao {
    @Query("SELECT * FROM trade_strategies ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<TradeStrategyEntity>>

    /** 全部活跃且未过期的策略（回流用，全局，不过滤个股）。 */
    @Query("SELECT * FROM trade_strategies WHERE status = 'ACTIVE' AND (validUntil IS NULL OR validUntil >= :today) ORDER BY createdAt DESC")
    suspend fun activeStrategies(today: String): List<TradeStrategyEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: TradeStrategyEntity)

    @Query("UPDATE trade_strategies SET status = :status, updatedAt = :now WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, now: Long = System.currentTimeMillis())

    @Query("DELETE FROM trade_strategies WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM trade_strategies")
    suspend fun getAllForBackup(): List<TradeStrategyEntity>

    @Query("DELETE FROM trade_strategies")
    suspend fun clear()

    @Insert
    suspend fun insertAll(items: List<TradeStrategyEntity>)
}
```

`activeStrategies` 供全局回流使用，过滤 `ACTIVE` 且未过期（`validUntil IS NULL OR >= today`），不按个股过滤。

### 3.3 Migration（`MIGRATION_15_16`）

```kotlin
val MIGRATION_15_16 = object : Migration(15, 16) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `trade_strategies` (" +
                    "`id` TEXT NOT NULL PRIMARY KEY, " +
                    "`targetText` TEXT NOT NULL, " +
                    "`direction` TEXT NOT NULL, " +
                    "`reasoning` TEXT NOT NULL, " +
                    "`risks` TEXT NOT NULL, " +
                    "`validUntil` TEXT, " +
                    "`sourceNote` TEXT, " +
                    "`rawOcrText` TEXT NOT NULL, " +
                    "`status` TEXT NOT NULL DEFAULT 'ACTIVE', " +
                    "`createdAt` INTEGER NOT NULL, " +
                    "`updatedAt` INTEGER NOT NULL)"
        )
    }
}
```

`AppDatabase` 同步：`entities` 加 `TradeStrategyEntity::class`，`version = 16`，加 `abstract fun tradeStrategyDao(): TradeStrategyDao`；`DatabaseModule.addMigrations(...)` 末尾追加 `MIGRATION_15_16`。

### 3.4 备份（`BackupData` 加字段）

`BackupContainer` 加 `val tradeStrategies: List<TradeStrategyEntity> = emptyList()`（默认空，兼容旧备份）；`BackupRepository` 导出时 `tradeStrategyDao.getAllForBackup()`，导入时 `clear()` + `insertAll()`（与 `notificationRules` 同构处理）。

---

## 4. LLM 提取层（纯函数 + 编排）

### 4.1 输入 / 输出模型（纯数据，无 Android 依赖）

```kotlin
// data/repository/ScreenshotStrategy.kt
data class ScreenshotStrategy(
    val targetText: String,                       // 标的原文片段，必定非空
    val direction: StrategyDirection,             // BUY / SELL / WATCH
    val reasoning: String,                        // 核心理由 ≤200 字
    val risks: List<String>,                      // 风险点列表
    val validUntil: String?                       // ISO 日期 "2026-09-01"，null=长期
) {
    enum class StrategyDirection { BUY, SELL, WATCH }
}

sealed interface ScreenshotStrategyState {        // 与 StockLlmAnalysisState 五态对称 + NoStrategy
    data object Idle : ScreenshotStrategyState
    data object Loading : ScreenshotStrategyState
    data object NotConfigured : ScreenshotStrategyState
    data class Success(val strategy: ScreenshotStrategy) : ScreenshotStrategyState
    data class NoStrategy(val message: String) : ScreenshotStrategyState   // LLM 判定截图无可提取策略
    data class Error(val message: String) : ScreenshotStrategyState
}
```

> 比 `StockLlmAnalysisState` 多一态 `NoStrategy`：泛化截图场景特有，LLM 明确判定「非股票相关/无买卖倾向」时用，避免噪声当策略入库。

### 4.2 Prompt Builder（纯函数）

```kotlin
// data/repository/ScreenshotStrategyPromptBuilder.kt
object ScreenshotStrategyPromptBuilder {
    data class LlmPrompt(val system: String, val user: String)
    fun build(ocrText: String): LlmPrompt
}
```

**system prompt**：
```text
你是一位稳健的中文投资策略整理助手。
【任务】用户给出一截从财经内容（研报/新闻/股吧/聊天等）OCR 出的文本，请提取其中**可执行的买卖策略**。
【输出要求】严格输出 JSON：
{
  "isActionable": true/false,
  "targetText": "涉及的股票名称或代码（原文片段，不确定可合并写）",
  "direction": "BUY" | "SELL" | "WATCH",
  "reasoning": "核心理由≤200字，仅基于原文",
  "risks": ["具体风险点", "..."],
  "validUntil": "YYYY-MM-DD 或 null（无明确期限填 null）"
}
【判定规则】
- 若截图与股票/投资无关、或仅陈述事实无任何买卖倾向 → isActionable=false，其余字段填空/null。
- direction：买入倾向→BUY，卖出/看空→SELL，观望/持有/无明确方向→WATCH。
- reasoning 与 risks 仅据原文归纳，绝不编造数据、价格、财报。
- validUntil：原文有明确到期/止盈时间填日期，否则 null。
【约束】中文；不给具体买卖价格；不复述 OCR 错乱字符。
```

**user message**：`【截图文本】\n<ocrText>`，**不截断**（研报类信息密度高，截断丢结论；成本由手动触发控制）。空 `ocrText` 仍产出合法 prompt（渲染「（空）」）。

### 4.3 Parser（纯函数，永不抛异常）

```kotlin
// data/repository/ScreenshotStrategyParser.kt
object ScreenshotStrategyParser {
    fun parse(rawContent: String): ScreenshotStrategyParseResult
}
sealed interface ScreenshotStrategyParseResult {
    data class Actionable(val strategy: ScreenshotStrategy) : ScreenshotStrategyParseResult
    data object NotActionable : ScreenshotStrategyParseResult     // isActionable=false
    data class Failed(val rawText: String) : ScreenshotStrategyParseResult  // 解析失败兜底
}
```

兜底链：空 → `Failed("")`；`JsonExtraction.extractJsonObject`（**复用**现有去重工具）→ gson 解析；`isActionable=false` → `NotActionable`；`direction` 非法值 → 降级 `WATCH`；`risks` 非 list → 空列表；任一异常 → `Failed(原文)`。

### 4.4 编排（新 `ScreenshotStrategyRepository`，照抄 `LlmAnalysisRepository`）

```kotlin
// data/repository/ScreenshotStrategyRepository.kt
@Singleton
class ScreenshotStrategyRepository @Inject constructor(
    private val llmApi: LlmApi,
    private val configSource: LlmConfigSource,
) {
    suspend fun analyze(ocrText: String): ScreenshotStrategyState
}
```

流程：读 `configSource.observeConfig().first()`；`!isComplete` → `NotConfigured`；`ScreenshotStrategyPromptBuilder.build` → `url = baseUrl.trimEnd('/') + "/chat/completions"` → `llmApi.chatCompletions` → Parser → 映射：`Actionable→Success`，`NotActionable→NoStrategy("未识别到可执行的买卖策略")`，`Failed→Error("LLM 响应解析失败，请重试")`。

错误映射复用 `LlmAnalysisRepository.mapHttpError` 同款逻辑（401/403→"API key 无效"，429→"请求过频，稍后重试"，其它→"分析失败，请重试"，异常→"网络错误，请重试"，空 content→"LLM 返回为空"）。

---

## 5. ViewModel

### 5.1 `ScreenshotImportViewModel`（导入页）

```kotlin
// viewmodel/ScreenshotImportViewModel.kt
@HiltViewModel
class ScreenshotImportViewModel @Inject constructor(
    private val textRecognitionService: TextRecognitionService,
    private val screenshotStrategyRepository: ScreenshotStrategyRepository,
    private val strategyRepository: TradeStrategyRepository,    // 持久化
    @ApplicationContext private val context: Context
) : ViewModel()
```

**Phase 枚举**（扩展既有 `ImportPhase`，加两态）：
```kotlin
enum class ImportPhase {
    Idle, LoadingImage, OcrRunning,
    ReviewOcr,         // 第一步：OCR 文本可编辑
    Analyzing,         // LLM 分析中
    ReviewStrategy,    // 第二步：策略字段可编辑（不关联个股）
    Done, Error
}
```

**UiState**：
```kotlin
@Stable
data class ScreenshotImportUiState(
    val phase: ImportPhase = ImportPhase.Idle,
    val imageUri: String? = null,
    val editableOcrText: String = "",                       // 第一步可编辑
    val analysisError: String? = null,                       // LLM 阶段错误（区别于流程错误）
    val editableStrategy: EditableStrategy? = null,          // 第二步草稿
    val sourceNote: String = "",                             // 第二步可编辑来源
    val errorMessage: String? = null
)

/** 第二步可编辑草稿（字段全可变）。 */
data class EditableStrategy(
    val targetText: String,
    val direction: ScreenshotStrategy.StrategyDirection,
    val reasoning: String,
    val risks: MutableList<String>,
    val validUntil: String?                  // 可清空=长期
)
```

> 策略全局，不关联个股，故无 `matchResult`/`StockMatchResult`。`StockRepository` 也不再注入到本 VM（无需查自选股）。

**流程方法**：

- **`onImagePicked(uri: Uri)`**：`LoadingImage` → `loadSampledBitmap`（复用 `BitmapLoader`）→ `OcrRunning` → `textRecognitionService.recognize` → `editableOcrText = elements.joinToString("\n"){it.text}` → `phase = ReviewOcr`（**停在此步**，不自动进 LLM）。OCR 失败 → `Error("图片识别失败：…")`，不崩。
- **`onOcrTextChanged(text: String)`**：更新 `editableOcrText`（仅 ReviewOcr 阶段）。
- **`startAnalysis()`**（ReviewOcr → Analyzing）：`phase = Analyzing` → `viewModelScope.launch { screenshotStrategyRepository.analyze(editableOcrText) }`：
  - `Success` → 组装 `editableStrategy`（直接用 LLM 返回的 `targetText`/`direction`/`reasoning`/`risks`/`validUntil`）→ `phase = ReviewStrategy`。
  - `NoStrategy` → `analysisError = message`，停在 ReviewOcr（用户可改文本重试或换图）。
  - `Error/NotConfigured` → `analysisError`，停在 ReviewOcr。
- **第二步编辑方法**：`onTargetTextChanged` / `onDirectionChanged` / `onReasoningChanged` / `onRiskChanged(index,text)` / `addRisk()` / `removeRisk(index)` / `onValidUntilChanged` / `onSourceNoteChanged`。（无关联相关方法。）
- **`confirmSave()`**（ReviewStrategy → Done）：组装 `TradeStrategyEntity`（`id=UUID`、`risks=risksToJsonString(editableStrategy.risks)`、`rawOcrText=editableOcrText`、`sourceNote`、`targetText`/`direction`/`reasoning`/`validUntil` 来自草稿）→ `strategyRepository.upsert` → `phase = Done`。失败 → `errorMessage`，停留在 ReviewStrategy 不丢草稿。
- **`resetToIdle()`** / **`backToOcrReview()`**（第二步返回第一步）。

### 5.2 `TradeStrategyListViewModel`（列表页）

```kotlin
@HiltViewModel
class TradeStrategyListViewModel @Inject constructor(
    private val strategyDao: TradeStrategyDao
) : ViewModel() {
    val uiState: StateFlow<TradeStrategyListUiState> =
        strategyDao.observeAll().map { it.map(::toUiItem) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TradeStrategyListUiState())
    fun archive(id: String) { viewModelScope.launch { strategyDao.updateStatus(id, STRATEGY_STATUS_ARCHIVED) } }
    fun delete(id: String) { viewModelScope.launch { strategyDao.delete(id) } }
}
```

`toUiItem`：`risks` JSON 字符串 → `List<String>`（吞解析异常返回空）；`direction` 字符串 → 显示文案 + `FinanceStatusTone`（BUY=绿/SELL=红/WATCH=中性）。

### 5.3 `TradeStrategyRepository`（持久化封装）

```kotlin
// data/repository/TradeStrategyRepository.kt
@Singleton
class TradeStrategyRepository @Inject constructor(
    private val strategyDao: TradeStrategyDao
) {
    suspend fun upsert(entity: TradeStrategyEntity) = runCatching { strategyDao.upsert(entity) }.getOrNull()
    /** 全部活跃且未过期的策略（全局回流，不过滤个股）。 */
    suspend fun activeStrategies(): List<TradeStrategyEntity> =
        runCatching { strategyDao.activeStrategies(todayIso()) }.getOrDefault(emptyList())  // 红线 #2
    private fun todayIso(): String = LocalDate.now().toString()
}
```

---

## 6. 回流：截图策略 → 两个 AI 分析 prompt（全局）

策略是**全局投资原则**（不绑定个股），故回流是「一次查全部活跃策略 → 塞进本次分析的 prompt」，而不是按股过滤。分析任何一只股时，LLM 都看到用户全部的策略原则作为背景。

### 6.1 数据流
```
trade_strategies（全部 ACTIVE 且未过期）
   ↓ 一次查全部（activeStrategies，无参，不过滤个股）
   ↓ map 成 UserStrategyRef（去 sourceNote）
个股/组合 AI 分析触发
   ↓ 作为独立参数传给 PromptBuilder（不入 StockLlmInput/EvaluatedStock 字段）
PromptBuilder 渲染「用户投资原则」段（全局，置于本次分析数据末尾）
   ↓
LLM 综合股息/BOLL/基本面 + 用户全局原则 → 解读
```

> **设计选择**：策略是「分析时的背景上下文」，不是「某只股的属性」。因此**不**给 `StockLlmInput`/`EvaluatedStock` 加 `userStrategies` 字段（那会把全局原则误标成个股属性），而是作为**独立参数**注入两个 PromptBuilder。VM 取一次策略，传给 builder。

### 6.2 领域模型（纯数据）

```kotlin
// data/repository/UserStrategyRef.kt
data class UserStrategyRef(
    val direction: String,       // BUY/SELL/WATCH
    val reasoning: String,
    val risks: List<String>,
    val validUntil: String?,
    val daysAgo: Int             // 距今天数，让 LLM 感知时效
)
```

> **不含 `sourceNote`**（用户确认：来源不入 prompt）。`TradeStrategyEntity → UserStrategyRef` 转换是纯函数 `toUserStrategyRef`，用 `createdAt` 算 `daysAgo`。

### 6.3 `StockLlmPromptBuilder` 扩展（纯函数，加参数）

```kotlin
object StockLlmPromptBuilder {
    data class LlmPrompt(val system: String, val user: String)
    // 新签名：加全局策略参数（默认空，向后兼容现有测试）
    fun build(input: StockLlmInput, userStrategies: List<UserStrategyRef> = emptyList()): LlmPrompt
}
```

**system `【数据语义】` 增补一条**：
```
- 用户投资原则：用户此前从外部内容整理出的整体投资观点，对所有标的通用，属用户个人视角，非客观数据；解读时可对照呼应，但不要盲从或简单复述。
```

**`buildUser` 末尾追加**（纯函数渲染，**不含 sourceNote**）：
```
【用户投资原则（来自截图分析，全局，仅供参照）】
  [买入] 招商银行基本面稳健，ROE 持续>15% (3天前)
    风险: 银行业息差收窄 / 房地产敞口
  [观望] 等股息率回到 5% 以上再考虑 (10天前)
无策略则渲染 "—"。
```

### 6.4 组合级 `LlmPromptBuilder` 同步扩展

```kotlin
object LlmPromptBuilder {
    fun build(
        evaluatedStocks: List<EvaluatedStock>,
        dailyBands: Map<String, BollBand?>,
        monthlyBands: Map<String, BollBand?>,
        signals: PortfolioSignals,
        thresholds: DividendThresholds,
        userStrategies: List<UserStrategyRef> = emptyList(),   // 新增全局参数
    ): LlmPrompt
}
```

**`EvaluatedStock` 不加字段**（策略是全局的，不属个股）。组合 prompt 在「策略信号」段之后追加同款「用户投资原则」整段（全局渲染一次，去 sourceNote）。

### 6.5 VM 集成

- **`StockDetailViewModel.analyzeWithLlm()`**：注入 `TradeStrategyRepository`；组装 `StockLlmInput` 时，先 `val userStrategies = runCatching { strategyRepository.activeStrategies().map { toUserStrategyRef(it) } }.getOrDefault(emptyList())`，调 `StockLlmPromptBuilder.build(input, userStrategies)`。失败吞异常返回空（红线 #2），不阻塞分析。
- **`PortfolioViewModel.analyzeWithLlm()`**：注入 `TradeStrategyRepository`；取一次全局策略 `activeStrategies()`（**不按股**），传给 `LlmPromptBuilder.build(..., userStrategies)`。

---

## 7. UI

### 7.1 `ScreenshotImportScreen`（导入页，新）

复用 `PortfolioImportScreen` 的 PhotoPicker 骨架，内容区按 `phase` 切换：

- **`Idle`**：大按钮「选择截图」。
- **`LoadingImage` / `OcrRunning`**：`CircularProgressIndicator` + 文案。
- **`ReviewOcr`**（第一步）：`OutlinedTextField`（多行）展示 `editableOcrText` 可改；`analysisError` 有则红字提示；底部 `Button("AI 提取策略")` → `startAnalysis()` + `TextButton("重选图片")` → `resetToIdle()`。
- **`Analyzing`**：转圈 + "AI 分析中…"。
- **`ReviewStrategy`**（第二步）：
  - `targetText`：`OutlinedTextField`。
  - `direction`：`SegmentedButton`（买入/卖出/观望三选一）。
  - `reasoning`：多行 `OutlinedTextField`。
  - `risks`：动态列表（每条 `OutlinedTextField` + 删除图标，「+ 添加风险」按钮）。
  - `validUntil`：日期选择器（`DatePickerDialog`），可清空=长期。
  - `sourceNote`：`OutlinedTextField`「来源备注（可选）」。
  - 底部 `Button("保存策略")` → `confirmSave()` + `TextButton("返回重提")` → `backToOcrReview()`。
- **`Done`**：成功提示 + `TextButton("查看策略库")`（导航）+ `TextButton("再分析一张")` → `resetToIdle()`。

LLM 五态渲染模式与 `StockLlmAnalysisSection` 一致（Loading/NotConfigured/Error/Success/NoStrategy）。

### 7.2 `TradeStrategyListScreen`（列表页，新）

- `TopAppBar`「策略库」+ 返回。
- `LazyColumn`：每条 `Card`，方向 `StatusPill`（`FinanceStatusTone`：BUY=绿/SELL=红/WATCH=中性）置顶角；标的文本、理由摘要、有效期、来源、创建时间分行；点击展开全文 + 风险列表 + 操作（归档/删除）。
- 空状态 `EmptyStateView`「暂无策略，点 + 从截图添加」。
- `FloatingActionButton`「＋」→ 跳 `ScreenshotImportScreen`。

### 7.3 导航与入口

`AppNavigation.kt` `Routes` 加：
```kotlin
const val SCREENSHOT_IMPORT = "screenshotImport"
const val TRADE_STRATEGY_LIST = "tradeStrategyList"
```
注册两个 `composable`。

**入口**：设置页（`SettingsScreen` 或对应文件）加一项「策略库」→ `TRADE_STRATEGY_LIST`。列表页 FAB → `SCREENSHOT_IMPORT`。

---

## 8. DI

- `DatabaseModule` 加 DAO provider：
  ```kotlin
  @Provides fun provideTradeStrategyDao(db: AppDatabase): TradeStrategyDao = db.tradeStrategyDao()
  ```
- `ScreenshotStrategyRepository` / `TradeStrategyRepository` 是 `@Singleton @Inject constructor`，Hilt 自动装配。复用 `LlmApi` + `LlmConfigSource`（已装配）。
- **零新 module、零新 baseUrl、零新依赖。**

---

## 9. 测试策略（TDD）

JUnit4 + Truth + MockK + kotlinx-coroutines-test（+ Robolectric for VM）。

### 9.1 纯函数（快）
- **`ScreenshotStrategyPromptBuilderTest`**：system 含 JSON schema/约束；user 含 OCR 原文不截断；空 OCR 仍产出合法 prompt。
- **`ScreenshotStrategyParserTest`**：完整 JSON→`Actionable`（四字段正确）；`isActionable=false`→`NotActionable`；`direction` 非法值→降级 `WATCH`；`risks` 非 list→空列表；`fenced`/裸/畸形/空→`Failed` 不抛异常。
- **`risksJsonCodecTest`**：`risksToJsonString`/`risksFromJson`（`List<String>↔JSON 字符串` 互转）；畸形字符串解析返回空。
- **`toUserStrategyRefTest`**（纯函数）：`TradeStrategyEntity → UserStrategyRef` 转换；`daysAgo` 由 `createdAt` 正确计算；`sourceNote` 不进入 ref。

### 9.2 回流渲染（扩展现有纯函数测试）
- **`StockLlmPromptBuilderTest` 扩展**：传 `userStrategies` 参数→user 段渲染方向/理由/风险/时效；**断言不含 sourceNote**；无策略→渲染"—"；system 段含「用户投资原则」语义条。
- **`LlmPromptBuilderTest` 扩展**：传 `userStrategies` 参数→渲染全局「用户投资原则」整段（一次，不按股）；**断言不含 sourceNote**。

### 9.3 ViewModel（Robolectric + MockK）
- **`ScreenshotStrategyRepositoryTest`**：配置缺失→`NotConfigured`；401→`Error("API key 无效")`；空 content→`Error("LLM 返回为空")`；`NotActionable`→`NoStrategy`；成功→`Success`。
- **`ScreenshotImportViewModelTest`**：`onImagePicked`→OCR→停 ReviewOcr；`startAnalysis` 成功→ReviewStrategy（直接用 LLM 返回字段，无匹配）；`NoStrategy`→停 ReviewOcr 报错；第二步编辑→`confirmSave`→持久化（断言 entity 字段，**无 stockCode**）；OCR 失败→Error 不崩。
- **`TradeStrategyListViewModelTest`**：`observeAll` 渲染；`archive`/`delete` 调 DAO。
- **`TradeStrategyRepositoryTest`**：`activeStrategies`（无参，全局）过滤 ACTIVE + 未过期 + 倒序；异常返回空。
- **`StockDetailViewModelTest` 扩展**：`analyzeWithLlm` 触发时 `activeStrategies`（全局）被调用、结果经 `toUserStrategyRef` 传给 `StockLlmPromptBuilder.build(input, userStrategies)`；查策略失败仍正常出分析。
- **`PortfolioViewModelTest` 扩展**：组合级取一次全局策略，传给 `LlmPromptBuilder.build(..., userStrategies)`。
- **`BackupRepositoryTest` 扩展**：新表纳入导出/导入 round-trip。

### 9.4 不写
- Compose UI 测试（与个股 AI 解读约定一致）；真实 LLM/PhotoPicker 调用（手动验证）。

---

## 10. 文件改动清单

### 新增
- `data/local/entity/TradeStrategyEntity.kt`（+ 方向/状态常量）
- `data/local/dao/TradeStrategyDao.kt`
- `data/repository/ScreenshotStrategy.kt`（结果模型 + `ScreenshotStrategyState` sealed）
- `data/repository/ScreenshotStrategyPromptBuilder.kt`（纯函数）
- `data/repository/ScreenshotStrategyParser.kt`（纯函数）
- `data/repository/ScreenshotStrategyRepository.kt`（编排）
- `data/repository/TradeStrategyRepository.kt`（持久化封装 + 回流查询）
- `data/repository/UserStrategyRef.kt`（回流纯数据）
- `viewmodel/ScreenshotImportViewModel.kt` + `EditableStrategy` + 扩展 `ImportPhase`（无 `StockMatchResult`，不注入 `StockRepository`）
- `viewmodel/TradeStrategyListViewModel.kt`
- `ui/screen/ScreenshotImportScreen.kt`
- `ui/screen/TradeStrategyListScreen.kt`
- 测试：`ScreenshotStrategyPromptBuilderTest`、`ScreenshotStrategyParserTest`、`risksJsonCodecTest`、`toUserStrategyRefTest`、`ScreenshotStrategyRepositoryTest`、`ScreenshotImportViewModelTest`、`TradeStrategyListViewModelTest`、`TradeStrategyRepositoryTest`，并扩展 `StockLlmPromptBuilderTest`/`LlmPromptBuilderTest`/`StockDetailViewModelTest`/`PortfolioViewModelTest`/`BackupRepositoryTest`

### 修改
- `data/local/AppDatabase.kt` — `entities` 加 `TradeStrategyEntity`，`version=16`，加 `MIGRATION_15_16`，加 `tradeStrategyDao()`。
- `di/DatabaseModule.kt` — 注册 `MIGRATION_15_16`，加 `TradeStrategyDao` provider。
- `data/local/backup/BackupData.kt` — `BackupContainer` 加 `tradeStrategies` 字段。
- `data/repository/BackupRepository.kt` — 导出/导入加 `trade_strategies`。
- `data/repository/StockLlmPromptBuilder.kt` — `build` 加 `userStrategies` 参数；system 语义 + user 渲染「用户投资原则」段（无 sourceNote）。
- `data/repository/LlmPromptBuilder.kt` — `build` 加 `userStrategies` 参数；组合级「策略信号」后追加全局「用户投资原则」整段（无 sourceNote）。
- `viewmodel/StockDetailViewModel.kt` — `analyzeWithLlm` 注入 `TradeStrategyRepository`，取全局策略传给 `StockLlmPromptBuilder.build(input, userStrategies)`。
- `viewmodel/PortfolioViewModel.kt` — `analyzeWithLlm` 注入 `TradeStrategyRepository`，取一次全局策略传给 `LlmPromptBuilder.build(..., userStrategies)`。
- `ui/navigation/AppNavigation.kt` — `Routes` 加两条 + 注册两个 composable。
- 设置页对应文件 — 加「策略库」入口项。

### 不动
- `StockLlmInput` / `EvaluatedStock` — **不加 `userStrategies` 字段**（策略全局，作 builder 独立参数而非个股属性）。
- `LlmApi` / `LlmChatRequest` / `LlmChatResponse` / `LlmConfigRepository` / `LlmProviderPresets` / `LlmConfig` / `NetworkModule`（全复用）。
- `TextRecognitionService` / `MlKitTextRecognitionService` / `BitmapLoader` / `OcrElement`（全复用，OCR 用纯文本模式，**不复用 `HoldingScreenshotParser`**）。
- `HoldingScreenshotParser` / `PortfolioImportViewModel` / `PortfolioImportScreen`（持仓导入流程独立，本设计不动）。
- `JsonExtraction`（复用）。
- 组合级与个股级 LLM 编排 Repository（`LlmAnalysisRepository` 不动；截图分析用独立 `ScreenshotStrategyRepository`）。

---

## 11. 红线自查

| 红线 | 本设计如何遵守 |
|---|---|
| #1 schema 改必加 Migration | `MIGRATION_15_16` 手写 CREATE TABLE，`version=16`，`DatabaseModule` 注册 |
| #2 网络/DB 异常必须吞 | `analyze` try/catch 映射 Error；OCR 失败映射 Error 态；`activeStrategies`/`upsert` 包 `runCatching{}.getOrNull/getOrDefault`，不崩 UI |
| #3 isLoading 必须复位 | `phase` 在成功/失败分支都收敛到非 loading 态（ReviewOcr/ReviewStrategy/Done/Error） |
| #4 自选股数据订阅 | 本设计不订阅 `allStocksFlow`（策略全局，不查自选股）；红线 #4 的原始场景（价格刷新）不涉及 |
| #5 并发限流 | 回流取策略是单次 DB 查询（非网络批量），无需 Semaphore |
| #6 纯函数不带 Android 依赖 | Prompt/Parser/UserStrategyRef/ScreenshotStrategy/toUserStrategyRef/risksJsonCodec 均纯 Kotlin |
| #7 不换算东财数据 | 不涉及东财数据（LLM 输入是用户截图文本 + 已有股息/BOLL 透传） |
| #8 Release 签名环境变量 | 不涉及 |
| #9 依赖版本只改 toml | 无新依赖（OCR/LLM/PhotoPicker/DatePicker 全复用） |
| #10 中文界面 | 文案/prompt 全中文 |

---

## 12. 风险与未决

- **LLM 把噪声截图当策略**：用 `NoStrategy` 态 + `isActionable=false` 判定兜底；两步 Review 给用户最后把关。
- **OCR 错字率高**：第一步 Review 让用户改文本；LLM prompt 约束「不复述 OCR 错乱字符」。
- **回流 prompt 膨胀**：全局策略全部进 prompt，策略多时 token 上涨。`activeStrategies` 已过滤 ACTIVE+未过期；用户可归档过期策略减负；后续真膨胀可加上限（如取最近 N 条）。
- **策略全局的边界**：策略对所有股票生效是用户明确诉求，但 LLM 可能对无关标的生硬套用某条策略（如把"银行业"策略套到科技股）。system prompt 已约束「对照呼应，不要盲从」，属可接受语义风险。
- **DatePicker/SegmentedButton 最低 SDK**：`minSdk=24`；`SegmentedButton`（M3 1.3.1）与 `DatePicker` 均兼容，需确认 Compose BOM 2024.12.01 下 API 稳定（实现阶段验证）。
