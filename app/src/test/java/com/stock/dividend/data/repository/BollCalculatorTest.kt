package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.sqrt

class BollCalculatorTest {

    @Test
    fun `normal 20 closes produce upper greater than middle greater than lower`() {
        // 1..20，均值=10.5，σ=样本标准差
        val closes = (1..20).map { it.toDouble() }

        val band = BollCalculator.calculate(closes)

        assertThat(band).isNotNull()
        band!!
        assertThat(band.middle).isWithin(1e-9).of(10.5)
        assertThat(band.upper).isGreaterThan(band.middle)
        assertThat(band.lower).isLessThan(band.middle)
        // upper - middle == middle - lower（对称）
        assertThat(band.upper - band.middle).isWithin(1e-9).of(band.middle - band.lower)
    }

    @Test
    fun `middle equals simple moving average and sigma uses n-1 denominator`() {
        val closes = (1..20).map { it.toDouble() }
        val expectedMean = closes.average() // 10.5
        val expectedSigma = sqrt(closes.sumOf { (it - expectedMean) * (it - expectedMean) } / (closes.size - 1))

        val band = BollCalculator.calculate(closes)!!

        assertThat(band.middle).isWithin(1e-9).of(expectedMean)
        assertThat(band.upper).isWithin(1e-9).of(expectedMean + 2.0 * expectedSigma)
        assertThat(band.lower).isWithin(1e-9).of(expectedMean - 2.0 * expectedSigma)
    }

    @Test
    fun `uses only the last period closes when more are provided`() {
        // 给 30 根，应只用最后 20 根（11..30，均值=20.5）
        val closes = (1..30).map { it.toDouble() }

        val band = BollCalculator.calculate(closes)!!

        assertThat(band.middle).isWithin(1e-9).of(20.5)
    }

    @Test
    fun `returns null when fewer than period closes`() {
        val closes = (1..19).map { it.toDouble() }

        assertThat(BollCalculator.calculate(closes)).isNull()
    }

    @Test
    fun `returns null for empty list`() {
        assertThat(BollCalculator.calculate(emptyList())).isNull()
    }

    @Test
    fun `returns null when window contains non-positive price`() {
        val closes = (1..19).map { it.toDouble() } + listOf(0.0)

        assertThat(BollCalculator.calculate(closes)).isNull()
    }

    @Test
    fun `returns null when window contains NaN`() {
        val closes = (1..19).map { it.toDouble() } + listOf(Double.NaN)

        assertThat(BollCalculator.calculate(closes)).isNull()
    }

    @Test
    fun `returns equal upper middle lower when sigma is zero`() {
        // 20 根相同价格 → σ=0，三轨重合（合法极端情况）
        val closes = List(20) { 10.0 }

        val band = BollCalculator.calculate(closes)!!

        assertThat(band.middle).isEqualTo(10.0)
        assertThat(band.upper).isWithin(1e-9).of(10.0)
        assertThat(band.lower).isWithin(1e-9).of(10.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects period leq 1`() {
        BollCalculator.calculate(List(5) { 1.0 }, period = 1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects non-positive stdDevMult`() {
        BollCalculator.calculate(List(20) { 1.0 }, mult = 0.0)
    }

    @Test
    fun `respects custom period and mult`() {
        val closes = (1..10).map { it.toDouble() }
        val expectedMean = closes.average() // 5.5
        val expectedSigma = sqrt(closes.sumOf { (it - expectedMean) * (it - expectedMean) } / 9.0)

        val band = BollCalculator.calculate(closes, period = 10, mult = 3.0)!!

        assertThat(band.period).isEqualTo(10)
        assertThat(band.stdDevMult).isEqualTo(3.0)
        assertThat(band.upper).isWithin(1e-9).of(expectedMean + 3.0 * expectedSigma)
    }
}
