package com.stock.dividend.data.agent.tools

import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import com.stock.dividend.data.local.entity.EXPENSE_PERIOD_MONTHLY
import com.stock.dividend.data.local.entity.EXPENSE_PERIOD_YEARLY
import com.stock.dividend.data.repository.BollBand
import com.stock.dividend.data.repository.DividendIncomeRepository
import com.stock.dividend.data.repository.DividendRepository
import com.stock.dividend.data.repository.EvaluatedStock
import com.stock.dividend.data.repository.ForecastCalculator
import com.stock.dividend.data.repository.FireGoalRepository
import com.stock.dividend.data.repository.HoldingRecommender
import com.stock.dividend.data.repository.KlinePeriod
import com.stock.dividend.data.repository.LivingExpenseRepository
import com.stock.dividend.data.repository.NotificationRuleRepository
import com.stock.dividend.data.repository.PortfolioAdvisor
import com.stock.dividend.data.repository.StockRepository
import com.stock.dividend.data.repository.TradeStrategyRepository
import com.stock.dividend.data.repository.TransactionRepository
import com.stock.dividend.data.repository.risksFromJson
import com.stock.dividend.viewmodel.CoverageExpenseInput
import com.stock.dividend.viewmodel.ExpenseCoverageCalculator
import com.stock.dividend.viewmodel.ExpensePeriod
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** [GetPortfolioSignalsTool] 内部行：评估结果 + 日/月 BOLL。 */
private data class PortfolioEvalRow(
    val evaluated: EvaluatedStock,
    val daily: BollBand?,
    val monthly: BollBand?
)

class GetHoldingsTool(
    private val stockRepository: StockRepository,
) : ReadTool(
    name = "get_holdings",
    description = "返回全部自选/持仓列表（含观察仓 shares=0）：代码、名称、股数、成本、现价（批量实时刷新）、市值、盈亏、行业、标签、最后更新时间。无需参数。",
) {
    override suspend fun run(context: ToolContext, args: Map<String, Any>): Any = runCatching {
        val stocks = stockRepository.observeAllStocksForSnapshot()
        val prices = stockRepository.fetchFreshPrices(stocks)
        val tagsByCode = runCatching { stockRepository.observeAllStockTags().first() }
            .getOrDefault(emptyList())
            .groupBy({ it.stockCode }, { it.tag })
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
                    put("tags", tagsByCode[s.code].orEmpty())
                    s.lastUpdated?.let { put("lastUpdated", it) }
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
    description = "组合概况：总市值（元）、总成本、总盈亏、年化股息预测（元）、FIRE 目标进度与支出覆盖率（现价批量实时刷新，与 get_holdings 同口径）。无需参数。",
) {
    override suspend fun run(context: ToolContext, args: Map<String, Any>): Any = runCatching {
        val stocks = stockRepository.observeAllStocksForSnapshot()
        val prices = stockRepository.fetchFreshPrices(stocks)
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
    description = "持仓按行业的市值占比与目标配比对比（百分比，现价批量实时刷新，与 get_holdings 同口径）。无需参数。",
) {
    override suspend fun run(context: ToolContext, args: Map<String, Any>): Any = runCatching {
        val stocks = stockRepository.observeAllStocksForSnapshot()
        val prices = stockRepository.fetchFreshPrices(stocks)
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
                description = "可选：股票代码或名称（推荐 6 位数字代码，带前缀代码会自动归一化）；不传返回全部交易"
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

/**
 * 组合层策略信号：仓位控制 + 三周期共振买点。
 * 与 App「一键评估」同口径：持仓（shares>0）逐只拉日/周/月 BOLL（Semaphore(3) 限流），
 * 复用 [HoldingRecommender] 与 [PortfolioAdvisor]，结论一律由程序计算。
 */
class GetPortfolioSignalsTool(
    private val stockRepository: StockRepository,
    private val dividendRepository: DividendRepository,
    private val notificationRuleRepository: NotificationRuleRepository,
) : ReadTool(
    name = "get_portfolio_signals",
    description = "组合层策略信号（基于全部持仓，shares>0）：仓位控制（上轨占比、平均股息率、建议现金比例是否触发）+ 三周期共振买点列表（日下轨+周下轨+月中轨及以下）。无需参数。",
) {
    override suspend fun run(context: ToolContext, args: Map<String, Any>): Any = runCatching {
        val stocks = stockRepository.observeAllStocksForSnapshot().filter { it.shares > 0 }
        val thresholds = notificationRuleRepository.observeEvalThresholds().first()
        val semaphore = Semaphore(3)  // 与 App 评估一致，防 Tencent 限流
        coroutineScope {
            val rows = stocks.map { stock ->
                async {
                    semaphore.withPermit {
                        val price = stockRepository.refreshPrice(stock)
                        val weekly = stockRepository.fetchBoll(stock.code, KlinePeriod.WEEKLY)
                        val daily = stockRepository.fetchBoll(stock.code, KlinePeriod.DAILY)
                        val monthly = stockRepository.fetchBoll(stock.code, KlinePeriod.MONTHLY)
                        val dividends = dividendRepository.observeDividends(stock.code).first()
                        val rec = HoldingRecommender.recommend(
                            price = price ?: 0.0,
                            band = weekly,
                            latestYearlyDividend = ForecastCalculator.latestYearlyCashPerShare(dividends),
                            thresholds = thresholds,
                            dailyBand = daily,
                            monthlyBand = monthly
                        )
                        val evaluated = EvaluatedStock(
                            code = stock.code,
                            name = stock.name,
                            industry = stock.industry,
                            action = rec.action,
                            priceVsLower = rec.priceVsLower,
                            dividendYield = rec.dividendYield,
                            bollBand = weekly,
                            currentPrice = price?.takeIf { it > 0.0 },
                            reasons = rec.reasons
                        )
                        PortfolioEvalRow(evaluated, daily, monthly)
                    }
                }
            }.awaitAll()

            val evaluated = rows.map { it.evaluated }
            val dailyBands = rows.associate { it.evaluated.code to it.daily }
            val monthlyBands = rows.associate { it.evaluated.code to it.monthly }
            val signals = PortfolioAdvisor.evaluate(evaluated, dailyBands, monthlyBands)
            mapOf(
                "positionControl" to mapOf(
                    "triggered" to signals.positionControl.triggered,
                    "upperBandRatio" to signals.positionControl.upperBandRatio,
                    "avgDividendYield" to signals.positionControl.avgDividendYield,
                    "targetCashPercent" to signals.positionControl.targetCashPercent
                ),
                "buySignals" to signals.buySignals.map {
                    mapOf(
                        "code" to it.code,
                        "dailyAtLower" to it.dailyAtLower,
                        "weeklyAtLower" to it.weeklyAtLower,
                        "monthlyBelowMiddle" to it.monthlyBelowMiddle
                    )
                }
            )
        }
    }.getOrElse { e -> mapOf("error" to (e.message ?: "查询失败")) }
}

class GetDividendIncomeTool(
    private val dividendIncomeRepository: DividendIncomeRepository,
    private val stockRepository: StockRepository,
) : ReadTool(
    name = "get_dividend_income",
    description = "查询实际股息到账记录：不传 year 返回可用年份、各年合计、单股年度收入、记录数与最大单笔；传 year 返回该年全部记录明细（含来源与备注）。",
    parameters = Schema(
        type = Type.OBJECT,
        properties = mapOf(
            "year" to Schema(
                type = Type.INTEGER,
                description = "可选：年份（如 2026），传了则返回该年记录明细"
            )
        )
    ),
) {
    override suspend fun run(context: ToolContext, args: Map<String, Any>): Any = runCatching {
        val year = args.intArg("year")
        val names = runCatching { stockRepository.observeAllStocksForSnapshot() }
            .getOrDefault(emptyList())
            .associate { it.code to it.name }
        if (year != null) {
            val records = dividendIncomeRepository.observeByYear(year).first()
            val total = dividendIncomeRepository.observeTotalByYear(year).first()
            mapOf(
                "year" to year,
                "total" to total,
                "records" to records.map {
                    buildMap<String, Any?> {
                        put("id", it.id)
                        it.stockCode?.let { code ->
                            put("stockCode", code)
                            put("stockName", names[code])
                        }
                        put("date", it.date)
                        put("amount", it.amount)
                        put("source", it.source)
                        it.exDividendDate?.let { v -> put("exDividendDate", v) }
                        it.note?.let { v -> put("note", v) }
                    }
                }
            )
        } else {
            mapOf(
                "years" to dividendIncomeRepository.observeAvailableYears().first(),
                "yearlyTotals" to dividendIncomeRepository.observeYearlyTotals().first().map {
                    mapOf("year" to it.year, "total" to it.total)
                },
                "perStockIncome" to dividendIncomeRepository.observePerStockYearlyIncome().first().map {
                    buildMap<String, Any?> {
                        put("stockCode", it.stockCode)
                        put("stockName", names[it.stockCode])
                        put("year", it.year)
                        put("total", it.total)
                    }
                },
                "recordCount" to dividendIncomeRepository.observeRecordCount().first(),
                "maxSingleIncome" to dividendIncomeRepository.observeMaxSingleIncome().first()
            )
        }
    }.getOrElse { e -> mapOf("error" to (e.message ?: "查询失败")) }
}
