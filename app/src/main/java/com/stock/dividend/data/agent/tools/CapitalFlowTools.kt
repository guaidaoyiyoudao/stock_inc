package com.stock.dividend.data.agent.tools

import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import com.stock.dividend.data.plane.MarketDataPlane
import com.stock.dividend.data.repository.MarketDataRepository

private val FLOW_CODE_SCHEMA = Schema(
    type = Type.OBJECT,
    properties = mapOf(
        "code" to Schema(
            type = Type.STRING,
            description = "股票代码或名称：推荐 6 位数字代码（如 600036）或股票名称；带前缀代码会自动归一化"
        )
    ),
    required = listOf("code")
)

class GetCapitalFlowTool(
    private val marketDataPlane: MarketDataPlane,
) : ReadTool(
    name = "get_capital_flow",
    description = "查询单只股票的资金流向：主力净流入额（元）/占比（%）、超大单/大单/中单/小单净流入额与占比。正值=净流入，负值=净流出。code 参数格式见参数说明。",
    parameters = FLOW_CODE_SCHEMA,
) {
    override suspend fun run(context: ToolContext, args: Map<String, Any>): Any {
        val code = args.stringArg("code") ?: return mapOf("error" to "缺少 code 参数")
        return runCatching {
            val stock = marketDataPlane.resolveStock(code)
                ?: return@runCatching mapOf("error" to "未找到股票：$code")
            val flow = marketDataPlane.getCapitalFlow(stock.code)
                ?: return@runCatching mapOf("error" to "资金流数据获取失败")
            buildMap<String, Any?> {
                put("code", stock.code)
                put("name", stock.name)
                flow.mainNetInflow?.let { put("mainNetInflow", it) }
                flow.mainNetInflowPct?.let { put("mainNetInflowPct", it) }
                flow.superLargeNetInflow?.let { put("superLargeNetInflow", it) }
                flow.superLargeNetInflowPct?.let { put("superLargeNetInflowPct", it) }
                flow.largeNetInflow?.let { put("largeNetInflow", it) }
                flow.largeNetInflowPct?.let { put("largeNetInflowPct", it) }
                flow.mediumNetInflow?.let { put("mediumNetInflow", it) }
                flow.mediumNetInflowPct?.let { put("mediumNetInflowPct", it) }
                flow.smallNetInflow?.let { put("smallNetInflow", it) }
                flow.smallNetInflowPct?.let { put("smallNetInflowPct", it) }
            }
        }.getOrElse { e -> mapOf("error" to (e.message ?: "查询失败")) }
    }
}

class GetValuationMetricsTool(
    private val marketDataPlane: MarketDataPlane,
) : ReadTool(
    name = "get_valuation_metrics",
    description = "查询单只股票的估值与盘口指标快照：PE(TTM)、PB、总市值、流通市值、换手率、振幅、量比。code 参数格式见参数说明。",
    parameters = FLOW_CODE_SCHEMA,
) {
    override suspend fun run(context: ToolContext, args: Map<String, Any>): Any {
        val code = args.stringArg("code") ?: return mapOf("error" to "缺少 code 参数")
        return runCatching {
            val stock = marketDataPlane.resolveStock(code)
                ?: return@runCatching mapOf("error" to "未找到股票：$code")
            val entity = stock.toEntity()
            // 单次平面行情请求（此前 refreshPrice + fetchQuoteSnapshots 连发两次网络，平面会话缓存已合并）
            val snapshot = marketDataPlane.getQuoteSnapshots(listOf(entity))[stock.code]
                ?: return@runCatching mapOf("error" to "行情数据获取失败")
            buildMap<String, Any?> {
                put("code", stock.code)
                put("name", stock.name)
                snapshot.price?.let { put("currentPrice", it) }
                snapshot.pe?.let { put("peTtm", it) }
                snapshot.pb?.let { put("pb", it) }
                snapshot.totalMarketCap?.let { put("totalMarketCap", it) }
                snapshot.circMarketCap?.let { put("circMarketCap", it) }
                snapshot.turnoverRate?.let { put("turnoverRate", it) }
                snapshot.amplitude?.let { put("amplitude", it) }
                snapshot.volumeRatio?.let { put("volumeRatio", it) }
            }
        }.getOrElse { e -> mapOf("error" to (e.message ?: "查询失败")) }
    }
}

class GetDragonTigerTool(
    private val marketDataPlane: MarketDataPlane,
) : ReadTool(
    name = "get_dragon_tiger",
    description = "查询龙虎榜（当日上榜个股：交易日期、代码、名称、上榜原因、净买入额、龙虎榜成交额）。传 code 仅查该股；不传返回当日全市场。limit 默认 20，范围 1-50。",
    parameters = Schema(
        type = Type.OBJECT,
        properties = mapOf(
            "code" to Schema(type = Type.STRING, description = "可选：股票代码或名称；不传则返回全市场龙虎榜"),
            "limit" to Schema(type = Type.INTEGER, description = "可选：返回条数（1-50），默认 20")
        )
    ),
) {
    override suspend fun run(context: ToolContext, args: Map<String, Any>): Any {
        val limit = args.intArg("limit")?.coerceIn(1, 50) ?: 20
        return runCatching {
            val stockCode = args.stringArg("code")
            val resolved = stockCode?.let { stockCodeResolved(it) }
            val items = marketDataPlane.getDragonTiger(stockCode = resolved, limit = limit)
            if (items.isEmpty()) return@runCatching mapOf("error" to "暂无龙虎榜数据")
            mapOf(
                "items" to items.map {
                    buildMap<String, Any?> {
                        it.tradeDate?.let { v -> put("tradeDate", v) }
                        it.securityCode?.let { v -> put("code", v) }
                        it.securityName?.let { v -> put("name", v) }
                        it.explain?.let { v -> put("explain", v) }
                        it.netBuy?.let { v -> put("netBuy", v) }
                        it.billboardDealAmt?.let { v -> put("billboardDealAmt", v) }
                    }
                }
            )
        }.getOrElse { e -> mapOf("error" to (e.message ?: "查询失败")) }
    }

    /** 此工具内轻量解析：只有传了 code 且能匹配 6 位数字才作为过滤条件。 */
    private fun stockCodeResolved(raw: String): String? {
        val t = raw.trim()
        return when {
            t.matches(Regex("(?i)^(sh|sz)[.]?(\\d{6})$")) -> t.lowercase().replace(".", ".")
            t.matches(Regex("\\d{6}")) -> "${if (t.startsWith("6")) "sh" else "sz"}.$t"
            else -> null
        }
    }
}

class GetMarketSentimentTool(
    private val marketDataPlane: MarketDataPlane,
) : ReadTool(
    name = "get_market_sentiment",
    description = "查询当日市场情绪快照：主要指数（上证/深证/沪深300/创业板/科创50/中证500/中证1000）现价与涨跌幅、行业板块涨跌榜（领涨/领跌各前 5）、主力资金净流入榜（前 5）。无需参数。",
    parameters = null,
) {
    override suspend fun run(context: ToolContext, args: Map<String, Any>): Any {
        return runCatching {
            // 平面 60s 内存缓存：同参数列表（CHANGE）只发一次请求
            val indices = marketDataPlane.getIndexQuotes()
            val topIndustries = marketDataPlane.getIndustryList(
                sortBy = MarketDataRepository.SortBy.CHANGE, limit = 5
            )
            val bottomIndustries = runCatching {
                // 领跌：升序取前 5（接口 po=1 是降序，这里取不到升序，改用取较多后排序）
                marketDataPlane.getIndustryList(
                    sortBy = MarketDataRepository.SortBy.CHANGE, limit = 30
                ).sortedBy { it.changePct ?: Double.MAX_VALUE }.take(5)
            }.getOrDefault(emptyList())
            val inflowIndustries = marketDataPlane.getIndustryList(
                sortBy = MarketDataRepository.SortBy.INFLOW, limit = 5
            )
            buildMap<String, Any?> {
                put("indices", indices.map {
                    buildMap<String, Any?> {
                        put("name", it.name)
                        put("code", it.code)
                        it.price?.let { v -> put("price", v) }
                        it.changePct?.let { v -> put("changePct", v) }
                    }
                })
                put("topIndustries", topIndustries.map { it.toMap() })
                put("bottomIndustries", bottomIndustries.map { it.toMap() })
                put("inflowIndustries", inflowIndustries.map { it.toMap() })
            }
        }.getOrElse { e -> mapOf("error" to (e.message ?: "查询失败")) }
    }

    private fun com.stock.dividend.data.repository.MarketListItem.toMap(): Map<String, Any?> =
        buildMap {
            put("name", name)
            code?.let { put("code", it) }
            changePct?.let { put("changePct", it) }
            mainNetInflow?.let { put("mainNetInflow", it) }
            leaderName?.let { put("leaderName", it) }
        }
}
