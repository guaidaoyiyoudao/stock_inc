package com.stock.dividend.data.agent

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ToolDisplayNameTest {

    @Test
    fun knownTool_returnsChineseAction() {
        assertThat(ToolDisplayName.name("add_stock")).isEqualTo("添加自选")
        assertThat(ToolDisplayName.name("get_holdings")).isEqualTo("查询持仓")
        assertThat(ToolDisplayName.name("add_trade_strategy")).isEqualTo("写入策略")
        assertThat(ToolDisplayName.name("get_kline")).isEqualTo("查询 K 线")
    }

    @Test
    fun unknownTool_returnsGenericFallback_noRawName() {
        // 未知工具回退为通用动作，不暴露原始 snake_case 标识
        val display = ToolDisplayName.name("some_new_tool_v2")
        assertThat(display).isEqualTo("处理操作")
        assertThat(display).doesNotContain("some_new_tool_v2")
    }

    @Test
    fun everyRegisteredTool_hasMapping() {
        // 防御：新增工具时若漏配映射，本测试会失败提醒
        val allTools = listOf(
            "search_stock", "get_stock_info", "get_dividend_history", "get_dividend_forecast",
            "get_valuation", "get_buy_threshold", "get_stock_evaluation", "get_stock_fundamentals",
            "get_kline", "get_holdings", "get_portfolio_summary", "get_industry_allocation",
            "get_transactions", "get_notification_rules", "get_user_strategies",
            "get_portfolio_signals", "get_dividend_income", "add_stock", "remove_stock",
            "update_holding", "add_transaction", "set_stock_tags", "update_industry_target",
            "update_notification_rule", "update_stock_settings", "add_trade_strategy",
            "get_living_expenses", "add_living_expense", "update_living_expense",
            "remove_living_expense", "set_fire_goal",
        )
        allTools.forEach { code ->
            val display = ToolDisplayName.name(code)
            assertThat(display).isNotEqualTo("处理操作")
        }
    }
}
