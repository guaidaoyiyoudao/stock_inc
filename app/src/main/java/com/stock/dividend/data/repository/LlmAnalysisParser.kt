package com.stock.dividend.data.repository

import com.google.gson.JsonParser

/**
 * 解析 LLM 返回内容为 [LlmAnalysis]（纯函数，永不抛异常）。
 * 兜底链：完整 JSON → 字段缺失补默认 → ```json 代码块提取 → 纯文本降级。
 * stockComments 兼容两种形态：新对象 {"brief","risks"} 与旧字符串（→brief，risks 空）。
 */
object LlmAnalysisParser {

    fun parse(rawContent: String): LlmAnalysis {
        val trimmed = rawContent.trim()
        if (trimmed.isEmpty()) return LlmAnalysis("", emptyMap(), emptyList())
        val jsonStr = JsonExtraction.extractJsonObject(trimmed) ?: return LlmAnalysis(trimmed, emptyMap(), emptyList())
        return try {
            val obj = JsonParser.parseString(jsonStr).asJsonObject
            val overview = obj.get("overview")?.takeIf { !it.isJsonNull }?.asString ?: trimmed
            val comments = buildMap {
                obj.get("stockComments")?.takeIf { it.isJsonObject }?.asJsonObject?.entrySet()?.forEach { (k, v) ->
                    if (!v.isJsonNull) {
                        val comment = when {
                            v.isJsonObject -> {
                                val c = v.asJsonObject
                                StockLlmComment(
                                    brief = c.get("brief")?.takeIf { !it.isJsonNull }?.asString ?: "",
                                    risks = buildList {
                                        c.get("risks")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { r ->
                                            if (!r.isJsonNull) add(r.asString)
                                        }
                                    }
                                )
                            }
                            v.isJsonPrimitive && v.asJsonPrimitive.isString ->
                                StockLlmComment(brief = v.asString, risks = emptyList())
                            else -> null
                        }
                        if (comment != null) put(k, comment)
                    }
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
}
