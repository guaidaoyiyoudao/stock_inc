package com.stock.dividend.data.repository

/**
 * 把单只股票的股息能力 + 三周期 BOLL 价格位置序列化为 LLM prompt（纯函数）。
 * system 定角色 + JSON schema + 约束；user 放结构化数据。不喂成本价等敏感信息。
 */
object StockLlmPromptBuilder {

    data class LlmPrompt(val system: String, val user: String)

    fun build(input: StockLlmInput): LlmPrompt = LlmPrompt(SYSTEM, buildUser(input))

    private val SYSTEM = """
你是一位稳健、客观的中文分红股投资分析助手。

【任务】
基于用户提供的一只股票的股息数据与价格位置，输出该股的自然语言解读。

【数据语义（仅供理解，不要复述规则公式）】
- 距下轨%：0=价格在 BOLL 下轨（便宜），100=在上轨（贵）；给出 日/周/月 三周期，据此判断多周期共振
- 分红率%：当年现金分红 / 当年股价（逐年序列反映分红力度趋势）
- 股息率%：最新一期年现金分红 / 现价
- 预测：基于历史分红的线性平均，非承诺；实际样本年数越少越不可靠
- 买入线：股息率达到「国债收益率×倍数」时视为低估信号

【输出要求】严格输出 JSON：
{"valuation":"估值判断≤120字：结合三周期位置判断当前贵/便宜/合理","dividendSustainability":"分红可持续性≤120字：结合分红率趋势与预测样本","action":"一句话结论≤20字：如可逢低关注/暂观望/持有等定性","risks":["具体风险点"]}

【约束】
1. 仅基于提供数据，绝不编造价格/股息率/财报/未给出的信息。
2. 中文，专业易懂，避免绝对化断言（"一定""必定"）。
3. 不给明确买卖时点或具体价格目标；这是解读，不是指令。
4. 缺失数据用"—"表示的部分，解读中不要臆测。
5. 风险要点具体，不泛泛而谈；不复述规则逻辑。
    """.trim()

    private fun buildUser(input: StockLlmInput): String {
        val sb = StringBuilder()
        sb.append("【标的】${input.code} ${input.name}")
        input.industry?.takeIf { it.isNotBlank() }?.let { sb.append(" [$it]") }
        sb.append("\n")
        sb.append("【现价】${input.currentPrice?.let { "¥${"%.2f".format(it)}" } ?: "—"}\n")

        // 分红率趋势
        val points = input.dividendRatePoints
        sb.append("【分红率趋势】")
        if (points.isNullOrEmpty()) {
            sb.append("—\n")
        } else {
            sb.append(points.joinToString(" | ") { "${"%.1f".format(it)}%" })
            val trend = when {
                points.size < 2 -> ""
                points.last() > points.first() + 0.3 -> "（整体上升）"
                points.first() > points.last() + 0.3 -> "（整体下降）"
                else -> "（整体平稳）"
            }
            sb.append("（近${points.size}年$trend）\n")
        }

        sb.append("【最新股息率】${input.latestDividendYield?.let { "${"%.1f".format(it)}%" } ?: "—"}\n")

        // 预测
        val f = input.forecast
        sb.append("【预测】")
        if (f == null) {
            sb.append("—\n")
        } else {
            sb.append("1年均每股 ¥${"%.2f".format(f.avgCashPerShare1Y)} / ")
            sb.append("3年均每股 ¥${"%.2f".format(f.avgCashPerShare3Y)} / ")
            sb.append("5年均每股 ¥${"%.2f".format(f.avgCashPerShare5Y)}")
            sb.append("（实际样本 ${f.actualYears} 年）\n")
        }

        // 买入线
        val bt = input.buyThreshold
        sb.append("【买入线】")
        if (bt == null) {
            sb.append("—\n")
        } else {
            sb.append("目标股息率 ${"%.1f".format(bt.targetYieldPercent)}%")
            sb.append("，当前 ${bt.currentYieldPercent?.let { "${"%.1f".format(it)}%" } ?: "—"}")
            val reachedZh = when (bt.reached) {
                true -> "已达标"
                false -> "未达标"
                null -> "无法判定"
            }
            sb.append("，$reachedZh\n")
        }

        // BOLL 三周期位置
        sb.append("【BOLL 位置】")
        sb.append("日距下轨 ${input.bollDaily?.let { "${it.priceVsLowerPercent}%" } ?: "—"} / ")
        sb.append("周距下轨 ${input.bollWeekly?.let { "${it.priceVsLowerPercent}%" } ?: "—"} / ")
        sb.append("月距下轨 ${input.bollMonthly?.let { "${it.priceVsLowerPercent}%" } ?: "—"}\n")

        return sb.toString()
    }
}
