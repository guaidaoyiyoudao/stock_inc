package com.stock.dividend.viewmodel

import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.local.entity.FireGoalEntity
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
        hasAnyIncomeRecord = false,
        incomeRecordCount = 0,
        maxSingleIncome = 0.0,
        perStockYearlyIncome = emptyMap(),
        fireGoal = null,
        forecastTotal = 0.0
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

    // --- Recording Habit ---

    @Test
    fun `RECORD_10 unlocked when income record count reaches 10`() {
        val result = AchievementChecker.check(emptyCtx().copy(incomeRecordCount = 10))
        assertThat(result).contains("record_10")
    }

    @Test
    fun `RECORD_10 not unlocked when income record count is 9`() {
        val result = AchievementChecker.check(emptyCtx().copy(incomeRecordCount = 9))
        assertThat(result).doesNotContain("record_10")
    }

    @Test
    fun `RECORD_50 unlocked when income record count reaches 50`() {
        val result = AchievementChecker.check(emptyCtx().copy(incomeRecordCount = 50))
        assertThat(result).contains("record_50")
    }

    @Test
    fun `RECORD_50 not unlocked when income record count is 49`() {
        val result = AchievementChecker.check(emptyCtx().copy(incomeRecordCount = 49))
        assertThat(result).doesNotContain("record_50")
    }

    // --- Income Breakthrough ---

    @Test
    fun `SINGLE_100 unlocked when max single income reaches 100`() {
        val result = AchievementChecker.check(emptyCtx().copy(maxSingleIncome = 150.0))
        assertThat(result).contains("single_100")
    }

    @Test
    fun `SINGLE_100 not unlocked when max single income is 99`() {
        val result = AchievementChecker.check(emptyCtx().copy(maxSingleIncome = 99.0))
        assertThat(result).doesNotContain("single_100")
    }

    @Test
    fun `YOY_GROWTH_50 unlocked when year-over-year growth exceeds 50 percent`() {
        val result = AchievementChecker.check(
            emptyCtx().copy(yearlyTotals = mapOf(2023 to 1000.0, 2024 to 1600.0))
        )
        assertThat(result).contains("yoy_growth_50")
    }

    @Test
    fun `YOY_GROWTH_50 not unlocked when growth is exactly 50 percent`() {
        val result = AchievementChecker.check(
            emptyCtx().copy(yearlyTotals = mapOf(2023 to 1000.0, 2024 to 1500.0))
        )
        assertThat(result).doesNotContain("yoy_growth_50")
    }

    @Test
    fun `YOY_GROWTH_50 not unlocked with only one year of data`() {
        val result = AchievementChecker.check(
            emptyCtx().copy(yearlyTotals = mapOf(2024 to 5000.0))
        )
        assertThat(result).doesNotContain("yoy_growth_50")
    }

    @Test
    fun `STOCK_INCOME_1K unlocked when single stock yearly income reaches 1000`() {
        val result = AchievementChecker.check(
            emptyCtx().copy(
                perStockYearlyIncome = mapOf("sh.600000" to mapOf(2024 to 1200.0))
            )
        )
        assertThat(result).contains("stock_income_1k")
    }

    @Test
    fun `STOCK_INCOME_1K not unlocked when single stock yearly income is 999`() {
        val result = AchievementChecker.check(
            emptyCtx().copy(
                perStockYearlyIncome = mapOf("sh.600000" to mapOf(2024 to 999.0))
            )
        )
        assertThat(result).doesNotContain("stock_income_1k")
    }

    // --- Goal Achievement ---

    @Test
    fun `SET_FIRE_GOAL unlocked when fire goal is set`() {
        val goal = FireGoalEntity(targetAmount = 50000.0)
        val result = AchievementChecker.check(emptyCtx().copy(fireGoal = goal))
        assertThat(result).contains("set_fire_goal")
    }

    @Test
    fun `SET_FIRE_GOAL not unlocked when no fire goal`() {
        val result = AchievementChecker.check(emptyCtx())
        assertThat(result).doesNotContain("set_fire_goal")
    }

    @Test
    fun `FIRE_PROGRESS_10 unlocked when progress reaches 10 percent`() {
        val goal = FireGoalEntity(targetAmount = 10000.0)
        val result = AchievementChecker.check(emptyCtx().copy(fireGoal = goal, forecastTotal = 1500.0))
        assertThat(result).contains("fire_progress_10")
    }

    @Test
    fun `FIRE_PROGRESS_10 not unlocked when progress is below 10 percent`() {
        val goal = FireGoalEntity(targetAmount = 10000.0)
        val result = AchievementChecker.check(emptyCtx().copy(fireGoal = goal, forecastTotal = 500.0))
        assertThat(result).doesNotContain("fire_progress_10")
    }

    @Test
    fun `FIRE_PROGRESS_50 unlocked when progress reaches 50 percent`() {
        val goal = FireGoalEntity(targetAmount = 10000.0)
        val result = AchievementChecker.check(emptyCtx().copy(fireGoal = goal, forecastTotal = 6000.0))
        assertThat(result).contains("fire_progress_50")
    }

    @Test
    fun `FIRE_PROGRESS_50 not unlocked when progress is below 50 percent`() {
        val goal = FireGoalEntity(targetAmount = 10000.0)
        val result = AchievementChecker.check(emptyCtx().copy(fireGoal = goal, forecastTotal = 4000.0))
        assertThat(result).doesNotContain("fire_progress_50")
    }

    @Test
    fun `FIRE progress achievements not unlocked when targetAmount is zero`() {
        val goal = FireGoalEntity(targetAmount = 0.0)
        val result = AchievementChecker.check(emptyCtx().copy(fireGoal = goal, forecastTotal = 1000.0))
        assertThat(result).doesNotContain("fire_progress_10")
        assertThat(result).doesNotContain("fire_progress_50")
    }

    @Test
    fun `FIRE progress achievements not unlocked when fireGoal is null`() {
        val result = AchievementChecker.check(emptyCtx().copy(forecastTotal = 1000.0))
        assertThat(result).doesNotContain("fire_progress_10")
        assertThat(result).doesNotContain("fire_progress_50")
    }

    // --- Data Completeness ---

    @Test
    fun `COMPLETE_PROFILE unlocked when all held stocks have cost basis`() {
        val stocks = listOf(
            testStock.copy(shares = 100, costPerShare = 10.0),
            testStock.copy(code = "sh.600001", name = "股票2", shares = 200, costPerShare = 15.0)
        )
        val result = AchievementChecker.check(emptyCtx().copy(stocks = stocks))
        assertThat(result).contains("complete_profile")
    }

    @Test
    fun `COMPLETE_PROFILE not unlocked when a held stock has zero cost basis`() {
        val stocks = listOf(
            testStock.copy(shares = 100, costPerShare = 10.0),
            testStock.copy(code = "sh.600001", name = "股票2", shares = 200, costPerShare = 0.0)
        )
        val result = AchievementChecker.check(emptyCtx().copy(stocks = stocks))
        assertThat(result).doesNotContain("complete_profile")
    }

    @Test
    fun `COMPLETE_PROFILE not unlocked with no held stocks`() {
        val stocks = listOf(
            testStock.copy(shares = 0, costPerShare = 0.0)
        )
        val result = AchievementChecker.check(emptyCtx().copy(stocks = stocks))
        assertThat(result).doesNotContain("complete_profile")
    }

    @Test
    fun `PORTFOLIO_10 unlocked when 10 stocks have complete data`() {
        val stocks = (1..10).map { i ->
            testStock.copy(code = "sh.60000$i", name = "股票$i", shares = 100 * i, costPerShare = 10.0 + i)
        }
        val result = AchievementChecker.check(emptyCtx().copy(stocks = stocks))
        assertThat(result).contains("portfolio_10")
    }

    @Test
    fun `PORTFOLIO_10 not unlocked with only 9 complete stocks`() {
        val stocks = (1..9).map { i ->
            testStock.copy(code = "sh.60000$i", name = "股票$i", shares = 100 * i, costPerShare = 10.0 + i)
        }
        val result = AchievementChecker.check(emptyCtx().copy(stocks = stocks))
        assertThat(result).doesNotContain("portfolio_10")
    }
}
