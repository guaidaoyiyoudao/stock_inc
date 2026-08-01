package com.stock.dividend.data.agent

import com.google.adk.kt.events.ToolConfirmation
import com.google.adk.kt.tools.FunctionTool
import com.google.adk.kt.tools.ToolContext
import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.agent.tools.AddTransactionTool
import com.stock.dividend.data.agent.tools.AddLivingExpenseTool
import com.stock.dividend.data.agent.tools.AddStockTool
import com.stock.dividend.data.agent.tools.GetBuyThresholdTool
import com.stock.dividend.data.agent.tools.GetDividendIncomeTool
import com.stock.dividend.data.agent.tools.GetDividendHistoryTool
import com.stock.dividend.data.agent.tools.GetFundamentalsTool
import com.stock.dividend.data.agent.tools.GetNotificationRulesTool
import com.stock.dividend.data.agent.tools.GetHoldingsTool
import com.stock.dividend.data.agent.tools.GetKlineTool
import com.stock.dividend.data.agent.tools.GetPortfolioSignalsTool
import com.stock.dividend.data.agent.tools.GetPortfolioSummaryTool
import com.stock.dividend.data.agent.tools.GetLivingExpensesTool
import com.stock.dividend.data.agent.tools.GetStockEvaluationTool
import com.stock.dividend.data.agent.tools.GetStockInfoTool
import com.stock.dividend.data.agent.tools.GetTransactionsTool
import com.stock.dividend.data.agent.tools.GetUserStrategiesTool
import com.stock.dividend.data.agent.tools.GetValuationTool
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
import com.stock.dividend.data.local.entity.StockEntity
import com.stock.dividend.data.local.entity.LivingExpenseItemEntity
import com.stock.dividend.data.local.entity.StockTagEntity
import com.stock.dividend.data.repository.BollBand
import com.stock.dividend.data.repository.BondYieldRepository
import com.stock.dividend.data.repository.DividendIncomeRepository
import com.stock.dividend.data.repository.DividendRepository
import com.stock.dividend.data.repository.DividendThresholds
import com.stock.dividend.data.repository.FireGoalRepository
import com.stock.dividend.data.repository.Fundamentals
import com.stock.dividend.data.repository.FundamentalsCacheRepository
import com.stock.dividend.data.repository.KlineBar
import com.stock.dividend.data.repository.KlinePeriod
import com.stock.dividend.data.repository.KlineRepository
import com.stock.dividend.data.repository.LivingExpenseRepository
import com.stock.dividend.data.repository.NotificationRuleRepository
import com.stock.dividend.data.repository.StockRepository
import com.stock.dividend.data.repository.StockSearchResult
import com.stock.dividend.data.repository.TradeStrategyRepository
import com.stock.dividend.data.repository.TransactionRepository
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
        val tool = GetStockInfoTool(stockRepo, dividendRepo)
        val context = mockk<ToolContext>(relaxed = true)
        val result = tool.run(context, mapOf("code" to "600519")) as Map<*, *>
        assertThat(result["lastUpdated"]).isEqualTo(999L)
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
    fun getValuationTool_returnsErrorOnInsufficientData() = runTest {
        val stockRepo = mockk<StockRepository>(relaxed = true)
        coEvery { stockRepo.resolveStock("600519") } returns StockSearchResult("sh.600519", "贵州茅台", "1")
        val dividendRepo = mockk<DividendRepository>(relaxed = true)
        coEvery { dividendRepo.observeDividends(any()) } returns flowOf(emptyList())
        val tool = GetValuationTool(stockRepo, dividendRepo)
        val context = mockk<ToolContext>(relaxed = true)
        val result = tool.run(context, mapOf("code" to "600519")) as Map<*, *>
        assertThat(result["error"]?.toString()).contains("分红数据不足")
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
}
