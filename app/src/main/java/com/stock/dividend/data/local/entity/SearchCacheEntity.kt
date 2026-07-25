package com.stock.dividend.data.local.entity

import androidx.compose.runtime.Stable
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 搜索结果缓存：同一关键词（queryKey）命中时直接返回缓存，不再请求网络。
 *
 * - 以 code 作主键（REPLACE 更新股票最新名称/市场）；按 queryKey 索引复用。
 * - queryKey 为用户输入的小写归一形式，避免大小写差异导致重复请求。
 * - 派生缓存，可在 BackupRepository 之外独立重建，故不纳入备份。
 */
@Stable
@Entity(tableName = "search_cache", indices = [Index("queryKey")])
data class SearchCacheEntity(
    @PrimaryKey
    val code: String,
    val queryKey: String,
    val name: String,
    val marketCode: String,
    val updatedAt: Long = System.currentTimeMillis()
)
