package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.local.AppDatabase
import com.stock.dividend.data.local.dao.IndustryTargetDao
import com.stock.dividend.data.local.dao.StockDao
import com.stock.dividend.data.local.dao.TransactionDao
import com.stock.dividend.data.local.entity.IndustryTargetEntity
import com.stock.dividend.data.local.entity.StockEntity
import com.stock.dividend.data.local.entity.TransactionEntity
import com.stock.dividend.data.remote.QuoteApi
import com.stock.dividend.data.remote.SearchApi
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
    private val dao: StockDao = mockk(relaxed = true)
    private val transactionDao: TransactionDao = mockk(relaxed = true)
    private val industryTargetDao: IndustryTargetDao = mockk(relaxed = true)
    private val appDatabase: AppDatabase = mockk(relaxed = true)
    private val repository = StockRepository(api, quoteApi, dao, transactionDao, industryTargetDao, appDatabase)

    @org.junit.Before
    fun setUp() {
        // Room 的 withTransaction 扩展函数默认会走真实 DB 事务，单测里 mock 为「直接执行 block」。
        mockkStatic("androidx.room.RoomDatabaseKt")
        val blockSlot = slot<suspend () -> Any>()
        coEvery { appDatabase.withTransaction(capture(blockSlot)) } coAnswers {
            blockSlot.captured.invoke()
        }
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
    fun `fetchQuotes converts API prices from fen to yuan`() = runTest {
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
        // 买入加权均价 = (1500*100 + 1600*100) / 200 = 1550
        coVerify { dao.updateCostPerShare("sh.600519", 1550.0) }
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

    private fun stockItem(code: String, name: String, mktNum: String) = StockSearchResponse.StockItem(
        Code = code,
        Name = name,
        MktNum = mktNum,
        SecurityTypeName = if (mktNum == "1") "沪A" else "深A",
        Classify = "AStock"
    )
}
