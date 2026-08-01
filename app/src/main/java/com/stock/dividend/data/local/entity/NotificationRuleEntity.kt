package com.stock.dividend.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

const val NOTIFICATION_RULE_TYPE_DIVIDEND_YIELD_THRESHOLD = "DIVIDEND_YIELD_THRESHOLD"
const val NOTIFICATION_RULE_TYPE_DIVIDEND_YIELD_BELOW_THRESHOLD = "DIVIDEND_YIELD_BELOW_THRESHOLD"
const val NOTIFICATION_RULE_TYPE_PRICE_ABOVE = "PRICE_ABOVE"
const val NOTIFICATION_RULE_TYPE_PRICE_BELOW = "PRICE_BELOW"
const val NOTIFICATION_RULE_TYPE_BOLL_WEEKLY_UPPER = "BOLL_WEEKLY_UPPER"
const val EVAL_MIN_YIELD = "EVAL_MIN_YIELD"
const val EVAL_BOOST_YIELD = "EVAL_BOOST_YIELD"

@Entity(
    tableName = "notification_rules",
    indices = [
        Index(value = ["type", "stockCode"], unique = true)
    ]
)
data class NotificationRuleEntity(
    @PrimaryKey
    val id: String,
    val type: String,
    val stockCode: String? = null,
    val enabled: Boolean = true,
    val thresholdPercent: Double = 5.0,
    val lastWasAboveThreshold: Boolean? = null,
    val lastCheckedAt: Long? = null,
    val lastTriggeredAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
