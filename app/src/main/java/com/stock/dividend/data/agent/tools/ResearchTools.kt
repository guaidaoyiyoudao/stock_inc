package com.stock.dividend.data.agent.tools

import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import com.stock.dividend.data.repository.ResearchRepository
import com.stock.dividend.data.repository.StockRepository

class GetResearchReportsTool(
    private val stockRepository: StockRepository,
    private val researchRepository: ResearchRepository,
) : ReadTool(
    name = "get_research_reports",
    description = "查询单只股票的券商研报：标题、研究机构、发布日、评级、今明两年预测 EPS（元/股）/PE（倍）。limit 默认 10，范围 1-20。",
    parameters = Schema(
        type = Type.OBJECT,
        properties = mapOf(
            "code" to Schema(
                type = Type.STRING,
                description = "股票代码或名称：推荐 6 位数字代码（如 600519）或股票名称；带前缀代码会自动归一化"
            ),
            "limit" to Schema(type = Type.INTEGER, description = "可选：返回条数（1-20），默认 10")
        ),
        required = listOf("code")
    ),
) {
    override suspend fun run(context: ToolContext, args: Map<String, Any>): Any {
        val code = args.stringArg("code") ?: return mapOf("error" to "缺少 code 参数")
        val limit = args.intArg("limit")?.coerceIn(1, 20) ?: 10
        return runCatching {
            val stock = stockRepository.resolveStock(code)
                ?: return@runCatching mapOf("error" to "未找到股票：$code")
            val code6 = stock.code.substringAfter(".")
            val reports = researchRepository.fetchReports(code6, limit)
            if (reports.isEmpty()) return@runCatching mapOf("error" to "暂无研报数据")
            mapOf(
                "code" to stock.code,
                "name" to stock.name,
                "reports" to reports.map {
                    buildMap<String, Any?> {
                        it.title?.let { v -> put("title", v) }
                        it.orgName?.let { v -> put("orgName", v) }
                        it.publishDate?.let { v -> put("publishDate", v) }
                        it.rating?.let { v -> put("rating", v) }
                        it.predictThisYearEps?.let { v -> put("predictThisYearEps", v) }
                        it.predictThisYearPe?.let { v -> put("predictThisYearPe", v) }
                        it.predictNextYearEps?.let { v -> put("predictNextYearEps", v) }
                        it.predictNextYearPe?.let { v -> put("predictNextYearPe", v) }
                    }
                }
            )
        }.getOrElse { e -> mapOf("error" to (e.message ?: "查询失败")) }
    }
}

class GetStockNewsTool(
    private val stockRepository: StockRepository,
    private val researchRepository: ResearchRepository,
) : ReadTool(
    name = "get_stock_news",
    description = "查询单只股票的公告/资讯：标题、发布日期。limit 默认 10，范围 1-20。code 参数格式见参数说明。",
    parameters = Schema(
        type = Type.OBJECT,
        properties = mapOf(
            "code" to Schema(
                type = Type.STRING,
                description = "股票代码或名称：推荐 6 位数字代码（如 600519）或股票名称；带前缀代码会自动归一化"
            ),
            "limit" to Schema(type = Type.INTEGER, description = "可选：返回条数（1-20），默认 10")
        ),
        required = listOf("code")
    ),
) {
    override suspend fun run(context: ToolContext, args: Map<String, Any>): Any {
        val code = args.stringArg("code") ?: return mapOf("error" to "缺少 code 参数")
        val limit = args.intArg("limit")?.coerceIn(1, 20) ?: 10
        return runCatching {
            val stock = stockRepository.resolveStock(code)
                ?: return@runCatching mapOf("error" to "未找到股票：$code")
            val code6 = stock.code.substringAfter(".")
            val anns = researchRepository.fetchAnnouncements(code6, limit)
            if (anns.isEmpty()) return@runCatching mapOf("error" to "暂无公告数据")
            mapOf(
                "code" to stock.code,
                "name" to stock.name,
                "announcements" to anns.map {
                    buildMap<String, Any?> {
                        it.title?.let { v -> put("title", v) }
                        it.noticeDate?.let { v -> put("noticeDate", v) }
                        it.artCode?.let { v -> put("artCode", v) }
                    }
                }
            )
        }.getOrElse { e -> mapOf("error" to (e.message ?: "查询失败")) }
    }
}
