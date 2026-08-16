package com.stock.dividend.data.agent

import com.google.adk.kt.events.ToolConfirmation
import com.google.adk.kt.tools.FunctionTool
import com.google.adk.kt.tools.ToolContext
import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.agent.tools.AddTransactionTool
import com.stock.dividend.data.agent.tools.AddLivingExpenseTool
import com.stock.dividend.data.agent.tools.AddStockTool
import com.stock.dividend.data.agent.tools.AddTradeStrategyTool
import com.stock.dividend.data.agent.tools.GetBuyThresholdTool
import com.stock.dividend.data.agent.tools.GetCapitalFlowTool
import com.stock.dividend.data.agent.tools.GetDividendIncomeTool
import com.stock.dividend.data.agent.tools.GetDividendHistoryTool
import com.stock.dividend.data.agent.tools.GetDividendMetricsTool
import com.stock.dividend.data.agent.tools.GetDragonTigerTool
import com.stock.dividend.data.agent.tools.GetEtfInfoTool
import com.stock.dividend.data.agent.tools.GetFinancialStatementsTool
import com.stock.dividend.data.agent.tools.GetFundamentalsTool
import com.stock.dividend.data.agent.tools.GetNotificationRulesTool
import com.stock.dividend.data.agent.tools.GetGridPlansTool
import com.stock.dividend.data.agent.tools.GetHoldingsTool
import com.stock.dividend.data.agent.tools.GetIndustryListTool
import com.stock.dividend.data.agent.tools.GetIndustryPeersTool
import com.stock.dividend.data.agent.tools.GetKlineTool
import com.stock.dividend.data.agent.tools.GetPortfolioSignalsTool
import com.stock.dividend.data.agent.tools.GetPortfolioSummaryTool
import com.stock.dividend.data.agent.tools.GetLivingExpensesTool
import com.stock.dividend.data.agent.tools.GetMarketIndexTool
import com.stock.dividend.data.agent.tools.GetMarketSentimentTool
import com.stock.dividend.data.agent.tools.GetResearchReportsTool
import com.stock.dividend.data.agent.tools.GetStockEvaluationTool
import com.stock.dividend.data.agent.tools.GetStockInfoTool
import com.stock.dividend.data.agent.tools.GetStockNewsTool
import com.stock.dividend.data.agent.tools.GetTransactionsTool
import com.stock.dividend.data.agent.tools.GetTreasuryYieldsTool
import com.stock.dividend.data.agent.tools.GetUserStrategiesTool
import com.stock.dividend.data.agent.tools.GetValuationMetricsTool
import com.stock.dividend.data.agent.tools.RemoveStockTool
import com.stock.dividend.data.agent.tools.RemoveLivingExpenseTool
import com.stock.dividend.data.agent.tools.SearchStockTool
import com.stock.dividend.data.agent.tools.SetFireGoalTool
import com.stock.dividend.data.agent.tools.SetStockTagsTool
import com.stock.dividend.data.agent.tools.UpdateHoldingTool
import com.stock.dividend.data.agent.tools.UpdateIndustryTargetTool
import com.stock.dividend.data.agent.tools.UpdateLivingExpenseTool
import com.stock.dividend.data.agent.tools.UpdateNotificationRuleTool
import com.stock.dividend.data.agent.tools.UpdateStockSettingsTool
import com.stock.dividend.data.local.dao.StockYearlyIncome
import com.stock.dividend.data.local.dao.YearlyTotal
import com.stock.dividend.data.local.entity.DividendEntity
import com.stock.dividend.data.local.entity.DividendIncomeRecordEntity
import com.stock.dividend.data.local.entity.GridPlanEntity
import com.stock.dividend.data.local.entity.StockEntity
import com.stock.dividend.data.local.entity.LivingExpenseItemEntity
import com.stock.dividend.data.local.entity.StockTagEntity
import com.stock.dividend.data.repository.BollBand
import com.stock.dividend.data.repository.BondYieldRepository
import com.stock.dividend.data.repository.CapitalFlow
import com.stock.dividend.data.repository.DividendIncomeRepository
import com.stock.dividend.data.repository.DividendMetrics
import com.stock.dividend.data.repository.DividendRepository
import com.stock.dividend.data.repository.DividendThresholds
import com.stock.dividend.data.repository.DragonTigerItem
import com.stock.dividend.data.repository.FireGoalRepository
import com.stock.dividend.data.repository.FinancialStatements
import com.stock.dividend.data.repository.FinancialStatementsRepository
import com.stock.dividend.data.repository.Fundamentals
import com.stock.dividend.data.repository.FundamentalsCacheRepository
import com.stock.dividend.data.repository.GridPlanRepository
import com.stock.dividend.data.repository.IndexQuote
import com.stock.dividend.data.repository.KlineBar
import com.stock.dividend.data.repository.KlinePeriod
import com.stock.dividend.data.repository.KlineRepository
import com.stock.dividend.data.repository.LivingExpenseRepository
import com.stock.dividend.data.repository.MarketDataRepository
import com.stock.dividend.data.repository.MarketListItem
import com.stock.dividend.data.repository.NotificationRuleRepository
import com.stock.dividend.data.repository.QuoteSnapshot
import com.stock.dividend.data.repository.ResearchReport
import com.stock.dividend.data.repository.ResearchRepository
import com.stock.dividend.data.repository.StockAnnouncement
import com.stock.dividend.data.repository.StockRepository
import com.stock.dividend.data.repository.StockSearchResult
import com.stock.dividend.data.repository.TradeStrategyRepository
import com.stock.dividend.data.local.entity.TransactionEntity
import com.stock.dividend.data.repository.TransactionRepository
import com.stock.dividend.data.repository.TreasuryYields
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

class StockAgentToolsTest {

    @Test
    fun addStockTool_declaration_requiresCode() {
        val tool = AddStockTool(mockk())
        val decl = tool.declaration()
        assertThat(decl.name).isEqualTo("add_stock")
        assertThat(decl.parameters!!.required).containsExactly("code")
    }

    @Test
    fun addStockTool_returnsConfirmationPlaceholderWhenUnconfirmed() = runTest {
        val repo = mockk<StockRepository>(relaxed = true)
        val tool = AddStockTool(repo)
        val context = mockk<ToolContext>(relaxed = true)
        every { context.toolConfirmation } returns null
        val result = tool.run(context, mapOf("code" to "600519")) as Map<*, *>
        assertThat(result["error"]).isEqualTo(FunctionTool.CONFIRMATION_REQUIRED_ERROR)
        coVerify(exactly = 0) { repo.addStock(any(), any(), any(), any()) }
        coVerify { context.requestConfirmation(any(), any()) }
    }

    @Test
    fun addStockTool_runsRepositoryWhenConfirmed() = runTest {
        val repo = mockk<StockRepository>(relaxed = true)
        coEvery { repo.resolveStock("600519") } returns
            StockSearchResult(code = "sh.600519", name = "贵州茅台", marketCode = "1")
        coEvery { repo.addStock(any(), 0, 0.0, any()) } returns Result.success(Unit)
        val tool = AddStockTool(repo)
        val context = mockk<ToolContext>(relaxed = true)
        every { context.toolConfirmation } returns ToolConfirmation(confirmed = true)
        val result = tool.run(context, mapOf("code" to "600519")) as Map<*, *>
        assertThat(result["ok"]).isEqualTo(true)
        coVerify { repo.addStock(any(), 0, 0.0, any()) }
    }

    @Test
    fun addStockTool_skipsRepositoryWhenRejected() = runTest {
        val repo = mockk<StockRepository>(relaxed = true)
        val tool = AddStockTool(repo)
        val context = mockk<ToolContext>(relaxed = true)
        every { context.toolConfirmation } returns ToolConfirmation(confirmed = false)
        val result = tool.run(context, mapOf("code" to "600519")) as Map<*, *>
        assertThat(result["error"]).isEqualTo(FunctionTool.REJECTED_ERROR)
        coVerify(exactly = 0) { repo.addStock(any(), any(), any(), any()) }
    }

    @Test
    fun addStockTool_returnsErrorWhenStockNotFound() = runTest {
        val repo = mockk<StockRepository>(relaxed = true)
        coEvery { repo.resolveStock("000000") } returns null
        val tool = AddStockTool(repo)
        val context = mockk<ToolContext>(relaxed = true)
        every { context.toolConfirmation } returns ToolConfirmation(confirmed = true)
        val result = tool.run(context, mapOf("code" to "000000")) as Map<*, *>
        assertThat(result["error"]?.toString()).contains("未找到")
        coVerify(exactly = 0) { repo.addStock(any(), any(), any(), any()) }
    }

    @Test
    fun addStockTool_acceptsStringNumberParams() = runTest {
        val repo = mockk<StockRepository>(relaxed = true)
        coEvery { repo.resolveStock("600519") } returns StockSearchResult("sh.600519", "贵州茅台", "1")
        coEvery { repo.addStock(any(), 100, 12.5, any()) } returns Result.success(Unit)
        val tool = AddStockTool(repo)
        val context = mockk<ToolContext>(relaxed = true)
        every { context.toolConfirmation } returns ToolConfirmation(confirmed = true)
        val result = tool.run(
            context,
            mapOf("code" to "600519", "shares" to "100", "costPerShare" to "12.5")
        ) as Map<*, *>
        assertThat(result["ok"]).isEqualTo(true)
        coVerify { repo.addStock(any(), 100, 12.5, any()) }
    }

    @Test
    fun addLivingExpenseTool_validatesAndCallsRepo() = runTest {
        val repo = mockk<LivingExpenseRepository>(relaxed = true)
        coEvery { repo.addExpense("房租", 3000.0, "MONTHLY") } returns 42L
        val tool = AddLivingExpenseTool(repo)
        val context = mockk<ToolContext>(relaxed = true)
        every { context.toolConfirmation } returns ToolConfirmation(confirmed = true)
        val result = tool.run(context, mapOf("name" to "房租", "amount" to 3000.0)) as Map<*, *>
        assertThat(result["ok"]).isEqualTo(true)
        assertThat(result["id"]).isEqualTo(42L)
        coVerify { repo.addExpense("房租", 3000.0, "MONTHLY") }
    }

    @Test
    fun addLivingExpenseTool_returnsErrorOnInvalidAmount() = runTest {
        val repo = mockk<LivingExpenseRepository>(relaxed = true)
        val tool = AddLivingExpenseTool(repo)
        val context = mockk<ToolContext>(relaxed = true)
        every { context.toolConfirmation } returns ToolConfirmation(confirmed = true)
        val result = tool.run(context, mapOf("name" to "房租", "amount" to -1.0)) as Map<*, *>
        assertThat(result["error"]).isNotNull()
        coVerify(exactly = 0) { repo.addExpense(any(), any(), any()) }
    }

    @Test
    fun removeLivingExpenseTool_skipsDeleteOnBadPeriod() = runTest {
        val repo = mockk<LivingExpenseRepository>(relaxed = true)
        val tool = RemoveLivingExpenseTool(repo)
        val context = mockk<ToolContext>(relaxed = true)
        every { context.toolConfirmation } returns ToolConfirmation(confirmed = true)
        val result = tool.run(context, mapOf("id" to "abc")) as Map<*, *>
        assertThat(result["error"]).isNotNull()
        coVerify(exactly = 0) { repo.deleteExpense(any()) }
    }

    @Test
    fun setFireGoalTool_skipsOnInvalidAmount() = runTest {
        val repo = mockk<FireGoalRepository>(relaxed = true)
        val tool = SetFireGoalTool(repo)
        val context = mockk<ToolContext>(relaxed = true)
        every { context.toolConfirmation } returns ToolConfirmation(confirmed = true)
        val result = tool.run(context, mapOf("amount" to 0.0)) as Map<*, *>
        assertThat(result["error"]).isNotNull()
        coVerify(exactly = 0) { repo.saveGoal(any()) }
    }

    @Test
    fun getHoldingsTool_returnsHoldingsAndCachedPrices() = runTest {
        val repo = mockk<StockRepository>(relaxed = true)
        coEvery { repo.observeAllStocksForSnapshot() } returns emptyList()
        coEvery { repo.getCachedPrices(emptyList()) } returns emptyMap()
        coEvery { repo.observeAllStockTags() } returns flowOf(emptyList())
        val tool = GetHoldingsTool(repo)
        val context = mockk<ToolContext>(relaxed = true)
        val result = tool.run(context, emptyMap()) as Map<*, *>
        assertThat(result["holdings"]).isNotNull()
    }

    @Test
    fun getHoldingsTool_includesTagsAndLastUpdated() = runTest {
        val repo = mockk<StockRepository>(relaxed = true)
        val stock = StockEntity(
            code = "sh.600519", name = "贵州茅台", marketCode = "1",
            shares = 100, costPerShare = 100.0, industry = "白酒", lastUpdated = 123456789L
        )
        coEvery { repo.observeAllStocksForSnapshot() } returns listOf(stock)
        coEvery { repo.fetchQuotes(listOf(stock)) } returns mapOf("sh.600519" to 200.0)
        coEvery { repo.getCachedPrices(listOf("sh.600519")) } returns mapOf("sh.600519" to 200.0)
        coEvery { repo.observeAllStockTags() } returns flowOf(listOf(StockTagEntity("sh.600519", "红利")))
        val tool = GetHoldingsTool(repo)
        val context = mockk<ToolContext>(relaxed = true)
        val result = tool.run(context, emptyMap()) as Map<*, *>
        val holding = (result["holdings"] as List<*>).single() as Map<*, *>
        assertThat(holding["tags"] as List<*>).containsExactly("红利")
        assertThat(holding["lastUpdated"]).isEqualTo(123456789L)
    }

    @Test
    fun getHoldingsTool_usesFreshQuotesOverCache() = runTest {
        val repo = mockk<StockRepository>(relaxed = true)
        val stock = StockEntity(
            code = "sh.600036", name = "招商银行", marketCode = "1", shares = 100, costPerShare = 30.0
        )
        coEvery { repo.observeAllStocksForSnapshot() } returns listOf(stock)
        // 实时价 210 优先于缓存价 200：同一会话内与其他实时工具口径一致
        coEvery { repo.fetchQuotes(listOf(stock)) } returns mapOf("sh.600036" to 210.0)
        coEvery { repo.getCachedPrices(listOf("sh.600036")) } returns mapOf("sh.600036" to 200.0)
        coEvery { repo.observeAllStockTags() } returns flowOf(emptyList())
        val tool = GetHoldingsTool(repo)
        val context = mockk<ToolContext>(relaxed = true)
        val result = tool.run(context, emptyMap()) as Map<*, *>
        val holding = (result["holdings"] as List<*>).single() as Map<*, *>
        assertThat(holding["currentPrice"]).isEqualTo(210.0)
        assertThat(holding["marketValue"]).isEqualTo(21000.0)
    }

    @Test
    fun getHoldingsTool_fallsBackToCachedPricesWhenQuotesFail() = runTest {
        val repo = mockk<StockRepository>(relaxed = true)
        val stock = StockEntity(
            code = "sh.600036", name = "招商银行", marketCode = "1", shares = 100, costPerShare = 30.0
        )
        coEvery { repo.observeAllStocksForSnapshot() } returns listOf(stock)
        coEvery { repo.fetchQuotes(any()) } returns emptyMap()
        coEvery { repo.getCachedPrices(listOf("sh.600036")) } returns mapOf("sh.600036" to 200.0)
        coEvery { repo.observeAllStockTags() } returns flowOf(emptyList())
        val tool = GetHoldingsTool(repo)
        val context = mockk<ToolContext>(relaxed = true)
        val result = tool.run(context, emptyMap()) as Map<*, *>
        val holding = (result["holdings"] as List<*>).single() as Map<*, *>
        assertThat(holding["currentPrice"]).isEqualTo(200.0)
    }

    @Test
    fun updateHoldingTool_updatesAndReturnsOk() = runTest {
        val repo = mockk<StockRepository>(relaxed = true)
        coEvery { repo.resolveStock("600519") } returns StockSearchResult("sh.600519", "贵州茅台", "1")
        val tool = UpdateHoldingTool(repo)
        val context = mockk<ToolContext>(relaxed = true)
        every { context.toolConfirmation } returns ToolConfirmation(confirmed = true)
        val result = tool.run(context, mapOf("code" to "600519", "shares" to 100, "costPerShare" to 120.0)) as Map<*, *>
        assertThat(result["ok"]).isEqualTo(true)
        coVerify { repo.updateShares("sh.600519", 100) }
        coVerify { repo.updateCostPerShare("sh.600519", 120.0) }
    }

    @Test
    fun updateHoldingTool_rejectsNegativeShares() = runTest {
        val repo = mockk<StockRepository>(relaxed = true)
        val tool = UpdateHoldingTool(repo)
        val context = mockk<ToolContext>(relaxed = true)
        every { context.toolConfirmation } returns ToolConfirmation(confirmed = true)
        val result = tool.run(context, mapOf("code" to "600519", "shares" to -1, "costPerShare" to 1.0)) as Map<*, *>
        assertThat(result["error"]).isNotNull()
        coVerify(exactly = 0) { repo.updateShares(any(), any()) }
    }

    @Test
    fun removeStockTool_removesStock() = runTest {
        val repo = mockk<StockRepository>(relaxed = true)
        coEvery { repo.resolveStock("600519") } returns StockSearchResult("sh.600519", "贵州茅台", "1")
        val tool = RemoveStockTool(repo)
        val context = mockk<ToolContext>(relaxed = true)
        every { context.toolConfirmation } returns ToolConfirmation(confirmed = true)
        val result = tool.run(context, mapOf("code" to "600519")) as Map<*, *>
        assertThat(result["ok"]).isEqualTo(true)
        coVerify { repo.removeStock("sh.600519") }
    }

    @Test
    fun addTransactionTool_recordsAndRecomputes() = runTest {
        val stockRepo = mockk<StockRepository>(relaxed = true)
        coEvery { stockRepo.resolveStock("600519") } returns StockSearchResult("sh.600519", "贵州茅台", "1")
        val txRepo = mockk<TransactionRepository>(relaxed = true)
        val tool = AddTransactionTool(stockRepo, txRepo)
        val context = mockk<ToolContext>(relaxed = true)
        every { context.toolConfirmation } returns ToolConfirmation(confirmed = true)
        val result = tool.run(
            context,
            mapOf("code" to "600519", "type" to "buy", "shares" to 100, "price" to 120.0)
        ) as Map<*, *>
        assertThat(result["ok"]).isEqualTo(true)
        coVerify { txRepo.addTransaction(match { it.stockCode == "sh.600519" && it.type == "BUY" && it.shares == 100 }) }
        coVerify { stockRepo.recomputeHolding("sh.600519") }
    }

    @Test
    fun addTransactionTool_rejectsInvalidType() = runTest {
        val tool = AddTransactionTool(mockk(relaxed = true), mockk(relaxed = true))
        val context = mockk<ToolContext>(relaxed = true)
        every { context.toolConfirmation } returns ToolConfirmation(confirmed = true)
        val result = tool.run(
            context,
            mapOf("code" to "600519", "type" to "HOLD", "shares" to 100, "price" to 120.0)
        ) as Map<*, *>
        assertThat(result["error"]).isNotNull()
    }

    @Test
    fun setStockTagsTool_overwritesTags() = runTest {
        val repo = mockk<StockRepository>(relaxed = true)
        coEvery { repo.resolveStock("600519") } returns StockSearchResult("sh.600519", "贵州茅台", "1")
        val tool = SetStockTagsTool(repo)
        val context = mockk<ToolContext>(relaxed = true)
        every { context.toolConfirmation } returns ToolConfirmation(confirmed = true)
        val result = tool.run(context, mapOf("code" to "600519", "tags" to listOf("红利", "核心"))) as Map<*, *>
        assertThat(result["ok"]).isEqualTo(true)
        coVerify { repo.setStockTags("sh.600519", listOf("红利", "核心")) }
    }

    @Test
    fun setStockTagsTool_acceptsCommaSeparated() = runTest {
        val repo = mockk<StockRepository>(relaxed = true)
        coEvery { repo.resolveStock("600519") } returns StockSearchResult("sh.600519", "贵州茅台", "1")
        val tool = SetStockTagsTool(repo)
        val context = mockk<ToolContext>(relaxed = true)
        every { context.toolConfirmation } returns ToolConfirmation(confirmed = true)
        val result = tool.run(context, mapOf("code" to "600519", "tags" to "红利,核心")) as Map<*, *>
        assertThat(result["ok"]).isEqualTo(true)
        coVerify { repo.setStockTags("sh.600519", listOf("红利", "核心")) }
    }

    @Test
    fun updateIndustryTargetTool_rejectsOutOfRangeWeight() = runTest {
        val repo = mockk<StockRepository>(relaxed = true)
        val tool = UpdateIndustryTargetTool(repo)
        val context = mockk<ToolContext>(relaxed = true)
        every { context.toolConfirmation } returns ToolConfirmation(confirmed = true)
        val result = tool.run(context, mapOf("industry" to "银行", "weight" to 150.0)) as Map<*, *>
        assertThat(result["error"]).isNotNull()
        coVerify(exactly = 0) { repo.updateIndustryTarget(any(), any()) }
    }

    @Test
    fun updateNotificationRuleTool_rejectsBoostLessThanMin() = runTest {
        val repo = mockk<NotificationRuleRepository>(relaxed = true)
        val tool = UpdateNotificationRuleTool(mockk(relaxed = true), repo)
        val context = mockk<ToolContext>(relaxed = true)
        every { context.toolConfirmation } returns ToolConfirmation(confirmed = true)
        val result = tool.run(context, mapOf("minYield" to 5.0, "boostYield" to 3.0)) as Map<*, *>
        assertThat(result["error"]).isNotNull()
        coVerify(exactly = 0) { repo.saveEvalThresholds(any(), any()) }
    }

    @Test
    fun updateNotificationRuleTool_savesStockRule() = runTest {
        val stockRepo = mockk<StockRepository>(relaxed = true)
        coEvery { stockRepo.resolveStock("600519") } returns StockSearchResult("sh.600519", "贵州茅台", "1")
        val ruleRepo = mockk<NotificationRuleRepository>(relaxed = true)
        val tool = UpdateNotificationRuleTool(stockRepo, ruleRepo)
        val context = mockk<ToolContext>(relaxed = true)
        every { context.toolConfirmation } returns ToolConfirmation(confirmed = true)
        val result = tool.run(context, mapOf("code" to "600519", "thresholdPercent" to 5.0)) as Map<*, *>
        assertThat(result["ok"]).isEqualTo(true)
        coVerify { ruleRepo.saveDividendYieldRule("sh.600519", true, 5.0, any()) }
    }

    @Test
    fun updateNotificationRuleTool_requiresThresholdForStockRule() = runTest {
        val ruleRepo = mockk<NotificationRuleRepository>(relaxed = true)
        val tool = UpdateNotificationRuleTool(mockk(relaxed = true), ruleRepo)
        val context = mockk<ToolContext>(relaxed = true)
        every { context.toolConfirmation } returns ToolConfirmation(confirmed = true)
        val result = tool.run(context, mapOf("code" to "600519")) as Map<*, *>
        assertThat(result["error"]).isNotNull()
        coVerify(exactly = 0) { ruleRepo.saveDividendYieldRule(any(), any(), any()) }
    }

    @Test
    fun updateStockSettingsTool_updatesMultiplierAndPeriod() = runTest {
        val repo = mockk<StockRepository>(relaxed = true)
        coEvery { repo.resolveStock("600519") } returns StockSearchResult("sh.600519", "贵州茅台", "1")
        val tool = UpdateStockSettingsTool(repo)
        val context = mockk<ToolContext>(relaxed = true)
        every { context.toolConfirmation } returns ToolConfirmation(confirmed = true)
        val result = tool.run(
            context,
            mapOf("code" to "600519", "buyThresholdMultiplier" to 3.0, "yieldPeriod" to "5")
        ) as Map<*, *>
        assertThat(result["ok"]).isEqualTo(true)
        coVerify { repo.updateBuyThresholdMultiplier("sh.600519", 3.0) }
        coVerify { repo.updateYieldPeriod("sh.600519", "5") }
    }

    @Test
    fun updateStockSettingsTool_rejectsInvalidValues() = runTest {
        val repo = mockk<StockRepository>(relaxed = true)
        val tool = UpdateStockSettingsTool(repo)
        val context = mockk<ToolContext>(relaxed = true)
        every { context.toolConfirmation } returns ToolConfirmation(confirmed = true)
        val badMultiplier = tool.run(
            context, mapOf("code" to "600519", "buyThresholdMultiplier" to 0.0)
        ) as Map<*, *>
        assertThat(badMultiplier["error"]).isNotNull()
        val badPeriod = tool.run(
            context, mapOf("code" to "600519", "yieldPeriod" to "10")
        ) as Map<*, *>
        assertThat(badPeriod["error"]).isNotNull()
        val none = tool.run(context, mapOf("code" to "600519")) as Map<*, *>
        assertThat(none["error"]).isNotNull()
        coVerify(exactly = 0) { repo.updateBuyThresholdMultiplier(any(), any()) }
        coVerify(exactly = 0) { repo.updateYieldPeriod(any(), any()) }
    }

    @Test
    fun updateLivingExpenseTool_updatesExpense() = runTest {
        val repo = mockk<LivingExpenseRepository>(relaxed = true)
        val tool = UpdateLivingExpenseTool(repo)
        val context = mockk<ToolContext>(relaxed = true)
        every { context.toolConfirmation } returns ToolConfirmation(confirmed = true)
        val result = tool.run(
            context,
            mapOf("id" to 3L, "name" to "房租", "amount" to 3200.0, "period" to "MONTHLY")
        ) as Map<*, *>
        assertThat(result["ok"]).isEqualTo(true)
        coVerify { repo.updateExpense(3L, "房租", 3200.0, "MONTHLY") }
    }

    @Test
    fun removeLivingExpenseTool_deletesExpense() = runTest {
        val repo = mockk<LivingExpenseRepository>(relaxed = true)
        val tool = RemoveLivingExpenseTool(repo)
        val context = mockk<ToolContext>(relaxed = true)
        every { context.toolConfirmation } returns ToolConfirmation(confirmed = true)
        val result = tool.run(context, mapOf("id" to 7L)) as Map<*, *>
        assertThat(result["ok"]).isEqualTo(true)
        coVerify { repo.deleteExpense(7L) }
    }

    @Test
    fun setFireGoalTool_setsGoal() = runTest {
        val repo = mockk<FireGoalRepository>(relaxed = true)
        val tool = SetFireGoalTool(repo)
        val context = mockk<ToolContext>(relaxed = true)
        every { context.toolConfirmation } returns ToolConfirmation(confirmed = true)
        val result = tool.run(context, mapOf("amount" to 2_000_000.0)) as Map<*, *>
        assertThat(result["ok"]).isEqualTo(true)
        coVerify { repo.saveGoal(2_000_000.0) }
    }

    @Test
    fun getStockInfoTool_returnsErrorWhenNotFound() = runTest {
        val stockRepo = mockk<StockRepository>(relaxed = true)
        coEvery { stockRepo.resolveStock("000000") } returns null
        val tool = GetStockInfoTool(stockRepo, mockk(relaxed = true))
        val context = mockk<ToolContext>(relaxed = true)
        val result = tool.run(context, mapOf("code" to "000000")) as Map<*, *>
        assertThat(result["error"]?.toString()).contains("未找到")
    }

    @Test
    fun getStockInfoTool_includesLastUpdated() = runTest {
        val stockRepo = mockk<StockRepository>(relaxed = true)
        coEvery { stockRepo.resolveStock("600519") } returns StockSearchResult("sh.600519", "贵州茅台", "1")
        coEvery { stockRepo.fetchQuotes(any()) } returns mapOf("sh.600519" to 1500.0)
        coEvery { stockRepo.observeStock("sh.600519") } returns flowOf(
            StockEntity(code = "sh.600519", name = "贵州茅台", marketCode = "1", lastUpdated = 999L)
        )
        val dividendRepo = mockk<DividendRepository>(relaxed = true)
        coEvery { dividendRepo.getLatestDividend("sh.600519") } returns null
        coEvery { dividendRepo.observeDividends("sh.600519") } returns flowOf(emptyList())
        val tool = GetStockInfoTool(stockRepo, dividendRepo)
        val context = mockk<ToolContext>(relaxed = true)
        val result = tool.run(context, mapOf("code" to "600519")) as Map<*, *>
        assertThat(result["lastUpdated"]).isEqualTo(999L)
    }

    @Test
    fun getStockInfoTool_computesDividendYieldByCurrentPrice() = runTest {
        val stockRepo = mockk<StockRepository>(relaxed = true)
        coEvery { stockRepo.resolveStock("600519") } returns StockSearchResult("sh.600519", "贵州茅台", "1")
        coEvery { stockRepo.fetchQuotes(any()) } returns mapOf("sh.600519" to 1500.0)
        coEvery { stockRepo.observeStock("sh.600519") } returns flowOf(null)
        val dividendRepo = mockk<DividendRepository>(relaxed = true)
        val entity = DividendEntity(
            id = "d1", stockCode = "sh.600519", reportDate = "2024-12-31", cashPerShare = 2.0,
            dividendYield = 5.0, exDividendDate = "2025-06-20"
        )
        coEvery { dividendRepo.getLatestDividend("sh.600519") } returns entity
        coEvery { dividendRepo.observeDividends("sh.600519") } returns flowOf(listOf(entity))
        val tool = GetStockInfoTool(stockRepo, dividendRepo)
        val context = mockk<ToolContext>(relaxed = true)
        val result = tool.run(context, mapOf("code" to "600519")) as Map<*, *>
        // 股息率按现价实时计算（与 get_stock_evaluation/get_buy_threshold 同口径），
        // 而非透传 DB 里的除权时点历史快照 5.0
        assertThat((result["dividendYield"] as Double)).isWithin(1e-9).of(2.0 / 1500.0 * 100.0)
        assertThat(result["exDividendDate"]).isEqualTo("2025-06-20")
    }

    @Test
    fun getStockInfoTool_omitsDividendYieldWithoutPriceOrDividend() = runTest {
        val stockRepo = mockk<StockRepository>(relaxed = true)
        coEvery { stockRepo.resolveStock("600519") } returns StockSearchResult("sh.600519", "贵州茅台", "1")
        coEvery { stockRepo.fetchQuotes(any()) } returns emptyMap()
        coEvery { stockRepo.getCachedPrices(any()) } returns emptyMap()
        coEvery { stockRepo.observeStock("sh.600519") } returns flowOf(null)
        val dividendRepo = mockk<DividendRepository>(relaxed = true)
        val entity = DividendEntity(
            id = "d1", stockCode = "sh.600519", reportDate = "2024-12-31", cashPerShare = 2.0,
            dividendYield = 5.0, exDividendDate = "2025-06-20"
        )
        coEvery { dividendRepo.getLatestDividend("sh.600519") } returns entity
        coEvery { dividendRepo.observeDividends("sh.600519") } returns flowOf(listOf(entity))
        val tool = GetStockInfoTool(stockRepo, dividendRepo)
        val context = mockk<ToolContext>(relaxed = true)
        val result = tool.run(context, mapOf("code" to "600519")) as Map<*, *>
        // 无有效现价时不臆造股息率（缺失而非错误值）
        assertThat(result.containsKey("dividendYield")).isFalse()
        assertThat(result["exDividendDate"]).isEqualTo("2025-06-20")
    }

    @Test
    fun getFundamentalsTool_returnsPeriods() = runTest {
        val stockRepo = mockk<StockRepository>(relaxed = true)
        coEvery { stockRepo.resolveStock("600519") } returns StockSearchResult("sh.600519", "贵州茅台", "1")
        val dividendRepo = mockk<DividendRepository>(relaxed = true)
        coEvery { dividendRepo.observeDividends(any()) } returns flowOf(
            listOf(DividendEntity(id = "d1", stockCode = "sh.600519", reportDate = "2024-12-31", cashPerShare = 2.0))
        )
        val fundRepo = mockk<FundamentalsCacheRepository>(relaxed = true)
        coEvery { fundRepo.getFundamentals("sh.600519", false) } returns Fundamentals(
            periods = listOf(
                Fundamentals.Period(
                    reportDate = "2024-12-31", roe = 15.0, debtToAssetRatio = 30.0,
                    revenueYoy = 10.0, netProfitYoy = 8.0, basicEps = 5.0,
                    payoutRatio = 40.0, announceYield = 3.0, dividendPlan = "10派30元"
                )
            )
        )
        val tool = GetFundamentalsTool(stockRepo, dividendRepo, fundRepo)
        val context = mockk<ToolContext>(relaxed = true)
        val result = tool.run(context, mapOf("code" to "600519")) as Map<*, *>
        val period = (result["periods"] as List<*>).single() as Map<*, *>
        assertThat(period["reportDate"]).isEqualTo("2024-12-31")
        assertThat(period["roe"]).isEqualTo(15.0)
        assertThat(period["payoutRatio"]).isEqualTo(40.0)
        assertThat(period["dividendPlan"]).isEqualTo("10派30元")
    }

    @Test
    fun getFundamentalsTool_returnsErrorWhenDataMissing() = runTest {
        val stockRepo = mockk<StockRepository>(relaxed = true)
        coEvery { stockRepo.resolveStock("600519") } returns StockSearchResult("sh.600519", "贵州茅台", "1")
        val fundRepo = mockk<FundamentalsCacheRepository>(relaxed = true)
        coEvery { fundRepo.getFundamentals(any(), any()) } returns null
        val tool = GetFundamentalsTool(stockRepo, mockk(relaxed = true), fundRepo)
        val context = mockk<ToolContext>(relaxed = true)
        val result = tool.run(context, mapOf("code" to "600519")) as Map<*, *>
        assertThat(result["error"]?.toString()).contains("基本面数据不足")
    }

    @Test
    fun getFundamentalsTool_passesForceRefresh() = runTest {
        val stockRepo = mockk<StockRepository>(relaxed = true)
        coEvery { stockRepo.resolveStock("600519") } returns StockSearchResult("sh.600519", "贵州茅台", "1")
        val fundRepo = mockk<FundamentalsCacheRepository>(relaxed = true)
        coEvery { fundRepo.getFundamentals("sh.600519", true) } returns Fundamentals(periods = emptyList())
        val dividendRepo = mockk<DividendRepository>(relaxed = true)
        coEvery { dividendRepo.observeDividends(any()) } returns flowOf(emptyList())
        val tool = GetFundamentalsTool(stockRepo, dividendRepo, fundRepo)
        val context = mockk<ToolContext>(relaxed = true)
        tool.run(context, mapOf("code" to "600519", "forceRefresh" to true))
        coVerify { fundRepo.getFundamentals("sh.600519", true) }
    }

    @Test
    fun getKlineTool_returnsClosesAndBollBand() = runTest {
        val stockRepo = mockk<StockRepository>(relaxed = true)
        coEvery { stockRepo.resolveStock("600519") } returns StockSearchResult("sh.600519", "贵州茅台", "1")
        val klineRepo = mockk<KlineRepository>(relaxed = true)
        val klines = (1..20).map {
            KlineBar(
                date = "2026-01-%02d".format(it),
                open = it * 10.0,
                close = it * 10.0,
                high = it * 10.0,
                low = it * 10.0,
                volume = 1000.0
            )
        }
        coEvery { klineRepo.fetchKlines("sh.600519", KlinePeriod.WEEKLY, 40) } returns klines
        val tool = GetKlineTool(stockRepo, klineRepo)
        val context = mockk<ToolContext>(relaxed = true)
        val result = tool.run(context, mapOf("code" to "600519")) as Map<*, *>
        assertThat(result["closes"]).isEqualTo((1..20).map { it * 10.0 })
        assertThat((result["bars"] as List<*>)).hasSize(20)
        val bar = (result["bars"] as List<*>).first() as Map<*, *>
        assertThat(bar["date"]).isEqualTo("2026-01-01")
        assertThat(bar["volume"]).isEqualTo(1000.0)
        assertThat(result["latestClose"]).isEqualTo(200.0)
        assertThat(result["bollMiddle"]).isEqualTo(105.0)
        assertThat((result["bollUpper"] as Double)).isGreaterThan(105.0)
    }

    @Test
    fun getKlineTool_rejectsInvalidPeriod() = runTest {
        val stockRepo = mockk<StockRepository>(relaxed = true)
        coEvery { stockRepo.resolveStock("600519") } returns StockSearchResult("sh.600519", "贵州茅台", "1")
        val tool = GetKlineTool(stockRepo, mockk(relaxed = true))
        val context = mockk<ToolContext>(relaxed = true)
        val result = tool.run(context, mapOf("code" to "600519", "period" to "YEARLY")) as Map<*, *>
        assertThat(result["error"]?.toString()).contains("period")
    }

    @Test
    fun getKlineTool_returnsClosesWithoutBandWhenInsufficient() = runTest {
        val stockRepo = mockk<StockRepository>(relaxed = true)
        coEvery { stockRepo.resolveStock("600519") } returns StockSearchResult("sh.600519", "贵州茅台", "1")
        val klineRepo = mockk<KlineRepository>(relaxed = true)
        coEvery { klineRepo.fetchKlines(any(), any(), any()) } returns listOf(
            KlineBar("2026-01-01", 1.0, 1.0, 1.0, 1.0, 0.0),
            KlineBar("2026-01-02", 2.0, 2.0, 2.0, 2.0, 0.0),
            KlineBar("2026-01-03", 3.0, 3.0, 3.0, 3.0, 0.0)
        )
        val tool = GetKlineTool(stockRepo, klineRepo)
        val context = mockk<ToolContext>(relaxed = true)
        val result = tool.run(context, mapOf("code" to "600519")) as Map<*, *>
        assertThat(result["closes"]).isEqualTo(listOf(1.0, 2.0, 3.0))
        assertThat(result.containsKey("bollMiddle")).isFalse()
        assertThat(result["bollNote"]).isNotNull()
    }

    @Test
    fun getPortfolioSignalsTool_emptyPortfolioReturnsZeros() = runTest {
        val stockRepo = mockk<StockRepository>(relaxed = true)
        coEvery { stockRepo.observeAllStocksForSnapshot() } returns emptyList()
        val ruleRepo = mockk<NotificationRuleRepository>(relaxed = true)
        coEvery { ruleRepo.observeEvalThresholds() } returns flowOf(DividendThresholds())
        val tool = GetPortfolioSignalsTool(stockRepo, mockk(relaxed = true), ruleRepo)
        val context = mockk<ToolContext>(relaxed = true)
        val result = tool.run(context, emptyMap()) as Map<*, *>
        val pc = result["positionControl"] as Map<*, *>
        assertThat(pc["triggered"]).isEqualTo(false)
        assertThat(pc["targetCashPercent"]).isEqualTo(15)
        assertThat(result["buySignals"] as List<*>).isEmpty()
    }

    @Test
    fun getPortfolioSignalsTool_detectsResonantBuySignal() = runTest {
        val stockRepo = mockk<StockRepository>(relaxed = true)
        val stock = StockEntity(
            code = "sh.600519", name = "贵州茅台", marketCode = "1", shares = 100, industry = "白酒"
        )
        coEvery { stockRepo.observeAllStocksForSnapshot() } returns listOf(stock)
        coEvery { stockRepo.fetchQuotes(any()) } returns mapOf("sh.600519" to 100.0)
        coEvery { stockRepo.getCachedPrices(any()) } returns mapOf("sh.600519" to 100.0)
        coEvery { stockRepo.fetchBoll("sh.600519", KlinePeriod.WEEKLY) } returns BollBand(110.0, 125.0, 100.0)
        coEvery { stockRepo.fetchBoll("sh.600519", KlinePeriod.DAILY) } returns BollBand(110.0, 125.0, 100.0)
        coEvery { stockRepo.fetchBoll("sh.600519", KlinePeriod.MONTHLY) } returns BollBand(105.0, 125.0, 95.0)
        val dividendRepo = mockk<DividendRepository>(relaxed = true)
        coEvery { dividendRepo.observeDividends(any()) } returns flowOf(
            listOf(DividendEntity(id = "d1", stockCode = "sh.600519", reportDate = "2024-12-31", cashPerShare = 5.0))
        )
        val ruleRepo = mockk<NotificationRuleRepository>(relaxed = true)
        coEvery { ruleRepo.observeEvalThresholds() } returns flowOf(DividendThresholds())
        val tool = GetPortfolioSignalsTool(stockRepo, dividendRepo, ruleRepo)
        val context = mockk<ToolContext>(relaxed = true)
        val result = tool.run(context, emptyMap()) as Map<*, *>
        val buySignals = result["buySignals"] as List<*>
        assertThat(buySignals).hasSize(1)
        val signal = buySignals.single() as Map<*, *>
        assertThat(signal["code"]).isEqualTo("sh.600519")
        assertThat(signal["dailyAtLower"]).isEqualTo(true)
        assertThat(signal["monthlyBelowMiddle"]).isEqualTo(true)
    }

    @Test
    fun getDividendIncomeTool_returnsYearlyOverview() = runTest {
        val incomeRepo = mockk<DividendIncomeRepository>(relaxed = true)
        coEvery { incomeRepo.observeAvailableYears() } returns flowOf(listOf(2024, 2025))
        coEvery { incomeRepo.observeYearlyTotals() } returns flowOf(
            listOf(YearlyTotal(2024, 100.0), YearlyTotal(2025, 120.0))
        )
        coEvery { incomeRepo.observePerStockYearlyIncome() } returns flowOf(
            listOf(StockYearlyIncome("sh.600519", 2025, 120.0))
        )
        coEvery { incomeRepo.observeRecordCount() } returns flowOf(2)
        coEvery { incomeRepo.observeMaxSingleIncome() } returns flowOf(120.0)
        val stockRepo = mockk<StockRepository>(relaxed = true)
        coEvery { stockRepo.observeAllStocksForSnapshot() } returns
            listOf(StockEntity(code = "sh.600519", name = "贵州茅台", marketCode = "1"))
        val tool = GetDividendIncomeTool(incomeRepo, stockRepo)
        val context = mockk<ToolContext>(relaxed = true)
        val result = tool.run(context, emptyMap()) as Map<*, *>
        assertThat(result["years"] as List<*>).containsExactly(2024, 2025)
        val totals = result["yearlyTotals"] as List<*>
        assertThat((totals.first() as Map<*, *>)["total"]).isEqualTo(100.0)
        val perStock = result["perStockIncome"] as List<*>
        assertThat((perStock.single() as Map<*, *>)["stockName"]).isEqualTo("贵州茅台")
        assertThat(result["recordCount"]).isEqualTo(2)
    }

    @Test
    fun getDividendIncomeTool_returnsRecordsForYear() = runTest {
        val incomeRepo = mockk<DividendIncomeRepository>(relaxed = true)
        coEvery { incomeRepo.observeByYear(2026) } returns flowOf(
            listOf(
                DividendIncomeRecordEntity(
                    id = "r1", stockCode = "sh.600519", year = 2026,
                    date = "2026-06-30", amount = 300.0, source = "auto"
                )
            )
        )
        coEvery { incomeRepo.observeTotalByYear(2026) } returns flowOf(300.0)
        val stockRepo = mockk<StockRepository>(relaxed = true)
        coEvery { stockRepo.observeAllStocksForSnapshot() } returns
            listOf(StockEntity(code = "sh.600519", name = "贵州茅台", marketCode = "1"))
        val tool = GetDividendIncomeTool(incomeRepo, stockRepo)
        val context = mockk<ToolContext>(relaxed = true)
        val result = tool.run(context, mapOf("year" to "2026")) as Map<*, *>
        assertThat(result["year"]).isEqualTo(2026)
        assertThat(result["total"]).isEqualTo(300.0)
        val record = (result["records"] as List<*>).single() as Map<*, *>
        assertThat(record["amount"]).isEqualTo(300.0)
        assertThat(record["stockName"]).isEqualTo("贵州茅台")
    }

    @Test
    fun searchStockTool_returnsResults() = runTest {
        val repo = mockk<StockRepository>(relaxed = true)
        coEvery { repo.searchStocks("茅台") } returns Result.success(
            listOf(StockSearchResult("sh.600519", "贵州茅台", "1", 1500.0))
        )
        val tool = SearchStockTool(repo)
        val context = mockk<ToolContext>(relaxed = true)
        val result = tool.run(context, mapOf("query" to "茅台")) as Map<*, *>
        val list = result["results"] as List<*>
        assertThat(list.single()).isInstanceOf(Map::class.java)
        assertThat((list.single() as Map<*, *>)["name"]).isEqualTo("贵州茅台")
    }

    @Test
    fun getDividendHistoryTool_returnsErrorWhenNotFound() = runTest {
        val stockRepo = mockk<StockRepository>(relaxed = true)
        coEvery { stockRepo.resolveStock("000000") } returns null
        val tool = GetDividendHistoryTool(mockk(relaxed = true), stockRepo)
        val context = mockk<ToolContext>(relaxed = true)
        val result = tool.run(context, mapOf("code" to "000000")) as Map<*, *>
        assertThat(result["error"]?.toString()).contains("未找到")
    }

    @Test
    fun getBuyThresholdTool_returnsThresholdFields() = runTest {
        val stockRepo = mockk<StockRepository>(relaxed = true)
        coEvery { stockRepo.resolveStock("600519") } returns StockSearchResult("sh.600519", "贵州茅台", "1")
        coEvery { stockRepo.fetchQuotes(any()) } returns mapOf("sh.600519" to 1500.0)
        coEvery { stockRepo.observeStock(any()) } returns flowOf(null)
        val dividendRepo = mockk<DividendRepository>(relaxed = true)
        coEvery { dividendRepo.observeDividends(any()) } returns flowOf(emptyList())
        val bondRepo = mockk<BondYieldRepository>(relaxed = true)
        coEvery { bondRepo.fetch10YBondYield() } returns 2.0
        val tool = GetBuyThresholdTool(stockRepo, dividendRepo, bondRepo)
        val context = mockk<ToolContext>(relaxed = true)
        val result = tool.run(context, mapOf("code" to "600519")) as Map<*, *>
        assertThat(result["bondYield10Y"]).isEqualTo(2.0)
        assertThat(result["targetYieldPercent"]).isEqualTo(5.0)
        assertThat(result["reached"]).isNull()
    }

    @Test
    fun getStockEvaluationTool_returnsErrorWithoutPrice() = runTest {
        val stockRepo = mockk<StockRepository>(relaxed = true)
        coEvery { stockRepo.resolveStock("600519") } returns StockSearchResult("sh.600519", "贵州茅台", "1")
        coEvery { stockRepo.fetchQuotes(any()) } returns emptyMap()
        coEvery { stockRepo.getCachedPrices(any()) } returns emptyMap()
        val tool = GetStockEvaluationTool(stockRepo, mockk(relaxed = true), mockk(relaxed = true))
        val context = mockk<ToolContext>(relaxed = true)
        val result = tool.run(context, mapOf("code" to "600519")) as Map<*, *>
        assertThat(result["error"]?.toString()).contains("无有效现价")
    }

    @Test
    fun getPortfolioSummaryTool_returnsZerosForEmptyPortfolio() = runTest {
        val stockRepo = mockk<StockRepository>(relaxed = true)
        coEvery { stockRepo.observeAllStocksForSnapshot() } returns emptyList()
        coEvery { stockRepo.getCachedPrices(emptyList()) } returns emptyMap()
        val incomeRepo = mockk<DividendIncomeRepository>(relaxed = true)
        coEvery { incomeRepo.observeForecastTotal() } returns flowOf(100.0)
        val fireRepo = mockk<FireGoalRepository>(relaxed = true)
        coEvery { fireRepo.getGoalOnce() } returns null
        val expenseRepo = mockk<LivingExpenseRepository>(relaxed = true)
        coEvery { expenseRepo.observeExpenses() } returns flowOf(emptyList())
        val tool = GetPortfolioSummaryTool(stockRepo, incomeRepo, fireRepo, expenseRepo)
        val context = mockk<ToolContext>(relaxed = true)
        val result = tool.run(context, emptyMap()) as Map<*, *>
        assertThat(result["totalMarketValue"]).isEqualTo(0.0)
        assertThat(result["annualDividendForecast"]).isEqualTo(100.0)
        assertThat(result.containsKey("fireGoalAmount")).isFalse()
    }

    @Test
    fun getTransactionsTool_returnsErrorWhenCodeNotFound() = runTest {
        val stockRepo = mockk<StockRepository>(relaxed = true)
        coEvery { stockRepo.resolveStock("000000") } returns null
        val tool = GetTransactionsTool(stockRepo, mockk(relaxed = true))
        val context = mockk<ToolContext>(relaxed = true)
        val result = tool.run(context, mapOf("code" to "000000")) as Map<*, *>
        assertThat(result["error"]?.toString()).contains("未找到")
    }

    // ── get_grid_plans（网格计划查询）──────────────────────

    /** 现价 9.0、4 档网格（8/8.67/9.33/10）、已有一笔 BUY@8.7 → 下一档 8.67 + 执行进度 1/4。 */
    @Test
    fun getGridPlansTool_returnsPlansWithNextLevelAndExecution() = runTest {
        val stockRepo = mockk<StockRepository>(relaxed = true)
        coEvery { stockRepo.observeAllStocksForSnapshot() } returns
            listOf(StockEntity(code = "sh.600000", name = "浦发银行", marketCode = "1"))
        coEvery { stockRepo.fetchQuotes(any()) } returns mapOf("sh.600000" to 9.0)
        val gridRepo = mockk<GridPlanRepository>(relaxed = true)
        coEvery { gridRepo.observeAll() } returns flowOf(
            listOf(gridPlanOf("p1", "sh.600000"))
        )
        val txRepo = mockk<TransactionRepository>(relaxed = true)
        coEvery { txRepo.getAll() } returns listOf(
            TransactionEntity(stockCode = "sh.600000", type = "BUY", shares = 200, price = 8.7, date = "2026-08-01")
        )
        val tool = GetGridPlansTool(stockRepo, gridRepo, txRepo)
        val context = mockk<ToolContext>(relaxed = true)

        val result = tool.run(context, emptyMap()) as Map<*, *>
        val plans = result["plans"] as List<*>
        assertThat(plans).hasSize(1)
        val plan = plans[0] as Map<*, *>
        assertThat(plan["nextBuyLevel"]).isEqualTo(8.67)
        assertThat(plan["triggeredLevels"]).isEqualTo(1)
        assertThat(plan["totalLevels"]).isEqualTo(4)
        assertThat(plan["investedAmount"]).isEqualTo(1740.0)  // 8.7 × 200
        assertThat(plan["notifyEnabled"]).isEqualTo(true)
        assertThat(result["note"]?.toString()).contains("不自动下单")
    }

    /** 传 code 只返回该标的的计划。 */
    @Test
    fun getGridPlansTool_filtersByCode() = runTest {
        val stockRepo = mockk<StockRepository>(relaxed = true)
        coEvery { stockRepo.resolveStock("600000") } returns
            StockSearchResult(code = "sh.600000", name = "浦发银行", marketCode = "1")
        coEvery { stockRepo.observeAllStocksForSnapshot() } returns
            listOf(StockEntity(code = "sh.600000", name = "浦发银行", marketCode = "1"))
        coEvery { stockRepo.fetchQuotes(any()) } returns emptyMap()
        val gridRepo = mockk<GridPlanRepository>(relaxed = true)
        coEvery { gridRepo.observeAll() } returns flowOf(
            listOf(gridPlanOf("p1", "sh.600000"), gridPlanOf("p2", "sz.000001"))
        )
        val tool = GetGridPlansTool(stockRepo, gridRepo, mockk(relaxed = true))
        val context = mockk<ToolContext>(relaxed = true)

        val result = tool.run(context, mapOf("code" to "600000")) as Map<*, *>
        val plans = result["plans"] as List<*>
        assertThat(plans).hasSize(1)
        assertThat((plans[0] as Map<*, *>)["code"]).isEqualTo("sh.600000")
    }

    /** 未知 code → 报错。 */
    @Test
    fun getGridPlansTool_returnsErrorWhenCodeNotFound() = runTest {
        val stockRepo = mockk<StockRepository>(relaxed = true)
        coEvery { stockRepo.resolveStock("000000") } returns null
        val tool = GetGridPlansTool(stockRepo, mockk(relaxed = true), mockk(relaxed = true))
        val context = mockk<ToolContext>(relaxed = true)
        val result = tool.run(context, mapOf("code" to "000000")) as Map<*, *>
        assertThat(result["error"]?.toString()).contains("未找到")
    }

    private fun gridPlanOf(id: String, stockCode: String) = GridPlanEntity(
        id = id, stockCode = stockCode, stockName = "示例股票",
        basePrice = 10.0, lowPrice = 8.0, highPrice = 12.0,
        grids = 4, totalCapital = 100000.0
    )

    @Test
    fun getNotificationRulesTool_returnsEmptyForEmptyHoldings() = runTest {
        val stockRepo = mockk<StockRepository>(relaxed = true)
        coEvery { stockRepo.observeAllStocksForSnapshot() } returns emptyList()
        val ruleRepo = mockk<NotificationRuleRepository>(relaxed = true)
        coEvery { ruleRepo.getEnabledStockRules(emptyList()) } returns emptyMap()
        coEvery { ruleRepo.getGlobalDividendYieldRule() } returns null
        val tool = GetNotificationRulesTool(stockRepo, ruleRepo)
        val context = mockk<ToolContext>(relaxed = true)
        val result = tool.run(context, emptyMap()) as Map<*, *>
        assertThat(result["stocks"] as List<*>).isEmpty()
    }

    @Test
    fun getUserStrategiesTool_returnsStrategies() = runTest {
        val repo = mockk<TradeStrategyRepository>(relaxed = true)
        coEvery { repo.activeStrategies() } returns emptyList()
        val tool = GetUserStrategiesTool(repo)
        val context = mockk<ToolContext>(relaxed = true)
        val result = tool.run(context, emptyMap()) as Map<*, *>
        assertThat(result["strategies"] as List<*>).isEmpty()
    }

    @Test
    fun getLivingExpensesTool_returnsExpensesWithId() = runTest {
        val repo = mockk<LivingExpenseRepository>(relaxed = true)
        coEvery { repo.observeExpenses() } returns flowOf(
            listOf(LivingExpenseItemEntity(id = 7L, name = "房租", amount = 3000.0, period = "MONTHLY", sortOrder = 0))
        )
        val tool = GetLivingExpensesTool(repo)
        val context = mockk<ToolContext>(relaxed = true)
        val result = tool.run(context, emptyMap()) as Map<*, *>
        val list = result["expenses"] as List<*>
        assertThat((list.single() as Map<*, *>)["id"]).isEqualTo(7L)
        assertThat((list.single() as Map<*, *>)["name"]).isEqualTo("房租")
    }

    // ────────────────────────────────────────────────────────────────
    // 新增 13 个工具测试（估值指标/资金面/财务三表/分红深度/行业/资讯研报/市场广度）
    // ────────────────────────────────────────────────────────────────

    @Test
    fun getCapitalFlowTool_declaration_requiresCode() {
        val tool = GetCapitalFlowTool(mockk(), mockk())
        assertThat(tool.declaration().name).isEqualTo("get_capital_flow")
        assertThat(tool.declaration().parameters!!.required).containsExactly("code")
    }

    @Test
    fun getCapitalFlowTool_returnsFlowFields() = runTest {
        val stockRepo = mockk<StockRepository>(relaxed = true)
        coEvery { stockRepo.resolveStock("600519") } returns StockSearchResult("sh.600519", "贵州茅台", "1")
        val marketRepo = mockk<MarketDataRepository>(relaxed = true)
        coEvery { marketRepo.fetchCapitalFlow("sh.600519") } returns CapitalFlow(
            mainNetInflow = 1.2e9, mainNetInflowPct = 5.0,
            superLargeNetInflow = 8.0e8, largeNetInflow = 4.0e8,
            mediumNetInflow = -2.0e8, smallNetInflow = -1.0e8,
            superLargeNetInflowPct = 3.0, largeNetInflowPct = 2.0,
            mediumNetInflowPct = -1.0, smallNetInflowPct = -0.5
        )
        val tool = GetCapitalFlowTool(stockRepo, marketRepo)
        val context = mockk<ToolContext>(relaxed = true)
        val result = tool.run(context, mapOf("code" to "600519")) as Map<*, *>
        assertThat(result["code"]).isEqualTo("sh.600519")
        assertThat(result["mainNetInflow"]).isEqualTo(1.2e9)
        assertThat(result["mainNetInflowPct"]).isEqualTo(5.0)
    }

    @Test
    fun getCapitalFlowTool_returnsErrorWhenNotFound() = runTest {
        val stockRepo = mockk<StockRepository>(relaxed = true)
        coEvery { stockRepo.resolveStock("000000") } returns null
        val tool = GetCapitalFlowTool(stockRepo, mockk(relaxed = true))
        val context = mockk<ToolContext>(relaxed = true)
        val result = tool.run(context, mapOf("code" to "000000")) as Map<*, *>
        assertThat(result["error"]?.toString()).contains("未找到股票")
    }

    @Test
    fun getValuationMetricsTool_returnsPePbMarketCap() = runTest {
        val stockRepo = mockk<StockRepository>(relaxed = true)
        coEvery { stockRepo.resolveStock("600519") } returns StockSearchResult("sh.600519", "贵州茅台", "1")
        coEvery { stockRepo.fetchQuoteSnapshots(any()) } returns mapOf(
            "sh.600519" to QuoteSnapshot(
                stockCode = "sh.600519", price = 1500.0,
                pe = 25.0, pb = 8.0,
                totalMarketCap = 1.8e12, circMarketCap = 1.8e12,
                turnoverRate = 0.3, amplitude = 1.2, volumeRatio = 0.8
            )
        )
        val tool = GetValuationMetricsTool(stockRepo)
        val context = mockk<ToolContext>(relaxed = true)
        val result = tool.run(context, mapOf("code" to "600519")) as Map<*, *>
        assertThat(result["peTtm"]).isEqualTo(25.0)
        assertThat(result["pb"]).isEqualTo(8.0)
        assertThat(result["totalMarketCap"]).isEqualTo(1.8e12)
    }

    @Test
    fun getDragonTigerTool_returnsItemsWithoutCode() = runTest {
        val marketRepo = mockk<MarketDataRepository>(relaxed = true)
        coEvery { marketRepo.fetchDragonTiger(null, 20) } returns listOf(
            DragonTigerItem(
                tradeDate = "2026-07-31", securityCode = "600519", securityName = "贵州茅台",
                explain = "日涨幅偏离值达7%", netBuy = 1.0e8, billboardDealAmt = 5.0e8
            )
        )
        val tool = GetDragonTigerTool(marketRepo)
        val context = mockk<ToolContext>(relaxed = true)
        val result = tool.run(context, emptyMap()) as Map<*, *>
        val item = (result["items"] as List<*>).single() as Map<*, *>
        assertThat(item["code"]).isEqualTo("600519")
        assertThat(item["netBuy"]).isEqualTo(1.0e8)
    }

    @Test
    fun getMarketSentimentTool_returnsIndicesAndIndustries() = runTest {
        val marketRepo = mockk<MarketDataRepository>(relaxed = true)
        coEvery { marketRepo.fetchIndexQuotes() } returns listOf(
            IndexQuote("000001", "上证指数", 3000.0, 1.5, null, null, null, null, 1.0e11)
        )
        coEvery { marketRepo.fetchIndustryList(any(), any()) } returns listOf(
            MarketListItem(code = "BK1277", name = "白酒", changePct = 3.0, leaderName = "贵州茅台",
                price = null, pe = null, pb = null, totalMarketCap = null, turnoverRate = null,
                industry = null, mainNetInflow = null, mainNetInflowPct = null,
                leaderCode = null, leaderChangePct = null)
        )
        val tool = GetMarketSentimentTool(marketRepo)
        val context = mockk<ToolContext>(relaxed = true)
        val result = tool.run(context, emptyMap()) as Map<*, *>
        assertThat((result["indices"] as List<*>)).isNotEmpty()
        assertThat((result["topIndustries"] as List<*>)).isNotEmpty()
    }

    @Test
    fun getFinancialStatementsTool_returnsPeriods() = runTest {
        val stockRepo = mockk<StockRepository>(relaxed = true)
        coEvery { stockRepo.resolveStock("600519") } returns StockSearchResult("sh.600519", "贵州茅台", "1")
        val finRepo = mockk<FinancialStatementsRepository>(relaxed = true)
        coEvery { finRepo.getFinancialStatements("sh.600519", false) } returns FinancialStatements(
            periods = listOf(
                FinancialStatements.Period(
                    reportDate = "2024-12-31",
                    totalOperateIncome = 1.7e10, parentNetProfit = 8.6e9,
                    netcashOperate = 9.0e9, totalAssets = 2.5e11,
                    operateCost = null, saleExpense = null, manageExpense = null,
                    financeExpense = null, operateProfit = null, totalProfit = null,
                    incomeTax = null, deductParentNetProfit = null,
                    netcashInvest = null, netcashFinance = null, endCce = null,
                    totalLiabilities = null, totalEquity = null, monetaryFunds = null,
                    accountsRece = null, inventory = null, accountsPayable = null,
                    fixedAsset = null
                )
            )
        )
        val tool = GetFinancialStatementsTool(stockRepo, finRepo)
        val context = mockk<ToolContext>(relaxed = true)
        val result = tool.run(context, mapOf("code" to "600519")) as Map<*, *>
        val period = (result["periods"] as List<*>).single() as Map<*, *>
        assertThat(period["reportDate"]).isEqualTo("2024-12-31")
        assertThat(period["totalOperateIncome"]).isEqualTo(1.7e10)
        assertThat(period["netcashOperate"]).isEqualTo(9.0e9)
    }

    @Test
    fun getFinancialStatementsTool_passesForceRefresh() = runTest {
        val stockRepo = mockk<StockRepository>(relaxed = true)
        coEvery { stockRepo.resolveStock("600519") } returns StockSearchResult("sh.600519", "贵州茅台", "1")
        val finRepo = mockk<FinancialStatementsRepository>(relaxed = true)
        coEvery { finRepo.getFinancialStatements("sh.600519", true) } returns FinancialStatements(periods = emptyList())
        val tool = GetFinancialStatementsTool(stockRepo, finRepo)
        val context = mockk<ToolContext>(relaxed = true)
        tool.run(context, mapOf("code" to "600519", "forceRefresh" to true))
        coVerify { finRepo.getFinancialStatements("sh.600519", true) }
    }

    @Test
    fun getDividendMetricsTool_returnsMetrics() = runTest {
        val stockRepo = mockk<StockRepository>(relaxed = true)
        coEvery { stockRepo.resolveStock("600519") } returns StockSearchResult("sh.600519", "贵州茅台", "1")
        val dividendRepo = mockk<DividendRepository>(relaxed = true)
        coEvery { dividendRepo.observeDividends("sh.600519") } returns flowOf(
            listOf(
                DividendEntity(id = "d1", stockCode = "sh.600519", reportDate = "2024-12-31", cashPerShare = 1.0),
                DividendEntity(id = "d2", stockCode = "sh.600519", reportDate = "2023-12-31", cashPerShare = 1.1),
                DividendEntity(id = "d3", stockCode = "sh.600519", reportDate = "2022-12-31", cashPerShare = 1.21)
            )
        )
        val tool = GetDividendMetricsTool(stockRepo, dividendRepo)
        val context = mockk<ToolContext>(relaxed = true)
        val result = tool.run(context, mapOf("code" to "600519")) as Map<*, *>
        assertThat(result["totalYears"]).isEqualTo(3)
        assertThat(result["consecutiveYears"]).isEqualTo(3)
        assertThat(result["latestYear"]).isEqualTo("2024")
        assertThat(result["cagr3y"]).isNotNull()
    }

    @Test
    fun getDividendMetricsTool_returnsErrorWhenNoData() = runTest {
        val stockRepo = mockk<StockRepository>(relaxed = true)
        coEvery { stockRepo.resolveStock("600519") } returns StockSearchResult("sh.600519", "贵州茅台", "1")
        val dividendRepo = mockk<DividendRepository>(relaxed = true)
        coEvery { dividendRepo.observeDividends(any()) } returns flowOf(emptyList())
        val tool = GetDividendMetricsTool(stockRepo, dividendRepo)
        val context = mockk<ToolContext>(relaxed = true)
        val result = tool.run(context, mapOf("code" to "600519")) as Map<*, *>
        assertThat(result["error"]?.toString()).contains("分红数据不足")
    }

    @Test
    fun getIndustryListTool_returnsIndustries() = runTest {
        val marketRepo = mockk<MarketDataRepository>(relaxed = true)
        coEvery {
            marketRepo.fetchIndustryList(MarketDataRepository.SortBy.CHANGE, 15)
        } returns listOf(
            MarketListItem(code = "BK1277", name = "白酒", changePct = 3.0, leaderName = "贵州茅台",
                price = null, pe = null, pb = null, totalMarketCap = null, turnoverRate = null,
                industry = null, mainNetInflow = null, mainNetInflowPct = null,
                leaderCode = null, leaderChangePct = null)
        )
        val tool = GetIndustryListTool(marketRepo)
        val context = mockk<ToolContext>(relaxed = true)
        val result = tool.run(context, emptyMap()) as Map<*, *>
        assertThat(result["sortBy"]).isEqualTo("CHANGE")
        val ind = (result["industries"] as List<*>).single() as Map<*, *>
        assertThat(ind["code"]).isEqualTo("BK1277")
    }

    @Test
    fun getIndustryListTool_parsesSortByInflow() = runTest {
        val marketRepo = mockk<MarketDataRepository>(relaxed = true)
        coEvery { marketRepo.fetchIndustryList(MarketDataRepository.SortBy.INFLOW, any()) } returns emptyList()
        val tool = GetIndustryListTool(marketRepo)
        val context = mockk<ToolContext>(relaxed = true)
        tool.run(context, mapOf("sortBy" to "INFLOW"))
        coVerify { marketRepo.fetchIndustryList(MarketDataRepository.SortBy.INFLOW, any()) }
    }

    @Test
    fun getIndustryPeersTool_requiresCodeOrIndustry() = runTest {
        val tool = GetIndustryPeersTool(mockk(relaxed = true), mockk(relaxed = true))
        val context = mockk<ToolContext>(relaxed = true)
        val result = tool.run(context, emptyMap()) as Map<*, *>
        assertThat(result["error"]?.toString()).contains("code 或 industry")
    }

    @Test
    fun getIndustryPeersTool_returnsPeersByIndustryCode() = runTest {
        val marketRepo = mockk<MarketDataRepository>(relaxed = true)
        coEvery {
            marketRepo.fetchIndustryPeers("BK1277", MarketDataRepository.PeerSortBy.MARKET_CAP, 15)
        } returns listOf(
            MarketListItem(code = "600519", name = "贵州茅台", price = 1500.0, pe = 25.0, totalMarketCap = 1.8e12,
                changePct = null, pb = null, turnoverRate = null, industry = null,
                mainNetInflow = null, mainNetInflowPct = null, leaderName = null,
                leaderCode = null, leaderChangePct = null)
        )
        val tool = GetIndustryPeersTool(mockk(relaxed = true), marketRepo)
        val context = mockk<ToolContext>(relaxed = true)
        val result = tool.run(context, mapOf("industry" to "BK1277")) as Map<*, *>
        val peer = (result["peers"] as List<*>).single() as Map<*, *>
        assertThat(peer["code"]).isEqualTo("600519")
        assertThat(peer["pe"]).isEqualTo(25.0)
    }

    @Test
    fun getResearchReportsTool_returnsReports() = runTest {
        val stockRepo = mockk<StockRepository>(relaxed = true)
        coEvery { stockRepo.resolveStock("600519") } returns StockSearchResult("sh.600519", "贵州茅台", "1")
        val researchRepo = mockk<ResearchRepository>(relaxed = true)
        coEvery { researchRepo.fetchReports("600519", 10) } returns listOf(
                ResearchReport(
                    title = "需求根基稳固", stockCode = "600519", stockName = "贵州茅台",
                    orgName = "中邮证券", publishDate = "2026-07-23",
                    predictThisYearEps = 67.19, predictThisYearPe = 19.42,
                    predictNextYearEps = null, predictNextYearPe = null, rating = "买入"
                )
        )
        val tool = GetResearchReportsTool(stockRepo, researchRepo)
        val context = mockk<ToolContext>(relaxed = true)
        val result = tool.run(context, mapOf("code" to "600519")) as Map<*, *>
        val r = (result["reports"] as List<*>).single() as Map<*, *>
        assertThat(r["orgName"]).isEqualTo("中邮证券")
        assertThat(r["predictThisYearEps"]).isEqualTo(67.19)
        assertThat(r["rating"]).isEqualTo("买入")
    }

    @Test
    fun getStockNewsTool_returnsAnnouncements() = runTest {
        val stockRepo = mockk<StockRepository>(relaxed = true)
        coEvery { stockRepo.resolveStock("600519") } returns StockSearchResult("sh.600519", "贵州茅台", "1")
        val researchRepo = mockk<ResearchRepository>(relaxed = true)
        coEvery { researchRepo.fetchAnnouncements("600519", 10) } returns listOf(
            StockAnnouncement(title = "贵州茅台重大事项公告", noticeDate = "2026-07-18", artCode = "AN123")
        )
        val tool = GetStockNewsTool(stockRepo, researchRepo)
        val context = mockk<ToolContext>(relaxed = true)
        val result = tool.run(context, mapOf("code" to "600519")) as Map<*, *>
        val a = (result["announcements"] as List<*>).single() as Map<*, *>
        assertThat(a["title"]).isEqualTo("贵州茅台重大事项公告")
        assertThat(a["noticeDate"]).isEqualTo("2026-07-18")
    }

    @Test
    fun getMarketIndexTool_returnsAllIndicesWhenNoCode() = runTest {
        val marketRepo = mockk<MarketDataRepository>(relaxed = true)
        coEvery { marketRepo.fetchIndexQuotes() } returns listOf(
            IndexQuote("000001", "上证指数", 3000.0, 1.5, null, null, null, null, 1.0e11)
        )
        val tool = GetMarketIndexTool(marketRepo)
        val context = mockk<ToolContext>(relaxed = true)
        val result = tool.run(context, emptyMap()) as Map<*, *>
        val idx = (result["indices"] as List<*>).single() as Map<*, *>
        assertThat(idx["name"]).isEqualTo("上证指数")
        assertThat(idx["price"]).isEqualTo(3000.0)
    }

    @Test
    fun getMarketIndexTool_returnsSingleIndexByCode() = runTest {
        val marketRepo = mockk<MarketDataRepository>(relaxed = true)
        coEvery { marketRepo.fetchIndexOrEtfQuote("000300") } returns
            IndexQuote("000300", "沪深300", 4000.0, 0.8, null, null, null, null, null)
        val tool = GetMarketIndexTool(marketRepo)
        val context = mockk<ToolContext>(relaxed = true)
        val result = tool.run(context, mapOf("code" to "000300")) as Map<*, *>
        val idx = (result["indices"] as List<*>).single() as Map<*, *>
        assertThat(idx["code"]).isEqualTo("000300")
    }

    @Test
    fun getEtfInfoTool_returnsEtfQuote() = runTest {
        val marketRepo = mockk<MarketDataRepository>(relaxed = true)
        coEvery { marketRepo.fetchIndexOrEtfQuote("510300") } returns
            IndexQuote("510300", "沪深300ETF", 4.65, 1.04, null, null, null, null, 7.0e9)
        val tool = GetEtfInfoTool(marketRepo)
        val context = mockk<ToolContext>(relaxed = true)
        val result = tool.run(context, mapOf("code" to "510300")) as Map<*, *>
        assertThat(result["name"]).isEqualTo("沪深300ETF")
        assertThat(result["price"]).isEqualTo(4.65)
    }

    @Test
    fun getTreasuryYieldsTool_returnsYields() = runTest {
        val bondRepo = mockk<BondYieldRepository>(relaxed = true)
        coEvery { bondRepo.fetchAllYields() } returns TreasuryYields(
            date = "2026-07-31",
            yield2Y = 1.26, yield5Y = 1.41, yield10Y = 1.71, yield30Y = 2.19,
            cnUsSpread10Y = 0.45, lpr1Y = 4.28, lpr5Y = 4.75
        )
        val tool = GetTreasuryYieldsTool(bondRepo)
        val context = mockk<ToolContext>(relaxed = true)
        val result = tool.run(context, emptyMap()) as Map<*, *>
        assertThat(result["cnGovBond10Y"]).isEqualTo(1.71)
        assertThat(result["cnGovBond30Y"]).isEqualTo(2.19)
        assertThat(result["lpr5Y"]).isEqualTo(4.75)
    }

    @Test
    fun addTradeStrategyTool_declaration_requiresCoreFields() {
        val tool = AddTradeStrategyTool(mockk())
        val decl = tool.declaration()
        assertThat(decl.name).isEqualTo("add_trade_strategy")
        assertThat(decl.parameters!!.required).containsExactly("targetText", "direction", "reasoning")
    }

    @Test
    fun addTradeStrategyTool_returnsConfirmationPlaceholderWhenUnconfirmed() = runTest {
        val repo = mockk<TradeStrategyRepository>(relaxed = true)
        val tool = AddTradeStrategyTool(repo)
        val context = mockk<ToolContext>(relaxed = true)
        every { context.toolConfirmation } returns null
        val result = tool.run(
            context,
            mapOf("targetText" to "银行股", "direction" to "buy", "reasoning" to "高股息")
        ) as Map<*, *>
        assertThat(result["error"]).isEqualTo(FunctionTool.CONFIRMATION_REQUIRED_ERROR)
        coVerify(exactly = 0) { repo.upsert(any()) }
        coVerify { context.requestConfirmation(any(), any()) }
    }

    @Test
    fun addTradeStrategyTool_writesEntityWhenConfirmed() = runTest {
        val repo = mockk<TradeStrategyRepository>(relaxed = true)
        val tool = AddTradeStrategyTool(repo)
        val context = mockk<ToolContext>(relaxed = true)
        every { context.toolConfirmation } returns ToolConfirmation(confirmed = true)
        val result = tool.run(
            context,
            mapOf(
                "targetText" to "银行股",
                "direction" to "buy",
                "reasoning" to "股息率高且破净",
                "risks" to listOf("估值修复不及预期", "分红下滑")
            )
        ) as Map<*, *>
        assertThat(result["ok"]).isEqualTo(true)
        assertThat(result["direction"]).isEqualTo("BUY")
        coVerify {
            repo.upsert(match {
                it.targetText == "银行股" && it.direction == "BUY" &&
                    it.reasoning == "股息率高且破净" && it.risks.contains("估值修复不及预期") &&
                    it.sourceNote == "AI 对话" && it.rawOcrText == ""
            })
        }
    }

    @Test
    fun addTradeStrategyTool_skipsRepositoryWhenRejected() = runTest {
        val repo = mockk<TradeStrategyRepository>(relaxed = true)
        val tool = AddTradeStrategyTool(repo)
        val context = mockk<ToolContext>(relaxed = true)
        every { context.toolConfirmation } returns ToolConfirmation(confirmed = false)
        val result = tool.run(
            context,
            mapOf("targetText" to "银行股", "direction" to "buy", "reasoning" to "高股息")
        ) as Map<*, *>
        assertThat(result["error"]).isEqualTo(FunctionTool.REJECTED_ERROR)
        coVerify(exactly = 0) { repo.upsert(any()) }
    }

    @Test
    fun addTradeStrategyTool_rejectsInvalidDirection() = runTest {
        val repo = mockk<TradeStrategyRepository>(relaxed = true)
        val tool = AddTradeStrategyTool(repo)
        val context = mockk<ToolContext>(relaxed = true)
        every { context.toolConfirmation } returns ToolConfirmation(confirmed = true)
        val result = tool.run(
            context,
            mapOf("targetText" to "银行股", "direction" to "HOLD", "reasoning" to "高股息")
        ) as Map<*, *>
        assertThat(result["error"]).isNotNull()
        coVerify(exactly = 0) { repo.upsert(any()) }
    }
}
