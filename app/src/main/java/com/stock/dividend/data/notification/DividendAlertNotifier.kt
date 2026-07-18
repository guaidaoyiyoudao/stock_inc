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
        thresholdValue: Double
    )
}
