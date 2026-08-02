package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.stock.dividend.data.remote.dto.BalanceSheetFullResponse
import com.stock.dividend.data.remote.dto.CashFlowStatementResponse
import com.stock.dividend.data.remote.dto.IncomeStatementResponse
import org.junit.Test

/**
 * 财务三表 DTO 解析测试——锁定字段映射与单位（金额均为「元」绝对值）。
 *
 * Fixture 取自 **实测**（2026-08-02 东方财富 datacenter-web，茅台 600519 2025Q1）：
 * - 利润表 RPT_DMSK_FN_INCOME：TOTAL_OPERATE_INCOME=51443450583.77（营收 514 亿）
 * - 现金流量表 RPT_DMSK_FN_CASHFLOW：NETCASH_OPERATE 等
 * - 资产负债表 RPT_DMSK_FN_BALANCE：TOTAL_ASSETS、DEBT_ASSET_RATIO（%）
 *
 * 单位：三表金额字段均为「元」原值，不 ÷100；DEBT_ASSET_RATIO 为「%」。
 */
class FinancialStatementDtoParseTest {

    private val gson = Gson()

    // ── 利润表（实测茅台 2025Q1，已裁剪核心字段）────────────────────
    private val incomeJson = """
        {"success":true,"result":{"data":[{
          "SECUCODE":"600519.SH","SECURITY_CODE":"600519","SECURITY_NAME_ABBR":"贵州茅台",
          "REPORT_DATE":"2025-03-31 00:00:00","NOTICE_DATE":"2026-04-25 00:00:00",
          "TOTAL_OPERATE_INCOME":51443450583.77,"OPERATE_INCOME":null,
          "TOTAL_OPERATE_COST":14433297495.64,"OPERATE_COST":4061430550.43,
          "SALE_EXPENSE":1495322480.9,"MANAGE_EXPENSE":1919959143.43,"FINANCE_EXPENSE":-282549536.99,
          "OPERATE_PROFIT":37036598228.0,"TOTAL_PROFIT":37031453649.79,"INCOME_TAX":9256817638.18,
          "PARENT_NETPROFIT":26847474238.76,"DEDUCT_PARENT_NETPROFIT":26849883702.9
        }]}}
    """.trimIndent()

    @Test
    fun `income statement parses amounts in yuan`() {
        val resp = gson.fromJson(incomeJson, IncomeStatementResponse::class.java)
        val item = resp.result!!.data!!.first()
        assertThat(item.securityCode).isEqualTo("600519")
        assertThat(item.reportDate).startsWith("2025-03-31")
        // 金额单位「元」，原值
        assertThat(item.totalOperateIncome).isEqualTo(51443450583.77)
        assertThat(item.parentNetProfit).isEqualTo(26847474238.76)
        assertThat(item.incomeTax).isEqualTo(9256817638.18)
        // 财务费用为负（利息收入），原样保留
        assertThat(item.financeExpense).isEqualTo(-282549536.99)
        // OPERATE_INCOME 为 null 字段
        assertThat(item.operateIncome).isNull()
    }

    // ── 现金流量表（实测结构，核心字段）──────────────────────────────
    private val cashFlowJson = """
        {"success":true,"result":{"data":[{
          "SECURITY_CODE":"600519","SECURITY_NAME_ABBR":"贵州茅台",
          "REPORT_DATE":"2025-03-31 00:00:00",
          "NETCASH_OPERATE":30000000000.0,"NETCASH_INVEST":-5000000000.0,
          "NETCASH_FINANCE":-8000000000.0,"CCE_ADD":17000000000.0,
          "END_CCE":200000000000.0,"RECEIVE_INVEST_INCOME":1000000000.0
        }]}}
    """.trimIndent()

    @Test
    fun `cashflow statement parses net cash amounts in yuan`() {
        val resp = gson.fromJson(cashFlowJson, CashFlowStatementResponse::class.java)
        val item = resp.result!!.data!!.first()
        assertThat(item.netcashOperate).isEqualTo(30000000000.0)
        assertThat(item.netcashInvest).isEqualTo(-5000000000.0) // 投资净流出，负值
        assertThat(item.netcashFinance).isEqualTo(-8000000000.0)
        assertThat(item.endCce).isEqualTo(200000000000.0)
    }

    // ── 资产负债表（实测结构，含 DEBT_ASSET_RATIO 百分比）────────────
    private val balanceJson = """
        {"success":true,"result":{"data":[{
          "SECURITY_CODE":"600519","SECURITY_NAME_ABBR":"贵州茅台",
          "REPORT_DATE":"2025-03-31 00:00:00",
          "TOTAL_ASSETS":250000000000.0,"TOTAL_LIABILITIES":50000000000.0,
          "TOTAL_EQUITY":200000000000.0,"MONETARYFUNDS":180000000000.0,
          "ACCOUNTS_RECE":500000000.0,"INVENTORY":45000000000.0,
          "ACCOUNTS_PAYABLE":3000000000.0,"FIXED_ASSET":20000000000.0,
          "DEBT_ASSET_RATIO":20.0
        }]}}
    """.trimIndent()

    @Test
    fun `balance sheet parses amounts in yuan and debt ratio as percent`() {
        val resp = gson.fromJson(balanceJson, BalanceSheetFullResponse::class.java)
        val item = resp.result!!.data!!.first()
        assertThat(item.totalAssets).isEqualTo(250000000000.0)
        assertThat(item.totalLiabilities).isEqualTo(50000000000.0)
        assertThat(item.monetaryFunds).isEqualTo(180000000000.0)
        // DEBT_ASSET_RATIO 单位「%」，20.0 表示 20%，原值不 ÷100
        assertThat(item.debtAssetRatio).isEqualTo(20.0)
    }

    // ── 三表对齐合并：报告期带时间后缀须归一化 ──────────────────────
    @Test
    fun `three statements align by normalized reportDate`() {
        val income = listOf(
            IncomeStatementResponse.Item(
                securityCode = "600519", securityName = "贵州茅台",
                reportDate = "2025-03-31 00:00:00", totalOperateIncome = 5.1e10, parentNetProfit = 2.7e10,
                noticeDate = null, operateIncome = null, totalOperateCost = null,
                operateCost = null, saleExpense = null, manageExpense = null,
                financeExpense = null, operateProfit = null, totalProfit = null,
                incomeTax = null, deductParentNetProfit = null
            )
        )
        val cashFlow = listOf(
            CashFlowStatementResponse.Item(
                securityCode = "600519", securityName = "贵州茅台",
                reportDate = "2025-03-31 00:00:00", netcashOperate = 3.0e10, endCce = 2.0e11,
                netcashInvest = null, netcashFinance = null, cceAdd = null, receiveInvestIncome = null
            )
        )
        val balance = listOf(
            BalanceSheetFullResponse.Item(
                securityCode = "600519", securityName = "贵州茅台",
                reportDate = "2025-03-31 00:00:00", totalAssets = 2.5e11, debtAssetRatio = 20.0,
                totalLiabilities = null, totalEquity = null, monetaryFunds = null,
                accountsRece = null, inventory = null, accountsPayable = null, fixedAsset = null
            )
        )
        val r = FinancialStatementsBuilder.build(income, cashFlow, balance)!!
        val p = r.periods.single()
        assertThat(p.reportDate).isEqualTo("2025-03-31") // 时间后缀已去
        // 三表同报告期对齐
        assertThat(p.totalOperateIncome).isEqualTo(5.1e10)
        assertThat(p.netcashOperate).isEqualTo(3.0e10)
        assertThat(p.totalAssets).isEqualTo(2.5e11)
    }
}
