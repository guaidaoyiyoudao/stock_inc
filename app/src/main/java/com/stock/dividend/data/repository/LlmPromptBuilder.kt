package com.stock.dividend.data.repository

/**
 * 把规则评估结果 + 策略信号序列化为 LLM prompt（纯函数）。
 * system 定角色 + JSON schema + 约束；user 放结构化数据。不喂成本价等敏感信息。
 */
object LlmPromptBuilder {

    data class LlmPrompt(val system: String, val user: String)

    fun build(
        evaluatedStocks: List<EvaluatedStock>,
        dailyBands: Map<String, BollBand?>,
        monthlyBands: Map<String, BollBand?>,
        signals: PortfolioSignals,
        thresholds: DividendThresholds,
        userStrategies: List<UserStrategyRef> = emptyList(),
    ): LlmPrompt = LlmPrompt(SYSTEM, buildUser(evaluatedStocks, dailyBands, monthlyBands, signals, thresholds, userStrategies))

    private val SYSTEM = """
你是一位稳健、客观的中文分红股投资分析助手。

【任务】
基于用户提供的持仓评估数据与策略信号（已由规则引擎判定），输出自然语言解读。

【数据语义（仅供理解，不要复述规则公式）】
- action=买：日下轨+周下轨+月中轨及以下 三周期共振，且股息率达最低门槛
- action=卖：价格处于周线 BOLL 上轨附近（偏高）
- action=持有：未达三周期共振（中轨、仅单一周期偏低、或股息率不足）
- 距下轨%：0=在下轨（便宜），100=在上轨（贵）；每只股给出 日/周/月 三周期的距下轨%，据此判断多周期共振
- 股息率%：年现金分红 / 现价
- 仓位控制信号：多数股票抵达上轨 + 整体股息偏低 → 建议控仓、现金 ≥ 目标%
- 三周期共振买点：与 action=买 同源（日下轨 + 周下轨 + 月中轨及以下 同时成立）
- 用户投资原则：用户此前从外部内容整理出的整体投资观点，对所有标的通用，属用户个人视角，非客观数据；解读时可对照呼应，但不要盲从或简单复述。

【输出要求】严格输出 JSON：
{"overview":"组合整体解读≤150字","stockComments":{"<code>":"该股≤40字"},"risks":["具体风险点"]}

【约束】
1. 仅基于提供数据，绝不编造价格/股息率/财报/未给出的信息。
2. 中文，专业易懂，避免绝对化断言。
3. 不给明确买卖时点或价格目标；这是解读，不是指令。
4. 仓位控制信号触发时，overview 必须明确提示控仓与现金 ≥ 目标%。
5. 三周期共振买点的股票要在 stockComments 中点名。
6. 风险要点具体，不泛泛而谈；不复述规则逻辑。
""".trim()

    private fun buildUser(
        stocks: List<EvaluatedStock>,
        dailyBands: Map<String, BollBand?>,
        monthlyBands: Map<String, BollBand?>,
        signals: PortfolioSignals,
        thresholds: DividendThresholds,
        userStrategies: List<UserStrategyRef>,
    ): String {
        val sb = StringBuilder()
        sb.append("【门槛】买入需三周期共振且股息率 ≥ ${thresholds.minYieldPercent}%\n")
        sb.append("【持仓评估】\n")
        if (stocks.isEmpty()) sb.append("（无）\n")
        stocks.forEach { s ->
            val actionZh = when (s.action) {
                HoldingAction.BUY -> "买"
                HoldingAction.SELL -> "卖"
                HoldingAction.HOLD -> "持有"
                HoldingAction.INSUFFICIENT_DATA -> "数据不足"
            }
            val daily = ratioVsLower(s.currentPrice, dailyBands[s.code])
            val weekly = if (s.priceVsLower.isFinite()) "${(s.priceVsLower * 100).toInt()}%" else "—"
            val monthly = ratioVsLower(s.currentPrice, monthlyBands[s.code])
            sb.append("- ${s.code} ${s.name} [${s.industry}]：$actionZh，股息率 ${s.dividendYield?.let { "${"%.1f".format(it)}%" } ?: "—"}")
            sb.append(" | 日距下轨 $daily / 周距下轨 $weekly / 月距下轨 $monthly\n")
        }
        sb.append("【策略信号】\n")
        val pc = signals.positionControl
        if (pc.triggered) {
            sb.append("- 控仓：触发（上轨占比 ${"%.0f".format(pc.upperBandRatio * 100)}%，平均股息率 ${"%.1f".format(pc.avgDividendYield)}%），建议现金 ≥ ${pc.targetCashPercent}%\n")
        } else {
            sb.append("- 控仓：未触发\n")
        }
        if (signals.buySignals.isNotEmpty()) {
            sb.append("- 三周期共振买点：${signals.buySignals.joinToString("、") { it.code }}\n")
        } else {
            sb.append("- 三周期共振买点：无\n")
        }
        // 用户投资原则（全局回流，不含 sourceNote）
        sb.append("【用户投资原则（来自截图分析，全局，仅供参照）】")
        if (userStrategies.isEmpty()) {
            sb.append("—\n")
        } else {
            sb.append("\n")
            userStrategies.forEach { ref ->
                val dirZh = when (ref.direction) { "BUY" -> "买入"; "SELL" -> "卖出"; else -> "观望" }
                sb.append("- [$dirZh] ${ref.reasoning}(${ref.daysAgo}天前)")
                if (ref.risks.isNotEmpty()) sb.append(" 风险:${ref.risks.joinToString("/")}")
                sb.append("\n")
            }
        }
        return sb.toString()
    }

    /** (price - lower) / (upper - lower) → "X%"，clamp 0..100；band/price 无效返回 "—"。 */
    private fun ratioVsLower(price: Double?, band: BollBand?): String {
        if (price == null || price <= 0.0 || band == null || band.upper <= band.lower) return "—"
        val r = ((price - band.lower) / (band.upper - band.lower) * 100).toInt().coerceIn(0, 100)
        return "$r%"
    }
}
