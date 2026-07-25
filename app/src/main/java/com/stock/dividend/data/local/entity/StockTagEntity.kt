package com.stock.dividend.data.local.entity

import androidx.compose.runtime.Stable
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Stable
@Entity(
    tableName = "stock_tags",
    primaryKeys = ["stockCode", "tag"],
    foreignKeys = [ForeignKey(
        entity = StockEntity::class,
        parentColumns = ["code"],
        childColumns = ["stockCode"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("stockCode"), Index("tag")]
)
data class StockTagEntity(
    val stockCode: String,
    val tag: String,
    val createdAt: Long = System.currentTimeMillis()
)
