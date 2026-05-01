package com.stock.dividend.viewmodel

import com.stock.dividend.data.local.entity.StockEntity

object AchievementChecker {
    data class CheckContext(
        val stocks: List<StockEntity>,
        val yearlyTotals: Map<Int, Double>,
        val hasAnyIncomeRecord: Boolean
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

        return unlocked
    }
}
