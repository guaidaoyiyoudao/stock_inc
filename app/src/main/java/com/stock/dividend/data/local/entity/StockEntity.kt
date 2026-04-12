package com.stock.dividend.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "stocks")
data class StockEntity(
    @PrimaryKey
    val code: String,
    val name: String,
    val marketCode: String,
    val addedAt: Long = System.currentTimeMillis(),
    val lastUpdated: Long? = null
)
