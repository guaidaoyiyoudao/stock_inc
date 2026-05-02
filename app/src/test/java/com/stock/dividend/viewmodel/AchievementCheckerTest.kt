package com.stock.dividend.viewmodel

import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.local.entity.StockEntity
import org.junit.Test

class AchievementCheckerTest {

    private val testStock = StockEntity(
        code = "sh.600000",
        name = "浦发银行",
        marketCode = "1",
        shares = 1000
    )

    private fun emptyCtx() = AchievementChecker.CheckContext(
        stocks = emptyList(),
        yearlyTotals = emptyMap(),
        hasAnyIncomeRecord = false
    )

    @Test
    fun `no achievements with empty context`() {
        val result = AchievementChecker.check(emptyCtx())
        assertThat(result).isEmpty()
    }

    @Test
    fun `FIRST_DIVIDEND unlocked when has income record`() {
        val result = AchievementChecker.check(emptyCtx().copy(hasAnyIncomeRecord = true))
        assertThat(result).contains("first_dividend")
    }

    @Test
    fun `FIRST_DIVIDEND not unlocked when no income`() {
        val result = AchievementChecker.check(emptyCtx())
        assertThat(result).doesNotContain("first_dividend")
    }

    @Test
    fun `INCOME_1K unlocked when max yearly total reaches 1000`() {
        val result = AchievementChecker.check(
            emptyCtx().copy(yearlyTotals = mapOf(2024 to 1500.0))
        )
        assertThat(result).contains("income_1k")
    }

    @Test
    fun `INCOME_10K unlocked when max yearly total reaches 10000`() {
        val result = AchievementChecker.check(
            emptyCtx().copy(yearlyTotals = mapOf(2024 to 12000.0))
        )
        assertThat(result).contains("income_1k")
        assertThat(result).contains("income_10k")
    }

    @Test
    fun `INCOME_100K unlocked when max yearly total reaches 100000`() {
        val result = AchievementChecker.check(
            emptyCtx().copy(yearlyTotals = mapOf(2024 to 150000.0))
        )
        assertThat(result).contains("income_1k")
        assertThat(result).contains("income_10k")
        assertThat(result).contains("income_100k")
    }

    @Test
    fun `PORTFOLIO_START unlocked when has stocks`() {
        val result = AchievementChecker.check(
            emptyCtx().copy(stocks = listOf(testStock))
        )
        assertThat(result).contains("portfolio_start")
    }

    @Test
    fun `DIVERSIFY_5 unlocked when has 5+ stocks`() {
        val stocks = (1..5).map { i ->
            testStock.copy(code = "sh.60000$i", name = "股票$i")
        }
        val result = AchievementChecker.check(emptyCtx().copy(stocks = stocks))
        assertThat(result).contains("portfolio_start")
        assertThat(result).contains("diversify_5")
    }

    @Test
    fun `DIVERSIFY_5 not unlocked with 4 stocks`() {
        val stocks = (1..4).map { i ->
            testStock.copy(code = "sh.60000$i", name = "股票$i")
        }
        val result = AchievementChecker.check(emptyCtx().copy(stocks = stocks))
        assertThat(result).doesNotContain("diversify_5")
    }

    @Test
    fun `HOLD_1Y unlocked when earliest stock added over 1 year ago`() {
        val oneYearAgo = System.currentTimeMillis() - 400L * 24 * 3600 * 1000
        val result = AchievementChecker.check(
            emptyCtx().copy(stocks = listOf(testStock.copy(addedAt = oneYearAgo)))
        )
        assertThat(result).contains("hold_1y")
    }

    @Test
    fun `HOLD_1Y not unlocked when stock added recently`() {
        val recent = System.currentTimeMillis() - 100L * 24 * 3600 * 1000
        val result = AchievementChecker.check(
            emptyCtx().copy(stocks = listOf(testStock.copy(addedAt = recent)))
        )
        assertThat(result).doesNotContain("hold_1y")
    }

    @Test
    fun `STREAK_3Y unlocked with 3 consecutive years`() {
        val result = AchievementChecker.check(
            emptyCtx().copy(yearlyTotals = mapOf(2022 to 100.0, 2023 to 200.0, 2024 to 300.0))
        )
        assertThat(result).contains("streak_3y")
    }

    @Test
    fun `STREAK_3Y not unlocked with non-consecutive years`() {
        val result = AchievementChecker.check(
            emptyCtx().copy(yearlyTotals = mapOf(2021 to 100.0, 2023 to 200.0, 2024 to 300.0))
        )
        assertThat(result).doesNotContain("streak_3y")
    }

    @Test
    fun `STREAK_3Y not unlocked with only 2 years`() {
        val result = AchievementChecker.check(
            emptyCtx().copy(yearlyTotals = mapOf(2023 to 100.0, 2024 to 200.0))
        )
        assertThat(result).doesNotContain("streak_3y")
    }
}
