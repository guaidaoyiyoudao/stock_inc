package com.stock.dividend.data.repository

/**
 * 不可变历史「按报告期」合并（纯函数）：远端同报告期覆盖缓存，缓存独有的更早期次永续保留，升序返回。
 *
 * 用于财报/基本面这类「历史期次不可变、只有新期次追加」的数据——远端拉取窗口缩短或部分接口失败时，
 * 已缓存的历史期次不随刷新丢失。
 */
internal fun <T> mergeByReportDate(
    cached: List<T>,
    remote: List<T>,
    dateOf: (T) -> String
): List<T> {
    if (cached.isEmpty()) return remote
    if (remote.isEmpty()) return cached
    val merged = LinkedHashMap<String, T>(cached.size + remote.size)
    cached.forEach { merged[dateOf(it)] = it }
    remote.forEach { merged[dateOf(it)] = it }
    return merged.values.sortedBy(dateOf)
}
