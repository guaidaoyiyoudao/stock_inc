package com.stock.dividend.data.repository

/**
 * 回流到 LLM prompt 的用户策略引用（纯数据，无 Android 依赖）。
 * 策略是全局投资原则，对所有标的生效；**不含 sourceNote**（来源不入 prompt）。
 */
data class UserStrategyRef(
    val direction: String,       // BUY/SELL/WATCH
    val reasoning: String,
    val risks: List<String>,
    val validUntil: String?,
    val daysAgo: Int             // 距今天数，让 LLM 感知时效
)
