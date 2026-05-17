package com.stock.dividend.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

const val NOTIFICATION_RULE_TYPE_DIVIDEND_YIELD_THRESHOLD = "DIVIDEND_YIELD_THRESHOLD"

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
