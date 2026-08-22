package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.stock.dividend.data.local.dao.FinancialStatementsCacheDao
import com.stock.dividend.data.local.entity.FinancialStatementsCacheEntity
import com.stock.dividend.data.remote.FundamentalApi
import com.stock.dividend.data.remote.dto.IncomeStatementResponse
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.IOException

class FinancialStatementsRepositoryTest {

    private val dao: FinancialStatementsCacheDao = mockk()
    private val fundamentalApi: FundamentalApi = mockk()
    private val gson = Gson()
    private val repository = FinancialStatementsRepository(dao, fundamentalApi)

    /** FinancialStatements.Period 无默认值，测试用命名参数填全（未涉及字段 null）。 */
    private fun period(reportDate: String, income: Double? = 1.0, cash: Double? = 2.0, assets: Double? = 3.0) =
        FinancialStatements.Period(
            reportDate = reportDate,
            totalOperateIncome = income, operateCost = null, saleExpense = null, manageExpense = null,
            financeExpense = null, operateProfit = null, totalProfit = null, incomeTax = null,
            parentNetProfit = null, deductParentNetProfit = null,
            netcashOperate = cash, netcashInvest = null, netcashFinance = null, endCce = null,
            totalAssets = assets, totalLiabilities = null, totalEquity = null, monetaryFunds = null,
            accountsRece = null, inventory = null, accountsPayable = null, fixedAsset = null
        )

    private fun entity(fs: FinancialStatements, fetchedAt: Long = System.currentTimeMillis()) =
        FinancialStatementsCacheEntity(stockCode = "sh.600036", payload = gson.toJson(fs), fetchedAt = fetchedAt)

    private fun incomeItem(reportDate: String, income: Double?) = IncomeStatementResponse.Item(
        securityCode = null, securityName = null, reportDate = reportDate, noticeDate = null,
        totalOperateIncome = income, operateIncome = null, totalOperateCost = null, operateCost = null,
        saleExpense = null, manageExpense = null, financeExpense = null, operateProfit = null,
        totalProfit = null, incomeTax = null, parentNetProfit = null, deductParentNetProfit = null
    )

    /** 网络路径：利润表真实构造（merge 断言用）；现金/资产负债表抛异常走降级（fetchFromNetwork 内 runCatching 吞）。 */
    private fun stubNetwork(incomeItems: List<IncomeStatementResponse.Item>) {
        coEvery { fundamentalApi.getIncomeStatement(filter = any()) } returns IncomeStatementResponse(
            success = true, result = IncomeStatementResponse.IncomeResult(data = incomeItems)
        )
        coEvery { fundamentalApi.getCashFlowStatement(filter = any()) } throws IOException("cash down")
        coEvery { fundamentalApi.getBalanceSheetFull(filter = any()) } throws IOException("balance down")
    }

    @Test
    fun `fresh cache returns without network`() = runTest {
        coEvery { dao.get("sh.600036") } returns entity(FinancialStatements(listOf(period("2025-12-31"))))

        val result = repository.getFinancialStatements("sh.600036")

        assertThat(result!!.periods.single().totalOperateIncome).isEqualTo(1.0)
        coVerify(exactly = 0) { fundamentalApi.getIncomeStatement(filter = any()) }
    }

    @Test
    fun `network failure falls back to stale cache`() = runTest {
        coEvery { dao.get("sh.600036") } returns entity(
            FinancialStatements(listOf(period("2024-12-31"))),
            fetchedAt = System.currentTimeMillis() - 8L * 24 * 60 * 60 * 1000
        )
        coEvery { fundamentalApi.getIncomeStatement(filter = any()) } throws IOException("down")
        coEvery { fundamentalApi.getCashFlowStatement(filter = any()) } throws IOException("down")
        coEvery { fundamentalApi.getBalanceSheetFull(filter = any()) } throws IOException("down")

        val result = repository.getFinancialStatements("sh.600036")

        assertThat(result).isNotNull()
        assertThat(result!!.periods.single().reportDate).isEqualTo("2024-12-31")
    }

    @Test
    fun `no cache and network failure yields null`() = runTest {
        coEvery { dao.get("sh.600036") } returns null
        coEvery { fundamentalApi.getIncomeStatement(filter = any()) } throws IOException("down")
        coEvery { fundamentalApi.getCashFlowStatement(filter = any()) } throws IOException("down")
        coEvery { fundamentalApi.getBalanceSheetFull(filter = any()) } throws IOException("down")

        assertThat(repository.getFinancialStatements("sh.600036")).isNull()
    }

    @Test
    fun `stale refresh merges and preserves older cached periods`() = runTest {
        val cachedFs = FinancialStatements(
            listOf(period("2023-12-31"), period("2024-12-31", income = 9.9))
        )
        coEvery { dao.get("sh.600036") } returns entity(
            cachedFs, fetchedAt = System.currentTimeMillis() - 8L * 24 * 60 * 60 * 1000
        )
        stubNetwork(
            listOf(incomeItem("2024-12-31 00:00:00", 11.0), incomeItem("2025-12-31 00:00:00", 12.0))
        )
        val payloadSlot = slot<FinancialStatementsCacheEntity>()
        coEvery { dao.upsert(capture(payloadSlot)) } returns Unit

        val result = repository.getFinancialStatements("sh.600036")

        // 2023 期从缓存续接、2024 期被远端覆盖、2025 期新增
        assertThat(result!!.periods.map { it.reportDate }).containsExactly(
            "2023-12-31", "2024-12-31", "2025-12-31"
        ).inOrder()
        assertThat(result.periods[1].totalOperateIncome).isEqualTo(11.0)
        // 落库 payload 同样保留全部历史期次
        val persisted = gson.fromJson(payloadSlot.captured.payload, FinancialStatements::class.java)
        assertThat(persisted.periods).hasSize(3)
    }

    @Test
    fun `remote null statement fields fall back to cached same-period values`() = runTest {
        // 2026-08-20 审计 M5：cash/balance 子接口失败（stubNetwork 降级空）时远端同期该表字段全 null——
        // 修复前整期覆盖会把缓存里原本齐全的科目抹掉且随后被持久化（无法自愈）；修复后字段级回退
        coEvery { dao.get("sh.600036") } returns entity(
            FinancialStatements(listOf(period("2024-12-31", income = 100.0, cash = 200.0, assets = 300.0))),
            fetchedAt = System.currentTimeMillis() - 8L * 24 * 60 * 60 * 1000
        )
        // 远端：利润表成功（新值 111），现金/资产负债表失败 → netcashOperate/totalAssets 为 null
        stubNetwork(listOf(incomeItem("2024-12-31 00:00:00", 111.0)))

        val result = repository.getFinancialStatements("sh.600036")!!

        val merged = result.periods.first { it.reportDate == "2024-12-31" }
        assertThat(merged.totalOperateIncome).isEqualTo(111.0) // 远端有值 → 远端覆盖
        assertThat(merged.netcashOperate).isEqualTo(200.0)     // 远端 null → 缓存保底
        assertThat(merged.totalAssets).isEqualTo(300.0)
    }
}
