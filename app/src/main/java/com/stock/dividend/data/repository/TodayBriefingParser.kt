package com.stock.dividend.data.repository

/** 今日简报 LLM 响应解析（纯函数，容错）。 */
object TodayBriefingParser {

    /** 从 LLM 响应中提取一句话简报。兜底链：JSON.briefing → 围栏 JSON → 原文去引号。 */
    fun parse(raw: String): String {
        val json = JsonExtraction.extractJsonObject(raw)
        if (json != null) {
            val match = Regex(""""briefing"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(json)
            if (match != null) {
                return match.groupValues[1].replace("\\\"", "\"").trim().take(80)
            }
        }
        return raw.trim().trim('"').trim().take(80)
    }
}
