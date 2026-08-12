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
}
