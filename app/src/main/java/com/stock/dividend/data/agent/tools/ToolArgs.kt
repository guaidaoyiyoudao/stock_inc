package com.stock.dividend.data.agent.tools

import com.stock.dividend.data.local.entity.StockEntity
import com.stock.dividend.data.repository.StockRepository
import com.stock.dividend.data.repository.StockSearchResult

/** 从工具参数 Map 取必填字符串，空白视为缺失。 */
internal fun Map<String, Any?>.stringArg(key: String): String? =
    this[key]?.toString()?.trim()?.takeIf { it.isNotBlank() }

/** 整数参数：接受 Number 或数字字符串（模型常把数字传成字符串）。 */
internal fun Map<String, Any?>.intArg(key: String): Int? =
    when (val value = this[key]) {
        is Number -> value.toInt()
        is String -> value.trim().toIntOrNull()
        else -> null
    }

/** 小数参数：接受 Number 或数字字符串。 */
internal fun Map<String, Any?>.doubleArg(key: String): Double? =
    when (val value = this[key]) {
        is Number -> value.toDouble()
        is String -> value.trim().toDoubleOrNull()
        else -> null
    }

/** 布尔参数：接受 Boolean 或 "true"/"false" 字符串。 */
internal fun Map<String, Any?>.boolArg(key: String): Boolean? =
    when (val value = this[key]) {
        is Boolean -> value
        is String -> value.trim().toBooleanStrictOrNull()
        else -> null
    }

/** 字符串列表参数：接受 List 或逗号/顿号/分号/空格分隔的字符串。 */
internal fun Map<String, Any?>.stringListArg(key: String): List<String> =
    when (val value = this[key]) {
        is List<*> -> value.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
        is String -> value.split(Regex("[,，、;；\\s]+")).map { it.trim() }.filter { it.isNotEmpty() }
        else -> emptyList()
    }

/** 现价：先网络刷新，失败回退缓存（多个工具共用）。 */
internal suspend fun StockRepository.refreshPrice(entity: StockEntity): Double? =
    runCatching { fetchQuotes(listOf(entity))[entity.code] }.getOrNull()
        ?: runCatching { getCachedPrices(listOf(entity.code))[entity.code] }.getOrNull()

/** 搜索结果 → 轻量 StockEntity（仅 code/name/marketCode，供行情查询用，多个工具共用）。 */
internal fun StockSearchResult.toEntity(): StockEntity =
    StockEntity(code = code, name = name, marketCode = marketCode)
