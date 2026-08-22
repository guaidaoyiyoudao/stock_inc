package com.stock.dividend.data.repository

/**
 * 不可变历史「按报告期」合并（纯函数）：远端同报告期覆盖缓存，缓存独有的更早期次永续保留，升序返回。
 *
 * 用于财报/基本面这类「历史期次不可变、只有新期次追加」的数据——远端拉取窗口缩短或部分接口失败时，
 * 已缓存的历史期次不随刷新丢失。
 *
 * @param repairRemote 可选：远端记录覆盖缓存**同期**记录前，先经此函数修复。典型用途——远端某子接口
 *        失败（runCatching 降级空表）时同期记录的部分字段为 null，用缓存已有值回补，防止「字段级回退」
 *        （整期覆盖会把缓存里原本齐全的字段抹掉，且随后被持久化无法自愈）。
 */
internal fun <T> mergeByReportDate(
    cached: List<T>,
    remote: List<T>,
    dateOf: (T) -> String,
    repairRemote: ((remote: T, cached: T) -> T)? = null
): List<T> {
    if (cached.isEmpty()) return remote
    if (remote.isEmpty()) return cached
    val merged = LinkedHashMap<String, T>(cached.size + remote.size)
    cached.forEach { merged[dateOf(it)] = it }
    remote.forEach { r ->
        val key = dateOf(r)
        val repaired = repairRemote?.let { repair -> merged[key]?.let { c -> repair(r, c) } } ?: r
        merged[key] = repaired
    }
    return merged.values.sortedBy(dateOf)
}
