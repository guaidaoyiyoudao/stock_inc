package com.stock.dividend.data.repository

/**
 * 把规则评估结果 + 策略信号 + 每股深度数据（基本面/预测/买入线）序列化为 LLM prompt（纯函数）。
 * system 定角色 + JSON schema + 约束；user 放结构化数据。不喂成本价等敏感信息。
 */
object LlmPromptBuilder {

    data class LlmPrompt(val system: String, val user: String)

    fun build(input: PortfolioLlmInput): LlmPrompt = LlmPrompt(SYSTEM, buildUser(input))

    private val SYSTEM = """
你是一位稳健、客观的中文分红股投资分析助手。

【任务】
基于用户提供的持仓评估数据、策略信号与每股深度数据（已由规则引擎判定），输出自然语言解读。

【数据语义（仅供理解，不要复述规则公式）】
- action=买：日下轨+周下轨+月中轨及以下 三周期共振，且股息率达最低门槛
- action=卖：价格处于周线 BOLL 上轨附近（偏高）
- action=持有：未达三周期共振（中轨、仅单一周期偏低、或股息率不足）
- 距下轨%：0=在下轨（便宜），100=在上轨（贵）；每只股给出 日/周/月 三周期的距下轨%，据此判断多周期共振
- 股息率%：年现金分红 / 现价
- 仓位控制信号：多数股票抵达上轨 + 整体股息偏低 → 建议控仓、现金 ≥ 目标%
- 三周期共振买点：与 action=买 同源（日下轨 + 周下轨 + 月中轨及以下 同时成立）
- 基本面：ROE/负债率/营收净利同比/派息率为最新报告期数据；趋势为近 N 期整体方向，供判断质地与分红可持续性
- 预测：基于历史分红的线性平均，非承诺；实际样本年数越少越不可靠
- 买入线：股息率达到「国债收益率×倍数」时视为低估信号
- 用户投资原则：用户此前从外部内容整理出的整体投资观点，对所有标的通用，属用户个人视角，非客观数据；解读时可对照呼应，但不要盲从或简单复述。

【输出要求】严格输出 JSON：
{"overview":"组合整体解读≤150字","stockComments":{"<code>":{"brief":"该股≤60字","risks":["该股具体风险点"]}},"risks":["具体风险点"]}

【约束】
1. 仅基于提供数据，绝不编造价格/股息率/财报/未给出的信息；缺失数据标"—"的部分不要臆测。
2. 中文，专业易懂，避免绝对化断言。
3. 不给明确买卖时点或价格目标；这是解读，不是指令。
4. 仓位控制信号触发时，overview 必须明确提示控仓与现金 ≥ 目标%。
5. 三周期共振买点的股票要在 stockComments 中点名。
6. 每股 brief 需结合基本面/预测/买入线等深度数据，风险点要具体，不复述规则逻辑。
""".trim()

    private fun buildUser(input: PortfolioLlmInput): String {
        val stocks = input.evaluation
        val sb = StringBuilder()
        sb.append("【门槛】买入需三周期共振且股息率 ≥ ${input.thresholds.minYieldPercent}%\n")
        sb.append("【持仓评估】\n")
        if (stocks.isEmpty()) sb.append("（无）\n")
        stocks.forEach { s ->
            val actionZh = when (s.action) {
                HoldingAction.BUY -> "买"
                HoldingAction.SELL -> "卖"
                HoldingAction.HOLD -> "持有"
                HoldingAction.INSUFFICIENT_DATA -> "数据不足"
            }
            val daily = ratioVsLower(s.currentPrice, input.dailyBands[s.code])
            val weekly = if (s.priceVsLower.isFinite()) "${(s.priceVsLower * 100).toInt()}%" else "—"
            val monthly = ratioVsLower(s.currentPrice, input.monthlyBands[s.code])
            sb.append("- ${s.code} ${s.name} [${s.industry}]：$actionZh，股息率 ${s.dividendYield?.let { "${"%.1f".format(it)}%" } ?: "—"}")
            sb.append(" | 日距下轨 $daily / 周距下轨 $weekly / 月距下轨 $monthly")
            appendDeepData(sb, input.stockDetails[s.code])
            sb.append("\n")
        }
        sb.append("【策略信号】\n")
        val pc = input.signals.positionControl
        if (pc.triggered) {
            sb.append("- 控仓：触发（上轨占比 ${"%.0f".format(pc.upperBandRatio * 100)}%，平均股息率 ${"%.1f".format(pc.avgDividendYield)}%），建议现金 ≥ ${pc.targetCashPercent}%\n")
        } else {
            sb.append("- 控仓：未触发\n")
        }
        if (input.signals.buySignals.isNotEmpty()) {
            sb.append("- 三周期共振买点：${input.signals.buySignals.joinToString("、") { it.code }}\n")
        } else {
            sb.append("- 三周期共振买点：无\n")
        }
        sb.append("【用户投资原则（来自截图分析，全局，仅供参照）】")
        if (input.userStrategies.isEmpty()) {
            sb.append("—\n")
        } else {
            sb.append("\n")
            input.userStrategies.forEach { ref ->
                val dirZh = when (ref.direction) { "BUY" -> "买入"; "SELL" -> "卖出"; else -> "观望" }
                sb.append("- [$dirZh] ${ref.reasoning}(${ref.daysAgo}天前)")
                if (ref.risks.isNotEmpty()) sb.append(" 风险:${ref.risks.joinToString("/")}")
                sb.append("\n")
            }
        }
        return sb.toString()
    }

    /** 每股深度数据要点式渲染；缺失渲染 "—"，不阻塞。 */
    private fun appendDeepData(sb: StringBuilder, detail: PortfolioLlmStockDetail?) {
        if (detail == null) {
            sb.append(" | 基本面 — / 预测 — / 买入线 —")
            return
        }
        appendFundamentals(sb, detail.fundamentals)
        appendForecast(sb, detail.forecast)
        appendBuyThreshold(sb, detail.buyThreshold)
    }

    private fun appendFundamentals(sb: StringBuilder, f: Fundamentals?) {
        if (f == null || f.periods.isEmpty()) {
            sb.append(" | 基本面 —")
            return
        }
        val latest = f.periods.last()
        sb.append(" | 基本面: ROE ${fmtPercent(latest.roe)} / 负债率 ${fmtPercent(latest.debtToAssetRatio)}")
        sb.append(" / 营收 ${fmtYoy(latest.revenueYoy)} / 净利 ${fmtYoy(latest.netProfitYoy)}")
        sb.append(" / 派息率 ${fmtPercent(latest.payoutRatio)}")
        latest.dividendPlan?.takeIf { it.isNotBlank() }?.let { sb.append(" / $it") }
        sb.append("（近${f.periods.size}期${roeTrendZh(f.periods)}）")
    }

    private fun appendForecast(sb: StringBuilder, f: StockLlmInput.StockLlmForecast?) {
        if (f == null) {
            sb.append(" / 预测 —")
            return
        }
        sb.append(" / 预测: 1年均 ¥${"%.2f".format(f.avgCashPerShare1Y)}")
        sb.append(" / 3年均 ¥${"%.2f".format(f.avgCashPerShare3Y)}")
        sb.append(" / 5年均 ¥${"%.2f".format(f.avgCashPerShare5Y)}（样本 ${f.actualYears} 年）")
    }

    private fun appendBuyThreshold(sb: StringBuilder, bt: StockLlmInput.StockLlmBuyThreshold?) {
        if (bt == null) {
            sb.append(" / 买入线 —")
            return
        }
        sb.append(" / 买入线: 目标 ${"%.1f".format(bt.targetYieldPercent)}%")
        sb.append("，当前 ${bt.currentYieldPercent?.let { "${"%.1f".format(it)}%" } ?: "—"}")
        val reachedZh = when (bt.reached) {
            true -> "已达标"
            false -> "未达标"
            null -> "无法判定"
        }
        sb.append("，$reachedZh")
    }

    /** ROE 序列趋势（非空值首末比较，近似描述；样本 <2 期为"数据不足"）。 */
    private fun roeTrendZh(periods: List<Fundamentals.Period>): String {
        val roes = periods.mapNotNull { it.roe?.takeIf { v -> v.isFinite() } }
        return when {
            roes.size < 2 -> "数据不足"
            roes.last() > roes.first() + 0.3 -> "ROE整体上升"
            roes.first() > roes.last() + 0.3 -> "ROE整体下降"
            else -> "ROE整体平稳"
        }
    }

    private fun fmtPercent(v: Double?): String = when {
        v == null || !v.isFinite() -> "—"
        else -> "${"%.1f".format(v)}%"
    }

    private fun fmtYoy(v: Double?): String = when {
        v == null || !v.isFinite() -> "—"
        else -> "${if (v >= 0) "+" else ""}${"%.1f".format(v)}%"
    }

    /** (price - lower) / (upper - lower) → "X%"，clamp 0..100；band/price 无效返回 "—"。 */
    private fun ratioVsLower(price: Double?, band: BollBand?): String {
        if (price == null || price <= 0.0 || band == null || band.upper <= band.lower) return "—"
        val r = ((price - band.lower) / (band.upper - band.lower) * 100).toInt().coerceIn(0, 100)
        return "$r%"
    }
}
