package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * [PortfolioRiskDiagnoser] 纯函数单测：组合风险全景诊断（集中度/股息可持续性/估值水位）。
 *
 * 每个用例锁定一条规则；权重/HHI/CR 均为手算金标准。
 * HHI 采用 0-10000 标度（权重百分数的平方和，如单一持仓=10000）。
 */
class PortfolioRiskDiagnoserTest {

    private fun holding(
        code: String,
        marketValue: Double,
        industry: String? = "银行",
        annualDividend: Double? = null,
        consecutiveYears: Int? = 5,
        payoutRatio: Double? = 30.0,
        name: String? = code,
    ) = DiagnoseHolding(
        code = code,
        name = name,
        industry = industry,
        marketValue = marketValue,
        annualDividend = annualDividend,
        consecutiveYears = consecutiveYears,
        payoutRatio = payoutRatio,
    )

    @Test
    fun `empty holdings returns null`() {
        assertThat(PortfolioRiskDiagnoser.diagnose(emptyList(), bondYield10yPct = 3.0)).isNull()
    }

    @Test
    fun `zero market value returns null`() {
        val d = PortfolioRiskDiagnoser.diagnose(
            listOf(holding("600036", 0.0)), bondYield10yPct = 3.0
        )
        assertThat(d).isNull()
    }

    @Test
    fun `single holding computes full concentration and warns`() {
        val d = PortfolioRiskDiagnoser.diagnose(
            listOf(holding("600036", 100_000.0, industry = "银行")),
            bondYield10yPct = 3.0,
        )!!
        assertThat(d.holdingCount).isEqualTo(1)
        assertThat(d.stockCr1).isEqualTo(100.0)
        assertThat(d.stockCr3).isEqualTo(100.0)
        assertThat(d.stockHhi).isEqualTo(10000.0)
        assertThat(d.industryHhi).isEqualTo(10000.0)
        assertThat(d.industryCr3).isEqualTo(100.0)
        // 单一持仓必触发：行业集中 + 个股集中两条建议
        assertThat(d.suggestions.any { it.contains("行业高度集中") }).isTrue()
        assertThat(d.suggestions.any { it.contains("600036") && it.contains("30%") }).isTrue()
    }

    @Test
    fun `concentrated industries trigger hhi warning`() {
        // 两行业 70/30：HHI = 70² + 30² = 5800 > 2500 → 触发；CR3 = 100
        val d = PortfolioRiskDiagnoser.diagnose(
            listOf(
                holding("600036", 70_000.0, industry = "银行"),
                holding("600519", 30_000.0, industry = "白酒"),
            ),
            bondYield10yPct = 3.0,
        )!!
        assertThat(d.industryHhi).isEqualTo(5800.0)
        assertThat(d.industryCr3).isEqualTo(100.0)
        assertThat(d.topIndustries.first().name).isEqualTo("银行")
        assertThat(d.topIndustries.first().weightPct).isEqualTo(70.0)
        assertThat(d.suggestions.any { it.contains("行业高度集中") }).isTrue()
    }

    @Test
    fun `blank industry grouped as unclassified`() {
        val d = PortfolioRiskDiagnoser.diagnose(
            listOf(
                holding("600036", 50_000.0, industry = " 银行 "),
                holding("000001", 50_000.0, industry = null),
            ),
            bondYield10yPct = 3.0,
        )!!
        assertThat(d.topIndustries.map { it.name }).containsExactly("银行", "未分类").inOrder()
    }

    @Test
    fun `diversified portfolio yields no risk suggestions`() {
        // 5 行业各 20%、单股 CR1=20%：HHI=2000 ≤2500 不触发行业，CR1≤30% 不触发个股
        val d = PortfolioRiskDiagnoser.diagnose(
            listOf(
                holding("600036", 20_000.0, industry = "银行"),
                holding("600519", 20_000.0, industry = "白酒"),
                holding("601088", 20_000.0, industry = "煤炭"),
                holding("600900", 20_000.0, industry = "电力"),
                holding("000333", 20_000.0, industry = "家电"),
            ),
            bondYield10yPct = null,
        )!!
        assertThat(d.industryHhi).isEqualTo(2000.0)
        assertThat(d.industryCr3).isEqualTo(60.0)
        assertThat(d.suggestions).containsExactly("组合结构均衡，未触发明显风险规则")
    }

    @Test
    fun `dividend source concentration computed from annual dividend amounts`() {
        // 股息金额 45/30/15/10：CR3 = (45+30+15)/100 = 90% > 80% → 触发
        val d = PortfolioRiskDiagnoser.diagnose(
            listOf(
                holding("A", 40_000.0, industry = "银行", annualDividend = 45.0 * 100),
                holding("B", 30_000.0, industry = "煤炭", annualDividend = 30.0 * 100),
                holding("C", 20_000.0, industry = "电力", annualDividend = 15.0 * 100),
                holding("D", 10_000.0, industry = "家电", annualDividend = 10.0 * 100),
            ),
            bondYield10yPct = 3.0,
        )!!
        assertThat(d.dividendSourceCr3).isEqualTo(90.0)
        assertThat(d.suggestions.any { it.contains("股息来源集中") }).isTrue()
    }

    @Test
    fun `dividend source excludes holdings without dividend data`() {
        // 仅 2 只有股息数据：CR3 按「有数据的持仓内」占比算 = 100% → 触发集中提示
        val d = PortfolioRiskDiagnoser.diagnose(
            listOf(
                holding("A", 50_000.0, industry = "银行", annualDividend = 500.0),
                holding("B", 50_000.0, industry = "煤炭", annualDividend = 300.0),
                holding("C", 50_000.0, industry = "电力", annualDividend = null),
            ),
            bondYield10yPct = 3.0,
        )!!
        assertThat(d.dividendSourceCr3).isEqualTo(100.0)
        // 无数据的 C 不进入股息来源计算
        assertThat(d.suggestions.any { it.contains("股息来源集中") }).isTrue()
    }

    @Test
    fun `fragile dividend weight counts short-history and unknown holdings`() {
        // 无记录(null) + 连续 2 年 共 60 权重 > 40% → 触发
        val d = PortfolioRiskDiagnoser.diagnose(
            listOf(
                holding("A", 30_000.0, industry = "银行", consecutiveYears = null),
                holding("B", 30_000.0, industry = "煤炭", consecutiveYears = 2),
                holding("C", 40_000.0, industry = "电力", consecutiveYears = 10),
            ),
            bondYield10yPct = 3.0,
        )!!
        assertThat(d.fragileDividendWeightPct).isEqualTo(60.0)
        assertThat(d.suggestions.any { it.contains("连续分红不足") }).isTrue()
    }

    @Test
    fun `high payout ratio holdings flagged`() {
        val d = PortfolioRiskDiagnoser.diagnose(
            listOf(
                holding("A", 50_000.0, industry = "银行", payoutRatio = 130.0),
                holding("B", 50_000.0, industry = "煤炭", payoutRatio = 40.0),
            ),
            bondYield10yPct = 3.0,
        )!!
        assertThat(d.highPayoutCodes).containsExactly("A")
        assertThat(d.suggestions.any { it.contains("派息率超 100%") && it.contains("A") }).isTrue()
    }

    @Test
    fun `negative yield spread vs bond warns no advantage`() {
        // 加权股息率 = 3000/100000 = 3.0% < 国债 3.5% → 负利差
        val d = PortfolioRiskDiagnoser.diagnose(
            listOf(holding("A", 100_000.0, industry = "银行", annualDividend = 3000.0)),
            bondYield10yPct = 3.5,
        )!!
        assertThat(d.weightedDividendYieldPct).isEqualTo(3.0)
        assertThat(d.yieldSpreadPct).isEqualTo(-0.5)
        assertThat(d.suggestions.any { it.contains("低于 10Y 国债") }).isTrue()
    }

    @Test
    fun `thin positive spread warns weak margin`() {
        // 利差 0.5%：3.5% vs 3.0%
        val d = PortfolioRiskDiagnoser.diagnose(
            listOf(holding("A", 100_000.0, industry = "银行", annualDividend = 3500.0)),
            bondYield10yPct = 3.0,
        )!!
        assertThat(d.yieldSpreadPct).isEqualTo(0.5)
        assertThat(d.suggestions.any { it.contains("安全边际偏薄") }).isTrue()
        assertThat(d.suggestions.any { it.contains("低于 10Y 国债") }).isFalse()
    }

    @Test
    fun `healthy spread produces no yield warning`() {
        val d = PortfolioRiskDiagnoser.diagnose(
            listOf(holding("A", 100_000.0, industry = "银行", annualDividend = 6000.0)),
            bondYield10yPct = 3.0,
        )!!
        assertThat(d.yieldSpreadPct).isEqualTo(3.0)
        assertThat(d.suggestions.any { it.contains("国债") }).isFalse()
    }

    @Test
    fun `missing bond yield leaves spread null and no warning`() {
        val d = PortfolioRiskDiagnoser.diagnose(
            listOf(holding("A", 100_000.0, industry = "银行", annualDividend = 6000.0)),
            bondYield10yPct = null,
        )!!
        assertThat(d.bondYield10yPct).isNull()
        assertThat(d.yieldSpreadPct).isNull()
        assertThat(d.suggestions.none { it.contains("国债") }).isTrue()
    }

    @Test
    fun `all dividends missing yields null metrics not fabricated`() {
        val d = PortfolioRiskDiagnoser.diagnose(
            listOf(
                holding("A", 50_000.0, industry = "银行", annualDividend = null),
                holding("B", 50_000.0, industry = "煤炭", annualDividend = null),
            ),
            bondYield10yPct = 3.0,
        )!!
        assertThat(d.weightedDividendYieldPct).isNull()
        assertThat(d.yieldSpreadPct).isNull()
        assertThat(d.dividendSourceCr3).isNull()
        assertThat(d.suggestions.none { it.contains("股息来源") }).isTrue()
    }

    @Test
    fun `weighted dividend yield uses market value weights`() {
        // 2 只：股息 500/100000=0.5%、600/40000=1.5% → 加权 = (500+600)/140000 = 1100/140000 ≈ 0.7857%
        val d = PortfolioRiskDiagnoser.diagnose(
            listOf(
                holding("A", 100_000.0, industry = "银行", annualDividend = 500.0),
                holding("B", 40_000.0, industry = "煤炭", annualDividend = 600.0),
            ),
            bondYield10yPct = 3.0,
        )!!
        // 1100/140000 = 0.7857…%，输出保留 2 位小数
        assertThat(d.weightedDividendYieldPct).isWithin(1e-9).of(0.79)
    }

    @Test
    fun `stock cr3 computed on holdings sorted by weight`() {
        // 权重 50/30/20 → CR1=50（>30 触发）、CR3=100
        val d = PortfolioRiskDiagnoser.diagnose(
            listOf(
                holding("600036", 50_000.0, industry = "银行"),
                holding("600519", 30_000.0, industry = "银行"),
                holding("601088", 20_000.0, industry = "银行"),
            ),
            bondYield10yPct = 3.0,
        )!!
        assertThat(d.stockCr1).isEqualTo(50.0)
        assertThat(d.stockCr3).isEqualTo(100.0)
        assertThat(d.topHoldings.map { it.name }).containsExactly("600036", "600519", "601088").inOrder()
    }
}
