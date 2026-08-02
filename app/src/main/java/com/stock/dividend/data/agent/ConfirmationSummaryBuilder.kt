package com.stock.dividend.data.agent

/** 把写工具名 + 参数转为用户可读的确认摘要。纯函数。 */
internal object ConfirmationSummaryBuilder {
    fun summarize(toolName: String, args: Map<String, Any?>): String = when (toolName) {
        "add_stock" -> "添加自选：${args["code"]}（${args["shares"] ?: 0} 股）"
        "remove_stock" -> "删除自选：${args["code"]}"
        "update_holding" -> "修改持仓：${args["code"]} → ${args["shares"]} 股，成本 ${args["costPerShare"]}"
        "add_transaction" -> "记录交易：${args["code"]} ${args["type"]} ${args["shares"]} 股 @ ${args["price"]}"
        "set_stock_tags" -> "设置标签：${args["code"]} → ${args["tags"]}"
        "update_industry_target" -> "设置行业目标：${args["industry"]} = ${args["weight"]}%"
        "update_notification_rule" -> if (args["code"] != null) {
            "设置个股股息率提醒：${args["code"]} ≥ ${args["thresholdPercent"]}%（启用=${args["enabled"] ?: true}）"
        } else {
            "更新评估门槛：min=${args["minYield"]}%, boost=${args["boostYield"]}%"
        }
        "update_stock_settings" -> "修改个股参数：${args["code"]}（倍数=${args["buyThresholdMultiplier"] ?: "不变"}，预测年限=${args["yieldPeriod"] ?: "不变"}）"
        "add_living_expense" -> "添加支出：${args["name"]} ${args["amount"]} 元（${args["period"]}）"
        "update_living_expense" -> "修改支出：id=${args["id"]} ${args["name"]} ${args["amount"]} 元"
        "remove_living_expense" -> "删除支出：id=${args["id"]}"
        "set_fire_goal" -> "设置 FIRE 目标：${args["amount"]} 元"
        "add_trade_strategy" -> "写入策略：[${args["direction"]}] ${args["targetText"]}"
        else -> "$toolName（$args）"
    }
}
