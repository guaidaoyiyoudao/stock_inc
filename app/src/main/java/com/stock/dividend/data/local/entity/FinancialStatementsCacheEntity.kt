package com.stock.dividend.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 单股财务三表缓存（季报级慢变数据，7 天 TTL；payload 为 FinancialStatements 的 Gson JSON）。 */
@Entity(tableName = "financial_statements_cache")
data class FinancialStatementsCacheEntity(
    @PrimaryKey
    val stockCode: String,
    val payload: String,
    val fetchedAt: Long
)
