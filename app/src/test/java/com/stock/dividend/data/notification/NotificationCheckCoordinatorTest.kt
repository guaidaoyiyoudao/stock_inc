package com.stock.dividend.data.notification

import com.stock.dividend.data.local.dao.DividendDao
import com.stock.dividend.data.local.entity.DividendEntity
import com.stock.dividend.data.local.entity.GridPlanEntity
import com.stock.dividend.data.local.entity.NOTIFICATION_RULE_TYPE_BOLL_WEEKLY_UPPER
import com.stock.dividend.data.local.entity.NOTIFICATION_RULE_TYPE_DIVIDEND_YIELD_THRESHOLD
import com.stock.dividend.data.local.entity.NOTIFICATION_RULE_TYPE_PRICE_ABOVE
import com.stock.dividend.data.local.entity.NotificationRuleEntity
import com.stock.dividend.data.local.entity.StockEntity
import com.stock.dividend.data.repository.BollBand
import com.stock.dividend.data.repository.GridPlanRepository
import com.stock.dividend.data.repository.NotificationRuleRepository
import com.stock.dividend.data.repository.StockRepository
import com.stock.dividend.data.repository.TransactionRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

class NotificationCheckCoordinatorTest {

    private val stockRepository: StockRepository = mockk()
    private val dividendDao: DividendDao = mockk()
    private val ruleRepository: NotificationRuleRepository = mockk(relaxed = true)
    private val notifier: DividendAlertNotifier = mockk(relaxed = true)
    private val gridPlanRepository: GridPlanRepository = mockk(relaxed = true)
    private val transactionRepository: TransactionRepository = mockk(relaxed = true)
    private val coordinator = NotificationCheckCoordinator(
        stockRepository = stockRepository,
        dividendDao = dividendDao,
        ruleRepository = ruleRepository,
        evaluator = NotificationRuleEvaluator(),
        notifier = notifier,
        gridPlanRepository = gridPlanRepository,
        transactionRepository = transactionRepository
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

    @Test
    fun `sends boll upper notification with band upper as threshold when price crosses`() = runTest {
        val stock = StockEntity("sz.000001", "平安银行", "0", shares = 100)
        val rule = rule(
            type = NOTIFICATION_RULE_TYPE_BOLL_WEEKLY_UPPER,
            lastWasAboveThreshold = false,
            thresholdPercent = 0.0
        )
        coEvery { ruleRepository.getEffectiveDividendYieldRules(listOf(stock.code)) } returns emptyMap()
        coEvery { ruleRepository.getEnabledStockRules(listOf(stock.code)) } returns mapOf(stock.code to listOf(rule))
        coEvery { dividendDao.getByStock(stock.code) } returns emptyList()
        coEvery { notifier.canNotify() } returns true
        coEvery { stockRepository.fetchBoll(stock.code) } returns BollBand(middle = 11.0, upper = 12.0, lower = 10.0)

        coordinator.checkWithPrices(
            stocks = listOf(stock),
            prices = mapOf(stock.code to 12.5)
        )

        coVerify {
            notifier.sendNotificationRuleAlert(
                stockCode = stock.code,
                stockName = stock.name,
                ruleType = NOTIFICATION_RULE_TYPE_BOLL_WEEKLY_UPPER,
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

    @Test
    fun `skips boll rule without notification when band is unavailable`() = runTest {
        val stock = StockEntity("sz.000001", "平安银行", "0", shares = 100)
        val rule = rule(
            type = NOTIFICATION_RULE_TYPE_BOLL_WEEKLY_UPPER,
            lastWasAboveThreshold = false,
            thresholdPercent = 0.0
        )
        coEvery { ruleRepository.getEffectiveDividendYieldRules(listOf(stock.code)) } returns emptyMap()
        coEvery { ruleRepository.getEnabledStockRules(listOf(stock.code)) } returns mapOf(stock.code to listOf(rule))
        coEvery { dividendDao.getByStock(stock.code) } returns emptyList()
        coEvery { notifier.canNotify() } returns true
        coEvery { stockRepository.fetchBoll(stock.code) } returns null

        coordinator.checkWithPrices(
            stocks = listOf(stock),
            prices = mapOf(stock.code to 12.5)
        )

        coVerify(exactly = 0) {
            notifier.sendNotificationRuleAlert(any(), any(), any(), any(), any(), any())
        }
        coVerify(exactly = 0) {
            ruleRepository.updateRuleEvaluationState(any(), any(), any(), any())
        }
    }

    // ── 网格到档提醒（checkGridPlans）──────────────────────

    /** 现价到达下一买入档 → 发通知 + 回写已提醒档位；自选观察仓（shares=0）也覆盖。 */
    @Test
    fun `sends grid level notification for watchlist stock without holdings`() = runTest {
        val stock = StockEntity("sz.000001", "平安银行", "0", shares = 0)  // 非持仓也要提醒
        coEvery { gridPlanRepository.observeAll() } returns flowOf(listOf(gridPlan()))
        coEvery { stockRepository.observeAllStocksForSnapshot() } returns listOf(stock)
        coEvery { stockRepository.fetchQuotes(any()) } returns mapOf(stock.code to 9.9)
        coEvery { transactionRepository.getAll() } returns emptyList()
        coEvery { notifier.canNotify() } returns true

        coordinator.checkGridPlans()

        coVerify {
            notifier.sendNotificationRuleAlert(
                stockCode = stock.code,
                stockName = "浦发银行",
                ruleType = GRID_NEXT_LEVEL_ALERT,
                metricValue = 9.9,
                thresholdValue = 10.0,  // 4 档网格（8/8.67/9.33/10），现价 9.9 到达 10.00 档
                dedupKey = "grid-p1"    // 按计划独立成条
            )
            gridPlanRepository.updateNotifiedLevel("p1", 10.0)
        }
    }

    /** 该档已提醒过 → 不重复发通知、不回写。 */
    @Test
    fun `skips grid notification when level already notified`() = runTest {
        val stock = StockEntity("sz.000001", "平安银行", "0", shares = 100)
        coEvery { gridPlanRepository.observeAll() } returns flowOf(
            listOf(gridPlan(lastNotifiedLevelPrice = 10.0))
        )
        coEvery { stockRepository.observeAllStocksForSnapshot() } returns listOf(stock)
        coEvery { stockRepository.fetchQuotes(any()) } returns mapOf(stock.code to 9.9)
        coEvery { transactionRepository.getAll() } returns emptyList()
        coEvery { notifier.canNotify() } returns true

        coordinator.checkGridPlans()

        coVerify(exactly = 0) { notifier.sendNotificationRuleAlert(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { gridPlanRepository.updateNotifiedLevel(any(), any()) }
    }

    /** 无通知权限 → 不发也不落「已提醒」状态（权限恢复后仍能补提醒）。 */
    @Test
    fun `does not persist notified level without notification permission`() = runTest {
        val stock = StockEntity("sz.000001", "平安银行", "0", shares = 100)
        coEvery { gridPlanRepository.observeAll() } returns flowOf(listOf(gridPlan()))
        coEvery { stockRepository.observeAllStocksForSnapshot() } returns listOf(stock)
        coEvery { stockRepository.fetchQuotes(any()) } returns mapOf(stock.code to 9.9)
        coEvery { transactionRepository.getAll() } returns emptyList()
        coEvery { notifier.canNotify() } returns false

        coordinator.checkGridPlans()

        coVerify(exactly = 0) { notifier.sendNotificationRuleAlert(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { gridPlanRepository.updateNotifiedLevel(any(), any()) }
    }

    /** 迟滞复位：现价回升超过上次提醒档（8.67）→ 清空旧状态，同时提醒新到达档（10.00）。 */
    @Test
    fun `clears stale notify state while notifying new level`() = runTest {
        val stock = StockEntity("sz.000001", "平安银行", "0", shares = 100)
        coEvery { gridPlanRepository.observeAll() } returns flowOf(
            listOf(gridPlan(lastNotifiedLevelPrice = 8.67))
        )
        coEvery { stockRepository.observeAllStocksForSnapshot() } returns listOf(stock)
        coEvery { stockRepository.fetchQuotes(any()) } returns mapOf(stock.code to 9.5)
        coEvery { transactionRepository.getAll() } returns emptyList()
        coEvery { notifier.canNotify() } returns true

        coordinator.checkGridPlans()

        // 新提醒（10.00 档）覆盖了清空语义——只写新档位，不再额外写 null
        coVerify { gridPlanRepository.updateNotifiedLevel("p1", 10.0) }
        coVerify(exactly = 0) { gridPlanRepository.updateNotifiedLevel("p1", null) }
    }

    /** 无网格计划 → 直接返回，不拉行情。 */
    @Test
    fun `no grid plans means no quote fetch`() = runTest {
        coEvery { gridPlanRepository.observeAll() } returns flowOf(emptyList())

        coordinator.checkGridPlans()

        coVerify(exactly = 0) { stockRepository.fetchQuotes(any()) }
    }

    private fun gridPlan(lastNotifiedLevelPrice: Double? = null) = GridPlanEntity(
        id = "p1",
        stockCode = "sz.000001",
        stockName = "浦发银行",
        basePrice = 10.0,
        lowPrice = 8.0,
        highPrice = 12.0,
        grids = 4,
        totalCapital = 100000.0,
        notifyEnabled = true,
        lastNotifiedLevelPrice = lastNotifiedLevelPrice
    )

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
