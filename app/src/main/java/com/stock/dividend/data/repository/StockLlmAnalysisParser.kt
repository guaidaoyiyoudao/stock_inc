package com.stock.dividend.data.repository

import com.google.gson.JsonParser

/**
 * 解析 LLM 返回内容为 [StockLlmAnalysis]（纯函数，永不抛异常）。
 * 兜底链：空 → 全字段空 → [JsonExtraction] 提取 → gson 解析四字段（缺字段补默认）→
 * 异常 → 整段文本塞 valuation，其余空。
 */
object StockLlmAnalysisParser {

    fun parse(rawContent: String): StockLlmAnalysis {
        val trimmed = rawContent.trim()
        if (trimmed.isEmpty()) return StockLlmAnalysis("", "", "", emptyList())
        val jsonStr = JsonExtraction.extractJsonObject(trimmed)
            ?: return StockLlmAnalysis(trimmed, "", "", emptyList())
        return try {
            val obj = JsonParser.parseString(jsonStr).asJsonObject
            val valuation = obj.get("valuation")?.takeIf { !it.isJsonNull }?.asString ?: ""
            val sustainability = obj.get("dividendSustainability")?.takeIf { !it.isJsonNull }?.asString ?: ""
            val action = obj.get("action")?.takeIf { !it.isJsonNull }?.asString ?: ""
            val risks = buildList {
                obj.get("risks")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { v ->
                    if (!v.isJsonNull) add(v.asString)
                }
            }
            StockLlmAnalysis(valuation, sustainability, action, risks)
        } catch (_: Exception) {
            StockLlmAnalysis(trimmed, "", "", emptyList())
        }
    }
}
