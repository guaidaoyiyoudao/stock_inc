package com.stock.dividend.ui.component

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

class DateFormatUtilTest {

    @Test
    fun `formatTimestamp returns formatted date string`() {
        val timestamp = 1710000000000L // 2024-03-09 UTC
        val result = formatTimestamp(timestamp)
        assertThat(result).matches("\\d{4}-\\d{2}-\\d{2}")
    }

    @Test
    fun `formatTimestamp handles zero timestamp`() {
        val result = formatTimestamp(0L)
        assertThat(result).matches("\\d{4}-\\d{2}-\\d{2}")
    }
}

internal fun formatTimestamp(timestamp: Long): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    return sdf.format(java.util.Date(timestamp))
}
