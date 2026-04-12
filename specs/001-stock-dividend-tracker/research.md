# Research: Stock Dividend Tracker

**Branch**: `001-stock-dividend-tracker` | **Date**: 2026-04-12

## 数据源: 东方财富公开 HTTP API

### Decision: 使用东方财富 API 直连 (无后端)

**Rationale**:
- 东方财富提供免费、无需认证的公开 HTTP JSON API
- 支持股票名称模糊搜索和个股股息历史查询
- Android 应用可通过 Retrofit 直接调用,无需后端服务器
- 响应为标准 JSON,易于解析

**Alternatives considered**:
- Baostock: Python 专用 TCP 库,无法从 Android 直接调用
- 新浪财经: 股息数据返回 HTML 而非 JSON,解析困难
- 巨潮资讯: 需要动态 JS 计算的认证令牌,不可用

### API Endpoint 1: 股票搜索

**URL**: `https://searchapi.eastmoney.com/api/suggest/get`

**参数**:

| 参数 | 值 | 说明 |
|------|-----|------|
| input | 搜索关键词 | 支持中文名、代码、拼音首字母 |
| type | 14 | 固定值 (股票类型) |
| token | D43BF722C8E33BDC906FB84D85E326E8 | 公共固定 token |
| count | 10 | 返回结果数量 |

**示例**: `https://searchapi.eastmoney.com/api/suggest/get?input=平安银行&type=14&token=D43BF722C8E33BDC906FB84D85E326E8&count=5`

**响应**:
```json
{
  "QuotationCodeTable": {
    "Data": [
      {
        "Code": "000001",
        "Name": "平安银行",
        "MktNum": "0",
        "SecurityTypeName": "A股",
        "MktName": "深圳证券交易所"
      }
    ]
  }
}
```

**字段映射**:
- `Code` → 股票代码 (纯6位数字,如 `000001`)
- `Name` → 股票名称 (如 `平安银行`)
- `MktNum` → 市场编号: `1`=上海, `0`=深圳

### API Endpoint 2: 个股股息历史

**URL**: `https://datacenter-web.eastmoney.com/api/data/v1/get`

**参数**:

| 参数 | 值 | 说明 |
|------|-----|------|
| reportName | RPT_SHAREBONUS_DET | 分红送配报告 |
| columns | ALL | 返回全部字段 |
| filter | (SECURITY_CODE="000001") | 按股票代码过滤 |
| sortColumns | REPORT_DATE | 按报告期排序 |
| sortTypes | -1 | 降序 |
| pageSize | 500 | 每页条数 |
| pageNumber | 1 | 页码 |
| source | WEB | 固定值 |
| client | WEB | 固定值 |

**示例**: 查询平安银行全部分红历史
```
https://datacenter-web.eastmoney.com/api/data/v1/get?reportName=RPT_SHAREBONUS_DET&columns=ALL&filter=(SECURITY_CODE="000001")&sortColumns=REPORT_DATE&sortTypes=-1&pageSize=500&pageNumber=1&source=WEB&client=WEB
```

**响应**:
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
        "IMPL_PLAN": "实施方案",
        "PLAN_NOTICE_DATE": "2025-03-14T00:00:00",
        "BONUS_SHARE_RATIO": 0.0,
        "CONVERT_SHARE_RATIO": 0.0
      }
    ]
  }
}
```

**关键字段**:

| API 字段 | 中文名 | 说明 |
|----------|--------|------|
| SECURITY_CODE | 代码 | 股票代码 (纯数字) |
| SECURITY_NAME_ABBR | 名称 | 股票简称 |
| REPORT_DATE | 报告期 | 如 `2024-12-31` |
| CASH_DIVIDEND_RATIO | 派息金额 | 每10股派息金额 (元) |
| DIVIDEND_YIELD | 股息率 | 百分比 |
| EX_DIVIDEND_DATE | 除权除息日 | |
| EQUITY_RECORD_DATE | 股权登记日 | |
| IMPL_PLAN | 方案进度 | 董事会预案/实施方案等 |
| BONUS_SHARE_RATIO | 送股比例 | 每10股送股 |
| CONVERT_SHARE_RATIO | 转增比例 | 每10股转增 |

**重要**: `CASH_DIVIDEND_RATIO` 单位为**每10股**派息金额,需除以10换算为每股派息。

### 股票代码格式

东方财富 API 使用:
- **搜索 API**: 纯6位数字 (`000001`)
- **数据 API**: 纯6位数字 (`000001`), 通过 `MktNum` 判断市场 (1=上海, 0=深圳)

本地存储格式: 使用 `MktNum` 前缀组合为 `{market}{code}`, 如 `0000001` (深圳),
`1600519` (上海)。或者更简洁地存储为 `sz.000001` / `sh.600519` 以保持可读性。

### 注意事项

1. 这些是东方财富网页端的内部 API, 非官方文档化接口, 可能随时变更
2. 无需认证, 但建议在请求中添加 `Referer: https://data.eastmoney.com/` 避免被拦截
3. 无明确速率限制, 但应避免高频请求
4. 日期字段包含时间后缀 `T00:00:00`, 解析时需处理

## 架构决策

### Decision 1: 纯客户端架构

**Decision**: Android 应用通过 Retrofit 直连东方财富 API,无后端服务器

**Rationale**:
- 省去服务器部署和维护成本
- 东方财富 API 提供标准 HTTP JSON 接口,可直接从 Android 调用
- 个人使用场景下并发量极低,不存在性能瓶颈
- 数据缓存至本地 Room, 离线可浏览

### Decision 2: 数据单位换算

**Decision**: API 返回"每10股派息金额",本地换算为"每股派息金额"存储

**Rationale**:
- 用户习惯看"每股派息"而非"每10股派息"
- 换算仅为简单除法,不影响数据准确性
- 存储每股数值方便后续汇总计算
