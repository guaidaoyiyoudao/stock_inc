package com.stock.dividend.data.repository

import androidx.compose.runtime.Stable

/** 一只股票的评估结果（规则评估产出，结果页直接渲染）。 */
@Stable
data class EvaluatedStock(
    val code: String,
    val name: String,
    val industry: String,
    val action: HoldingAction,
    val priceVsLower: Double,
    val dividendYield: Double?,
    val bollBand: BollBand?,
    val currentPrice: Double?,
    val reasons: List<String>
)
