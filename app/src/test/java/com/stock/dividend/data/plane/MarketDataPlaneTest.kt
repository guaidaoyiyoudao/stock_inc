package com.stock.dividend.data.plane

import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.local.entity.DividendEntity
import com.stock.dividend.data.local.entity.StockEntity
import com.stock.dividend.data.repository.BondYieldRepository
import com.stock.dividend.data.repository.DividendRepository
import com.stock.dividend.data.repository.ErrorLogRepository
import com.stock.dividend.data.repository.FinancialStatementsRepository
import com.stock.dividend.data.repository.Fundamentals
import com.stock.dividend.data.repository.FundamentalsCacheRepository
import com.stock.dividend.data.repository.IndexQuote
import com.stock.dividend.data.repository.KlinePeriod
import com.stock.dividend.data.repository.KlineRepository
import com.stock.dividend.data.repository.MarketDataRepository
import com.stock.dividend.data.repository.QuoteSnapshot
import com.stock.dividend.data.repository.ResearchRepository
import com.stock.dividend.data.repository.StockRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

/** 数据平面单元测试：会话缓存 / 并发合并 / 分红新鲜度策略 / 统一口径。 */
class MarketDataPlaneTest {

    private val stockRepository = mockk<StockRepository>()
    private val dividendRepository = mockk<DividendRepository>(relaxed = true)
    private val klineRepository = mockk<KlineRepository>(relaxUnitFun = true)
    private val fundamentalsCacheRepository = mockk<FundamentalsCacheRepository>()
    private val financialStatementsRepository = mockk<FinancialStatementsRepository>()
    private val marketDataRepository = mockk<MarketDataRepository>()
    private val bondYieldRepository = mockk<BondYieldRepository>()
    private val researchRepository = mockk<ResearchRepository>()
    private val errorLogRepository = mockk<ErrorLogRepository>(relaxed = true)
    private val store = FakeFreshnessStore()

    private lateinit var plane: MarketDataPlane

    /** 可控时钟（ms）。 */
    private var now = 1_000_000L

    private val stock = StockEntity(code = "sh.600036", name = "招商银行", marketCode = "1")
    private val snapshot = QuoteSnapshot(stockCode = "sh.600036", price = 10.0)

    @Before
    fun setup() {
        plane = MarketDataPlane(
            stockRepository = stockRepository,
            dividendRepository = dividendRepository,
            klineRepository = klineRepository,
            fundamentalsCacheRepository = fundamentalsCacheRepository,
            financialStatementsRepository = financialStatementsRepository,
            marketDataRepository = marketDataRepository,
            bondYieldRepository = bondYieldRepository,
            researchRepository = researchRepository,
            dividendFreshnessStore = store,
            errorLogRepository = errorLogRepository
        )
        plane.nowProvider = { now }
    }

    // ── 行情：会话缓存 / 并发合并 ─────────────────────────

    @Test
    fun `first fetch goes network and caches session`() = runTest {
        coEvery { stockRepository.fetchQuoteSnapshots(listOf(stock)) } returns
            mapOf("sh.600036" to snapshot)

        val result = plane.getQuoteSnapshots(listOf(stock))

        assertThat(result["sh.600036"]?.price).isEqualTo(10.0)
        coVerify(exactly = 1) { stockRepository.fetchQuoteSnapshots(any()) }
    }

    @Test
    fun `fresh window reuses memory without network`() = runTest {
        coEvery { stockRepository.fetchQuoteSnapshots(listOf(stock)) } returns
            mapOf("sh.600036" to snapshot)

        plane.getQuoteSnapshots(listOf(stock))
        now += PlanePolicy.QUOTE_FRESH_MS - 1     // 窗口内
        plane.getQuoteSnapshots(listOf(stock))

        coVerify(exactly = 1) { stockRepository.fetchQuoteSnapshots(any()) }
    }

    @Test
    fun `expired window or force refetches`() = runTest {
        coEvery { stockRepository.fetchQuoteSnapshots(listOf(stock)) } returns
            mapOf("sh.600036" to snapshot)

        plane.getQuoteSnapshots(listOf(stock))
        now += PlanePolicy.QUOTE_FRESH_MS + 1     // 窗口过期
        plane.getQuoteSnapshots(listOf(stock))
        plane.getQuoteSnapshots(listOf(stock), force = true)

        coVerify(exactly = 3) { stockRepository.fetchQuoteSnapshots(any()) }
    }

    @Test
    fun `getPrices keeps only positive prices`() = runTest {
        val stale = snapshot.copy(price = null)
        coEvery { stockRepository.fetchQuoteSnapshots(any()) } returns
            mapOf("sh.600036" to snapshot, "sz.000001" to stale)

        val prices = plane.getPrices(
            listOf(stock, stock.copy(code = "sz.000001", marketCode = "0"))
        )

        assertThat(prices).containsExactly("sh.600036", 10.0)
    }

    @Test
    fun `concurrent same batch merges into one request`() = runTest {
        val gate = CompletableDeferred<Unit>()
        coEvery { stockRepository.fetchQuoteSnapshots(any()) } coAnswers {
            gate.await()
            mapOf("sh.600036" to snapshot)
        }

        val jobs = (1..5).map { async { plane.getQuoteSnapshots(listOf(stock)) } }
        gate.complete(Unit)
        val results = jobs.awaitAll()

        assertThat(results).hasSize(5)
        results.forEach { assertThat(it["sh.600036"]?.price).isEqualTo(10.0) }
        coVerify(exactly = 1) { stockRepository.fetchQuoteSnapshots(any()) }
    }

    @Test
    fun `clearSessionCaches drops memory so fresh window refetches`() = runTest {
        coEvery { stockRepository.fetchQuoteSnapshots(listOf(stock)) } returns
            mapOf("sh.600036" to snapshot)

        plane.getQuoteSnapshots(listOf(stock))
        plane.clearSessionCaches()
        // 仍在 10s 新鲜窗口内，但内存缓存已被清空 → 必须重新发网
        plane.getQuoteSnapshots(listOf(stock))

        coVerify(exactly = 2) { stockRepository.fetchQuoteSnapshots(any()) }
    }

    // ── 分红新鲜度（网格页股息率痛点根因） ─────────────────

    @Test
    fun `empty table triggers refresh and marks success clock`() = runTest {
        coEvery { dividendRepository.getDividends("sh.600036") } returns emptyList()
        coEvery {
            dividendRepository.fetchAndCacheDividends("sh.600036", "600036")
        } returns Result.success(Unit)

        plane.ensureDividendsFresh("sh.600036")

        coVerify(exactly = 1) { dividendRepository.fetchAndCacheDividends(any(), any()) }
        assertThat(store.success["sh.600036"]).isEqualTo(now)
    }

    @Test
    fun `fresh success clock skips network`() = runTest {
        coEvery { dividendRepository.getDividends("sh.600036") } returns listOf(dividend())
        store.markSuccess("sh.600036", now)

        plane.ensureDividendsFresh("sh.600036")

        coVerify(exactly = 0) { dividendRepository.fetchAndCacheDividends(any(), any()) }
    }

    @Test
    fun `missing or stale success clock refetches`() = runTest {
        coEvery { dividendRepository.getDividends("sh.600036") } returns listOf(dividend())
        coEvery {
            dividendRepository.fetchAndCacheDividends(any(), any())
        } returns Result.success(Unit)

        // 成功时钟缺失（升级用户：表有老数据但从未记账）→ 拉取
        plane.ensureDividendsFresh("sh.600036")
        coVerify(exactly = 1) { dividendRepository.fetchAndCacheDividends(any(), any()) }

        // 超 7 天过期 → 拉取
        store.markSuccess("sh.600036", now)
        now += PlanePolicy.DIVIDEND_FRESH_MS + 1
        plane.ensureDividendsFresh("sh.600036")
        coVerify(exactly = 2) { dividendRepository.fetchAndCacheDividends(any(), any()) }
    }

    @Test
    fun `failure enters backoff window`() = runTest {
        coEvery { dividendRepository.getDividends("sh.600036") } returns emptyList()
        coEvery {
            dividendRepository.fetchAndCacheDividends(any(), any())
        } returns Result.failure(Exception("network"))

        plane.ensureDividendsFresh("sh.600036")
        now += PlanePolicy.DIVIDEND_RETRY_BACKOFF_MS - 1     // 退避窗口内
        plane.ensureDividendsFresh("sh.600036")
        coVerify(exactly = 1) { dividendRepository.fetchAndCacheDividends(any(), any()) }

        now += PlanePolicy.DIVIDEND_RETRY_BACKOFF_MS + 1     // 退避窗口过期 → 重试
        plane.ensureDividendsFresh("sh.600036")
        coVerify(exactly = 2) { dividendRepository.fetchAndCacheDividends(any(), any()) }
        assertThat(store.success).isEmpty()                   // 失败不记成功时钟
    }

    @Test
    fun `refreshDividends forces fetch and records clock`() = runTest {
        coEvery {
            dividendRepository.fetchAndCacheDividends("sh.600036", "600036")
        } returns Result.success(Unit)

        val result = plane.refreshDividends("sh.600036")

        assertThat(result.isSuccess).isTrue()
        assertThat(store.success["sh.600036"]).isEqualTo(now)
    }

    // ── DPS / 当前股息率（统一口径） ──────────────────────

    @Test
    fun `getDps fetches when empty then computes latest year`() = runTest {
        // 第一次读（新鲜度检查）为空 → 触发拉网；拉网后第二次读拿到数据
        coEvery { dividendRepository.getDividends("sh.600036") } returnsMany listOf(
            emptyList(),
            listOf(dividend(cashPerShare = 0.36), dividend(reportDate = "2024-12-31", cashPerShare = 0.32))
        )
        coEvery {
            dividendRepository.fetchAndCacheDividends(any(), any())
        } returns Result.success(Unit)

        val dps = plane.getDps("sh.600036")

        assertThat(dps).isEqualTo(0.36)     // 最新年度 2025
    }

    @Test
    fun `yield equals dps over plane price`() = runTest {
        coEvery { stockRepository.getStock("sh.600036") } returns stock
        coEvery { stockRepository.fetchQuoteSnapshots(listOf(stock)) } returns
            mapOf("sh.600036" to snapshot)
        coEvery { dividendRepository.getDividends("sh.600036") } returns listOf(dividend())
        store.markSuccess("sh.600036", now)

        val yieldPct = plane.getCurrentDividendYield("sh.600036")

        assertThat(yieldPct).isEqualTo(0.36 / 10.0 * 100.0)
    }

    @Test
    fun `yield falls back to cached price without stock entity`() = runTest {
        coEvery { stockRepository.getStock("sh.600036") } returns null
        coEvery { stockRepository.getCachedPrices(listOf("sh.600036")) } returns
            mapOf("sh.600036" to 8.0)
        coEvery { dividendRepository.getDividends("sh.600036") } returns listOf(dividend())
        store.markSuccess("sh.600036", now)

        val yieldPct = plane.getCurrentDividendYield("sh.600036")

        assertThat(yieldPct).isEqualTo(0.36 / 8.0 * 100.0)
    }

    // ── BOLL：单一路径 + 内存缓存 ──────────────────────────

    @Test
    fun `getBoll caches within ttl`() = runTest {
        val closes = List(25) { 10.0 + it * 0.1 }
        coEvery { klineRepository.fetchCloses("sh.600036", KlinePeriod.WEEKLY) } returns closes

        val band1 = plane.getBoll("sh.600036")
        now += PlanePolicy.BOLL_TTL_MS - 1
        val band2 = plane.getBoll("sh.600036")

        assertThat(band1).isNotNull()
        assertThat(band2).isEqualTo(band1)
        coVerify(exactly = 1) { klineRepository.fetchCloses(any(), any()) }

        now += PlanePolicy.BOLL_TTL_MS + 1     // TTL 过期 → 重算
        plane.getBoll("sh.600036")
        coVerify(exactly = 2) { klineRepository.fetchCloses(any(), any()) }
    }

    // ── 基本面：内置派息率补全 ────────────────────────────

    @Test
    fun `getFundamentals returns enriched payout ratio`() = runTest {
        coEvery { fundamentalsCacheRepository.getFundamentals("sh.600036", false) } returns
            Fundamentals(periods = listOf(Fundamentals.Period(
                reportDate = "2025-12-31", roe = null, debtToAssetRatio = null,
                revenueYoy = null, netProfitYoy = null, basicEps = 1.0
            )))
        coEvery { dividendRepository.getDividends("sh.600036") } returns listOf(dividend())

        val result = plane.getFundamentals("sh.600036")

        assertThat(result?.periods?.first()?.payoutRatio).isEqualTo(0.36 / 1.0 * 100.0)
    }

    // ── 市场数据：60s 内存缓存 ─────────────────────────────

    @Test
    fun `index quotes share one request within ttl`() = runTest {
        coEvery { marketDataRepository.fetchIndexQuotes() } returns listOf(
            IndexQuote(
                code = "1.000001", name = "上证指数", price = 3000.0,
                changePct = null, prevClose = null, high = null, low = null, open = null, amount = null
            )
        )

        val first = plane.getIndexQuotes()
        now += PlanePolicy.MARKET_TTL_MS - 1
        val second = plane.getIndexQuotes()

        assertThat(first).isEqualTo(second)
        coVerify(exactly = 1) { marketDataRepository.fetchIndexQuotes() }
    }

    // ── 测试夹具 ──────────────────────────────────────────

    private fun dividend(
        reportDate: String = "2025-12-31",
        cashPerShare: Double = 0.36
    ) = DividendEntity(
        id = "sh.600036_$reportDate",
        stockCode = "sh.600036",
        reportDate = reportDate,
        cashPerShare = cashPerShare
    )

    /** 内存版分红新鲜度记账（免 Robolectric）。 */
    private class FakeFreshnessStore : DividendFreshnessStore {
        val success = mutableMapOf<String, Long>()
        val attempt = mutableMapOf<String, Long>()
        override fun lastSuccessAt(stockCode: String): Long = success[stockCode] ?: 0L
        override fun lastAttemptAt(stockCode: String): Long = attempt[stockCode] ?: 0L
        override fun markSuccess(stockCode: String, at: Long) { success[stockCode] = at }
        override fun markAttempt(stockCode: String, at: Long) { attempt[stockCode] = at }
        override fun clear() { success.clear(); attempt.clear() }
    }

    // ── cashPerShareByDividendYear：派息率年度合计口径（2026-08-20 审计 M2）──

    @Test
    fun `cashPerShareByDividendYear sums semi-annual dividends per year`() {
        // 中国移动型：2024 年中期（reportDate 由腾讯 nd 补成 2024-12-31）+ 东财回退源真实
        // 报告期 2024-06-30——两种源口径按「前 4 位年份」归一后合计 4.70
        val dividends = listOf(
            dividend(reportDate = "2024-12-31", cashPerShare = 2.2012),
            dividend(reportDate = "2024-06-30", cashPerShare = 2.5025),
            dividend(reportDate = "2025-12-31", cashPerShare = 2.2916)
        )
        val byYear = dividends.cashPerShareByDividendYear()

        assertThat(byYear[2024]!!).isWithin(1e-9).of(4.7037)
        assertThat(byYear[2025]!!).isWithin(1e-9).of(2.2916)
    }

    @Test
    fun `cashPerShareByDividendYear drops invalid years and non-positive amounts`() {
        val dividends = listOf(
            dividend(reportDate = "2024-12-31", cashPerShare = 0.30),
            dividend(reportDate = "bad-year", cashPerShare = 1.0),   // 年份不可解析 → 剔除
            dividend(reportDate = "2023-12-31", cashPerShare = 0.0)   // 非正金额 → 剔除
        )
        val byYear = dividends.cashPerShareByDividendYear()

        assertThat(byYear.keys).containsExactly(2024)
        assertThat(byYear[2024]!!).isWithin(1e-9).of(0.30)
    }

    // ── 失败日志埋点（设置 → 数据 → 失败日志） ─────────────────

    @Test
    fun `getBoll failure records error log`() = runTest {
        coEvery { klineRepository.fetchCloses("sh.600036", KlinePeriod.WEEKLY) } throws
            java.io.IOException("timeout")

        val band = plane.getBoll("sh.600036", KlinePeriod.WEEKLY)

        assertThat(band).isNull()
        coVerify(exactly = 1) {
            errorLogRepository.record("K线", "周线数据获取失败（sh.600036）", any(), any())
        }
    }

    @Test
    fun `ensureDividendsFresh failure records error log`() = runTest {
        coEvery { dividendRepository.getDividends("sh.600036") } returns emptyList()
        coEvery { dividendRepository.fetchAndCacheDividends(any(), any()) } returns
            Result.failure(Exception("network"))

        plane.ensureDividendsFresh("sh.600036")

        coVerify(exactly = 1) {
            errorLogRepository.record("分红", "分红数据刷新失败（sh.600036）", any(), any())
        }
    }
}
