package com.stock.dividend.data.notification

import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.local.entity.DividendEntity
import com.stock.dividend.data.local.entity.NOTIFICATION_RULE_TYPE_BOLL_WEEKLY_UPPER
import com.stock.dividend.data.local.entity.NOTIFICATION_RULE_TYPE_DIVIDEND_YIELD_THRESHOLD
import com.stock.dividend.data.local.entity.NOTIFICATION_RULE_TYPE_DIVIDEND_YIELD_BELOW_THRESHOLD
import com.stock.dividend.data.local.entity.NOTIFICATION_RULE_TYPE_PRICE_ABOVE
import com.stock.dividend.data.local.entity.NOTIFICATION_RULE_TYPE_PRICE_BELOW
import com.stock.dividend.data.local.entity.NotificationRuleEntity
import org.junit.Test

class NotificationRuleEvaluatorTest {

    private val evaluator = NotificationRuleEvaluator()

    @Test
    fun `uses latest report year cash per share total to compute yield`() {
        val result = evaluator.evaluate(
            rule = rule(lastWasAboveThreshold = false, thresholdPercent = 5.0),
            dividends = listOf(
                dividend("2024-12-31", 0.4),
                dividend("2025-06-30", 0.6),
                dividend("2025-12-31", 0.5)
            ),
            currentPrice = 20.0,
            checkedAt = 1000L
        )

        assertThat(result.yieldPercent).isWithin(0.0001).of(5.5)
        assertThat(result.shouldNotify).isTrue()
        assertThat(result.updatedLastWasAboveThreshold).isTrue()
    }

    @Test
    fun `does not notify on first comparable check when already above threshold`() {
        val result = evaluator.evaluate(
            rule = rule(lastWasAboveThreshold = null, thresholdPercent = 5.0),
            dividends = listOf(dividend("2025-12-31", 1.2)),
            currentPrice = 20.0,
            checkedAt = 1000L
        )

        assertThat(result.yieldPercent).isWithin(0.0001).of(6.0)
        assertThat(result.shouldNotify).isFalse()
        assertThat(result.updatedLastWasAboveThreshold).isTrue()
    }

    @Test
    fun `does not repeat while remaining above threshold`() {
        val result = evaluator.evaluate(
            rule = rule(lastWasAboveThreshold = true, thresholdPercent = 5.0),
            dividends = listOf(dividend("2025-12-31", 1.2)),
            currentPrice = 20.0,
            checkedAt = 1000L
        )

        assertThat(result.shouldNotify).isFalse()
        assertThat(result.updatedLastWasAboveThreshold).isTrue()
    }

    @Test
    fun `resets state when yield falls below threshold`() {
        val result = evaluator.evaluate(
            rule = rule(lastWasAboveThreshold = true, thresholdPercent = 5.0),
            dividends = listOf(dividend("2025-12-31", 0.8)),
            currentPrice = 20.0,
            checkedAt = 1000L
        )

        assertThat(result.shouldNotify).isFalse()
        assertThat(result.updatedLastWasAboveThreshold).isFalse()
    }

    @Test
    fun `returns not comparable when price is missing`() {
        val result = evaluator.evaluate(
            rule = rule(lastWasAboveThreshold = false, thresholdPercent = 5.0),
            dividends = listOf(dividend("2025-12-31", 1.2)),
            currentPrice = null,
            checkedAt = 1000L
        )

        assertThat(result.isComparable).isFalse()
        assertThat(result.shouldNotify).isFalse()
        assertThat(result.updatedLastWasAboveThreshold).isNull()
    }

    @Test
    fun `notifies when price crosses above target`() {
        val result = evaluator.evaluate(
            rule = rule(
                type = NOTIFICATION_RULE_TYPE_PRICE_ABOVE,
                lastWasAboveThreshold = false,
                thresholdPercent = 12.0
            ),
            dividends = emptyList(),
            currentPrice = 12.5,
            checkedAt = 1000L
        )

        assertThat(result.metricValue).isEqualTo(12.5)
        assertThat(result.shouldNotify).isTrue()
        assertThat(result.updatedLastWasAboveThreshold).isTrue()
    }

    @Test
    fun `notifies when price crosses below target`() {
        val result = evaluator.evaluate(
            rule = rule(
                type = NOTIFICATION_RULE_TYPE_PRICE_BELOW,
                lastWasAboveThreshold = true,
                thresholdPercent = 12.0
            ),
            dividends = emptyList(),
            currentPrice = 11.5,
            checkedAt = 1000L
        )

        assertThat(result.metricValue).isEqualTo(11.5)
        assertThat(result.shouldNotify).isTrue()
        assertThat(result.updatedLastWasAboveThreshold).isFalse()
    }

    @Test
    fun `notifies when dividend yield crosses below target`() {
        val result = evaluator.evaluate(
            rule = rule(
                type = NOTIFICATION_RULE_TYPE_DIVIDEND_YIELD_BELOW_THRESHOLD,
                lastWasAboveThreshold = true,
                thresholdPercent = 5.0
            ),
            dividends = listOf(dividend("2025-12-31", 0.8)),
            currentPrice = 20.0,
            checkedAt = 1000L
        )

        assertThat(result.yieldPercent).isWithin(0.0001).of(4.0)
        assertThat(result.shouldNotify).isTrue()
        assertThat(result.updatedLastWasAboveThreshold).isFalse()
    }

    @Test
    fun `notifies when price crosses above weekly boll upper band`() {
        val result = evaluator.evaluate(
            rule = rule(
                type = NOTIFICATION_RULE_TYPE_BOLL_WEEKLY_UPPER,
                lastWasAboveThreshold = false,
                thresholdPercent = 0.0
            ),
            dividends = emptyList(),
            currentPrice = 12.5,
            checkedAt = 1000L,
            bollUpper = 12.0
        )

        assertThat(result.metricValue).isEqualTo(12.5)
        assertThat(result.shouldNotify).isTrue()
        assertThat(result.updatedLastWasAboveThreshold).isTrue()
    }

    @Test
    fun `does not repeat while price remains above weekly boll upper band`() {
        val result = evaluator.evaluate(
            rule = rule(
                type = NOTIFICATION_RULE_TYPE_BOLL_WEEKLY_UPPER,
                lastWasAboveThreshold = true,
                thresholdPercent = 0.0
            ),
            dividends = emptyList(),
            currentPrice = 12.5,
            checkedAt = 1000L,
            bollUpper = 12.0
        )

        assertThat(result.shouldNotify).isFalse()
        assertThat(result.updatedLastWasAboveThreshold).isTrue()
    }

    @Test
    fun `resets state when price falls below weekly boll upper band`() {
        val result = evaluator.evaluate(
            rule = rule(
                type = NOTIFICATION_RULE_TYPE_BOLL_WEEKLY_UPPER,
                lastWasAboveThreshold = true,
                thresholdPercent = 0.0
            ),
            dividends = emptyList(),
            currentPrice = 11.5,
            checkedAt = 1000L,
            bollUpper = 12.0
        )

        assertThat(result.shouldNotify).isFalse()
        assertThat(result.updatedLastWasAboveThreshold).isFalse()
    }

    @Test
    fun `boll upper rule is not comparable when band is missing`() {
        val result = evaluator.evaluate(
            rule = rule(
                type = NOTIFICATION_RULE_TYPE_BOLL_WEEKLY_UPPER,
                lastWasAboveThreshold = false,
                thresholdPercent = 0.0
            ),
            dividends = emptyList(),
            currentPrice = 12.5,
            checkedAt = 1000L,
            bollUpper = null
        )

        assertThat(result.isComparable).isFalse()
        assertThat(result.shouldNotify).isFalse()
        assertThat(result.updatedLastWasAboveThreshold).isNull()
    }

    private fun rule(
        lastWasAboveThreshold: Boolean?,
        thresholdPercent: Double,
        type: String = NOTIFICATION_RULE_TYPE_DIVIDEND_YIELD_THRESHOLD
    ) = NotificationRuleEntity(
        id = "global-dividend-yield",
        type = type,
        stockCode = null,
        enabled = true,
        thresholdPercent = thresholdPercent,
        lastWasAboveThreshold = lastWasAboveThreshold,
        lastCheckedAt = null,
        lastTriggeredAt = null,
        createdAt = 0L,
        updatedAt = 0L
    )

    private fun dividend(reportDate: String, cashPerShare: Double) = DividendEntity(
        id = reportDate,
        stockCode = "sz.000001",
        reportDate = reportDate,
        cashPerShare = cashPerShare
    )
}
