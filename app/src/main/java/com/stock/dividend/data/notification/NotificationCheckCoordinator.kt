package com.stock.dividend.data.notification

import com.stock.dividend.data.local.dao.DividendDao
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

        val rules = ruleRepository.getEffectiveDividendYieldRules(activeStocks.map { it.code })
        if (rules.isEmpty()) return

        val now = clock()
        val canNotify = notifier.canNotify()
        activeStocks.forEach { stock ->
            val rule = rules[stock.code] ?: return@forEach
            val result = evaluator.evaluate(
                rule = rule,
                dividends = dividendDao.getByStock(stock.code),
                currentPrice = prices[stock.code],
                checkedAt = now
            )
            val updatedState = result.updatedLastWasAboveThreshold ?: return@forEach
            val triggeredAt = if (result.shouldNotify && canNotify) now else null
            if (result.shouldNotify && canNotify) {
                notifier.sendDividendYieldAlert(
                    stockCode = stock.code,
                    stockName = stock.name,
                    yieldPercent = result.yieldPercent ?: 0.0,
                    thresholdPercent = rule.thresholdPercent
                )
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
