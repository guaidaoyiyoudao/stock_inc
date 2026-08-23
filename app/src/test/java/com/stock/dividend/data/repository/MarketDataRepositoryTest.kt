package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.remote.FundamentalApi
import com.stock.dividend.data.remote.MarketApi
import com.stock.dividend.data.remote.dto.MarketClistResponse
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * [MarketDataRepository.fetchCapitalFlow] 行归属校验（2026-08-20 审计 H1 修复）。
 *
 * 实测背景：clist 的 `fs=m:{market}+t:2+s:{code}` 单股筛选**实际不生效**——返回的是
 * 全市场按 fid 排序的列表（请求 600941 时首条是当日涨幅第一的志邦家居）。修复为请求
 * f12 并按代码精确匹配，否则资金流数据张冠李戴。
 */
class MarketDataRepositoryTest {

    private val marketApi: MarketApi = mockk()
    private val stockRepository: StockRepository = mockk()
    private val fuyaoApi: com.stock.dividend.data.remote.FuyaoApi = mockk()
    private val fuyaoConfig: FuyaoConfig = mockk(relaxed = true)
    private val fundamentalApi: FundamentalApi = mockk()
    private val errorLogRepository: ErrorLogRepository = mockk(relaxed = true)
    private val cacheDao: com.stock.dividend.data.local.dao.FuyaoCacheDao = mockk(relaxed = true)
    private val repo = MarketDataRepository(
        marketApi, stockRepository, fuyaoApi, fuyaoConfig, FuyaoCacheStore(cacheDao),
        fundamentalApi, errorLogRepository
    )

    @org.junit.Before
    fun setUp() {
        // 默认扶摇未配置（relaxed Boolean=false），存量用例全部走东财现状路径
        io.mockk.coEvery { fuyaoConfig.enabled } returns false
    }

    private fun clistResponse(vararg items: MarketClistResponse.MarketClistItem) =
        MarketClistResponse(
            MarketClistResponse.MarketClistData(diff = items.toList(), total = items.size)
        )

    // ── 扶摇指数批量主源 ─────────────────────────────────────────

    @Test
    fun `fetchIndexQuotes uses fuyao batch primary with local names`() = runTest {
        io.mockk.coEvery { fuyaoConfig.enabled } returns true
        io.mockk.coEvery { fuyaoApi.getIndexSnapshot(thscodes = any()) } returns
            com.stock.dividend.data.remote.dto.FuyaoEnvelope(
                code = 0, message = "success", requestId = "t",
                data = com.stock.dividend.data.remote.dto.FuyaoSnapshotData(
                    item = listOf(
                        com.stock.dividend.data.remote.dto.FuyaoPriceItem(
                            thscode = "000001.SH", lastPrice = 3905.2, changePct = 0.037913,
                            prevClose = 3903.72, turnover = 883423480000.0
                        ),
                        com.stock.dividend.data.remote.dto.FuyaoPriceItem(
                            thscode = "399001.SZ", lastPrice = 14094.168, changePct = 0.868739
                        )
                    )
                )
            )

        val quotes = repo.fetchIndexQuotes()

        // 只返回扶摇给的两只；名称由本地清单带出（扶摇快照无名称），代码为 6 位
        assertThat(quotes).hasSize(2)
        val sh = quotes.first { it.code == "000001" }
        assertThat(sh.name).isEqualTo("上证指数")
        assertThat(sh.price).isWithin(1e-9).of(3905.2)
        assertThat(sh.changePct).isWithin(1e-9).of(0.037913)
        // 一次批量请求，东财 stock/get 未调用（原为 7 次单查）
        io.mockk.coVerify(exactly = 0) { marketApi.getIndexQuote(any()) }
    }

    @Test
    fun `fetchCapitalFlow picks only the row matching requested code`() = runTest {
        // s: 筛选失效场景：首条是别的股票（当日涨幅第一），目标股在第 2 条
        coEvery { marketApi.getClist(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
            clistResponse(
                MarketClistResponse.MarketClistItem(code = "603801", mainNetInflow = 743194.0, mainNetInflowPct = 0.05),
                MarketClistResponse.MarketClistItem(code = "600941", mainNetInflow = 1000000.0, mainNetInflowPct = 1.23)
            )

        val flow = repo.fetchCapitalFlow("sh.600941")!!

        // 修复前：firstOrNull() 拿到的是志邦家居 603801 的 743194（张冠李戴）
        assertThat(flow.mainNetInflow).isEqualTo(1000000.0)
        assertThat(flow.mainNetInflowPct).isEqualTo(1.23)
    }

    @Test
    fun `fetchCapitalFlow returns null when response does not contain requested stock`() = runTest {
        // 全列表均非目标股（极端：目标退市/停牌）→ null，绝不拿别人的数据凑数
        coEvery { marketApi.getClist(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
            clistResponse(
                MarketClistResponse.MarketClistItem(code = "603801", mainNetInflow = 743194.0)
            )

        assertThat(repo.fetchCapitalFlow("sh.600941")).isNull()
    }

    @Test
    fun `fetchCapitalFlow requests f12 field for row ownership check`() = runTest {
        coEvery { marketApi.getClist(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
            clistResponse(MarketClistResponse.MarketClistItem(code = "600519", mainNetInflow = 1.0))

        repo.fetchCapitalFlow("sh.600519")

        // fields 必须含 f12（代码）才能做归属校验
        coVerify {
            marketApi.getClist(any(), any(), any(), any(), any(), any(), any(), any(),
                fields = match { it.split(",").contains("f12") }, any())
        }
    }
}
