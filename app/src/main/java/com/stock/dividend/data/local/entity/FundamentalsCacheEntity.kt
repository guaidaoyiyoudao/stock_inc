package com.stock.dividend.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 单股基本面缓存（季报级慢变数据，7 天 TTL；payload 为 Fundamentals 的 Gson JSON）。 */
@Entity(tableName = "fundamentals_cache")
data class FundamentalsCacheEntity(
    @PrimaryKey
    val stockCode: String,
    val payload: String,
    val fetchedAt: Long
)
