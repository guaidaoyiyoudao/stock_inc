package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LlmAnalysisParserTest {

    @Test
    fun `parses full json with structured stock comments`() {
        val raw = """{"overview":"组合偏防御","stockComments":{"600036":{"brief":"低估可关注","risks":["银行占比高"]}},"risks":["整体股息率偏低"]}"""
        val a = LlmAnalysisParser.parse(raw)
        assertThat(a.overview).isEqualTo("组合偏防御")
        assertThat(a.stockComments["600036"]?.brief).isEqualTo("低估可关注")
        assertThat(a.stockComments["600036"]?.risks).containsExactly("银行占比高")
        assertThat(a.risks).containsExactly("整体股息率偏低")
    }

    @Test
    fun `legacy string stock comments map to brief`() {
        val raw = """{"overview":"x","stockComments":{"600036":"低估"},"risks":[]}"""
        val a = LlmAnalysisParser.parse(raw)
        assertThat(a.stockComments["600036"]?.brief).isEqualTo("低估")
        assertThat(a.stockComments["600036"]?.risks).isEmpty()
    }

    @Test
    fun `missing risks in stock comment yields empty list`() {
        val raw = """{"overview":"x","stockComments":{"600036":{"brief":"ok"}}}"""
        val a = LlmAnalysisParser.parse(raw)
        assertThat(a.stockComments["600036"]?.brief).isEqualTo("ok")
        assertThat(a.stockComments["600036"]?.risks).isEmpty()
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
