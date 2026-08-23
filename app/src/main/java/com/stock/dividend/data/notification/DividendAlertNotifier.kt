package com.stock.dividend.data.notification

interface DividendAlertNotifier {
    suspend fun canNotify(): Boolean

    suspend fun sendDividendYieldAlert(
        stockCode: String,
        stockName: String,
        yieldPercent: Double,
        thresholdPercent: Double
    )

    suspend fun sendNotificationRuleAlert(
        stockCode: String,
        stockName: String,
        ruleType: String,
        metricValue: Double,
        thresholdValue: Double,
        /** 通知去重键：同一标的多条来源（如同股多套网格）各自独立成条；null=沿用按股票聚合的旧行为。 */
        dedupKey: String? = null
    )

    /** 交易策略卖出阈值到档提醒（年线定投：高于年线卖一半/全卖；去重状态存 strategy_plans）。 */
    suspend fun sendStrategySellAlert(signal: StrategySellSignal)
}
