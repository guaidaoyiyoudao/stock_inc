package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.local.entity.DividendEntity
import com.stock.dividend.data.local.entity.GridPlanEntity
import org.junit.Test
import java.time.LocalDate

class TodaySignalAggregatorTest {

    private val today = LocalDate.of(2026, 8, 12)

    private fun snapshot(
        code: String,
        name: String = code,
        price: Double? = 10.0,
        weekly: BollBand? = null,
        daily: BollBand? = null,
        monthly: BollBand? = null,
        dividend: Double? = null,
        bond: Double? = null,
        multiplier: Double = 2.5,
    ) = TodayStockSnapshot(
        code = code, name = name, price = price,
        weeklyBand = weekly, dailyBand = daily, monthlyBand = monthly,
        latestYearlyDividend = dividend, bondYield10Y = bond,
        buyThresholdMultiplier = multiplier,
    )

    @Test
    fun bollResonantBuy_triggersBuySignal() {
        // price 同时 ≤ 日/周下轨、≤ 月中轨，且股息率 ≥ 2% → HoldingRecommender.BUY
        val band = BollBand(middle = 10.0, upper = 11.0, lower = 9.0)
        val monthly = BollBand(middle = 10.5, upper = 12.0, lower = 9.0)
        val s = snapshot("sh.600000", "Test", price = 8.8, weekly = band, daily = band, monthly = monthly, dividend = 0.5)
        val result = TodaySignalAggregator.aggregate(TodaySignalInput(listOf(s), emptyList(), emptyMap(), emptyList(), today))
        val buy = result.first { it.type == TodaySignalType.BUY_TRIGGER }
        assertThat(buy.stockCode).isEqualTo("sh.600000")
        assertThat(buy.sortPriority).isEqualTo(0)
    }

    @Test
    fun dividendYieldReachesThreshold_triggersBuySignal() {
        // bond 2.6% × 2.5 = 6.5% 目标；价 5.0、年分红 0.4 → 8% ≥ 6.5% 触发
        val s = snapshot("sh.600001", price = 5.0, dividend = 0.4, bond = 2.6, multiplier = 2.5)
        val result = TodaySignalAggregator.aggregate(TodaySignalInput(listOf(s), emptyList(), emptyMap(), emptyList(), today))
        assertThat(result.any { it.type == TodaySignalType.BUY_TRIGGER && it.stockCode == "sh.600001" }).isTrue()
    }

    @Test
    fun noPrice_skipsBuySignal() {
        val s = snapshot("sh.600002", price = null, dividend = 0.4, bond = 2.6)
        val result = TodaySignalAggregator.aggregate(TodaySignalInput(listOf(s), emptyList(), emptyMap(), emptyList(), today))
        assertThat(result).isEmpty()
    }

    @Test
    fun gridNextLevel_triggersGridSignal() {
        val plan = GridPlanEntity(
            id = "g1", stockCode = "sh.600003", stockName = "Grid",
            basePrice = 10.0, lowPrice = 8.0, highPrice = 11.0, grids = 3, totalCapital = 9000.0
        )
        val prices = mapOf("sh.600003" to 9.6) // 现价在档位上方 → 有下一档
        val result = TodaySignalAggregator.aggregate(
            TodaySignalInput(emptyList(), listOf(plan), prices, emptyList(), today)
        )
        val grid = result.first { it.type == TodaySignalType.GRID_NEXT_LEVEL }
        assertThat(grid.stockName).isEqualTo("Grid")
    }

    @Test
    fun dividendWithin30Days_triggersCountdown() {
        val div = DividendEntity(
            id = "d1", stockCode = "sh.600004", reportDate = "2025-12-31",
            cashPerShare = 0.25, exDividendDate = "2026-08-20"
        )
        val result = TodaySignalAggregator.aggregate(
            TodaySignalInput(emptyList(), emptyList(), emptyMap(), listOf(div), today)
        )
        assertThat(result.any { it.type == TodaySignalType.DIVIDEND_COUNTDOWN }).isTrue()
    }

    @Test
    fun signalsSortedByPriorityThenCode() {
        val band = BollBand(middle = 10.0, upper = 11.0, lower = 9.0)
        val monthly = BollBand(middle = 10.5, upper = 12.0, lower = 9.0)
        val buyStock = snapshot("sh.999999", price = 8.8, weekly = band, daily = band, monthly = monthly, dividend = 0.5)
        val plan = GridPlanEntity("g", "sh.000001", "G", 10.0, 8.0, 11.0, 3, 9000.0)
        val prices = mapOf("sh.000001" to 9.6)
        val result = TodaySignalAggregator.aggregate(
            TodaySignalInput(listOf(buyStock), listOf(plan), prices, emptyList(), today)
        )
        // BUY(priority 0) 排在 GRID(priority 1) 前
        assertThat(result.first().type).isEqualTo(TodaySignalType.BUY_TRIGGER)
    }

    @Test
    fun dividendDateWithTimeSuffix_parsed() {
        val div = DividendEntity("d", "sh.600005", "2025-12-31", 0.1, exDividendDate = "2026-08-15 00:00:00")
        val result = TodaySignalAggregator.aggregate(
            TodaySignalInput(emptyList(), emptyList(), emptyMap(), listOf(div), today)
        )
        assertThat(result).isNotEmpty()
    }

    @Test
    fun weeklyBollLowerBreak_triggersBuySignal() {
        // 仅周线 BOLL，price ≤ lower；无日/月（三周期不共振）、无 bond（门槛不判）→ 应触发「跌破BOLL下轨」
        val band = BollBand(middle = 10.0, upper = 11.0, lower = 9.0)
        val s = snapshot("sh.600010", price = 8.8, weekly = band)
        val result = TodaySignalAggregator.aggregate(
            TodaySignalInput(listOf(s), emptyList(), emptyMap(), emptyList(), today)
        )
        assertThat(result.any { it.type == TodaySignalType.BUY_TRIGGER && it.title.contains("BOLL下轨") }).isTrue()
    }

    @Test
    fun sameStockTwoGridPlans_producesDistinctSignalKeys() {
        // 同股多套网格计划（合法场景）：两条 GRID 信号都要出，但 LazyColumn key 必须互不相同，
        // 否则今日页滚动到信号区组合 item 时抛「Key was already used」闪退（2026-08-16 修复的回归锁）
        val planA = GridPlanEntity("g1", "sh.600003", "Grid", 10.0, 8.0, 11.0, 3, 9000.0)
        val planB = GridPlanEntity("g2", "sh.600003", "Grid", 9.5, 7.0, 10.5, 4, 6000.0)
        val prices = mapOf("sh.600003" to 9.6)
        val result = TodaySignalAggregator.aggregate(
            TodaySignalInput(emptyList(), listOf(planA, planB), prices, emptyList(), today)
        )
        val grids = result.filter { it.type == TodaySignalType.GRID_NEXT_LEVEL }
        assertThat(grids).hasSize(2)
        assertThat(grids.map { it.key }.distinct()).hasSize(2)
    }

    @Test
    fun mixedSignals_allKeysUnique() {
        // 混合输入（买入 + 网格 + 分红同股并存）：全部信号的 key 全局唯一
        val band = BollBand(middle = 10.0, upper = 11.0, lower = 9.0)
        val monthly = BollBand(middle = 10.5, upper = 12.0, lower = 9.0)
        val s = snapshot("sh.600003", price = 8.8, weekly = band, daily = band, monthly = monthly, dividend = 0.5)
        val plan = GridPlanEntity("g1", "sh.600003", "Grid", 10.0, 8.0, 11.0, 3, 9000.0)
        val div = DividendEntity("d1", "sh.600003", "2025-12-31", 0.25, exDividendDate = "2026-08-20")
        val result = TodaySignalAggregator.aggregate(
            TodaySignalInput(listOf(s), listOf(plan), mapOf("sh.600003" to 9.6), listOf(div), today)
        )
        assertThat(result).hasSize(3)
        assertThat(result.map { it.key }.distinct()).hasSize(3)
    }
}
