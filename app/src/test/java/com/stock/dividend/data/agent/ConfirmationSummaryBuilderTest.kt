package com.stock.dividend.data.agent

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ConfirmationSummaryBuilderTest {

    @Test
    fun updateNotificationRule_globalThresholdSummary() {
        val summary = ConfirmationSummaryBuilder.summarize(
            "update_notification_rule",
            mapOf("minYield" to 2.0, "boostYield" to 5.0)
        )
        assertThat(summary).isEqualTo("更新评估门槛：min=2.0%, boost=5.0%")
    }

    @Test
    fun updateNotificationRule_stockRuleSummary() {
        val summary = ConfirmationSummaryBuilder.summarize(
            "update_notification_rule",
            mapOf("code" to "600519", "thresholdPercent" to 5.0)
        )
        assertThat(summary).isEqualTo("设置个股股息率提醒：600519 ≥ 5.0%（启用=true）")
    }

    @Test
    fun updateStockSettings_summary() {
        val summary = ConfirmationSummaryBuilder.summarize(
            "update_stock_settings",
            mapOf("code" to "600519", "buyThresholdMultiplier" to 3.0, "yieldPeriod" to "5")
        )
        assertThat(summary).isEqualTo("修改个股参数：600519（倍数=3.0，预测年限=5）")
    }

    @Test
    fun addTradeStrategy_summary() {
        val summary = ConfirmationSummaryBuilder.summarize(
            "add_trade_strategy",
            mapOf("direction" to "BUY", "targetText" to "银行股")
        )
        assertThat(summary).isEqualTo("写入策略：[BUY] 银行股")
    }
}
