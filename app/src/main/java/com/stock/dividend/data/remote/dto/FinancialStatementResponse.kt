package com.stock.dividend.data.remote.dto

import com.google.gson.annotations.SerializedName

/**
 * 东方财富 datacenter-web「利润表」(reportName=RPT_DMSK_FN_INCOME) 响应壳。
 * 结构与 [FundamentalResponse] 同构。字段名已实测确认（2026-08，茅台）。
 * 金额单位均为「元」（绝对值，非每股）；缺失字段为 null。
 */
data class IncomeStatementResponse(
    val success: Boolean?,
    val result: IncomeResult?
) {
    data class IncomeResult(val data: List<Item>?)

    data class Item(
        @SerializedName("SECURITY_CODE")
        val securityCode: String?,
        @SerializedName("SECURITY_NAME_ABBR")
        val securityName: String?,
        @SerializedName("REPORT_DATE")
        val reportDate: String?,
        @SerializedName("NOTICE_DATE")
        val noticeDate: String?,
        @SerializedName("TOTAL_OPERATE_INCOME")
        val totalOperateIncome: Double?,      // 营业总收入
        @SerializedName("OPERATE_INCOME")
        val operateIncome: Double?,           // 营业收入（部分企业无）
        @SerializedName("TOTAL_OPERATE_COST")
        val totalOperateCost: Double?,        // 营业总成本
        @SerializedName("OPERATE_COST")
        val operateCost: Double?,             // 营业成本
        @SerializedName("SALE_EXPENSE")
        val saleExpense: Double?,             // 销售费用
        @SerializedName("MANAGE_EXPENSE")
        val manageExpense: Double?,           // 管理费用
        @SerializedName("FINANCE_EXPENSE")
        val financeExpense: Double?,          // 财务费用
        @SerializedName("OPERATE_PROFIT")
        val operateProfit: Double?,           // 营业利润
        @SerializedName("TOTAL_PROFIT")
        val totalProfit: Double?,             // 利润总额
        @SerializedName("INCOME_TAX")
        val incomeTax: Double?,               // 所得税
        @SerializedName("PARENT_NETPROFIT")
        val parentNetProfit: Double?,         // 归母净利润
        @SerializedName("DEDUCT_PARENT_NETPROFIT")
        val deductParentNetProfit: Double?    // 扣非归母净利润
    )
}

/**
 * 东方财富「现金流量表」(reportName=RPT_DMSK_FN_CASHFLOW) 响应壳。
 * 金额单位「元」。字段名已实测确认（2026-08，茅台）。
 */
data class CashFlowStatementResponse(
    val success: Boolean?,
    val result: CashFlowResult?
) {
    data class CashFlowResult(val data: List<Item>?)

    data class Item(
        @SerializedName("SECURITY_CODE")
        val securityCode: String?,
        @SerializedName("SECURITY_NAME_ABBR")
        val securityName: String?,
        @SerializedName("REPORT_DATE")
        val reportDate: String?,
        @SerializedName("NETCASH_OPERATE")
        val netcashOperate: Double?,          // 经营活动现金流量净额
        @SerializedName("NETCASH_INVEST")
        val netcashInvest: Double?,           // 投资活动现金流量净额
        @SerializedName("NETCASH_FINANCE")
        val netcashFinance: Double?,          // 筹资活动现金流量净额
        @SerializedName("CCE_ADD")
        val cceAdd: Double?,                  // 现金及现金等价物净增加额
        @SerializedName("END_CCE")
        val endCce: Double?,                  // 期末现金及现金等价物余额
        @SerializedName("RECEIVE_INVEST_INCOME")
        val receiveInvestIncome: Double?      // 取得投资收益收到的现金
    )
}

/**
 * 东方财富「资产负债表」(reportName=RPT_DMSK_FN_BALANCE) 响应壳。
 * 与现有 [BalanceSheetResponse] 同接口，但取全量字段（现有只取负债率）。
 * 金额单位「元」。字段名已实测确认（2026-08，茅台）。
 */
data class BalanceSheetFullResponse(
    val success: Boolean?,
    val result: BalanceSheetFullResult?
) {
    data class BalanceSheetFullResult(val data: List<Item>?)

    data class Item(
        @SerializedName("SECURITY_CODE")
        val securityCode: String?,
        @SerializedName("SECURITY_NAME_ABBR")
        val securityName: String?,
        @SerializedName("REPORT_DATE")
        val reportDate: String?,
        @SerializedName("TOTAL_ASSETS")
        val totalAssets: Double?,             // 资产总计
        @SerializedName("TOTAL_LIABILITIES")
        val totalLiabilities: Double?,        // 负债合计
        @SerializedName("TOTAL_EQUITY")
        val totalEquity: Double?,             // 所有者权益（或股东权益）合计
        @SerializedName("MONETARYFUNDS")
        val monetaryFunds: Double?,           // 货币资金
        @SerializedName("ACCOUNTS_RECE")
        val accountsRece: Double?,            // 应收账款
        @SerializedName("INVENTORY")
        val inventory: Double?,               // 存货
        @SerializedName("ACCOUNTS_PAYABLE")
        val accountsPayable: Double?,         // 应付账款
        @SerializedName("FIXED_ASSET")
        val fixedAsset: Double?,              // 固定资产
        @SerializedName("DEBT_ASSET_RATIO")
        val debtAssetRatio: Double?           // 资产负债率 %
    )
}
