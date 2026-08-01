package com.stock.dividend.data.agent.tools

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

/** 字符串列表参数：接受 List 或逗号/顿号/分号/空格分隔的字符串。 */
internal fun Map<String, Any?>.stringListArg(key: String): List<String> =
    when (val value = this[key]) {
        is List<*> -> value.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
        is String -> value.split(Regex("[,，、;；\\s]+")).map { it.trim() }.filter { it.isNotEmpty() }
        else -> emptyList()
    }
