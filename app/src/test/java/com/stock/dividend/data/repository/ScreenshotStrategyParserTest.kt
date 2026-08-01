package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ScreenshotStrategyParserTest {

    @Test
    fun fullJson_actionable() {
        val raw = """{"isActionable":true,"targetText":"招商银行","direction":"BUY","reasoning":"ROE高","risks":["息差"],"validUntil":"2026-09-01"}"""
        val r = ScreenshotStrategyParser.parse(raw)
        assertThat(r).isInstanceOf(ScreenshotStrategyParseResult.Actionable::class.java)
        val s = (r as ScreenshotStrategyParseResult.Actionable).strategy
        assertThat(s.targetText).isEqualTo("招商银行")
        assertThat(s.direction).isEqualTo(ScreenshotStrategy.StrategyDirection.BUY)
        assertThat(s.reasoning).isEqualTo("ROE高")
        assertThat(s.risks).containsExactly("息差")
        assertThat(s.validUntil).isEqualTo("2026-09-01")
    }

    @Test
    fun isActionableFalse_notActionable() {
        val r = ScreenshotStrategyParser.parse("""{"isActionable":false}""")
        assertThat(r).isEqualTo(ScreenshotStrategyParseResult.NotActionable)
    }

    @Test
    fun invalidDirection_fallsBackToWatch() {
        val r = ScreenshotStrategyParser.parse("""{"isActionable":true,"direction":"XXX"}""")
        assertThat((r as ScreenshotStrategyParseResult.Actionable).strategy.direction)
            .isEqualTo(ScreenshotStrategy.StrategyDirection.WATCH)
    }

    @Test
    fun missingRisks_emptyList() {
        val r = ScreenshotStrategyParser.parse("""{"isActionable":true}""")
        assertThat((r as ScreenshotStrategyParseResult.Actionable).strategy.risks).isEmpty()
    }

    @Test
    fun fencedJson_extracted() {
        val raw = "前缀\n```json\n{\"isActionable\":true,\"targetText\":\"x\"}\n```\n后缀"
        val r = ScreenshotStrategyParser.parse(raw)
        assertThat(r).isInstanceOf(ScreenshotStrategyParseResult.Actionable::class.java)
    }

    @Test
    fun pureText_failed() {
        val r = ScreenshotStrategyParser.parse("这不是json")
        assertThat(r).isInstanceOf(ScreenshotStrategyParseResult.Failed::class.java)
    }

    @Test
    fun empty_failed() {
        assertThat(ScreenshotStrategyParser.parse(""))
            .isInstanceOf(ScreenshotStrategyParseResult.Failed::class.java)
    }

    @Test
    fun malformed_doesNotThrow() {
        // 不抛异常即通过
        ScreenshotStrategyParser.parse("{broken")
        ScreenshotStrategyParser.parse("""{"reasoning":}""")
    }
}
