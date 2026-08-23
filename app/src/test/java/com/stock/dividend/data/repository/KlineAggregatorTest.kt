package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 日线 → 周/月线本地聚合（[KlineAggregator]）纯函数测试。
 *
 * 关键规则：周期 K 的 date = 组内最后一个交易日（与尾根当前性判定兼容）；
 * open=首日、close=末日、high/low=极值、volume 求和；ISO 周跨年不劈叉。
 */
class KlineAggregatorTest {

    private fun bar(date: String, open: Double, close: Double, high: Double, low: Double, volume: Double) =
        KlineBar(date = date, open = open, close = close, high = high, low = low, volume = volume)

    @Test
    fun `daily passthrough`() {
        val dailies = listOf(bar("2026-08-17", 10.0, 10.5, 10.8, 9.9, 100.0))
        assertThat(KlineAggregator.aggregate(dailies, KlinePeriod.DAILY)).isSameInstanceAs(dailies)
    }

    @Test
    fun `weekly aggregation takes first open last close extremes and volume sum`() {
        // 两整周 + 半周（周四/周五），均为交易日升序
        val dailies = listOf(
            bar("2026-08-03", 10.0, 10.5, 10.8, 9.9, 100.0),   // W32 周一
            bar("2026-08-04", 10.5, 10.2, 10.9, 10.1, 110.0),
            bar("2026-08-05", 10.2, 10.6, 11.0, 10.0, 120.0),
            bar("2026-08-06", 10.6, 10.4, 10.7, 10.2, 130.0),
            bar("2026-08-07", 10.4, 10.8, 11.2, 10.3, 140.0),  // W32 周五
            bar("2026-08-10", 11.0, 11.5, 11.8, 10.9, 150.0),  // W33 周一
            bar("2026-08-11", 11.5, 11.2, 11.6, 11.1, 160.0),
            bar("2026-08-12", 11.2, 11.9, 12.0, 11.0, 170.0)   // W33 周三（本周最后交易日）
        )

        val weekly = KlineAggregator.aggregate(dailies, KlinePeriod.WEEKLY)

        assertThat(weekly).hasSize(2)
        val w32 = weekly[0]
        assertThat(w32.date).isEqualTo("2026-08-07")            // 组内最后交易日
        assertThat(w32.open).isWithin(1e-9).of(10.0)            // 首日 open
        assertThat(w32.close).isWithin(1e-9).of(10.8)           // 末日 close
        assertThat(w32.high).isWithin(1e-9).of(11.2)
        assertThat(w32.low).isWithin(1e-9).of(9.9)
        assertThat(w32.volume).isWithin(1e-9).of(600.0)         // 100+110+120+130+140
        val w33 = weekly[1]
        assertThat(w33.date).isEqualTo("2026-08-12")
        assertThat(w33.open).isWithin(1e-9).of(11.0)
        assertThat(w33.close).isWithin(1e-9).of(11.9)
        assertThat(w33.high).isWithin(1e-9).of(12.0)
        assertThat(w33.low).isWithin(1e-9).of(10.9)
        assertThat(w33.volume).isWithin(1e-9).of(480.0)
    }

    @Test
    fun `weekly iso week crossing year boundary stays in one group`() {
        // 2024-12-30(周一)/12-31(周二) 属 ISO 2025-W01，与 2025-01-02(周四)/01-03(周五) 同周
        val dailies = listOf(
            bar("2024-12-30", 10.0, 10.2, 10.3, 9.9, 100.0),
            bar("2024-12-31", 10.2, 10.1, 10.4, 10.0, 110.0),
            bar("2025-01-02", 10.1, 10.5, 10.6, 10.0, 120.0),
            bar("2025-01-03", 10.5, 10.8, 10.9, 10.4, 130.0),
            bar("2025-01-06", 10.8, 11.0, 11.1, 10.7, 140.0)    // 次周一，新一周
        )

        val weekly = KlineAggregator.aggregate(dailies, KlinePeriod.WEEKLY)

        assertThat(weekly).hasSize(2)
        assertThat(weekly[0].date).isEqualTo("2025-01-03")      // 跨年周的最后交易日
        assertThat(weekly[0].volume).isWithin(1e-9).of(460.0)
        assertThat(weekly[1].date).isEqualTo("2025-01-06")
    }

    @Test
    fun `monthly aggregation groups by calendar month`() {
        val dailies = listOf(
            bar("2026-07-30", 10.0, 10.4, 10.6, 9.9, 100.0),
            bar("2026-07-31", 10.4, 10.8, 10.9, 10.3, 110.0),
            bar("2026-08-03", 10.9, 11.2, 11.4, 10.8, 120.0),
            bar("2026-08-17", 11.2, 11.6, 11.8, 11.1, 130.0)
        )

        val monthly = KlineAggregator.aggregate(dailies, KlinePeriod.MONTHLY)

        assertThat(monthly).hasSize(2)
        val july = monthly[0]
        assertThat(july.date).isEqualTo("2026-07-31")
        assertThat(july.open).isWithin(1e-9).of(10.0)
        assertThat(july.close).isWithin(1e-9).of(10.8)
        assertThat(july.volume).isWithin(1e-9).of(210.0)
        val august = monthly[1]
        assertThat(august.date).isEqualTo("2026-08-17")
        assertThat(august.open).isWithin(1e-9).of(10.9)
        assertThat(august.close).isWithin(1e-9).of(11.6)
    }

    @Test
    fun `empty input yields empty output`() {
        assertThat(KlineAggregator.aggregate(emptyList(), KlinePeriod.WEEKLY)).isEmpty()
        assertThat(KlineAggregator.aggregate(emptyList(), KlinePeriod.MONTHLY)).isEmpty()
    }

    @Test
    fun `single daily bar forms single period bar`() {
        val dailies = listOf(bar("2026-08-17", 10.0, 10.5, 10.7, 9.8, 100.0))
        val weekly = KlineAggregator.aggregate(dailies, KlinePeriod.WEEKLY)
        assertThat(weekly).hasSize(1)
        assertThat(weekly[0].date).isEqualTo("2026-08-17")
        assertThat(weekly[0].open).isWithin(1e-9).of(10.0)
        assertThat(weekly[0].close).isWithin(1e-9).of(10.5)
    }
}
