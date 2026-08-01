package com.stock.dividend.data.agent

import com.stock.dividend.data.local.entity.STRATEGY_DIRECTION_BUY
import com.stock.dividend.data.local.entity.STRATEGY_DIRECTION_SELL
import com.stock.dividend.data.local.entity.TradeStrategyEntity
import com.stock.dividend.data.repository.risksFromJson

/**
 * Agent 系统提示词构造：把策略库（全局投资原则）直接注入提示词，
 * 让模型每轮都参考用户自己的规则，而不是等它想起调工具。纯函数，便于单测。
 */
object AgentInstructionBuilder {

    val BASE_INSTRUCTION: String = """
        你是「股息追踪」App 的 AI 投资助手。
        涉及持仓、个股行情、估值、股息、买入线、行业配比或通知规则时，必须调用对应工具获取
        真实数据，禁止编造股票代码、价格、收益率与计算结果。
        添加/修改/删除自选、持仓、交易、支出、FIRE 目标、标签、行业目标、通知规则等写操作，
        必须调用对应工具并等待用户确认，确认后才能执行。
        格式约定：金额一律用元；百分比一律用 %（如 5.0 表示 5%）；股票代码可用 6 位数字、
        sh./sz. 前缀或股票名称；日期格式 yyyy-MM-dd（如 2026-08-01）。参数必须严格按工具说明传值，
        需要修改/删除支出前先调用 get_living_expenses 获取 id。
        回答简洁中文，可用 Markdown；涉及投资建议时提示仅供参考。
    """.trimIndent()

    private const val MAX_STRATEGIES = 20
    private const val MAX_REASONING_LENGTH = 120

    fun build(strategies: List<TradeStrategyEntity>): String {
        if (strategies.isEmpty()) return BASE_INSTRUCTION
        val lines = strategies.take(MAX_STRATEGIES).map { strategy ->
            val direction = when (strategy.direction) {
                STRATEGY_DIRECTION_BUY -> "买入"
                STRATEGY_DIRECTION_SELL -> "卖出"
                else -> "观察"
            }
            val reasoning = strategy.reasoning.trim().take(MAX_REASONING_LENGTH)
            val risks = risksFromJson(strategy.risks).take(3)
            val riskText = if (risks.isEmpty()) "" else "；风险：${risks.joinToString("、")}"
            "- [$direction] ${strategy.targetText}：$reasoning$riskText"
        }
        return BASE_INSTRUCTION + "\n\n## 你的全局投资原则（来自策略库，回答与建议必须优先遵循）\n" +
            lines.joinToString("\n")
    }
}
