package com.stock.dividend.data.repository

import androidx.room.withTransaction
import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.local.AppDatabase
import com.stock.dividend.data.local.dao.DividendDao
import com.stock.dividend.data.local.dao.FinancialStatementsCacheDao
import com.stock.dividend.data.local.dao.FundamentalsCacheDao
import com.stock.dividend.data.local.dao.KlineCacheDao
import com.stock.dividend.data.local.dao.LlmAnalysisCacheDao
import com.stock.dividend.data.local.dao.PriceCacheDao
import com.stock.dividend.data.local.dao.SearchCacheDao
import com.stock.dividend.data.plane.DividendFreshnessStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

class CacheManagementRepositoryTest {

    private val priceCacheDao = mockk<PriceCacheDao>(relaxed = true)
    private val searchCacheDao = mockk<SearchCacheDao>(relaxed = true)
    private val klineCacheDao = mockk<KlineCacheDao>(relaxed = true)
    private val fundamentalsCacheDao = mockk<FundamentalsCacheDao>(relaxed = true)
    private val financialStatementsCacheDao = mockk<FinancialStatementsCacheDao>(relaxed = true)
    private val llmAnalysisCacheDao = mockk<LlmAnalysisCacheDao>(relaxed = true)
    private val dividendDao = mockk<DividendDao>(relaxed = true)
    private val freshnessStore = mockk<DividendFreshnessStore>(relaxed = true)
    private val fuyaoCacheDao = mockk<com.stock.dividend.data.local.dao.FuyaoCacheDao>(relaxed = true)
    private val appDatabase = mockk<AppDatabase>(relaxed = true)

    private val repository = CacheManagementRepository(
        priceCacheDao = priceCacheDao,
        searchCacheDao = searchCacheDao,
        klineCacheDao = klineCacheDao,
        fundamentalsCacheDao = fundamentalsCacheDao,
        financialStatementsCacheDao = financialStatementsCacheDao,
        llmAnalysisCacheDao = llmAnalysisCacheDao,
        dividendDao = dividendDao,
        dividendFreshnessStore = freshnessStore,
        fuyaoCacheDao = fuyaoCacheDao,
        appDatabase = appDatabase,
    )

    @Before
    fun setUp() {
        coEvery { priceCacheDao.count() } returns 10L
        coEvery { searchCacheDao.count() } returns 20L
        coEvery { klineCacheDao.count() } returns 640L
        coEvery { fundamentalsCacheDao.count() } returns 5L
        coEvery { financialStatementsCacheDao.count() } returns 4L
        coEvery { llmAnalysisCacheDao.count() } returns 7L
        coEvery { dividendDao.count() } returns 120L
        coEvery { fuyaoCacheDao.count() } returns 9L
        // Room 的 withTransaction 扩展函数默认会走真实 DB 事务，单测里 mock 为「直接执行 block」
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
    fun `loadStats returns entry count for every cache kind`() = runTest {
        val stats = repository.loadStats()

        assertThat(stats.map { it.kind }).containsExactlyElementsIn(CacheKind.entries)
        assertThat(stats.first { it.kind == CacheKind.PRICE }.entries).isEqualTo(10L)
        assertThat(stats.first { it.kind == CacheKind.SEARCH }.entries).isEqualTo(20L)
        assertThat(stats.first { it.kind == CacheKind.KLINE }.entries).isEqualTo(640L)
        assertThat(stats.first { it.kind == CacheKind.FUNDAMENTALS }.entries).isEqualTo(5L)
        assertThat(stats.first { it.kind == CacheKind.STATEMENTS }.entries).isEqualTo(4L)
        assertThat(stats.first { it.kind == CacheKind.LLM_ANALYSIS }.entries).isEqualTo(7L)
        assertThat(stats.first { it.kind == CacheKind.DIVIDENDS }.entries).isEqualTo(120L)
    }

    @Test
    fun `loadStats swallows dao failure and reports zero`() = runTest {
        coEvery { priceCacheDao.count() } throws IllegalStateException("db")

        val stats = repository.loadStats()

        assertThat(stats.first { it.kind == CacheKind.PRICE }.entries).isEqualTo(0L)
        assertThat(stats.first { it.kind == CacheKind.KLINE }.entries).isEqualTo(640L)
    }

    @Test
    fun `clear price only deletes price cache rows`() = runTest {
        repository.clear(CacheKind.PRICE)

        coVerify(exactly = 1) { priceCacheDao.deleteAll() }
        coVerify(exactly = 0) { klineCacheDao.clearAll() }
        coVerify(exactly = 0) { dividendDao.deleteAll() }
    }

    @Test
    fun `clear kline removes bars and meta`() = runTest {
        repository.clear(CacheKind.KLINE)

        coVerify(exactly = 1) { klineCacheDao.clearAll() }
        coVerify(exactly = 0) { priceCacheDao.deleteAll() }
    }

    @Test
    fun `clear fundamentals and statements call clear`() = runTest {
        repository.clear(CacheKind.FUNDAMENTALS)
        repository.clear(CacheKind.STATEMENTS)

        coVerify(exactly = 1) { fundamentalsCacheDao.clear() }
        coVerify(exactly = 1) { financialStatementsCacheDao.clear() }
    }

    @Test
    fun `clear dividends also resets freshness bookkeeping`() = runTest {
        repository.clear(CacheKind.DIVIDENDS)

        coVerify(exactly = 1) { dividendDao.deleteAll() }
        // 记账一并清零：清缓存后下次 getDps 立即重新拉网，不吃 5 分钟退避闭门羹
        verify(exactly = 1) { freshnessStore.clear() }
    }

    @Test
    fun `clear other kinds does not touch dividend freshness`() = runTest {
        repository.clear(CacheKind.PRICE)

        verify(exactly = 0) { freshnessStore.clear() }
    }

    @Test
    fun `clearAll clears every cache`() = runTest {
        repository.clearAll()

        io.mockk.coVerifyAll {
            priceCacheDao.deleteAll()
            searchCacheDao.deleteAll()
            klineCacheDao.clearAll()
            fundamentalsCacheDao.clear()
            financialStatementsCacheDao.clear()
            llmAnalysisCacheDao.clear()
            dividendDao.deleteAll()
        }
        verify(exactly = 1) { freshnessStore.clear() }
    }

    @Test
    fun `immutable history kinds are marked permanent`() {
        // 历史不可变数据（已收盘 K 线、已披露财报期次、已实施分红）→ 永久缓存策略
        assertThat(CacheKind.KLINE.permanent).isTrue()
        assertThat(CacheKind.FUNDAMENTALS.permanent).isTrue()
        assertThat(CacheKind.STATEMENTS.permanent).isTrue()
        assertThat(CacheKind.DIVIDENDS.permanent).isTrue()
        // 实时/派生数据 → 短期缓存，可随时重建
        assertThat(CacheKind.PRICE.permanent).isFalse()
        assertThat(CacheKind.SEARCH.permanent).isFalse()
        assertThat(CacheKind.LLM_ANALYSIS.permanent).isFalse()
    }

    @Test
    fun `every kind has non-blank label and description`() {
        CacheKind.entries.forEach { kind ->
            assertThat(kind.label).isNotEmpty()
            assertThat(kind.description).isNotEmpty()
        }
    }
}
