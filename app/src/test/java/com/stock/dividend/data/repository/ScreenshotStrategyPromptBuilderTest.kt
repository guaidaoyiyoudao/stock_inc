package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ScreenshotStrategyPromptBuilderTest {

    @Test
    fun system_containsSchemaAndConstraints() {
        val p = ScreenshotStrategyPromptBuilder.build("xx")
        assertThat(p.system).contains("isActionable")
        assertThat(p.system).contains("BUY")
        assertThat(p.system).contains("SELL")
        assertThat(p.system).contains("WATCH")
        assertThat(p.system).contains("绝不编造数据、价格、财报")
        assertThat(p.system).contains("不给具体买卖价格")
    }

    @Test
    fun user_containsFullOcrText_untruncated() {
        val ocr = "招商银行基本面稳健\nROE持续>15%\n建议买入".repeat(50)
        val p = ScreenshotStrategyPromptBuilder.build(ocr)
        assertThat(p.user).contains(ocr)
    }

    @Test
    fun user_emptyOcr_stillLegal() {
        val p = ScreenshotStrategyPromptBuilder.build("")
        assertThat(p.user).contains("截图文本")
        assertThat(p.user).isNotEmpty()
    }
}
