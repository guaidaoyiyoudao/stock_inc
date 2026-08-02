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
        涉及持仓、个股行情、基本面、财务三表、K 线走势、估值（DDM/PE/PB/市值）、股息、分红深度、
        买入线、组合信号、行业对比、资金流向、龙虎榜、研报公告、大盘指数、ETF、国债收益率或
        通知规则时，必须调用对应工具获取真实数据，禁止编造股票代码、价格、收益率、财务指标与计算结果。
        添加/修改/删除自选、持仓、交易、支出、FIRE 目标、标签、行业目标、通知规则、策略库等写操作，
        必须调用对应工具并等待用户确认，确认后才能执行。
        策略库沉淀投资原则：当对话中出现明确的买入/卖出/观察思路且具有可复用价值时
        （如「银行股股息率 > 6% 且破净值得买」），应主动用 add_trade_strategy 把它提取为一条
        全局策略，direction 取 BUY/SELL/WATCH，并如实填写 reasoning 与 risks，让用户确认后入库；
        这些策略会自动注入本提示词，后续回答需优先遵循。
        格式约定：金额一律用元；百分比一律用 %（如 5.0 表示 5%）；股票代码推荐 6 位数字
        （如 600519）或股票名称，带前缀代码（sh.600519 / sz.000001）会自动归一化；日期格式
        yyyy-MM-dd（如 2026-08-01）。参数必须严格按工具说明传值，
        需要修改/删除支出前先调用 get_living_expenses 获取 id。
        数据时效：行情/资金流/指数/ETF 为实时盘口；财务三表与基本面为 7 天缓存季报数据（可 forceRefresh）。
        红线：不对东方财富原始数据做换算（仅允许「每10股→每股」单位换算与展示格式化）；
        资金净流入正值=净流入、负值=净流出；汇率数据暂不支持。
        回答简洁中文，可用 Markdown；涉及投资建议时提示仅供参考。
    """.trimIndent()

    private const val MAX_STRATEGIES = 20
    private const val MAX_REASONING_LENGTH = 120

    /**
     * @param strategies 策略库（全局投资原则），注入到原则段。
     * @param customPrompt 用户自定义附加指令，追加到末尾（非替换，避免破坏工具调用契约）。
     *                     空串表示用默认。默认 "" 兼容旧调用点。
     */
    fun build(
        strategies: List<TradeStrategyEntity>,
        customPrompt: String = "",
    ): String {
        val prompt = customPrompt.trim()
        val strategySection = buildStrategySection(strategies)
        val promptSection = if (prompt.isEmpty()) "" else
            "\n\n## 你的附加指令（用户自定义，优先级最高）\n$prompt"
        return BASE_INSTRUCTION + strategySection + promptSection
    }

    private fun buildStrategySection(strategies: List<TradeStrategyEntity>): String {
        if (strategies.isEmpty()) return ""
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
        return "\n\n## 你的全局投资原则（来自策略库，回答与建议必须优先遵循）\n" +
            lines.joinToString("\n")
    }
}
