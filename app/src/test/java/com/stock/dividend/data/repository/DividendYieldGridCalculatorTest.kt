package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 股息率网格线计算器单测（纯函数，无 Android 依赖）。
 *
 * 金标准口径：价格 P = DPS ÷ (股息率/100)；档位对齐 0.5% 步长，仅保留区间内档位。
 */
class DividendYieldGridCalculatorTest {

    @Test
    fun `golden grid with hand computed prices and buy side flags`() {
        // DPS=0.6、现价 10（隐含 6.0%）、区间 [8.0, 12.5]：
        // 区间内整档股息率 5.0~7.5%（0.6/12.5=4.8% 起、0.6/8.0=7.5% 止），共 6 档。
        // 价格手算：5.0%→12.00，5.5%→0.6/0.055=10.9090→10.91，6.0%→10.00，
        //           6.5%→0.6/0.065=9.2307→9.23，7.0%→0.6/0.07=8.5714→8.57，7.5%→8.00（恰在边界，保留）。
        val lines = DividendYieldGridCalculator.computeLines(
            dps = 0.6, lowPrice = 8.0, highPrice = 12.5, currentPrice = 10.0
        )

        assertThat(lines.map { it.yieldPercent }).containsExactly(5.0, 5.5, 6.0, 6.5, 7.0, 7.5).inOrder()
        assertThat(lines.map { it.price }).containsExactly(12.0, 10.91, 10.0, 9.23, 8.57, 8.0).inOrder()
        // 现价 10：高于现价（10.91/12.0）与恰等于现价（10.0）为 false，低于现价为 true（买点侧）
        assertThat(lines.map { it.belowCurrent })
            .containsExactly(false, false, false, true, true, true).inOrder()
    }

    @Test
    fun `non aligned implied yield snaps to half percent grid`() {
        // DPS=0.6、现价 9.48 → 隐含 6.329% → 锚定 6.5% 档；结果只含 0.5% 整档，无 6.3% 之类的档外值
        val lines = DividendYieldGridCalculator.computeLines(
            dps = 0.6, lowPrice = 8.0, highPrice = 12.5, currentPrice = 9.48
        )

        assertThat(lines.map { it.yieldPercent }).contains(6.5)
        lines.forEach { yield ->
            assertThat((yield.yieldPercent * 2) % 1.0).isWithin(1e-9).of(0.0)
        }
    }

    @Test
    fun `empty when no grid line falls in range for very low yield stock`() {
        // DPS=0.05、现价 50（隐含 0.1%）：最低档 0.5% 对应价 10.0，远低于区间 [49, 51] → 空表
        val lines = DividendYieldGridCalculator.computeLines(
            dps = 0.05, lowPrice = 49.0, highPrice = 51.0, currentPrice = 50.0
        )

        assertThat(lines).isEmpty()
    }

    @Test
    fun `null or non positive dps returns empty`() {
        assertThat(
            DividendYieldGridCalculator.computeLines(dps = null, lowPrice = 8.0, highPrice = 12.0, currentPrice = 10.0)
        ).isEmpty()
        assertThat(
            DividendYieldGridCalculator.computeLines(dps = 0.0, lowPrice = 8.0, highPrice = 12.0, currentPrice = 10.0)
        ).isEmpty()
        assertThat(
            DividendYieldGridCalculator.computeLines(dps = -0.6, lowPrice = 8.0, highPrice = 12.0, currentPrice = 10.0)
        ).isEmpty()
    }

    @Test
    fun `invalid price range returns empty`() {
        // 高低价倒挂 / 相等 / 非正 → 空表
        assertThat(
            DividendYieldGridCalculator.computeLines(dps = 0.6, lowPrice = 12.0, highPrice = 8.0, currentPrice = 10.0)
        ).isEmpty()
        assertThat(
            DividendYieldGridCalculator.computeLines(dps = 0.6, lowPrice = 10.0, highPrice = 10.0, currentPrice = 10.0)
        ).isEmpty()
        assertThat(
            DividendYieldGridCalculator.computeLines(dps = 0.6, lowPrice = 0.0, highPrice = 10.0, currentPrice = 10.0)
        ).isEmpty()
    }

    @Test
    fun `missing current price anchors at midpoint and keeps belowCurrent null`() {
        // 现价缺失：用区间中点（(8+12)/2=10 → 隐含 6.0%）锚定；档位 5.0~7.5% 全部落在 [8,12]，
        // 7.5% 档价 8.0 恰在边界保留；belowCurrent 全为 null（无现价可比较）
        val lines = DividendYieldGridCalculator.computeLines(
            dps = 0.6, lowPrice = 8.0, highPrice = 12.0, currentPrice = null
        )

        assertThat(lines.map { it.yieldPercent }).containsExactly(5.0, 5.5, 6.0, 6.5, 7.0, 7.5).inOrder()
        assertThat(lines.all { it.belowCurrent == null }).isTrue()
    }

    @Test
    fun `one percent step produces coarser grid`() {
        // 步长 1.0%：区间 [8.0, 12.5] 内仅 5/6/7% 三档（4%→15 超上界，8%→7.5 低于下界）
        val lines = DividendYieldGridCalculator.computeLines(
            dps = 0.6, lowPrice = 8.0, highPrice = 12.5, currentPrice = 10.0,
            stepPercent = 1.0
        )

        assertThat(lines.map { it.yieldPercent }).containsExactly(5.0, 6.0, 7.0).inOrder()
        assertThat(lines.map { it.price }).containsExactly(12.0, 10.0, 8.57).inOrder()
    }
}
