package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.local.dao.StockDao
import com.stock.dividend.data.local.entity.StockEntity
import com.stock.dividend.data.remote.QuoteApi
import com.stock.dividend.data.remote.SearchApi
import com.stock.dividend.data.remote.dto.QuoteData
import com.stock.dividend.data.remote.dto.QuoteItem
import com.stock.dividend.data.remote.dto.QuoteResponse
import com.stock.dividend.data.remote.dto.StockSearchResponse
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
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
    private val repository = StockRepository(api, quoteApi, dao)

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
}
