package com.stock.dividend.data.agent.tools

import com.stock.dividend.data.local.entity.StockEntity
import com.stock.dividend.data.plane.MarketDataPlane
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

/** 现价：数据平面行情（任何获取都写透 price_cache），无有效价回退缓存价（多个工具共用）。 */
internal suspend fun MarketDataPlane.refreshPrice(entity: StockEntity): Double? =
    runCatching { getQuoteSnapshots(listOf(entity))[entity.code]?.price?.takeIf { it > 0.0 } }.getOrNull()
        ?: runCatching { cachedPrices(listOf(entity.code))[entity.code] }.getOrNull()

/**
 * 组合现价：批量平面行情（单次 ulist 请求 + 会话去重），失败或为空回退缓存（多个组合工具共用）。
 * 保证与 get_stock_info 等实时工具在同一会话内现价口径一致。
 */
internal suspend fun MarketDataPlane.fetchFreshPrices(stocks: List<StockEntity>): Map<String, Double> =
    runCatching { getPrices(stocks) }.getOrNull()?.takeIf { it.isNotEmpty() }
        ?: runCatching { cachedPrices(stocks.map { it.code }) }.getOrNull()
        ?: emptyMap()

/** 搜索结果 → 轻量 StockEntity（仅 code/name/marketCode，供行情查询用，多个工具共用）。 */
internal fun StockSearchResult.toEntity(): StockEntity =
    StockEntity(code = code, name = name, marketCode = marketCode)
