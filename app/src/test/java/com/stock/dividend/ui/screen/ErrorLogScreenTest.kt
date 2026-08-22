package com.stock.dividend.ui.screen

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.TimeZone

/** [formatLogTimestamp] 时间格式化纯函数测试（固定时区保证断言稳定）。 */
class ErrorLogScreenTest {

    private val originalZone = TimeZone.getDefault()

    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(originalZone)
    }

    @Test
    fun `formats epoch millis to local datetime`() {
        // 1_700_000_000_000 = 2023-11-14T22:13:20Z → 上海 +8 = 2023-11-15 06:13:20
        assertThat(formatLogTimestamp(1_700_000_000_000L))
            .isEqualTo("2023-11-15 06:13:20")
    }

    @Test
    fun `zero epoch formats to epoch start`() {
        assertThat(formatLogTimestamp(0L)).isEqualTo("1970-01-01 08:00:00")
    }
}
