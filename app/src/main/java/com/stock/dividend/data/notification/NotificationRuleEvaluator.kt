package com.stock.dividend.data.notification

import com.stock.dividend.data.local.entity.DividendEntity
import com.stock.dividend.data.local.entity.NOTIFICATION_RULE_TYPE_DIVIDEND_YIELD_BELOW_THRESHOLD
import com.stock.dividend.data.local.entity.NOTIFICATION_RULE_TYPE_DIVIDEND_YIELD_THRESHOLD
import com.stock.dividend.data.local.entity.NOTIFICATION_RULE_TYPE_PRICE_ABOVE
import com.stock.dividend.data.local.entity.NOTIFICATION_RULE_TYPE_PRICE_BELOW
import com.stock.dividend.data.local.entity.NotificationRuleEntity
import javax.inject.Inject

data class NotificationRuleEvaluation(
    val isComparable: Boolean,
    val yieldPercent: Double? = null,
    val metricValue: Double? = yieldPercent,
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
        return when (rule.type) {
            NOTIFICATION_RULE_TYPE_PRICE_ABOVE,
            NOTIFICATION_RULE_TYPE_PRICE_BELOW -> evaluatePriceRule(rule, currentPrice, checkedAt)
            NOTIFICATION_RULE_TYPE_DIVIDEND_YIELD_THRESHOLD,
            NOTIFICATION_RULE_TYPE_DIVIDEND_YIELD_BELOW_THRESHOLD -> evaluateYieldRule(
                rule = rule,
                dividends = dividends,
                currentPrice = currentPrice,
                checkedAt = checkedAt
            )
            else -> NotificationRuleEvaluation(isComparable = false, checkedAt = checkedAt)
        }
    }

    private fun evaluateYieldRule(
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
        val shouldNotify = when (rule.type) {
            NOTIFICATION_RULE_TYPE_DIVIDEND_YIELD_BELOW_THRESHOLD -> rule.lastWasAboveThreshold == true && !currentAbove
            else -> rule.lastWasAboveThreshold == false && currentAbove
        }

        return NotificationRuleEvaluation(
            isComparable = true,
            yieldPercent = yieldPercent,
            metricValue = yieldPercent,
            shouldNotify = shouldNotify,
            updatedLastWasAboveThreshold = currentAbove,
            checkedAt = checkedAt
        )
    }

    private fun evaluatePriceRule(
        rule: NotificationRuleEntity,
        currentPrice: Double?,
        checkedAt: Long
    ): NotificationRuleEvaluation {
        if (currentPrice == null || currentPrice <= 0.0 || rule.thresholdPercent <= 0.0) {
            return NotificationRuleEvaluation(isComparable = false, checkedAt = checkedAt)
        }

        val currentAbove = currentPrice >= rule.thresholdPercent
        val shouldNotify = when (rule.type) {
            NOTIFICATION_RULE_TYPE_PRICE_BELOW -> rule.lastWasAboveThreshold == true && !currentAbove
            else -> rule.lastWasAboveThreshold == false && currentAbove
        }

        return NotificationRuleEvaluation(
            isComparable = true,
            metricValue = currentPrice,
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
