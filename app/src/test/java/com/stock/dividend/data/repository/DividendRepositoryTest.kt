package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.local.dao.DividendDao
import com.stock.dividend.data.local.entity.DividendEntity
import com.stock.dividend.data.remote.DividendApi
import com.stock.dividend.data.remote.dto.DividendResponse
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.net.ConnectException
import java.net.SocketTimeoutException

class DividendRepositoryTest {

    private val api: DividendApi = mockk()
    private val dao: DividendDao = mockk(relaxed = true)
    private val repository = DividendRepository(api, dao)

    @Test
    fun `fetchAndCacheDividends returns success with valid data`() = runTest {
        coEvery { api.getDividends(filter = any()) } returns DividendResponse(
            success = true,
            result = DividendResponse.DividendResult(
                data = listOf(
                    DividendResponse.DividendItem(
                        securityCode = "000001",
                        secuCode = "000001.SZ",
                        securityNameAbbr = "平安银行",
                        reportDate = "2024-12-31T00:00:00",
                        pretaxBonusRmb = 3.62,
                        dividentRatio = 0.0305,
                        exDividendDate = "2025-06-12T00:00:00",
                        equityRecordDate = "2025-06-11T00:00:00",
                        assignProgress = "实施分配"
                    )
                )
            )
        )

        val result = repository.fetchAndCacheDividends("sz.000001", "000001")

        assertThat(result.isSuccess).isTrue()
    }

    @Test
    fun `fetchAndCacheDividends converts pretaxBonusRmb by dividing by 10`() = runTest {
        val entitiesSlot = mutableListOf<List<DividendEntity>>()
        coEvery { dao.insertAll(capture(entitiesSlot)) } returns Unit

        coEvery { api.getDividends(filter = any()) } returns DividendResponse(
            success = true,
            result = DividendResponse.DividendResult(
                data = listOf(
                    DividendResponse.DividendItem(
                        securityCode = "000001",
                        secuCode = "000001.SZ",
                        securityNameAbbr = "平安银行",
                        reportDate = "2024-12-31T00:00:00",
                        pretaxBonusRmb = 2.46,
                        dividentRatio = 0.0593,
                        exDividendDate = null,
                        equityRecordDate = null,
                        assignProgress = null
                    )
                )
            )
        )

        repository.fetchAndCacheDividends("sz.000001", "000001")

        val entity = entitiesSlot.last()[0]
        assertThat(entity.cashPerShare).isWithin(0.001).of(0.246)
        assertThat(entity.dividendYield).isWithin(0.01).of(5.93)
    }

    @Test
    fun `fetchAndCacheDividends strips T00-00-00 from dates`() = runTest {
        val entitiesSlot = mutableListOf<List<DividendEntity>>()
        coEvery { dao.insertAll(capture(entitiesSlot)) } returns Unit

        coEvery { api.getDividends(filter = any()) } returns DividendResponse(
            success = true,
            result = DividendResponse.DividendResult(
                data = listOf(
                    DividendResponse.DividendItem(
                        securityCode = "000001",
                        secuCode = "000001.SZ",
                        securityNameAbbr = "平安银行",
                        reportDate = "2024-12-31T00:00:00",
                        pretaxBonusRmb = 2.46,
                        dividentRatio = null,
                        exDividendDate = "2025-07-11T00:00:00",
                        equityRecordDate = "2025-07-10T00:00:00",
                        assignProgress = null
                    )
                )
            )
        )

        repository.fetchAndCacheDividends("sz.000001", "000001")

        val entity = entitiesSlot.last()[0]
        assertThat(entity.reportDate).isEqualTo("2024-12-31")
        assertThat(entity.exDividendDate).isEqualTo("2025-07-11")
        assertThat(entity.recordDate).isEqualTo("2025-07-10")
    }

    @Test
    fun `fetchAndCacheDividends strips space time from eastmoney dates`() = runTest {
        val entitiesSlot = mutableListOf<List<DividendEntity>>()
        coEvery { dao.insertAll(capture(entitiesSlot)) } returns Unit

        coEvery { api.getDividends(filter = any()) } returns DividendResponse(
            success = true,
            result = DividendResponse.DividendResult(
                data = listOf(
                    DividendResponse.DividendItem(
                        securityCode = "600398",
                        secuCode = "600398.SH",
                        securityNameAbbr = "海澜之家",
                        reportDate = "2025-12-31 00:00:00",
                        pretaxBonusRmb = 4.1,
                        dividentRatio = 0.062,
                        exDividendDate = "2026-05-11 00:00:00",
                        equityRecordDate = "2026-05-08 00:00:00",
                        assignProgress = "实施分配"
                    )
                )
            )
        )

        repository.fetchAndCacheDividends("sh.600398", "600398")

        val entity = entitiesSlot.last().single()
        assertThat(entity.reportDate).isEqualTo("2025-12-31")
        assertThat(entity.exDividendDate).isEqualTo("2026-05-11")
        assertThat(entity.recordDate).isEqualTo("2026-05-08")
    }

    @Test
    fun `fetchAndCacheDividends persists ex dividend date for calendar`() = runTest {
        val entitiesSlot = mutableListOf<List<DividendEntity>>()
        coEvery { dao.insertAll(capture(entitiesSlot)) } returns Unit

        coEvery { api.getDividends(filter = any()) } returns DividendResponse(
            success = true,
            result = DividendResponse.DividendResult(
                data = listOf(
                    DividendResponse.DividendItem(
                        securityCode = "000001",
                        secuCode = "000001.SZ",
                        securityNameAbbr = "平安银行",
                        reportDate = "2098-12-31T00:00:00",
                        pretaxBonusRmb = 3.0,
                        dividentRatio = null,
                        exDividendDate = "2099-06-18T00:00:00",
                        equityRecordDate = "2099-06-17T00:00:00",
                        assignProgress = "实施分配"
                    )
                )
            )
        )

        repository.fetchAndCacheDividends("sz.000001", "000001")

        val entity = entitiesSlot.last().single()
        assertThat(entity.exDividendDate).isEqualTo("2099-06-18")
        assertThat(entity.recordDate).isEqualTo("2099-06-17")
    }

    @Test
    fun `fetchAndCacheDividends persists plan notice date for yearly plan calendar`() = runTest {
        val entitiesSlot = mutableListOf<List<DividendEntity>>()
        coEvery { dao.insertAll(capture(entitiesSlot)) } returns Unit

        coEvery { api.getDividends(filter = any()) } returns DividendResponse(
            success = true,
            result = DividendResponse.DividendResult(
                data = listOf(
                    DividendResponse.DividendItem(
                        securityCode = "600398",
                        secuCode = "600398.SH",
                        securityNameAbbr = "海澜之家",
                        reportDate = "2026-06-30 00:00:00",
                        pretaxBonusRmb = 1.0,
                        dividentRatio = null,
                        exDividendDate = null,
                        equityRecordDate = null,
                        assignProgress = "预披露",
                        planNoticeDate = "2026-04-30 00:00:00"
                    )
                )
            )
        )

        repository.fetchAndCacheDividends("sh.600398", "600398")

        assertThat(entitiesSlot.last().single().planNoticeDate).isEqualTo("2026-04-30")
    }

    @Test
    fun `fetchAndCacheDividends skips rows for a different secucode`() = runTest {
        val entitiesSlot = mutableListOf<List<DividendEntity>>()
        coEvery { dao.insertAll(capture(entitiesSlot)) } returns Unit

        coEvery { api.getDividends(filter = any()) } returns DividendResponse(
            success = true,
            result = DividendResponse.DividendResult(
                data = listOf(
                    DividendResponse.DividendItem(
                        securityCode = "000001",
                        secuCode = "000001.SH",
                        securityNameAbbr = "测试股票",
                        reportDate = "2024-12-31T00:00:00",
                        pretaxBonusRmb = 2.46,
                        dividentRatio = null,
                        exDividendDate = "2025-07-11T00:00:00",
                        equityRecordDate = "2025-07-10T00:00:00",
                        assignProgress = "实施分配"
                    )
                )
            )
        )

        repository.fetchAndCacheDividends("sz.000001", "000001")

        assertThat(entitiesSlot.last()).isEmpty()
    }

    @Test
    fun `fetchAndCacheDividends deletes old data before inserting new`() = runTest {
        val deleteSlot = mutableListOf<String>()
        coEvery { dao.deleteByStockCode(capture(deleteSlot)) } returns Unit
        coEvery { api.getDividends(filter = any()) } returns DividendResponse(
            success = true,
            result = DividendResponse.DividendResult(data = emptyList())
        )

        repository.fetchAndCacheDividends("sz.000001", "000001")

        coVerify(ordering = io.mockk.Ordering.ORDERED) {
            dao.deleteByStockCode("sz.000001")
            dao.insertAll(any())
        }
    }

    @Test
    fun `fetchAndCacheDividends returns user-friendly message on timeout`() = runTest {
        coEvery { api.getDividends(filter = any()) } throws SocketTimeoutException("timeout")

        val result = repository.fetchAndCacheDividends("sz.000001", "000001")

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).isEqualTo("网络连接超时，请重试")
    }

    @Test
    fun `fetchAndCacheDividends returns user-friendly message on ConnectException`() = runTest {
        coEvery { api.getDividends(filter = any()) } throws ConnectException("refused")

        val result = repository.fetchAndCacheDividends("sz.000001", "000001")

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).isEqualTo("网络连接失败，请检查网络后重试")
    }

    @Test
    fun `fetchAndCacheDividends returns user-friendly message on HTTP 5xx`() = runTest {
        coEvery { api.getDividends(filter = any()) } throws HttpException(
            Response.error<Any>(502, okhttp3.ResponseBody.create(null, ""))
        )

        val result = repository.fetchAndCacheDividends("sz.000001", "000001")

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).isEqualTo("服务器暂时无法响应，请稍后重试")
    }

    @Test
    fun `fetchAndCacheDividends skips items with zero cash dividend`() = runTest {
        coEvery { api.getDividends(filter = any()) } returns DividendResponse(
            success = true,
            result = DividendResponse.DividendResult(
                data = listOf(
                    DividendResponse.DividendItem(
                        securityCode = "000001",
                        securityNameAbbr = "平安银行",
                        reportDate = "2024-12-31T00:00:00",
                        pretaxBonusRmb = 0.0,
                        dividentRatio = null,
                        exDividendDate = null,
                        equityRecordDate = null,
                        assignProgress = null
                    )
                )
            )
        )

        val result = repository.fetchAndCacheDividends("sz.000001", "000001")

        assertThat(result.isSuccess).isTrue()
        coVerify { dao.insertAll(emptyList()) }
    }

    @Test
    fun `fetchAndCacheDividends skips items with null reportDate`() = runTest {
        coEvery { api.getDividends(filter = any()) } returns DividendResponse(
            success = true,
            result = DividendResponse.DividendResult(
                data = listOf(
                    DividendResponse.DividendItem(
                        securityCode = "000001",
                        securityNameAbbr = "平安银行",
                        reportDate = null,
                        pretaxBonusRmb = 1.0,
                        dividentRatio = null,
                        exDividendDate = null,
                        equityRecordDate = null,
                        assignProgress = null
                    )
                )
            )
        )

        val result = repository.fetchAndCacheDividends("sz.000001", "000001")

        assertThat(result.isSuccess).isTrue()
        coVerify { dao.insertAll(emptyList()) }
    }

    @Test
    fun `fetchAndCacheDividends handles empty response data`() = runTest {
        coEvery { api.getDividends(filter = any()) } returns DividendResponse(
            success = true,
            result = DividendResponse.DividendResult(data = emptyList())
        )

        val result = repository.fetchAndCacheDividends("sz.000001", "000001")

        assertThat(result.isSuccess).isTrue()
    }

    @Test
    fun `fetchAndCacheDividends handles null result`() = runTest {
        coEvery { api.getDividends(filter = any()) } returns DividendResponse(
            success = true,
            result = null
        )

        val result = repository.fetchAndCacheDividends("sz.000001", "000001")

        assertThat(result.isSuccess).isTrue()
    }

    @Test
    fun `fetchAndCacheDividends handles multiple valid items`() = runTest {
        val entitiesSlot = mutableListOf<List<DividendEntity>>()
        coEvery { dao.insertAll(capture(entitiesSlot)) } returns Unit

        coEvery { api.getDividends(filter = any()) } returns DividendResponse(
            success = true,
            result = DividendResponse.DividendResult(
                data = listOf(
                    DividendResponse.DividendItem(
                        securityCode = "000001",
                        securityNameAbbr = "平安银行",
                        reportDate = "2024-12-31T00:00:00",
                        pretaxBonusRmb = 3.62,
                        dividentRatio = 0.0305,
                        exDividendDate = "2025-06-12T00:00:00",
                        equityRecordDate = null,
                        assignProgress = "实施分配"
                    ),
                    DividendResponse.DividendItem(
                        securityCode = "000001",
                        securityNameAbbr = "平安银行",
                        reportDate = "2024-06-30T00:00:00",
                        pretaxBonusRmb = 2.46,
                        dividentRatio = 0.021,
                        exDividendDate = "2024-10-10T00:00:00",
                        equityRecordDate = null,
                        assignProgress = "实施分配"
                    )
                )
            )
        )

        repository.fetchAndCacheDividends("sz.000001", "000001")

        val entities = entitiesSlot.last()
        assertThat(entities).hasSize(2)
        assertThat(entities[0].cashPerShare).isWithin(0.001).of(0.362)
        assertThat(entities[1].cashPerShare).isWithin(0.001).of(0.246)
    }

    @Test
    fun `observeDividends returns dao flow`() {
        val flow = MutableStateFlow<List<DividendEntity>>(emptyList())
        coEvery { dao.observeByStock("sz.000001") } returns flow

        val result = repository.observeDividends("sz.000001")

        assertThat(result).isSameInstanceAs(flow)
    }
}
