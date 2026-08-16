package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * [GridBacktestCalculator]（网格历史回测）单测。
 *
 * 口径：日收盘价逐日推进，收盘 ≤ 档位价即触发（按档位价成交）；
 * 对照基准 = 窗口首日收盘一次性买入（同资金、整手）。
 */
class GridBacktestCalculatorTest {

    private fun bar(date: String, close: Double) = KlineBar(
        date = date, open = close, close = close, high = close, low = close, volume = 1.0
    )

    /** 全程高于买入起点 → 0 档触发，均价/节省为 null，对照基准仍可算。 */
    @Test
    fun `no trigger when price stays above base`() {
        val klines = listOf(bar("2026-01-02", 10.5), bar("2026-01-05", 10.8), bar("2026-01-06", 11.0))
        val r = GridBacktestCalculator.backtest(klines, 10.0, 8.0, 12.0, 4, 100000.0)
        assertThat(r).isNotNull()
        assertThat(r!!.triggeredCount).isEqualTo(0)
        assertThat(r.avgBuyPrice).isNull()
        assertThat(r.costSavingPct).isNull()
        assertThat(r.lumpSumPrice).isEqualTo(10.5)
        assertThat(r.firstTriggerDate).isNull()
    }

    /** 一路跌穿资金用完位 → 4 档全触发，网格均价低于首日一次性买入（节省为正）。 */
    @Test
    fun `full trigger when price falls through low`() {
        val klines = listOf(
            bar("2026-01-02", 9.5), bar("2026-01-05", 9.0),
            bar("2026-01-06", 8.5), bar("2026-01-07", 7.9)
        )
        val r = GridBacktestCalculator.backtest(klines, 10.0, 8.0, 12.0, 4, 100000.0)!!
        assertThat(r.triggeredCount).isEqualTo(4)
        assertThat(r.totalLevels).isEqualTo(4)
        // 首日 9.5 已 ≤ 买入起点档 10.0 → base 档当日触发；9.33 档次日（9.0 ≤ 9.33）
        assertThat(r.firstTriggerDate).isEqualTo("2026-01-02")
        assertThat(r.lastTriggerDate).isEqualTo("2026-01-07")
        // 对照：首日 9.5 一次性可买 (100000/9.5/100)×100 = 10500 股
        assertThat(r.lumpSumPrice).isEqualTo(9.5)
        assertThat(r.lumpSumShares).isEqualTo(10500)
        // 网格只买 9.33 以下 → 均价必然低于 9.5，节省为正
        assertThat(r.avgBuyPrice!!).isLessThan(9.5)
        assertThat(r.costSavingPct!!).isGreaterThan(0.0)
        assertThat(r.capitalUtilizationPct).isNotNull()
    }

    /** 震荡部分触发：只命中 9.33/8.67 两档，首末触发日正确。 */
    @Test
    fun `partial trigger tracks first and last dates`() {
        val klines = listOf(
            bar("2026-01-02", 9.3), bar("2026-01-05", 9.4),
            bar("2026-01-06", 8.6), bar("2026-01-07", 9.0)
        )
        val r = GridBacktestCalculator.backtest(klines, 10.0, 8.0, 12.0, 4, 100000.0)!!
        // 首日 9.3 触发 base 档 10.0 与 9.33 档；01-06 的 8.6 再触发 8.67 → 共 3 档
        assertThat(r.triggeredCount).isEqualTo(3)
        assertThat(r.firstTriggerDate).isEqualTo("2026-01-02")
        assertThat(r.lastTriggerDate).isEqualTo("2026-01-06")
        assertThat(r.boughtShares).isGreaterThan(0)
        // 窗口 min/max 收盘
        assertThat(r.minClose).isEqualTo(8.6)
        assertThat(r.maxClose).isEqualTo(9.4)
    }

    /** 空窗口/单根 K 线 → null（不臆造）。 */
    @Test
    fun `null for empty or single kline`() {
        assertThat(GridBacktestCalculator.backtest(emptyList(), 10.0, 8.0, 12.0, 4, 100000.0)).isNull()
        assertThat(
            GridBacktestCalculator.backtest(listOf(bar("2026-01-02", 9.0)), 10.0, 8.0, 12.0, 4, 100000.0)
        ).isNull()
    }

    /** 等比网格回测：16/8/4 三档（比值 2），15/7/3.5 逐日跌穿 → 3 档全触发。 */
    @Test
    fun `geometric grid backtest triggers all levels`() {
        val klines = listOf(bar("2026-01-02", 15.0), bar("2026-01-05", 7.0), bar("2026-01-06", 3.5))
        val r = GridBacktestCalculator.backtest(
            klines, 16.0, 4.0, 20.0, 3, 100000.0, gridType = GridType.GEOMETRIC
        )!!
        assertThat(r.triggeredCount).isEqualTo(3)
        // 4/8/16 按 1/price 加权：股数 14200/3500/800 → 均价 97600/18500 ≈ 5.28（越便宜买越多拉低均价）
        assertThat(r.avgBuyPrice!!).isWithin(0.05).of(5.28)
    }

    /** 计划参数非法（grids<2）→ null。 */
    @Test
    fun `null for invalid plan parameters`() {
        val klines = listOf(bar("2026-01-02", 9.0), bar("2026-01-05", 8.5))
        assertThat(GridBacktestCalculator.backtest(klines, 10.0, 8.0, 12.0, 1, 100000.0)).isNull()
    }
}
