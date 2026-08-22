package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 场内基金（ETF/LOF）识别与基金 f10 分红送配页解析（纯函数单测）。
 *
 * fixture 取自 fundf10.eastmoney.com/fhsp_510880.html 实测响应（2026-08-22，服务端渲染 HTML，
 * 已裁剪无关行）；「暂无分红」空表取自 fhsp_510300.html 实测。
 */
class FundDividendParserTest {

    // ── isExchangeTradedFund（App 内 sh./sz. 前缀格式）──

    @Test
    fun `identifies exchange traded funds by market prefix and code range`() {
        assertThat(FundDividendParser.isExchangeTradedFund("sh.510300")).isTrue()   // 沪 ETF
        assertThat(FundDividendParser.isExchangeTradedFund("sh.510880")).isTrue()   // 沪红利 ETF
        assertThat(FundDividendParser.isExchangeTradedFund("sh.588000")).isTrue()   // 科创 ETF
        assertThat(FundDividendParser.isExchangeTradedFund("sh.501018")).isTrue()   // 沪 LOF
        assertThat(FundDividendParser.isExchangeTradedFund("sz.159915")).isTrue()   // 深 ETF
        assertThat(FundDividendParser.isExchangeTradedFund("sz.161907")).isTrue()   // 深 LOF
    }

    @Test
    fun `rejects stocks and invalid codes`() {
        assertThat(FundDividendParser.isExchangeTradedFund("sh.600519")).isFalse()  // 沪主板
        assertThat(FundDividendParser.isExchangeTradedFund("sh.688981")).isFalse()  // 科创板
        assertThat(FundDividendParser.isExchangeTradedFund("sz.000001")).isFalse()  // 深主板
        assertThat(FundDividendParser.isExchangeTradedFund("sz.300750")).isFalse()  // 创业板
        assertThat(FundDividendParser.isExchangeTradedFund("sz.150244")).isTrue()   // 深 15 开头（分级遗留 LOF）
        assertThat(FundDividendParser.isExchangeTradedFund("sh.5103")).isFalse()    // 非 6 位
        assertThat(FundDividendParser.isExchangeTradedFund("510300")).isFalse()     // 无市场前缀
        assertThat(FundDividendParser.isExchangeTradedFund("")).isFalse()
    }

    // ── isExchangeTradedFundCode（裸 6 位代码，供行情除数选择）──

    @Test
    fun `bare code detection covers both markets without stock collisions`() {
        assertThat(FundDividendParser.isExchangeTradedFundCode("510880")).isTrue()
        assertThat(FundDividendParser.isExchangeTradedFundCode("159915")).isTrue()
        assertThat(FundDividendParser.isExchangeTradedFundCode("161907")).isTrue()
        // 股票：沪 6 开头 / 深 0、3 开头，与基金号段（5、15、16）无冲突
        assertThat(FundDividendParser.isExchangeTradedFundCode("600519")).isFalse()
        assertThat(FundDividendParser.isExchangeTradedFundCode("000001")).isFalse()
        assertThat(FundDividendParser.isExchangeTradedFundCode("300750")).isFalse()
        assertThat(FundDividendParser.isExchangeTradedFundCode("688981")).isFalse()
        assertThat(FundDividendParser.isExchangeTradedFundCode("51088")).isFalse()  // 5 位
    }

    // ── parseDividendHtml ──

    /** 真实 fhsp_510880.html 的 cfxq 表节选（含同年多笔与旧年份，年份列跨行共享语义不变）。 */
    private val realHtml = """
        <!DOCTYPE html><html><head><meta charset="utf-8"></head><body>
        <div class="fundmain">
        <table class='w782 comm cfxq'><thead><tr><th class='first'>年份</th><th>权益登记日</th><th>除息日</th><th>每10份分红</th><th class='last'>分红发放日</th></tr></thead><tbody><tr><td>2026年</td><td>2026-01-20</td><td>2026-01-21</td><td>每10份派现金1.4300元</td><td>2026-01-26</td></tr> <tr><td>2025年</td><td>2025-01-20</td><td>2025-01-21</td><td>每10份派现金1.4200元</td><td>2025-01-24</td></tr> <tr><td>2010年</td><td>2010-07-14</td><td>2010-07-15</td><td>每10份派现金0.2000元</td><td>2010-07-21</td></tr>
        </tbody></table>
        <table class='w782 comm elsewhere'><tr><td>陷阱表</td></tr></table>
        </div></body></html>
    """.trimIndent()

    @Test
    fun `parses real f10 html table rows with unit conversion`() {
        val entities = FundDividendParser.parseDividendHtml(realHtml, "sh.510880")

        assertThat(entities).hasSize(3)
        val first = entities.first()
        assertThat(first.id).isEqualTo("sh.510880_2026-01-21")
        assertThat(first.stockCode).isEqualTo("sh.510880")
        assertThat(first.cashPerShare).isWithin(1e-9).of(0.143)      // 每10份 → 每份
        assertThat(first.exDividendDate).isEqualTo("2026-01-21")
        assertThat(first.recordDate).isEqualTo("2026-01-20")
        assertThat(first.reportDate).isEqualTo("2026-12-31")
        assertThat(first.dividendYield).isNull()
        assertThat(first.planStatus).isNull()
        // 旧年份同样解析（历史保留式入库）
        assertThat(entities.last().cashPerShare).isWithin(1e-9).of(0.02)
        assertThat(entities.last().reportDate).isEqualTo("2010-12-31")
    }

    @Test
    fun `no dividend page returns empty`() {
        // 未分红基金（510300 实测）：页面只有「暂无分红」文案，无 cfxq 数据行
        val html = "<html><body><div class='nodata'>暂无分红</div></body></html>"
        assertThat(FundDividendParser.parseDividendHtml(html, "sh.510300")).isEmpty()
    }

    @Test
    fun `skips non cash distribution rows`() {
        val html = """
            <table class='w782 comm cfxq'><tbody>
            <tr><td>2026年</td><td>2026-01-20</td><td>2026-01-21</td><td>每10份送份额2.0份</td><td>2026-01-26</td></tr>
            <tr><td>2026年</td><td>2026-06-01</td><td>2026-06-02</td><td>每10份派现金0.5000元</td><td>2026-06-08</td></tr>
            </tbody></table>
        """.trimIndent()
        val entities = FundDividendParser.parseDividendHtml(html, "sz.159915")

        assertThat(entities).hasSize(1)   // 送份额跳过，只留现金分红
        assertThat(entities.single().cashPerShare).isWithin(1e-9).of(0.05)
    }

    @Test
    fun `skips rows with missing ex date or malformed year`() {
        val html = """
            <table class='w782 comm cfxq'><tbody>
            <tr><td>2026年</td><td></td><td></td><td>每10份派现金1.0000元</td><td></td></tr>
            <tr><td>未知年份</td><td>2026-01-20</td><td>2026-01-21</td><td>每10份派现金1.0000元</td><td>2026-01-26</td></tr>
            <tr><td>2026年</td><td>2026-01-20</td><td>2026-01-21</td><td>每10份派现金0.0000元</td><td>2026-01-26</td></tr>
            </tbody></table>
        """.trimIndent()
        assertThat(FundDividendParser.parseDividendHtml(html, "sh.510880")).isEmpty()
    }

    @Test
    fun `garbage or non html input returns empty without throwing`() {
        assertThat(FundDividendParser.parseDividendHtml("", "sh.510300")).isEmpty()
        assertThat(FundDividendParser.parseDividendHtml("服务暂时不可用", "sh.510300")).isEmpty()
        assertThat(FundDividendParser.parseDividendHtml("<html><table class='cfxq'>", "sh.510300")).isEmpty()
    }

    @Test
    fun `dedups rows by ex date`() {
        val html = """
            <table class='w782 comm cfxq'><tbody>
            <tr><td>2026年</td><td>2026-01-20</td><td>2026-01-21</td><td>每10份派现金1.4300元</td><td>2026-01-26</td></tr>
            <tr><td>2026年</td><td>2026-01-20</td><td>2026-01-21</td><td>每10份派现金1.4300元</td><td>2026-01-26</td></tr>
            </tbody></table>
        """.trimIndent()
        assertThat(FundDividendParser.parseDividendHtml(html, "sh.510880")).hasSize(1)
    }
}
