package com.stock.dividend.data.local.entity

import androidx.compose.runtime.Stable
import androidx.room.Entity
import androidx.room.PrimaryKey

const val STRATEGY_DIRECTION_BUY = "BUY"
const val STRATEGY_DIRECTION_SELL = "SELL"
const val STRATEGY_DIRECTION_WATCH = "WATCH"
const val STRATEGY_STATUS_ACTIVE = "ACTIVE"
const val STRATEGY_STATUS_ARCHIVED = "ARCHIVED"

/**
 * 截图策略分析产出的买卖策略。策略是**全局投资原则**（不绑定个股）：
 * 对所有股票生效（如「大盘破 3000 加仓」），不存 stockCode。
 * `targetText` 仅是 LLM 提取的标的/语境描述（如「招商银行」「银行业」「大盘」），
 * 供人类阅读，不作关联键。`risks` 存 JSON 数组字符串。
 */
@Stable
@Entity(tableName = "trade_strategies")
data class TradeStrategyEntity(
    @PrimaryKey
    val id: String,
    val targetText: String,
    val direction: String,
    val reasoning: String,
    val risks: String,
    val validUntil: String?,
    val sourceNote: String?,
    val rawOcrText: String,
    val status: String = STRATEGY_STATUS_ACTIVE,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
