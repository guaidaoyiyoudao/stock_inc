package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class JsonExtractionTest {

    @Test
    fun `raw starting with brace returned as is`() {
        val raw = """{"a":1}"""
        assertThat(JsonExtraction.extractJsonObject(raw)).isEqualTo(raw)
    }

    @Test
    fun `json fenced with language tag is extracted`() {
        val raw = "```json\n{\"a\":1}\n```"
        assertThat(JsonExtraction.extractJsonObject(raw)).isEqualTo("""{"a":1}""")
    }

    @Test
    fun `json fenced without language tag is extracted`() {
        val raw = "```\n{\"a\":1}\n```"
        assertThat(JsonExtraction.extractJsonObject(raw)).isEqualTo("""{"a":1}""")
    }

    @Test
    fun `bare json embedded in surrounding text is sliced`() {
        val raw = "解析结果如下：{\"a\":1} 请参考。"
        assertThat(JsonExtraction.extractJsonObject(raw)).isEqualTo("""{"a":1}""")
    }

    @Test
    fun `text without braces returns null`() {
        assertThat(JsonExtraction.extractJsonObject("纯文本无对象")).isNull()
    }

    @Test
    fun `empty string returns null`() {
        assertThat(JsonExtraction.extractJsonObject("")).isNull()
    }
}
