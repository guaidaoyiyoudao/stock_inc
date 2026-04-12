# Data Model: Holdings Dividend Forecast

**Branch**: `002-holdings-dividend-forecast` | **Date**: 2026-04-12

## Entity 变更

### Stock (股票) — v2 变更

在现有 StockEntity 基础上新增两个字段:

| Field | Type | Description | Constraints |
|-------|------|-------------|-------------|
| shares | Int | 持有股数 | NOT NULL, DEFAULT 0, >= 0 |
| yieldPeriod | String | 股息率档位选择 | NOT NULL, DEFAULT "3", 枚举: "1"/"3"/"5" |

完整 v2 StockEntity:

| Field | Type | Description | Constraints |
|-------|------|-------------|-------------|
| code | String (PK) | 股票代码 | 格式: `{sh\|sz}.{6位数字}` |
| name | String | 股票名称 | 非空 |
| marketCode | String | 东方财富市场编号 | 非空 |
| addedAt | Long | 添加时间 (epoch millis) | 非空, 自动生成 |
| lastUpdated | Long? | 股息数据最后更新时间 | 可空 |
| shares | Int | 持有股数 | NOT NULL, DEFAULT 0 |
| yieldPeriod | String | 股息率档位 | NOT NULL, DEFAULT "3" |

### DividendRecord (股息记录) — 无变更

| Field | Type | Description | Constraints |
|-------|------|-------------|-------------|
| id | String (PK) | 主键: `{stockCode}_{reportDate}` | 自动生成 |
| stockCode | String (FK) | 关联股票代码 | → Stock.code, CASCADE |
| reportDate | String | 报告期 `YYYY-MM-DD` | 非空 |
| cashPerShare | Double | 每股派息 (元) | 默认 0.0 |
| dividendYield | Double? | 股息率 (%) | 可空 |
| exDividendDate | String? | 除权除息日 | 可空 |
| recordDate | String? | 股权登记日 | 可空 |
| planStatus | String? | 方案进度 | 可空 |

## Relationships

```
Stock (1) ──── (N) DividendRecord
  一个股票有多条股息记录
  删除股票时级联删除所有股息记录
  Stock.shares 和 Stock.yieldPeriod 用于预测计算
```

## 数据流: 预测计算

```
[Room 本地数据]
  ├── StockDao.observeAll() → Flow<List<StockEntity>>  (含 shares, yieldPeriod)
  └── DividendDao.observeByStock(code) → Flow<List<DividendEntity>>  (含 cashPerShare, reportDate)

[ViewModel 计算层]
  ├── combine(stocksFlow, dividendsMap) → 预测数据
  │     对每只股票:
  │     1. 按 reportDate 年份去重 (每年取最新记录)
  │     2. 根据 yieldPeriod 取最近 N 条
  │     3. avgCashPerShare = sum(cashPerShare) / count
  │     4. forecastIncome = shares × avgCashPerShare
  └── 汇总: sum(每只股票的 forecastIncome)

[UI State]
  ├── HomeUiState.forecastTotal: Double
  ├── StockWithForecast(shares, forecastIncome, avgCashPerShare, actualYears)
  └── StockDetailUiState.forecasts: Map<YieldPeriod, ForecastDetail>
```

## Room 迁移

```sql
-- Migration v1 → v2
ALTER TABLE stocks ADD COLUMN shares INTEGER NOT NULL DEFAULT 0;
ALTER TABLE stocks ADD COLUMN yieldPeriod TEXT NOT NULL DEFAULT '3';
```

## 计算规则

### 平均每股派息金额计算

```
输入: DividendEntity 列表 (按 reportDate DESC 排序), yieldPeriod: "1"|"3"|"5"
输出: avgCashPerShare: Double, actualYears: Int

步骤:
1. 提取每个 reportDate 的年份 (YYYY)
2. 按年份去重, 每年保留最新的 cashPerShare (列表已降序, 取第一条即可)
3. N = yieldPeriod.toInt()
4. 取前 N 个年份的数据
5. actualYears = min(可用年数, N)
6. avgCashPerShare = sum(选中年份的 cashPerShare) / actualYears
```

### 预测年度股息收入计算

```
forecastIncome = shares × avgCashPerShare
```

- shares = 0 → 不计算, 不展示预测
- shares < 0 → 视为 0
- avgCashPerShare = 0 (无历史数据) → 展示 "暂无历史数据，无法预测"

## Validation Rules

- shares MUST >= 0 (负数自动纠正为 0)
- yieldPeriod MUST 为 "1", "3", "5" 之一 (默认 "3")
- 预测收入 MUST 在持仓或股息率变更后即时更新 (通过 Flow 自动触发)
- 汇总预测收入 MUST 等于各股票预测收入之和 (SC-004)
