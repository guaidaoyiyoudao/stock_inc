package com.stock.dividend.data.local.entity

import androidx.compose.runtime.Stable
import androidx.room.Entity
import androidx.room.PrimaryKey

@Stable
@Entity(tableName = "stocks")
data class StockEntity(
    @PrimaryKey
    val code: String,
    val name: String,
    val marketCode: String,
    val addedAt: Long = System.currentTimeMillis(),
    val lastUpdated: Long? = null,
    val shares: Int = 0,
    val yieldPeriod: String = "3",
    val costPerShare: Double = 0.0,
    val targetWeight: Double = 0.0
)
