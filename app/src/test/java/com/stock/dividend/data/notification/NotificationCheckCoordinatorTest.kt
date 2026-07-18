package com.stock.dividend.data.notification

import com.stock.dividend.data.local.dao.DividendDao
import com.stock.dividend.data.local.entity.DividendEntity
import com.stock.dividend.data.local.entity.NOTIFICATION_RULE_TYPE_DIVIDEND_YIELD_THRESHOLD
import com.stock.dividend.data.local.entity.NOTIFICATION_RULE_TYPE_PRICE_ABOVE
import com.stock.dividend.data.local.entity.NotificationRuleEntity
import com.stock.dividend.data.local.entity.StockEntity
import com.stock.dividend.data.repository.NotificationRuleRepository
import com.stock.dividend.data.repository.StockRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

class NotificationCheckCoordinatorTest {

    private val stockRepository: StockRepository = mockk()
    private val dividendDao: DividendDao = mockk()
    private val ruleRepository: NotificationRuleRepository = mockk(relaxed = true)
    private val notifier: DividendAlertNotifier = mockk(relaxed = true)
    private val coordinator = NotificationCheckCoordinator(
        stockRepository = stockRepository,
        dividendDao = dividendDao,
        ruleRepository = ruleRepository,
        evaluator = NotificationRuleEvaluator(),
        notifier = notifier
    ).apply {
        clock = { 1000L }
    }

    @Test
    fun `sends notification and persists state when yield crosses threshold`() = runTest {
        val stock = StockEntity("sz.000001", "平安银行", "0", shares = 100)
        val rule = rule(lastWasAboveThreshold = false, thresholdPercent = 5.0)
        coEvery { ruleRepository.getEffectiveDividendYieldRules(listOf(stock.code)) } returns mapOf(stock.code to rule)
        coEvery { dividendDao.getByStock(stock.code) } returns listOf(dividend("2025-12-31", 1.2))
        coEvery { notifier.canNotify() } returns true

        coordinator.checkWithPrices(
            stocks = listOf(stock),
            prices = mapOf(stock.code to 20.0)
        )

        coVerify {
            notifier.sendDividendYieldAlert(
                stockCode = stock.code,
                stockName = stock.name,
                yieldPercent = 6.0,
                thresholdPercent = 5.0
            )
            ruleRepository.updateRuleEvaluationState(
                rule = rule,
                lastWasAboveThreshold = true,
                checkedAt = 1000L,
                triggeredAt = 1000L
            )
        }
    }

    @Test
    fun `persists above state without notification on first comparable check`() = runTest {
        val stock = StockEntity("sz.000001", "平安银行", "0", shares = 100)
        val rule = rule(lastWasAboveThreshold = null, thresholdPercent = 5.0)
        coEvery { ruleRepository.getEffectiveDividendYieldRules(listOf(stock.code)) } returns mapOf(stock.code to rule)
        coEvery { dividendDao.getByStock(stock.code) } returns listOf(dividend("2025-12-31", 1.2))

        coordinator.checkWithPrices(
            stocks = listOf(stock),
            prices = mapOf(stock.code to 20.0)
        )

        coVerify(exactly = 0) {
            notifier.sendDividendYieldAlert(any(), any(), any(), any())
        }
        coVerify {
            ruleRepository.updateRuleEvaluationState(
                rule = rule,
                lastWasAboveThreshold = true,
                checkedAt = 1000L,
                triggeredAt = null
            )
        }
    }

    @Test
    fun `sends custom rule notification when price crosses threshold`() = runTest {
        val stock = StockEntity("sz.000001", "平安银行", "0", shares = 100)
        val rule = rule(
            type = NOTIFICATION_RULE_TYPE_PRICE_ABOVE,
            lastWasAboveThreshold = false,
            thresholdPercent = 12.0
        )
        coEvery { ruleRepository.getEffectiveDividendYieldRules(listOf(stock.code)) } returns emptyMap()
        coEvery { ruleRepository.getEnabledStockRules(listOf(stock.code)) } returns mapOf(stock.code to listOf(rule))
        coEvery { dividendDao.getByStock(stock.code) } returns emptyList()
        coEvery { notifier.canNotify() } returns true

        coordinator.checkWithPrices(
            stocks = listOf(stock),
            prices = mapOf(stock.code to 12.5)
        )

        coVerify {
            notifier.sendNotificationRuleAlert(
                stockCode = stock.code,
                stockName = stock.name,
                ruleType = NOTIFICATION_RULE_TYPE_PRICE_ABOVE,
                metricValue = 12.5,
                thresholdValue = 12.0
            )
            ruleRepository.updateRuleEvaluationState(
                rule = rule,
                lastWasAboveThreshold = true,
                checkedAt = 1000L,
                triggeredAt = 1000L
            )
        }
    }

    private fun rule(
        lastWasAboveThreshold: Boolean?,
        thresholdPercent: Double,
        type: String = NOTIFICATION_RULE_TYPE_DIVIDEND_YIELD_THRESHOLD
    ) = NotificationRuleEntity(
        id = "global",
        type = type,
        stockCode = null,
        enabled = true,
        thresholdPercent = thresholdPercent,
        lastWasAboveThreshold = lastWasAboveThreshold,
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
