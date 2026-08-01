package com.stock.dividend.data.agent.tools

import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import com.stock.dividend.data.local.entity.EXPENSE_PERIOD_MONTHLY
import com.stock.dividend.data.local.entity.EXPENSE_PERIOD_YEARLY
import com.stock.dividend.data.repository.DividendIncomeRepository
import com.stock.dividend.data.repository.FireGoalRepository
import com.stock.dividend.data.repository.LivingExpenseRepository
import com.stock.dividend.data.repository.NotificationRuleRepository
import com.stock.dividend.data.repository.StockRepository
import com.stock.dividend.data.repository.TradeStrategyRepository
import com.stock.dividend.data.repository.TransactionRepository
import com.stock.dividend.data.repository.risksFromJson
import com.stock.dividend.viewmodel.CoverageExpenseInput
import com.stock.dividend.viewmodel.ExpenseCoverageCalculator
import com.stock.dividend.viewmodel.ExpensePeriod
import kotlinx.coroutines.flow.first

class GetHoldingsTool(
    private val stockRepository: StockRepository,
) : ReadTool(
    name = "get_holdings",
    description = "返回全部自选/持仓列表（含观察仓 shares=0）：代码、名称、股数、成本、现价、市值、盈亏、行业。无需参数。",
) {
    override suspend fun run(context: ToolContext, args: Map<String, Any>): Any = runCatching {
        val stocks = stockRepository.observeAllStocksForSnapshot()
        val prices = stockRepository.getCachedPrices(stocks.map { it.code })
        mapOf(
            "holdings" to stocks.map { s ->
                buildMap<String, Any?> {
                    put("code", s.code)
                    put("name", s.name)
                    put("shares", s.shares)
                    put("costPerShare", s.costPerShare)
                    val price = prices[s.code]
                    put("currentPrice", price)
                    if (price != null && s.shares > 0) {
                        put("marketValue", price * s.shares)
                        put("profit", price * s.shares - s.costPerShare * s.shares)
                        put(
                            "profitPct",
                            if (s.costPerShare > 0) (price - s.costPerShare) / s.costPerShare * 100.0 else null
                        )
                    }
                    s.industry.takeIf { it.isNotBlank() }?.let { put("industry", it) }
                }
            }
        )
    }.getOrElse { e -> mapOf("error" to (e.message ?: "查询失败")) }
}

class GetPortfolioSummaryTool(
    private val stockRepository: StockRepository,
    private val dividendIncomeRepository: DividendIncomeRepository,
    private val fireGoalRepository: FireGoalRepository,
    private val livingExpenseRepository: LivingExpenseRepository,
) : ReadTool(
    name = "get_portfolio_summary",
    description = "组合概况：总市值（元）、总成本、总盈亏、年化股息预测（元）、FIRE 目标进度与支出覆盖率。无需参数。",
) {
    override suspend fun run(context: ToolContext, args: Map<String, Any>): Any = runCatching {
        val stocks = stockRepository.observeAllStocksForSnapshot()
        val prices = stockRepository.getCachedPrices(stocks.map { it.code })
        val totalMarketValue = stocks.sumOf { (prices[it.code] ?: 0.0) * it.shares }
        val totalCost = stocks.sumOf { it.costPerShare * it.shares }
        val annualForecast = dividendIncomeRepository.observeForecastTotal().first()
        val goal = fireGoalRepository.getGoalOnce()
        val expenses = livingExpenseRepository.observeExpenses().first()
        val coverage = ExpenseCoverageCalculator.calculate(
            forecastAnnualDividendIncome = annualForecast,
            items = expenses.map {
                CoverageExpenseInput(
                    id = it.id,
                    name = it.name,
                    amount = it.amount,
                    period = if (it.period == EXPENSE_PERIOD_MONTHLY) ExpensePeriod.MONTHLY else ExpensePeriod.YEARLY,
                    sortOrder = it.sortOrder
                )
            }
        )
        buildMap<String, Any?> {
            put("totalMarketValue", totalMarketValue)
            put("totalCost", totalCost)
            put("totalProfit", totalMarketValue - totalCost)
            put("totalProfitPct", if (totalCost > 0) (totalMarketValue - totalCost) / totalCost * 100.0 else null)
            put("annualDividendForecast", annualForecast)
            goal?.let {
                put("fireGoalAmount", it.targetAmount)
                put("fireProgressPercent", if (it.targetAmount > 0) totalMarketValue / it.targetAmount * 100.0 else null)
            }
            if (expenses.isNotEmpty()) {
                put("totalAnnualExpense", coverage.totalAnnualExpense)
                put("expenseCoverageRatio", coverage.coverageRatio)
            }
        }
    }.getOrElse { e -> mapOf("error" to (e.message ?: "查询失败")) }
}

class GetIndustryAllocationTool(
    private val stockRepository: StockRepository,
) : ReadTool(
    name = "get_industry_allocation",
    description = "持仓按行业的市值占比与目标配比对比（百分比）。无需参数。",
) {
    override suspend fun run(context: ToolContext, args: Map<String, Any>): Any = runCatching {
        val stocks = stockRepository.observeAllStocksForSnapshot()
        val prices = stockRepository.getCachedPrices(stocks.map { it.code })
        val targets = stockRepository.getIndustryTargets().associate { it.industry to it.targetWeight }
        val byIndustry = stocks.groupBy { it.industry.takeIf { i -> i.isNotBlank() } ?: "未分类" }
        val total = byIndustry.values.sumOf { list ->
            list.sumOf { (prices[it.code] ?: 0.0) * it.shares }
        }
        mapOf(
            "allocations" to byIndustry.map { (industry, list) ->
                val value = list.sumOf { (prices[it.code] ?: 0.0) * it.shares }
                buildMap<String, Any?> {
                    put("industry", industry)
                    put("marketValue", value)
                    put("weightPercent", if (total > 0) value / total * 100.0 else null)
                    put("targetWeight", targets[industry])
                }
            }
        )
    }.getOrElse { e -> mapOf("error" to (e.message ?: "查询失败")) }
}

class GetTransactionsTool(
    private val stockRepository: StockRepository,
    private val transactionRepository: TransactionRepository,
) : ReadTool(
    name = "get_transactions",
    description = "查询交易记录（买入/卖出、股数、价格、日期）。不传 code 返回全部，传 code 只返回该股。",
    parameters = Schema(
        type = Type.OBJECT,
        properties = mapOf(
            "code" to Schema(
                type = Type.STRING,
                description = "可选：股票代码或名称（6 位数字、sh./sz. 前缀或名称）；不传返回全部交易"
            )
        )
    ),
) {
    override suspend fun run(context: ToolContext, args: Map<String, Any>): Any = runCatching {
        val code = args.stringArg("code")
        val resolvedCode = code?.let {
            stockRepository.resolveStock(it)?.code ?: return@runCatching mapOf("error" to "未找到股票：$it")
        }
        val list = if (resolvedCode != null) {
            transactionRepository.getByStock(resolvedCode)
        } else {
            transactionRepository.getAll()
        }
        mapOf(
            "transactions" to list.map {
                mapOf(
                    "stockCode" to it.stockCode,
                    "type" to it.type,
                    "shares" to it.shares,
                    "price" to it.price,
                    "date" to it.date
                )
            }
        )
    }.getOrElse { e -> mapOf("error" to (e.message ?: "查询失败")) }
}

class GetNotificationRulesTool(
    private val stockRepository: StockRepository,
    private val notificationRuleRepository: NotificationRuleRepository,
) : ReadTool(
    name = "get_notification_rules",
    description = "查看当前全局与个股的股息率/价格提醒阈值（百分比）。无需参数。",
) {
    override suspend fun run(context: ToolContext, args: Map<String, Any>): Any = runCatching {
        val stocks = stockRepository.observeAllStocksForSnapshot()
        val rules = notificationRuleRepository.getEnabledStockRules(stocks.map { it.code })
        val global = notificationRuleRepository.getGlobalDividendYieldRule()
        buildMap<String, Any?> {
            global?.let { put("globalDividendYieldThreshold", it.thresholdPercent) }
            put(
                "stocks",
                stocks.map { s ->
                    buildMap<String, Any?> {
                        put("code", s.code)
                        put("name", s.name)
                        put(
                            "rules",
                            rules[s.code].orEmpty().map {
                                mapOf("type" to it.type, "thresholdPercent" to it.thresholdPercent, "enabled" to it.enabled)
                            }
                        )
                    }
                }
            )
        }
    }.getOrElse { e -> mapOf("error" to (e.message ?: "查询失败")) }
}

class GetUserStrategiesTool(
    private val tradeStrategyRepository: TradeStrategyRepository,
) : ReadTool(
    name = "get_user_strategies",
    description = "读取全局策略库（用户投资原则），回答投资问题时引用其中的规则。无需参数。",
) {
    override suspend fun run(context: ToolContext, args: Map<String, Any>): Any = runCatching {
        val strategies = tradeStrategyRepository.activeStrategies()
        mapOf(
            "strategies" to strategies.map {
                buildMap<String, Any?> {
                    put("id", it.id)
                    put("targetText", it.targetText)
                    put("direction", it.direction)
                    put("reasoning", it.reasoning)
                    put("risks", risksFromJson(it.risks))
                    put("sourceNote", it.sourceNote)
                }
            }
        )
    }.getOrElse { e -> mapOf("error" to (e.message ?: "查询失败")) }
}
