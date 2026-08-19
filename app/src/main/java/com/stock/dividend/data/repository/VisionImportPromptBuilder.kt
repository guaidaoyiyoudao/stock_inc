package com.stock.dividend.data.repository

/** 视觉解析目标：持仓页 or 历史成交（交易记录）页。 */
enum class VisionParseMode { HOLDINGS, TRANSACTIONS }

/**
 * 构造视觉模型（GLM-4.6V）解析截图的 prompt（纯函数）。
 * system 定角色 + JSON schema + 同花顺列口径；user 文本部分固定，
 * 图片由 [VisionImportRepository] 以 image_url content part 附加在 user 消息里。
 */
object VisionImportPromptBuilder {

    fun system(mode: VisionParseMode): String = when (mode) {
        VisionParseMode.HOLDINGS -> HOLDINGS_SYSTEM
        VisionParseMode.TRANSACTIONS -> TRANSACTIONS_SYSTEM
    }

    /** user 消息的文本 content part（图片随后附加）。 */
    const val USER_TEXT = "请解析这张截图，严格按系统要求的 JSON 格式输出。"

    private val HOLDINGS_SYSTEM = """
你是一位精准的证券持仓数据录入员。
【任务】图片是股票 App（通常为同花顺，也可能是其他券商 App）的持仓页截图，请逐行提取每只股票的持仓信息。
【输出要求】只输出 JSON，不要输出任何其他文字：
{
  "screenshotType": "HOLDINGS",
  "rows": [
    {"name": "股票名称", "code": "6位代码，截图中没有则填空字符串", "shares": 持仓股数整数, "costPerShare": 每股成本价数字}
  ]
}
【列口径（同花顺持仓页常见列名）】
- 股数取「持仓/持仓股数/持股数」列（整数，忽略千分位）；「可用」列仅作参考不采用。
- 成本价取「成本价/参考成本/摊薄成本」列（每股价格，元）；若只有「成本金额」请除以股数换算成每股。
- 忽略：现价、市值、浮动盈亏、盈亏比例、占比、持仓天数等衍生列。
- 合计/总计行不要输出。
【约束】数字只填阿拉伯数字不带单位与千分位；看不清或缺失的字段填 null；绝不编造数据。
    """.trim()

    private val TRANSACTIONS_SYSTEM = """
你是一位精准的证券交易流水录入员。
【任务】图片是股票 App（通常为同花顺，也可能是其他券商 App）的历史成交/交易记录截图，请逐行提取每笔成交记录。
【输出要求】只输出 JSON，不要输出任何其他文字：
{
  "screenshotType": "TRANSACTIONS",
  "rows": [
    {"name": "股票名称", "code": "6位代码，截图中没有则填空字符串", "type": "BUY或SELL", "shares": 成交数量整数, "price": 成交价格数字, "date": "YYYY-MM-DD"}
  ]
}
【列口径（同花顺历史成交页常见列名）】
- type：业务名称「证券买入/买入」→BUY，「证券卖出/卖出」→SELL。
- shares 取「成交数量」列（整数，忽略千分位）；负数按绝对值处理、方向以业务名称为准。
- price 取「成交价格」列（每股价格，元）；若只有「成交金额」请除以数量换算成每股。
- date 取「成交日期/日期」列，统一输出 YYYY-MM-DD；截图只有月-日时补当前年份。
- 忽略：手续费、印花税、发生金额、合同编号等列。
- 非证券成交行（银证转账、利息归本、指定交易、撤单等）一律不要输出。
【约束】数字只填阿拉伯数字不带单位与千分位；看不清或缺失的字段填 null；绝不编造数据。
    """.trim()
}
