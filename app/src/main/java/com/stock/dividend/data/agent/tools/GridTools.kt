package com.stock.dividend.data.agent.tools

import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import com.stock.dividend.data.repository.GridCalculator
import com.stock.dividend.data.repository.GridType
import com.stock.dividend.data.repository.GridExecutionCalculator
import com.stock.dividend.data.repository.GridPlanRepository
import com.stock.dividend.data.repository.StockRepository
import com.stock.dividend.data.repository.TransactionRepository
import kotlinx.coroutines.flow.first

/**
 * 网格交易计划查询（只读）：计划参数（买入起点/资金用完位/参考上界/档数/资金）、
 * 当前价、下一档买入价与股数、执行进度（已触发档/已投入/剩余/浮盈）与到档提醒开关。
 * 与 App 网格页同口径（GridCalculator + GridExecutionCalculator，纯程序计算）。
 */
class GetGridPlansTool(
    private val stockRepository: StockRepository,
    private val gridPlanRepository: GridPlanRepository,
    private val transactionRepository: TransactionRepository,
) : ReadTool(
    name = "get_grid_plans",
    description = "查询网格交易计划（纯买入分批建仓）：参数（买入起点/资金用完位/参考上界/档数/总资金）、当前价、下一档买入价与建议股数、执行进度（已触发档/已投入/剩余可投/浮盈）与到档提醒开关。不传 code 返回全部计划，传 code 只返回该标的的计划。",
    parameters = Schema(
        type = Type.OBJECT,
        properties = mapOf(
            "code" to Schema(
                type = Type.STRING,
                description = "可选：股票代码或名称（推荐 6 位数字代码，带前缀代码会自动归一化）；不传返回全部网格计划"
            )
        )
    ),
) {
    override suspend fun run(context: ToolContext, args: Map<String, Any>): Any = runCatching {
        val code = args.stringArg("code")
        val resolvedCode = code?.let {
            stockRepository.resolveStock(it)?.code ?: return@runCatching mapOf("error" to "未找到股票：$it")
        }

        val plans = gridPlanRepository.observeAll().first()
            .filter { resolvedCode == null || it.stockCode == resolvedCode }
        if (plans.isEmpty()) {
            return@runCatching mapOf(
                "plans" to emptyList<Any>(),
                "note" to if (resolvedCode != null) "该标的暂无网格计划" else "暂无任何网格计划"
            )
        }

        val planCodes = plans.map { it.stockCode }.toSet()
        val stocks = stockRepository.observeAllStocksForSnapshot().filter { it.code in planCodes }
        val prices = stockRepository.fetchFreshPrices(stocks)
        val transactionsByStock = transactionRepository.getAll().groupBy { it.stockCode }

        mapOf(
            "plans" to plans.map { plan ->
                val price = prices[plan.stockCode]
                val planTxs = transactionsByStock[plan.stockCode].orEmpty()
                val result = GridCalculator.markTriggeredLevels(
                    GridCalculator.generate(
                        basePrice = plan.basePrice,
                        lowPrice = plan.lowPrice,
                        highPrice = plan.highPrice,
                        grids = plan.grids,
                        totalCapital = plan.totalCapital,
                        currentPrice = price,
                        gridType = GridType.fromRaw(plan.gridType)
                    ),
                    planTxs
                )
                val execution = GridExecutionCalculator.calculate(result, plan.totalCapital, planTxs, price)
                buildMap<String, Any?> {
                    put("code", plan.stockCode)
                    put("name", plan.stockName)
                    put("basePrice", plan.basePrice)      // 买入起点（第一档/最贵档）
                    put("lowPrice", plan.lowPrice)        // 资金用完位（最后一档/最便宜档）
                    put("highPrice", plan.highPrice)      // 参考上界（超过不追买）
                    put("grids", plan.grids)
                    put("totalCapital", plan.totalCapital)
                    put("notifyEnabled", plan.notifyEnabled)
                    put("currentPrice", price)
                    result.validationError?.let { put("validationError", it) }
                    // 现价高于起点或跌破资金用完位时 nextBuyHint 为 null（无下一档）
                    val nextBuy = result.nextBuyHint
                    put("nextBuyLevel", nextBuy)
                    if (nextBuy != null) {
                        result.levels.firstOrNull { it.price == nextBuy }?.let { put("nextBuyShares", it.shares) }
                    }
                    put("triggeredLevels", execution.triggeredCount)
                    put("totalLevels", execution.totalLevels)
                    put("investedAmount", execution.investedAmount)
                    put("remainingCapital", execution.remainingCapital)
                    put("avgBuyPrice", execution.avgBuyPrice)
                    put("unrealizedPnl", execution.unrealizedPnl)
                }
            },
            "note" to "网格仅计划提示，不自动下单；实际执行（挂单/成交）由用户在券商端手动完成"
        )
    }.getOrElse { e -> mapOf("error" to (e.message ?: "查询失败")) }
}
