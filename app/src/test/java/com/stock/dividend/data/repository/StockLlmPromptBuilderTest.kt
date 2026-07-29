package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StockLlmPromptBuilderTest {

    private fun fullInput() = StockLlmInput(
        code = "600036",
        name = "招商银行",
        industry = "银行",
        currentPrice = 12.34,
        dividendRatePoints = listOf(3.2, 3.5, 3.8, 4.1),
        latestDividendYield = 4.1,
        forecast = StockLlmInput.StockLlmForecast(1.80, 1.65, 1.50, 4),
        buyThreshold = StockLlmInput.StockLlmBuyThreshold(6.5, 4.1, reached = false),
        bollDaily = StockLlmInput.StockLlmBollPosition(30),
        bollWeekly = StockLlmInput.StockLlmBollPosition(25),
        bollMonthly = StockLlmInput.StockLlmBollPosition(60),
    )

    private fun prompt(input: StockLlmInput) = StockLlmPromptBuilder.build(input)

    @Test
    fun `system prompt states JSON schema fields and constraints`() {
        val p = prompt(fullInput())
        assertThat(p.system).contains("valuation")
        assertThat(p.system).contains("dividendSustainability")
        assertThat(p.system).contains("action")
        assertThat(p.system).contains("risks")
        assertThat(p.system).contains("仅基于")
    }

    @Test
    fun `user message includes code name price three-period boll and dividend metrics`() {
        val p = prompt(fullInput())
        assertThat(p.user).contains("600036")
        assertThat(p.user).contains("招商银行")
        assertThat(p.user).contains("12.34")
        assertThat(p.user).contains("日距下轨")
        assertThat(p.user).contains("周距下轨")
        assertThat(p.user).contains("月距下轨")
        assertThat(p.user).contains("4.1%")
        // 分红率趋势序列中至少出现一个历史点
        assertThat(p.user).contains("3.2%")
    }

    @Test
    fun `user message includes forecast and buy threshold`() {
        val p = prompt(fullInput())
        assertThat(p.user).contains("1年均每股")
        assertThat(p.user).contains("1.80")
        assertThat(p.user).contains("实际样本 4 年")
        assertThat(p.user).contains("买入线")
        assertThat(p.user).contains("6.5%")
        assertThat(p.user).contains("未达标")
    }

    @Test
    fun `user message excludes cost basis`() {
        val p = prompt(fullInput())
        assertThat(p.user).doesNotContain("成本")
        assertThat(p.user).doesNotContain("cost")
    }

    @Test
    fun `missing boll periods show dash`() {
        val input = fullInput().copy(bollDaily = null, bollMonthly = null)
        val p = prompt(input)
        assertThat(p.user).contains("日距下轨 —")
        assertThat(p.user).contains("月距下轨 —")
        // 周线仍有值
        assertThat(p.user).contains("周距下轨 25%")
    }

    @Test
    fun `empty dividend rate points show dash and still valid`() {
        val input = fullInput().copy(dividendRatePoints = emptyList())
        val p = prompt(input)
        assertThat(p.user).contains("【分红率趋势】—")
        assertThat(p.system).isNotEmpty()
    }

    @Test
    fun `null dividend rate points show dash and still valid`() {
        val input = fullInput().copy(dividendRatePoints = null)
        val p = prompt(input)
        assertThat(p.user).contains("【分红率趋势】—")
    }

    @Test
    fun `null forecast and buy threshold show dash and still valid`() {
        val input = fullInput().copy(forecast = null, buyThreshold = null)
        val p = prompt(input)
        assertThat(p.user).contains("【预测】—")
        assertThat(p.user).contains("【买入线】—")
        assertThat(p.user).isNotEmpty()
    }

    @Test
    fun `rising trend is annotated in user message`() {
        val input = fullInput().copy(dividendRatePoints = listOf(2.0, 3.0, 4.0, 5.0))
        val p = prompt(input)
        assertThat(p.user).contains("整体上升")
    }
}
