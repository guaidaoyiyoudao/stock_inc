# API Contracts: Holdings Dividend Forecast

**Branch**: `002-holdings-dividend-forecast` | **Date**: 2026-04-12

## 无新增外部 API

本功能不引入新的外部 API。所有预测计算基于 001-stock-dividend-tracker 已获取的本地缓存数据。

## 内部数据流契约

### DAO 层变更

```
StockDao:
  现有: insert(), delete(), observeAll(), getByCode()
  新增: updateShares(code, shares)        → 更新持仓数量
  新增: updateYieldPeriod(code, period)    → 更新股息率档位
  新增: observeByCode(code)               → Flow<StockEntity?> (用于详情页)

DividendDao:
  现有: insertAll(), deleteByStockCode(), observeByStock(), deleteAll(), observeTotalCashPerShare()
  不变 (预测计算在 ViewModel 层基于 observeByStock 的数据完成)
```

### Repository 层变更

```
StockRepository:
  现有: searchStocks(), addStock(), removeStock(), observeAllStocks()
  修改: addStock(result, shares: Int = 0)     → 支持传入持仓数量
  新增: updateShares(code, shares)             → 更新持仓
  新增: updateYieldPeriod(code, period)        → 更新股息率档位
  新增: observeStock(code)                     → Flow<StockEntity?>

DividendRepository:
  现有: fetchAndCacheDividends(), observeDividends()
  不变 (预测计算在 ViewModel 层)
```

### ViewModel 层契约

```
HomeViewModel:
  uiState: StateFlow<HomeUiState>
    - stocks: List<StockEntity>              (含 shares, yieldPeriod)
    - forecastTotal: Double                  (预测年度股息收入汇总)
    - stockForecasts: Map<String, StockForecast>  (code → 预测数据)
    - isLoading: Boolean
    - error: String?
    - deletedStock: StockEntity?

  新增数据类:
  StockForecast(
    shares: Int,
    avgCashPerShare: Double,
    forecastIncome: Double,
    actualYears: Int
  )

AddStockViewModel:
  uiState: StateFlow<AddStockUiState>
    现有: searchQuery, searchResults, isSearching, error, addedStock, canRetry
    新增: shares: Int        (持有股数输入)
    新增: sharesError: String? (输入校验错误)

  修改: addStock(result, shares)  → 支持传入持仓数量

StockDetailViewModel:
  uiState: StateFlow<StockDetailUiState>
    现有: stock, dividends, isLoading, error
    新增: forecast: ForecastDetail?             (当前选中档位的预测)
    新增: allForecasts: Map<String, ForecastDetail>  ("1"→..., "3"→..., "5"→...)
    新增: selectedPeriod: String                (当前选中档位)

  新增数据类:
  ForecastDetail(
    avgCashPerShare: Double,
    forecastIncome: Double,
    actualYears: Int,
    dataYearsAvailable: Int
  )

  新增方法:
    updateShares(shares: Int)       → 更新持仓并重新计算
    updateYieldPeriod(period: String) → 更新档位并重新计算
```

### 导航契约

```
Routes:
  现有: HOME, ADD_STOCK, STOCK_DETAIL/{code}
  新增: EDIT_HOLDING/{code}

导航路径:
  HomeScreen → StockCard click → StockDetailScreen
  StockDetailScreen → "编辑持仓" button → EditHoldingScreen
  EditHoldingScreen → "保存" → popBackStack → StockDetailScreen (刷新数据)
```
