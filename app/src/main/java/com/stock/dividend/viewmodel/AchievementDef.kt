package com.stock.dividend.viewmodel

enum class AchievementDef(
    val id: String,
    val title: String,
    val description: String,
    val icon: String,
    val category: AchievementCategory
) {
    FIRST_DIVIDEND("first_dividend", "首次分红", "收到第一笔股息收入", "🌱", AchievementCategory.INCOME_MILESTONE),
    INCOME_1K("income_1k", "收入破千", "单年股息收入达到1,000元", "🌿", AchievementCategory.INCOME_MILESTONE),
    INCOME_10K("income_10k", "收入破万", "单年股息收入达到10,000元", "🌳", AchievementCategory.INCOME_MILESTONE),
    INCOME_100K("income_100k", "收入十万", "单年股息收入达到100,000元", "🏔️", AchievementCategory.INCOME_MILESTONE),
    PORTFOLIO_START("portfolio_start", "持仓起步", "开始关注第一只股票", "🚩", AchievementCategory.INVESTMENT_STRATEGY),
    DIVERSIFY_5("diversify_5", "分散投资", "同时持有5只以上股票", "🛡️", AchievementCategory.INVESTMENT_STRATEGY),
    HOLD_1Y("hold_1y", "坚持持有", "最早添加的股票持有满一年", "💎", AchievementCategory.LONG_TERM_COMMITMENT),
    STREAK_3Y("streak_3y", "连年分红", "连续3年有股息收入", "❄️", AchievementCategory.LONG_TERM_COMMITMENT),
    RECORD_10("record_10", "勤于记录", "累计记录10笔股息收入", "📝", AchievementCategory.RECORDING_HABIT),
    RECORD_50("record_50", "记录达人", "累计记录50笔股息收入", "📋", AchievementCategory.RECORDING_HABIT),
    SINGLE_100("single_100", "单笔突破", "单笔股息收入超过100元", "🚀", AchievementCategory.INCOME_BREAKTHROUGH),
    YOY_GROWTH_50("yoy_growth_50", "年年增长", "年度股息收入同比增长50%以上", "📈", AchievementCategory.INCOME_BREAKTHROUGH),
    STOCK_INCOME_1K("stock_income_1k", "股息王", "单只股票年度股息超过1,000元", "👑", AchievementCategory.INCOME_BREAKTHROUGH),
    SET_FIRE_GOAL("set_fire_goal", "确立目标", "设置FIRE财务自由目标", "🎯", AchievementCategory.GOAL_ACHIEVEMENT),
    FIRE_PROGRESS_10("fire_progress_10", "起步前行", "FIRE目标进度达到10%", "🏃", AchievementCategory.GOAL_ACHIEVEMENT),
    FIRE_PROGRESS_50("fire_progress_50", "半程之星", "FIRE目标进度达到50%", "🌟", AchievementCategory.GOAL_ACHIEVEMENT),
    COMPLETE_PROFILE("complete_profile", "完整档案", "所有持仓股票都填写了股数和成本价", "✅", AchievementCategory.DATA_COMPLETENESS),
    PORTFOLIO_10("portfolio_10", "投资全景", "同时持有10只以上有完整数据的股票", "📊", AchievementCategory.DATA_COMPLETENESS);
}
