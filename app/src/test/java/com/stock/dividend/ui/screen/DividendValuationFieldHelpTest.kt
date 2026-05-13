package com.stock.dividend.ui.screen

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DividendValuationFieldHelpTest {
    @Test
    fun `field help contains explanations for every editable assumption`() {
        val helpByTitle = dividendValuationFieldHelp.associateBy { it.title }

        assertThat(helpByTitle.keys).containsExactly(
            "股息基准",
            "未来股息增长率",
            "折现率",
            "终值增长率",
            "预测年限",
            "安全边际"
        )
        assertThat(helpByTitle["股息基准"]!!.description)
            .isEqualTo("估值的起点股息，默认取最近 5 个可用分红年份的每股现金分红平均值；没有历史分红时可手动输入。")
        assertThat(helpByTitle["折现率"]!!.description)
            .isEqualTo("把未来现金流折算成今天价值时使用的回报率要求，越高则估值越低。")
        assertThat(helpByTitle["终值增长率"]!!.description)
            .isEqualTo("预测期结束后，假设股息长期稳定增长的比例；必须低于折现率。")
    }
}
