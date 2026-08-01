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
        fundamentals = Fundamentals(
            periods = listOf(
                Fundamentals.Period("2023-12-31", roe = 12.0, debtToAssetRatio = 60.0, revenueYoy = 10.0, netProfitYoy = 7.0, basicEps = 1.0, payoutRatio = 28.0, dividendPlan = "10派2.00元(含税)"),
                Fundamentals.Period("2024-03-31", roe = 11.2, debtToAssetRatio = 62.0, revenueYoy = 8.0, netProfitYoy = 5.0, basicEps = 1.0, payoutRatio = 30.0, dividendPlan = "10派3.60元(含税)")
            )
        ),
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

    @Test
    fun `system prompt states fundamentals semantics for sustainability`() {
        val p = prompt(fullInput())
        assertThat(p.system).contains("ROE")
        assertThat(p.system).contains("资产负债率")
        assertThat(p.system).contains("派息率")
        assertThat(p.system).contains("分红方案")
        // dividendSustainability 字段提示升级为结合基本面
        assertThat(p.system).contains("ROE/派息率/成长性")
    }

    @Test
    fun `user message renders fundamentals periods with metrics`() {
        val p = prompt(fullInput())
        assertThat(p.user).contains("【基本面（近2期）】")
        assertThat(p.user).contains("2023-12-31")
        assertThat(p.user).contains("ROE 12.0%")
        assertThat(p.user).contains("负债率 60%")
        // 同比带正负号
        assertThat(p.user).contains("营收+10.0%")
        assertThat(p.user).contains("净利+7.0%")
        assertThat(p.user).contains("派息率 28%")
    }

    @Test
    fun `user message renders dividend plan per period`() {
        val p = prompt(fullInput())
        assertThat(p.user).contains("10派2.00元(含税)")
        assertThat(p.user).contains("10派3.60元(含税)")
    }

    @Test
    fun `null fundamentals render dash in user message`() {
        val input = fullInput().copy(fundamentals = null)
        val p = prompt(input)
        assertThat(p.user).contains("【基本面（近0期）】—")
    }

    @Test
    fun `fundamentals with missing metrics render dash per field`() {
        val input = fullInput().copy(
            fundamentals = Fundamentals(
                periods = listOf(
                    Fundamentals.Period("2024-03-31", roe = null, debtToAssetRatio = null, revenueYoy = null, netProfitYoy = null, basicEps = null, payoutRatio = null)
                )
            )
        )
        val p = prompt(input)
        assertThat(p.user).contains("ROE —")
        assertThat(p.user).contains("营收—")
        assertThat(p.user).contains("派息率 —")
    }

    @Test
    fun `negative yoy renders with minus sign`() {
        val input = fullInput().copy(
            fundamentals = Fundamentals(
                periods = listOf(
                    Fundamentals.Period("2024-03-31", roe = 9.0, debtToAssetRatio = 65.0, revenueYoy = -2.5, netProfitYoy = -3.0, basicEps = 1.0, payoutRatio = 35.0)
                )
            )
        )
        val p = prompt(input)
        assertThat(p.user).contains("营收-2.5%")
        assertThat(p.user).contains("净利-3.0%")
    }

    // ===== 回流：全局用户投资原则 =====

    @Test
    fun `userStrategies rendered without sourceNote`() {
        val u = StockLlmPromptBuilder.build(fullInput(), listOf(
            UserStrategyRef("BUY", "ROE高", listOf("息差收窄"), "2026-09-01", 3)
        )).user
        assertThat(u).contains("用户投资原则")
        assertThat(u).contains("[买入]")
        assertThat(u).contains("ROE高")
        assertThat(u).contains("3天前")
        assertThat(u).contains("息差收窄")
    }

    @Test
    fun `userStrategies empty renders dash`() {
        // 单参 build 默认空策略列表
        val u = StockLlmPromptBuilder.build(fullInput()).user
        assertThat(u).contains("用户投资原则")
        assertThat(u).contains("—")
    }

    @Test
    fun `system contains user strategy semantics`() {
        val s = StockLlmPromptBuilder.build(fullInput()).system
        assertThat(s).contains("用户投资原则")
    }
}
