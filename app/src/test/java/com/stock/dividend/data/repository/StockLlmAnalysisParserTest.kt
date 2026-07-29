package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StockLlmAnalysisParserTest {

    @Test
    fun `parses full json`() {
        val raw = """{"valuation":"当前价格偏低","dividendSustainability":"分红稳定","action":"可逢低关注","risks":["银行业绩波动"]}"""
        val a = StockLlmAnalysisParser.parse(raw)
        assertThat(a.valuation).isEqualTo("当前价格偏低")
        assertThat(a.dividendSustainability).isEqualTo("分红稳定")
        assertThat(a.action).isEqualTo("可逢低关注")
        assertThat(a.risks).containsExactly("银行业绩波动")
    }

    @Test
    fun `missing risks yields empty list`() {
        val raw = """{"valuation":"x","dividendSustainability":"y","action":"z"}"""
        val a = StockLlmAnalysisParser.parse(raw)
        assertThat(a.risks).isEmpty()
    }

    @Test
    fun `missing action yields empty string`() {
        val raw = """{"valuation":"x","dividendSustainability":"y","risks":[]}"""
        val a = StockLlmAnalysisParser.parse(raw)
        assertThat(a.action).isEqualTo("")
    }

    @Test
    fun `missing dividendSustainability yields empty string`() {
        val raw = """{"valuation":"x","action":"z"}"""
        val a = StockLlmAnalysisParser.parse(raw)
        assertThat(a.dividendSustainability).isEqualTo("")
    }

    @Test
    fun `json fenced in code block is extracted`() {
        val raw = "```json\n{\"valuation\":\"fenced\",\"action\":\"ok\"}\n```"
        val a = StockLlmAnalysisParser.parse(raw)
        assertThat(a.valuation).isEqualTo("fenced")
        assertThat(a.action).isEqualTo("ok")
    }

    @Test
    fun `plain text falls back to valuation only`() {
        val raw = "这只是一段纯文本解读，没有 JSON。"
        val a = StockLlmAnalysisParser.parse(raw)
        assertThat(a.valuation).isEqualTo(raw)
        assertThat(a.dividendSustainability).isEqualTo("")
        assertThat(a.action).isEqualTo("")
        assertThat(a.risks).isEmpty()
    }

    @Test
    fun `malformed json does not throw and falls back`() {
        val raw = """{"valuation": broken"""
        val a = StockLlmAnalysisParser.parse(raw)
        assertThat(a.valuation).isNotEmpty()
    }

    @Test
    fun `empty string yields all-empty analysis`() {
        val a = StockLlmAnalysisParser.parse("")
        assertThat(a.valuation).isEqualTo("")
        assertThat(a.action).isEqualTo("")
        assertThat(a.risks).isEmpty()
    }
}
