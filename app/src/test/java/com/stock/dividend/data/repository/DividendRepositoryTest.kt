package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonObject
import com.stock.dividend.data.local.dao.DividendDao
import com.stock.dividend.data.local.entity.DividendEntity
import com.stock.dividend.data.remote.DividendApi
import com.stock.dividend.data.remote.FundDividendApi
import com.stock.dividend.data.remote.TencentDividendApi
import com.stock.dividend.data.remote.dto.DividendResponse
import com.stock.dividend.data.remote.dto.TencentKlineResponse
import com.stock.dividend.di.EastMoneyDividendApi
import com.stock.dividend.di.TencentDividendSource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.net.ConnectException
import java.net.SocketTimeoutException

class DividendRepositoryTest {

    @TencentDividendSource
    private val tencentApi: TencentDividendApi = mockk()
    @EastMoneyDividendApi
    private val eastMoneyApi: DividendApi = mockk()
    private val fundDividendApi: FundDividendApi = mockk()
    private val dao: DividendDao = mockk(relaxed = true)
    private val repository = DividendRepository(tencentApi, eastMoneyApi, fundDividendApi, dao)

    @Before
    fun setUp() {
        // 默认：东财补充源（股息率元数据）返回空，需要补充效果的用例单独 stub
        coEvery { eastMoneyApi.getDividends(filter = any()) } returns DividendResponse(
            success = true, result = DividendResponse.DividendResult(data = emptyList())
        )
    }

    /** 构造一条 qfqday 记录：前 6 个是 OHLCV 字符串，第 7 个是分红对象（可为 null 表示无分红）。 */
    private fun dayEntry(
        date: String,
        dividend: JsonObject? = null
    ): List<Any> = listOf(date, "1.0", "1.0", "1.0", "1.0", "100").let {
        if (dividend == null) it else it + dividend
    }

    private fun dividendObj(nd: String, fhSh: String, djr: String, cqr: String): JsonObject =
        JsonObject().apply {
            addProperty("nd", nd)
            addProperty("fh_sh", fhSh)
            addProperty("djr", djr)
            addProperty("cqr", cqr)
        }

    private fun klineResponse(days: List<List<*>>): TencentKlineResponse =
        TencentKlineResponse(
            code = 0,
            msg = "",
            data = mapOf("sz000001" to TencentKlineResponse.StockData(qfqday = days))
        )

    /** 腾讯为主源。days=null 表示腾讯异常（用于测试回退）。 */
    private fun stubTencent(days: List<List<*>>?) {
        if (days == null) {
            coEvery { tencentApi.getKline(match { it.startsWith("sz000001,") }) } throws
                SocketTimeoutException("tencent down")
        } else {
            coEvery { tencentApi.getKline(match { it.startsWith("sz000001,") }) } returns klineResponse(days)
        }
    }

    /** 东方财富分红明细条目（PRETAX_BONUS_RMB 为每10股派息，元）。 */
    private fun eastMoneyItem(
        secuCode: String = "000001.SZ",
        reportDate: String = "2024-12-31T00:00:00",
        pretaxBonusRmb: Double = 2.46,
        dividentRatio: Double? = 0.0305,
        exDividendDate: String? = "2025-06-12T00:00:00",
        equityRecordDate: String? = "2025-06-11T00:00:00",
        assignProgress: String? = "实施分配"
    ) = DividendResponse.DividendItem(
        securityCode = "000001",
        secuCode = secuCode,
        securityNameAbbr = "平安银行",
        reportDate = reportDate,
        pretaxBonusRmb = pretaxBonusRmb,
        dividentRatio = dividentRatio,
        exDividendDate = exDividendDate,
        equityRecordDate = equityRecordDate,
        assignProgress = assignProgress
    )

    @Test
    fun `fetchAndCacheDividends returns success with valid data`() = runTest {
        stubTencent(
            listOf(
                dayEntry(
                    "2025-07-11",
                    dividendObj("2024", "20", "2025-07-10", "2025-07-11")
                )
            )
        )

        val result = repository.fetchAndCacheDividends("sz.000001", "000001")

        assertThat(result.isSuccess).isTrue()
    }

    @Test
    fun `fetchAndCacheDividends converts fh_sh to per share by dividing by 10`() = runTest {
        val entitiesSlot = mutableListOf<List<DividendEntity>>()
        coEvery { dao.insertAll(capture(entitiesSlot)) } returns Unit
        stubTencent(
            listOf(
                dayEntry("2025-07-11", dividendObj("2024", "24.6", "2025-07-10", "2025-07-11"))
            )
        )

        repository.fetchAndCacheDividends("sz.000001", "000001")

        val entity = entitiesSlot.last().single()
        assertThat(entity.cashPerShare).isWithin(0.001).of(2.46)
        // 腾讯无股息率字段
        assertThat(entity.dividendYield).isNull()
    }

    @Test
    fun `fetchAndCacheDividends maps cqr to ex-dividend date and djr to record date`() = runTest {
        val entitiesSlot = mutableListOf<List<DividendEntity>>()
        coEvery { dao.insertAll(capture(entitiesSlot)) } returns Unit
        stubTencent(
            listOf(
                dayEntry("2025-07-11", dividendObj("2024", "24.6", "2025-07-10", "2025-07-11"))
            )
        )

        repository.fetchAndCacheDividends("sz.000001", "000001")

        val entity = entitiesSlot.last().single()
        assertThat(entity.exDividendDate).isEqualTo("2025-07-11")
        assertThat(entity.recordDate).isEqualTo("2025-07-10")
    }

    @Test
    fun `fetchAndCacheDividends builds reportDate from fiscal year nd`() = runTest {
        val entitiesSlot = mutableListOf<List<DividendEntity>>()
        coEvery { dao.insertAll(capture(entitiesSlot)) } returns Unit
        stubTencent(
            listOf(
                dayEntry("2025-07-11", dividendObj("2024", "24.6", "2025-07-10", "2025-07-11"))
            )
        )

        repository.fetchAndCacheDividends("sz.000001", "000001")

        val entity = entitiesSlot.last().single()
        assertThat(entity.reportDate).isEqualTo("2024-12-31")
    }

    @Test
    fun `fetchAndCacheDividends persists ex dividend date for calendar`() = runTest {
        val entitiesSlot = mutableListOf<List<DividendEntity>>()
        coEvery { dao.insertAll(capture(entitiesSlot)) } returns Unit
        stubTencent(
            listOf(
                dayEntry("2099-06-18", dividendObj("2098", "30", "2099-06-17", "2099-06-18"))
            )
        )

        repository.fetchAndCacheDividends("sz.000001", "000001")

        val entity = entitiesSlot.last().single()
        assertThat(entity.exDividendDate).isEqualTo("2099-06-18")
        assertThat(entity.recordDate).isEqualTo("2099-06-17")
    }

    @Test
    fun `fetchAndCacheDividends replaces covered rows without wiping history`() = runTest {
        // 历史保留式写入：只删本次结果覆盖到的行（id + 除权日），不再整表清空
        val idsSlot = mutableListOf<List<String>>()
        val exSlot = mutableListOf<List<String>>()
        coEvery { dao.deleteByIds("sz.000001", capture(idsSlot)) } returns Unit
        coEvery { dao.deleteByStockAndExDates("sz.000001", capture(exSlot)) } returns Unit
        coEvery { dao.insertAll(any()) } returns Unit
        stubTencent(
            listOf(
                dayEntry("2025-07-11", dividendObj("2024", "24.6", "2025-07-10", "2025-07-11"))
            )
        )

        repository.fetchAndCacheDividends("sz.000001", "000001")

        coVerify(exactly = 0) { dao.deleteByStockCode(any()) }
        assertThat(idsSlot.last()).containsExactly("sz.000001_2025-07-11")
        assertThat(exSlot.last()).containsExactly("2025-07-11")
        coVerify(ordering = io.mockk.Ordering.ORDERED) {
            dao.deleteByIds(any(), any())
            dao.insertAll(any())
        }
    }

    @Test
    fun `fetchAndCacheDividends purges stale pending rows only in eastmoney fallback`() = runTest {
        // 东财全量路径携带预案信息：exDate=null 且不在本次结果中的失效预案行应被清洗
        stubTencent(null)
        coEvery { eastMoneyApi.getDividends(filter = any()) } returns DividendResponse(
            success = true, result = DividendResponse.DividendResult(data = listOf(eastMoneyItem()))
        )
        coEvery { dao.deleteStalePendingByStock("sz.000001", any()) } returns Unit
        coEvery { dao.insertAll(any()) } returns Unit

        repository.fetchAndCacheDividends("sz.000001", "000001")

        coVerify(exactly = 1) { dao.deleteStalePendingByStock("sz.000001", listOf("sz.000001_2024-12-31")) }
    }

    @Test
    fun `tencent path does not purge pending rows`() = runTest {
        // 腾讯不携带预案信息（只有已实施的分红），不能据其清洗 pending 行
        stubTencent(
            listOf(dayEntry("2025-07-11", dividendObj("2024", "24.6", "2025-07-10", "2025-07-11")))
        )
        coEvery { dao.insertAll(any()) } returns Unit

        repository.fetchAndCacheDividends("sz.000001", "000001")

        coVerify(exactly = 0) { dao.deleteStalePendingByStock(any(), any()) }
    }

    @Test
    fun `fetchAndCacheDividends returns user-friendly message on timeout`() = runTest {
        // 腾讯、东方财富都超时，才最终失败（腾讯失败会回退到东方财富）
        coEvery { tencentApi.getKline(any()) } throws SocketTimeoutException("timeout")
        coEvery { eastMoneyApi.getDividends(filter = any()) } throws SocketTimeoutException("timeout")

        val result = repository.fetchAndCacheDividends("sz.000001", "000001")

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).isEqualTo("网络连接超时，请重试")
    }

    @Test
    fun `fetchAndCacheDividends returns user-friendly message on ConnectException`() = runTest {
        coEvery { tencentApi.getKline(any()) } throws ConnectException("refused")
        coEvery { eastMoneyApi.getDividends(filter = any()) } throws ConnectException("refused")

        val result = repository.fetchAndCacheDividends("sz.000001", "000001")

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).isEqualTo("网络连接失败，请检查网络后重试")
    }

    @Test
    fun `fetchAndCacheDividends returns user-friendly message on HTTP 5xx`() = runTest {
        coEvery { tencentApi.getKline(any()) } throws HttpException(
            Response.error<Any>(502, okhttp3.ResponseBody.create(null, ""))
        )
        coEvery { eastMoneyApi.getDividends(filter = any()) } throws HttpException(
            Response.error<Any>(502, okhttp3.ResponseBody.create(null, ""))
        )

        val result = repository.fetchAndCacheDividends("sz.000001", "000001")

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).isEqualTo("服务器暂时无法响应，请稍后重试")
    }

    @Test
    fun `fetchAndCacheDividends skips entries without dividend object`() = runTest {
        val entitiesSlot = mutableListOf<List<DividendEntity>>()
        coEvery { dao.insertAll(capture(entitiesSlot)) } returns Unit
        stubTencent(
            listOf(
                dayEntry("2025-07-10", dividend = null), // 普通交易日，无分红
                dayEntry("2025-07-11", dividendObj("2024", "24.6", "2025-07-10", "2025-07-11"))
            )
        )

        repository.fetchAndCacheDividends("sz.000001", "000001")

        assertThat(entitiesSlot.last()).hasSize(1)
    }

    @Test
    fun `fetchAndCacheDividends skips entries with zero or invalid fh_sh`() = runTest {
        val entitiesSlot = mutableListOf<List<DividendEntity>>()
        coEvery { dao.insertAll(capture(entitiesSlot)) } returns Unit
        stubTencent(
            listOf(
                dayEntry("2025-07-09", dividendObj("2023", "0", "2025-07-08", "2025-07-09")),
                dayEntry("2025-07-10", dividendObj("2024", "abc", "2025-07-09", "2025-07-10")),
                dayEntry("2025-07-11", dividendObj("2024", "24.6", "2025-07-10", "2025-07-11"))
            )
        )

        repository.fetchAndCacheDividends("sz.000001", "000001")

        // 只有合法那条被解析
        assertThat(entitiesSlot.last()).hasSize(1)
        assertThat(entitiesSlot.last().single().cashPerShare).isWithin(0.001).of(2.46)
    }

    @Test
    fun `fetchAndCacheDividends handles empty qfqday`() = runTest {
        stubTencent(emptyList())
        // 腾讯返回空 → 回退到东方财富，东方财富也空 → 双源空结果不清库（历史分红不可变）
        coEvery { eastMoneyApi.getDividends(filter = any()) } returns DividendResponse(
            success = true, result = DividendResponse.DividendResult(data = emptyList())
        )

        val result = repository.fetchAndCacheDividends("sz.000001", "000001")

        assertThat(result.isSuccess).isTrue()
        coVerify(exactly = 0) { dao.insertAll(any()) }
        coVerify(exactly = 0) { dao.deleteByStockCode(any()) }
    }

    @Test
    fun `fetchAndCacheDividends handles null qfqday`() = runTest {
        coEvery { tencentApi.getKline(any()) } returns TencentKlineResponse(
            code = 0,
            msg = "",
            data = mapOf("sz000001" to TencentKlineResponse.StockData(qfqday = null))
        )
        coEvery { eastMoneyApi.getDividends(filter = any()) } returns DividendResponse(
            success = true, result = DividendResponse.DividendResult(data = emptyList())
        )

        val result = repository.fetchAndCacheDividends("sz.000001", "000001")

        assertThat(result.isSuccess).isTrue()
        coVerify(exactly = 0) { dao.insertAll(any()) }
    }

    @Test
    fun `fetchAndCacheDividends handles null data`() = runTest {
        coEvery { tencentApi.getKline(any()) } returns TencentKlineResponse(
            code = 0, msg = "", data = null
        )
        coEvery { eastMoneyApi.getDividends(filter = any()) } returns DividendResponse(
            success = true, result = DividendResponse.DividendResult(data = emptyList())
        )

        val result = repository.fetchAndCacheDividends("sz.000001", "000001")

        assertThat(result.isSuccess).isTrue()
        coVerify(exactly = 0) { dao.insertAll(any()) }
    }

    @Test
    fun `fetchAndCacheDividends dedups by ex-dividend date across chunks`() = runTest {
        val entitiesSlot = mutableListOf<List<DividendEntity>>()
        coEvery { dao.insertAll(capture(entitiesSlot)) } returns Unit
        // 两块请求都返回同两条（模拟分块重叠），应按除权日去重
        stubTencent(
            listOf(
                dayEntry("2025-06-12", dividendObj("2024", "36.2", "2025-06-11", "2025-06-12")),
                dayEntry("2024-10-10", dividendObj("2024", "24.6", "2024-10-09", "2024-10-10"))
            )
        )

        repository.fetchAndCacheDividends("sz.000001", "000001")

        // 两块各返回 2 条，但 id(除权日)相同，去重后只剩 2 条
        val entities = entitiesSlot.last()
        assertThat(entities).hasSize(2)
        val byCash = entities.map { it.cashPerShare }.sorted()
        assertThat(byCash[0]).isWithin(0.001).of(2.46)
        assertThat(byCash[1]).isWithin(0.001).of(3.62)
    }

    @Test
    fun `fetchAndCacheDividends converts sh code to tencent format`() = runTest {
        val entitiesSlot = mutableListOf<List<DividendEntity>>()
        coEvery { dao.insertAll(capture(entitiesSlot)) } returns Unit
        coEvery { tencentApi.getKline(match { it.startsWith("sh600398,") }) } returns TencentKlineResponse(
            code = 0,
            msg = "",
            data = mapOf("sh600398" to TencentKlineResponse.StockData(
                qfqday = listOf(
                    dayEntry("2026-05-11", dividendObj("2025", "41", "2026-05-08", "2026-05-11"))
                )
            ))
        )

        repository.fetchAndCacheDividends("sh.600398", "600398")

        val entity = entitiesSlot.last().single()
        assertThat(entity.cashPerShare).isWithin(0.001).of(4.1)
        assertThat(entity.reportDate).isEqualTo("2025-12-31")
        assertThat(entity.exDividendDate).isEqualTo("2026-05-11")
        assertThat(entity.recordDate).isEqualTo("2026-05-08")
    }

    @Test
    fun `fetchAndCacheDividends falls back to eastmoney when tencent throws`() = runTest {
        val entitiesSlot = mutableListOf<List<DividendEntity>>()
        coEvery { dao.insertAll(capture(entitiesSlot)) } returns Unit
        stubTencent(null) // 腾讯异常
        coEvery { eastMoneyApi.getDividends(filter = any()) } returns DividendResponse(
            success = true,
            result = DividendResponse.DividendResult(
                data = listOf(eastMoneyItem(pretaxBonusRmb = 2.46, dividentRatio = 0.0593))
            )
        )

        repository.fetchAndCacheDividends("sz.000001", "000001")

        // 应来自东方财富：每股派息 = 2.46/10，股息率 = 0.0593*100
        val entity = entitiesSlot.last().single()
        assertThat(entity.cashPerShare).isWithin(0.001).of(0.246)
        assertThat(entity.dividendYield).isWithin(0.01).of(5.93)
        assertThat(entity.exDividendDate).isEqualTo("2025-06-12")
        assertThat(entity.recordDate).isEqualTo("2025-06-11")
        coVerify(exactly = 1) { eastMoneyApi.getDividends(filter = any()) }
    }

    @Test
    fun `fetchAndCacheDividends falls back to eastmoney when tencent returns empty`() = runTest {
        val entitiesSlot = mutableListOf<List<DividendEntity>>()
        coEvery { dao.insertAll(capture(entitiesSlot)) } returns Unit
        stubTencent(emptyList()) // 腾讯无数据
        coEvery { eastMoneyApi.getDividends(filter = any()) } returns DividendResponse(
            success = true,
            result = DividendResponse.DividendResult(
                data = listOf(eastMoneyItem())
            )
        )

        repository.fetchAndCacheDividends("sz.000001", "000001")

        assertThat(entitiesSlot.last()).hasSize(1)
        coVerify(exactly = 1) { eastMoneyApi.getDividends(filter = any()) }
    }

    @Test
    fun `fetchAndCacheDividends uses tencent when it has data and skips eastmoney`() = runTest {
        val entitiesSlot = mutableListOf<List<DividendEntity>>()
        coEvery { dao.insertAll(capture(entitiesSlot)) } returns Unit
        stubTencent(
            listOf(
                dayEntry("2025-07-11", dividendObj("2024", "24.6", "2025-07-10", "2025-07-11"))
            )
        )
        // 东财补充源（股息率元数据）异常应被静默吞掉，不影响腾讯主源结果
        coEvery { eastMoneyApi.getDividends(filter = any()) } throws SocketTimeoutException("em down")

        repository.fetchAndCacheDividends("sz.000001", "000001")

        assertThat(entitiesSlot.last()).hasSize(1)
        // 腾讯主源数据仍在（东财补充失败不破坏主流程）
        assertThat(entitiesSlot.last().single().cashPerShare).isWithin(0.001).of(2.46)
    }

    @Test
    fun `fetchAndCacheDividends enriches dividendYield from eastmoney by ex-dividend date`() = runTest {
        val entitiesSlot = mutableListOf<List<DividendEntity>>()
        coEvery { dao.insertAll(capture(entitiesSlot)) } returns Unit
        stubTencent(
            listOf(
                // 两条：一条东财有对应除权日记录，一条没有
                dayEntry("2025-07-11", dividendObj("2024", "24.6", "2025-07-10", "2025-07-11")),
                dayEntry("2024-10-10", dividendObj("2023", "20", "2024-10-09", "2024-10-10"))
            )
        )
        coEvery { eastMoneyApi.getDividends(filter = any()) } returns DividendResponse(
            success = true,
            result = DividendResponse.DividendResult(
                data = listOf(eastMoneyItem(dividentRatio = 0.0593, exDividendDate = "2025-07-11T00:00:00"))
            )
        )

        repository.fetchAndCacheDividends("sz.000001", "000001")

        val entities = entitiesSlot.last().sortedBy { it.exDividendDate }
        // 除权日对齐上的那条补到了历史股息率快照（0.0593 → 5.93%）
        assertThat(entities[1].dividendYield).isWithin(0.01).of(5.93)
        // 对不上的那条保持 null，不臆造
        assertThat(entities[0].dividendYield).isNull()
    }

    @Test
    fun `fetchAndCacheDividends merges scheduled not yet ex dividend record from eastmoney`() = runTest {
        // 海尔智家场景（2026-08-20 实测）：年度分红已公告实施、除权日在明天——腾讯分红嵌在历史
        // K 线里结构性拉不到未来记录，须由东财明细按除权日对齐补入；未除权预案（exDate=null）不合并
        val entitiesSlot = mutableListOf<List<DividendEntity>>()
        coEvery { dao.insertAll(capture(entitiesSlot)) } returns Unit
        stubTencent(
            listOf(
                dayEntry("2025-07-11", dividendObj("2024", "24.6", "2025-07-10", "2025-07-11"))
            )
        )
        coEvery { eastMoneyApi.getDividends(filter = any()) } returns DividendResponse(
            success = true,
            result = DividendResponse.DividendResult(
                data = listOf(
                    // 与腾讯按除权日对齐：仅补历史股息率快照，不重复合并
                    eastMoneyItem(dividentRatio = 0.0593, exDividendDate = "2025-07-11T00:00:00"),
                    // 已排期未除权（实施分配、除权日 2026-08-21）：合并入库
                    eastMoneyItem(
                        reportDate = "2025-12-31T00:00:00",
                        pretaxBonusRmb = 8.9151,
                        dividentRatio = null,
                        exDividendDate = "2026-08-21 00:00:00",
                        equityRecordDate = "2026-08-20 00:00:00",
                        assignProgress = "实施分配"
                    ),
                    // 预案（除权日未定、金额可能变）：不合并
                    eastMoneyItem(
                        reportDate = "2026-06-30T00:00:00",
                        pretaxBonusRmb = 3.0,
                        exDividendDate = null,
                        assignProgress = "预披露"
                    )
                )
            )
        )

        repository.fetchAndCacheDividends("sz.000001", "000001")

        val entities = entitiesSlot.last().sortedBy { it.exDividendDate }
        assertThat(entities).hasSize(2)
        // 已除权的腾讯主记录补到股息率快照
        assertThat(entities[0].dividendYield).isWithin(0.01).of(5.93)
        // 已排期未除权的东财补充记录：每股派息、除权/登记日、进度齐全（TTM/日历按此计入）
        val scheduled = entities[1]
        assertThat(scheduled.id).isEqualTo("sz.000001_2025-12-31")
        assertThat(scheduled.cashPerShare).isWithin(1e-9).of(0.89151)
        assertThat(scheduled.exDividendDate).isEqualTo("2026-08-21")
        assertThat(scheduled.recordDate).isEqualTo("2026-08-20")
        assertThat(scheduled.planStatus).isEqualTo("实施分配")
    }

    @Test
    fun `fetchAndCacheDividends keeps tencent only when eastmoney has no extra records`() = runTest {
        // 东财明细与腾讯按除权日全部对齐时零合并：行数与原 enrich 行为一致
        val entitiesSlot = mutableListOf<List<DividendEntity>>()
        coEvery { dao.insertAll(capture(entitiesSlot)) } returns Unit
        stubTencent(
            listOf(
                dayEntry("2025-07-11", dividendObj("2024", "24.6", "2025-07-10", "2025-07-11"))
            )
        )
        coEvery { eastMoneyApi.getDividends(filter = any()) } returns DividendResponse(
            success = true,
            result = DividendResponse.DividendResult(
                data = listOf(
                    eastMoneyItem(dividentRatio = 0.0593, exDividendDate = "2025-07-11T00:00:00")
                )
            )
        )

        repository.fetchAndCacheDividends("sz.000001", "000001")

        assertThat(entitiesSlot.last()).hasSize(1)
        assertThat(entitiesSlot.last().single().dividendYield).isWithin(0.01).of(5.93)
    }

    @Test
    fun `fetchAndCacheDividends keeps null yield when eastmoney enrich fails`() = runTest {
        val entitiesSlot = mutableListOf<List<DividendEntity>>()
        coEvery { dao.insertAll(capture(entitiesSlot)) } returns Unit
        stubTencent(
            listOf(
                dayEntry("2025-07-11", dividendObj("2024", "24.6", "2025-07-10", "2025-07-11"))
            )
        )
        coEvery { eastMoneyApi.getDividends(filter = any()) } throws ConnectException("em refused")

        val result = repository.fetchAndCacheDividends("sz.000001", "000001")

        // 补充源失败静默：主流程成功、数据保留、股息率保持 null
        assertThat(result.isSuccess).isTrue()
        assertThat(entitiesSlot.last().single().dividendYield).isNull()
    }

    @Test
    fun `fetchAndCacheDividends strips space time from eastmoney dates in fallback`() = runTest {
        val entitiesSlot = mutableListOf<List<DividendEntity>>()
        coEvery { dao.insertAll(capture(entitiesSlot)) } returns Unit
        stubTencent(null)
        coEvery { eastMoneyApi.getDividends(filter = any()) } returns DividendResponse(
            success = true,
            result = DividendResponse.DividendResult(
                data = listOf(
                    eastMoneyItem(
                        secuCode = "600398.SH",
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
        assertThat(entity.cashPerShare).isWithin(0.001).of(0.41)
        assertThat(entity.reportDate).isEqualTo("2025-12-31")
        assertThat(entity.exDividendDate).isEqualTo("2026-05-11")
        assertThat(entity.recordDate).isEqualTo("2026-05-08")
    }

    @Test
    fun `observeDividends returns dao flow`() {
        val flow = MutableStateFlow<List<DividendEntity>>(emptyList())
        coEvery { dao.observeByStock("sz.000001") } returns flow

        val result = repository.observeDividends("sz.000001")

        assertThat(result).isSameInstanceAs(flow)
    }

    // ---------- 2026-08-20 审计 H2 修复：分块窗口不赌超窗截断 ----------

    @Test
    fun `tencent fetch uses three 2-year blocks within 640-bar limit`() = runTest {
        // 实测：3 年窗口（≈730 交易日）超 640 上限时腾讯锚定最新端截头约 4 个月——
        // 落在洞里的除权分红永久丢失（中国移动 2023-09-01 的 10派22.247 实证）。
        // 修复后：三块各 2 年（≈487 交易日 < 640 必完整），端点相接覆盖 6 年。
        val params = mutableListOf<String>()
        coEvery { tencentApi.getKline(any()) } answers {
            params.add(firstArg())
            klineResponse(emptyList())
        }

        repository.fetchAndCacheDividends("sz.000001", "000001")

        assertThat(params).hasSize(3)
        val today = java.time.LocalDate.now()
        val expected = listOf(
            today.minusYears(2) to today,
            today.minusYears(4) to today.minusYears(2),
            today.minusYears(6) to today.minusYears(4)
        )
        expected.forEach { (start, end) ->
            val param = params.firstOrNull { it.endsWith(",$start,$end,640,qfq") }
            assertThat(param).isNotNull()   // 每块窗口精确 2 年且不超 640 根
        }
    }

    // ---------- 2026-08-22 场内基金（ETF/LOF）专用分红源 ----------

    /** 真实 fundf10 fhsp 页结构裁剪（红利 ETF 510880 实测响应节选，含跨年多笔年份单元格）。 */
    private val fundHtml = """
        <html><body>
        <table class='w782 comm cfxq'><thead><tr><th class='first'>年份</th><th>权益登记日</th><th>除息日</th><th>每10份分红</th><th class='last'>分红发放日</th></tr></thead><tbody>
        <tr><td>2026年</td><td>2026-01-20</td><td>2026-01-21</td><td>每10份派现金1.4300元</td><td>2026-01-26</td></tr>
        <tr><td>2010年</td><td>2010-07-14</td><td>2010-07-15</td><td>每10份派现金0.2000元</td><td>2010-07-21</td></tr>
        </tbody></table>
        </body></html>
    """.trimIndent()

    @Test
    fun `fund fetches dividends from fund f10 page instead of tencent or eastmoney`() = runTest {
        coEvery { fundDividendApi.getFundDividendHtml("510880") } returns fundHtml

        repository.fetchAndCacheDividends("sh.510880", "510880")

        coVerify(exactly = 0) { tencentApi.getKline(any()) }
        coVerify(exactly = 0) { eastMoneyApi.getDividends(filter = any()) }
        coVerify(exactly = 1) { fundDividendApi.getFundDividendHtml("510880") }
    }

    @Test
    fun `fund dividends parse per-share amount and dates from html`() = runTest {
        coEvery { fundDividendApi.getFundDividendHtml("510880") } returns fundHtml

        repository.fetchAndCacheDividends("sh.510880", "510880")

        val entitiesSlot = mutableListOf<List<DividendEntity>>()
        coVerify { dao.insertAll(capture(entitiesSlot)) }
        val entities = entitiesSlot.first().first { it.exDividendDate == "2026-01-21" }
        assertThat(entities.id).isEqualTo("sh.510880_2026-01-21")
        assertThat(entities.stockCode).isEqualTo("sh.510880")
        assertThat(entities.cashPerShare).isWithin(1e-9).of(0.143)   // 每10份派现金1.43 → ÷10
        assertThat(entities.exDividendDate).isEqualTo("2026-01-21")  // 除息日
        assertThat(entities.recordDate).isEqualTo("2026-01-20")      // 权益登记日
        assertThat(entities.reportDate).isEqualTo("2026-12-31")      // 年份列 → 年末日
        assertThat(entities.dividendYield).isNull()                  // f10 无股息率快照
    }

    @Test
    fun `fund html with no dividends writes nothing and succeeds`() = runTest {
        // 未分红基金（如 510300）f10 页返回「暂无分红」，无 cfxq 数据行
        coEvery { fundDividendApi.getFundDividendHtml("510300") } returns
            "<html><body><div>暂无分红</div></body></html>"

        val result = repository.fetchAndCacheDividends("sh.510300", "510300")

        assertThat(result.isSuccess).isTrue()
        coVerify(exactly = 0) { dao.insertAll(any()) }
        coVerify(exactly = 0) { dao.deleteByStockAndExDates(any(), any()) }
    }

    @Test
    fun `fund f10 network failure degrades to silent empty`() = runTest {
        coEvery { fundDividendApi.getFundDividendHtml("510880") } throws
            SocketTimeoutException("f10 down")

        val result = repository.fetchAndCacheDividends("sh.510880", "510880")

        assertThat(result.isSuccess).isTrue()   // 失败静默（与腾讯主源同语义），不误清历史
        coVerify(exactly = 0) { dao.insertAll(any()) }
    }
}
