package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * [MaDcaStrategyCalculator] 单测：年线定投策略（红利 ETF 经典款）——
 * 250 日均线下方开启定投窗口；高于年线 7.5% 卖出一半、15% 全部卖出。
 */
class MaDcaStrategyCalculatorTest {

    /** 250 根收盘价全为 10 → 年线 = 10（便于用价格直接控制偏离度）。 */
    private val flatCloses = List(250) { 10.0 }

    @Test
    fun `现价低于年线返回定投窗口信号`() {
        val e = MaDcaStrategyCalculator.evaluate(flatCloses, currentPrice = 9.5)!!
        assertThat(e.signal).isEqualTo(MaDcaSignal.DCA_WINDOW)
        assertThat(e.maValue).isWithin(1e-9).of(10.0)
        assertThat(e.deviationPercent).isWithin(1e-9).of(-5.0)
    }

    @Test
    fun `现价等于年线不算低于年线`() {
        val e = MaDcaStrategyCalculator.evaluate(flatCloses, currentPrice = 10.0)!!
        assertThat(e.signal).isEqualTo(MaDcaSignal.HOLD)
        assertThat(e.deviationPercent).isWithin(1e-9).of(0.0)
    }

    @Test
    fun `偏离恰达卖半阈值计为卖一半`() {
        val e = MaDcaStrategyCalculator.evaluate(flatCloses, currentPrice = 10.75)!!
        assertThat(e.signal).isEqualTo(MaDcaSignal.SELL_HALF)
        assertThat(e.deviationPercent).isWithin(1e-9).of(7.5)
    }

    @Test
    fun `偏离低于卖半阈值为持有`() {
        val e = MaDcaStrategyCalculator.evaluate(flatCloses, currentPrice = 10.7)!!
        assertThat(e.signal).isEqualTo(MaDcaSignal.HOLD)
    }

    @Test
    fun `偏离恰达清仓阈值计为全卖`() {
        val e = MaDcaStrategyCalculator.evaluate(flatCloses, currentPrice = 11.5)!!
        assertThat(e.signal).isEqualTo(MaDcaSignal.SELL_ALL)
        assertThat(e.deviationPercent).isWithin(1e-9).of(15.0)
    }

    @Test
    fun `均线只取末 maPeriod 根`() {
        // 251 根：最早的 1 根不应参与年线计算（末 250 根全为 10）
        val closes = listOf(999.0) + flatCloses
        val e = MaDcaStrategyCalculator.evaluate(closes, currentPrice = 10.0)!!
        assertThat(e.maValue).isWithin(1e-9).of(10.0)
    }

    @Test
    fun `收盘价不足周期数返回 null（上市不足一年）`() {
        val e = MaDcaStrategyCalculator.evaluate(List(249) { 10.0 }, currentPrice = 10.0)
        assertThat(e).isNull()
    }

    @Test
    fun `收盘价含零或非法值返回 null`() {
        assertThat(MaDcaStrategyCalculator.evaluate(flatCloses + 0.0, currentPrice = 10.0)).isNull()
        assertThat(
            MaDcaStrategyCalculator.evaluate(
                flatCloses.dropLast(1) + Double.NaN, currentPrice = 10.0
            )
        ).isNull()
    }

    @Test
    fun `现价非法返回 null`() {
        assertThat(MaDcaStrategyCalculator.evaluate(flatCloses, currentPrice = 0.0)).isNull()
        assertThat(MaDcaStrategyCalculator.evaluate(flatCloses, currentPrice = Double.NaN)).isNull()
    }

    @Test
    fun `自定义周期与阈值生效`() {
        val e = MaDcaStrategyCalculator.evaluate(
            closes = List(60) { 5.0 },
            currentPrice = 5.0,
            maPeriod = 60,
            sellHalfPercent = 5.0,
            sellAllPercent = 10.0
        )!!
        assertThat(e.signal).isEqualTo(MaDcaSignal.HOLD)
        assertThat(e.sellHalfTriggerPrice).isWithin(1e-9).of(5.25)
        assertThat(e.sellAllTriggerPrice).isWithin(1e-9).of(5.5)
    }

    @Test
    fun `卖出触发价等于年线乘阈值系数`() {
        val e = MaDcaStrategyCalculator.evaluate(flatCloses, currentPrice = 10.0)!!
        assertThat(e.sellHalfTriggerPrice).isWithin(1e-9).of(10.75)
        assertThat(e.sellAllTriggerPrice).isWithin(1e-9).of(11.5)
    }

    @Test
    fun `卖一半按整手向下取整`() {
        assertThat(MaDcaStrategyCalculator.sellSharesFor(MaDcaSignal.SELL_HALF, 500)).isEqualTo(200)
        assertThat(MaDcaStrategyCalculator.sellSharesFor(MaDcaSignal.SELL_HALF, 300)).isEqualTo(100)
        assertThat(MaDcaStrategyCalculator.sellSharesFor(MaDcaSignal.SELL_HALF, 150)).isEqualTo(0)
        assertThat(MaDcaStrategyCalculator.sellSharesFor(MaDcaSignal.SELL_HALF, 0)).isEqualTo(0)
    }

    @Test
    fun `全卖返回全部持仓`() {
        assertThat(MaDcaStrategyCalculator.sellSharesFor(MaDcaSignal.SELL_ALL, 700)).isEqualTo(700)
    }

    @Test
    fun `非卖出信号不产生卖出股数`() {
        assertThat(MaDcaStrategyCalculator.sellSharesFor(MaDcaSignal.HOLD, 700)).isEqualTo(0)
        assertThat(MaDcaStrategyCalculator.sellSharesFor(MaDcaSignal.DCA_WINDOW, 700)).isEqualTo(0)
    }

    @Test
    fun `定投金额按现价折整手股数`() {
        assertThat(MaDcaStrategyCalculator.dcaBuyShares(1000.0, 3.45)).isEqualTo(200)
        assertThat(MaDcaStrategyCalculator.dcaBuyShares(1000.0, 9.99)).isEqualTo(100)
        assertThat(MaDcaStrategyCalculator.dcaBuyShares(99.0, 9.99)).isEqualTo(0)
    }

    @Test
    fun `maSeries 前 period 减 1 个为 null 之后为滚动均值`() {
        val series = MaDcaStrategyCalculator.maSeries(listOf(1.0, 2.0, 3.0, 4.0, 5.0), period = 3)
        assertThat(series).hasSize(5)
        assertThat(series[0]).isNull()
        assertThat(series[1]).isNull()
        assertThat(series[2]!!).isWithin(1e-9).of(2.0)
        assertThat(series[3]!!).isWithin(1e-9).of(3.0)
        assertThat(series[4]!!).isWithin(1e-9).of(4.0)
    }

    @Test
    fun `maSeries 不足周期返回全 null 序列`() {
        val series = MaDcaStrategyCalculator.maSeries(listOf(1.0, 2.0), period = 3)
        assertThat(series).containsExactly(null, null).inOrder()
    }

    @Test
    fun `参数校验 各非法分支`() {
        assertThat(
            MaDcaStrategyCalculator.validateParams(maPeriod = 1, 7.5, 15.0, 1000.0)
        ).isNotNull()
        assertThat(
            MaDcaStrategyCalculator.validateParams(250, 0.0, 15.0, 1000.0)
        ).isNotNull()
        assertThat(
            MaDcaStrategyCalculator.validateParams(250, 15.0, 15.0, 1000.0)
        ).isNotNull()
        assertThat(
            MaDcaStrategyCalculator.validateParams(250, 7.5, 15.0, 0.0)
        ).isNotNull()
    }

    @Test
    fun `参数校验 合法参数返回 null`() {
        assertThat(
            MaDcaStrategyCalculator.validateParams(250, 7.5, 15.0, 1000.0)
        ).isNull()
    }
}
