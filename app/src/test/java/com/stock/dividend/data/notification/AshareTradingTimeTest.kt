package com.stock.dividend.data.notification

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDateTime

/** [AshareTradingTime]（A 股交易时段守卫）单测：周一至五 9:15–15:15 含头含尾。 */
class AshareTradingTimeTest {

    /** 2026-08-14 是周五。 */
    @Test
    fun `weekday trading hours are in window`() {
        assertThat(AshareTradingTime.isTradingWindow(LocalDateTime.of(2026, 8, 14, 10, 0))).isTrue()
        assertThat(AshareTradingTime.isTradingWindow(LocalDateTime.of(2026, 8, 14, 13, 30))).isTrue()  // 午休不细分
    }

    /** 边界：9:15 含 / 9:14 不含 / 15:15 含 / 15:16 不含。 */
    @Test
    fun `boundaries are inclusive at both ends`() {
        assertThat(AshareTradingTime.isTradingWindow(LocalDateTime.of(2026, 8, 14, 9, 15))).isTrue()
        assertThat(AshareTradingTime.isTradingWindow(LocalDateTime.of(2026, 8, 14, 9, 14))).isFalse()
        assertThat(AshareTradingTime.isTradingWindow(LocalDateTime.of(2026, 8, 14, 15, 15))).isTrue()
        assertThat(AshareTradingTime.isTradingWindow(LocalDateTime.of(2026, 8, 14, 15, 16))).isFalse()
    }

    /** 周末不交易。 */
    @Test
    fun `weekend is out of window`() {
        assertThat(AshareTradingTime.isTradingWindow(LocalDateTime.of(2026, 8, 15, 10, 0))).isFalse()  // 周六
        assertThat(AshareTradingTime.isTradingWindow(LocalDateTime.of(2026, 8, 16, 10, 0))).isFalse()  // 周日
    }

    /** 盘前盘后不交易。 */
    @Test
    fun `pre market and after hours are out`() {
        assertThat(AshareTradingTime.isTradingWindow(LocalDateTime.of(2026, 8, 14, 9, 0))).isFalse()
        assertThat(AshareTradingTime.isTradingWindow(LocalDateTime.of(2026, 8, 14, 16, 0))).isFalse()
    }
}
