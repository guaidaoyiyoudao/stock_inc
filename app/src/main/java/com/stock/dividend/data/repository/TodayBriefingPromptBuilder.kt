package com.stock.dividend.data.repository

/**
 * 今日简报 LLM prompt 构造（纯函数，无 Android 依赖）。
 *
 * 约束 LLM：一句话 ≤ 50 字、只解读不臆造数字、不加引号前后缀。
 */
object TodayBriefingPromptBuilder {

    fun build(
        portfolioLine: String,
        signals: List<TodaySignal>,
        dividendLine: String?,
    ): String {
        val signalBlock = if (signals.isEmpty()) {
            "今日无显著信号，组合平静。"
        } else {
            signals.take(3).joinToString("；") { "${it.stockName}${it.title}（${it.detail}）" }
        }
        return """
            你是股息投资助手。基于以下今日数据，用一句话（不超过 50 个汉字）总结今天最值得持有者关注的一点。
            规则：只解读，不要编造数据里没有的数字；直接输出那句话，不要加引号或前后缀。

            【组合表现】$portfolioLine
            【信号】$signalBlock
            【分红】${dividendLine ?: "无近期除权"}
        """.trimIndent()
    }
}
