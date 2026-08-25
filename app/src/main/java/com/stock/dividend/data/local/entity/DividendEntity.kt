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
    /** 每股送转比例（送股+转增合计，股/股）。纯送转行 cashPerShare=0——**必须落库**：K 线漂移检测按 MAX(exDividendDate) 感知除权，送转同样整体位移前复权历史（审计 M4-1）。 */
    val bonusPerShare: Double? = null,
    val dividendYield: Double? = null,
    val exDividendDate: String? = null,
    val recordDate: String? = null,
    val planNoticeDate: String? = null,
    val planStatus: String? = null
)
