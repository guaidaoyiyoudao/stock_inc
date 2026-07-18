package com.stock.dividend.data.notification

import com.stock.dividend.data.local.dao.DividendDao
import com.stock.dividend.data.local.entity.NOTIFICATION_RULE_TYPE_DIVIDEND_YIELD_THRESHOLD
import com.stock.dividend.data.local.entity.StockEntity
import com.stock.dividend.data.repository.NotificationRuleRepository
import com.stock.dividend.data.repository.StockRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationCheckCoordinator @Inject constructor(
    private val stockRepository: StockRepository,
    private val dividendDao: DividendDao,
    private val ruleRepository: NotificationRuleRepository,
    private val evaluator: NotificationRuleEvaluator,
    private val notifier: DividendAlertNotifier
) {
    internal var clock: () -> Long = { System.currentTimeMillis() }

    suspend fun checkActiveHoldings() {
        val stocks = stockRepository.observeAllStocksForSnapshot()
            .filter { it.shares > 0 }
        val prices = stockRepository.fetchQuotes(stocks)
        checkWithPrices(stocks, prices)
    }

    suspend fun checkWithPrices(
        stocks: List<StockEntity>,
        prices: Map<String, Double>
    ) {
        val activeStocks = stocks.filter { it.shares > 0 }
        if (activeStocks.isEmpty()) return

        val stockCodes = activeStocks.map { it.code }
        val effectiveYieldRules = ruleRepository.getEffectiveDividendYieldRules(stockCodes)
        val stockRules = ruleRepository.getEnabledStockRules(stockCodes)
        if (effectiveYieldRules.isEmpty() && stockRules.isEmpty()) return

        val now = clock()
        val canNotify = notifier.canNotify()
        activeStocks.forEach { stock ->
            val rules = buildList {
                effectiveYieldRules[stock.code]?.let(::add)
                addAll(stockRules[stock.code].orEmpty().filter { it.type != NOTIFICATION_RULE_TYPE_DIVIDEND_YIELD_THRESHOLD })
            }
            if (rules.isEmpty()) return@forEach
            val dividends = dividendDao.getByStock(stock.code)
            rules.forEach { rule ->
                val result = evaluator.evaluate(
                    rule = rule,
                    dividends = dividends,
                    currentPrice = prices[stock.code],
                    checkedAt = now
                )
                val updatedState = result.updatedLastWasAboveThreshold ?: return@forEach
                val triggeredAt = if (result.shouldNotify && canNotify) now else null
                if (result.shouldNotify && canNotify) {
                    sendAlert(stock, rule.type, result.metricValue ?: 0.0, rule.thresholdPercent)
                }
                ruleRepository.updateRuleEvaluationState(
                    rule = rule,
                    lastWasAboveThreshold = updatedState,
                    checkedAt = now,
                    triggeredAt = triggeredAt
                )
            }
        }
    }

    private suspend fun sendAlert(
        stock: StockEntity,
        ruleType: String,
        metricValue: Double,
        thresholdValue: Double
    ) {
        if (ruleType == NOTIFICATION_RULE_TYPE_DIVIDEND_YIELD_THRESHOLD) {
            notifier.sendDividendYieldAlert(
                stockCode = stock.code,
                stockName = stock.name,
                yieldPercent = metricValue,
                thresholdPercent = thresholdValue
            )
        } else {
            notifier.sendNotificationRuleAlert(
                stockCode = stock.code,
                stockName = stock.name,
                ruleType = ruleType,
                metricValue = metricValue,
                thresholdValue = thresholdValue
            )
        }
    }
}
