package com.stock.dividend.viewmodel

enum class AchievementCategory(
    val id: String,
    val title: String,
    val description: String,
    val icon: String
) {
    INCOME_MILESTONE("income_milestone", "收入里程碑", "迈向财务自由之路", "💰"),
    INVESTMENT_STRATEGY("investment_strategy", "投资策略", "构建多元化组合", "📊"),
    LONG_TERM_COMMITMENT("long_term_commitment", "长期坚持", "时间是最好的朋友", "⏳")
}
