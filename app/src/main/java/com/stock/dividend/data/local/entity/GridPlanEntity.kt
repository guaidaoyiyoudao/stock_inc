package com.stock.dividend.data.local.entity

import androidx.compose.runtime.Stable
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 网格交易计划：用户为某只股票设定的网格参数（基准价/区间/档数/总资金），
 * 用于生成等差网格买卖档位表。**仅做计划与提示，不联网下单**。
 *
 * - [stockCode] 关联标的；删除股票时级联删除其网格计划（外键）。
 * - [basePrice] 基准价（中轴），[lowPrice]/[highPrice] 为网格上下界。
 * - [grids] 网格档数（区间内等分档位数，≥2）。
 * - [totalCapital] 投入总资金（元），按等差权重分配到各档。
 */
@Stable
@Entity(
    tableName = "grid_plans",
    foreignKeys = [
        androidx.room.ForeignKey(
            entity = StockEntity::class,
            parentColumns = ["code"],
            childColumns = ["stockCode"],
            onDelete = androidx.room.ForeignKey.CASCADE
        )
    ],
    indices = [Index("stockCode")]
)
data class GridPlanEntity(
    @PrimaryKey
    val id: String,
    val stockCode: String,
    val stockName: String,
    val basePrice: Double,
    val lowPrice: Double,
    val highPrice: Double,
    val grids: Int,
    val totalCapital: Double,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
