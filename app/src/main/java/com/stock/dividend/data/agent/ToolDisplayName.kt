package com.stock.dividend.data.agent

/**
 * 把 snake_case 工具名映射为面向用户的中文动作名。纯函数，便于单测。
 *
 * UI 只显示动作（「查询持仓」「添加自选」），不暴露内部工具名；
 * 未知工具回退为「处理操作」，避免露出生硬的英文标识。
 */
object ToolDisplayName {
    private val NAMES: Map<String, String> = mapOf(
        // ── 行情/个股 ──
        "search_stock" to "搜索股票",
        "get_stock_info" to "查询行情",
        "get_dividend_history" to "查询分红历史",
        "get_dividend_forecast" to "预测股息收入",
        "get_valuation" to "查询估值",
        "get_buy_threshold" to "计算买入线",
        "get_stock_evaluation" to "评估个股",
        "get_stock_fundamentals" to "查询基本面",
        "get_kline" to "查询 K 线",
        // ── 组合 ──
        "get_holdings" to "查询持仓",
        "get_portfolio_summary" to "汇总组合",
        "get_industry_allocation" to "查询行业配比",
        "get_transactions" to "查询交易记录",
        "get_notification_rules" to "查询通知规则",
        "get_user_strategies" to "查询策略库",
        "get_portfolio_signals" to "查询组合信号",
        "get_dividend_income" to "查询股息收入",
        // ── 组合分析（2026-08-15 新增）──
        "get_market_ranking" to "查全市场榜单",
        "compare_stocks" to "多股对比",
        "diagnose_portfolio" to "诊断组合风险",
        // ── 写操作 ──
        "add_stock" to "添加自选",
        "remove_stock" to "删除自选",
        "update_holding" to "修改持仓",
        "add_transaction" to "记录交易",
        "set_stock_tags" to "设置标签",
        "update_industry_target" to "设置行业目标",
        "update_notification_rule" to "设置提醒",
        "update_stock_settings" to "修改个股参数",
        "add_trade_strategy" to "写入策略",
        // ── 财务 ──
        "get_living_expenses" to "查询支出",
        "add_living_expense" to "添加支出",
        "update_living_expense" to "修改支出",
        "remove_living_expense" to "删除支出",
        "set_fire_goal" to "设置 FIRE 目标",
    )

    fun name(toolName: String): String = NAMES[toolName] ?: "处理操作"
}
