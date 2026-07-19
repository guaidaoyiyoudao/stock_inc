package com.stock.dividend.data.scan

import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.scan.HoldingScreenshotParser
import org.junit.Test

class HoldingScreenshotParserTest {

    @Test
    fun `parses typical holdings rows with name code shares and price`() {
        val text = """
            证券名称 证券代码 持股数 成本价
            贵州茅台 600519 100 1500.00
            平安银行 000001 1000 12.34
        """.trimIndent()

        val rows = HoldingScreenshotParser.parse(text)

        assertThat(rows).hasSize(2)
        val maotai = rows[0]
        assertThat(maotai.codeOrName).isEqualTo("600519")
        assertThat(maotai.shares).isEqualTo(100)
        assertThat(maotai.costPerShare).isWithin(0.001).of(1500.00)
        val pingan = rows[1]
        assertThat(pingan.codeOrName).isEqualTo("000001")
        assertThat(pingan.shares).isEqualTo(1000)
        assertThat(pingan.costPerShare).isWithin(0.001).of(12.34)
    }

    @Test
    fun `prefers 6-digit code over chinese name when both present`() {
        val rows = HoldingScreenshotParser.parse("贵州茅台 600519 100 1500.00")
        assertThat(rows).hasSize(1)
        assertThat(rows[0].codeOrName).isEqualTo("600519")
    }

    @Test
    fun `falls back to chinese name when no 6-digit code`() {
        val rows = HoldingScreenshotParser.parse("贵州茅台 100 1500.00")
        assertThat(rows).hasSize(1)
        assertThat(rows[0].codeOrName).isEqualTo("贵州茅台")
        assertThat(rows[0].shares).isEqualTo(100)
    }

    @Test
    fun `strips thousand-separator commas in shares and price`() {
        val rows = HoldingScreenshotParser.parse("贵州茅台 600519 1,000 1,500.00")
        assertThat(rows).hasSize(1)
        assertThat(rows[0].shares).isEqualTo(1000)
        assertThat(rows[0].costPerShare).isWithin(0.001).of(1500.00)
    }

    @Test
    fun `strips unit suffixes like gu and yuan`() {
        val rows = HoldingScreenshotParser.parse("600519 100股 1500.00元")
        assertThat(rows).hasSize(1)
        assertThat(rows[0].shares).isEqualTo(100)
        assertThat(rows[0].costPerShare).isWithin(0.001).of(1500.00)
    }

    @Test
    fun `null cost when no decimal price found`() {
        val rows = HoldingScreenshotParser.parse("贵州茅台 600519 100")
        assertThat(rows).hasSize(1)
        assertThat(rows[0].shares).isEqualTo(100)
        assertThat(rows[0].costPerShare).isNull()
    }

    @Test
    fun `drops rows without shares`() {
        val rows = HoldingScreenshotParser.parse("贵州茅台 600519 1500.00")
        // 无股数 → 无意义行，丢弃
        assertThat(rows).isEmpty()
    }

    @Test
    fun `drops header and total rows`() {
        val text = """
            持仓明细
            证券名称 证券代码 持股数 成本价
            贵州茅台 600519 100 1500.00
            持仓市值 150000.00
            总计
        """.trimIndent()
        val rows = HoldingScreenshotParser.parse(text)
        assertThat(rows).hasSize(1)
        assertThat(rows[0].codeOrName).isEqualTo("600519")
    }

    @Test
    fun `does not confuse 6-digit code with shares count`() {
        // 代码 600519 不应被当成股数
        val rows = HoldingScreenshotParser.parse("贵州茅台 600519 100 1500.00")
        assertThat(rows[0].shares).isEqualTo(100)
    }

    @Test
    fun `blank text returns empty`() {
        assertThat(HoldingScreenshotParser.parse("")).isEmpty()
        assertThat(HoldingScreenshotParser.parse("   \n  \n")).isEmpty()
    }
}
