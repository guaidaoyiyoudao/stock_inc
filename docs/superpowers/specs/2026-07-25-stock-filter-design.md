# 股票筛选与删除行业配置栏 — 设计文档

- **日期**：2026-07-25
- **目标**：删除「行业配置栏」UI 区块；让持仓/自选股支持按「行业」与「自定义标签」筛选显示
- **方案**：方案 A —— 标签独立多对多表 + 双维度 AND FilterChip 横滚条

---

## 1. 背景与动机

当前 `PortfolioScreen` 把持仓页分成三大块：

1. 摘要（年股息预测 / FIRE 进度 / 持仓汇总）
2. **行业配置**（折叠头 + 饼图 + 行业卡片列表 + 编辑行业目标占比对话框）
3. 个股持仓 + 自选关注

「行业配置」区块让用户为每个行业设目标占总资产 %，行业内个股再设目标占行业 %，叠成两层权重模型。用户反馈这套目标权重不实用，**希望整块移除**；同时希望像选股器那样，**按各种分类筛着看**自己的持仓与自选股。

由此本次改造两件事：

- **删除**：行业配置栏的全部 UI（折叠头、刷新按钮、饼图、行业卡片、编辑对话框）
- **新增**：在持仓列表顶部加一行 FilterChip 横滚条，支持按「行业」「标签」两个维度筛选

---

## 2. 范围

### 2.1 In scope

- 移除 `PortfolioScreen.kt` 中行业配置相关 UI 区块
- 移除行业配置区块专用的局部 state（`industryExpanded`、相关 `EditIndustryDialog` 调用）
- 新增「标签」数据层（实体 / DAO / Repository 方法 / DB migration v13→v14）
- 在 `EditHoldingScreen` 增加标签编辑区（`FlowRow` + 输入弹窗）
- 在 `PortfolioScreen` 个股区块顶部加 FilterChip 横滚条（行业组 + 标签组，组内 OR、跨组 AND）
- `PortfolioViewModel` 暴露 `availableIndustries`、`availableTags`、`selectedIndustries`、`selectedTags`、`filteredItems`、`filteredWatchlist`

### 2.2 Out of scope（明确不做）

- **不动** `IndustryTargetEntity` / `IndustryTargetDao` / `stockRepository.observeIndustryTargets()` / `getIndustryTargets()` / `updateIndustryTarget()` / `deleteIndustryTarget()` / `refreshIndustries()` / `fetchAndCacheIndustry()`。这些数据层保留，未来可复用（且 `industry` 字段在卡片副标题上仍要显示，所以行业数据获取链路不能断）。
- 不删除 `IndustryAllocationPieChart.kt`（虽然不再被引用，但保留以备未来复用；编译器不报 unused file）。
- 不删 `PortfolioUiState` 上的 `industryGroups` / `industryTargetSum` / `editingIndustry*` / `isRefreshingIndustry` 等字段（保留以缩小改动面、避免连带重构 ViewModel）。仅删除 UI 引用。
- 不引入"市场/股息率区间"等其他维度（已和用户对齐：本次只做行业 + 标签）。
- 不持久化筛选状态（已和用户对齐：默认全选/不筛，每次进入页面都是全量）。

### 2.3 兼容性

- DB version 13 → 14，新增 `MIGRATION_13_14`（CREATE TABLE `stock_tags`），并加入 `DatabaseModule.addMigrations(...)`。
- `app/build.gradle.kts` `versionCode` 5→6、`versionName` 3.0.2→3.1.0（minor：新增功能 + UI 移除）。

---

## 3. 数据层设计

### 3.1 新增实体 `StockTagEntity`

```kotlin
// data/local/entity/StockTagEntity.kt
@Entity(
    tableName = "stock_tags",
    primaryKeys = ["stockCode", "tag"],
    foreignKeys = [ForeignKey(
        entity = StockEntity::class,
        parentColumns = ["code"],
        childColumns = ["stockCode"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("stockCode"), Index("tag")]
)
data class StockTagEntity(
    val stockCode: String,   // sh.600036
    val tag: String,         // "高息"、"白马"…
    val createdAt: Long = System.currentTimeMillis()
)
```

设计要点：

- **复合主键 `(stockCode, tag)`**：天然防重复（同一只股不会被贴两次同一标签）。
- **FK CASCADE**：股票被删除时其标签自动清除（与 `transactions`、`dividends` 一致），无需 ViewModel 显式清理。
- **`tag` 加索引**：列出"所有出现过的标签"和"某标签下所有股票"都要按 tag 查询。
- **不加 `tag` 唯一约束表**：标签本身没有元数据（颜色/排序），用 `SELECT DISTINCT tag` 即可枚举，YAGNI。

### 3.2 新增 DAO `StockTagDao`

```kotlin
// data/local/dao/StockTagDao.kt
@Dao
interface StockTagDao {
    @Query("SELECT * FROM stock_tags")
    fun observeAll(): Flow<List<StockTagEntity>>

    @Query("SELECT * FROM stock_tags WHERE stockCode = :code")
    fun observeByStock(code: String): Flow<List<StockTagEntity>>

    @Query("SELECT DISTINCT tag FROM stock_tags ORDER BY tag")
    fun observeAllTags(): Flow<List<String>>

    @Query("SELECT tag FROM stock_tags WHERE stockCode = :code")
    suspend fun getTagsForStock(code: String): List<String>

    @Query("SELECT DISTINCT tag FROM stock_tags ORDER BY tag")
    suspend fun getAllTags(): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(tag: StockTagEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(tags: List<StockTagEntity>)

    @Query("SELECT * FROM stock_tags")
    suspend fun getAll(): List<StockTagEntity>

    @Query("DELETE FROM stock_tags")
    suspend fun deleteAll()

    @Query("DELETE FROM stock_tags WHERE stockCode = :code AND tag = :tag")
    suspend fun delete(stockCode: String, tag: String)

    /** 全量覆盖某只股票的标签集合（编辑页保存时调用）。 */
    @Query("DELETE FROM stock_tags WHERE stockCode = :code")
    suspend fun clearForStock(code: String)
}
```

### 3.3 `AppDatabase` 改动

```kotlin
@Database(
    entities = [ ..., StockTagEntity::class ],
    version = 14,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    ...
    abstract fun stockTagDao(): StockTagDao

    val MIGRATION_13_14 = object : Migration(13, 14) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `stock_tags` (" +
                "`stockCode` TEXT NOT NULL, " +
                "`tag` TEXT NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`stockCode`, `tag`), " +
                "FOREIGN KEY(`stockCode`) REFERENCES `stocks`(`code`) ON UPDATE NO ACTION ON DELETE CASCADE)"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_stock_tags_stockCode` ON `stock_tags`(`stockCode`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_stock_tags_tag` ON `stock_tags`(`tag`)")
        }
    }
}
```

`DatabaseModule` 在 `.addMigrations(...)` 末尾追加 `AppDatabase.MIGRATION_13_14`，并注入 `StockTagDao`。

### 3.4 `StockRepository` 新增方法

```kotlin
fun observeAllTags(): Flow<List<String>> = stockTagDao.observeAllTags()
fun observeTagsForStock(code: String): Flow<List<String>> =
    stockTagDao.observeByStock(code).map { list -> list.map { it.tag } }

/** 编辑页保存时：先 clear，再批量 insert。 */
suspend fun setStockTags(code: String, tags: List<String>) {
    appDatabase.withTransaction {
        stockTagDao.clearForStock(code)
        tags.distinct().forEach { tag ->
            stockTagDao.insert(StockTagEntity(stockCode = code, tag = tag.trim()))
        }
    }
}
```

`StockRepository` 构造函数新增 `private val stockTagDao: StockTagDao`。

### 3.5 Backup 兼容性（已确认：必须改）

`BackupRepository` + `BackupContainer` 是**显式逐表枚举**做导出/导入的（已核对源码：`BackupRepository.kt:45-79` export、`:105-125` import），不是扫全库。因此新增 `stock_tags` 表必须同步加入备份链路，否则标签既不会被导出，也不会被恢复：

- `BackupContainer` 新增字段 `val stockTags: List<StockTagEntity> = emptyList()`（默认空，向后兼容旧备份文件）。
- `BackupRepository.exportToJson`：加 `val stockTags = async { stockTagDao.getAll() }`，写入 container。
- `BackupRepository.importFromJson`：在 `stockDao.insertAll(container.stocks)` **之后**插入 `stockTagDao.insertAll(container.stockTags)`（依赖 stocks 先存在以满足 FK）；删表顺序里加 `stockTagDao.deleteAll()`（紧跟 `stockDao.deleteAll()` 之前其实会被 FK CASCADE 自动清，但显式 deleteAll 更安全、避免旧库 FK 未生效情况）。
- `BackupRepository` 构造函数注入 `private val stockTagDao: StockTagDao`。
- 旧备份文件（无 `stockTags` 字段）→ Gson 反序列化为默认空 list，导入后该设备无标签，符合预期。

---

## 4. UI 层设计

### 4.1 删除：`PortfolioScreen.kt` 行业配置区块

移除以下代码段（参考当前 `PortfolioScreen.kt:141, 181-254, 364-373`）：

- 局部变量 `var industryExpanded by remember { mutableStateOf(true) }`（行 141）
- 行业配置折叠头 item（含 `Icon` + "行业配置" + 刷新按钮 / `CircularProgressIndicator`，行 182-223）
- `industryExpanded` 内的提示语 item（行 224-232）
- `IndustryAllocationPieChart` 包装 Card item（行 233-244）
- 行业卡片 `items(uiState.industryGroups) { IndustryAllocationCard(...) }`（行 245-253）
- `if (uiState.editingIndustry != null) { EditIndustryDialog(...) }`（行 364-373）

同时移除：

- 不再使用的 import：`IndustryAllocationPieChart`、`animateFloatAsState`、`rotate`、`KeyboardArrowDown`（如果只被行业区块用到——需 grep 确认 `holdingsExpanded` 的折叠头也用到这些 import，**所以这些 import 保留**）。
- 顶层私有 composable `IndustryAllocationCard`（行 997-1070）和 `EditIndustryDialog`（行 1072-1110）：移除（仅本文件用，删除后无引用）。

`PortfolioSummaryCard` 上的「行业目标合计 / 未达 100%」软提示（`targetWeightLabel = "行业目标合计"`，行 175-179、473-479）：**保留**。这块属于持仓汇总卡片，不属于行业配置栏；`industryTargetSum` 数据层也保留。若用户后续想一起去掉，可在迭代中再做。

### 4.2 新增：`PortfolioScreen.kt` FilterChip 横滚条

在「个股持仓」折叠头与持仓列表之间，插入一个 `FilterChipsRow` composable：

```
[全部] [银行] [白酒] [证券] [未分类]   ← 行业组（含「全部」互斥头 chip）
[全部] [高息] [白马] [周期]            ← 标签组（含「全部」互斥头 chip）
```

实现要点：

- 每组首 chip 是「全部」，选中时清空该组选择（与该组其他 chip 互斥）。
- 同组多选（OR）：选「银行」「白酒」 → 显示银行或白酒的股。
- 跨组 AND：行业选「银行」、标签选「高息」 → 显示既是银行又带「高息」标签的股。
- 「自选关注」列表共享同一筛选条件（与持仓股一起被筛）。
- FilterChip 用 Material3 `FilterChip`，外层 `LazyRow` 横滑。

筛选逻辑放在 `PortfolioViewModel`，UI 只渲染 + 转发点击。

```kotlin
@Composable
private fun FilterChipsRow(
    label: String,
    options: List<String>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onClear: () -> Unit
) {
    val isAll = selected.isEmpty()
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            FilterChip(
                selected = isAll,
                onClick = onClear,
                label = { Text("全部") }
            )
        }
        items(options) { opt ->
            FilterChip(
                selected = opt in selected,
                onClick = { onToggle(opt) },
                label = { Text(opt) }
            )
        }
    }
}
```

布局位置示意（删行业区块、加筛选条后的新结构）：

```
[DividendSummaryCard]
[FireProgressCard]
[PortfolioSummaryCard]
┌─ 个股持仓 ▾ ──────────── [+ 添加股票] ┐
│ [全部] [银行] [白酒] …   ← 行业筛选        │
│ [全部] [高息] [白马] …   ← 标签筛选        │
│ <持仓卡片 SwipeToDismissHoldingItem>      │
└─────────────────────────────────────────┘
┌─ 自选关注 ─────────────────────────────┐
│ <自选股卡片 SwipeToDismissWatchItem>      │  ← 同样按上面筛选
└─────────────────────────────────────────┘
```

### 4.3 修改：`EditHoldingScreen.kt` 标签编辑区

在「股息率档位」item 之前（或之后），插入一个 Card：

```
┌─ 标签 ──────────────────────────────────┐
│  [高息 ✕] [白马 ✕]  [+ 添加标签]            │
└─────────────────────────────────────────┘
```

- 用 Compose `FlowRow`（`androidx.compose.foundation.layout.FlowRow`，需要 `@OptIn(ExperimentalLayoutApi::class)`）。
- 每个标签做成 `InputChip`，带 ✕ 删除。
- 「+ 添加标签」打开一个 `AlertDialog`：内含 `OutlinedTextField`，输入新标签后回车/确认；若文本命中已有标签集合，直接选中而非新建（避免重名）。
- ViewModel 持有 `editingTags: Set<String>`，保存持仓时连同 shares/cost 一起 `setStockTags`。

### 4.4 `EditHoldingViewModel` 改动

```kotlin
data class EditHoldingUiState(
    ...,
    val tags: List<String> = emptyList(),         // 当前股票已有标签
    val allTags: List<String> = emptyList(),      // 全局已存在标签（用于输入建议）
    val showAddTagDialog: Boolean = false,
    val addTagInput: String = "",
    val addTagError: String? = null
)

init {
    // combine(stock flow, observeTagsForStock(code), observeAllTags()) → uiState
}

fun addTag(tag: String) { /* 去空白、去重、加入 editingTags */ }
fun removeTag(tag: String) { /* editingTags - tag */ }
fun showAddTagDialog() / dismissAddTagDialog()
fun saveHolding() {
    viewModelScope.launch {
        ... 原有保存逻辑 ...
        stockRepository.setStockTags(stockCode, uiState.value.tags)
    }
}
```

---

## 5. ViewModel (`PortfolioViewModel`) 改动

### 5.1 新增 state

```kotlin
@Stable
data class PortfolioUiState(
    ... 原有字段 ...,
    // 行业/标签候选
    val availableIndustries: List<String> = emptyList(),  // 来自持仓 + 自选，去重排序，含"未分类"
    val availableTags: List<String> = emptyList(),        // 来自 stock_tags 全表
    // 当前选中
    val selectedIndustries: Set<String> = emptySet(),
    val selectedTags: Set<String> = emptySet(),
    // 筛后列表（ViewModel 内完成过滤，UI 直接渲染）
    val filteredItems: List<PortfolioItem> = emptyList(),
    val filteredWatchlist: List<StockEntity> = emptyList()
)
```

`PortfolioItem` 增加 `tags: List<String> = emptyList()`（用于在卡片副标题上展示、也用于筛选）。

### 5.2 新增依赖收集

```kotlin
// tagsByCode: Map<String, List<String>> —— 全量股票→标签映射
private val tagsByCodeFlow = stockTagDao.observeAll()  // SELECT * FROM stock_tags
    .map { list -> list.groupBy { it.stockCode }.mapValues { it.value.map { e -> e.tag } } }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())
```

> 注意：`StockTagDao` 需要新增一个 `observeAll()` 返回 `Flow<List<StockTagEntity>>`（在 3.2 基础上补一个）。或者直接 `observeAllTags()` 配合每个股票的 `observeTagsForStock`；前者更高效（一次查询全拿）。

新增 Collector 5：

```kotlin
viewModelScope.launch {
    combine(allStocksFlow, tagsByCodeFlow) { stocks, tagsByCode ->
        // 1) 重算 availableIndustries / availableTags
        // 2) 重算每只股票的 tags，刷新 items/watchlist 上 PortfolioItem.tags
        // 3) 触发 filter 重算
    }.collect { ... }
}
```

### 5.3 筛选纯函数

```kotlin
private fun applyFilter(
    items: List<PortfolioItem>,
    watchlist: List<StockEntity>,
    tagsByCode: Map<String, List<String>>,
    selectedIndustries: Set<String>,
    selectedTags: Set<String>
): Pair<List<PortfolioItem>, List<StockEntity>> {
    fun matchIndustry(industry: String): Boolean {
        if (selectedIndustries.isEmpty()) return true
        val normalized = industry.ifEmpty { "未分类" }
        return normalized in selectedIndustries
    }
    fun matchTags(code: String): Boolean {
        if (selectedTags.isEmpty()) return true
        val stockTags = tagsByCode[code].orEmpty()
        return stockTags.any { it in selectedTags }   // OR
    }
    val fi = items.filter { matchIndustry(it.industry) && matchTags(it.code) }
    val fw = watchlist.filter {
        val industry = it.industry  // StockEntity 已有 industry 字段
        matchIndustry(industry) && matchTags(it.code)
    }
    return fi to fw
}
```

- 调用时机：Collector 1（holdings）、Collector 4（watchlist/forecast）、Collector 5（tags）、用户点击 chip（更新 `selectedIndustries/Tags`）后都触发一次重算并写入 `filteredItems/filteredWatchlist`。
- 为简化，可在 `_uiState.update` 里集中调一次 `applyFilter`，把结果一并写回。

### 5.4 暴露事件方法

```kotlin
fun toggleIndustryFilter(industry: String)
fun clearIndustryFilter()
fun toggleTagFilter(tag: String)
fun clearTagFilter()
```

每个方法：先 `update` `selectedIndustries/Tags`，再 `update` `filteredItems/filteredWatchlist = applyFilter(...)`。

---

## 6. 数据流总览

```
Room (stocks / stock_tags / industry_targets)
   │
   ├── StockRepository.observeAllStocks() ─┐
   ├── StockTagDao.observeAll() ───────────┤
   └── observeIndustryTargets() (保留，UI 不再用)
                                          ▼
                            PortfolioViewModel (combine)
                                          │
                                          ▼
                       PortfolioUiState (filteredItems/Watchlist)
                                          │
                                          ▼
                          PortfolioScreen (LazyColumn + FilterChipsRow)
```

标签编辑回路：

```
EditHoldingScreen  →  EditHoldingViewModel  →  StockRepository.setStockTags
                                                  │
                                                  ▼
                                          StockTagDao.clear + insert
                                                  │
                                                  ▼
                                          stock_tags 变化 → Collector 5 重算
```

---

## 7. 错误处理

- **标签输入校验**：`addTag` 去首尾空白；空串拒绝（`addTagError = "标签不能为空"`）；超长（>20 字符）拒绝；重复（已存在或全局已存在同名）→ 直接选中已有而非新建（避免 `IGNORE` 冲突时的迷惑）。
- **setStockTags 失败**：包进原有 `saveHolding` 的 try，失败时 `EditHoldingUiState.error` 提示，不阻断其他字段保存（shares/cost 先成功，标签后失败用户可见）。
- **migration 失败**：现有 `DatabaseModule` 未配置 fallbackToDestructiveMigration，migration 失败会抛异常崩溃 —— 与历史一致，不在本次新增风险面。
- **筛选无结果**：`filteredItems` 为空但 `items` 非空时，列表区域显示 `EmptyStateView("当前筛选无结果", onClear = ::clearAllFilters)`（复用现有 `EmptyStateView` 的视觉，传一个简化版 callback）。

---

## 8. 测试

### 8.1 单元测试（新增）

- `StockTagDaoTest`（instrumented，Room in-memory db）
  - insert 重复 `(code, tag)` → 仍是 1 行
  - 删除 stock → stock_tags 自动 CASCADE 清空
  - `observeAllTags()` 去重 + 排序
- `PortfolioViewModelFilterTest`
  - 给定 items + tagsByCode，`applyFilter` 在各种 selectedIndustries/Tags 组合下的输出
  - 含「未分类」用例：industry="" 在选「未分类」时命中、选「银行」时不命中
  - 多标签 OR：股票带 `[高息, 白马]`，选「高息」命中、选「白马」命中、选「周期」不命中
  - 跨组 AND：行业=银行 ∩ 标签=高息 只剩同时满足者
  - 空选 = 全量

### 8.2 手动验收清单

1. 升级安装（保留旧数据）→ 应用正常启动，DB v14
2. 持仓页：行业配置区块消失，摘要/个股/自选区块还在
3. 给 3 只股贴不同标签，编辑页保存 → 返回持仓页标签可见
4. 行业 chip 选「银行」→ 只剩银行股；自选股也被同步筛
5. 标签 chip 选「高息」→ 行业 + 标签 AND 后剩余股票
6. 「全部」chip 清空筛选
7. 删除一只股 → 其 stock_tags 自动消失（标签 chip 列表也更新）
8. 撤销删除 → 标签随股票恢复（撤销路径只恢复 StockEntity，FK CASCADE 已删的 tags 不会自动回来 → **见 §10 已知限制**）

### 8.3 既有测试回归

- `IncomeTimelineCardTest`、`DesignSystemTest` 应继续通过（不涉及本次改动）。
- 跑 `./gradlew :app:assembleDebug` + `:app:testDebugUnitTest` + `:app:connectedAndroidTest`（如设备可用）。

---

## 9. 实现顺序（writing-plans 阶段细化）

1. 数据层：`StockTagEntity` + `StockTagDao`（含 `observeAll`/`observeAllTags`/`getAll`/`insertAll`/`deleteAll`）+ `AppDatabase` v14 + `MIGRATION_13_14` + `DatabaseModule`（注入 DAO + 加 migration）+ `StockRepository.observeAllTags/observeTagsForStock/setStockTags`
2. **Backup 链路**：`BackupContainer.stockTags` + `BackupRepository` export/import + DAO 注入（§3.5）
3. `PortfolioViewModel`：`tagsByCodeFlow`（基于 `stockTagDao.observeAll()`）+ Collector 5 + `applyFilter` + `PortfolioItem.tags` + 4 个 toggle/clear 方法 + state 字段
4. `PortfolioScreen`：删除行业配置区块 + 删除 `IndustryAllocationCard`/`EditIndustryDialog` + 加 `FilterChipsRow`
5. `EditHoldingViewModel` + `EditHoldingScreen`：标签编辑 UI 与保存
6. `build.gradle.kts` version bump（versionCode 5→6, versionName 3.0.2→3.1.0）
7. 单测（DAO + ViewModel filter）+ 手测清单
2. `PortfolioViewModel`：`tagsByCodeFlow` + Collector 5 + `applyFilter` + `PortfolioItem.tags` + 4 个 toggle/clear 方法 + state 字段
3. `PortfolioScreen`：删除行业配置区块 + 删除 `IndustryAllocationCard`/`EditIndustryDialog` + 加 `FilterChipsRow`
4. `EditHoldingViewModel` + `EditHoldingScreen`：标签编辑 UI 与保存
5. `build.gradle.kts` version bump
6. 单测 + 手测

---

## 10. 已知限制 / 取舍

- **撤销删除不恢复标签**：现有 `undoDelete` 只 `restoreStock(StockEntity)`，FK CASCADE 已把 `stock_tags` 删掉，撤销时不会重建。与本次 scope 一致（不扩 undoDelete），如要恢复可在迭代中把 deletedStock 的 tags 一并备份到 `PortfolioUiState.deletedTags` 并在 undo 时重写。**接受此限制**。
- **行业目标合计软提示保留**：`PortfolioSummaryCard` 上的「行业目标合计 X% 未达 100%」仍存在（因为数据层 `industry_targets` 保留）。若 UX 上希望一起隐藏，需在 follow-up 单独处理。
- **不持久化筛选状态**：每次进页面都全量显示，符合"默认全部、不筛"决策。
- **`IndustryAllocationPieChart.kt` 文件保留但 dead**：避免一次性删太多；后续清理可单独提 PR。

---

## 11. 用户已确认决策（问答记录）

| 问题 | 用户选择 |
|---|---|
| 删除范围 | 只删 UI 区块（保留数据层） |
| 筛选维度 | 行业 + 自定义标签 |
| 筛选 UI 形式 | 顶部 FilterChip 横滚条 |
| 标签存储 | 每只股票可打多个标签（独立多对多表） |
| 多维度组合 | AND（交集） |
| 标签编辑入口 | 复用 EditHoldingScreen |
| 默认筛选状态 | 默认全部、不筛 |

## 12. 方案 A vs B 对比（备忘）

| 维度 | A: 独立 stock_tags 表 | B: stocks 加 tags 列 |
|---|---|---|
| 多对多 | 原生支持 | 需 CSV/JSON |
| 查询某标签下所有股 | `WHERE tag = ?` 精确 | `LIKE '%tag%'` 易误匹配 |
| 未来扩展（标签聚合统计） | 易 | 难 |
| Migration 复杂度 | +1 表 +2 索引 | +1 列 |
| 单股读取需 JOIN | 是 | 否 |

→ 选 **A**。
