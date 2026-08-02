package com.stock.dividend.data.agent.tools

import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import com.stock.dividend.data.repository.DividendMetricsCalculator
import com.stock.dividend.data.repository.DividendRepository
import com.stock.dividend.data.repository.StockRepository
import kotlinx.coroutines.flow.first

class GetDividendMetricsTool(
    private val stockRepository: StockRepository,
    private val dividendRepository: DividendRepository,
) : ReadTool(
    name = "get_dividend_metrics",
    description = "查询单只股票的分红深度指标：分红总年数、截至最新年份的连续分红年数、近 3/5 年每股分红均值、近 3 年每股分红复合年增长率（CAGR%）、近 5 年标准差与变异系数（衡量分红稳定性，越小越稳）。code 参数格式见参数说明。",
    parameters = Schema(
        type = Type.OBJECT,
        properties = mapOf(
            "code" to Schema(
                type = Type.STRING,
                description = "股票代码或名称：推荐 6 位数字代码（如 600519）或股票名称；带前缀代码会自动归一化"
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
            val dividends = dividendRepository.observeDividends(stock.code).first()
            val metrics = DividendMetricsCalculator.calculate(dividends)
                ?: return@runCatching mapOf("error" to "分红数据不足，无法计算深度指标")
            buildMap<String, Any?> {
                put("code", stock.code)
                put("name", stock.name)
                put("totalYears", metrics.totalYears)
                put("consecutiveYears", metrics.consecutiveYears)
                put("latestYear", metrics.latestYear)
                metrics.avgCashPerShare3y?.let { put("avgCashPerShare3y", it) }
                metrics.avgCashPerShare5y?.let { put("avgCashPerShare5y", it) }
                metrics.cagr3y?.let { put("cagr3y", it) }
                metrics.stdDev?.let { put("stdDev", it) }
                metrics.coefficientOfVariation?.let { put("coefficientOfVariation", it) }
            }
        }.getOrElse { e -> mapOf("error" to (e.message ?: "查询失败")) }
    }
}
