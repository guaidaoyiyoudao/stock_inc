package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TodayBriefingPromptBuilderTest {

    @Test
    fun containsPortfolioAndSignalLines() {
        val prompt = TodayBriefingPromptBuilder.build(
            portfolioLine = "组合今日 +0.80%（跑赢沪深300 0.50pp）",
            signals = listOf(
                TodaySignal(TodaySignalType.BUY_TRIGGER, "sh.600000", "Test", "三周期共振买入", "现价 8.80", 0)
            ),
            dividendLine = "未来30天1笔除权",
        )
        assertThat(prompt).contains("组合今日 +0.80%")
        assertThat(prompt).contains("Test三周期共振买入")
        assertThat(prompt).contains("50")
    }

    @Test
    fun emptySignals_rendersCalmLine() {
        val prompt = TodayBriefingPromptBuilder.build("组合今日 +0.10%", emptyList(), null)
        assertThat(prompt).contains("无显著信号")
    }

    @Test
    fun takesTopThreeSignals() {
        val signals = (1..5).map {
            TodaySignal(TodaySignalType.BUY_TRIGGER, "c$it", "S$it", "买入", "d$it", 0)
        }
        val prompt = TodayBriefingPromptBuilder.build("p", signals, null)
        assertThat(prompt).contains("S1")
        assertThat(prompt).contains("S3")
        assertThat(prompt).doesNotContain("S4")
    }

    @Test
    fun diagnosisAndMarketLines_appendedAsBlocks() {
        val prompt = TodayBriefingPromptBuilder.build(
            portfolioLine = "组合今日 +0.10%",
            signals = emptyList(),
            dividendLine = null,
            diagnosisLine = "加权股息率 4.00%，10Y国债 3.00%，利差+1.00pp",
            marketLine = "领涨板块 银行、白酒；领跌板块 煤炭",
        )
        assertThat(prompt).contains("【组合体检】加权股息率 4.00%")
        assertThat(prompt).contains("【市场】领涨板块 银行")
    }

    @Test
    fun nullDiagnosisAndMarket_omitsBlocks() {
        val prompt = TodayBriefingPromptBuilder.build("组合今日 +0.10%", emptyList(), null)
        assertThat(prompt).doesNotContain("【组合体检】")
        assertThat(prompt).doesNotContain("【市场】")
    }
}
