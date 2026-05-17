package com.stock.dividend.data.notification

import com.stock.dividend.data.local.entity.DividendEntity
import com.stock.dividend.data.local.entity.NotificationRuleEntity
import javax.inject.Inject

data class NotificationRuleEvaluation(
    val isComparable: Boolean,
    val yieldPercent: Double? = null,
    val shouldNotify: Boolean = false,
    val updatedLastWasAboveThreshold: Boolean? = null,
    val checkedAt: Long
)

class NotificationRuleEvaluator @Inject constructor() {

    fun evaluate(
        rule: NotificationRuleEntity,
        dividends: List<DividendEntity>,
        currentPrice: Double?,
        checkedAt: Long
    ): NotificationRuleEvaluation {
        if (currentPrice == null || currentPrice <= 0.0 || rule.thresholdPercent <= 0.0) {
            return NotificationRuleEvaluation(isComparable = false, checkedAt = checkedAt)
        }

        val completeYearCashPerShare = latestReportYearCashPerShare(dividends)
            ?: return NotificationRuleEvaluation(isComparable = false, checkedAt = checkedAt)

        if (completeYearCashPerShare <= 0.0) {
            return NotificationRuleEvaluation(isComparable = false, checkedAt = checkedAt)
        }

        val yieldPercent = completeYearCashPerShare / currentPrice * 100.0
        val currentAbove = yieldPercent >= rule.thresholdPercent
        val shouldNotify = rule.lastWasAboveThreshold == false && currentAbove

        return NotificationRuleEvaluation(
            isComparable = true,
            yieldPercent = yieldPercent,
            shouldNotify = shouldNotify,
            updatedLastWasAboveThreshold = currentAbove,
            checkedAt = checkedAt
        )
    }

    private fun latestReportYearCashPerShare(dividends: List<DividendEntity>): Double? {
        return dividends
            .mapNotNull { dividend ->
                val year = dividend.reportDate.take(4).toIntOrNull() ?: return@mapNotNull null
                year to dividend.cashPerShare
            }
            .groupBy(keySelector = { it.first }, valueTransform = { it.second })
            .maxByOrNull { it.key }
            ?.value
            ?.sum()
    }
}
