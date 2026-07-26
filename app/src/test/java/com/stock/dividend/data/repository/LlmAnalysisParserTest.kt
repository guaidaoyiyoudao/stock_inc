package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LlmAnalysisParserTest {

    @Test
    fun `parses full json`() {
        val raw = """{"overview":"组合偏防御","stockComments":{"600036":"低估"},"risks":["银行占比高"]}"""
        val a = LlmAnalysisParser.parse(raw)
        assertThat(a.overview).isEqualTo("组合偏防御")
        assertThat(a.stockComments["600036"]).isEqualTo("低估")
        assertThat(a.risks).containsExactly("银行占比高")
    }

    @Test
    fun `missing risks yields empty list`() {
        val raw = """{"overview":"x","stockComments":{}}"""
        val a = LlmAnalysisParser.parse(raw)
        assertThat(a.risks).isEmpty()
    }

    @Test
    fun `missing stockComments yields empty map`() {
        val raw = """{"overview":"x","risks":[]}"""
        val a = LlmAnalysisParser.parse(raw)
        assertThat(a.stockComments).isEmpty()
    }

    @Test
    fun `json fenced in code block is extracted`() {
        val raw = "```json\n{\"overview\":\"fenced\"}\n```"
        assertThat(LlmAnalysisParser.parse(raw).overview).isEqualTo("fenced")
    }

    @Test
    fun `plain text falls back to overview`() {
        val raw = "这只是一段纯文本解读，没有 JSON。"
        val a = LlmAnalysisParser.parse(raw)
        assertThat(a.overview).isEqualTo(raw)
        assertThat(a.stockComments).isEmpty()
        assertThat(a.risks).isEmpty()
    }

    @Test
    fun `malformed json does not throw`() {
        val raw = """{"overview": broken"""
        val a = LlmAnalysisParser.parse(raw)
        assertThat(a.overview).isNotEmpty()
    }
}
