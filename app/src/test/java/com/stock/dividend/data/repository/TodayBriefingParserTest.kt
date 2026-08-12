package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TodayBriefingParserTest {

    @Test
    fun plainText_trimmedAndStrippedOfQuotes() {
        assertThat(TodayBriefingParser.parse("  \"组合今日上涨，建议关注。\"  "))
            .isEqualTo("组合今日上涨，建议关注。")
    }

    @Test
    fun jsonObjectBriefingField_extracted() {
        val raw = """{"briefing":"今日无信号，组合平静。"}"""
        assertThat(TodayBriefingParser.parse(raw)).isEqualTo("今日无信号，组合平静。")
    }

    @Test
    fun fencedJson_extracted() {
        val raw = "```json\n{\"briefing\":\"你好\"}\n```"
        assertThat(TodayBriefingParser.parse(raw)).isEqualTo("你好")
    }

    @Test
    fun truncated_over80Chars() {
        val long = "句".repeat(120)
        assertThat(TodayBriefingParser.parse(long).length).isAtMost(80)
    }
}
