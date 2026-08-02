package com.stock.dividend.data.agent.tools

import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import com.stock.dividend.data.repository.BondYieldRepository
import com.stock.dividend.data.repository.MarketDataRepository

class GetMarketIndexTool(
    private val marketDataRepository: MarketDataRepository,
) : ReadTool(
    name = "get_market_index",
    description = "查询主要大盘指数行情：上证指数、深证成指、沪深300、创业板指、科创50、中证500、中证1000 的现价、涨跌幅、成交额。无需参数；或传单个指数 code（如 000001 上证、000300 沪深300）只查该指数。",
    parameters = Schema(
        type = Type.OBJECT,
        properties = mapOf(
            "code" to Schema(
                type = Type.STRING,
                description = "可选：单个指数 6 位代码（如 000001 上证、399001 深成、000300 沪深300、399006 创业板、000688 科创50）；不传返回全部主要指数"
            )
        )
    ),
) {
    override suspend fun run(context: ToolContext, args: Map<String, Any>): Any {
        val code = args.stringArg("code")
        return runCatching {
            val indices = if (code != null) {
                listOfNotNull(marketDataRepository.fetchIndexOrEtfQuote(code))
            } else {
                marketDataRepository.fetchIndexQuotes()
            }
            if (indices.isEmpty()) return@runCatching mapOf("error" to "指数数据获取失败")
            mapOf(
                "indices" to indices.map {
                    buildMap<String, Any?> {
                        put("name", it.name)
                        put("code", it.code)
                        it.price?.let { v -> put("price", v) }
                        it.changePct?.let { v -> put("changePct", v) }
                        it.amount?.let { v -> put("amount", v) }
                    }
                }
            )
        }.getOrElse { e -> mapOf("error" to (e.message ?: "查询失败")) }
    }
}

class GetEtfInfoTool(
    private val marketDataRepository: MarketDataRepository,
) : ReadTool(
    name = "get_etf_info",
    description = "查询 ETF 基金行情：现价、涨跌幅、成交额。传 ETF 代码（如 510300 沪深300ETF、510880 红利ETF、159915 创业板ETF）。code 参数为 ETF 的 6 位代码。",
    parameters = Schema(
        type = Type.OBJECT,
        properties = mapOf(
            "code" to Schema(
                type = Type.STRING,
                description = "ETF 6 位代码（如 510300 沪深300ETF、510880 红利ETF、511010 国债ETF、159915 创业板ETF）"
            )
        ),
        required = listOf("code")
    ),
) {
    override suspend fun run(context: ToolContext, args: Map<String, Any>): Any {
        val code = args.stringArg("code") ?: return mapOf("error" to "缺少 code 参数")
        return runCatching {
            val etf = marketDataRepository.fetchIndexOrEtfQuote(code)
                ?: return@runCatching mapOf("error" to "ETF 数据获取失败")
            buildMap<String, Any?> {
                put("code", etf.code)
                put("name", etf.name)
                etf.price?.let { put("price", it) }
                etf.changePct?.let { put("changePct", it) }
                etf.amount?.let { put("amount", it) }
            }
        }.getOrElse { e -> mapOf("error" to (e.message ?: "查询失败")) }
    }
}

class GetTreasuryYieldsTool(
    private val bondYieldRepository: BondYieldRepository,
) : ReadTool(
    name = "get_treasury_yields",
    description = "查询国债收益率与 LPR：中国 2/5/10/30 年期国债到期收益率（%）、中美 10 年期利差（%）、LPR 1 年期/5 年期（%）。10Y 国债为关键无风险利率基准（买入线 = 10Y × 倍数）。无需参数。",
    parameters = null,
) {
    override suspend fun run(context: ToolContext, args: Map<String, Any>): Any {
        return runCatching {
            val y = bondYieldRepository.fetchAllYields()
            buildMap<String, Any?> {
                y.date?.let { put("date", it) }
                y.yield2Y?.let { put("cnGovBond2Y", it) }
                y.yield5Y?.let { put("cnGovBond5Y", it) }
                y.yield10Y?.let { put("cnGovBond10Y", it) }
                y.yield30Y?.let { put("cnGovBond30Y", it) }
                y.cnUsSpread10Y?.let { put("cnUsSpread10Y", it) }
                y.lpr1Y?.let { put("lpr1Y", it) }
                y.lpr5Y?.let { put("lpr5Y", it) }
            }
        }.getOrElse { e -> mapOf("error" to (e.message ?: "查询失败")) }
    }
}
