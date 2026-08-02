package com.stock.dividend.data.agent.tools

import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import com.stock.dividend.data.repository.FinancialStatementsRepository
import com.stock.dividend.data.repository.StockRepository

class GetFinancialStatementsTool(
    private val stockRepository: StockRepository,
    private val financialStatementsRepository: FinancialStatementsRepository,
) : ReadTool(
    name = "get_financial_statements",
    description = "查询单只股票近 8 期财务三表明细：利润表（营收/营业成本/三费/营业利润/利润总额/所得税/归母净利/扣非净利）、现金流量表（经营/投资/筹资净额/期末现金）、资产负债表（总资产/总负债/净资产/货币资金/应收/存货/应付/固定资产）。金额单位元。forceRefresh=true 绕过 7 天缓存强制刷新。",
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
            val stmts = financialStatementsRepository.getFinancialStatements(stock.code, forceRefresh)
                ?: return@runCatching mapOf("error" to "财务报表数据不足，无法查询")
            mapOf(
                "code" to stock.code,
                "name" to stock.name,
                "periods" to stmts.periods.map { p ->
                    buildMap<String, Any?> {
                        put("reportDate", p.reportDate)
                        // 利润表
                        p.totalOperateIncome?.let { put("totalOperateIncome", it) }
                        p.operateCost?.let { put("operateCost", it) }
                        p.saleExpense?.let { put("saleExpense", it) }
                        p.manageExpense?.let { put("manageExpense", it) }
                        p.financeExpense?.let { put("financeExpense", it) }
                        p.operateProfit?.let { put("operateProfit", it) }
                        p.totalProfit?.let { put("totalProfit", it) }
                        p.incomeTax?.let { put("incomeTax", it) }
                        p.parentNetProfit?.let { put("parentNetProfit", it) }
                        p.deductParentNetProfit?.let { put("deductParentNetProfit", it) }
                        // 现金流量表
                        p.netcashOperate?.let { put("netcashOperate", it) }
                        p.netcashInvest?.let { put("netcashInvest", it) }
                        p.netcashFinance?.let { put("netcashFinance", it) }
                        p.endCce?.let { put("endCce", it) }
                        // 资产负债表
                        p.totalAssets?.let { put("totalAssets", it) }
                        p.totalLiabilities?.let { put("totalLiabilities", it) }
                        p.totalEquity?.let { put("totalEquity", it) }
                        p.monetaryFunds?.let { put("monetaryFunds", it) }
                        p.accountsRece?.let { put("accountsRece", it) }
                        p.inventory?.let { put("inventory", it) }
                        p.accountsPayable?.let { put("accountsPayable", it) }
                        p.fixedAsset?.let { put("fixedAsset", it) }
                    }
                }
            )
        }.getOrElse { e -> mapOf("error" to (e.message ?: "查询失败")) }
    }
}
