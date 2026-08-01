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
    val targetWeight: Double = 0.0,
    val industry: String = "",
    /** 买入阈值倍数：当前股息率达到「10Y 国债收益率 × 该倍数」时提示买入。默认 2.5。 */
    val buyThresholdMultiplier: Double = DEFAULT_BUY_THRESHOLD_MULTIPLIER
) {
    companion object {
        const val DEFAULT_BUY_THRESHOLD_MULTIPLIER = 2.5
    }
}
