package com.stock.dividend.data.agent.tools

import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import com.stock.dividend.data.plane.MarketDataPlane
import com.stock.dividend.data.repository.MarketDataRepository

class GetIndustryListTool(
    private val marketDataPlane: MarketDataPlane,
) : ReadTool(
    name = "get_industry_list",
    description = "查询 A 股行业板块行情（东财一级行业）：板块代码、名称、涨跌幅、换手率、主力净流入、领涨股。sortBy 可选 CHANGE(涨跌幅,默认)/INFLOW(主力净流入)/TURNOVER(换手率)；limit 默认 15，范围 5-30。",
    parameters = Schema(
        type = Type.OBJECT,
        properties = mapOf(
            "sortBy" to Schema(
                type = Type.STRING,
                description = "可选：排序维度 CHANGE/INFLOW/TURNOVER，默认 CHANGE"
            ),
            "limit" to Schema(type = Type.INTEGER, description = "可选：返回条数（5-30），默认 15")
        )
    ),
) {
    override suspend fun run(context: ToolContext, args: Map<String, Any>): Any {
        val sortBy = parseSortBy(args.stringArg("sortBy"))
        val limit = args.intArg("limit")?.coerceIn(5, 30) ?: 15
        return runCatching {
            val list = marketDataPlane.getIndustryList(sortBy = sortBy, limit = limit)
            if (list.isEmpty()) return@runCatching mapOf("error" to "行业数据获取失败")
            mapOf(
                "sortBy" to sortBy.name,
                "industries" to list.map { it.toMap() }
            )
        }.getOrElse { e -> mapOf("error" to (e.message ?: "查询失败")) }
    }

    private fun parseSortBy(raw: String?): MarketDataRepository.SortBy =
        when (raw?.uppercase()) {
            "INFLOW" -> MarketDataRepository.SortBy.INFLOW
            "TURNOVER" -> MarketDataRepository.SortBy.TURNOVER
            else -> MarketDataRepository.SortBy.CHANGE
        }
}

class GetIndustryPeersTool(
    private val marketDataPlane: MarketDataPlane,
) : ReadTool(
    name = "get_industry_peers",
    description = "查询同行业个股对比：传 code（取其所属行业）或 industry（板块代码 BKxxxx 或行业名），返回同行业个股的现价/涨跌幅/PE/PB/总市值/换手率。sortBy 可选 CHANGE/MARKET_CAP(默认)/PE/PB；limit 默认 15，范围 5-30。",
    parameters = Schema(
        type = Type.OBJECT,
        properties = mapOf(
            "code" to Schema(
                type = Type.STRING,
                description = "可选：股票代码或名称，取其所属行业。与 industry 二选一，优先 code"
            ),
            "industry" to Schema(
                type = Type.STRING,
                description = "可选：行业板块代码（如 BK1277）或行业名称；code 传了则忽略本参数"
            ),
            "sortBy" to Schema(
                type = Type.STRING,
                description = "可选：排序 CHANGE/MARKET_CAP/PE/PB，默认 MARKET_CAP（按总市值降序）"
            ),
            "limit" to Schema(type = Type.INTEGER, description = "可选：返回条数（5-30），默认 15")
        )
    ),
) {
    override suspend fun run(context: ToolContext, args: Map<String, Any>): Any {
        val code = args.stringArg("code")
        val industry = args.stringArg("industry")
        val target = code ?: industry ?: return mapOf("error" to "需提供 code 或 industry 参数")
        val sortBy = parsePeerSortBy(args.stringArg("sortBy"))
        val limit = args.intArg("limit")?.coerceIn(5, 30) ?: 15
        return runCatching {
            // 若传的是股票 code，先 resolve 拿到 sh./sz. 格式，便于 Repository 反查行业
            val resolvedTarget = if (code != null) {
                marketDataPlane.resolveStock(code)?.code ?: return@runCatching mapOf("error" to "未找到股票：$code")
            } else {
                target
            }
            val peers = marketDataPlane.getIndustryPeers(resolvedTarget, sortBy, limit)
            if (peers.isEmpty()) return@runCatching mapOf("error" to "无法解析该股票的行业，请直接传 industry 板块代码")
            mapOf(
                "count" to peers.size,
                "peers" to peers.map { it.toMap() }
            )
        }.getOrElse { e -> mapOf("error" to (e.message ?: "查询失败")) }
    }

    private fun parsePeerSortBy(raw: String?): MarketDataRepository.PeerSortBy =
        when (raw?.uppercase()) {
            "CHANGE" -> MarketDataRepository.PeerSortBy.CHANGE
            "PE" -> MarketDataRepository.PeerSortBy.PE
            "PB" -> MarketDataRepository.PeerSortBy.PB
            else -> MarketDataRepository.PeerSortBy.MARKET_CAP
        }
}

private fun com.stock.dividend.data.repository.MarketListItem.toMap(): Map<String, Any?> =
    buildMap {
        put("code", code)
        put("name", name)
        price?.let { put("price", it) }
        changePct?.let { put("changePct", it) }
        pe?.let { put("pe", it) }
        pb?.let { put("pb", it) }
        totalMarketCap?.let { put("totalMarketCap", it) }
        turnoverRate?.let { put("turnoverRate", it) }
        industry?.let { put("industry", it) }
        mainNetInflow?.let { put("mainNetInflow", it) }
        mainNetInflowPct?.let { put("mainNetInflowPct", it) }
        leaderName?.let { put("leaderName", it) }
        leaderCode?.let { put("leaderCode", it) }
        leaderChangePct?.let { put("leaderChangePct", it) }
    }
