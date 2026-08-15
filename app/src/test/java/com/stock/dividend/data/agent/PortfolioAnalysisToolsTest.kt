package com.stock.dividend.data.agent

import com.google.adk.kt.tools.ToolContext
import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.agent.tools.GetCompareStocksTool
import com.stock.dividend.data.agent.tools.GetMarketRankingTool
import com.stock.dividend.data.agent.tools.GetPortfolioDiagnosisTool
import com.stock.dividend.data.local.entity.DividendEntity
import com.stock.dividend.data.local.entity.StockEntity
import com.stock.dividend.data.repository.BollBand
import com.stock.dividend.data.repository.BondYieldRepository
import com.stock.dividend.data.repository.DividendRepository
import com.stock.dividend.data.repository.DividendThresholds
import com.stock.dividend.data.repository.Fundamentals
import com.stock.dividend.data.repository.FundamentalsCacheRepository
import com.stock.dividend.data.repository.KlinePeriod
import com.stock.dividend.data.repository.MarketDataRepository
import com.stock.dividend.data.repository.MarketListItem
import com.stock.dividend.data.repository.NotificationRuleRepository
import com.stock.dividend.data.repository.PortfolioDiagnosisAssembler
import com.stock.dividend.data.repository.QuoteSnapshot
import com.stock.dividend.data.repository.StockRepository
import com.stock.dividend.data.repository.StockSearchResult
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * 组合分析三工具（全市场榜单 / 多股对比 / 组合诊断）测试。
 * 结论由程序计算：股息率按现价实时算（与 get_stock_info 同口径）、
 * 诊断指标由 [com.stock.dividend.data.repository.PortfolioRiskDiagnoser] 纯函数产出。
 */
class PortfolioAnalysisToolsTest {

    private val context = mockk<ToolContext>(relaxed = true)

    private fun div(code: String, reportDate: String, cash: Double) = DividendEntity(
        id = "$code-$reportDate", stockCode = code, reportDate = reportDate, cashPerShare = cash
    )

    // ────────────────────────────────────────────────────────────────
    // get_market_ranking
    // ────────────────────────────────────────────────────────────────

    @Test
    fun marketRankingTool_declaration_hasNoRequiredParams() {
        val tool = GetMarketRankingTool(mockk())
        assertThat(tool.declaration().name).isEqualTo("get_market_ranking")
        assertThat(tool.declaration().parameters!!.required ?: emptyList<String>()).isEmpty()
    }

    @Test
    fun marketRankingTool_returnsItemsWithDividendYield() = runTest {
        val marketRepo = mockk<MarketDataRepository>(relaxed = true)
        coEvery {
            marketRepo.fetchMarketRanking(
                MarketDataRepository.RankingSortBy.DIVIDEND_YIELD, null, null, 20
            )
        } returns listOf(
            MarketListItem(
                code = "002763", name = "汇洁股份", price = 7.53, changePct = 0.53,
                pe = 8.41, pb = 1.98, totalMarketCap = 3.09e9, turnoverRate = 1.5,
                industry = null, mainNetInflow = null, mainNetInflowPct = null,
                leaderName = null, leaderCode = null, leaderChangePct = null,
                dividendYield = 14.61
            )
        )
        val tool = GetMarketRankingTool(marketRepo)
        val result = tool.run(context, emptyMap()) as Map<*, *>
        val item = (result["items"] as List<*>).single() as Map<*, *>
        assertThat(item["code"]).isEqualTo("002763")
        assertThat(item["dividendYield"]).isEqualTo(14.61)
        assertThat(item["peTtm"]).isEqualTo(8.41)
        // 无过滤时不附加口径说明
        assertThat(result.containsKey("note")).isFalse()
    }

    @Test
    fun marketRankingTool_passesFiltersToRepository() = runTest {
        val marketRepo = mockk<MarketDataRepository>(relaxed = true)
        coEvery {
            marketRepo.fetchMarketRanking(
                MarketDataRepository.RankingSortBy.PE, 5.0, 10.0, 30
            )
        } returns listOf(
            MarketListItem(
                code = "600036", name = "招商银行", price = null, changePct = null,
                pe = 6.0, pb = null, totalMarketCap = null, turnoverRate = null,
                industry = null, mainNetInflow = null, mainNetInflowPct = null,
                leaderName = null, leaderCode = null, leaderChangePct = null,
                dividendYield = 5.5
            )
        )
        val tool = GetMarketRankingTool(marketRepo)
        val result = tool.run(
            context,
            mapOf("sortBy" to "pe", "minDividendYield" to 5.0, "maxPe" to 10.0, "limit" to 30)
        ) as Map<*, *>
        // 有过滤时返回口径说明（客户端过滤仅作用于榜单前列，诚实口径）
        assertThat(result["note"]?.toString()).contains("前 200")
    }

    @Test
    fun marketRankingTool_emptyRankingReturnsError() = runTest {
        val marketRepo = mockk<MarketDataRepository>(relaxed = true)
        coEvery { marketRepo.fetchMarketRanking(any(), any(), any(), any()) } returns emptyList()
        val tool = GetMarketRankingTool(marketRepo)
        val result = tool.run(context, emptyMap()) as Map<*, *>
        assertThat(result["error"]).isNotNull()
    }

    // ────────────────────────────────────────────────────────────────
    // compare_stocks
    // ────────────────────────────────────────────────────────────────

    @Test
    fun compareStocksTool_declaration_requiresCodes() {
        val tool = GetCompareStocksTool(mockk(), mockk(), mockk())
        assertThat(tool.declaration().name).isEqualTo("compare_stocks")
        assertThat(tool.declaration().parameters!!.required).containsExactly("codes")
    }

    @Test
    fun compareStocksTool_computesRealtimeYieldAndDividendDepth() = runTest {
        val stockRepo = mockk<StockRepository>(relaxed = true)
        coEvery { stockRepo.resolveStock("600519") } returns StockSearchResult("sh.600519", "贵州茅台", "1")
        coEvery { stockRepo.resolveStock("000001") } returns StockSearchResult("sz.000001", "平安银行", "0")
        coEvery { stockRepo.fetchQuoteSnapshots(any()) } returns mapOf(
            "sh.600519" to QuoteSnapshot(stockCode = "sh.600519", price = 1500.0, pe = 25.0, pb = 8.0),
            "sz.000001" to QuoteSnapshot(stockCode = "sz.000001", price = 10.0, pe = 5.0, pb = 0.6)
        )
        // 持仓：茅台持有 100 股成本 1400 → 盈亏 (1500-1400)*100
        coEvery { stockRepo.observeStock("sh.600519") } returns flowOf(
            StockEntity("sh.600519", "贵州茅台", "1", shares = 100, costPerShare = 1400.0)
        )
        coEvery { stockRepo.observeStock("sz.000001") } returns flowOf(
            StockEntity("sz.000001", "平安银行", "0")  // 观察仓 shares=0
        )
        val divRepo = mockk<DividendRepository>(relaxed = true)
        // 茅台：2025 年合计 51 元/股（45+6），2024 年 49 元 → 连续 2 年；股息率 = 51/1500*100 = 3.4%
        coEvery { divRepo.observeDividends("sh.600519") } returns flowOf(
            listOf(
                div("sh.600519", "2025-12-31", 45.0), div("sh.600519", "2025-06-30", 6.0),
                div("sh.600519", "2024-12-31", 49.0)
            )
        )
        coEvery { divRepo.observeDividends("sz.000001") } returns flowOf(emptyList())
        val ruleRepo = mockk<NotificationRuleRepository>(relaxed = true)
        coEvery { ruleRepo.observeEvalThresholds() } returns flowOf(DividendThresholds())

        val tool = GetCompareStocksTool(stockRepo, divRepo, ruleRepo)
        val result = tool.run(context, mapOf("codes" to "600519,000001")) as Map<*, *>
        val stocks = result["stocks"] as List<*>
        assertThat(stocks).hasSize(2)
        val maotai = stocks.first { (it as Map<*, *>)["code"] == "sh.600519" } as Map<*, *>
        // 股息率实时算：51/1500*100 = 3.4%（浮点容差）
        assertThat(maotai["dividendYield"] as Double).isWithin(1e-9).of(3.4)
        assertThat(maotai["consecutiveYears"]).isEqualTo(2)
        // 持仓盈亏：(1500-1400)*100 = 10000
        assertThat(maotai["profit"]).isEqualTo(10_000.0)
        // 观察仓无盈亏字段
        val pingan = stocks.first { (it as Map<*, *>)["code"] == "sz.000001" } as Map<*, *>
        assertThat(pingan.containsKey("profit")).isFalse()
        assertThat((result["notFound"] as List<*>)).isEmpty()
    }

    @Test
    fun compareStocksTool_collectsNotFoundAndReturnsRest() = runTest {
        val stockRepo = mockk<StockRepository>(relaxed = true)
        coEvery { stockRepo.resolveStock("600519") } returns StockSearchResult("sh.600519", "贵州茅台", "1")
        coEvery { stockRepo.resolveStock("999999") } returns null
        coEvery { stockRepo.fetchQuoteSnapshots(any()) } returns mapOf(
            "sh.600519" to QuoteSnapshot(stockCode = "sh.600519", price = 1500.0)
        )
        coEvery { stockRepo.observeStock("sh.600519") } returns flowOf(null)
        val divRepo = mockk<DividendRepository>(relaxed = true)
        coEvery { divRepo.observeDividends("sh.600519") } returns flowOf(emptyList())
        val ruleRepo = mockk<NotificationRuleRepository>(relaxed = true)
        coEvery { ruleRepo.observeEvalThresholds() } returns flowOf(DividendThresholds())

        val tool = GetCompareStocksTool(stockRepo, divRepo, ruleRepo)
        val result = tool.run(context, mapOf("codes" to "600519,999999")) as Map<*, *>
        assertThat((result["stocks"] as List<*>)).hasSize(1)
        assertThat((result["notFound"] as List<*>)).containsExactly("999999")
    }

    @Test
    fun compareStocksTool_allInvalidReturnsError() = runTest {
        val stockRepo = mockk<StockRepository>(relaxed = true)
        coEvery { stockRepo.resolveStock(any()) } returns null
        val tool = GetCompareStocksTool(stockRepo, mockk(), mockk())
        val result = tool.run(context, mapOf("codes" to "999999")) as Map<*, *>
        assertThat(result["error"]?.toString()).contains("未找到")
    }

    @Test
    fun compareStocksTool_deepModeAddsActionFromBoll() = runTest {
        val stockRepo = mockk<StockRepository>(relaxed = true)
        coEvery { stockRepo.resolveStock("600519") } returns StockSearchResult("sh.600519", "贵州茅台", "1")
        coEvery { stockRepo.fetchQuoteSnapshots(any()) } returns mapOf(
            "sh.600519" to QuoteSnapshot(stockCode = "sh.600519", price = 1350.0)
        )
        coEvery { stockRepo.observeStock("sh.600519") } returns flowOf(null)
        // 三周期 BOLL：价 1350 ≤ 日/周下轨 1400 且 ≤ 月中轨 1600 → 三周期共振；
        // 股息率 51/1350 ≈ 3.78% > minYield 2% → BUY
        val band = BollBand(middle = 1600.0, upper = 1800.0, lower = 1400.0)
        coEvery { stockRepo.fetchBoll("sh.600519", KlinePeriod.WEEKLY) } returns band
        coEvery { stockRepo.fetchBoll("sh.600519", KlinePeriod.DAILY) } returns band
        coEvery { stockRepo.fetchBoll("sh.600519", KlinePeriod.MONTHLY) } returns band
        val divRepo = mockk<DividendRepository>(relaxed = true)
        coEvery { divRepo.observeDividends("sh.600519") } returns flowOf(
            listOf(div("sh.600519", "2025-12-31", 51.0))
        )
        val ruleRepo = mockk<NotificationRuleRepository>(relaxed = true)
        coEvery { ruleRepo.observeEvalThresholds() } returns flowOf(DividendThresholds())

        val tool = GetCompareStocksTool(stockRepo, divRepo, ruleRepo)
        val result = tool.run(context, mapOf("codes" to "600519", "deep" to true)) as Map<*, *>
        val row = (result["stocks"] as List<*>).single() as Map<*, *>
        assertThat(row["action"]).isEqualTo("BUY")
        assertThat(row["reasons"] as List<*>).isNotEmpty()
    }

    // ────────────────────────────────────────────────────────────────
    // diagnose_portfolio
    // ────────────────────────────────────────────────────────────────

    @Test
    fun portfolioDiagnosisTool_declaration_hasNoParams() {
        val tool = GetPortfolioDiagnosisTool(mockk(), mockk())
        assertThat(tool.declaration().name).isEqualTo("diagnose_portfolio")
        assertThat(tool.declaration().parameters).isNull()
    }

    @Test
    fun portfolioDiagnosisTool_emptyHoldingsReturnsError() = runTest {
        val stockRepo = mockk<StockRepository>(relaxed = true)
        coEvery { stockRepo.observeAllStocksForSnapshot() } returns emptyList()
        val tool = GetPortfolioDiagnosisTool(stockRepo, mockk())
        val result = tool.run(context, emptyMap()) as Map<*, *>
        assertThat(result["error"]?.toString()).contains("持仓")
    }

    @Test
    fun portfolioDiagnosisTool_diagnosesConcentrationAndYieldSpread() = runTest {
        val stockRepo = mockk<StockRepository>(relaxed = true)
        // 2 只同行业（银行）持仓，各 100 股、现价 10 → 市值 1000/1000
        coEvery { stockRepo.observeAllStocksForSnapshot() } returns listOf(
            StockEntity("sh.600036", "招商银行", "1", shares = 100, costPerShare = 9.0, industry = "银行"),
            StockEntity("sh.601166", "兴业银行", "1", shares = 100, costPerShare = 9.0, industry = "银行")
        )
        coEvery { stockRepo.fetchQuotes(any()) } returns mapOf("sh.600036" to 10.0, "sh.601166" to 10.0)
        val divRepo = mockk<DividendRepository>(relaxed = true)
        // 每股年分红 0.4 元 × 100 股 = 年股息金额 40 元/只；组合股息率 = 80/2000 = 4%
        coEvery { divRepo.observeDividends("sh.600036") } returns flowOf(
            listOf(div("sh.600036", "2025-12-31", 0.4), div("sh.600036", "2024-12-31", 0.38))
        )
        coEvery { divRepo.observeDividends("sh.601166") } returns flowOf(
            listOf(div("sh.601166", "2025-12-31", 0.4), div("sh.601166", "2024-12-31", 0.38))
        )
        val fundRepo = mockk<FundamentalsCacheRepository>(relaxed = true)
        coEvery { fundRepo.getFundamentals(any(), any()) } returns null
        val bondRepo = mockk<BondYieldRepository>(relaxed = true)
        coEvery { bondRepo.fetch10YBondYield(false) } returns 3.0

        // 装配器用真实实现（mock 其依赖），锁定工具→装配→诊断全链路口径
        val assembler = PortfolioDiagnosisAssembler(divRepo, fundRepo, bondRepo)
        val tool = GetPortfolioDiagnosisTool(stockRepo, assembler)
        val result = tool.run(context, emptyMap()) as Map<*, *>
        // 同行业双持仓：行业 HHI=10000、CR3=100 → 触发行业集中建议
        assertThat(result["industryHhi"]).isEqualTo(10_000.0)
        assertThat(result["suggestions"].toString()).contains("行业高度集中")
        // 组合股息率 4% vs 国债 3% → 利差 1.0（不触发薄利差警示）
        assertThat(result["weightedDividendYieldPct"]).isEqualTo(4.0)
        assertThat(result["yieldSpreadPct"]).isEqualTo(1.0)
        assertThat(result["holdingCount"]).isEqualTo(2)
    }

    @Test
    fun portfolioDiagnosisTool_flagsHighPayoutFromFundamentals() = runTest {
        val stockRepo = mockk<StockRepository>(relaxed = true)
        coEvery { stockRepo.observeAllStocksForSnapshot() } returns listOf(
            StockEntity("sh.600036", "招商银行", "1", shares = 100, costPerShare = 9.0, industry = "银行")
        )
        coEvery { stockRepo.fetchQuotes(any()) } returns mapOf("sh.600036" to 10.0)
        val divRepo = mockk<DividendRepository>(relaxed = true)
        // 每股 0.4 元 + EPS 0.3 → 派息率 = 0.4/0.3*100 ≈ 133% > 100%
        coEvery { divRepo.observeDividends("sh.600036") } returns flowOf(
            listOf(div("sh.600036", "2025-12-31", 0.4))
        )
        val fundRepo = mockk<FundamentalsCacheRepository>(relaxed = true)
        coEvery { fundRepo.getFundamentals("sh.600036", any()) } returns Fundamentals(
            periods = listOf(
                Fundamentals.Period(
                    reportDate = "2025-12-31", roe = null, debtToAssetRatio = null,
                    revenueYoy = null, netProfitYoy = null, basicEps = 0.3
                )
            )
        )
        val bondRepo = mockk<BondYieldRepository>(relaxed = true)
        coEvery { bondRepo.fetch10YBondYield(false) } returns 3.0

        val assembler = PortfolioDiagnosisAssembler(divRepo, fundRepo, bondRepo)
        val tool = GetPortfolioDiagnosisTool(stockRepo, assembler)
        val result = tool.run(context, emptyMap()) as Map<*, *>
        assertThat(result["highPayoutCodes"] as List<*>).containsExactly("sh.600036")
        assertThat(result["suggestions"].toString()).contains("派息率超 100%")
    }
}
