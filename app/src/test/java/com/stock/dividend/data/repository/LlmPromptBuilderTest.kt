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
    ) = LlmPromptBuilder.build(stocks, daily, monthly, signals, DividendThresholds())

    @Test
    fun `system prompt states JSON schema and constraints`() {
        val p = prompt(listOf(stock("600036")))
        assertThat(p.system).contains("overview")
        assertThat(p.system).contains("stockComments")
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
        // price 9.5；日 band(9..11)→距下轨 25%；周 priceVsLower=0.1→10%；月 band middle=12→<中轨
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
        val p = prompt(listOf(stock("600036")))  // 空 daily/monthly map
        assertThat(p.user).contains("日距下轨 —")
    }

    @Test
    fun `empty stocks still produces valid prompt`() {
        val p = prompt(emptyList())
        assertThat(p.system).isNotEmpty()
        assertThat(p.user).isNotEmpty()
    }

    // ===== 回流：全局用户投资原则 =====

    @Test
    fun `userStrategies rendered globally without sourceNote`() {
        val p = LlmPromptBuilder.build(
            listOf(stock("600036")), emptyMap(), emptyMap(), noSignals, DividendThresholds(),
            listOf(UserStrategyRef("BUY", "ROE高", emptyList(), null, 5))
        )
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
