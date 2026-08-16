package com.stock.dividend.data.notification

import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.local.entity.GridPlanEntity
import com.stock.dividend.data.local.entity.TransactionEntity
import org.junit.Test

/**
 * [GridNotifyEvaluator]（网格到档提醒评估）单测。
 *
 * 触发语义：crossed = 「档位价 ≥ 现价」中最便宜的一档；每档只提醒一次
 * （lastNotifiedLevelPrice 去重）；现价回升超过上次提醒档后清空状态（迟滞复位）；
 * 已有实际买入的档不提醒。测试网格统一 4 档：8.00 / 8.67 / 9.33 / 10.00。
 */
class GridNotifyEvaluatorTest {

    private fun plan(
        notifyEnabled: Boolean = true,
        lastNotifiedLevelPrice: Double? = null,
        stockCode: String = "sh.600000"
    ) = GridPlanEntity(
        id = "p1",
        stockCode = stockCode,
        stockName = "浦发银行",
        basePrice = 10.0,
        lowPrice = 8.0,
        highPrice = 12.0,
        grids = 4,
        totalCapital = 100000.0,
        notifyEnabled = notifyEnabled,
        lastNotifiedLevelPrice = lastNotifiedLevelPrice
    )

    private fun tx(price: Double, type: String = "BUY") = TransactionEntity(
        id = 0L, stockCode = "sh.600000", type = type, shares = 100, price = price, date = "2026-01-01"
    )

    /** 现价 9.9 到达 10.00 档（买入起点）→ 提醒一次并记录已提醒档位。 */
    @Test
    fun `notifies when price reaches a level`() {
        val evaluation = GridNotifyEvaluator.evaluate(
            plans = listOf(plan()),
            prices = mapOf("sh.600000" to 9.9),
            transactionsByStock = emptyMap()
        )
        assertThat(evaluation.signals).hasSize(1)
        assertThat(evaluation.signals[0].levelPrice).isEqualTo(10.0)
        assertThat(evaluation.signals[0].currentPrice).isEqualTo(9.9)
        assertThat(evaluation.signals[0].shares).isGreaterThan(0)
        assertThat(evaluation.notifiedLevels).containsExactly("p1", 10.0)
        assertThat(evaluation.clearedPlanIds).isEmpty()
    }

    /** 同一档已提醒过（lastNotified == crossed）→ 跳过，避免重复轰炸。 */
    @Test
    fun `skips when level already notified`() {
        val evaluation = GridNotifyEvaluator.evaluate(
            plans = listOf(plan(lastNotifiedLevelPrice = 10.0)),
            prices = mapOf("sh.600000" to 9.9),
            transactionsByStock = emptyMap()
        )
        assertThat(evaluation.signals).isEmpty()
        assertThat(evaluation.notifiedLevels).isEmpty()
        assertThat(evaluation.clearedPlanIds).isEmpty()
    }

    /** 现价回升到上次提醒档之上 → 清空提醒状态（迟滞复位）。 */
    @Test
    fun `clears state when price recovers above last notified level`() {
        val evaluation = GridNotifyEvaluator.evaluate(
            plans = listOf(plan(lastNotifiedLevelPrice = 8.67)),
            prices = mapOf("sh.600000" to 9.5),
            transactionsByStock = emptyMap()
        )
        assertThat(evaluation.clearedPlanIds).containsExactly("p1")
        // 现价 9.5 仍低于 10.00 档且该档未提醒过 → 依旧提醒 10.00
        assertThat(evaluation.signals).hasSize(1)
        assertThat(evaluation.signals[0].levelPrice).isEqualTo(10.0)
    }

    /** 现价仍在上次提醒档之下 → 不清空、不重复提醒。 */
    @Test
    fun `no clear when price stays below last notified level`() {
        val evaluation = GridNotifyEvaluator.evaluate(
            plans = listOf(plan(lastNotifiedLevelPrice = 8.67)),
            prices = mapOf("sh.600000" to 8.5),
            transactionsByStock = emptyMap()
        )
        assertThat(evaluation.clearedPlanIds).isEmpty()
        // crossed = min{档位 ≥ 8.5} = 8.67 == lastNotified → 跳过
        assertThat(evaluation.signals).isEmpty()
    }

    /** 跳空跌穿多档（现价 8.5）→ 只提醒最深到达档 8.67，一次不刷屏。 */
    @Test
    fun `notifies deepest crossed level on gap down`() {
        val evaluation = GridNotifyEvaluator.evaluate(
            plans = listOf(plan()),
            prices = mapOf("sh.600000" to 8.5),
            transactionsByStock = emptyMap()
        )
        assertThat(evaluation.signals).hasSize(1)
        assertThat(evaluation.signals[0].levelPrice).isEqualTo(8.67)
    }

    /** 现价跌破资金用完位 → 提醒最后一档（8.00），资金打完的最终提示。 */
    @Test
    fun `notifies final level when price falls below low price`() {
        val evaluation = GridNotifyEvaluator.evaluate(
            plans = listOf(plan()),
            prices = mapOf("sh.600000" to 7.9),
            transactionsByStock = emptyMap()
        )
        assertThat(evaluation.signals).hasSize(1)
        assertThat(evaluation.signals[0].levelPrice).isEqualTo(8.0)
    }

    /** 现价高于买入起点 → 未到达任何档，不提醒。 */
    @Test
    fun `no signal when price above base`() {
        val evaluation = GridNotifyEvaluator.evaluate(
            plans = listOf(plan()),
            prices = mapOf("sh.600000" to 10.5),
            transactionsByStock = emptyMap()
        )
        assertThat(evaluation.signals).isEmpty()
        assertThat(evaluation.notifiedLevels).isEmpty()
    }

    /** 开关关闭 → 整个计划跳过。 */
    @Test
    fun `skips disabled plan`() {
        val evaluation = GridNotifyEvaluator.evaluate(
            plans = listOf(plan(notifyEnabled = false)),
            prices = mapOf("sh.600000" to 9.9),
            transactionsByStock = emptyMap()
        )
        assertThat(evaluation.signals).isEmpty()
    }

    /** 无现价（行情拉取失败）→ 跳过，不臆造。 */
    @Test
    fun `skips plan without price`() {
        val evaluation = GridNotifyEvaluator.evaluate(
            plans = listOf(plan()),
            prices = emptyMap(),
            transactionsByStock = emptyMap()
        )
        assertThat(evaluation.signals).isEmpty()
    }

    /** 到达档已有实际 BUY 成交（markTriggeredLevels 同口径命中）→ 不唠叨。 */
    @Test
    fun `skips level already executed by transaction`() {
        // 半步长 = (8.67−8.00)/2 = 0.335；BUY@9.4 命中 9.33 档
        val evaluation = GridNotifyEvaluator.evaluate(
            plans = listOf(plan()),
            prices = mapOf("sh.600000" to 9.0),
            transactionsByStock = mapOf("sh.600000" to listOf(tx(9.4)))
        )
        // crossed = min{档位 ≥ 9.0} = 9.33 已触发 → 无提醒；下一档 8.67 未到达
        assertThat(evaluation.signals).isEmpty()
    }

    /** 计划参数非法（grids < 2 → validationError）→ 跳过。 */
    @Test
    fun `skips invalid plan parameters`() {
        val invalid = plan().copy(grids = 1)
        val evaluation = GridNotifyEvaluator.evaluate(
            plans = listOf(invalid),
            prices = mapOf("sh.600000" to 9.9),
            transactionsByStock = emptyMap()
        )
        assertThat(evaluation.signals).isEmpty()
    }

    /** 等比网格（16/8/4，比值 2）：现价 15 到达 16 档——档位按 gridType 正确生成。 */
    @Test
    fun `geometric plan notifies on geometric levels`() {
        val geom = GridPlanEntity(
            id = "p2",
            stockCode = "sh.600000",
            stockName = "浦发银行",
            basePrice = 16.0,
            lowPrice = 4.0,
            highPrice = 20.0,
            grids = 3,
            totalCapital = 100000.0,
            gridType = "GEOM"
        )
        val evaluation = GridNotifyEvaluator.evaluate(
            plans = listOf(geom),
            prices = mapOf("sh.600000" to 15.0),
            transactionsByStock = emptyMap()
        )
        assertThat(evaluation.signals).hasSize(1)
        // 等比档位 4/8/16；现价 15 ≤ 16 → 到达最贵档（等差则会是别的分布）
        assertThat(evaluation.signals[0].levelPrice).isEqualTo(16.0)
    }
}
