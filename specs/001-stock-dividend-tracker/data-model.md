# Data Model: Stock Dividend Tracker

**Branch**: `001-stock-dividend-tracker` | **Date**: 2026-04-12

## Entities

### Stock (股票)

用户关注的股票信息,存储在本地 Room 数据库。

| Field | Type | Description | Constraints |
|-------|------|-------------|-------------|
| code | String (PK) | 股票代码,如 `sz.000001` | 格式: `{sh\|sz}.{6位数字}` |
| name | String | 股票名称,如 `平安银行` | 非空 |
| marketCode | String | 东方财富市场编号: `1`=上海, `0`=深圳 | 非空 |
| addedAt | Long | 用户添加时间 (epoch millis) | 非空, 自动生成 |
| lastUpdated | Long? | 股息数据最后更新时间 | 可空 |

### DividendRecord (股息记录)

某只股票某一次分红记录,来源于东方财富 API,缓存至本地。

| Field | Type | Description | Constraints |
|-------|------|-------------|-------------|
| id | String (PK) | 主键: `{stockCode}_{reportDate}` | 自动生成 |
| stockCode | String (FK) | 关联股票代码 | → Stock.code, CASCADE |
| reportDate | String | 报告期, `YYYY-MM-DD` | 非空, 如 `2024-12-31` |
| cashPerShare | Double | 每股派息 (元), 已从每10股换算 | 默认 0.0 |
| dividendYield | Double? | 股息率 (%) | 可空 |
| exDividendDate | String? | 除权除息日 | 可空 |
| recordDate | String? | 股权登记日 | 可空 |
| planStatus | String? | 方案进度 | 可空, 如 "实施方案" |

### Relationships

```
Stock (1) ──── (N) DividendRecord
  一个股票有多条股息记录
  删除股票时级联删除所有股息记录
```

## Data Flow

```
[东方财富 HTTP API]
       ↕ (REST JSON)
[Android App]
  ├── Retrofit → DTO (网络传输对象)
  │     ├── StockSearchResponse
  │     └── DividendResponse
  ├── Repository
  │     ├── 网络数据 → 存入 Room
  │     └── 优先读取 Room (离线优先)
  ├── Room DAO → 本地 SQLite
  └── ViewModel → UI State (Compose)
```

### DTO 结构

**StockSearchResponse** (东方财富搜索 API 响应):
```json
{
  "QuotationCodeTable": {
    "Data": [
      {
        "Code": "000001",
        "Name": "平安银行",
        "MktNum": "0",
        "SecurityTypeName": "A股"
      }
    ]
  }
}
```

**DividendResponse** (东方财富股息 API 响应):
```json
{
  "result": {
    "data": [
      {
        "SECURITY_CODE": "000001",
        "SECURITY_NAME_ABBR": "平安银行",
        "REPORT_DATE": "2024-12-31T00:00:00",
        "CASH_DIVIDEND_RATIO": 2.46,
        "DIVIDEND_YIELD": 5.93,
        "EX_DIVIDEND_DATE": "2025-07-11T00:00:00",
        "EQUITY_RECORD_DATE": "2025-07-10T00:00:00",
        "IMPL_PLAN": "实施方案"
      }
    ]
  }
}
```

### DTO → Entity 转换

**StockSearchDto → StockEntity**:
- `code`: MktNum + Code → `sz.000001` (MktNum=0→sz, MktNum=1→sh)
- `name`: 直接映射 `Name`
- `marketCode`: 直接映射 `MktNum`
- `addedAt`: `System.currentTimeMillis()`

**DividendDto → DividendEntity**:
- `id`: `{stockCode}_{reportDate}` (去时间部分)
- `stockCode`: 关联 Stock.code
- `reportDate`: 去掉 `T00:00:00` 后缀, 取 `YYYY-MM-DD`
- `cashPerShare`: `CASH_DIVIDEND_RATIO / 10.0` (每10股 → 每股)
- `dividendYield`: 直接映射 `DIVIDEND_YIELD`
- `exDividendDate`: 去掉 `T00:00:00` 后缀
- `recordDate`: 去掉 `T00:00:00` 后缀
- `planStatus`: 直接映射 `IMPL_PLAN`

## Validation Rules

- 股票代码格式验证: 正则 `^\d{6}$` (API 返回纯数字)
- 搜索结果仅展示 `SecurityTypeName` 包含 "A股" 的结果
- `cashPerShare` 为非负数
- 同一股票同一报告期的股息记录唯一 (复合主键保证)
- 添加重复股票时忽略 (INSERT OR IGNORE)
- 日期字段统一存储为 `YYYY-MM-DD` 格式
