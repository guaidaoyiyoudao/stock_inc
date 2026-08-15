package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.local.entity.DividendEntity
import com.stock.dividend.data.local.entity.StockEntity
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * [PortfolioDiagnosisAssembler] 单测（MockK mock 依赖）：
 * 装配口径（股息/派息率/连续年数/缺价跳过）与异常吞掉（红线 #2）。
 */
class PortfolioDiagnosisAssemblerTest {

    private val divRepo = mockk<DividendRepository>(relaxed = true)
    private val fundRepo = mockk<FundamentalsCacheRepository>(relaxed = true)
    private val bondRepo = mockk<BondYieldRepository>(relaxed = true)

    private fun stock(code: String, shares: Int = 100, industry: String = "银行") =
        StockEntity(code, code.substringAfter("."), "1", shares = shares, costPerShare = 9.0, industry = industry)

    private fun div(code: String, reportDate: String, cash: Double) = DividendEntity(
        id = "$code-$reportDate", stockCode = code, reportDate = reportDate, cashPerShare = cash
    )

    @Test
    fun `empty stocks returns null`() = runTest {
        val assembler = PortfolioDiagnosisAssembler(divRepo, fundRepo, bondRepo)
        assertThat(assembler.assemble(emptyList(), mapOf("sh.600036" to 10.0))).isNull()
    }

    @Test
    fun `holdings without price are skipped`() = runTest {
        coEvery { divRepo.observeDividends(any()) } returns flowOf(emptyList<DividendEntity>())
        val assembler = PortfolioDiagnosisAssembler(divRepo, fundRepo, bondRepo)
        // 2 只持仓只有 1 只有价 → 只诊断 1 只（现价缺失跳过，不臆造）
        val d = assembler.assemble(
            listOf(stock("sh.600036"), stock("sh.601166")),
            mapOf("sh.600036" to 10.0),
        )
        assertThat(d!!.holdingCount).isEqualTo(1)
    }

    @Test
    fun `all prices missing returns null`() = runTest {
        val assembler = PortfolioDiagnosisAssembler(divRepo, fundRepo, bondRepo)
        assertThat(assembler.assemble(listOf(stock("sh.600036")), emptyMap())).isNull()
    }

    @Test
    fun `assembles dividend metrics and yield`() = runTest {
        // 每股年分红 0.4（最新年）、连续 2 年记录 → 股息率 4%（0.4/10）
        coEvery { divRepo.observeDividends("sh.600036") } returns flowOf(
            listOf(div("sh.600036", "2025-12-31", 0.4), div("sh.600036", "2024-12-31", 0.38))
        )
        coEvery { fundRepo.getFundamentals("sh.600036") } returns null
        coEvery { bondRepo.fetch10YBondYield(false) } returns 3.0
        val assembler = PortfolioDiagnosisAssembler(divRepo, fundRepo, bondRepo)

        val d = assembler.assemble(listOf(stock("sh.600036")), mapOf("sh.600036" to 10.0))!!
        assertThat(d.holdingCount).isEqualTo(1)
        assertThat(d.totalMarketValue).isEqualTo(1000.0)
        // 年股息金额 = 0.4 × 100 股 = 40；组合股息率 = 40/1000 = 4%；利差 = 4 - 3 = 1
        assertThat(d.weightedDividendYieldPct).isEqualTo(4.0)
        assertThat(d.yieldSpreadPct).isEqualTo(1.0)
    }

    @Test
    fun `payout ratio enriched from fundamentals`() = runTest {
        coEvery { divRepo.observeDividends("sh.600036") } returns flowOf(
            listOf(div("sh.600036", "2025-12-31", 0.4))
        )
        coEvery { fundRepo.getFundamentals("sh.600036") } returns Fundamentals(
            periods = listOf(
                Fundamentals.Period(
                    reportDate = "2025-12-31", roe = null, debtToAssetRatio = null,
                    revenueYoy = null, netProfitYoy = null, basicEps = 0.3,
                )
            )
        )
        coEvery { bondRepo.fetch10YBondYield(false) } returns 3.0
        val assembler = PortfolioDiagnosisAssembler(divRepo, fundRepo, bondRepo)

        val d = assembler.assemble(listOf(stock("sh.600036")), mapOf("sh.600036" to 10.0))!!
        // 派息率 = 0.4/0.3×100 ≈ 133% > 100% → 进超标名单
        assertThat(d.highPayoutCodes).containsExactly("sh.600036")
    }

    @Test
    fun `bond yield failure degrades to null spread`() = runTest {
        coEvery { divRepo.observeDividends(any()) } returns flowOf(emptyList<DividendEntity>())
        coEvery { bondRepo.fetch10YBondYield(false) } throws RuntimeException("network down")
        val assembler = PortfolioDiagnosisAssembler(divRepo, fundRepo, bondRepo)

        val d = assembler.assemble(listOf(stock("sh.600036")), mapOf("sh.600036" to 10.0))!!
        // 国债拉取失败 → 利差 null（不臆造），诊断其余部分照常
        assertThat(d.bondYield10yPct).isNull()
        assertThat(d.yieldSpreadPct).isNull()
        assertThat(d.holdingCount).isEqualTo(1)
    }

    @Test
    fun `dividend source failure degrades to missing metrics`() = runTest {
        coEvery { divRepo.observeDividends(any()) } throws RuntimeException("db broken")
        coEvery { bondRepo.fetch10YBondYield(false) } returns 3.0
        val assembler = PortfolioDiagnosisAssembler(divRepo, fundRepo, bondRepo)
        // 股息源炸掉 → 局部降级为空记录，诊断照常（红线 #2：吞异常返回安全值）
        val d = assembler.assemble(listOf(stock("sh.600036")), mapOf("sh.600036" to 10.0))
        assertThat(d).isNotNull()
        assertThat(d!!.holdingCount).isEqualTo(1)
        assertThat(d.weightedDividendYieldPct).isNull()
        assertThat(d.yieldSpreadPct).isNull()
    }
}
