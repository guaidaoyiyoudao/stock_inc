package com.stock.dividend.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** LLM 解读结果缓存（prompt 哈希 key；scope=PORTFOLIO/STOCK；payload 为对应分析的 Gson JSON）。 */
@Entity(tableName = "llm_analysis_cache")
data class LlmAnalysisCacheEntity(
    @PrimaryKey
    val cacheKey: String,
    val scope: String,
    val payload: String,
    val createdAt: Long
)
