package com.stock.dividend.data.repository

/**
 * 把 OCR 文本构造成 LLM prompt（纯函数）：system 定角色 + JSON schema + 约束；
 * user 直接粘贴 OCR 全文（不截断，研报信息密度高）。空文本仍产出合法 prompt。
 */
object ScreenshotStrategyPromptBuilder {

    data class LlmPrompt(val system: String, val user: String)

    fun build(ocrText: String): LlmPrompt = LlmPrompt(SYSTEM, buildUser(ocrText))

    private val SYSTEM = """
你是一位稳健的中文投资策略整理助手。
【任务】用户给出一截从财经内容（研报/新闻/股吧/聊天等）OCR 出的文本，请提取其中**可执行的买卖策略**。
【输出要求】严格输出 JSON：
{
  "isActionable": true/false,
  "targetText": "涉及的股票名称或代码（原文片段，不确定可合并写）",
  "direction": "BUY" | "SELL" | "WATCH",
  "reasoning": "核心理由≤200字，仅基于原文",
  "risks": ["具体风险点", "..."],
  "validUntil": "YYYY-MM-DD 或 null（无明确期限填 null）"
}
【判定规则】
- 若截图与股票/投资无关、或仅陈述事实无任何买卖倾向 → isActionable=false，其余字段填空/null。
- direction：买入倾向→BUY，卖出/看空→SELL，观望/持有/无明确方向→WATCH。
- reasoning 与 risks 仅据原文归纳，绝不编造数据、价格、财报。
- validUntil：原文有明确到期/止盈时间填日期，否则 null。
【约束】中文；不给具体买卖价格；不复述 OCR 错乱字符。
    """.trim()

    private fun buildUser(ocrText: String): String =
        "【截图文本】\n${if (ocrText.isBlank()) "（空）" else ocrText}"
}
