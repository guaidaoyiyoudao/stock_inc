package com.stock.dividend.data.repository

import com.google.gson.JsonParser

/**
 * 解析 LLM 返回内容为 [LlmAnalysis]（纯函数，永不抛异常）。
 * 兜底链：完整 JSON → 字段缺失补默认 → ```json 代码块提取 → 纯文本降级。
 */
object LlmAnalysisParser {

    fun parse(rawContent: String): LlmAnalysis {
        val trimmed = rawContent.trim()
        if (trimmed.isEmpty()) return LlmAnalysis("", emptyMap(), emptyList())
        val jsonStr = extractJsonObject(trimmed) ?: return LlmAnalysis(trimmed, emptyMap(), emptyList())
        return try {
            val obj = JsonParser.parseString(jsonStr).asJsonObject
            val overview = obj.get("overview")?.takeIf { !it.isJsonNull }?.asString ?: trimmed
            val comments = buildMap {
                obj.get("stockComments")?.takeIf { it.isJsonObject }?.asJsonObject?.entrySet()?.forEach { (k, v) ->
                    if (!v.isJsonNull) put(k, v.asString)
                }
            }
            val risks = buildList {
                obj.get("risks")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { v ->
                    if (!v.isJsonNull) add(v.asString)
                }
            }
            LlmAnalysis(overview, comments, risks)
        } catch (_: Exception) {
            LlmAnalysis(trimmed, emptyMap(), emptyList())
        }
    }

    private fun extractJsonObject(raw: String): String? {
        if (raw.startsWith("{")) return raw
        val fence = Regex("""```(?:json)?\s*(\{.*?})\s*```""", RegexOption.DOT_MATCHES_ALL).find(raw)
        if (fence != null) return fence.groupValues[1]
        val first = raw.indexOf('{')
        val last = raw.lastIndexOf('}')
        return if (first in 0 until last) raw.substring(first, last + 1) else null
    }
}
