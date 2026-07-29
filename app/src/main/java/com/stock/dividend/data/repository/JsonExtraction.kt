package com.stock.dividend.data.repository

/**
 * 从可能含前后文 / ```json 围栏 / 裸 JSON 对象的文本中提取首个 JSON 对象字符串（纯函数）。
 * 抽出供 [LlmAnalysisParser] 与 [StockLlmAnalysisParser] 共用，避免重复逻辑。
 */
object JsonExtraction {

    /**
     * 提取首个 JSON 对象字符串；无则返回 null。
     * 兜底链：以 `{` 开头 → 原样 → ``` ```json 围栏 ``` → 取捕获组 → 首个 `{` 到末个 `}` 子串 → null。
     */
    fun extractJsonObject(raw: String): String? {
        if (raw.startsWith("{")) return raw
        val fence = Regex("""```(?:json)?\s*(\{.*?})\s*```""", RegexOption.DOT_MATCHES_ALL).find(raw)
        if (fence != null) return fence.groupValues[1]
        val first = raw.indexOf('{')
        val last = raw.lastIndexOf('}')
        return if (first in 0 until last) raw.substring(first, last + 1) else null
    }
}
