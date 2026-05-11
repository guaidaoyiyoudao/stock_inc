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

    @Test
    fun `normalizeCompanyCode strips market prefix`() {
        assertThat(normalizeCompanyCode("sh.600519")).isEqualTo("600519")
        assertThat(normalizeCompanyCode("sz.000001")).isEqualTo("000001")
    }

    @Test
    fun `companyIconFallbackLabel uses stock name before code`() {
        assertThat(companyIconFallbackLabel("贵州茅台", "600519")).isEqualTo("贵")
    }

    @Test
    fun `companyIconFallbackLabel falls back to code when stock name is blank`() {
        assertThat(companyIconFallbackLabel("", "600519")).isEqualTo("6")
    }

    @Test
    fun `companyLogoUrlForCode resolves common A-share logo`() {
        assertThat(companyLogoUrlForCode("sh.600036"))
            .isEqualTo("https://s3-symbol-logo.tradingview.com/china-merchants-bank.svg")
    }

    @Test
    fun `aShareLogoMap covers all TradingView China common stocks with logos`() {
        assertThat(A_SHARE_LOGO_ID_COUNT).isEqualTo(5275)
        assertThat(aShareLogoIdForCode("600036")).isEqualTo("china-merchants-bank")
        assertThat(aShareLogoIdForCode("301666")).isNull()
    }
}

internal fun formatTimestamp(timestamp: Long): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    return sdf.format(java.util.Date(timestamp))
}
