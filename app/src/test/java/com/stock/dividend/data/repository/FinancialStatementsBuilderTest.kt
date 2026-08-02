package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.remote.dto.BalanceSheetFullResponse
import com.stock.dividend.data.remote.dto.CashFlowStatementResponse
import com.stock.dividend.data.remote.dto.IncomeStatementResponse
import org.junit.Test

class FinancialStatementsBuilderTest {

    @Test
    fun `build returns null when income empty`() {
        val r = FinancialStatementsBuilder.build(
            income = emptyList(),
            cashFlow = emptyList(),
            balance = emptyList()
        )
        assertThat(r).isNull()
    }

    @Test
    fun `build aligns three statements by reportDate and strips time suffix`() {
        // 三表报告期都带 " 00:00:00" 后缀，须归一化后对齐
        val income = listOf(
            IncomeStatementResponse.Item(
                securityCode = "600519", securityName = "贵州茅台",
                reportDate = "2024-12-31 00:00:00",
                totalOperateIncome = 1.7e10, parentNetProfit = 8.6e9,
                noticeDate = null, operateIncome = null, totalOperateCost = null,
                operateCost = null, saleExpense = null, manageExpense = null,
                financeExpense = null, operateProfit = null, totalProfit = null,
                incomeTax = null, deductParentNetProfit = null
            ),
            IncomeStatementResponse.Item(
                securityCode = "600519", securityName = "贵州茅台",
                reportDate = "2023-12-31 00:00:00",
                totalOperateIncome = 1.5e10, parentNetProfit = 7.5e9,
                noticeDate = null, operateIncome = null, totalOperateCost = null,
                operateCost = null, saleExpense = null, manageExpense = null,
                financeExpense = null, operateProfit = null, totalProfit = null,
                incomeTax = null, deductParentNetProfit = null
            )
        )
        val cashFlow = listOf(
            CashFlowStatementResponse.Item(
                securityCode = "600519", securityName = "贵州茅台",
                reportDate = "2024-12-31 00:00:00",
                netcashOperate = 9.0e9, endCce = 1.0e10,
                netcashInvest = null, netcashFinance = null, cceAdd = null, receiveInvestIncome = null
            )
        )
        val balance = listOf(
            BalanceSheetFullResponse.Item(
                securityCode = "600519", securityName = "贵州茅台",
                reportDate = "2024-12-31 00:00:00",
                totalAssets = 2.5e11, totalLiabilities = 5.0e10, debtAssetRatio = 20.0,
                totalEquity = null, monetaryFunds = null, accountsRece = null,
                inventory = null, accountsPayable = null, fixedAsset = null
            )
        )

        val r = FinancialStatementsBuilder.build(income, cashFlow, balance)!!
        // 升序：旧→新
        assertThat(r.periods).hasSize(2)
        assertThat(r.periods[0].reportDate).isEqualTo("2023-12-31")
        assertThat(r.periods[1].reportDate).isEqualTo("2024-12-31")
        // 2024 期对齐了现金流与资产负债表
        val latest = r.periods[1]
        assertThat(latest.totalOperateIncome).isWithin(1.0).of(1.7e10)
        assertThat(latest.parentNetProfit).isWithin(1.0).of(8.6e9)
        assertThat(latest.netcashOperate).isWithin(1.0).of(9.0e9)
        assertThat(latest.endCce).isWithin(1.0).of(1.0e10)
        assertThat(latest.totalAssets).isWithin(1.0).of(2.5e11)
        assertThat(latest.totalLiabilities).isWithin(1.0).of(5.0e10)
        // 2023 期无对应现金流/资产负债表数据 → null（不臆造）
        val early = r.periods[0]
        assertThat(early.netcashOperate).isNull()
        assertThat(early.totalAssets).isNull()
        assertThat(early.parentNetProfit).isWithin(1.0).of(7.5e9)
    }

    @Test
    fun `build respects maxN keeping latest N periods`() {
        val income = (2020..2024).map { y ->
            IncomeStatementResponse.Item(
                securityCode = "600519", securityName = "贵州茅台",
                reportDate = "$y-12-31 00:00:00",
                totalOperateIncome = y.toDouble() * 1e9,
                noticeDate = null, operateIncome = null, totalOperateCost = null,
                operateCost = null, saleExpense = null, manageExpense = null,
                financeExpense = null, operateProfit = null, totalProfit = null,
                incomeTax = null, parentNetProfit = null, deductParentNetProfit = null
            )
        }
        val r = FinancialStatementsBuilder.build(income, emptyList(), emptyList(), maxN = 3)!!
        assertThat(r.periods).hasSize(3)
        assertThat(r.periods.first().reportDate).isEqualTo("2022-12-31")
        assertThat(r.periods.last().reportDate).isEqualTo("2024-12-31")
    }
}
