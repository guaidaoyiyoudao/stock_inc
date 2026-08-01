package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LlmPromptBuilderTest {

    private fun stock(code: String, action: HoldingAction = HoldingAction.BUY) = EvaluatedStock(
        code = code, name = "n$code", industry = "银行",
        action = action, priceVsLower = 0.1, dividendYield = 4.2,
        bollBand = null, currentPrice = 10.0, reasons = listOf("接近下轨")
    )

    private val noSignals = PortfolioSignals(
        positionControl = PositionControlSignal(false, 0.0, 0.0, 15),
        buySignals = emptyList()
    )

    private fun prompt(
        stocks: List<EvaluatedStock>,
        signals: PortfolioSignals = noSignals,
        daily: Map<String, BollBand?> = emptyMap(),
        monthly: Map<String, BollBand?> = emptyMap(),
        details: Map<String, PortfolioLlmStockDetail> = emptyMap(),
    ) = LlmPromptBuilder.build(
        PortfolioLlmInput(
            evaluation = stocks,
            dailyBands = daily,
            monthlyBands = monthly,
            signals = signals,
            thresholds = DividendThresholds(),
            stockDetails = details
        )
    )

    @Test
    fun `system prompt states JSON schema and constraints`() {
        val p = prompt(listOf(stock("600036")))
        assertThat(p.system).contains("overview")
        assertThat(p.system).contains("stockComments")
        assertThat(p.system).contains("brief")
        assertThat(p.system).contains("risks")
        assertThat(p.system).contains("仅基于")
    }

    @Test
    fun `user message includes each stock code action and metrics`() {
        val p = prompt(listOf(stock("600036")))
        assertThat(p.user).contains("600036")
        assertThat(p.user).contains("买")
        assertThat(p.user).contains("4.2")
    }

    @Test
    fun `position control signal surfaces cash hint in user message`() {
        val sig = PortfolioSignals(
            positionControl = PositionControlSignal(true, 0.6, 1.5, 15),
            buySignals = emptyList()
        )
        val p = prompt(listOf(stock("600036")), sig)
        assertThat(p.user).contains("15")
        assertThat(p.user).contains("控仓")
    }

    @Test
    fun `resonant buy codes listed in user message`() {
        val sig = PortfolioSignals(
            positionControl = PositionControlSignal(false, 0.0, 0.0, 15),
            buySignals = listOf(
                MultiTimeframeBuySignal("600036", true, true, true, true)
            )
        )
        val p = prompt(listOf(stock("600036")), sig)
        assertThat(p.user).contains("600036")
        assertThat(p.user).contains("共振")
    }

    @Test
    fun `user message excludes cost basis`() {
        val p = prompt(listOf(stock("600036")))
        assertThat(p.user).doesNotContain("成本")
        assertThat(p.user).doesNotContain("cost")
    }

    @Test
    fun `three-period positions appear when bands present`() {
        val daily = mapOf("600036" to BollBand(middle = 10.0, upper = 11.0, lower = 9.0))
        val monthly = mapOf("600036" to BollBand(middle = 12.0, upper = 14.0, lower = 10.0))
        val s = stock("600036").copy(currentPrice = 9.5, bollBand = BollBand(10.0, 11.0, 9.0))
        val p = prompt(listOf(s), daily = daily, monthly = monthly)
        assertThat(p.user).contains("日距下轨")
        assertThat(p.user).contains("周距下轨")
        assertThat(p.user).contains("月距下轨")
    }

    @Test
    fun `missing bands show dash for that period`() {
        val p = prompt(listOf(stock("600036")))
        assertThat(p.user).contains("日距下轨 —")
    }

    @Test
    fun `empty stocks still produces valid prompt`() {
        val p = prompt(emptyList())
        assertThat(p.system).isNotEmpty()
        assertThat(p.user).isNotEmpty()
    }

    @Test
    fun `deep data rendered per stock`() {
        val detail = PortfolioLlmStockDetail(
            fundamentals = Fundamentals(
                periods = listOf(
                    Fundamentals.Period(
                        reportDate = "2025-03-31", roe = 12.0, debtToAssetRatio = 60.0,
                        revenueYoy = 8.0, netProfitYoy = 5.0, payoutRatio = 25.0,
                        dividendPlan = "10派3.60元(含税)"
                    )
                )
            ),
            forecast = StockLlmInput.StockLlmForecast(
                avgCashPerShare1Y = 0.5, avgCashPerShare3Y = 0.6,
                avgCashPerShare5Y = 0.7, actualYears = 5
            ),
            buyThreshold = StockLlmInput.StockLlmBuyThreshold(
                targetYieldPercent = 6.5, currentYieldPercent = 4.2, reached = false
            )
        )
        val p = prompt(listOf(stock("600036")), details = mapOf("600036" to detail))
        assertThat(p.user).contains("ROE 12.0%")
        assertThat(p.user).contains("负债率 60.0%")
        assertThat(p.user).contains("营收 +8.0%")
        assertThat(p.user).contains("净利 +5.0%")
        assertThat(p.user).contains("派息率 25.0%")
        assertThat(p.user).contains("10派3.60元(含税)")
        assertThat(p.user).contains("1年均 ¥0.50")
        assertThat(p.user).contains("5年均 ¥0.70")
        assertThat(p.user).contains("样本 5 年")
        assertThat(p.user).contains("目标 6.5%")
        assertThat(p.user).contains("未达标")
    }

    @Test
    fun `missing deep data shows dashes`() {
        val p = prompt(listOf(stock("600036")))
        assertThat(p.user).contains("基本面 — / 预测 — / 买入线 —")
    }

    @Test
    fun `fundamentals with missing metrics render dash`() {
        val detail = PortfolioLlmStockDetail(
            fundamentals = Fundamentals(
                periods = listOf(
                    Fundamentals.Period("2025-03-31", roe = null, debtToAssetRatio = null,
                        revenueYoy = null, netProfitYoy = null, payoutRatio = null)
                )
            )
        )
        val p = prompt(listOf(stock("600036")), details = mapOf("600036" to detail))
        assertThat(p.user).contains("ROE —")
        assertThat(p.user).contains("派息率 —")
    }

    @Test
    fun `userStrategies rendered globally without sourceNote`() {
        val input = PortfolioLlmInput(
            evaluation = listOf(stock("600036")),
            dailyBands = emptyMap(),
            monthlyBands = emptyMap(),
            signals = noSignals,
            thresholds = DividendThresholds(),
            userStrategies = listOf(UserStrategyRef("BUY", "ROE高", emptyList(), null, 5))
        )
        val p = LlmPromptBuilder.build(input)
        assertThat(p.user).contains("用户投资原则")
        assertThat(p.user).contains("[买入]")
        assertThat(p.user).contains("5天前")
    }

    @Test
    fun `system contains user strategy semantics`() {
        val p = prompt(listOf(stock("600036")))
        assertThat(p.system).contains("用户投资原则")
    }
}
