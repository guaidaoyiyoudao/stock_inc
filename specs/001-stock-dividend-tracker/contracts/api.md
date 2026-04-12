# API Contracts: Stock Dividend Tracker

**Branch**: `001-stock-dividend-tracker` | **Date**: 2026-04-12

## 外部 API: 东方财富

### 1. 股票搜索

**Endpoint**: `GET https://searchapi.eastmoney.com/api/suggest/get`

**请求参数**:

| 参数 | 类型 | 必填 | 值 |
|------|------|------|-----|
| input | String | Yes | 用户输入的搜索关键词 |
| type | String | Yes | `14` (固定,股票类型) |
| token | String | Yes | `D43BF722C8E33BDC906FB84D85E326E8` (公共常量) |
| count | String | Yes | `10` (返回结果数) |

**请求头**:
```
Referer: https://so.eastmoney.com/
```

**响应**: `application/json`

```json
{
  "QuotationCodeTable": {
    "Data": [
      {
        "Code": "000001",
        "Name": "平安银行",
        "MktNum": "0",
        "SecurityTypeName": "A股",
        "SecurityTypeNameAbbr": "深A",
        "MktName": "深圳证券交易所"
      }
    ]
  }
}
```

**错误处理**:
- `QuotationCodeTable.Data` 为 null 或空数组 → 无匹配结果
- 网络超时 → 使用 OkHttp 10s 超时, 触发 Repository 层错误回调

---

### 2. 个股股息历史

**Endpoint**: `GET https://datacenter-web.eastmoney.com/api/data/v1/get`

**请求参数**:

| 参数 | 类型 | 必填 | 值 |
|------|------|------|-----|
| reportName | String | Yes | `RPT_SHAREBONUS_DET` |
| columns | String | Yes | `ALL` |
| filter | String | Yes | `(SECURITY_CODE="{code}")` |
| sortColumns | String | Yes | `REPORT_DATE` |
| sortTypes | String | Yes | `-1` (降序) |
| pageSize | String | Yes | `500` |
| pageNumber | String | Yes | `1` |
| source | String | Yes | `WEB` |
| client | String | Yes | `WEB` |

**请求头**:
```
Referer: https://data.eastmoney.com/
```

**响应**: `application/json`

```json
{
  "success": true,
  "result": {
    "pages": 1,
    "data": [
      {
        "SECURITY_CODE": "000001",
        "SECURITY_NAME_ABBR": "平安银行",
        "REPORT_DATE": "2024-12-31T00:00:00",
        "CASH_DIVIDEND_RATIO": 2.46,
        "DIVIDEND_YIELD": 5.93,
        "EX_DIVIDEND_DATE": "2025-07-11T00:00:00",
        "EQUITY_RECORD_DATE": "2025-07-10T00:00:00",
        "IMPL_PLAN": "实施方案",
        "PLAN_NOTICE_DATE": "2025-03-14T00:00:00",
        "BONUS_SHARE_RATIO": 0.0,
        "CONVERT_SHARE_RATIO": 0.0,
        "EPS": 2.45,
        "BPS": 21.36
      }
    ]
  }
}
```

**错误处理**:
- `success` 为 false → API 错误, 展示通用错误提示
- `result.data` 为空数组 → 该股票无股息记录
- 网络超时 → 使用 OkHttp 15s 超时, 触发 Repository 层错误回调
- `CASH_DIVIDEND_RATIO` 为 null 或 0 → 该报告期无现金分红

---

## 内部数据流契约

### Repository 层行为

```
StockRepository:
  searchStocks(query) → 调用搜索 API → 返回 List<StockSearchResult>
  addStock(stock) → 存入 Room → 返回 Result<Unit>
  removeStock(code) → 从 Room 删除 (CASCADE 股息记录) → 返回 Unit
  observeAllStocks() → Room Flow → Flow<List<Stock>>

DividendRepository:
  fetchAndCacheDividends(stockCode) → 调用股息 API → 存入 Room → 返回 Result<Unit>
  observeDividends(stockCode) → Room Flow → Flow<List<DividendRecord>>
  observeTotalDividendSummary() → Room Flow → Flow<DividendSummary>
```

### ViewModel → UI State

```
HomeViewModel:
  uiState: StateFlow<HomeUiState>
    - summary: DividendSummary (总收入)
    - stocks: List<StockWithDividends> (股票+最近股息)
    - isLoading: Boolean
    - error: String?

AddStockViewModel:
  uiState: StateFlow<AddStockUiState>
    - searchQuery: String
    - searchResults: List<StockSearchResult>
    - isSearching: Boolean
    - error: String?

StockDetailViewModel:
  uiState: StateFlow<StockDetailUiState>
    - stock: Stock
    - dividends: List<DividendRecord> (按年分组)
    - isLoading: Boolean
    - error: String?
```
