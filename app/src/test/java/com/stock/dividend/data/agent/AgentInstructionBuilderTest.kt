package com.stock.dividend.data.agent

import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.local.entity.STRATEGY_DIRECTION_BUY
import com.stock.dividend.data.local.entity.TradeStrategyEntity
import org.junit.Test

class AgentInstructionBuilderTest {

    @Test
    fun withoutStrategies_returnsBaseInstructionOnly() {
        val instruction = AgentInstructionBuilder.build(emptyList())
        assertThat(instruction).contains("AI 投资助手")
        assertThat(instruction).doesNotContain("全局投资原则")
    }

    @Test
    fun withStrategies_injectsDirectionGoalAndReason() {
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
    fun overlongReason_isTruncated() {
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

    @Test
    fun blankCustomPrompt_omitsPromptSection_andKeepsBaseContract() {
        // 空串/纯空白：不追加「附加指令」段，且保留工具调用契约
        val instruction = AgentInstructionBuilder.build(emptyList(), customPrompt = "   ")
        assertThat(instruction).doesNotContain("附加指令")
        assertThat(instruction).contains("必须调用对应工具")
    }

    @Test
    fun baseInstruction_guidesProactiveStrategyExtraction() {
        // 默认提示词必须引导模型主动用 add_trade_strategy 提取策略（覆盖策略库写操作的提示缺口）
        val instruction = AgentInstructionBuilder.build(emptyList())
        assertThat(instruction).contains("add_trade_strategy")
        assertThat(instruction).contains("BUY/SELL/WATCH")
        // 写操作清单应包含「策略库」，否则模型可能不知道这是受控写操作
        assertThat(instruction).contains("策略库")
    }

    @Test
    fun customPrompt_appendedAfterStrategies_andKeepsBaseContract() {
        val strategy = TradeStrategyEntity(
            id = "s3",
            targetText = "煤炭股",
            direction = STRATEGY_DIRECTION_BUY,
            reasoning = "高分红",
            risks = "[]",
            validUntil = null,
            sourceNote = null,
            rawOcrText = ""
        )
        val instruction = AgentInstructionBuilder.build(
            listOf(strategy),
            customPrompt = "回答加 emoji"
        )
        // 保留默认契约（不可因自定义而破坏工具调用/数据准确性约束）
        assertThat(instruction).contains("必须调用对应工具")
        // 策略段保留
        assertThat(instruction).contains("全局投资原则")
        assertThat(instruction).contains("[买入] 煤炭股")
        // 自定义指令追加在末尾
        assertThat(instruction).contains("附加指令")
        assertThat(instruction).contains("回答加 emoji")
        // 顺序：默认契约 → 策略 → 自定义
        val baseIdx = instruction.indexOf("必须调用对应工具")
        val strategyIdx = instruction.indexOf("全局投资原则")
        val promptIdx = instruction.indexOf("附加指令")
        assertThat(baseIdx).isLessThan(strategyIdx)
        assertThat(strategyIdx).isLessThan(promptIdx)
    }
}
