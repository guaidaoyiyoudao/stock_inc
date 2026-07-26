package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.remote.TencentDividendApi
import com.stock.dividend.data.remote.dto.TencentKlineResponse
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Test

class KlineRepositoryTest {

    private val tencentApi: TencentDividendApi = mockk()
    private val repository = KlineRepository(tencentApi)

    @Test
    fun `fetchWeeklyCloses parses close column from qfqweek in ascending order`() = runTest {
        coEvery { tencentApi.getKline(any()) } returns klineResponse(
            listOf(
                listOf("2026-07-21", "10.00", "10.50", "10.80", "9.90", "1000"),
                listOf("2026-07-28", "10.50", "11.00", "11.20", "10.40", "1200")
            )
        )

        val closes = repository.fetchWeeklyCloses("sh.600036")

        assertThat(closes).containsExactly(10.50, 11.00).inOrder()
    }

    @Test
    fun `buildParam uses week type qfq adjust and sh tencent code`() {
        val param = repository.buildParam("sh600036", weeks = 40)

        // {code},week,{start},{end},{count},qfq
        assertThat(param).startsWith("sh600036,week,")
        assertThat(param).endsWith(",640,qfq")
        // 含 start 与 end 两个 ISO 日期（YYYY-MM-DD）
        val parts = param.split(",")
        assertThat(parts).hasSize(6)
        assertThat(parts[1]).isEqualTo("week")
        assertThat(parts[5]).isEqualTo("qfq")
        assertThat(parts[2]).matches("""\d{4}-\d{2}-\d{2}""")
        assertThat(parts[3]).matches("""\d{4}-\d{2}-\d{2}""")
    }

    @Test
    fun `fetchWeeklyCloses passes tencent code to api`() = runTest {
        val paramSlot = slot<String>()
        coEvery { tencentApi.getKline(capture(paramSlot)) } returns klineResponse(emptyList())

        repository.fetchWeeklyCloses("sz.000001")

        coVerify { tencentApi.getKline(any()) }
        assertThat(paramSlot.captured).startsWith("sz000001,week,")
    }

    @Test
    fun `fetchWeeklyCloses filters out non-positive and invalid closes`() = runTest {
        coEvery { tencentApi.getKline(any()) } returns klineResponse(
            listOf(
                listOf("2026-07-21", "10.00", "10.50", "10.80", "9.90", "1000"),
                listOf("2026-07-28", "0.00", "0.00", "0.00", "0.00", "0"),     // close=0 过滤
                listOf("2026-08-04", "11.00", "abc", "11.50", "10.90", "800")  // close 非数字过滤
            )
        )

        val closes = repository.fetchWeeklyCloses("sh.600036")

        assertThat(closes).containsExactly(10.50)
    }

    @Test
    fun `fetchWeeklyCloses returns empty when data is null`() = runTest {
        coEvery { tencentApi.getKline(any()) } returns TencentKlineResponse(code = 0, msg = null, data = null)

        assertThat(repository.fetchWeeklyCloses("sh.600036")).isEmpty()
    }

    @Test
    fun `fetchWeeklyCloses returns empty when both qfqweek and qfqday are null`() = runTest {
        coEvery { tencentApi.getKline(any()) } returns TencentKlineResponse(
            code = 0,
            msg = null,
            data = mapOf("sh600036" to TencentKlineResponse.StockData(qfqday = null, qfqweek = null))
        )

        assertThat(repository.fetchWeeklyCloses("sh.600036")).isEmpty()
    }

    @Test
    fun `fetchWeeklyCloses falls back to qfqday when qfqweek absent`() = runTest {
        // 接口偶发只返回日线（qfqday）时，仍应能取到收盘价，避免 BOLL 整体失效。
        coEvery { tencentApi.getKline(any()) } returns TencentKlineResponse(
            code = 0,
            msg = null,
            data = mapOf(
                "sh600036" to TencentKlineResponse.StockData(
                    qfqday = listOf(listOf("2026-07-21", "10.00", "10.50", "10.80", "9.90", "1000")),
                    qfqweek = null
                )
            )
        )

        val closes = repository.fetchWeeklyCloses("sh.600036")

        assertThat(closes).containsExactly(10.50)
    }

    @Test
    fun `fetchWeeklyCloses returns empty on unknown code prefix`() = runTest {
        // 非 sh./sz. 前缀 → toTencentCode 返回 null，不发请求
        val closes = repository.fetchWeeklyCloses("600036")

        assertThat(closes).isEmpty()
        coVerify(exactly = 0) { tencentApi.getKline(any()) }
    }

    @Test
    fun `fetchWeeklyCloses returns empty on network exception`() = runTest {
        coEvery { tencentApi.getKline(any()) } throws java.io.IOException("down")

        assertThat(repository.fetchWeeklyCloses("sh.600036")).isEmpty()
    }

    private fun klineResponse(rows: List<List<*>>): TencentKlineResponse = TencentKlineResponse(
        code = 0,
        msg = null,
        data = mapOf("sh600036" to TencentKlineResponse.StockData(qfqday = null, qfqweek = rows))
    )
}
