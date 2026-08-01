package com.stock.dividend.data.agent

import com.google.adk.kt.events.ToolConfirmation
import com.google.adk.kt.tools.FunctionTool
import com.google.adk.kt.tools.ToolContext
import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.agent.tools.AddTransactionTool
import com.stock.dividend.data.agent.tools.AddLivingExpenseTool
import com.stock.dividend.data.agent.tools.AddStockTool
import com.stock.dividend.data.agent.tools.GetBuyThresholdTool
import com.stock.dividend.data.agent.tools.GetDividendHistoryTool
import com.stock.dividend.data.agent.tools.GetNotificationRulesTool
import com.stock.dividend.data.agent.tools.GetHoldingsTool
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
import com.stock.dividend.data.local.entity.StockEntity
import com.stock.dividend.data.local.entity.LivingExpenseItemEntity
import com.stock.dividend.data.repository.BondYieldRepository
import com.stock.dividend.data.repository.DividendIncomeRepository
import com.stock.dividend.data.repository.DividendRepository
import com.stock.dividend.data.repository.FireGoalRepository
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
    fun `AddStockTool declaration 声明 code 必填`() {
        val tool = AddStockTool(mockk())
        val decl = tool.declaration()
        assertThat(decl.name).isEqualTo("add_stock")
        assertThat(decl.parameters!!.required).containsExactly("code")
    }

    @Test
    fun `AddStockTool 未确认时返回确认占位错误且不执行仓库`() = runTest {
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
    fun `AddStockTool 确认后执行仓库并返回 ok`() = runTest {
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
    fun `AddStockTool 拒绝确认时不执行仓库`() = runTest {
        val repo = mockk<StockRepository>(relaxed = true)
        val tool = AddStockTool(repo)
        val context = mockk<ToolContext>(relaxed = true)
        every { context.toolConfirmation } returns ToolConfirmation(confirmed = false)
        val result = tool.run(context, mapOf("code" to "600519")) as Map<*, *>
        assertThat(result["error"]).isEqualTo(FunctionTool.REJECTED_ERROR)
        coVerify(exactly = 0) { repo.addStock(any(), any(), any(), any()) }
    }

    @Test
    fun `AddStockTool 未找到股票返回错误`() = runTest {
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
    fun `AddStockTool 接受字符串数字参数`() = runTest {
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
    fun `AddLivingExpenseTool 校验金额并调用仓库`() = runTest {
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
    fun `AddLivingExpenseTool 金额非法时返回错误且不写库`() = runTest {
        val repo = mockk<LivingExpenseRepository>(relaxed = true)
        val tool = AddLivingExpenseTool(repo)
        val context = mockk<ToolContext>(relaxed = true)
        every { context.toolConfirmation } returns ToolConfirmation(confirmed = true)
        val result = tool.run(context, mapOf("name" to "房租", "amount" to -1.0)) as Map<*, *>
        assertThat(result["error"]).isNotNull()
        coVerify(exactly = 0) { repo.addExpense(any(), any(), any()) }
    }

    @Test
    fun `RemoveLivingExpenseTool 周期参数错误时不删除`() = runTest {
        val repo = mockk<LivingExpenseRepository>(relaxed = true)
        val tool = RemoveLivingExpenseTool(repo)
        val context = mockk<ToolContext>(relaxed = true)
        every { context.toolConfirmation } returns ToolConfirmation(confirmed = true)
        val result = tool.run(context, mapOf("id" to "abc")) as Map<*, *>
        assertThat(result["error"]).isNotNull()
        coVerify(exactly = 0) { repo.deleteExpense(any()) }
    }

    @Test
    fun `SetFireGoalTool 目标金额非法时不写库`() = runTest {
        val repo = mockk<FireGoalRepository>(relaxed = true)
        val tool = SetFireGoalTool(repo)
        val context = mockk<ToolContext>(relaxed = true)
        every { context.toolConfirmation } returns ToolConfirmation(confirmed = true)
        val result = tool.run(context, mapOf("amount" to 0.0)) as Map<*, *>
        assertThat(result["error"]).isNotNull()
        coVerify(exactly = 0) { repo.saveGoal(any()) }
    }

    @Test
    fun `GetHoldingsTool 返回持仓列表与缓存价格`() = runTest {
        val repo = mockk<StockRepository>(relaxed = true)
        coEvery { repo.observeAllStocksForSnapshot() } returns emptyList()
        coEvery { repo.getCachedPrices(emptyList()) } returns emptyMap()
        val tool = GetHoldingsTool(repo)
        val context = mockk<ToolContext>(relaxed = true)
        val result = tool.run(context, emptyMap()) as Map<*, *>
        assertThat(result["holdings"]).isNotNull()
    }

    @Test
    fun `UpdateHoldingTool 修改持仓并返回 ok`() = runTest {
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
    fun `UpdateHoldingTool 负数股数报错且不写库`() = runTest {
        val repo = mockk<StockRepository>(relaxed = true)
        val tool = UpdateHoldingTool(repo)
        val context = mockk<ToolContext>(relaxed = true)
        every { context.toolConfirmation } returns ToolConfirmation(confirmed = true)
        val result = tool.run(context, mapOf("code" to "600519", "shares" to -1, "costPerShare" to 1.0)) as Map<*, *>
        assertThat(result["error"]).isNotNull()
        coVerify(exactly = 0) { repo.updateShares(any(), any()) }
    }

    @Test
    fun `RemoveStockTool 删除自选`() = runTest {
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
    fun `AddTransactionTool 记录交易并重算持仓`() = runTest {
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
    fun `AddTransactionTool 非法类型报错`() = runTest {
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
    fun `SetStockTagsTool 覆盖标签`() = runTest {
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
    fun `SetStockTagsTool 接受逗号分隔字符串`() = runTest {
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
    fun `UpdateIndustryTargetTool 权重超范围报错`() = runTest {
        val repo = mockk<StockRepository>(relaxed = true)
        val tool = UpdateIndustryTargetTool(repo)
        val context = mockk<ToolContext>(relaxed = true)
        every { context.toolConfirmation } returns ToolConfirmation(confirmed = true)
        val result = tool.run(context, mapOf("industry" to "银行", "weight" to 150.0)) as Map<*, *>
        assertThat(result["error"]).isNotNull()
        coVerify(exactly = 0) { repo.updateIndustryTarget(any(), any()) }
    }

    @Test
    fun `UpdateNotificationRuleTool boost 小于 min 报错`() = runTest {
        val repo = mockk<NotificationRuleRepository>(relaxed = true)
        val tool = UpdateNotificationRuleTool(repo)
        val context = mockk<ToolContext>(relaxed = true)
        every { context.toolConfirmation } returns ToolConfirmation(confirmed = true)
        val result = tool.run(context, mapOf("minYield" to 5.0, "boostYield" to 3.0)) as Map<*, *>
        assertThat(result["error"]).isNotNull()
        coVerify(exactly = 0) { repo.saveEvalThresholds(any(), any()) }
    }

    @Test
    fun `UpdateLivingExpenseTool 修改支出`() = runTest {
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
    fun `RemoveLivingExpenseTool 删除支出`() = runTest {
        val repo = mockk<LivingExpenseRepository>(relaxed = true)
        val tool = RemoveLivingExpenseTool(repo)
        val context = mockk<ToolContext>(relaxed = true)
        every { context.toolConfirmation } returns ToolConfirmation(confirmed = true)
        val result = tool.run(context, mapOf("id" to 7L)) as Map<*, *>
        assertThat(result["ok"]).isEqualTo(true)
        coVerify { repo.deleteExpense(7L) }
    }

    @Test
    fun `SetFireGoalTool 设置目标`() = runTest {
        val repo = mockk<FireGoalRepository>(relaxed = true)
        val tool = SetFireGoalTool(repo)
        val context = mockk<ToolContext>(relaxed = true)
        every { context.toolConfirmation } returns ToolConfirmation(confirmed = true)
        val result = tool.run(context, mapOf("amount" to 2_000_000.0)) as Map<*, *>
        assertThat(result["ok"]).isEqualTo(true)
        coVerify { repo.saveGoal(2_000_000.0) }
    }

    @Test
    fun `GetStockInfoTool 未找到股票返回错误`() = runTest {
        val stockRepo = mockk<StockRepository>(relaxed = true)
        coEvery { stockRepo.resolveStock("000000") } returns null
        val tool = GetStockInfoTool(stockRepo, mockk(relaxed = true))
        val context = mockk<ToolContext>(relaxed = true)
        val result = tool.run(context, mapOf("code" to "000000")) as Map<*, *>
        assertThat(result["error"]?.toString()).contains("未找到")
    }

    @Test
    fun `SearchStockTool 返回搜索结果`() = runTest {
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
    fun `GetDividendHistoryTool 未找到股票返回错误`() = runTest {
        val stockRepo = mockk<StockRepository>(relaxed = true)
        coEvery { stockRepo.resolveStock("000000") } returns null
        val tool = GetDividendHistoryTool(mockk(relaxed = true), stockRepo)
        val context = mockk<ToolContext>(relaxed = true)
        val result = tool.run(context, mapOf("code" to "000000")) as Map<*, *>
        assertThat(result["error"]?.toString()).contains("未找到")
    }

    @Test
    fun `GetValuationTool 分红数据不足返回错误`() = runTest {
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
    fun `GetBuyThresholdTool 返回买入线字段`() = runTest {
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
    fun `GetStockEvaluationTool 无现价返回错误`() = runTest {
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
    fun `GetPortfolioSummaryTool 空组合返回零值与预测`() = runTest {
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
    fun `GetTransactionsTool code 未找到返回错误`() = runTest {
        val stockRepo = mockk<StockRepository>(relaxed = true)
        coEvery { stockRepo.resolveStock("000000") } returns null
        val tool = GetTransactionsTool(stockRepo, mockk(relaxed = true))
        val context = mockk<ToolContext>(relaxed = true)
        val result = tool.run(context, mapOf("code" to "000000")) as Map<*, *>
        assertThat(result["error"]?.toString()).contains("未找到")
    }

    @Test
    fun `GetNotificationRulesTool 空持仓返回空规则列表`() = runTest {
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
    fun `GetUserStrategiesTool 返回策略列表`() = runTest {
        val repo = mockk<TradeStrategyRepository>(relaxed = true)
        coEvery { repo.activeStrategies() } returns emptyList()
        val tool = GetUserStrategiesTool(repo)
        val context = mockk<ToolContext>(relaxed = true)
        val result = tool.run(context, emptyMap()) as Map<*, *>
        assertThat(result["strategies"] as List<*>).isEmpty()
    }

    @Test
    fun `GetLivingExpensesTool 返回支出列表含 id`() = runTest {
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
