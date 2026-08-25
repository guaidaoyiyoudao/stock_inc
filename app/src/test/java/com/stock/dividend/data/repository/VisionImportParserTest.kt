package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** 视觉模型响应解析（纯函数，永不抛异常）。fixture 为内联三引号 JSON。 */
class VisionImportParserTest {

    @Test
    fun `holdings json parses rows`() {
        val raw = """
            {"screenshotType":"HOLDINGS","rows":[
              {"name":"贵州茅台","code":"600519","shares":100,"costPerShare":1500.5},
              {"name":"平安银行","code":"000001","shares":1000,"costPerShare":12.34}
            ]}
        """.trimIndent()

        val result = VisionImportParser.parse(raw)

        assertThat(result).isInstanceOf(VisionImportParseResult.Holdings::class.java)
        val holdings = result as VisionImportParseResult.Holdings
        assertThat(holdings.rows).hasSize(2)
        assertThat(holdings.rows[0].codeOrName).isEqualTo("600519")
        assertThat(holdings.rows[0].shares).isEqualTo(100)
        assertThat(holdings.rows[0].costPerShare).isEqualTo(1500.5)
        assertThat(holdings.rows[0].name).isEqualTo("贵州茅台")
        assertThat(holdings.rows[1].codeOrName).isEqualTo("000001")
    }

    @Test
    fun `holdings row without code falls back to name`() {
        val raw = """
            {"screenshotType":"HOLDINGS","rows":[{"name":"贵州茅台","code":"","shares":100,"costPerShare":null}]}
        """.trimIndent()

        val result = VisionImportParser.parse(raw) as VisionImportParseResult.Holdings

        assertThat(result.rows.single().codeOrName).isEqualTo("贵州茅台")
        assertThat(result.rows.single().costPerShare).isNull()
    }

    @Test
    fun `transactions json parses rows with normalized type and date`() {
        val raw = """
            {"screenshotType":"TRANSACTIONS","rows":[
              {"name":"贵州茅台","code":"600519","type":"证券买入","shares":100,"price":1500.50,"date":"20260801"},
              {"name":"平安银行","code":"","type":"SELL","shares":500,"price":"12.34","date":"2026/8/2"}
            ]}
        """.trimIndent()

        val result = VisionImportParser.parse(raw) as VisionImportParseResult.Transactions

        assertThat(result.rows).hasSize(2)
        assertThat(result.rows[0].type).isEqualTo("BUY")
        assertThat(result.rows[0].date).isEqualTo("2026-08-01")
        assertThat(result.rows[0].codeOrName).isEqualTo("600519")
        assertThat(result.rows[1].type).isEqualTo("SELL")
        assertThat(result.rows[1].date).isEqualTo("2026-08-02")
        assertThat(result.rows[1].price).isEqualTo(12.34)
        assertThat(result.rows[1].codeOrName).isEqualTo("平安银行")
    }

    @Test
    fun `unknown type yields null and row is kept`() {
        val raw = """
            {"screenshotType":"TRANSACTIONS","rows":[{"name":"银证转账","code":"","type":"银行转入","shares":null,"price":null,"date":null}]}
        """.trimIndent()

        val result = VisionImportParser.parse(raw) as VisionImportParseResult.Transactions

        assertThat(result.rows.single().type).isNull()
    }

    @Test
    fun `missing screenshotType infers transactions from row keys`() {
        val raw = """
            {"rows":[{"name":"贵州茅台","type":"BUY","shares":100,"price":15.2,"date":"2026-08-01"}]}
        """.trimIndent()

        val result = VisionImportParser.parse(raw)

        assertThat(result).isInstanceOf(VisionImportParseResult.Transactions::class.java)
    }

    @Test
    fun `missing screenshotType infers holdings when only cost keys present`() {
        val raw = """
            {"rows":[{"name":"贵州茅台","code":"600519","shares":100,"costPerShare":1500.0}]}
        """.trimIndent()

        val result = VisionImportParser.parse(raw)

        assertThat(result).isInstanceOf(VisionImportParseResult.Holdings::class.java)
    }

    @Test
    fun `date formats are normalized`() {
        assertThat(VisionImportParser.normalizeDate("2026-08-01")).isEqualTo("2026-08-01")
        assertThat(VisionImportParser.normalizeDate("2026年8月1日")).isEqualTo("2026-08-01")
        assertThat(VisionImportParser.normalizeDate("2026.8.1")).isEqualTo("2026-08-01")
        assertThat(VisionImportParser.normalizeDate("20260801")).isEqualTo("2026-08-01")
        assertThat(VisionImportParser.normalizeDate("8/2")).isEqualTo("2026-08-02")
        assertThat(VisionImportParser.normalizeDate("not a date")).isNull()
        assertThat(VisionImportParser.normalizeDate("2026-13-99")).isNull()
    }

    @Test
    fun `dates with time suffix are stripped before parsing`() {
        // 券商成交时间常带时分秒（§4.9.6 报告期归一化先例）：剥掉后缀再解析，不再整条归 null
        assertThat(VisionImportParser.normalizeDate("2026-08-01 09:30:15")).isEqualTo("2026-08-01")
        assertThat(VisionImportParser.normalizeDate("2026/8/1 09:30")).isEqualTo("2026-08-01")
        assertThat(VisionImportParser.normalizeDate("2026年8月1日 15:00:00")).isEqualTo("2026-08-01")
        assertThat(VisionImportParser.normalizeDate(" 2026-08-01 09:30:15 ")).isEqualTo("2026-08-01")
        // 剥掉后缀后仍非法的照旧归 null
        assertThat(VisionImportParser.normalizeDate("not a date 09:30:15")).isNull()
    }

    @Test
    fun `type normalization covers synonyms`() {
        assertThat(VisionImportParser.normalizeType("买入")).isEqualTo("BUY")
        assertThat(VisionImportParser.normalizeType("证券买入")).isEqualTo("BUY")
        assertThat(VisionImportParser.normalizeType("buy")).isEqualTo("BUY")
        assertThat(VisionImportParser.normalizeType("卖出")).isEqualTo("SELL")
        assertThat(VisionImportParser.normalizeType("证券卖出")).isEqualTo("SELL")
        assertThat(VisionImportParser.normalizeType("利息归本")).isNull()
        assertThat(VisionImportParser.normalizeType("")).isNull()
    }

    @Test
    fun `fenced json is extracted`() {
        val raw = """
            ```json
            {"screenshotType":"HOLDINGS","rows":[{"name":"贵州茅台","code":"600519","shares":100,"costPerShare":1500.0}]}
            ```
        """.trimIndent()

        val result = VisionImportParser.parse(raw)

        assertThat(result).isInstanceOf(VisionImportParseResult.Holdings::class.java)
    }

    @Test
    fun `numbers as strings with commas are parsed`() {
        val raw = """
            {"screenshotType":"HOLDINGS","rows":[{"name":"贵州茅台","code":600519,"shares":"1,000","costPerShare":"1,500.00"}]}
        """.trimIndent()

        val result = VisionImportParser.parse(raw) as VisionImportParseResult.Holdings

        // code 是数字字面量：不能读成 "600519.0"
        assertThat(result.rows.single().codeOrName).isEqualTo("600519")
        assertThat(result.rows.single().shares).isEqualTo(1000)
        assertThat(result.rows.single().costPerShare).isEqualTo(1500.0)
    }

    @Test
    fun `empty rows yields Empty`() {
        val result = VisionImportParser.parse("""{"screenshotType":"HOLDINGS","rows":[]}""")

        assertThat(result).isEqualTo(VisionImportParseResult.Empty)
    }

    @Test
    fun `garbage input yields Invalid without throwing`() {
        assertThat(VisionImportParser.parse("")).isEqualTo(VisionImportParseResult.Invalid)
        assertThat(VisionImportParser.parse("模型回复了一句话")).isEqualTo(VisionImportParseResult.Invalid)
        assertThat(VisionImportParser.parse("""{"foo":1}""")).isEqualTo(VisionImportParseResult.Invalid)
        assertThat(VisionImportParser.parse("[1,2,3]")).isEqualTo(VisionImportParseResult.Invalid)
    }

    @Test
    fun `rows with blank code and name are dropped`() {
        val raw = """
            {"screenshotType":"HOLDINGS","rows":[{"name":"","code":"","shares":100,"costPerShare":10.0},{"name":"贵州茅台","code":"600519","shares":100,"costPerShare":1500.0}]}
        """.trimIndent()

        val result = VisionImportParser.parse(raw) as VisionImportParseResult.Holdings

        assertThat(result.rows).hasSize(1)
    }
}
