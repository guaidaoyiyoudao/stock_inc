package com.stock.dividend.viewmodel

import com.stock.dividend.data.local.entity.FireGoalEntity
import com.stock.dividend.data.local.entity.StockEntity

object AchievementChecker {
    data class CheckContext(
        val stocks: List<StockEntity>,
        val yearlyTotals: Map<Int, Double>,
        val hasAnyIncomeRecord: Boolean,
        val incomeRecordCount: Int = 0,
        val maxSingleIncome: Double = 0.0,
        val perStockYearlyIncome: Map<String, Map<Int, Double>> = emptyMap(),
        val fireGoal: FireGoalEntity? = null,
        val forecastTotal: Double = 0.0
    )

    fun check(ctx: CheckContext): Set<String> {
        val unlocked = mutableSetOf<String>()

        if (ctx.hasAnyIncomeRecord) unlocked.add(AchievementDef.FIRST_DIVIDEND.id)

        val maxIncome = ctx.yearlyTotals.values.maxOrNull() ?: 0.0
        if (maxIncome >= 1_000) unlocked.add(AchievementDef.INCOME_1K.id)
        if (maxIncome >= 10_000) unlocked.add(AchievementDef.INCOME_10K.id)
        if (maxIncome >= 100_000) unlocked.add(AchievementDef.INCOME_100K.id)

        if (ctx.stocks.isNotEmpty()) unlocked.add(AchievementDef.PORTFOLIO_START.id)
        if (ctx.stocks.size >= 5) unlocked.add(AchievementDef.DIVERSIFY_5.id)

        val earliestAddedAt = ctx.stocks.minOfOrNull { it.addedAt }
        if (earliestAddedAt != null && earliestAddedAt > 0 &&
            System.currentTimeMillis() - earliestAddedAt >= 365L * 24 * 3600 * 1000
        ) {
            unlocked.add(AchievementDef.HOLD_1Y.id)
        }

        val years = ctx.yearlyTotals.keys.sorted()
        if (years.size >= 3) {
            var maxStreak = 1
            for (i in 1 until years.size) {
                if (years[i] == years[i - 1] + 1) maxStreak++ else maxStreak = 1
            }
            if (maxStreak >= 3) unlocked.add(AchievementDef.STREAK_3Y.id)
        }

        // --- Recording Habit ---
        if (ctx.incomeRecordCount >= 10) unlocked.add(AchievementDef.RECORD_10.id)
        if (ctx.incomeRecordCount >= 50) unlocked.add(AchievementDef.RECORD_50.id)

        // --- Income Breakthrough ---
        if (ctx.maxSingleIncome >= 100) unlocked.add(AchievementDef.SINGLE_100.id)

        val sortedYears = ctx.yearlyTotals.keys.sorted()
        if (sortedYears.size >= 2) {
            for (i in 1 until sortedYears.size) {
                val prev = ctx.yearlyTotals[sortedYears[i - 1]] ?: continue
                val curr = ctx.yearlyTotals[sortedYears[i]] ?: continue
                if (prev > 0 && curr > prev * 1.5) {
                    unlocked.add(AchievementDef.YOY_GROWTH_50.id)
                    break
                }
            }
        }

        val maxStockIncome = ctx.perStockYearlyIncome.values
            .flatMap { it.values }
            .maxOrNull() ?: 0.0
        if (maxStockIncome >= 1_000) unlocked.add(AchievementDef.STOCK_INCOME_1K.id)

        // --- Goal Achievement ---
        if (ctx.fireGoal != null) unlocked.add(AchievementDef.SET_FIRE_GOAL.id)
        if (ctx.fireGoal != null && ctx.fireGoal.targetAmount > 0) {
            val progress = ctx.forecastTotal / ctx.fireGoal.targetAmount
            if (progress >= 0.1) unlocked.add(AchievementDef.FIRE_PROGRESS_10.id)
            if (progress >= 0.5) unlocked.add(AchievementDef.FIRE_PROGRESS_50.id)
        }

        // --- Data Completeness ---
        val heldStocks = ctx.stocks.filter { it.shares > 0 }
        if (heldStocks.isNotEmpty() && heldStocks.all { it.costPerShare > 0 }) {
            unlocked.add(AchievementDef.COMPLETE_PROFILE.id)
        }
        if (heldStocks.count { it.costPerShare > 0 } >= 10) {
            unlocked.add(AchievementDef.PORTFOLIO_10.id)
        }

        return unlocked
    }
}
