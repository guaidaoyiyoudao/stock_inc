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
    private val fuyaoApi: com.stock.dividend.data.remote.FuyaoApi = mockk()
    private val fuyaoConfig: FuyaoConfig = mockk(relaxed = true)
    private val fundamentalApi: FundamentalApi = mockk()
    private val gson = Gson()
    private val repository = FinancialStatementsRepository(dao, fuyaoApi, fuyaoConfig, fundamentalApi)

    @org.junit.Before
    fun setUp() {
        // 默认扶摇未配置（relaxed Boolean=false），存量用例全部走东财现状路径
        io.mockk.coEvery { fuyaoConfig.enabled } returns false
        io.mockk.coEvery { dao.get(any()) } returns null
    }

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

    // ── 扶摇主源 + 东财并行补缺科目 ─────────────────────────────

    @Test
    fun `fuyao statements primary with eastmoney subject supplement`() = runTest {
        coEvery { fuyaoConfig.enabled } returns true
        // 扶摇三表（茅台 2026Q2，报告期取 period_end_ms=2026-06-30）
        coEvery { fuyaoApi.getIncomeStatements(thscode = any(), period = any(), limit = any()) } returns
            com.stock.dividend.data.remote.dto.FuyaoEnvelope(code = 0, message = "s", requestId = "t",
                data = com.stock.dividend.data.remote.dto.FuyaoStatementsData(item = listOf(
                    com.stock.dividend.data.remote.dto.FuyaoIncomeItem(
                        periodEndMs = msOf("2026-06-30"), operatingIncome = 51180000000.0,
                        operatingCosts = 5100000000.0, operatingProfit = 38800000000.0,
                        profitTotal = 39000000000.0, incomeTaxExpense = 9000000000.0,
                        parentNetProfit = 29900000000.0
                    )
                )))
        coEvery { fuyaoApi.getBalanceSheets(thscode = any(), period = any(), limit = any()) } returns
            com.stock.dividend.data.remote.dto.FuyaoEnvelope(code = 0, message = "s", requestId = "t",
                data = com.stock.dividend.data.remote.dto.FuyaoStatementsData(item = listOf(
                    com.stock.dividend.data.remote.dto.FuyaoBalanceSheetItem(
                        periodEndMs = msOf("2026-06-30"), assetsTotal = 309050784569.31,
                        totalDebt = 46954432394.95, holderEquityTotal = 262096352174.36,
                        cash = 53518798979.08
                    )
                )))
        coEvery { fuyaoApi.getCashFlowStatements(thscode = any(), period = any(), limit = any()) } returns
            com.stock.dividend.data.remote.dto.FuyaoEnvelope(code = 0, message = "s", requestId = "t",
                data = com.stock.dividend.data.remote.dto.FuyaoStatementsData(item = listOf(
                    com.stock.dividend.data.remote.dto.FuyaoCashFlowItem(
                        periodEndMs = msOf("2026-06-30"), operateNetCash = 70690750119.06
                    )
                )))
        // 东财并行：提供扶摇缺的财务费用/扣非/期末现金/存货/应付/固定资产
        coEvery { fundamentalApi.getIncomeStatement(filter = any()) } returns IncomeStatementResponse(
            success = true, result = IncomeStatementResponse.IncomeResult(data = listOf(
                IncomeStatementResponse.Item(
                    securityCode = null, securityName = null, reportDate = "2026-06-30 00:00:00",
                    noticeDate = null, totalOperateIncome = 51179999999.0, operateIncome = null,
                    totalOperateCost = null, operateCost = null, saleExpense = null, manageExpense = null,
                    financeExpense = 120000000.0, operateProfit = null, totalProfit = null,
                    incomeTax = null, parentNetProfit = null, deductParentNetProfit = 29800000000.0
                )
            ))
        )
        coEvery { fundamentalApi.getCashFlowStatement(filter = any()) } returns
            com.stock.dividend.data.remote.dto.CashFlowStatementResponse(
                success = true,
                result = com.stock.dividend.data.remote.dto.CashFlowStatementResponse.CashFlowResult(
                    data = listOf(
                        com.stock.dividend.data.remote.dto.CashFlowStatementResponse.Item(
                            securityCode = null, securityName = null, reportDate = "2026-06-30 00:00:00",
                            netcashOperate = 70690750119.06, netcashInvest = null, netcashFinance = null,
                            cceAdd = null, endCce = 61000000000.0, receiveInvestIncome = null
                        )
                    )
                )
            )
        coEvery { fundamentalApi.getBalanceSheetFull(filter = any()) } returns
            com.stock.dividend.data.remote.dto.BalanceSheetFullResponse(
                success = true,
                result = com.stock.dividend.data.remote.dto.BalanceSheetFullResponse.BalanceSheetFullResult(
                    data = listOf(
                        com.stock.dividend.data.remote.dto.BalanceSheetFullResponse.Item(
                            securityCode = null, securityName = null, reportDate = "2026-06-30 00:00:00",
                            totalAssets = null, totalLiabilities = null, totalEquity = null,
                            monetaryFunds = null, accountsRece = null, inventory = 40000000000.0,
                            accountsPayable = 5000000000.0, fixedAsset = 20000000000.0,
                            debtAssetRatio = null
                        )
                    )
                )
            )
        val payloadSlot = slot<com.stock.dividend.data.local.entity.FinancialStatementsCacheEntity>()
        coEvery { dao.upsert(capture(payloadSlot)) } returns Unit

        val result = repository.getFinancialStatements("sh.600519")

        val p = result!!.periods.single()
        // 扶摇为权威（东财同期值不覆盖）；营业总收入例外——扶摇无该口径（审计 M1），由东财回填
        assertThat(p.reportDate).isEqualTo("2026-06-30")
        assertThat(p.totalOperateIncome).isWithin(0.01).of(51179999999.0)
        assertThat(p.totalAssets).isWithin(0.01).of(309050784569.31)
        // 东财补齐缺科目
        assertThat(p.financeExpense).isWithin(0.01).of(120000000.0)
        assertThat(p.deductParentNetProfit).isWithin(0.01).of(29800000000.0)
        assertThat(p.endCce).isWithin(0.01).of(61000000000.0)
        assertThat(p.inventory).isWithin(0.01).of(40000000000.0)
        assertThat(p.accountsPayable).isWithin(0.01).of(5000000000.0)
        assertThat(p.fixedAsset).isWithin(0.01).of(20000000000.0)
        // 合并结果写缓存
        coVerify(exactly = 1) { dao.upsert(any()) }
    }

    private fun msOf(date: String): Long = java.time.LocalDate.parse(date)
        .atStartOfDay(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()
}
