package com.stock.dividend.data.local.entity

import androidx.compose.runtime.Stable
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Stable
@Entity(
    tableName = "dividends",
    foreignKeys = [
        ForeignKey(
            entity = StockEntity::class,
            parentColumns = ["code"],
            childColumns = ["stockCode"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("stockCode")]
)
data class DividendEntity(
    @PrimaryKey
    val id: String,
    val stockCode: String,
    val reportDate: String,
    val cashPerShare: Double = 0.0,
    val dividendYield: Double? = null,
    val exDividendDate: String? = null,
    val recordDate: String? = null,
    val planStatus: String? = null
)
