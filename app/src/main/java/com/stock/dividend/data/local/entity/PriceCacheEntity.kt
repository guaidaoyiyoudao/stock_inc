package com.stock.dividend.data.local.entity

import androidx.compose.runtime.Stable
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 实时价格缓存：code → 最近一次拉到的现价。
 *
 * - 不设外键：搜索预览阶段的非持仓股价格也要缓存（加自选前的预览价）。
 * - 永久缓存 + 后台刷新：缓存作"有总比无好"的兜底，冷启动先用缓存价重算，
 *   后台拉到新价后覆盖（updatedAt 记录新鲜度，便于后续 UI 标注）。
 * - code 格式与 [com.stock.dividend.data.repository.StockRepository.fetchQuotes]
 *   返回的 key 一致：`sh.600036` / `sz.000001`。
 */
@Stable
@Entity(tableName = "price_cache")
data class PriceCacheEntity(
    @PrimaryKey
    val code: String,
    val price: Double,
    val updatedAt: Long = System.currentTimeMillis()
)
