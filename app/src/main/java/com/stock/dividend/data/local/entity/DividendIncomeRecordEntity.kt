package com.stock.dividend.data.local.entity

import androidx.compose.runtime.Stable
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Stable
@Entity(
    tableName = "dividend_income_records",
    foreignKeys = [
        ForeignKey(
            entity = StockEntity::class,
            parentColumns = ["code"],
            childColumns = ["stockCode"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("stockCode"), Index("year")]
)
data class DividendIncomeRecordEntity(
    @PrimaryKey
    val id: String,
    val stockCode: String? = null,
    val year: Int,
    val date: String,
    val amount: Double,
    val exDividendDate: String? = null,
    val source: String = "auto",
    val note: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
