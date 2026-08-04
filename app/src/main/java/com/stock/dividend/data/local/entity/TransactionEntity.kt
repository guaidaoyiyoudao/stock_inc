package com.stock.dividend.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "transactions",
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
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val stockCode: String,
    val type: String,
    val shares: Int,
    val price: Double = 0.0,
    val date: String,
    val createdAt: Long = System.currentTimeMillis(),
    /** 交易备注/复盘笔记（用户自由填写，可为空）。v19 新增。 */
    val note: String? = null
)
