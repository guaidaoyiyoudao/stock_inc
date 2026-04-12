package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.local.dao.StockDao
import com.stock.dividend.data.local.entity.StockEntity
import com.stock.dividend.data.remote.EastMoneyApi
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

    private val api: EastMoneyApi = mockk()
    private val dao: StockDao = mockk(relaxed = true)
    private val repository = StockRepository(api, dao)

    @Test
    fun `searchStocks returns filtered A-stock results`() = runTest {
        coEvery { api.searchStocks(input = "平安银行") } returns StockSearchResponse(
            quotationCodeTable = StockSearchResponse.QuotationCodeTable(
                Data = listOf(
                    StockSearchResponse.StockItem(
                        Code = "000001",
                        Name = "平安银行",
                        MktNum = "0",
                        SecurityTypeName = "A股"
                    ),
                    StockSearchResponse.StockItem(
                        Code = "000001",
                        Name = "平安银行",
                        MktNum = "0",
                        SecurityTypeName = "ETF"
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
                        SecurityTypeName = "A股"
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
                        SecurityTypeName = "A股"
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
    fun `observeAllStocks returns dao flow`() {
        val flow = MutableStateFlow<List<StockEntity>>(emptyList())
        coEvery { dao.observeAll() } returns flow

        val result = repository.observeAllStocks()

        assertThat(result).isSameInstanceAs(flow)
    }
}
