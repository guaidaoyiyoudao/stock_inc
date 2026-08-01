package com.stock.dividend.data.agent

import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.local.entity.STRATEGY_DIRECTION_BUY
import com.stock.dividend.data.local.entity.TradeStrategyEntity
import org.junit.Test

class AgentInstructionBuilderTest {

    @Test
    fun `无策略时只返回基础指令`() {
        val instruction = AgentInstructionBuilder.build(emptyList())
        assertThat(instruction).contains("AI 投资助手")
        assertThat(instruction).doesNotContain("全局投资原则")
    }

    @Test
    fun `有策略时注入方向目标与理由`() {
        val strategy = TradeStrategyEntity(
            id = "s1",
            targetText = "银行股",
            direction = STRATEGY_DIRECTION_BUY,
            reasoning = "股息率高且破净",
            risks = """["估值修复不及预期"]""",
            validUntil = null,
            sourceNote = "截图",
            rawOcrText = ""
        )
        val instruction = AgentInstructionBuilder.build(listOf(strategy))
        assertThat(instruction).contains("全局投资原则")
        assertThat(instruction).contains("[买入] 银行股")
        assertThat(instruction).contains("股息率高且破净")
        assertThat(instruction).contains("估值修复不及预期")
    }

    @Test
    fun `过长的理由被截断`() {
        val strategy = TradeStrategyEntity(
            id = "s2",
            targetText = "测试",
            direction = STRATEGY_DIRECTION_BUY,
            reasoning = "长".repeat(300),
            risks = "[]",
            validUntil = null,
            sourceNote = null,
            rawOcrText = ""
        )
        val instruction = AgentInstructionBuilder.build(listOf(strategy))
        assertThat(instruction).contains("长".repeat(120))
        assertThat(instruction).doesNotContain("长".repeat(121))
    }
}
