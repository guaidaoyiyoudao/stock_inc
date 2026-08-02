package com.stock.dividend.data.repository

import com.stock.dividend.data.remote.dto.BalanceSheetFullResponse
import com.stock.dividend.data.remote.dto.CashFlowStatementResponse
import com.stock.dividend.data.remote.dto.IncomeStatementResponse

/**
 * 单股财务三表（近 N 期）；纯数据，无 Android 依赖，便于单测构造。
 *
 * 各表 [periods] 升序（旧→新），与 [Fundamentals] 约定一致。
 * 所有金额字段单位「元」（绝对值，非每股）；缺失字段为 null。
 */
data class FinancialStatements(
    val periods: List<Period>
) {
    /** 单期：某报告期对齐的三表科目。 */
    data class Period(
        val reportDate: String,            // "2024-12-31"
        // ── 利润表 ──
        val totalOperateIncome: Double?,   // 营业总收入
        val operateCost: Double?,          // 营业成本
        val saleExpense: Double?,          // 销售费用
        val manageExpense: Double?,        // 管理费用
        val financeExpense: Double?,       // 财务费用
        val operateProfit: Double?,        // 营业利润
        val totalProfit: Double?,          // 利润总额
        val incomeTax: Double?,            // 所得税
        val parentNetProfit: Double?,      // 归母净利润
        val deductParentNetProfit: Double?,// 扣非归母净利润
        // ── 现金流量表 ──
        val netcashOperate: Double?,       // 经营活动现金流量净额
        val netcashInvest: Double?,        // 投资活动现金流量净额
        val netcashFinance: Double?,       // 筹资活动现金流量净额
        val endCce: Double?,               // 期末现金及现金等价物余额
        // ── 资产负债表 ──
        val totalAssets: Double?,          // 资产总计
        val totalLiabilities: Double?,     // 负债合计
        val totalEquity: Double?,          // 所有者权益合计
        val monetaryFunds: Double?,        // 货币资金
        val accountsRece: Double?,         // 应收账款
        val inventory: Double?,            // 存货
        val accountsPayable: Double?,      // 应付账款
        val fixedAsset: Double?            // 固定资产
    )
}

/**
 * 三个报表 DTO → [FinancialStatements]（纯函数）。
 *
 * 三表按报告期对齐：先各自归一化报告期日期（实测 REPORT_DATE 带 " 00:00:00" 后缀，须去后缀），
 * 再以利润表为基准，按 reportDate 关联现金流量表与资产负债表。无利润表数据的报告期丢弃。
 *
 * @param income   利润表项（升序后取最新 [maxN] 期，作为基准）
 * @param cashFlow 现金流量表项
 * @param balance  资产负债表项
 * @param maxN     最多保留期数，默认 8（季报，覆盖近 2 年）
 * @return 解析结果；income 为空返回 null
 */
object FinancialStatementsBuilder {
    fun build(
        income: List<IncomeStatementResponse.Item>,
        cashFlow: List<CashFlowStatementResponse.Item>,
        balance: List<BalanceSheetFullResponse.Item>,
        maxN: Int = 8
    ): FinancialStatements? {
        if (income.isEmpty()) return null
        val cashByDate = cashFlow.associate { it.normDate() to it }
        val balByDate = balance.associate { it.normDate() to it }

        val sorted = income
            .filter { !it.reportDate.isNullOrBlank() }
            .sortedBy { it.reportDate!! }
            .takeLast(maxN)
        if (sorted.isEmpty()) return null

        return FinancialStatements(
            periods = sorted.map { item ->
                val date = item.normDate()
                val cf = cashByDate[date]
                val bs = balByDate[date]
                FinancialStatements.Period(
                    reportDate = date,
                    totalOperateIncome = item.totalOperateIncome,
                    operateCost = item.operateCost,
                    saleExpense = item.saleExpense,
                    manageExpense = item.manageExpense,
                    financeExpense = item.financeExpense,
                    operateProfit = item.operateProfit,
                    totalProfit = item.totalProfit,
                    incomeTax = item.incomeTax,
                    parentNetProfit = item.parentNetProfit,
                    deductParentNetProfit = item.deductParentNetProfit,
                    netcashOperate = cf?.netcashOperate,
                    netcashInvest = cf?.netcashInvest,
                    netcashFinance = cf?.netcashFinance,
                    endCce = cf?.endCce,
                    totalAssets = bs?.totalAssets,
                    totalLiabilities = bs?.totalLiabilities,
                    totalEquity = bs?.totalEquity,
                    monetaryFunds = bs?.monetaryFunds,
                    accountsRece = bs?.accountsRece,
                    inventory = bs?.inventory,
                    accountsPayable = bs?.accountsPayable,
                    fixedAsset = bs?.fixedAsset
                )
            }
        )
    }

    private fun IncomeStatementResponse.Item.normDate(): String =
        reportDate!!.substringBefore(" ").trim()

    private fun CashFlowStatementResponse.Item.normDate(): String =
        reportDate?.substringBefore(" ")?.trim() ?: ""

    private fun BalanceSheetFullResponse.Item.normDate(): String =
        reportDate?.substringBefore(" ")?.trim() ?: ""
}
