package com.stock.dividend.data.notification

import com.stock.dividend.data.local.dao.DividendDao
import com.stock.dividend.data.local.entity.NOTIFICATION_RULE_TYPE_BOLL_WEEKLY_UPPER
import com.stock.dividend.data.local.entity.NOTIFICATION_RULE_TYPE_DIVIDEND_YIELD_THRESHOLD
import com.stock.dividend.data.local.entity.StockEntity
import com.stock.dividend.data.repository.GridPlanRepository
import com.stock.dividend.data.repository.NotificationRuleRepository
import com.stock.dividend.data.repository.StockRepository
import com.stock.dividend.data.repository.TransactionRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationCheckCoordinator @Inject constructor(
    private val stockRepository: StockRepository,
    private val dividendDao: DividendDao,
    private val ruleRepository: NotificationRuleRepository,
    private val evaluator: NotificationRuleEvaluator,
    private val notifier: DividendAlertNotifier,
    private val gridPlanRepository: GridPlanRepository,
    private val transactionRepository: TransactionRepository
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

        // 仅当存在周线 BOLL 上轨规则时，才按股票拉取上轨（避免无谓网络请求）
        val hasBollRules = stockRules.values.any { rules -> rules.any { it.type == NOTIFICATION_RULE_TYPE_BOLL_WEEKLY_UPPER } }
        val bollUpperByCode: Map<String, Double> = if (hasBollRules) {
            activeStocks.mapNotNull { stock ->
                stockRepository.fetchBoll(stock.code)?.upper?.let { stock.code to it }
            }.toMap()
        } else {
            emptyMap()
        }

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
                val bollUpper = if (rule.type == NOTIFICATION_RULE_TYPE_BOLL_WEEKLY_UPPER) {
                    bollUpperByCode[stock.code]
                } else {
                    null
                }
                val result = evaluator.evaluate(
                    rule = rule,
                    dividends = dividends,
                    currentPrice = prices[stock.code],
                    checkedAt = now,
                    bollUpper = bollUpper
                )
                val updatedState = result.updatedLastWasAboveThreshold ?: return@forEach
                val triggeredAt = if (result.shouldNotify && canNotify) now else null
                if (result.shouldNotify && canNotify) {
                    val thresholdValue = bollUpper ?: rule.thresholdPercent
                    sendAlert(stock, rule.type, result.metricValue ?: 0.0, thresholdValue)
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

    /**
     * 网格到档提醒检查：价格到达网格下一买入档时推送通知。
     *
     * 与 [checkActiveHoldings] 解耦——网格计划针对的标的**允许未持仓**（自选观察仓），
     * 因此按计划维度（而非持仓维度）批量拉价。评估逻辑见 [GridNotifyEvaluator]
     * （带迟滞的边沿触发：每档只提醒一次，现价回升后可重新提醒）。
     * 各数据源失败均吞异常静默跳过（§4.3），不让 Worker 崩溃。
     */
    suspend fun checkGridPlans() {
        val plans = runCatching { gridPlanRepository.observeAll().first() }
            .getOrDefault(emptyList())
            .filter { it.notifyEnabled }
        if (plans.isEmpty()) return

        val planCodes = plans.map { it.stockCode }.toSet()
        val stocks = runCatching {
            stockRepository.observeAllStocksForSnapshot().filter { it.code in planCodes }
        }.getOrDefault(emptyList())
        if (stocks.isEmpty()) return

        val prices = runCatching { stockRepository.fetchQuotes(stocks) }.getOrDefault(emptyMap())
        val transactionsByStock = runCatching {
            transactionRepository.getAll().groupBy { it.stockCode }
        }.getOrDefault(emptyMap())

        val evaluation = GridNotifyEvaluator.evaluate(plans, prices, transactionsByStock)
        if (evaluation.signals.isEmpty() && evaluation.clearedPlanIds.isEmpty()) return

        val canNotify = notifier.canNotify()
        evaluation.signals.forEach { signal ->
            if (canNotify) {
                // 复用规则通知管线（文案/渠道/deep link 到个股详情）
                notifier.sendNotificationRuleAlert(
                    stockCode = signal.plan.stockCode,
                    stockName = signal.plan.stockName,
                    ruleType = GRID_NEXT_LEVEL_ALERT,
                    metricValue = signal.currentPrice,
                    thresholdValue = signal.levelPrice,
                    dedupKey = "grid-" + signal.plan.id  // 同股多套网格各自成条，互不覆盖
                )
                // 「已提醒」状态只在真正发出通知后才落库——无通知权限时不吞掉提醒机会
                runCatching { gridPlanRepository.updateNotifiedLevel(signal.plan.id, signal.levelPrice) }
            }
        }
        // 迟滞复位：现价已回升超过上次提醒档 → 清空状态（未被本轮新提醒覆盖的计划才需显式清空）
        evaluation.clearedPlanIds
            .filter { it !in evaluation.notifiedLevels }
            .forEach { id ->
                runCatching { gridPlanRepository.updateNotifiedLevel(id, null) }
            }
    }
}
