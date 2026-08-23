package com.stock.dividend.data.repository

import com.stock.dividend.data.remote.dto.BalanceSheetFullResponse
import com.stock.dividend.data.remote.dto.CashFlowStatementResponse
import com.stock.dividend.data.remote.dto.FuyaoBalanceSheetItem
import com.stock.dividend.data.remote.dto.FuyaoCashFlowItem
import com.stock.dividend.data.remote.dto.FuyaoIncomeItem
import com.stock.dividend.data.remote.dto.IncomeStatementResponse
import com.stock.dividend.data.remote.dto.fuyaoMsToDateStringOrNull

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

/**
 * 扶摇三表 DTO → [FinancialStatements]（纯函数）。报告期取 **period_end_ms**——
 * report_date_ms 实测是公告日（同季度再公告会同值，作报告期会撞期，2026-08-23 实测）。
 * 以利润表为基准按报告期关联另两表，结构对齐 [FinancialStatementsBuilder]。
 *
 * 扶摇缺口科目置 null，由调用方以东财并行补齐（[FinancialStatements.Period.supplementedFrom]）：
 * **营业总收入**（扶摇 operating_income 实测为「营业收入」口径——茅台 2026H1 为 907.0 亿 vs
 * 东财营业总收入 922.8 亿，差财务子公司利息收入；故此字段恒 null 由东财回填，2026-08-23 审计 M1）、
 * 财务费用 / 扣非归母净利润 / 期末现金余额（扶摇仅净增加额）/ 存货 / 应付账款 / 固定资产。
 */
object FuyaoStatementsBuilder {
    fun build(
        income: List<FuyaoIncomeItem>,
        balance: List<FuyaoBalanceSheetItem>,
        cashFlow: List<FuyaoCashFlowItem>,
        maxN: Int = 8
    ): FinancialStatements? {
        if (income.isEmpty()) return null
        val balByDate = balance.mapNotNull { item ->
            item.periodEndMs.fuyaoMsToDateStringOrNull()?.let { it to item }
        }.toMap()
        val cfByDate = cashFlow.mapNotNull { item ->
            item.periodEndMs.fuyaoMsToDateStringOrNull()?.let { it to item }
        }.toMap()

        val sorted = income.mapNotNull { item ->
            item.periodEndMs.fuyaoMsToDateStringOrNull()?.let { it to item }
        }.sortedBy { it.first }.takeLast(maxN)
        if (sorted.isEmpty()) return null

        return FinancialStatements(
            periods = sorted.map { (date, item) ->
                val bs = balByDate[date]
                val cf = cfByDate[date]
                FinancialStatements.Period(
                    reportDate = date,
                    totalOperateIncome = null,   // 扶摇无营业总收入口径（见函数头 M1），东财并行回填
                    operateCost = item.operatingCosts,
                    saleExpense = item.salesFee,
                    manageExpense = item.manageFee,
                    financeExpense = null,
                    operateProfit = item.operatingProfit,
                    totalProfit = item.profitTotal,
                    incomeTax = item.incomeTaxExpense,
                    parentNetProfit = item.parentNetProfit,
                    deductParentNetProfit = null,
                    netcashOperate = cf?.operateNetCash,
                    netcashInvest = cf?.investNetCash,
                    netcashFinance = cf?.financeNetCash,
                    endCce = null,
                    totalAssets = bs?.assetsTotal,
                    totalLiabilities = bs?.totalDebt,
                    totalEquity = bs?.holderEquityTotal,
                    monetaryFunds = bs?.cash,
                    accountsRece = bs?.accountsReceivable,
                    inventory = null,
                    accountsPayable = null,
                    fixedAsset = null
                )
            }
        )
    }
}

/**
 * 主源（扶摇）+ 候补（东财）单期字段级合并：仅回填接收者为 null 的科目
 * （东财补齐扶摇缺的财务费用/扣非/期末现金/存货/应付/固定资产）。[other] 为 null 原样返回。
 */
fun FinancialStatements.Period.supplementedFrom(other: FinancialStatements.Period?): FinancialStatements.Period {
    if (other == null || other === this) return this
    return copy(
        totalOperateIncome = totalOperateIncome ?: other.totalOperateIncome,
        operateCost = operateCost ?: other.operateCost,
        saleExpense = saleExpense ?: other.saleExpense,
        manageExpense = manageExpense ?: other.manageExpense,
        financeExpense = financeExpense ?: other.financeExpense,
        operateProfit = operateProfit ?: other.operateProfit,
        totalProfit = totalProfit ?: other.totalProfit,
        incomeTax = incomeTax ?: other.incomeTax,
        parentNetProfit = parentNetProfit ?: other.parentNetProfit,
        deductParentNetProfit = deductParentNetProfit ?: other.deductParentNetProfit,
        netcashOperate = netcashOperate ?: other.netcashOperate,
        netcashInvest = netcashInvest ?: other.netcashInvest,
        netcashFinance = netcashFinance ?: other.netcashFinance,
        endCce = endCce ?: other.endCce,
        totalAssets = totalAssets ?: other.totalAssets,
        totalLiabilities = totalLiabilities ?: other.totalLiabilities,
        totalEquity = totalEquity ?: other.totalEquity,
        monetaryFunds = monetaryFunds ?: other.monetaryFunds,
        accountsRece = accountsRece ?: other.accountsRece,
        inventory = inventory ?: other.inventory,
        accountsPayable = accountsPayable ?: other.accountsPayable,
        fixedAsset = fixedAsset ?: other.fixedAsset
    )
}
