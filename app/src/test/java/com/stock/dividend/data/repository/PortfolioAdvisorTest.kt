package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PortfolioAdvisorTest {

    private fun stock(
        code: String,
        priceVsLower: Double = 0.1,
        yield: Double? = 3.0,
        price: Double = 10.0,
        band: BollBand? = null
    ) = EvaluatedStock(
        code = code, name = code, industry = "",
        action = HoldingAction.HOLD, priceVsLower = priceVsLower,
        dividendYield = yield, bollBand = band, currentPrice = price,
        reasons = emptyList()
    )

    private val lowerBand = BollBand(middle = 10.0, upper = 11.0, lower = 9.0)
    private val midBand = BollBand(middle = 12.0, upper = 13.0, lower = 11.0)

    @Test
    fun `position control triggers when majority at upper and low yield`() {
        val stocks = listOf(
            stock("a", priceVsLower = 0.95, yield = 1.5), stock("b", priceVsLower = 0.95, yield = 1.5),
            stock("c", priceVsLower = 0.2, yield = 1.0)
        )
        val sig = PortfolioAdvisor.evaluate(stocks, emptyMap(), emptyMap())
        assertThat(sig.positionControl.triggered).isTrue()
        assertThat(sig.positionControl.targetCashPercent).isEqualTo(15)
        assertThat(sig.positionControl.upperBandRatio).isWithin(0.01).of(2.0 / 3.0)
    }

    @Test
    fun `position control not triggered when yield high`() {
        val stocks = listOf(stock("a", 0.95, yield = 4.0), stock("b", 0.95, yield = 4.0))
        val sig = PortfolioAdvisor.evaluate(stocks, emptyMap(), emptyMap())
        assertThat(sig.positionControl.triggered).isFalse()
    }

    @Test
    fun `position control not triggered when few at upper`() {
        val stocks = listOf(stock("a", 0.95), stock("b", 0.2), stock("c", 0.2))
        val sig = PortfolioAdvisor.evaluate(stocks, emptyMap(), emptyMap())
        assertThat(sig.positionControl.triggered).isFalse()
    }

    @Test
    fun `resonant buy signal when daily lower weekly lower monthly below middle`() {
        // price 8.5 <= daily.lower 9, <= weekly.lower 9, < monthly.middle 12
        val stocks = listOf(stock("a", price = 8.5, band = lowerBand))
        val daily = mapOf("a" to lowerBand)
        val monthly = mapOf("a" to midBand)
        val sig = PortfolioAdvisor.evaluate(stocks, daily, monthly)
        assertThat(sig.buySignals).hasSize(1)
        assertThat(sig.buySignals.first().code).isEqualTo("a")
        assertThat(sig.buySignals.first().resonant).isTrue()
    }

    @Test
    fun `no resonant signal when monthly at or above middle`() {
        // price 8.5: daily<=9 ✓, weekly<=9 ✓, but monthly middle=8.0 → 8.5 >= 8.0 → not below middle
        val stocks = listOf(stock("a", price = 8.5, band = lowerBand))
        val daily = mapOf("a" to lowerBand)
        val monthly = mapOf("a" to BollBand(middle = 8.0, upper = 9.0, lower = 7.0))
        val sig = PortfolioAdvisor.evaluate(stocks, daily, monthly)
        assertThat(sig.buySignals).isEmpty()
    }

    @Test
    fun `missing monthly band skips resonance for that stock`() {
        val stocks = listOf(stock("a", price = 8.5, band = lowerBand))
        val daily = mapOf("a" to lowerBand)
        val monthly = mapOf<String, BollBand?>("a" to null)
        val sig = PortfolioAdvisor.evaluate(stocks, daily, monthly)
        assertThat(sig.buySignals).isEmpty()
    }

    @Test
    fun `empty stocks yields no signals`() {
        val sig = PortfolioAdvisor.evaluate(emptyList(), emptyMap(), emptyMap())
        assertThat(sig.positionControl.triggered).isFalse()
        assertThat(sig.buySignals).isEmpty()
    }
}
