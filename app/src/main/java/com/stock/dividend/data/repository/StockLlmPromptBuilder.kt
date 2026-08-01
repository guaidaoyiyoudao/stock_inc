package com.stock.dividend.data.repository

/**
 * 把单只股票的股息能力 + 三周期 BOLL 价格位置序列化为 LLM prompt（纯函数）。
 * system 定角色 + JSON schema + 约束；user 放结构化数据。不喂成本价等敏感信息。
 */
object StockLlmPromptBuilder {

    data class LlmPrompt(val system: String, val user: String)

    /**
     * @param userStrategies 用户全局投资原则（来自截图分析，回流进 prompt）。默认空——
     * 策略是全局背景而非个股属性，故作 builder 独立参数，不入 [StockLlmInput] 字段。
     * sourceNote 不入 prompt（仅 DB 存 + 列表展示）。
     */
    fun build(
        input: StockLlmInput,
        userStrategies: List<UserStrategyRef> = emptyList()
    ): LlmPrompt = LlmPrompt(SYSTEM, buildUser(input, userStrategies))

    private val SYSTEM = """
你是一位稳健、客观的中文分红股投资分析助手。

【任务】
基于用户提供的一只股票的股息数据与价格位置，输出该股的自然语言解读。

【数据语义（仅供理解，不要复述规则公式）】
- 距下轨%：0=价格在 BOLL 下轨（便宜），100=在上轨（贵）；给出 日/周/月 三周期，据此判断多周期共振
- 分红率%：当年现金分红 / 当年股价（逐年序列反映分红力度趋势）
- 股息率%：最新一期年现金分红 / 现价
- PE(TTM)：市盈率，股价 / 每股收益；越低代表估值越便宜，但需结合成长性，过低可能是盈利下滑信号
- PB：市净率，股价 / 每股净资产；红利股常 <1，反映破净程度
- 总市值：公司规模（元），大盘股流动性好但弹性小，小盘股反之
- 预测：基于历史分红的线性平均，非承诺；实际样本年数越少越不可靠
- 买入线：股息率达到「国债收益率×倍数」时视为低估信号
- ROE%：净资产收益率，反映赚钱效率，持续下滑是分红可持续性的危险信号
- 资产负债率%：越高杠杆越大，>70% 需警惕（行业差异大，结合行业判断）
- 营收/净利同比%：正负与趋势反映成长性，持续负增长会侵蚀分红能力
- 派息率%：分红/盈利，>80% 或持续上升而盈利不增，分红可能不可持续
- 分红方案：如「10派3.60元(含税)」表示每10股派3.6元，反映当期实际分红力度
- 用户投资原则：用户此前从外部内容整理出的整体投资观点，对所有标的通用，属用户个人视角，非客观数据；解读时可对照呼应，但不要盲从或简单复述。

【输出要求】严格输出 JSON：
{"valuation":"估值判断≤120字：结合三周期位置判断当前贵/便宜/合理","dividendSustainability":"分红可持续性≤120字：结合 ROE/派息率/成长性趋势","action":"一句话结论≤20字：如可逢低关注/暂观望/持有等定性","risks":["具体风险点"]}

【约束】
1. 仅基于提供数据，绝不编造价格/股息率/财报/未给出的信息。
2. 中文，专业易懂，避免绝对化断言（"一定""必定"）。
3. 不给明确买卖时点或具体价格目标；这是解读，不是指令。
4. 缺失数据用"—"表示的部分，解读中不要臆测。
5. 风险要点具体，不泛泛而谈；不复述规则逻辑。
    """.trim()

    private fun buildUser(input: StockLlmInput, userStrategies: List<UserStrategyRef>): String {
        val sb = StringBuilder()
        sb.append("【标的】${input.code} ${input.name}")
        input.industry?.takeIf { it.isNotBlank() }?.let { sb.append(" [$it]") }
        sb.append("\n")
        sb.append("【现价】${input.currentPrice?.let { "¥${"%.2f".format(it)}" } ?: "—"}\n")

        // 估值（PE/PB/总市值；三者全缺则不输出此段，避免噪音）
        val hasValuation = input.pe != null || input.pb != null || input.totalMarketCap != null
        if (hasValuation) {
            val peStr = input.pe?.let { "%.2f".format(it) } ?: "—"
            val pbStr = input.pb?.let { "%.2f".format(it) } ?: "—"
            val capStr = input.totalMarketCap?.let { "%.0f亿".format(it / 1_0000_0000) } ?: "—"
            sb.append("【估值】PE $peStr / PB $pbStr / 总市值 $capStr\n")
        }

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

        // 基本面（近 N 期，旧→新）
        sb.append("【基本面（近${input.fundamentals?.periods?.size ?: 0}期）】")
        val periods = input.fundamentals?.periods
        if (periods.isNullOrEmpty()) {
            sb.append("—\n")
        } else {
            sb.append("\n")
            periods.forEach { p ->
                sb.append("  ${p.reportDate}: ")
                sb.append("ROE ${p.roe?.let { "${"%.1f".format(it)}%" } ?: "—"} / ")
                sb.append("负债率 ${p.debtToAssetRatio?.let { "${"%.0f".format(it)}%" } ?: "—"} / ")
                sb.append("营收${formatYoy(p.revenueYoy)} / ")
                sb.append("净利${formatYoy(p.netProfitYoy)} / ")
                sb.append("派息率 ${p.payoutRatio?.let { "${"%.0f".format(it)}%" } ?: "—"}")
                p.dividendPlan?.takeIf { it.isNotBlank() }?.let { sb.append(" / $it") }
                sb.append("\n")
            }
        }

        // 用户投资原则（全局回流，不含 sourceNote）
        sb.append("【用户投资原则（来自截图分析，全局，仅供参照）】")
        if (userStrategies.isEmpty()) {
            sb.append("—\n")
        } else {
            sb.append("\n")
            userStrategies.forEach { ref ->
                val dirZh = when (ref.direction) {
                    "BUY" -> "买入"; "SELL" -> "卖出"; else -> "观望"
                }
                sb.append("  [$dirZh] ${ref.reasoning} (${ref.daysAgo}天前)\n")
                if (ref.risks.isNotEmpty()) {
                    sb.append("    风险: ${ref.risks.joinToString(" / ")}\n")
                }
            }
        }

        return sb.toString()
    }

    /** 同比%带正负号渲染：+8.0% / -2.0% / —。 */
    private fun formatYoy(value: Double?): String = when {
        value == null || !value.isFinite() -> "—"
        else -> "${if (value >= 0) "+" else ""}${"%.1f".format(value)}%"
    }
}
