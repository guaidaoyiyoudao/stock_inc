package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.local.AppDatabase
import com.stock.dividend.data.local.dao.IndustryTargetDao
import com.stock.dividend.data.local.dao.PriceCacheDao
import com.stock.dividend.data.local.dao.SearchCacheDao
import com.stock.dividend.data.local.dao.StockDao
import com.stock.dividend.data.local.dao.StockTagDao
import com.stock.dividend.data.local.dao.TransactionDao
import com.stock.dividend.data.local.entity.IndustryTargetEntity
import com.stock.dividend.data.local.entity.PriceCacheEntity
import com.stock.dividend.data.local.entity.SearchCacheEntity
import com.stock.dividend.data.local.entity.StockEntity
import com.stock.dividend.data.local.entity.TransactionEntity
import com.stock.dividend.data.repository.ErrorLogRepository
import com.stock.dividend.data.remote.QuoteApi
import com.stock.dividend.data.remote.SearchApi
import com.stock.dividend.data.remote.dto.BalanceSheetResponse
import com.stock.dividend.data.remote.dto.FundamentalResponse
import com.stock.dividend.data.remote.dto.QuoteData
import com.stock.dividend.data.remote.dto.QuoteItem
import com.stock.dividend.data.remote.dto.QuoteResponse
import com.stock.dividend.data.remote.dto.StockInfoData
import com.stock.dividend.data.remote.dto.StockInfoResponse
import com.stock.dividend.data.remote.dto.StockSearchResponse
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.slot
import androidx.room.withTransaction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class StockRepositoryTest {

    private val api: SearchApi = mockk()
    private val quoteApi: QuoteApi = mockk()
    private val fundamentalApi: com.stock.dividend.data.remote.FundamentalApi = mockk()
    private val dao: StockDao = mockk(relaxed = true)
    private val transactionDao: TransactionDao = mockk(relaxed = true)
    private val industryTargetDao: IndustryTargetDao = mockk(relaxed = true)
    private val priceCacheDao: PriceCacheDao = mockk(relaxed = true)
    private val searchCacheDao: SearchCacheDao = mockk(relaxed = true)
    private val stockTagDao: StockTagDao = mockk(relaxed = true)
    private val klineRepository: KlineRepository = mockk(relaxed = true)
    private val appDatabase: AppDatabase = mockk(relaxed = true)
    private val errorLogRepository: ErrorLogRepository = mockk(relaxed = true)
    private val repository = StockRepository(
        api, quoteApi, fundamentalApi, dao, transactionDao, industryTargetDao,
        priceCacheDao, searchCacheDao, stockTagDao, klineRepository, appDatabase, errorLogRepository
    )

    @org.junit.Before
    fun setUp() {
        // Room 的 withTransaction 扩展函数默认会走真实 DB 事务，单测里 mock 为「直接执行 block」。
        mockkStatic("androidx.room.RoomDatabaseKt")
        val blockSlot = slot<suspend () -> Any>()
        coEvery { appDatabase.withTransaction(capture(blockSlot)) } coAnswers {
            blockSlot.captured.invoke()
        }
        // 默认搜索缓存未命中，让现有 searchStocks 测试走原网络流程；个别缓存测试单独覆盖。
        coEvery { searchCacheDao.getByQuery(any()) } returns emptyList()
        // priceCacheDao.getByCodes 默认返回空（getCachedPrices 无缓存）
        coEvery { priceCacheDao.getByCodes(any()) } returns emptyList()
    }

    @After
    fun tearDown() {
        unmockkStatic("androidx.room.RoomDatabaseKt")
    }

    @Test
    fun `searchStocks returns filtered A-stock results`() = runTest {
        coEvery { api.searchStocks(input = "平安银行") } returns StockSearchResponse(
            quotationCodeTable = StockSearchResponse.QuotationCodeTable(
                Data = listOf(
                    StockSearchResponse.StockItem(
                        Code = "000001",
                        Name = "平安银行",
                        MktNum = "0",
                        SecurityTypeName = "深A",
                        Classify = "AStock"
                    ),
                    StockSearchResponse.StockItem(
                        Code = "000001",
                        Name = "平安银行",
                        MktNum = "0",
                        SecurityTypeName = "债券",
                        Classify = "Bond"
                    )
                )
            )
        )

        val result = repository.searchStocks("平安银行")

        assertThat(result.isSuccess).isTrue()
        val results = result.getOrNull()!!
        assertThat(results).hasSize(1)
        assertThat(results[0].name).isEqualTo("平安银行")
        assertThat(results[0].code).isEqualTo("sz.000001")
    }

    @Test
    fun `searchStocks keeps exchange traded funds but rejects OTC funds`() = runTest {
        // 实测口径（2026-08-22 searchapi）：场内基金 Classify="Fund" 且 MktNum="1"/"0"（与 A 股同市场规则）；
        // 场外基金 Classify="OTCFUND"（MktNum="150"，如易方达消费行业 110022）不可行情交易，必须排除
        coEvery { api.searchStocks(input = "红利ETF") } returns StockSearchResponse(
            quotationCodeTable = StockSearchResponse.QuotationCodeTable(
                Data = listOf(
                    StockSearchResponse.StockItem(
                        Code = "510880", Name = "红利ETF华泰柏瑞",
                        MktNum = "1", SecurityTypeName = "基金", Classify = "Fund"
                    ),
                    StockSearchResponse.StockItem(
                        Code = "159905", Name = "红利ETF工银",
                        MktNum = "0", SecurityTypeName = "基金", Classify = "Fund"
                    ),
                    StockSearchResponse.StockItem(
                        Code = "110022", Name = "易方达消费行业股票",
                        MktNum = "150", SecurityTypeName = "基金", Classify = "OTCFUND"
                    ),
                    StockSearchResponse.StockItem(
                        Code = "000001", Name = "平安银行",
                        MktNum = "0", SecurityTypeName = "深A", Classify = "AStock"
                    )
                )
            )
        )

        val result = repository.searchStocks("红利ETF")

        assertThat(result.isSuccess).isTrue()
        val results = result.getOrNull()!!
        assertThat(results.map { it.code }).containsExactly("sh.510880", "sz.159905", "sz.000001").inOrder()
        // marketCode 沿用 MktNum（1=沪/0=深），行情 secid 构造与 A 股同路
        assertThat(results[0].marketCode).isEqualTo("1")
    }

    @Test
    fun `searchStocks returns user-friendly message on timeout`() = runTest {
        coEvery { api.searchStocks(input = any()) } throws SocketTimeoutException("timeout")

        val result = repository.searchStocks("平安银行")

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).isEqualTo("网络连接超时，请重试")
    }

    @Test
    fun `searchStocks returns user-friendly message on no network`() = runTest {
        coEvery { api.searchStocks(input = any()) } throws UnknownHostException("no host")

        val result = repository.searchStocks("平安银行")

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).isEqualTo("网络连接失败，请检查网络后重试")
    }

    @Test
    fun `searchStocks returns user-friendly message on ConnectException`() = runTest {
        coEvery { api.searchStocks(input = any()) } throws ConnectException("refused")

        val result = repository.searchStocks("平安银行")

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).isEqualTo("网络连接失败，请检查网络后重试")
    }

    @Test
    fun `searchStocks returns user-friendly message on HTTP 5xx`() = runTest {
        coEvery { api.searchStocks(input = any()) } throws HttpException(
            Response.error<Any>(500, okhttp3.ResponseBody.create(null, ""))
        )

        val result = repository.searchStocks("平安银行")

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).isEqualTo("服务器暂时无法响应，请稍后重试")
    }

    @Test
    fun `searchStocks returns user-friendly message on HTTP 4xx`() = runTest {
        coEvery { api.searchStocks(input = any()) } throws HttpException(
            Response.error<Any>(403, okhttp3.ResponseBody.create(null, ""))
        )

        val result = repository.searchStocks("平安银行")

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).isEqualTo("网络请求失败，请重试")
    }

    @Test
    fun `searchStocks formats Shanghai stock code with sh prefix`() = runTest {
        coEvery { api.searchStocks(input = any()) } returns StockSearchResponse(
            quotationCodeTable = StockSearchResponse.QuotationCodeTable(
                Data = listOf(
                    StockSearchResponse.StockItem(
                        Code = "600519",
                        Name = "贵州茅台",
                        MktNum = "1",
                        SecurityTypeName = "沪A",
                        Classify = "AStock"
                    )
                )
            )
        )

        val result = repository.searchStocks("贵州茅台")

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()!![0].code).isEqualTo("sh.600519")
    }

    @Test
    fun `searchStocks formats Shenzhen stock code with sz prefix`() = runTest {
        coEvery { api.searchStocks(input = any()) } returns StockSearchResponse(
            quotationCodeTable = StockSearchResponse.QuotationCodeTable(
                Data = listOf(
                    StockSearchResponse.StockItem(
                        Code = "000001",
                        Name = "平安银行",
                        MktNum = "0",
                        SecurityTypeName = "深A",
                        Classify = "AStock"
                    )
                )
            )
        )

        val result = repository.searchStocks("平安银行")

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()!![0].code).isEqualTo("sz.000001")
    }

    @Test
    fun `searchStocks returns empty list when Data is null`() = runTest {
        coEvery { api.searchStocks(input = any()) } returns StockSearchResponse(
            quotationCodeTable = StockSearchResponse.QuotationCodeTable(Data = null)
        )

        val result = repository.searchStocks("不存在的股票")

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isEmpty()
    }

    @Test
    fun `addStock returns success on valid input`() = runTest {
        coEvery { dao.insert(any()) } returns 1L
        val searchResult = StockSearchResult("sz.000001", "平安银行", "0")

        val result = repository.addStock(searchResult)

        assertThat(result.isSuccess).isTrue()
    }

    @Test
    fun `addStock inserts correct entity into dao`() = runTest {
        coEvery { dao.insert(any()) } returns 1L
        val searchResult = StockSearchResult("sz.000001", "平安银行", "0")

        repository.addStock(searchResult)

        coVerify { dao.insert(match { it.code == "sz.000001" && it.name == "平安银行" && it.marketCode == "0" }) }
    }

    @Test
    fun `addStock returns user-friendly message on dao failure`() = runTest {
        coEvery { dao.insert(any()) } throws RuntimeException("db error")
        val searchResult = StockSearchResult("sz.000001", "平安银行", "0")

        val result = repository.addStock(searchResult)

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).isEqualTo("操作失败，请重试")
    }

    @Test
    fun `removeStock delegates to dao delete`() = runTest {
        coEvery { dao.delete("sz.000001") } returns Unit

        repository.removeStock("sz.000001")

        coVerify { dao.delete("sz.000001") }
    }

    @Test
    fun `searchStocks filters out HK stocks and bonds`() = runTest {
        coEvery { api.searchStocks(input = any()) } returns StockSearchResponse(
            quotationCodeTable = StockSearchResponse.QuotationCodeTable(
                Data = listOf(
                    StockSearchResponse.StockItem(
                        Code = "601318",
                        Name = "中国平安",
                        MktNum = "1",
                        SecurityTypeName = "沪A",
                        Classify = "AStock"
                    ),
                    StockSearchResponse.StockItem(
                        Code = "02318",
                        Name = "中国平安",
                        MktNum = "2",
                        SecurityTypeName = "港股",
                        Classify = "HK"
                    ),
                    StockSearchResponse.StockItem(
                        Code = "751240",
                        Name = "中国平安",
                        MktNum = "1",
                        SecurityTypeName = "债券",
                        Classify = "Bond"
                    )
                )
            )
        )

        val result = repository.searchStocks("中国平安")

        assertThat(result.isSuccess).isTrue()
        val results = result.getOrNull()!!
        assertThat(results).hasSize(1)
        assertThat(results[0].code).isEqualTo("sh.601318")
    }

    @Test
    fun `addStock with shares parameter creates entity with shares`() = runTest {
        coEvery { dao.insert(any()) } returns 1L
        val searchResult = StockSearchResult("sz.000001", "平安银行", "0")

        repository.addStock(searchResult, shares = 1000)

        coVerify { dao.insert(match { it.shares == 1000 }) }
    }

    @Test
    fun `addStock with no shares defaults to 0`() = runTest {
        coEvery { dao.insert(any()) } returns 1L
        val searchResult = StockSearchResult("sz.000001", "平安银行", "0")

        repository.addStock(searchResult)

        coVerify { dao.insert(match { it.shares == 0 }) }
    }

    @Test
    fun `updateShares delegates to dao with coerced value`() = runTest {
        coEvery { dao.updateShares(any(), any()) } returns Unit

        repository.updateShares("sz.000001", -100)

        coVerify { dao.updateShares("sz.000001", 0) }
    }

    @Test
    fun `updateYieldPeriod delegates to dao`() = runTest {
        coEvery { dao.updateYieldPeriod(any(), any()) } returns Unit

        repository.updateYieldPeriod("sz.000001", "5")

        coVerify { dao.updateYieldPeriod("sz.000001", "5") }
    }

    @Test
    fun `observeStock returns dao flow`() {
        val flow = MutableStateFlow<StockEntity?>(null)
        coEvery { dao.observeByCode("sz.000001") } returns flow

        val result = repository.observeStock("sz.000001")

        assertThat(result).isSameInstanceAs(flow)
    }

    @Test
    fun `observeAllStocks returns dao flow`() {
        val flow = MutableStateFlow<List<StockEntity>>(emptyList())
        coEvery { dao.observeAll() } returns flow

        val result = repository.observeAllStocks()

        assertThat(result).isSameInstanceAs(flow)
    }

    @Test
    fun `fetchQuotes converts API prices from x100 raw integers to yuan`() = runTest {
        val stocks = listOf(
            StockEntity(code = "sh.600519", name = "贵州茅台", marketCode = "1", costPerShare = 1500.0),
            StockEntity(code = "sz.000001", name = "平安银行", marketCode = "0", costPerShare = 12.0, shares = 1000)
        )
        coEvery { quoteApi.getQuotes(secids = "1.600519,0.000001") } returns QuoteResponse(
            data = QuoteData(
                diff = listOf(
                    QuoteItem(price = 146284.0, code = "600519", market = 1),
                    QuoteItem(price = 1109.0, code = "000001", market = 0)
                )
            )
        )

        val prices = repository.fetchQuotes(stocks)

        assertThat(prices["sh.600519"]).isWithin(0.01).of(1462.84)
        assertThat(prices["sz.000001"]).isWithin(0.01).of(11.09)
    }

    @Test
    fun `fetchQuotes returns empty map for empty stock list`() = runTest {
        val prices = repository.fetchQuotes(emptyList())

        assertThat(prices).isEmpty()
    }

    @Test
    fun `fetchQuotes returns empty map on API error`() = runTest {
        val stocks = listOf(
            StockEntity(code = "sh.600519", name = "贵州茅台", marketCode = "1")
        )
        coEvery { quoteApi.getQuotes(secids = any()) } throws RuntimeException("network error")

        val prices = repository.fetchQuotes(stocks)

        assertThat(prices).isEmpty()
    }

    @Test
    fun `fetchQuotes filters out null prices`() = runTest {
        val stocks = listOf(
            StockEntity(code = "sh.600519", name = "贵州茅台", marketCode = "1"),
            StockEntity(code = "sz.000001", name = "平安银行", marketCode = "0")
        )
        coEvery { quoteApi.getQuotes(secids = any()) } returns QuoteResponse(
            data = QuoteData(
                diff = listOf(
                    QuoteItem(price = 146284.0, code = "600519", market = 1),
                    QuoteItem(price = null, code = "000001", market = 0)
                )
            )
        )

        val prices = repository.fetchQuotes(stocks)

        assertThat(prices).hasSize(1)
        assertThat(prices["sh.600519"]).isWithin(0.01).of(1462.84)
        assertThat(prices.containsKey("sz.000001")).isFalse()
    }

    @Test
    fun `fetchQuotes filters out negative prices`() = runTest {
        val stocks = listOf(
            StockEntity(code = "sh.600519", name = "贵州茅台", marketCode = "1"),
            StockEntity(code = "sz.000001", name = "平安银行", marketCode = "0")
        )
        coEvery { quoteApi.getQuotes(secids = any()) } returns QuoteResponse(
            data = QuoteData(
                diff = listOf(
                    QuoteItem(price = 146284.0, code = "600519", market = 1),
                    QuoteItem(price = -1.0, code = "000001", market = 0)
                )
            )
        )

        val prices = repository.fetchQuotes(stocks)

        assertThat(prices).hasSize(1)
        assertThat(prices.containsKey("sz.000001")).isFalse()
    }

    @Test
    fun `fetchQuotes maps market code 1 to sh prefix`() = runTest {
        val stocks = listOf(
            StockEntity(code = "sh.600519", name = "贵州茅台", marketCode = "1")
        )
        coEvery { quoteApi.getQuotes(secids = "1.600519") } returns QuoteResponse(
            data = QuoteData(
                diff = listOf(
                    QuoteItem(price = 146284.0, code = "600519", market = 1)
                )
            )
        )

        val prices = repository.fetchQuotes(stocks)

        assertThat(prices).containsKey("sh.600519")
    }

    @Test
    fun `fetchQuotes maps market code 0 to sz prefix`() = runTest {
        val stocks = listOf(
            StockEntity(code = "sz.000001", name = "平安银行", marketCode = "0")
        )
        coEvery { quoteApi.getQuotes(secids = "0.000001") } returns QuoteResponse(
            data = QuoteData(
                diff = listOf(
                    QuoteItem(price = 1109.0, code = "000001", market = 0)
                )
            )
        )

        val prices = repository.fetchQuotes(stocks)

        assertThat(prices).containsKey("sz.000001")
    }

    @Test
    fun `fetchQuotes returns empty map when response data is null`() = runTest {
        val stocks = listOf(
            StockEntity(code = "sh.600519", name = "贵州茅台", marketCode = "1")
        )
        coEvery { quoteApi.getQuotes(secids = any()) } returns QuoteResponse(data = null)

        val prices = repository.fetchQuotes(stocks)

        assertThat(prices).isEmpty()
    }

    @Test
    fun `addStock with costPerShare creates entity with cost`() = runTest {
        coEvery { dao.insert(any()) } returns 1L
        val searchResult = StockSearchResult("sz.000001", "平安银行", "0")

        repository.addStock(searchResult, shares = 1000, costPerShare = 12.5)

        coVerify { dao.insert(match { it.costPerShare == 12.5 && it.shares == 1000 }) }
    }

    @Test
    fun `updateCostPerShare delegates to dao with coerced value`() = runTest {
        coEvery { dao.updateCostPerShare(any(), any()) } returns Unit

        repository.updateCostPerShare("sz.000001", -5.0)

        coVerify { dao.updateCostPerShare("sz.000001", 0.0) }
    }

    @Test
    fun `updateTargetWeight coerces negative to zero`() = runTest {
        repository.updateTargetWeight("sz.000001", -5.0)

        coVerify { dao.updateTargetWeight("sz.000001", 0.0) }
    }

    @Test
    fun `updateTargetWeight coerces above 100 to 100`() = runTest {
        repository.updateTargetWeight("sz.000001", 150.0)

        coVerify { dao.updateTargetWeight("sz.000001", 100.0) }
    }

    @Test
    fun `resolveStock prefers exact code match`() = runTest {
        coEvery { api.searchStocks(input = "600519") } returns StockSearchResponse(
            quotationCodeTable = StockSearchResponse.QuotationCodeTable(
                Data = listOf(
                    stockItem("600519", "贵州茅台", "1"),
                    stockItem("600520", "三一重工", "1")
                )
            )
        )

        val resolved = repository.resolveStock("600519")

        assertThat(resolved).isNotNull()
        assertThat(resolved!!.code).isEqualTo("sh.600519")
    }

    @Test
    fun `resolveStock falls back to first A-stock for name`() = runTest {
        coEvery { api.searchStocks(input = "贵州茅台") } returns StockSearchResponse(
            quotationCodeTable = StockSearchResponse.QuotationCodeTable(
                Data = listOf(stockItem("600519", "贵州茅台", "1"))
            )
        )

        val resolved = repository.resolveStock("贵州茅台")

        assertThat(resolved).isNotNull()
        assertThat(resolved!!.name).isEqualTo("贵州茅台")
    }

    @Test
    fun resolveStock_normalizesShPrefixedCode() = runTest {
        coEvery { api.searchStocks(input = "600519") } returns StockSearchResponse(
            quotationCodeTable = StockSearchResponse.QuotationCodeTable(
                Data = listOf(stockItem("600519", "贵州茅台", "1"))
            )
        )

        val resolved = repository.resolveStock("sh.600519")

        assertThat(resolved).isNotNull()
        assertThat(resolved!!.code).isEqualTo("sh.600519")
        coVerify { api.searchStocks(input = "600519") }
    }

    @Test
    fun resolveStock_normalizesSzPrefixedCode() = runTest {
        coEvery { api.searchStocks(input = "000001") } returns StockSearchResponse(
            quotationCodeTable = StockSearchResponse.QuotationCodeTable(
                Data = listOf(stockItem("000001", "平安银行", "0"))
            )
        )

        val resolved = repository.resolveStock("SZ000001")

        assertThat(resolved).isNotNull()
        assertThat(resolved!!.code).isEqualTo("sz.000001")
        coVerify { api.searchStocks(input = "000001") }
    }

    @Test
    fun `resolveStock returns null when search yields no results`() = runTest {
        coEvery { api.searchStocks(input = "不存在的") } returns StockSearchResponse(
            quotationCodeTable = null
        )

        assertThat(repository.resolveStock("不存在的")).isNull()
    }

    @Test
    fun `recomputeHolding updates denormalized shares and avg cost from transactions`() = runTest {
        coEvery { transactionDao.getByStock("sh.600519") } returns listOf(
            TransactionEntity(id = 1, stockCode = "sh.600519", type = "BUY", shares = 100, price = 1500.0, date = "2026-01-01"),
            TransactionEntity(id = 2, stockCode = "sh.600519", type = "BUY", shares = 100, price = 1600.0, date = "2026-02-01"),
            TransactionEntity(id = 3, stockCode = "sh.600519", type = "SELL", shares = 50, price = 1700.0, date = "2026-03-01")
        )

        repository.recomputeHolding("sh.600519")

        // 净持仓 = 100 + 100 - 50 = 150
        coVerify { dao.updateShares("sh.600519", 150) }
        // 摊薄成本 = (买入总额 − 卖出总额) / 持仓量
        //         = (1500*100 + 1600*100 − 1700*50) / 150 = 225000 / 150 = 1500
        // （卖出盈利 → 均价从简单加权的 1550 降到 1500）
        coVerify { dao.updateCostPerShare("sh.600519", 1500.0) }
    }

    @Test
    fun `recomputeHolding dilutes cost with realized pnl across buy and sell`() = runTest {
        // 摊薄成本法：已实现盈亏（买卖差额）直接冲减/增加剩余持仓成本，
        // 与交易时间顺序无关（纯累加）。
        coEvery { transactionDao.getByStock("sh.600519") } returns listOf(
            TransactionEntity(id = 1, stockCode = "sh.600519", type = "BUY", shares = 100, price = 10.0, date = "2026-01-01"),
            TransactionEntity(id = 2, stockCode = "sh.600519", type = "BUY", shares = 100, price = 14.0, date = "2026-02-01"),
            TransactionEntity(id = 3, stockCode = "sh.600519", type = "SELL", shares = 100, price = 15.0, date = "2026-03-01"),
            TransactionEntity(id = 4, stockCode = "sh.600519", type = "BUY", shares = 100, price = 16.0, date = "2026-04-01")
        )

        repository.recomputeHolding("sh.600519")

        // 摊薄 = (10*100 + 14*100 − 15*100 + 16*100) / 200 = 2500 / 200 = 12.5
        coVerify { dao.updateShares("sh.600519", 200) }
        coVerify { dao.updateCostPerShare("sh.600519", 12.5) }
    }

    @Test
    fun `importHoldings succeeds for resolvable rows and recompute is called`() = runTest {
        coEvery { api.searchStocks(input = "600519") } returns StockSearchResponse(
            quotationCodeTable = StockSearchResponse.QuotationCodeTable(
                Data = listOf(stockItem("600519", "贵州茅台", "1"))
            )
        )
        coEvery { transactionDao.getByStock("sh.600519") } returns emptyList()

        val summary = repository.importHoldings(
            listOf(ImportRow(rawCodeOrName = "600519", shares = 100, costPerShare = 1500.0))
        )

        assertThat(summary.succeeded).containsExactly("sh.600519")
        assertThat(summary.failed).isEmpty()
        coVerify { dao.insert(any()) }
        coVerify { transactionDao.insert(any()) }
        coVerify { dao.updateShares("sh.600519", 0) }
    }

    @Test
    fun `importHoldings records failed rows without blocking others`() = runTest {
        coEvery { api.searchStocks(input = "600519") } returns StockSearchResponse(
            quotationCodeTable = StockSearchResponse.QuotationCodeTable(
                Data = listOf(stockItem("600519", "贵州茅台", "1"))
            )
        )
        coEvery { api.searchStocks(input = "查无此股") } returns StockSearchResponse(
            quotationCodeTable = null
        )
        coEvery { transactionDao.getByStock("sh.600519") } returns emptyList()

        val summary = repository.importHoldings(
            listOf(
                ImportRow("查无此股", 100, 10.0),
                ImportRow("600519", 100, 1500.0)
            )
        )

        assertThat(summary.succeeded).containsExactly("sh.600519")
        assertThat(summary.failed.map { it.rawCodeOrName }).containsExactly("查无此股")
    }

    // ── importTransactions（交易记录截图导入）──

    @Test
    fun `importTransactions creates missing stock inserts in date order and recomputes`() = runTest {
        coEvery { api.searchStocks(input = "600519") } returns StockSearchResponse(
            quotationCodeTable = StockSearchResponse.QuotationCodeTable(
                Data = listOf(stockItem("600519", "贵州茅台", "1"))
            )
        )
        coEvery { dao.getByCode("sh.600519") } returns null
        // getByStock 回放已插入的交易（供去重检查与 recomputeHolding 读取）
        val insertedTxs = mutableListOf<TransactionEntity>()
        coEvery { transactionDao.insert(capture(insertedTxs)) } returns 1L
        coEvery { transactionDao.getByStock("sh.600519") } returns insertedTxs

        val summary = repository.importTransactions(
            listOf(
                TransactionImportRow("600519", "BUY", 100, 1500.0, "2026-08-02"),
                TransactionImportRow("600519", "BUY", 200, 1400.0, "2026-08-01")
            )
        )

        assertThat(summary.insertedCount).isEqualTo(2)
        assertThat(summary.duplicatesSkipped).isEqualTo(0)
        assertThat(summary.failedRows).isEmpty()
        // 股票不存在 → 先建自选（0 股，不产生初始 BUY 交易）
        coVerify { dao.insert(any()) }
        // 按日期升序插入（FIFO 口径与真实时间一致）
        assertThat(insertedTxs.map { it.date }).containsExactly("2026-08-01", "2026-08-02").inOrder()
        assertThat(insertedTxs.first().note).isEqualTo("截图导入")
        coVerify { dao.updateShares("sh.600519", 300) }
        coVerify { dao.updateCostPerShare("sh.600519", (200 * 1400.0 + 100 * 1500.0) / 300) }
    }

    @Test
    fun `importTransactions skips exact duplicates`() = runTest {
        coEvery { api.searchStocks(input = "600519") } returns StockSearchResponse(
            quotationCodeTable = StockSearchResponse.QuotationCodeTable(
                Data = listOf(stockItem("600519", "贵州茅台", "1"))
            )
        )
        coEvery { dao.getByCode("sh.600519") } returns StockEntity(
            code = "sh.600519", name = "贵州茅台", marketCode = "1", shares = 100
        )
        coEvery { transactionDao.getByStock("sh.600519") } returns listOf(
            TransactionEntity(stockCode = "sh.600519", type = "BUY", shares = 100, price = 1500.0, date = "2026-08-01")
        )

        val summary = repository.importTransactions(
            listOf(TransactionImportRow("600519", "BUY", 100, 1500.0, "2026-08-01"))
        )

        assertThat(summary.insertedCount).isEqualTo(0)
        assertThat(summary.duplicatesSkipped).isEqualTo(1)
        coVerify(exactly = 0) { transactionDao.insert(any()) }
    }

    @Test
    fun `importTransactions records unresolved rows without blocking others`() = runTest {
        coEvery { api.searchStocks(input = "查无此股") } returns StockSearchResponse(
            quotationCodeTable = null
        )
        coEvery { api.searchStocks(input = "600519") } returns StockSearchResponse(
            quotationCodeTable = StockSearchResponse.QuotationCodeTable(
                Data = listOf(stockItem("600519", "贵州茅台", "1"))
            )
        )
        coEvery { dao.getByCode("sh.600519") } returns null
        coEvery { transactionDao.getByStock("sh.600519") } returns emptyList()

        val summary = repository.importTransactions(
            listOf(
                TransactionImportRow("查无此股", "BUY", 100, 10.0, "2026-08-01"),
                TransactionImportRow("600519", "BUY", 100, 1500.0, "2026-08-02")
            )
        )

        assertThat(summary.insertedCount).isEqualTo(1)
        assertThat(summary.failedRows.map { it.rawCodeOrName }).containsExactly("查无此股")
    }

    @Test
    fun `fetchAndCacheIndustry parses f127 and persists`() = runTest {
        coEvery { dao.getByCode("sh.600519") } returns StockEntity(
            code = "sh.600519", name = "贵州茅台", marketCode = "1", shares = 0
        )
        coEvery { quoteApi.getStockInfo(secid = "1.600519") } returns StockInfoResponse(
            data = StockInfoData(code = "600519", name = "贵州茅台", industry = "食品饮料")
        )

        repository.fetchAndCacheIndustry("sh.600519")

        coVerify { dao.updateIndustry("sh.600519", "食品饮料") }
    }

    @Test
    fun `fetchAndCacheIndustry does not write when industry is blank`() = runTest {
        coEvery { dao.getByCode("sh.600519") } returns StockEntity(
            code = "sh.600519", name = "贵州茅台", marketCode = "1", shares = 0
        )
        coEvery { quoteApi.getStockInfo(secid = "1.600519") } returns StockInfoResponse(
            data = StockInfoData(code = "600519", name = "贵州茅台", industry = null)
        )

        repository.fetchAndCacheIndustry("sh.600519")

        coVerify(exactly = 0) { dao.updateIndustry(any(), any()) }
    }

    @Test
    fun `updateIndustryTarget coerces weight into 0-100`() = runTest {
        repository.updateIndustryTarget("银行", -5.0)
        coVerify { industryTargetDao.upsert(IndustryTargetEntity("银行", 0.0)) }

        repository.updateIndustryTarget("银行", 150.0)
        coVerify { industryTargetDao.upsert(IndustryTargetEntity("银行", 100.0)) }
    }

    // ── fetchBoll ────────────────────────────────────────────────────

    @Test
    fun `fetchBoll delegates to klineRepository and BollCalculator`() = runTest {
        // 20 根收盘价 1..20，均值 10.5
        coEvery { klineRepository.fetchCloses("sh.600036", KlinePeriod.WEEKLY) } returns (1..20).map { it.toDouble() }

        val band = repository.fetchBoll("sh.600036")

        assertThat(band).isNotNull()
        band!!
        assertThat(band.middle).isWithin(1e-9).of(10.5)
        assertThat(band.upper).isGreaterThan(band.middle)
        assertThat(band.lower).isLessThan(band.middle)
    }

    @Test
    fun `fetchBoll returns null when closes insufficient`() = runTest {
        coEvery { klineRepository.fetchCloses("sh.600036", KlinePeriod.WEEKLY) } returns (1..5).map { it.toDouble() }

        assertThat(repository.fetchBoll("sh.600036")).isNull()
    }

    @Test
    fun `fetchBoll returns null when klineRepository throws`() = runTest {
        coEvery { klineRepository.fetchCloses("sh.600036", KlinePeriod.WEEKLY) } throws java.io.IOException("down")

        assertThat(repository.fetchBoll("sh.600036")).isNull()
    }

    @Test
    fun `setStockTags clears then inserts normalized distinct tags in a transaction`() = runTest {
        repository.setStockTags("sh.600036", listOf(" 高息 ", "白马", "高息", ""))

        coVerify { stockTagDao.clearForStock("sh.600036") }
        coVerify { stockTagDao.insert(match { it.stockCode == "sh.600036" && it.tag == "高息" }) }
        coVerify { stockTagDao.insert(match { it.stockCode == "sh.600036" && it.tag == "白马" }) }
        // 空/去重后只剩 2 个 insert
        coVerify(exactly = 2) { stockTagDao.insert(any()) }
    }

    // ── 缓存集成：fetchQuotes 写 price_cache ────────────────────────

    @Test
    fun `fetchQuotes writes fetched prices to price_cache`() = runTest {
        val stocks = listOf(
            StockEntity(code = "sh.600519", name = "贵州茅台", marketCode = "1", costPerShare = 1500.0)
        )
        coEvery { quoteApi.getQuotes(secids = "1.600519") } returns QuoteResponse(
            data = QuoteData(diff = listOf(QuoteItem(price = 160000.0, code = "600519", market = 1)))
        )

        repository.fetchQuotes(stocks)

        val cacheSlot = slot<List<PriceCacheEntity>>()
        coVerify { priceCacheDao.upsertAll(capture(cacheSlot)) }
        assertThat(cacheSlot.captured.map { it.code to it.price })
            .containsExactly("sh.600519" to 1600.0)
    }

    @Test
    fun `fetchQuotes does not write cache on network failure`() = runTest {
        val stocks = listOf(
            StockEntity(code = "sh.600519", name = "贵州茅台", marketCode = "1")
        )
        coEvery { quoteApi.getQuotes(secids = any()) } throws java.io.IOException("down")

        repository.fetchQuotes(stocks)

        coVerify(exactly = 0) { priceCacheDao.upsertAll(any()) }
    }

    // ── fetchQuoteSnapshots（完整行情：PE/PB/涨跌/市值等）──────────────

    @Test
    fun `fetchQuoteSnapshots parses full quote into snapshots keyed by app code`() = runTest {
        val stocks = listOf(
            StockEntity(code = "sh.600036", name = "招商银行", marketCode = "1"),
            StockEntity(code = "sz.000001", name = "平安银行", marketCode = "0")
        )
        coEvery { quoteApi.getQuotes(secids = any()) } returns QuoteResponse(
            data = QuoteData(
                diff = listOf(
                    // 招行实测裸值（÷100 规则）：现价 3962→39.62，PE 660→6.60，市值原值
                    QuoteItem(
                        price = 3962.0, changePct = -229.0, pe = 660.0, pb = 90.0,
                        turnoverRate = 72.0, totalMarketCap = 999210282712.0,
                        code = "600036", market = 1
                    ),
                    QuoteItem(
                        price = 1163.0, changePct = 17.0, pe = 389.0, pb = 49.0,
                        code = "000001", market = 0
                    )
                )
            )
        )

        val snapshots = repository.fetchQuoteSnapshots(stocks)

        assertThat(snapshots).hasSize(2)
        val cmb = snapshots["sh.600036"]!!
        assertThat(cmb.price).isWithin(0.01).of(39.62)
        assertThat(cmb.changePct).isWithin(0.01).of(-2.29)
        assertThat(cmb.pe).isWithin(0.001).of(6.60)
        assertThat(cmb.pb).isWithin(0.01).of(0.90)
        assertThat(cmb.turnoverRate).isWithin(0.01).of(0.72)
        // 市值原值不除
        assertThat(cmb.totalMarketCap).isEqualTo(999210282712.0)
        val pab = snapshots["sz.000001"]!!
        assertThat(pab.price).isWithin(0.01).of(11.63)
        assertThat(pab.pe).isWithin(0.01).of(3.89)
    }

    @Test
    fun `fetchQuoteSnapshots returns empty map on network error`() = runTest {
        val stocks = listOf(StockEntity(code = "sh.600036", name = "招商银行", marketCode = "1"))
        coEvery { quoteApi.getQuotes(secids = any()) } throws java.io.IOException("down")

        assertThat(repository.fetchQuoteSnapshots(stocks)).isEmpty()
    }

    @Test
    fun `fetchQuoteSnapshots failure records error log`() = runTest {
        val stocks = listOf(StockEntity(code = "sh.600036", name = "招商银行", marketCode = "1"))
        coEvery { quoteApi.getQuotes(secids = any()) } throws java.io.IOException("down")

        assertThat(repository.fetchQuoteSnapshots(stocks)).isEmpty()

        // 静默失败落日志（设置 → 数据 → 失败日志）
        coVerify(exactly = 1) {
            errorLogRepository.record("行情", "行情获取失败（1 只标的）", any(), any())
        }
    }

    @Test
    fun `fetchQuoteSnapshots returns empty map for empty stock list`() = runTest {
        assertThat(repository.fetchQuoteSnapshots(emptyList())).isEmpty()
        coVerify(exactly = 0) { quoteApi.getQuotes(any(), any(), any()) }
    }

    @Test
    fun `fetchQuoteSnapshots returns empty map when response data is null`() = runTest {
        val stocks = listOf(StockEntity(code = "sh.600036", name = "招商银行", marketCode = "1"))
        coEvery { quoteApi.getQuotes(secids = any()) } returns QuoteResponse(data = null)

        assertThat(repository.fetchQuoteSnapshots(stocks)).isEmpty()
    }

    // ── getCachedPrices ─────────────────────────────────────────────

    @Test
    fun `getCachedPrices returns map from cache entities`() = runTest {
        coEvery { priceCacheDao.getByCodes(listOf("sh.600519", "sz.000001")) } returns listOf(
            PriceCacheEntity("sh.600519", 1600.0, 0L),
            PriceCacheEntity("sz.000001", 12.0, 0L)
        )

        val result = repository.getCachedPrices(listOf("sh.600519", "sz.000001"))

        assertThat(result["sh.600519"]).isEqualTo(1600.0)
        assertThat(result["sz.000001"]).isEqualTo(12.0)
    }

    @Test
    fun `getCachedPrices returns empty for empty input`() = runTest {
        assertThat(repository.getCachedPrices(emptyList())).isEmpty()
        coVerify(exactly = 0) { priceCacheDao.getByCodes(any()) }
    }

    // ── searchStocks 缓存优先 ───────────────────────────────────────

    @Test
    fun `searchStocks returns cached results without network on cache hit`() = runTest {
        coEvery { searchCacheDao.getByQuery("平安银行") } returns listOf(
            SearchCacheEntity("sz.000001", "平安银行", "平安银行", "0", 0L)
        )
        coEvery { priceCacheDao.getByCodes(listOf("sz.000001")) } returns listOf(
            PriceCacheEntity("sz.000001", 12.0, 0L)
        )

        val result = repository.searchStocks("平安银行")

        assertThat(result.isSuccess).isTrue()
        val items = result.getOrThrow()
        assertThat(items).hasSize(1)
        assertThat(items.first().code).isEqualTo("sz.000001")
        assertThat(items.first().currentPrice).isEqualTo(12.0)
        // 缓存命中：不应请求搜索 API
        coVerify(exactly = 0) { api.searchStocks(any(), any(), any(), any()) }
    }

    @Test
    fun `searchStocks writes search cache on network success`() = runTest {
        coEvery { searchCacheDao.getByQuery(any()) } returns emptyList()
        coEvery { api.searchStocks(input = "贵州茅台") } returns StockSearchResponse(
            quotationCodeTable = StockSearchResponse.QuotationCodeTable(
                Data = listOf(stockItem("600519", "贵州茅台", "1"))
            )
        )
        coEvery { quoteApi.getQuotes(any(), any(), any()) } returns QuoteResponse(data = null)

        repository.searchStocks("贵州茅台")

        val cacheSlot = slot<List<SearchCacheEntity>>()
        coVerify { searchCacheDao.upsertAll(capture(cacheSlot)) }
        assertThat(cacheSlot.captured.first().queryKey).isEqualTo("贵州茅台")
        assertThat(cacheSlot.captured.first().code).isEqualTo("sh.600519")
    }

    // ---------- 基本面：双接口合并 ----------

    @Test
    fun `fetchFundamentals merges financials and balance sheet debt ratio`() = runTest {
        coEvery { fundamentalApi.getFundamentals(filter = any()) } returns FundamentalResponse(
            success = true,
            result = FundamentalResponse.FundamentalResult(
                data = listOf(
                    FundamentalResponse.Item(
                        reportDate = "2024-12-31", weightedAvgRoe = 9.15, debtAssetRatio = null,
                        revenueYoy = -1.6, netProfitYoy = -4.2, basicEps = 2.07
                    )
                )
            )
        )
        coEvery { fundamentalApi.getBalanceSheet(filter = any()) } returns BalanceSheetResponse(
            success = true,
            result = BalanceSheetResponse.BalanceSheetResult(
                data = listOf(
                    BalanceSheetResponse.Item(reportDate = "2024-12-31 00:00:00", debtAssetRatio = 90.7)
                )
            )
        )

        val result = repository.fetchFundamentals("sz.000001")

        assertThat(result).isNotNull()
        assertThat(result!!.periods).hasSize(1)
        assertThat(result.periods[0].roe).isEqualTo(9.15)
        // 负债率由资产负债表补全
        assertThat(result.periods[0].debtToAssetRatio).isEqualTo(90.7)
        assertThat(result.periods[0].basicEps).isEqualTo(2.07)
    }

    @Test
    fun `fetchFundamentals degrades debt ratio to null when balance sheet fails`() = runTest {
        coEvery { fundamentalApi.getFundamentals(filter = any()) } returns FundamentalResponse(
            success = true,
            result = FundamentalResponse.FundamentalResult(
                data = listOf(
                    FundamentalResponse.Item(
                        reportDate = "2024-12-31", weightedAvgRoe = 9.15, debtAssetRatio = null,
                        revenueYoy = -1.6, netProfitYoy = -4.2, basicEps = 2.07
                    )
                )
            )
        )
        // 资产负债表接口抛异常 → runCatching 兜底为 null，仅负债率缺失
        coEvery { fundamentalApi.getBalanceSheet(filter = any()) } throws RuntimeException("network")

        val result = repository.fetchFundamentals("sz.000001")

        assertThat(result).isNotNull()
        assertThat(result!!.periods[0].debtToAssetRatio).isNull()
        assertThat(result.periods[0].roe).isEqualTo(9.15)
    }

    @Test
    fun `fetchFundamentals returns null when financials endpoint fails`() = runTest {
        // 主要财务指标接口失败 → 整体降级 null（红线 #2）
        coEvery { fundamentalApi.getFundamentals(filter = any()) } throws RuntimeException("network")
        coEvery { fundamentalApi.getBalanceSheet(filter = any()) } returns BalanceSheetResponse(
            success = true, result = BalanceSheetResponse.BalanceSheetResult(data = emptyList())
        )

        val result = repository.fetchFundamentals("sz.000001")
        assertThat(result).isNull()
    }

    private fun stockItem(code: String, name: String, mktNum: String) = StockSearchResponse.StockItem(
        Code = code,
        Name = name,
        MktNum = mktNum,
        SecurityTypeName = if (mktNum == "1") "沪A" else "深A",
        Classify = "AStock"
    )
}
