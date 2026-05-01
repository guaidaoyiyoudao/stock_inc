package com.stock.dividend.viewmodel

enum class AchievementDef(
    val id: String,
    val title: String,
    val description: String,
    val icon: String
) {
    FIRST_DIVIDEND("first_dividend", "首次分红", "收到第一笔股息收入", "🌱"),
    INCOME_1K("income_1k", "收入破千", "单年股息收入达到1,000元", "🌿"),
    INCOME_10K("income_10k", "收入破万", "单年股息收入达到10,000元", "🌳"),
    INCOME_100K("income_100k", "收入十万", "单年股息收入达到100,000元", "🏔️"),
    PORTFOLIO_START("portfolio_start", "持仓起步", "开始关注第一只股票", "🚩"),
    DIVERSIFY_5("diversify_5", "分散投资", "同时持有5只以上股票", "🛡️"),
    HOLD_1Y("hold_1y", "坚持持有", "最早添加的股票持有满一年", "💎"),
    STREAK_3Y("streak_3y", "连年分红", "连续3年有股息收入", "❄️");
}
