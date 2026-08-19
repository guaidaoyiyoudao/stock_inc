package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.local.dao.DividendDao
import com.stock.dividend.data.local.dao.KlineCacheDao
import com.stock.dividend.data.local.entity.KlineCacheEntity
import com.stock.dividend.data.local.entity.KlineCacheMetaEntity
import com.stock.dividend.data.remote.TencentDividendApi
import com.stock.dividend.data.remote.dto.TencentKlineResponse
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

class KlineRepositoryTest {

    private val tencentApi: TencentDividendApi = mockk()
    private val klineCacheDao: KlineCacheDao = mockk(relaxed = true)
    private val dividendDao: DividendDao = mockk(relaxed = true)
    private val repository = KlineRepository(tencentApi, klineCacheDao, dividendDao)

    @Before
    fun setUp() {
        // 默认无缓存（读取走全量拉取路径），涉及缓存的用例单独覆盖 stub
        coEvery { klineCacheDao.getBars(any(), any()) } returns emptyList()
        coEvery { klineCacheDao.getMeta(any(), any()) } returns null
        coEvery { dividendDao.getLatestExDividendDate(any()) } returns null
    }

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
        val param = repository.buildParam("sh600036", KlinePeriod.WEEKLY, 40)

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

    @Test
    fun `monthly buildParam uses month type and long lookback`() {
        val param = repository.buildParam("sh600036", KlinePeriod.MONTHLY, 40)
        assertThat(param).startsWith("sh600036,month,")
        assertThat(param).endsWith(",640,qfq")
    }

    // ── parseKlineBars 纯函数（实测腾讯 fqkline 数组格式）──────────────
    // 数组下标：[0]date [1]open [2]close [3]high [4]low [5]volume，均为字符串

    @Test
    fun `parseKlineBars parses OHLCV in order`() {
        // 取自实测腾讯返回（招行 600036 前复权日线片段）
        val rows = listOf(
            listOf("2024-10-08", "37.354", "35.984", "37.354", "34.804", "2973039.000"),
            listOf("2024-10-09", "35.684", "32.994", "35.704", "32.994", "1855932.000")
        )

        val bars = parseKlineBars(rows)

        assertThat(bars).hasSize(2)
        val first = bars[0]
        assertThat(first.date).isEqualTo("2024-10-08")
        assertThat(first.open).isWithin(0.001).of(37.354)
        assertThat(first.close).isWithin(0.001).of(35.984)
        assertThat(first.high).isWithin(0.001).of(37.354)
        assertThat(first.low).isWithin(0.001).of(34.804)
        assertThat(first.volume).isEqualTo(2973039.0)
    }

    @Test
    fun `parseKlineBars drops rows with non-positive close`() {
        val rows = listOf(
            listOf("2024-10-08", "37.354", "35.984", "37.354", "34.804", "2973039.000"),
            listOf("2024-10-09", "0.000", "0.000", "0.000", "0.000", "0"),
            listOf("2024-10-10", "33.384", "34.714", "35.484", "33.384", "1433298.000")
        )

        val bars = parseKlineBars(rows)

        assertThat(bars).hasSize(2)
        assertThat(bars.map { it.date }).containsExactly("2024-10-08", "2024-10-10").inOrder()
    }

    @Test
    fun `parseKlineBars drops rows with non-numeric close`() {
        val rows = listOf(
            listOf("2024-10-08", "37.354", "35.984", "37.354", "34.804", "2973039.000"),
            listOf("2024-10-09", "33.384", "abc", "35.484", "33.384", "1433298.000")
        )

        val bars = parseKlineBars(rows)

        assertThat(bars).hasSize(1)
        assertThat(bars[0].close).isWithin(0.001).of(35.984)
    }

    @Test
    fun `parseKlineBars treats missing ohl as close fallback`() {
        // OHLC 任一缺失时降级用 close 填充（不丢整根 K 线）；volume 缺失视为 0
        val rows = listOf(
            listOf("2024-10-08", "37.354", "35.984")  // 仅有 open+close
        )

        val bars = parseKlineBars(rows)

        assertThat(bars).hasSize(1)
        val bar = bars[0]
        assertThat(bar.close).isWithin(0.001).of(35.984)
        assertThat(bar.open).isWithin(0.001).of(37.354)
        // high/low 缺失 → 降级为 close
        assertThat(bar.high).isEqualTo(bar.close)
        assertThat(bar.low).isEqualTo(bar.close)
        assertThat(bar.volume).isEqualTo(0.0)
    }

    @Test
    fun `parseKlineBars returns empty for empty rows`() {
        assertThat(parseKlineBars(emptyList())).isEmpty()
    }

    @Test
    fun `parseKlineBars drops rows with blank date`() {
        val rows = listOf(
            listOf("", "37.354", "35.984", "37.354", "34.804", "1000"),
            listOf("2024-10-08", "37.354", "35.984", "37.354", "34.804", "1000")
        )

        val bars = parseKlineBars(rows)

        assertThat(bars).hasSize(1)
        assertThat(bars[0].date).isEqualTo("2024-10-08")
    }

    // ── fetchKlines（Repository 层，mock TencentDividendApi）──────────

    @Test
    fun `fetchKlines returns full OHLCV bars for weekly period`() = runTest {
        coEvery { tencentApi.getKline(any()) } returns klineResponse(
            listOf(
                listOf("2026-07-21", "10.00", "10.50", "10.80", "9.90", "1000"),
                listOf("2026-07-28", "10.50", "11.00", "11.20", "10.40", "1200")
            )
        )

        val bars = repository.fetchKlines("sh.600036", KlinePeriod.WEEKLY)

        assertThat(bars).hasSize(2)
        assertThat(bars[0].close).isWithin(0.001).of(10.50)
        assertThat(bars[0].high).isWithin(0.001).of(10.80)
        assertThat(bars[0].low).isWithin(0.001).of(9.90)
        assertThat(bars[0].volume).isEqualTo(1000.0)
    }

    @Test
    fun `fetchKlines returns empty on network exception`() = runTest {
        coEvery { tencentApi.getKline(any()) } throws java.io.IOException("down")

        assertThat(repository.fetchKlines("sh.600036", KlinePeriod.WEEKLY)).isEmpty()
    }

    @Test
    fun `fetchKlines returns empty for unknown code prefix`() = runTest {
        // 非 sh./sz. → toTencentCode 返回 null，不发请求
        assertThat(repository.fetchKlines("600036", KlinePeriod.WEEKLY)).isEmpty()
        coVerify(exactly = 0) { tencentApi.getKline(any()) }
    }

    @Test
    fun `fetchKlines returns empty when qfqweek null and qfqday null`() = runTest {
        coEvery { tencentApi.getKline(any()) } returns TencentKlineResponse(
            code = 0,
            msg = null,
            data = mapOf("sh600036" to TencentKlineResponse.StockData(qfqday = null, qfqweek = null))
        )

        assertThat(repository.fetchKlines("sh.600036", KlinePeriod.WEEKLY)).isEmpty()
    }

    @Test
    fun `fetchKlines monthly falls back to qfqweek when qfqmonth absent`() = runTest {
        coEvery { tencentApi.getKline(any()) } returns TencentKlineResponse(
            code = 0,
            msg = null,
            data = mapOf(
                "sh600036" to TencentKlineResponse.StockData(
                    qfqday = null,
                    qfqweek = listOf(listOf("2026-06-30", "10.0", "10.5", "11.0", "9.8", "5000")),
                    qfqmonth = null
                )
            )
        )

        val bars = repository.fetchKlines("sh.600036", KlinePeriod.MONTHLY)

        assertThat(bars).hasSize(1)
        assertThat(bars[0].close).isWithin(0.001).of(10.5)
    }

    // ── 本地缓存编排（永久缓存：历史永不重拉/每日一次补尾/除权漂移全量重建/失败回退）──

    private val today: LocalDate = LocalDate.now()

    private fun cacheBar(date: String, close: Double, period: String = "WEEKLY") = KlineCacheEntity(
        stockCode = "sh.600036", period = period, date = date,
        open = close, high = close, low = close, close = close, volume = 0.0
    )

    private fun klineResponseDaily(rows: List<List<*>>): TencentKlineResponse = TencentKlineResponse(
        code = 0, msg = null,
        data = mapOf("sh600036" to TencentKlineResponse.StockData(qfqday = rows, qfqweek = null))
    )

    @Test
    fun `first fetch writes bars and meta to cache`() = runTest {
        coEvery { tencentApi.getKline(any()) } returns klineResponse(
            listOf(
                listOf("2026-07-21", "10.00", "10.50", "10.80", "9.90", "1000"),
                listOf("2026-07-28", "10.50", "11.00", "11.20", "10.40", "1200")
            )
        )

        val closes = repository.fetchCloses("sh.600036", KlinePeriod.WEEKLY)

        assertThat(closes).containsExactly(10.50, 11.00).inOrder()
        coVerify(exactly = 1) { klineCacheDao.replaceBars("sh.600036", "WEEKLY", any()) }
        coVerify {
            klineCacheDao.upsertMeta(match {
                it.stockCode == "sh.600036" && it.period == "WEEKLY" && it.lastExDividendDate == null
            })
        }
    }

    @Test
    fun `tail-current cache serves without network`() = runTest {
        // 日线缓存尾=今天 → 已覆盖本周期最新一根，零网络
        coEvery { klineCacheDao.getBars("sh.600036", "DAILY") } returns listOf(
            cacheBar(today.minusDays(1).toString(), 10.5, "DAILY"),
            cacheBar(today.toString(), 11.0, "DAILY")
        )
        coEvery { klineCacheDao.getMeta("sh.600036", "DAILY") } returns
            KlineCacheMetaEntity("sh.600036", "DAILY", System.currentTimeMillis() - 60_000L, null)

        val closes = repository.fetchCloses("sh.600036", KlinePeriod.DAILY)

        assertThat(closes).containsExactly(10.50, 11.00).inOrder()
        coVerify(exactly = 0) { tencentApi.getKline(any()) }
    }

    @Test
    fun `tail-current cache returns only last N bars`() = runTest {
        coEvery { klineCacheDao.getBars("sh.600036", "DAILY") } returns listOf(
            cacheBar(today.minusDays(1).toString(), 10.5, "DAILY"),
            cacheBar(today.toString(), 11.0, "DAILY")
        )
        coEvery { klineCacheDao.getMeta("sh.600036", "DAILY") } returns
            KlineCacheMetaEntity("sh.600036", "DAILY", System.currentTimeMillis() - 60_000L, null)

        val closes = repository.fetchCloses("sh.600036", KlinePeriod.DAILY, bars = 1)

        assertThat(closes).containsExactly(11.00).inOrder()
        coVerify(exactly = 0) { tencentApi.getKline(any()) }
    }

    @Test
    fun `weekly tail within current week serves without network`() = runTest {
        // 周线最后一根落在本周（周一及以后）→ 本周正在形成的 K 线已被覆盖
        coEvery { klineCacheDao.getBars("sh.600036", "WEEKLY") } returns listOf(
            cacheBar(today.with(java.time.DayOfWeek.MONDAY).toString(), 11.0)
        )
        coEvery { klineCacheDao.getMeta("sh.600036", "WEEKLY") } returns
            KlineCacheMetaEntity("sh.600036", "WEEKLY", System.currentTimeMillis() - 60_000L, null)

        val closes = repository.fetchCloses("sh.600036", KlinePeriod.WEEKLY)

        assertThat(closes).containsExactly(11.00).inOrder()
        coVerify(exactly = 0) { tencentApi.getKline(any()) }
    }

    @Test
    fun `synced today skips network even when tail stale`() = runTest {
        // 停牌/长假期：尾部落后但今日已同步过（空结果也标记）→ 当日不再发请求
        coEvery { klineCacheDao.getBars("sh.600036", "DAILY") } returns listOf(
            cacheBar(today.minusDays(5).toString(), 10.0, "DAILY")
        )
        coEvery { klineCacheDao.getMeta("sh.600036", "DAILY") } returns
            KlineCacheMetaEntity("sh.600036", "DAILY", System.currentTimeMillis(), null)

        val closes = repository.fetchCloses("sh.600036", KlinePeriod.DAILY)

        assertThat(closes).containsExactly(10.0).inOrder()
        coVerify(exactly = 0) { tencentApi.getKline(any()) }
    }

    @Test
    fun `stale tail fetches incremental window from last cached date`() = runTest {
        val lastDate = today.minusDays(3)
        coEvery { klineCacheDao.getBars("sh.600036", "DAILY") } returns listOf(
            cacheBar(lastDate.minusDays(1).toString(), 10.0, "DAILY"),
            cacheBar(lastDate.toString(), 10.5, "DAILY")
        )
        // 3 天前同步过 → 今日未同步 → 补尾
        coEvery { klineCacheDao.getMeta("sh.600036", "DAILY") } returns
            KlineCacheMetaEntity("sh.600036", "DAILY", System.currentTimeMillis() - 3L * 24 * 60 * 60 * 1000, null)
        val paramSlot = slot<String>()
        coEvery { tencentApi.getKline(capture(paramSlot)) } returns klineResponseDaily(
            listOf(
                listOf(lastDate.toString(), "10.4", "10.6", "10.7", "10.3", "900"),  // 尾根修正
                listOf(today.toString(), "10.6", "10.8", "10.9", "10.5", "1100")     // 新增
            )
        )

        val closes = repository.fetchCloses("sh.600036", KlinePeriod.DAILY)

        // 增量窗口：从最后一根缓存日期（含）到今天
        val parts = paramSlot.captured.split(",")
        assertThat(parts[0]).isEqualTo("sh600036")
        assertThat(parts[1]).isEqualTo("day")
        assertThat(parts[2]).isEqualTo(lastDate.toString())
        coVerify { klineCacheDao.upsertBars(any()) }
        coVerify { klineCacheDao.trimToRecent("sh.600036", "DAILY", 800) }
        // 合并：尾根被远端覆盖，新增一根
        assertThat(closes).containsExactly(10.0, 10.6, 10.8).inOrder()
    }

    @Test
    fun `incremental network failure falls back to cached bars`() = runTest {
        coEvery { klineCacheDao.getBars("sh.600036", "DAILY") } returns listOf(
            cacheBar(today.minusDays(3).toString(), 10.0, "DAILY")
        )
        coEvery { klineCacheDao.getMeta("sh.600036", "DAILY") } returns
            KlineCacheMetaEntity("sh.600036", "DAILY", System.currentTimeMillis() - 3L * 24 * 60 * 60 * 1000, null)
        coEvery { tencentApi.getKline(any()) } throws java.io.IOException("down")

        val closes = repository.fetchCloses("sh.600036", KlinePeriod.DAILY)

        // 断网也能看缓存（红线 #2）
        assertThat(closes).containsExactly(10.0).inOrder()
        coVerify(exactly = 0) { klineCacheDao.upsertBars(any()) }
        coVerify(exactly = 0) { klineCacheDao.upsertMeta(any()) }
    }

    @Test
    fun `new ex-dividend date triggers full rebuild`() = runTest {
        coEvery { klineCacheDao.getBars("sh.600036", "WEEKLY") } returns listOf(
            cacheBar("2026-08-14", 10.0)
        )
        // 缓存写入后出现了新除权日 → 前复权全历史漂移，必须全量重建
        coEvery { klineCacheDao.getMeta("sh.600036", "WEEKLY") } returns
            KlineCacheMetaEntity("sh.600036", "WEEKLY", System.currentTimeMillis(), "2025-06-12")
        coEvery { dividendDao.getLatestExDividendDate("sh.600036") } returns "2026-05-11"
        val paramSlot = slot<String>()
        coEvery { tencentApi.getKline(capture(paramSlot)) } returns klineResponse(
            listOf(listOf("2026-08-14", "9.5", "9.8", "10.0", "9.4", "800"))
        )

        val closes = repository.fetchCloses("sh.600036", KlinePeriod.WEEKLY)

        coVerify(exactly = 1) { klineCacheDao.replaceBars("sh.600036", "WEEKLY", any()) }
        coVerify(exactly = 0) { klineCacheDao.upsertBars(any()) }  // 全量重建而非增量 upsert
        // 全量窗口起点（回看 ~2 年）≠ 缓存尾日期（增量起点）
        assertThat(paramSlot.captured.split(",")[2]).isNotEqualTo("2026-08-14")
        assertThat(closes).containsExactly(9.8).inOrder()
        coVerify { klineCacheDao.upsertMeta(match { it.lastExDividendDate == "2026-05-11" }) }
    }

    @Test
    fun `empty incremental response keeps cache and refreshes meta`() = runTest {
        coEvery { klineCacheDao.getBars("sh.600036", "DAILY") } returns listOf(
            cacheBar(today.minusDays(3).toString(), 10.0, "DAILY")
        )
        coEvery { klineCacheDao.getMeta("sh.600036", "DAILY") } returns
            KlineCacheMetaEntity("sh.600036", "DAILY", System.currentTimeMillis() - 3L * 24 * 60 * 60 * 1000, null)
        // 窗口内确无新 K 线（节假日/停牌）：成功但空
        coEvery { tencentApi.getKline(any()) } returns klineResponseDaily(emptyList())

        val closes = repository.fetchCloses("sh.600036", KlinePeriod.DAILY)

        assertThat(closes).containsExactly(10.0).inOrder()
        coVerify(exactly = 0) { klineCacheDao.upsertBars(any()) }
        coVerify { klineCacheDao.upsertMeta(any()) }  // 标记今日已同步，当日不再请求
    }

    @Test
    fun `klineTailIsCurrent boundaries for three periods`() {
        val fixedToday = LocalDate.of(2026, 8, 17)
        val monday = fixedToday.with(java.time.DayOfWeek.MONDAY)

        // 日线：已含今天才算完整
        assertThat(klineTailIsCurrent("2026-08-17", KlinePeriod.DAILY, fixedToday)).isTrue()
        assertThat(klineTailIsCurrent("2026-08-16", KlinePeriod.DAILY, fixedToday)).isFalse()
        // 周线：本周周一及以后
        assertThat(klineTailIsCurrent(monday.toString(), KlinePeriod.WEEKLY, fixedToday)).isTrue()
        assertThat(klineTailIsCurrent("2026-08-14", KlinePeriod.WEEKLY, fixedToday)).isFalse()
        // 月线：本月 1 号及以后
        assertThat(klineTailIsCurrent("2026-08-01", KlinePeriod.MONTHLY, fixedToday)).isTrue()
        assertThat(klineTailIsCurrent("2026-07-31", KlinePeriod.MONTHLY, fixedToday)).isFalse()
        // 解析失败不阻塞读取
        assertThat(klineTailIsCurrent("garbage", KlinePeriod.DAILY, fixedToday)).isTrue()
    }

    private fun klineResponse(rows: List<List<*>>): TencentKlineResponse = TencentKlineResponse(
        code = 0,
        msg = null,
        data = mapOf("sh600036" to TencentKlineResponse.StockData(qfqday = null, qfqweek = rows))
    )
}
