package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.local.entity.DividendEntity
import com.stock.dividend.data.plane.MarketDataPlane
import com.stock.dividend.data.local.entity.StockEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * [PortfolioDiagnosisAssembler] 单测（MockK mock 依赖）：
 * 装配口径（股息/派息率/连续年数/缺价跳过）与异常吞掉（红线 #2）。
 */
class PortfolioDiagnosisAssemblerTest {

    private val plane = mockk<MarketDataPlane>(relaxed = true)

    private fun stock(code: String, shares: Int = 100, industry: String = "银行") =
        StockEntity(code, code.substringAfter("."), "1", shares = shares, costPerShare = 9.0, industry = industry)

    private fun div(code: String, reportDate: String, cash: Double) = DividendEntity(
        id = "$code-$reportDate", stockCode = code, reportDate = reportDate, cashPerShare = cash
    )

    @Test
    fun `empty stocks returns null`() = runTest {
        val assembler = PortfolioDiagnosisAssembler(plane)
        assertThat(assembler.assemble(emptyList(), mapOf("sh.600036" to 10.0))).isNull()
    }

    @Test
    fun `holdings without price are skipped`() = runTest {
        coEvery { plane.getDividends(any()) } returns emptyList()
        val assembler = PortfolioDiagnosisAssembler(plane)
        // 2 只持仓只有 1 只有价 → 只诊断 1 只（现价缺失跳过，不臆造）
        val d = assembler.assemble(
            listOf(stock("sh.600036"), stock("sh.601166")),
            mapOf("sh.600036" to 10.0),
        )
        assertThat(d!!.holdingCount).isEqualTo(1)
    }

    @Test
    fun `all prices missing returns null`() = runTest {
        val assembler = PortfolioDiagnosisAssembler(plane)
        assertThat(assembler.assemble(listOf(stock("sh.600036")), emptyMap())).isNull()
    }

    @Test
    fun `assembles dividend metrics and yield`() = runTest {
        // 每股年分红 0.4（最新年）、连续 2 年记录 → 股息率 4%（0.4/10）
        coEvery { plane.getDividends("sh.600036") } returns
            listOf(div("sh.600036", "2025-12-31", 0.4), div("sh.600036", "2024-12-31", 0.38))
        coEvery { plane.getFundamentals("sh.600036") } returns null
        coEvery { plane.get10YBondYield(any()) } returns 3.0
        val assembler = PortfolioDiagnosisAssembler(plane)

        val d = assembler.assemble(listOf(stock("sh.600036")), mapOf("sh.600036" to 10.0))!!
        assertThat(d.holdingCount).isEqualTo(1)
        assertThat(d.totalMarketValue).isEqualTo(1000.0)
        // 年股息金额 = 0.4 × 100 股 = 40；组合股息率 = 40/1000 = 4%；利差 = 4 - 3 = 1
        assertThat(d.weightedDividendYieldPct).isEqualTo(4.0)
        assertThat(d.yieldSpreadPct).isEqualTo(1.0)
    }

    @Test
    fun `payout ratio enriched from fundamentals`() = runTest {
        coEvery { plane.getDividends("sh.600036") } returns
            listOf(div("sh.600036", "2025-12-31", 0.4))
        coEvery { plane.getFundamentals("sh.600036") } returns Fundamentals(
            periods = listOf(
                Fundamentals.Period(
                    reportDate = "2025-12-31", roe = null, debtToAssetRatio = null,
                    revenueYoy = null, netProfitYoy = null, basicEps = 0.3,
                    // 平面版 getFundamentals 已补派息率（0.4/0.3×100≈133.33），直接 stub 成品
                    payoutRatio = 133.33,
                )
            )
        )
        coEvery { plane.get10YBondYield(any()) } returns 3.0
        val assembler = PortfolioDiagnosisAssembler(plane)

        val d = assembler.assemble(listOf(stock("sh.600036")), mapOf("sh.600036" to 10.0))!!
        // 派息率 = 0.4/0.3×100 ≈ 133% > 100% → 进超标名单
        assertThat(d.highPayoutCodes).containsExactly("sh.600036")
    }

    @Test
    fun `bond yield failure degrades to null spread`() = runTest {
        coEvery { plane.getDividends(any()) } returns emptyList()
        coEvery { plane.get10YBondYield(any()) } throws RuntimeException("network down")
        val assembler = PortfolioDiagnosisAssembler(plane)

        val d = assembler.assemble(listOf(stock("sh.600036")), mapOf("sh.600036" to 10.0))!!
        // 国债拉取失败 → 利差 null（不臆造），诊断其余部分照常
        assertThat(d.bondYield10yPct).isNull()
        assertThat(d.yieldSpreadPct).isNull()
        assertThat(d.holdingCount).isEqualTo(1)
    }

    @Test
    fun `dividend source failure degrades to missing metrics`() = runTest {
        coEvery { plane.getDividends(any()) } throws RuntimeException("db broken")
        coEvery { plane.get10YBondYield(any()) } returns 3.0
        val assembler = PortfolioDiagnosisAssembler(plane)
        // 股息源炸掉 → 局部降级为空记录，诊断照常（红线 #2：吞异常返回安全值）
        val d = assembler.assemble(listOf(stock("sh.600036")), mapOf("sh.600036" to 10.0))
        assertThat(d).isNotNull()
        assertThat(d!!.holdingCount).isEqualTo(1)
        assertThat(d.weightedDividendYieldPct).isNull()
        assertThat(d.yieldSpreadPct).isNull()
    }

    @Test
    fun `ensures dividends freshness before reading - same policy as getDps`() = runTest {
        // 2026-08-20 审计 M7 修复：此前只读本地（getDividends 不触发 ensureFresh），
        // 从未刷新过分红的股票在诊断里股息率为 null，而同会话其他工具却有值
        coEvery { plane.getDividends(any()) } returns emptyList()
        coEvery { plane.get10YBondYield(any()) } returns 3.0

        PortfolioDiagnosisAssembler(plane).assemble(listOf(stock("sh.600036")), mapOf("sh.600036" to 10.0))

        coVerify(exactly = 1) { plane.ensureDividendsFresh("sh.600036") }
        coVerify(exactly = 1) { plane.getDividends("sh.600036") }
    }
}
