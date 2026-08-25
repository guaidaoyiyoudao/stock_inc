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

    // ── 个股资金流（ulist 按 secid 精确拉取，2026-08-24 接入）──────────

    @Test
    fun `fetchCapitalFlow picks only the row matching requested code`() = runTest {
        // 行归属最后防线：diff 含别的股票时只认 f12 与请求代码一致的记录（不张冠李戴）
        coEvery { marketApi.getCapitalFlow(secids = "1.600941") } returns
            com.stock.dividend.data.remote.dto.CapitalFlowResponse(
                data = com.stock.dividend.data.remote.dto.CapitalFlowResponse.CapitalFlowData(
                    diff = listOf(
                        com.stock.dividend.data.remote.dto.CapitalFlowResponse.CapitalFlowItem(
                            code = "603801", mainNetInflow = 743194.0, mainNetInflowPct = 0.05
                        ),
                        com.stock.dividend.data.remote.dto.CapitalFlowResponse.CapitalFlowItem(
                            code = "600941", mainNetInflow = 1000000.0, mainNetInflowPct = 1.23
                        )
                    )
                )
            )

        val flow = repo.fetchCapitalFlow("sh.600941")!!

        assertThat(flow.mainNetInflow).isEqualTo(1000000.0)
        assertThat(flow.mainNetInflowPct).isEqualTo(1.23)
    }

    @Test
    fun `fetchCapitalFlow returns null when response does not contain requested stock`() = runTest {
        // 响应不含目标股（退市/停牌）→ null，绝不拿别人的数据凑数
        coEvery { marketApi.getCapitalFlow(secids = "1.600941") } returns
            com.stock.dividend.data.remote.dto.CapitalFlowResponse(
                data = com.stock.dividend.data.remote.dto.CapitalFlowResponse.CapitalFlowData(
                    diff = listOf(
                        com.stock.dividend.data.remote.dto.CapitalFlowResponse.CapitalFlowItem(
                            code = "603801", mainNetInflow = 743194.0
                        )
                    )
                )
            )

        assertThat(repo.fetchCapitalFlow("sh.600941")).isNull()
    }

    @Test
    fun `fetchCapitalFlow requests f12 field and fltt=2 true values`() = runTest {
        coEvery { marketApi.getCapitalFlow(secids = any()) } returns
            com.stock.dividend.data.remote.dto.CapitalFlowResponse(null)

        repo.fetchCapitalFlow("sh.600519")

        // fields 必须含 f12（代码）做归属校验；fltt=2 锁定真实值口径（不带 fltt 占比是 ×100 整数）
        coVerify {
            marketApi.getCapitalFlow(
                secids = "1.600519",
                fields = match { it.split(",").contains("f12") },
                fltt = "2"
            )
        }
    }

    @Test
    fun `fetchCapitalFlow maps sz prefix to market 0`() = runTest {
        coEvery { marketApi.getCapitalFlow(secids = "0.000001") } returns
            com.stock.dividend.data.remote.dto.CapitalFlowResponse(
                data = com.stock.dividend.data.remote.dto.CapitalFlowResponse.CapitalFlowData(
                    diff = listOf(
                        com.stock.dividend.data.remote.dto.CapitalFlowResponse.CapitalFlowItem(
                            code = "000001", mainNetInflow = 60925362.0, mainNetInflowPct = -3.79
                        )
                    )
                )
            )

        val flow = repo.fetchCapitalFlow("sz.000001")!!

        assertThat(flow.mainNetInflow).isEqualTo(60925362.0)
        assertThat(flow.mainNetInflowPct).isEqualTo(-3.79)
    }

    @Test
    fun `fetchIndexOrEtfQuote resolves sh 000-prefixed index via main indices list`() = runTest {
        // 000001 上证指数：通用「6/5 沪、其余深」规则会误判深市（0.000001=平安银行个股）；
        // 清单优先匹配后东财降级应请求 1.000001（指数价格 ÷100 口径不变）
        coEvery { marketApi.getIndexQuote(any()) } returns
            com.stock.dividend.data.remote.dto.IndexQuoteResponse(
                data = com.stock.dividend.data.remote.dto.IndexQuoteResponse.IndexQuoteData(
                    code = "000001", name = "上证指数", price = 390500.0, changePct = 3.79
                )
            )

        val quote = repo.fetchIndexOrEtfQuote("000001")!!

        assertThat(quote.price).isWithin(1e-9).of(3905.0)
        coVerify(exactly = 1) { marketApi.getIndexQuote("1.000001") }
        coVerify(exactly = 0) { marketApi.getIndexQuote("0.000001") }
    }

    @Test
    fun `fetchIndexDailyBars remote final close overrides cached same date`() = runTest {
        coEvery { fuyaoConfig.enabled } returns true
        // 缓存：2026-06-26 首次同步时锁下的盘中价 close=3000（fetchedAt 昨天，不触发当日短路）
        coEvery { cacheDao.get("indexBars|000001.SH|400") } returns
            com.stock.dividend.data.local.entity.FuyaoCacheEntity(
                key = "indexBars|000001.SH|400",
                payload = com.google.gson.Gson().toJson(
                    listOf(KlineBar(date = "2026-06-26", open = 2990.0, close = 3000.0, high = 3010.0, low = 2980.0, volume = 100.0))
                ),
                fetchedAt = System.currentTimeMillis() - 86_400_000L
            )
        // 远端：同日真实收盘 3300（修正）+ 新一日 3310
        coEvery { fuyaoApi.getIndexDailyBars(thscode = any(), interval = any(), startMs = any(), endMs = any()) } returns
            com.stock.dividend.data.remote.dto.FuyaoEnvelope(
                code = 0, message = "success", requestId = "t",
                data = com.stock.dividend.data.remote.dto.FuyaoHistoricalData(
                    item = listOf(
                        com.stock.dividend.data.remote.dto.FuyaoBarItem(
                            dateMs = 1782403200000L, open = 2990.0, close = 3300.0, high = 3320.0, low = 2985.0, volume = 20000.0
                        ),
                        com.stock.dividend.data.remote.dto.FuyaoBarItem(
                            dateMs = 1782662400000L, open = 3300.0, close = 3310.0, high = 3315.0, low = 3295.0, volume = 21000.0
                        )
                    )
                )
            )

        val bars = repo.fetchIndexDailyBars("000001.SH")

        // 同日冲突远端胜出（收盘修正不被盘中缓存锁死），日期升序
        assertThat(bars.map { it.date }).containsExactly("2026-06-26", "2026-06-29").inOrder()
        assertThat(bars.first { it.date == "2026-06-26" }.close).isWithin(1e-9).of(3300.0)
    }
}
