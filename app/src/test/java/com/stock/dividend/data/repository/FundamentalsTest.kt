package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.remote.dto.FundamentalResponse
import org.junit.Test

class FundamentalsTest {

    private fun item(
        date: String,
        roe: Double? = 10.0,
        debt: Double? = 60.0,
        revYoy: Double? = 8.0,
        npYoy: Double? = 5.0,
        eps: Double? = 1.0,
        announceYield: Double? = null,
        dividendPlan: String? = null
    ) = FundamentalResponse.Item(
        reportDate = date,
        weightedAvgRoe = roe,
        debtAssetRatio = debt,
        revenueYoy = revYoy,
        netProfitYoy = npYoy,
        basicEps = eps,
        announceYield = announceYield,
        dividendPlan = dividendPlan
    )

    // ---------- FundamentalsBuilder ----------

    @Test
    fun `build returns null for empty items`() {
        assertThat(FundamentalsBuilder.build(emptyList())).isNull()
    }

    @Test
    fun `build sorts periods ascending by report date`() {
        val f = FundamentalsBuilder.build(
            listOf(item("2024-12-31"), item("2023-12-31"), item("2024-06-30"))
        )!!
        assertThat(f.periods.map { it.reportDate }).containsExactly(
            "2023-12-31", "2024-06-30", "2024-12-31"
        ).inOrder()
    }

    @Test
    fun `build keeps latest maxN periods when more provided`() {
        val items = (0..6).map { i -> item("2023-06-${10 + i}") }
        val f = FundamentalsBuilder.build(items, maxN = 5)!!
        assertThat(f.periods).hasSize(5)
        // 保留最新 5 期（升序最后 5 个）
        assertThat(f.periods.last().reportDate).isEqualTo("2023-06-16")
    }

    @Test
    fun `build filters out items with blank report date`() {
        val nullDateItem = FundamentalResponse.Item(
            reportDate = null, weightedAvgRoe = null, debtAssetRatio = null,
            revenueYoy = null, netProfitYoy = null, basicEps = null
        )
        val f = FundamentalsBuilder.build(listOf(nullDateItem, item("2024-12-31")))!!
        assertThat(f.periods).hasSize(1)
        assertThat(f.periods[0].reportDate).isEqualTo("2024-12-31")
    }

    @Test
    fun `build sets payoutRatio to null for enrichment later`() {
        val f = FundamentalsBuilder.build(listOf(item("2024-12-31")))!!
        assertThat(f.periods[0].payoutRatio).isNull()
        assertThat(f.periods[0].roe).isEqualTo(10.0)
        assertThat(f.periods[0].debtToAssetRatio).isEqualTo(60.0)
    }

    @Test
    fun `build merges debt ratio from balance sheet when financial item lacks it`() {
        // 财务指标项无负债率字段（debt=null，与实测 RPT_LICO_FN_CPD 一致）
        val finItems = listOf(item("2024-12-31", debt = null))
        // 资产负债表含负债率；注意日期带时间后缀（实测格式）
        val balItems = listOf(
            com.stock.dividend.data.remote.dto.BalanceSheetResponse.Item(
                reportDate = "2024-12-31 00:00:00", debtAssetRatio = 91.2
            )
        )
        val f = FundamentalsBuilder.build(finItems, balItems)!!
        assertThat(f.periods[0].debtToAssetRatio).isEqualTo(91.2)
    }

    @Test
    fun `build keeps financial debt ratio when balance sheet has no matching period`() {
        val finItems = listOf(item("2024-12-31", debt = 60.0))
        val balItems = listOf(
            com.stock.dividend.data.remote.dto.BalanceSheetResponse.Item(
                reportDate = "2024-06-30 00:00:00", debtAssetRatio = 91.2
            )
        )
        val f = FundamentalsBuilder.build(finItems, balItems)!!
        // 资产负债表无 2024-12-31 这期，沿用财务项原值（null 时为 null，这里财务项有值 60.0）
        assertThat(f.periods[0].debtToAssetRatio).isEqualTo(60.0)
    }

    @Test
    fun `build with empty balance sheet yields null debt ratio when financial item lacks it`() {
        val finItems = listOf(item("2024-12-31", debt = null))
        val f = FundamentalsBuilder.build(finItems, balanceSheetItems = emptyList())!!
        assertThat(f.periods[0].debtToAssetRatio).isNull()
    }

    @Test
    fun `build dedupes balance sheet periods keeping the first by date`() {
        // 资产负债表同报告期可能多行；按 REPORT_DATE 倒序拉取后第一条为最新合并报表，groupBy 取首条
        val finItems = listOf(item("2024-12-31", debt = null))
        val balItems = listOf(
            com.stock.dividend.data.remote.dto.BalanceSheetResponse.Item("2024-12-31 00:00:00", 91.2),
            com.stock.dividend.data.remote.dto.BalanceSheetResponse.Item("2024-12-31 00:00:00", 99.9)
        )
        val f = FundamentalsBuilder.build(finItems, balItems)!!
        assertThat(f.periods[0].debtToAssetRatio).isEqualTo(91.2)
    }

    @Test
    fun `build normalizes financial reportDate with time suffix and merges debt ratio`() {
        // 真实接口格式：财务接口 REPORTDATE 带「 00:00:00」后缀，资产负债表 REPORT_DATE 也带后缀
        val finItems = listOf(item("2024-12-31 00:00:00", debt = null))
        val balItems = listOf(
            com.stock.dividend.data.remote.dto.BalanceSheetResponse.Item("2024-12-31 00:00:00", debtAssetRatio = 90.7)
        )
        val f = FundamentalsBuilder.build(finItems, balItems)!!
        // reportDate 应归一化为纯日期（无后缀），否则 UI 标签与下游匹配全错
        assertThat(f.periods[0].reportDate).isEqualTo("2024-12-31")
        // 负债率应能跨接口按报告期对齐合并（修复前因 key 不一致而为 null）
        assertThat(f.periods[0].debtToAssetRatio).isEqualTo(90.7)
    }

    @Test
    fun `build normalizes financial reportDate so payout ratio can match dividends`() {
        // 派息率匹配依赖 reportDate 与分红数据对齐：分红数据 reportDate 是纯日期（YYYY-12-31）
        // 财务接口若带时间后缀则 enrichPayoutRatio 查不到 → payoutRatio 永远 null（修复前 bug）
        val f = FundamentalsBuilder.build(listOf(item("2024-12-31 00:00:00", eps = 2.07)))!!
        assertThat(f.periods[0].reportDate).isEqualTo("2024-12-31")
        val enriched = enrichPayoutRatio(f, mapOf("2024-12-31" to 0.36))
        // 0.36 / 2.07 * 100 ≈ 17.39；修复 reportDate 归一化后才能匹配上
        assertThat(enriched.periods[0].payoutRatio).isNotNull()
        assertThat(enriched.periods[0].payoutRatio).isWithin(0.01).of(17.39)
    }

    @Test
    fun `build parses announce yield and dividend plan`() {
        val f = FundamentalsBuilder.build(
            listOf(item("2024-12-31", announceYield = 5.32, dividendPlan = "10派3.60元(含税)"))
        )!!
        assertThat(f.periods[0].announceYield).isEqualTo(5.32)
        assertThat(f.periods[0].dividendPlan).isEqualTo("10派3.60元(含税)")
    }

    @Test
    fun `build blanks out empty dividend plan text`() {
        val f = FundamentalsBuilder.build(
            listOf(item("2024-12-31", dividendPlan = "   "))
        )!!
        assertThat(f.periods[0].dividendPlan).isNull()
    }

    @Test
    fun `build tolerates null metric fields`() {
        val f = FundamentalsBuilder.build(
            listOf(item("2024-12-31", roe = null, debt = null, revYoy = null, npYoy = null, eps = null))
        )!!
        assertThat(f.periods[0].roe).isNull()
        assertThat(f.periods[0].debtToAssetRatio).isNull()
        assertThat(f.periods[0].revenueYoy).isNull()
    }

    // ---------- enrichPayoutRatio ----------

    @Test
    fun `enrichPayoutRatio computes payout as cashPerShare over eps times 100`() {
        val f = Fundamentals(
            periods = listOf(Fundamentals.Period("2024-12-31", 10.0, 60.0, 8.0, 5.0, basicEps = 1.20, payoutRatio = null))
        )
        val enriched = enrichPayoutRatio(f, mapOf("2024-12-31" to 0.30))
        // 0.30 / 1.20 * 100 = 25
        assertThat(enriched.periods[0].payoutRatio).isEqualTo(25.0)
    }

    @Test
    fun `enrichPayoutRatio returns null when eps missing`() {
        val f = Fundamentals(
            periods = listOf(Fundamentals.Period("2024-12-31", 10.0, 60.0, 8.0, 5.0, basicEps = null, payoutRatio = null))
        )
        val enriched = enrichPayoutRatio(f, mapOf("2024-12-31" to 0.30))
        assertThat(enriched.periods[0].payoutRatio).isNull()
    }

    @Test
    fun `enrichPayoutRatio returns null when eps is zero or negative`() {
        val cash = mapOf("2024-12-31" to 0.30)
        assertThat(
            Fundamentals(listOf(Fundamentals.Period("2024-12-31", 10.0, 60.0, 8.0, 5.0, basicEps = 0.0, payoutRatio = null)))
                .let { enrichPayoutRatio(it, cash) }.periods[0].payoutRatio
        ).isNull()
        assertThat(
            Fundamentals(listOf(Fundamentals.Period("2024-12-31", 10.0, 60.0, 8.0, 5.0, basicEps = -1.0, payoutRatio = null)))
                .let { enrichPayoutRatio(it, cash) }.periods[0].payoutRatio
        ).isNull()
    }

    @Test
    fun `enrichPayoutRatio returns null when no matching cash per share`() {
        val f = Fundamentals(
            periods = listOf(Fundamentals.Period("2024-12-31", 10.0, 60.0, 8.0, 5.0, basicEps = 1.20, payoutRatio = null))
        )
        val enriched = enrichPayoutRatio(f, emptyMap())
        assertThat(enriched.periods[0].payoutRatio).isNull()
    }

    @Test
    fun `enrichPayoutRatio leaves other metrics untouched`() {
        val f = Fundamentals(
            periods = listOf(Fundamentals.Period("2024-12-31", 10.0, 60.0, 8.0, 5.0, basicEps = 1.20, payoutRatio = null))
        )
        val enriched = enrichPayoutRatio(f, mapOf("2024-12-31" to 0.30))
        assertThat(enriched.periods[0].roe).isEqualTo(10.0)
        assertThat(enriched.periods[0].debtToAssetRatio).isEqualTo(60.0)
        assertThat(enriched.periods[0].basicEps).isEqualTo(1.20)
    }

    // ---------- formatFundamentalsPeriod ----------

    @Test
    fun `formatPeriod maps standard quarter months to Q labels`() {
        assertThat(formatFundamentalsPeriod("2024-03-31")).isEqualTo("24Q1")
        assertThat(formatFundamentalsPeriod("2024-06-30")).isEqualTo("24Q2")
        assertThat(formatFundamentalsPeriod("2024-09-30")).isEqualTo("24Q3")
        assertThat(formatFundamentalsPeriod("2024-12-31")).isEqualTo("24Q4")
    }

    @Test
    fun `formatPeriod falls back to year-month for non-standard date`() {
        assertThat(formatFundamentalsPeriod("2024-07-15")).isEqualTo("2407")
    }

    @Test
    fun `formatPeriod returns input when unparseable`() {
        assertThat(formatFundamentalsPeriod("2024")).isEqualTo("2024")
    }

    // ---------- fundamentalsTrend ----------

    @Test
    fun `trend is Insufficient when fewer than two valid samples`() {
        val f = Fundamentals(periods = listOf(Fundamentals.Period("2024-12-31", 10.0, 60.0, 8.0, 5.0, null)))
        assertThat(fundamentalsTrend(f) { it.roe }).isEqualTo(FundamentalsTrend.Insufficient)
    }

    @Test
    fun `trend is Up when relative delta exceeds threshold`() {
        val f = Fundamentals(periods = listOf(
            Fundamentals.Period("2023-12-31", 10.0, 60.0, 8.0, 5.0, null),
            Fundamentals.Period("2024-12-31", 15.0, 60.0, 8.0, 5.0, null)
        ))
        assertThat(fundamentalsTrend(f) { it.roe }).isEqualTo(FundamentalsTrend.Up)
    }

    @Test
    fun `trend is Down when relative delta below negative threshold`() {
        val f = Fundamentals(periods = listOf(
            Fundamentals.Period("2023-12-31", 10.0, 60.0, 8.0, 5.0, null),
            Fundamentals.Period("2024-12-31", 9.0, 60.0, 8.0, 5.0, null)
        ))
        assertThat(fundamentalsTrend(f) { it.roe }).isEqualTo(FundamentalsTrend.Down)
    }

    @Test
    fun `trend is Flat when relative delta within threshold`() {
        val f = Fundamentals(periods = listOf(
            Fundamentals.Period("2023-12-31", 10.0, 60.0, 8.0, 5.0, null),
            Fundamentals.Period("2024-12-31", 10.2, 60.0, 8.0, 5.0, null)
        ))
        assertThat(fundamentalsTrend(f) { it.roe }).isEqualTo(FundamentalsTrend.Flat)
    }

    @Test
    fun `trend skips periods with null selected value`() {
        val f = Fundamentals(periods = listOf(
            Fundamentals.Period("2023-12-31", null, 60.0, 8.0, 5.0, null),
            Fundamentals.Period("2024-12-31", 10.0, 60.0, 8.0, 5.0, null),
            Fundamentals.Period("2025-12-31", 16.0, 60.0, 8.0, 5.0, null)
        ))
        // 前两期有有效 ROE：10.0 -> 16.0，明显上升
        assertThat(fundamentalsTrend(f) { it.roe }).isEqualTo(FundamentalsTrend.Up)
    }
}
