package com.stock.dividend.viewmodel

enum class AchievementCategory(
    val id: String,
    val title: String,
    val description: String,
    val icon: String
) {
    INCOME_MILESTONE("income_milestone", "收入里程碑", "迈向财务自由之路", "💰"),
    INVESTMENT_STRATEGY("investment_strategy", "投资策略", "构建多元化组合", "📊"),
    LONG_TERM_COMMITMENT("long_term_commitment", "长期坚持", "时间是最好的朋友", "⏳"),
    RECORDING_HABIT("recording_habit", "记录习惯", "坚持记录每一笔股息", "📝"),
    INCOME_BREAKTHROUGH("income_breakthrough", "收益突破", "追求更高的股息回报", "🚀"),
    GOAL_ACHIEVEMENT("goal_achievement", "目标达成", "向 FIRE 财务自由迈进", "🎯"),
    DATA_COMPLETENESS("data_completeness", "数据完整", "完善投资数据，掌控全局", "✅")
}
