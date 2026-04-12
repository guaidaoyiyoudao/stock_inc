# Research: Holdings Dividend Forecast

**Branch**: `002-holdings-dividend-forecast` | **Date**: 2026-04-12

## 数据存储方案

### Decision: 在 StockEntity 上新增字段 (不新建 Holding Entity)

**Rationale**:
- 持仓与股票是一对一关系 (spec 明确 "与股票一一关联")
- 新建 Entity 需要额外的 JOIN 查询和关联管理，增加不必要的复杂度
- 在 StockEntity 上直接新增 `shares` 和 `yieldPeriod` 字段最简洁
- Room Migration v1→v2 只需 `ALTER TABLE stocks ADD COLUMN` 两条语句

**Alternatives considered**:
- 新建 HoldingEntity: 需要额外的 DAO、Repository 方法和 JOIN 查询，违反 YAGNI 原则
- SharedPreferences 存储持仓: 无法与股票列表联动查询，不适合结构化数据

### 具体字段设计

| 新增字段 | 类型 | 默认值 | 说明 |
|----------|------|--------|------|
| shares | Int | 0 | 持有股数，整数，单位为"股" |
| yieldPeriod | String | "3" | 股息率档位选择，"1"/"3"/"5" 代表1年/3年/5年 |

### Room 迁移策略

```kotlin
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE stocks ADD COLUMN shares INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE stocks ADD COLUMN yieldPeriod TEXT NOT NULL DEFAULT '3'")
    }
}
```

## 预测计算逻辑

### Decision: 纯客户端基于本地缓存历史数据计算

**Rationale**:
- spec 明确 "平均股息率基于已有的历史股息记录计算，不引入外部数据源"
- 计算公式简单：算术平均值 × 持仓数量
- 使用 Kotlin Flow 在内存中实时计算，无需额外存储

### 计算算法

```
对于每只股票:
1. 从 Room 获取 DividendEntity 列表 (按 reportDate 降序)
2. 按 reportDate 年份去重 (每年取最新记录)
3. 根据用户选择的档位 (1/3/5年):
   - 取最近 N 年的 cashPerShare
   - 如果实际年数 < N，使用实际年数计算
   - 计算算术平均值: avgCashPerShare = sum(cashPerShare) / count
4. 预测年度股息收入 = shares × avgCashPerShare
5. 实际使用年数 = min(可用年数, N)
```

**数据不足处理**:
- 可用年数 = 0 → 显示 "暂无历史数据，无法预测"
- 可用年数 < 选择的年数 → 使用实际年数计算平均值，界面标注 "基于 X 年数据"

### yieldPeriod 存储格式

**Decision**: 使用字符串 "1"/"3"/"5"

**Rationale**:
- 与枚举相比，Room 原生支持 String 类型，无需 TypeConverter
- 值域固定为三个选项，在 ViewModel 层做输入校验即可
- 默认值 "3" 对应 spec 要求的 "默认使用3年平均股息率"

## 新增 Screen 设计

### Decision: EditHoldingScreen (独立编辑页面)

**Rationale**:
- 用户在 clarify 阶段明确选择独立页面而非内联对话框
- 原因: "后续可能需要新增其他的字段编辑"
- 独立页面便于扩展，不影响现有页面复杂度

### 路由设计

```
Routes.STOCK_DETAIL → Routes.EDIT_HOLDING/{code}
```

导航路径: Home → StockDetail → EditHolding → (返回) StockDetail

### EditHoldingScreen 内容

- 股票名称和代码展示 (只读)
- 持有股数输入 (OutlinedTextField, 数字键盘)
- 股息率档位选择 (1年/3年/5年, SegmentedButton 或 RadioGroup)
- 保存按钮 + 返回按钮

## UI 修改点

### StockCard 修改

现有 `latestDividend` 参数改为显示预测股息收入:
- 新增参数: `shares: Int`, `forecastIncome: String?`
- 布局: 股票名/代码 → 持仓数 → 预测收入

### DividendSummaryCard 修改

- 标题改为 "预测年度股息收入"
- 金额 = 所有股票的预测收入之和
- 底部注明 "仅供参考"

### AddStockScreen 修改

- 在搜索结果选择后、确认添加前，新增持有股数输入框
- 默认值空 (不填写则 shares=0)

### StockDetailScreen 修改

- 顶部展示持仓数量
- 新增 "编辑持仓" 按钮 (跳转 EditHoldingScreen)
- 股息记录下方新增预测收入区域:
  - 选中档位的预测收入 (突出显示)
  - 1年/3年/5年三档对比展示
  - 不足年数时标注实际使用年数
  - 底部注明 "仅供参考"
