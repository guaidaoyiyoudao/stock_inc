package com.stock.dividend.data.local.entity

import androidx.compose.runtime.Stable
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 行业目标配比：行业 → 占总资产的目标百分比（0-100）。
 * 与 [StockEntity.targetWeight]（个股占其行业的%）形成两层配比模型。
 */
@Stable
@Entity(tableName = "industry_targets")
data class IndustryTargetEntity(
    @PrimaryKey
    val industry: String,
    val targetWeight: Double = 0.0
)
