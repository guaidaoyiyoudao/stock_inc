package com.stock.dividend.data.agent.tools

import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import com.stock.dividend.data.local.entity.StockEntity
import com.stock.dividend.data.repository.BondYieldRepository
import com.stock.dividend.data.repository.BuyThresholdStatus
import com.stock.dividend.data.repository.BollBand
import com.stock.dividend.data.repository.DividendRepository
import com.stock.dividend.data.repository.ForecastCalculator
import com.stock.dividend.data.repository.FundamentalsCacheRepository
import com.stock.dividend.data.repository.HoldingRecommender
import com.stock.dividend.data.repository.KlinePeriod
import com.stock.dividend.data.repository.KlineRepository
import com.stock.dividend.data.repository.NotificationRuleRepository
import com.stock.dividend.data.repository.StockRepository
import com.stock.dividend.data.repository.BollCalculator
import com.stock.dividend.data.repository.computeBuyThreshold
import com.stock.dividend.data.repository.enrichPayoutRatio
import kotlinx.coroutines.flow.first

private val CODE_SCHEMA = Schema(
    type = Type.OBJECT,
    properties = mapOf(
        "code" to Schema(
            type = Type.STRING,
            description = "股票代码或名称：推荐 6 位数字代码（如 600519）或股票名称（如 贵州茅台）；带前缀代码（sh.600519 / sz.000001）会自动归一化，同样可用；拿不准先调用 search_stock"
        )
    ),
    required = listOf("code")
)

class GetStockInfoTool(
    private val stockRepository: StockRepository,
    private val dividendRepository: DividendRepository,
) : ReadTool(
    name = "get_stock_info",
    description = "查询单只股票的实时行情与基本信息：现价、行业、股息率（按现价与最近年度分红实时计算，与 get_stock_evaluation/get_buy_threshold 同口径）、最近除权日。code 参数格式见参数说明。",
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
            // 股息率按现价实时计算（dividends 表的 dividendYield 仅为除权时点历史快照，
            // 腾讯主源恒为 null，不能直接透传，否则与评估类工具口径不一致）
            val dividends = dividendRepository.observeDividends(stock.code).first()
            val yearlyCash = ForecastCalculator.latestYearlyCashPerShare(dividends)
            buildMap<String, Any?> {
                put("code", stock.code)
                put("name", stock.name)
                put("marketCode", stock.marketCode)
                put("currentPrice", price)
                if (price != null && price > 0.0 && yearlyCash != null && yearlyCash > 0.0) {
                    put("dividendYield", yearlyCash / price * 100.0)
                }
                saved?.industry?.takeIf { it.isNotBlank() }?.let { put("industry", it) }
                saved?.lastUpdated?.let { put("lastUpdated", it) }
                latest?.exDividendDate?.let { v -> put("exDividendDate", v) }
            }
        }.getOrElse { e -> mapOf("error" to (e.message ?: "查询失败")) }
    }
}

class GetFundamentalsTool(
    private val stockRepository: StockRepository,
    private val dividendRepository: DividendRepository,
    private val fundamentalsCacheRepository: FundamentalsCacheRepository,
) : ReadTool(
    name = "get_stock_fundamentals",
    description = "查询单只股票近 5 期基本面：报告期、ROE、资产负债率、营收/净利同比、基本每股收益、派息率、公告股息率与分红方案。forceRefresh=true 可绕过 7 天缓存强制刷新。",
    parameters = Schema(
        type = Type.OBJECT,
        properties = mapOf(
            "code" to Schema(
                type = Type.STRING,
                description = "股票代码或名称：推荐 6 位数字代码（如 600519）或股票名称；带前缀代码会自动归一化"
            ),
            "forceRefresh" to Schema(
                type = Type.BOOLEAN,
                description = "可选：是否强制刷新（默认 false，优先读 7 天缓存）"
            )
        ),
        required = listOf("code")
    ),
) {
    override suspend fun run(context: ToolContext, args: Map<String, Any>): Any {
        val code = args.stringArg("code") ?: return mapOf("error" to "缺少 code 参数")
        return runCatching {
            val stock = stockRepository.resolveStock(code)
                ?: return@runCatching mapOf("error" to "未找到股票：$code")
            val forceRefresh = args.boolArg("forceRefresh") ?: false
            val raw = fundamentalsCacheRepository.getFundamentals(stock.code, forceRefresh = forceRefresh)
                ?: return@runCatching mapOf("error" to "基本面数据不足，无法查询")
            val epsDivByDate = dividendRepository.observeDividends(stock.code).first()
                .filter { it.reportDate.isNotBlank() && it.cashPerShare > 0.0 }
                .associate { it.reportDate to it.cashPerShare }
            val enriched = enrichPayoutRatio(raw, epsDivByDate)
            mapOf(
                "code" to stock.code,
                "name" to stock.name,
                "periods" to enriched.periods.map { p ->
                    buildMap<String, Any?> {
                        put("reportDate", p.reportDate)
                        p.roe?.let { put("roe", it) }
                        p.debtToAssetRatio?.let { put("debtToAssetRatio", it) }
                        p.revenueYoy?.let { put("revenueYoy", it) }
                        p.netProfitYoy?.let { put("netProfitYoy", it) }
                        p.basicEps?.let { put("basicEps", it) }
                        p.payoutRatio?.let { put("payoutRatio", it) }
                        p.announceYield?.let { put("announceYield", it) }
                        p.dividendPlan?.let { put("dividendPlan", it) }
                    }
                }
            )
        }.getOrElse { e -> mapOf("error" to (e.message ?: "查询失败")) }
    }
}

class GetKlineTool(
    private val stockRepository: StockRepository,
    private val klineRepository: KlineRepository,
) : ReadTool(
    name = "get_kline",
    description = "查询单只股票的前复权 OHLCV K 线序列（旧→新：日期/开/收/高/低/量）与 BOLL 上/中/下轨（收盘价不足 20 根时无 BOLL）。注意：价格均为前复权口径，latestClose 是最近一根收盘价（盘中≈昨收，除权后会整体重算），与 get_stock_info 的实时现价可能不同，属正常口径差异。period 为 DAILY/WEEKLY/MONTHLY，默认 WEEKLY；bars 默认 40，范围 10-120。",
    parameters = Schema(
        type = Type.OBJECT,
        properties = mapOf(
            "code" to Schema(
                type = Type.STRING,
                description = "股票代码或名称：推荐 6 位数字代码（如 600519）或股票名称；带前缀代码会自动归一化"
            ),
            "period" to Schema(
                type = Type.STRING,
                description = "可选：K 线周期，DAILY/WEEKLY/MONTHLY，默认 WEEKLY"
            ),
            "bars" to Schema(
                type = Type.INTEGER,
                description = "可选：返回收盘价根数（10-120），默认 40"
            )
        ),
        required = listOf("code")
    ),
) {
    override suspend fun run(context: ToolContext, args: Map<String, Any>): Any {
        val code = args.stringArg("code") ?: return mapOf("error" to "缺少 code 参数")
        val period = args.stringArg("period")?.uppercase() ?: KlinePeriod.WEEKLY.name
        if (period !in KlinePeriod.entries.map { it.name }) {
            return mapOf("error" to "period 只能是 DAILY/WEEKLY/MONTHLY")
        }
        val bars = args.intArg("bars") ?: 40
        if (bars !in MIN_BARS..MAX_BARS) {
            return mapOf("error" to "bars 必须在 10-120 之间")
        }
        return runCatching {
            val stock = stockRepository.resolveStock(code)
                ?: return@runCatching mapOf("error" to "未找到股票：$code")
            val klines = klineRepository.fetchKlines(stock.code, KlinePeriod.valueOf(period), bars)
            val closes = klines.map { it.close }
            val band = BollCalculator.calculate(closes)
            buildMap<String, Any?> {
                put("code", stock.code)
                put("name", stock.name)
                put("period", period)
                put("closes", closes)
                put(
                    "bars",
                    klines.map {
                        mapOf(
                            "date" to it.date,
                            "open" to it.open,
                            "close" to it.close,
                            "high" to it.high,
                            "low" to it.low,
                            "volume" to it.volume
                        )
                    }
                )
                closes.lastOrNull()?.let { put("latestClose", it) }
                band?.let {
                    put("bollUpper", it.upper)
                    put("bollMiddle", it.middle)
                    put("bollLower", it.lower)
                    put("bollPeriod", it.period)
                }
                if (band == null) put("bollNote", "收盘价不足 ${BollBand.DEFAULT_PERIOD} 根，无法计算 BOLL")
            }
        }.getOrElse { e -> mapOf("error" to (e.message ?: "查询失败")) }
    }

    companion object {
        const val MIN_BARS = 10
        const val MAX_BARS = 120
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
    description = "查询单只股票的历史分红记录（报告期、每股分红、股息率、除权日）。注意：记录里的 dividendYield 是除权时点的历史快照值，个别记录可能缺失；当前股息率（按现价实时计算）请用 get_stock_info / get_stock_evaluation。code 参数格式见参数说明。",
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
