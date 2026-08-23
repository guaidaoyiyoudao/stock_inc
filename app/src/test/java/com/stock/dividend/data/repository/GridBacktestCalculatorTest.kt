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

    /** 自定义比例回测：贵档多配（[1,3]）抬高网格均价，与反比默认（越便宜越多）方向相反。 */
    @Test
    fun `custom weights backtest allocates by given ratio`() {
        val klines = listOf(bar("2026-01-02", 9.5), bar("2026-01-05", 7.9))
        val inverse = GridBacktestCalculator.backtest(klines, 10.0, 8.0, 12.0, 2, 100000.0)!!
        val custom = GridBacktestCalculator.backtest(
            klines, 10.0, 8.0, 12.0, 2, 100000.0, levelWeights = listOf(1.0, 3.0)
        )!!
        assertThat(custom.triggeredCount).isEqualTo(2)
        // 反比：@8 档 6900 股 + @10 档 4400 股，均价 ≈8.78；自定义 [1,3]：3100 + 7500 股，均价 ≈9.42
        assertThat(custom.avgBuyPrice!!).isGreaterThan(inverse.avgBuyPrice!!)
        assertThat(custom.avgBuyPrice).isWithin(0.01).of(9.42)
    }

    // ── 波段模式回测（底仓 + 波段拆分 / 股息率卖出锚 / T+1）──
    // 两档网格 8/10、DPS=0.5：默认步长 1.25pp → 卖出锚 8 档 10.00、10 档 13.33；
    // 资金 100000 → 8 档 6900 股、10 档 4400 股；30% 波段 → 2000/1300 股，底仓 4900/3100。

    /** 震荡窗口两个回合（100% 波段无底仓）：8 档买→10.5 卖、10 档买→13.5 卖；期末空仓。 */
    @Test
    fun `swing backtest completes round trips in oscillating window`() {
        val klines = listOf(
            bar("2026-01-02", 9.5),   // 买 10.00 档
            bar("2026-01-05", 8.0),   // 买 8.00 档（收盘 8.0 = 档位价）
            bar("2026-01-06", 10.5),  // 8.00 档到卖出锚 10.00 → 卖出（回合 1）
            bar("2026-01-07", 11.0),  // 无动作（10.00 档卖出锚 13.33 未到）
            bar("2026-01-08", 13.5)   // 10.00 档到卖出锚 13.33 → 卖出（回合 2）
        )
        val r = GridBacktestCalculator.backtest(
            klines, 10.0, 8.0, 12.0, 2, 100000.0,
            dps = 0.5, swingMode = true, swingRatioPercent = 100.0
        )!!
        assertThat(r.roundTrips).isEqualTo(2)
        assertThat(r.heldLevelCount).isEqualTo(0)
        assertThat(r.triggeredCount).isEqualTo(2)
        assertThat(r.boughtShares).isEqualTo(0)
        assertThat(r.investedAmount).isEqualTo(0.0)
        // 计划口径毛利 = (10.0−8.0)×6900 + (13.33−10.0)×4400 = 13800 + 14652 = 28452（未计费）
        assertThat(r.swingProfit).isWithin(0.01).of(28452.0)
        assertThat(r.swingProfitPct).isWithin(0.001).of(28.45)
        assertThat(r.feesPaid).isEqualTo(0.0)
    }

    /** 底仓不变（默认 30% 波段）：回合后底仓全部保留，期末净持仓 = 底仓 + 未卖波段。 */
    @Test
    fun `swing backtest keeps base position after round trip`() {
        val klines = listOf(
            bar("2026-01-02", 9.5),   // 买 10.00 档（底仓3100+波段1300）
            bar("2026-01-05", 8.0),   // 买 8.00 档（底仓4900+波段2000）
            bar("2026-01-06", 10.5),  // 8.00 档波段 2000 股卖出（回合 1，底仓 4900 不动）
            bar("2026-01-07", 11.0)   // 收盘
        )
        val r = GridBacktestCalculator.backtest(
            klines, 10.0, 8.0, 12.0, 2, 100000.0, dps = 0.5, swingMode = true
        )!!
        assertThat(r.roundTrips).isEqualTo(1)
        assertThat(r.swingProfit).isWithin(0.01).of((10.0 - 8.0) * 2000)
        // 底仓两档全部保留；10.00 档波段 1300 未到卖出锚仍在持
        assertThat(r.heldLevelCount).isEqualTo(2)
        assertThat(r.boughtShares).isEqualTo(4900 + 3100 + 1300)
        assertThat(r.investedAmount).isWithin(0.01).of(4900 * 8.0 + 3100 * 10.0 + 1300 * 10.0)
    }

    /** 费用假设：佣金/印花税逐笔计提，波段利润为扣除费用后的净额。 */
    @Test
    fun `swing backtest subtracts fees from round trip profit`() {
        val klines = listOf(
            bar("2026-01-02", 9.5), bar("2026-01-05", 8.0),
            bar("2026-01-06", 10.5), bar("2026-01-08", 13.5)
        )
        val r = GridBacktestCalculator.backtest(
            klines, 10.0, 8.0, 12.0, 2, 100000.0,
            dps = 0.5, swingMode = true, swingRatioPercent = 100.0,
            buyFeePercent = 0.025, sellFeePercent = 0.075
        )!!
        // 买入费 = (10×4400 + 8×6900) × 0.025% = 99200×0.00025 = 24.8
        // 卖出费 = (10.0×6900 + 13.33×4400) × 0.075% = (69000+58652)×0.00075 = 95.739
        assertThat(r.feesPaid).isWithin(0.01).of(120.54)
        assertThat(r.swingProfit).isWithin(0.01).of(28452.0 - 120.539)
    }

    /** 单边下跌窗口（波段模式）：无回合，底仓+波段全部建立——与纯买入持有行为一致。 */
    @Test
    fun `swing backtest monotonic down holds all levels`() {
        val klines = listOf(bar("2026-01-02", 9.5), bar("2026-01-05", 7.9))
        val r = GridBacktestCalculator.backtest(
            klines, 10.0, 8.0, 12.0, 2, 100000.0, dps = 0.5, swingMode = true
        )!!
        assertThat(r.roundTrips).isEqualTo(0)
        assertThat(r.heldLevelCount).isEqualTo(2)
        assertThat(r.triggeredCount).isEqualTo(2)
        assertThat(r.boughtShares).isEqualTo(6900 + 4400)
        assertThat(r.swingProfit).isEqualTo(0.0)
        assertThat(r.swingProfitPct).isEqualTo(0.0)
    }

    /** 回合后跌回档位：只补买波段股数（底仓不重复建），资金循环使用。 */
    @Test
    fun `swing backtest re-buys only swing shares on fall back`() {
        val klines = listOf(
            bar("2026-01-02", 9.5),   // 买 10.00 档全量
            bar("2026-01-05", 8.0),   // 买 8.00 档全量
            bar("2026-01-06", 10.5),  // 8.00 档波段 2000 卖出（回合 1）
            bar("2026-01-07", 8.0)    // 跌回 8.00 档 → 只补波段 2000
        )
        val r = GridBacktestCalculator.backtest(
            klines, 10.0, 8.0, 12.0, 2, 100000.0, dps = 0.5, swingMode = true
        )!!
        assertThat(r.roundTrips).isEqualTo(1)
        assertThat(r.heldLevelCount).isEqualTo(2)
        // 期末满持仓：两档底仓 + 两档波段（8 档波段已补回）
        assertThat(r.boughtShares).isEqualTo(6900 + 4400)
        assertThat(r.investedAmount).isWithin(0.01).of(8.0 * 6900 + 10.0 * 4400)
    }

    /** 纯买入模式回测（不开波段）：回合指标恒零，行为与历史版本一致。 */
    @Test
    fun `pure buy backtest keeps zero round trips`() {
        val klines = listOf(
            bar("2026-01-02", 9.5), bar("2026-01-05", 8.0),
            bar("2026-01-06", 10.5), bar("2026-01-08", 13.5)
        )
        val r = GridBacktestCalculator.backtest(klines, 10.0, 8.0, 12.0, 2, 100000.0)!!
        assertThat(r.roundTrips).isEqualTo(0)
        assertThat(r.swingProfit).isEqualTo(0.0)
        assertThat(r.swingProfitPct).isNull()
        // 纯买入：涨回也不卖，两档期末仍持有
        assertThat(r.triggeredCount).isEqualTo(2)
        assertThat(r.boughtShares).isEqualTo(6900 + 4400)
    }
}
