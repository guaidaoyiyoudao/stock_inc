package com.stock.dividend.data.agent.tools

import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import com.stock.dividend.data.local.entity.StockEntity
import com.stock.dividend.data.repository.BondYieldRepository
import com.stock.dividend.data.repository.BuyThresholdStatus
import com.stock.dividend.data.repository.DividendDiscountCalculator
import com.stock.dividend.data.repository.DividendDiscountInput
import com.stock.dividend.data.repository.DividendRepository
import com.stock.dividend.data.repository.ForecastCalculator
import com.stock.dividend.data.repository.HoldingRecommender
import com.stock.dividend.data.repository.KlinePeriod
import com.stock.dividend.data.repository.NotificationRuleRepository
import com.stock.dividend.data.repository.StockRepository
import com.stock.dividend.data.repository.StockSearchResult
import com.stock.dividend.data.repository.computeBuyThreshold
import kotlinx.coroutines.flow.first

private val CODE_SCHEMA = Schema(
    type = Type.OBJECT,
    properties = mapOf(
        "code" to Schema(
            type = Type.STRING,
            description = "股票代码或名称：6 位数字（如 600519）、带前缀（sh.600519 / sz.000001）或名称（如 贵州茅台）；拿不准先调用 search_stock"
        )
    ),
    required = listOf("code")
)

private fun StockSearchResult.toEntity(): StockEntity =
    StockEntity(code = code, name = name, marketCode = marketCode)

/** 现价：先网络刷新，失败回退缓存。 */
private suspend fun StockRepository.refreshPrice(entity: StockEntity): Double? =
    runCatching { fetchQuotes(listOf(entity))[entity.code] }.getOrNull()
        ?: runCatching { getCachedPrices(listOf(entity.code))[entity.code] }.getOrNull()

class GetStockInfoTool(
    private val stockRepository: StockRepository,
    private val dividendRepository: DividendRepository,
) : ReadTool(
    name = "get_stock_info",
    description = "查询单只股票的实时行情与基本信息：现价、行业、最近股息率、除权日。code 参数格式见参数说明。",
    parameters = CODE_SCHEMA,
) {
    override suspend fun run(context: ToolContext, args: Map<String, Any>): Any {
        val code = args.stringArg("code") ?: return mapOf("error" to "缺少 code 参数")
        return runCatching {
            val stock = stockRepository.resolveStock(code)
                ?: return@runCatching mapOf("error" to "未找到股票：$code")
            val entity = stock.toEntity()
            val price = stockRepository.refreshPrice(entity)
            val saved = stockRepository.observeStock(stock.code).first()
            val latest = dividendRepository.getLatestDividend(stock.code)
            buildMap<String, Any?> {
                put("code", stock.code)
                put("name", stock.name)
                put("marketCode", stock.marketCode)
                put("currentPrice", price)
                saved?.industry?.takeIf { it.isNotBlank() }?.let { put("industry", it) }
                latest?.let {
                    it.dividendYield?.let { v -> put("dividendYield", v) }
                    it.exDividendDate?.let { v -> put("exDividendDate", v) }
                }
            }
        }.getOrElse { e -> mapOf("error" to (e.message ?: "查询失败")) }
    }
}

class SearchStockTool(
    private val stockRepository: StockRepository,
) : ReadTool(
    name = "search_stock",
    description = "按名称或代码关键字搜索 A 股股票，返回代码、名称与现价。推荐在拿不准股票代码时先调用本工具。",
    parameters = Schema(
        type = Type.OBJECT,
        properties = mapOf(
            "query" to Schema(type = Type.STRING, description = "股票名称或代码关键字，如「茅台」或「600519」")
        ),
        required = listOf("query")
    ),
) {
    override suspend fun run(context: ToolContext, args: Map<String, Any>): Any {
        val query = args.stringArg("query") ?: return mapOf("error" to "缺少 query 参数")
        return runCatching {
            val results = stockRepository.searchStocks(query).getOrNull().orEmpty()
            mapOf(
                "results" to results.map {
                    buildMap<String, Any?> {
                        put("code", it.code)
                        put("name", it.name)
                        put("marketCode", it.marketCode)
                        it.currentPrice?.let { v -> put("currentPrice", v) }
                    }
                }
            )
        }.getOrElse { e -> mapOf("error" to (e.message ?: "搜索失败")) }
    }
}

class GetDividendHistoryTool(
    private val dividendRepository: DividendRepository,
    private val stockRepository: StockRepository,
) : ReadTool(
    name = "get_dividend_history",
    description = "查询单只股票的历史分红记录（报告期、每股分红、股息率、除权日）。code 参数格式见参数说明。",
    parameters = CODE_SCHEMA,
) {
    override suspend fun run(context: ToolContext, args: Map<String, Any>): Any {
        val code = args.stringArg("code") ?: return mapOf("error" to "缺少 code 参数")
        return runCatching {
            val stock = stockRepository.resolveStock(code)
                ?: return@runCatching mapOf("error" to "未找到股票：$code")
            val dividends = dividendRepository.observeDividends(stock.code).first()
            mapOf(
                "dividends" to dividends.map {
                    buildMap<String, Any?> {
                        put("reportDate", it.reportDate)
                        put("cashPerShare", it.cashPerShare)
                        it.dividendYield?.let { v -> put("dividendYield", v) }
                        it.exDividendDate?.let { v -> put("exDividendDate", v) }
                    }
                }
            )
        }.getOrElse { e -> mapOf("error" to (e.message ?: "查询失败")) }
    }
}

class GetDividendForecastTool(
    private val dividendRepository: DividendRepository,
    private val stockRepository: StockRepository,
) : ReadTool(
    name = "get_dividend_forecast",
    description = "查询单只股票的股息预测：近 3 年平均每股分红、按当前持仓的年化预测收入（元）、下次除权日。",
    parameters = CODE_SCHEMA,
) {
    override suspend fun run(context: ToolContext, args: Map<String, Any>): Any {
        val code = args.stringArg("code") ?: return mapOf("error" to "缺少 code 参数")
        return runCatching {
            val stock = stockRepository.resolveStock(code)
                ?: return@runCatching mapOf("error" to "未找到股票：$code")
            val dividends = dividendRepository.observeDividends(stock.code).first()
            val avg = ForecastCalculator.calculateAvgCashPerShare(dividends, years = 3)
            val saved = stockRepository.observeStock(stock.code).first()
            val shares = saved?.shares ?: 0
            val latest = dividendRepository.getLatestDividend(stock.code)
            buildMap<String, Any?> {
                put("code", stock.code)
                put("name", stock.name)
                avg?.let {
                    put("avgCashPerShare3y", it.avgCashPerShare)
                    put("forecastAnnualIncome", it.avgCashPerShare * shares)
                }
                put("shares", shares)
                latest?.exDividendDate?.let { put("nextExDividendDate", it) }
            }
        }.getOrElse { e -> mapOf("error" to (e.message ?: "查询失败")) }
    }
}

class GetValuationTool(
    private val stockRepository: StockRepository,
    private val dividendRepository: DividendRepository,
) : ReadTool(
    name = "get_valuation",
    description = "用股息贴现模型（DDM）估算单只股票的内在价值（元/股）、安全买入价（元/股）与当前折溢价。",
    parameters = CODE_SCHEMA,
) {
    override suspend fun run(context: ToolContext, args: Map<String, Any>): Any {
        val code = args.stringArg("code") ?: return mapOf("error" to "缺少 code 参数")
        return runCatching {
            val stock = stockRepository.resolveStock(code)
                ?: return@runCatching mapOf("error" to "未找到股票：$code")
            val entity = stock.toEntity()
            val price = stockRepository.refreshPrice(entity)
            val dividends = dividendRepository.observeDividends(stock.code).first()
            val basis = DividendDiscountCalculator.deriveDividendBasis(dividends)
            if (basis == null || basis.averageCashPerShare <= 0.0) {
                return@runCatching mapOf("error" to "分红数据不足，无法估值")
            }
            val result = DividendDiscountCalculator.calculate(
                DividendDiscountInput(
                    dividendBasisPerShare = basis.averageCashPerShare,
                    dividendGrowthRate = 5.0,
                    discountRate = 9.0,
                    terminalGrowthRate = 2.0,
                    projectionYears = 10,
                    marginOfSafety = 0.8,
                    currentPrice = price
                )
            )
            mapOf(
                "code" to stock.code,
                "name" to stock.name,
                "intrinsicValuePerShare" to result.intrinsicValuePerShare,
                "safetyBuyPrice" to result.safetyBuyPrice,
                "currentPrice" to result.currentPrice,
                "discountOrPremiumPercent" to result.discountOrPremiumPercent,
                "valuationStatus" to result.valuationStatus.name
            )
        }.getOrElse { e -> mapOf("error" to (e.message ?: "估值失败")) }
    }
}

class GetBuyThresholdTool(
    private val stockRepository: StockRepository,
    private val dividendRepository: DividendRepository,
    private val bondYieldRepository: BondYieldRepository,
) : ReadTool(
    name = "get_buy_threshold",
    description = "查询单只股票的买入线：10 年国债收益率（%）× 专属倍数得到目标股息率（%），并判断现价股息率是否达标。",
    parameters = CODE_SCHEMA,
) {
    override suspend fun run(context: ToolContext, args: Map<String, Any>): Any {
        val code = args.stringArg("code") ?: return mapOf("error" to "缺少 code 参数")
        return runCatching {
            val stock = stockRepository.resolveStock(code)
                ?: return@runCatching mapOf("error" to "未找到股票：$code")
            val entity = stock.toEntity()
            val price = stockRepository.refreshPrice(entity)
            val saved = stockRepository.observeStock(stock.code).first()
            val dividends = dividendRepository.observeDividends(stock.code).first()
            val bond = bondYieldRepository.fetch10YBondYield()
            val status: BuyThresholdStatus = computeBuyThreshold(
                bondYield10Y = bond,
                multiplier = saved?.buyThresholdMultiplier ?: StockEntity.DEFAULT_BUY_THRESHOLD_MULTIPLIER,
                latestYearlyCashPerShare = ForecastCalculator.latestYearlyCashPerShare(dividends),
                currentPrice = price
            )
            mapOf(
                "code" to stock.code,
                "name" to stock.name,
                "bondYield10Y" to status.bondYield10Y,
                "multiplier" to status.multiplier,
                "targetYieldPercent" to status.targetYieldPercent,
                "currentYieldPercent" to status.currentYieldPercent,
                "reached" to status.reached
            )
        }.getOrElse { e -> mapOf("error" to (e.message ?: "查询失败")) }
    }
}

class GetStockEvaluationTool(
    private val stockRepository: StockRepository,
    private val dividendRepository: DividendRepository,
    private val notificationRuleRepository: NotificationRuleRepository,
) : ReadTool(
    name = "get_stock_evaluation",
    description = "单股一键评估：BOLL 日/周/月三周期位置 + 股息率门槛，输出 BUY/HOLD/SELL 结论与理由（结论由程序计算，禁止自行推算）。",
    parameters = CODE_SCHEMA,
) {
    override suspend fun run(context: ToolContext, args: Map<String, Any>): Any {
        val code = args.stringArg("code") ?: return mapOf("error" to "缺少 code 参数")
        return runCatching {
            val stock = stockRepository.resolveStock(code)
                ?: return@runCatching mapOf("error" to "未找到股票：$code")
            val entity = stock.toEntity()
            val price = stockRepository.refreshPrice(entity)
            if (price == null || price <= 0.0) return@runCatching mapOf("error" to "无有效现价")
            val weekly = stockRepository.fetchBoll(stock.code, KlinePeriod.WEEKLY)
            val daily = stockRepository.fetchBoll(stock.code, KlinePeriod.DAILY)
            val monthly = stockRepository.fetchBoll(stock.code, KlinePeriod.MONTHLY)
            val dividends = dividendRepository.observeDividends(stock.code).first()
            val thresholds = notificationRuleRepository.observeEvalThresholds().first()
            val rec = HoldingRecommender.recommend(
                price = price,
                band = weekly,
                latestYearlyDividend = ForecastCalculator.latestYearlyCashPerShare(dividends),
                thresholds = thresholds,
                dailyBand = daily,
                monthlyBand = monthly
            )
            mapOf(
                "code" to stock.code,
                "name" to stock.name,
                "action" to rec.action.name,
                "bollTone" to rec.bollTone.name,
                "priceVsLower" to rec.priceVsLower,
                "dividendYield" to rec.dividendYield,
                "reasons" to rec.reasons
            )
        }.getOrElse { e -> mapOf("error" to (e.message ?: "评估失败")) }
    }
}
