package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.local.dao.PriceCacheDao
import com.stock.dividend.data.local.dao.StockDao
import com.stock.dividend.data.local.entity.GridPlanEntity
import com.stock.dividend.data.local.entity.PriceCacheEntity
import com.stock.dividend.data.local.entity.StockEntity
import com.stock.dividend.data.plane.MarketDataPlane
import com.stock.dividend.data.widget.WidgetUiState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Test

class WidgetDataRepositoryTest {

    private val plane = mockk<MarketDataPlane>(relaxed = true)
    private val stockDao = mockk<StockDao>()
    private val priceCacheDao = mockk<PriceCacheDao>()
    private val fireGoalRepository = mockk<FireGoalRepository>()
    private val gridPlanRepository = mockk<GridPlanRepository>(relaxed = true)
    private val transactionRepository = mockk<TransactionRepository>(relaxed = true)
    private val repo = WidgetDataRepository(stockDao, priceCacheDao, fireGoalRepository, plane, gridPlanRepository, transactionRepository)

    private fun planOf(code: String = "sh.600036") = GridPlanEntity(
        id = "p1", stockCode = code, stockName = "浦发银行",
        basePrice = 10.0, lowPrice = 8.0, highPrice = 12.0, grids = 4, totalCapital = 100000.0
    )

    @Test
    fun `returns EMPTY when no holdings`() = runTest {
        coEvery { stockDao.getAll() } returns emptyList()
        coEvery { fireGoalRepository.getGoalOnce() } returns null

        val state = repo.loadSnapshot()

        assertThat(state).isEqualTo(WidgetUiState.EMPTY)
    }

    @Test
    fun `aggregates market value and priced count`() = runTest {
        coEvery { stockDao.getAll() } returns listOf(
            stock("sh.600036", shares = 100, costPerShare = 30.0),
            stock("sz.000001", shares = 200, costPerShare = 10.0),
        )
        coEvery { priceCacheDao.getAll() } returns listOf(
            PriceCacheEntity("sh.600036", price = 36.0, updatedAt = 1000L),
        )
        coEvery { fireGoalRepository.getGoalOnce() } returns null

        val state = repo.loadSnapshot()

        assertThat(state.holdingCount).isEqualTo(2)
        assertThat(state.pricedCount).isEqualTo(1)
        assertThat(state.totalMarketValue).isEqualTo(3600.0)
    }

    @Test
    fun `computes cost basis pnl and percent`() = runTest {
        coEvery { stockDao.getAll() } returns listOf(
            stock("sh.600036", shares = 100, costPerShare = 30.0),
        )
        coEvery { priceCacheDao.getAll() } returns listOf(
            PriceCacheEntity("sh.600036", price = 36.0, updatedAt = 1000L),
        )
        coEvery { fireGoalRepository.getGoalOnce() } returns null

        val state = repo.loadSnapshot()

        assertThat(state.costBasisPnl).isEqualTo(600.0)
        assertThat(state.costBasisPnlPercent).isWithin(0.0001).of(0.2)
    }

    @Test
    fun `fire progress zero when goal not set`() = runTest {
        coEvery { stockDao.getAll() } returns listOf(stock("sh.600036", shares = 100, costPerShare = 30.0))
        coEvery { priceCacheDao.getAll() } returns listOf(PriceCacheEntity("sh.600036", price = 36.0, updatedAt = 1000L))
        coEvery { fireGoalRepository.getGoalOnce() } returns null

        val state = repo.loadSnapshot()

        assertThat(state.fireGoalAmount).isEqualTo(0.0)
    }

    @Test
    fun `lastPriceUpdatedAt is max of cache`() = runTest {
        coEvery { stockDao.getAll() } returns listOf(stock("sh.600036", shares = 100, costPerShare = 30.0))
        coEvery { priceCacheDao.getAll() } returns listOf(
            PriceCacheEntity("sh.600036", price = 36.0, updatedAt = 1000L),
            PriceCacheEntity("sz.000001", price = 10.0, updatedAt = 5000L),
        )
        coEvery { fireGoalRepository.getGoalOnce() } returns null

        val state = repo.loadSnapshot()

        assertThat(state.lastPriceUpdatedAt).isEqualTo(5000L)
    }

    @Test
    fun `dao exception returns EMPTY without throwing`() = runTest {
        coEvery { stockDao.getAll() } throws RuntimeException("db locked")
        coEvery { fireGoalRepository.getGoalOnce() } returns null

        val state = repo.loadSnapshot()

        assertThat(state).isEqualTo(WidgetUiState.EMPTY)
    }

    @Test
    fun `refreshPrices delegates to market data plane`() = runTest {
        val holdings = listOf(stock("sh.600036", shares = 100, costPerShare = 30.0))
        coEvery { stockDao.getAll() } returns holdings
        coEvery { plane.getPrices(any(), any()) } returns mapOf("sh.600036" to 37.0)

        val result = repo.refreshPrices()

        assertThat(result.isSuccess).isTrue()
        coVerify { plane.getPrices(any(), any()) }
    }

    @Test
    fun `refreshPrices returns failure when db read throws`() = runTest {
        // 网络失败由数据平面内部吞掉（返回空价）；此处仅 DB 读取异常会传导为 failure
        coEvery { stockDao.getAll() } throws RuntimeException("db locked")

        val result = repo.refreshPrices()

        assertThat(result.isFailure).isTrue()
    }

    private fun stock(code: String, shares: Int, costPerShare: Double) = StockEntity(
        code = code,
        name = "测试",
        marketCode = if (code.startsWith("sh")) "1" else "0",
        shares = shares,
        costPerShare = costPerShare,
    )
    /** 网格下一买档：用 price_cache 现价本地计算（现价 9.5 → 下一档 9.33）。 */
    @Test
    fun `grid next hint computed from cached price`() = runTest {
        coEvery { stockDao.getAll() } returns listOf(stock("sh.600036", shares = 100, costPerShare = 30.0))
        coEvery { priceCacheDao.getAll() } returns listOf(PriceCacheEntity("sh.600036", price = 9.5, updatedAt = 1000L))
        coEvery { fireGoalRepository.getGoalOnce() } returns null
        coEvery { gridPlanRepository.observeAll() } returns kotlinx.coroutines.flow.flowOf(listOf(planOf()))

        val state = repo.loadSnapshot()

        assertThat(state.gridNextHints).hasSize(1)
        assertThat(state.gridNextHints[0].stockName).isEqualTo("浦发银行")
        assertThat(state.gridNextHints[0].nextBuyPrice).isEqualTo(9.33)
    }

    /** 无持仓但有网格计划（自选观察仓）→ 不返回 EMPTY，网格提示仍展示。 */
    @Test
    fun `watchlist only grid plan still yields hints`() = runTest {
        coEvery { stockDao.getAll() } returns listOf(stock("sh.600036", shares = 0, costPerShare = 0.0))
        coEvery { priceCacheDao.getAll() } returns listOf(PriceCacheEntity("sh.600036", price = 9.5, updatedAt = 1000L))
        coEvery { fireGoalRepository.getGoalOnce() } returns null
        coEvery { gridPlanRepository.observeAll() } returns kotlinx.coroutines.flow.flowOf(listOf(planOf()))

        val state = repo.loadSnapshot()

        assertThat(state.holdingCount).isEqualTo(0)
        assertThat(state.gridNextHints).isNotEmpty()
    }

    /** 刷新拉价范围并入网格计划标的：自选未持仓股的缓存价也能刷新（修复死角）。 */
    @Test
    fun `refreshPrices includes grid plan stocks beyond holdings`() = runTest {
        coEvery { stockDao.getAll() } returns listOf(
            stock("sh.600036", shares = 100, costPerShare = 30.0),   // 持仓
            stock("sz.000001", shares = 0, costPerShare = 0.0)        // 自选（网格标的）
        )
        coEvery { gridPlanRepository.observeAll() } returns kotlinx.coroutines.flow.flowOf(listOf(planOf("sz.000001")))
        coEvery { plane.getPrices(any(), any()) } returns emptyMap()

        repo.refreshPrices()

        val captured = slot<List<StockEntity>>()
        coVerify { plane.getPrices(capture(captured), any()) }
        assertThat(captured.captured.map { it.code }).containsExactly("sh.600036", "sz.000001")
    }
    /** 已买档不再作为「下一买」提示（每档只买一次）：BUY@9.4 买掉 9.33 档，现价 9.5 → 下一买 8.67。 */
    @Test
    fun `grid next hint skips bought level`() = runTest {
        coEvery { stockDao.getAll() } returns listOf(stock("sh.600036", shares = 100, costPerShare = 30.0))
        coEvery { priceCacheDao.getAll() } returns listOf(PriceCacheEntity("sh.600036", price = 9.5, updatedAt = 1000L))
        coEvery { fireGoalRepository.getGoalOnce() } returns null
        coEvery { gridPlanRepository.observeAll() } returns kotlinx.coroutines.flow.flowOf(listOf(planOf()))
        coEvery { transactionRepository.getAll() } returns listOf(
            com.stock.dividend.data.local.entity.TransactionEntity(
                stockCode = "sh.600036", type = "BUY", shares = 300, price = 9.4, date = "2026-08-01"
            )
        )

        val state = repo.loadSnapshot()

        assertThat(state.gridNextHints).hasSize(1)
        assertThat(state.gridNextHints[0].nextBuyPrice).isEqualTo(8.67)  // 跳过已买的 9.33
    }
}