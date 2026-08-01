package com.stock.dividend.data.repository

import com.google.gson.JsonParser
import com.stock.dividend.data.repository.ScreenshotStrategy.StrategyDirection

sealed interface ScreenshotStrategyParseResult {
    data class Actionable(val strategy: ScreenshotStrategy) : ScreenshotStrategyParseResult
    data object NotActionable : ScreenshotStrategyParseResult
    data class Failed(val rawText: String) : ScreenshotStrategyParseResult
}

/**
 * 解析 LLM 响应为截图策略（纯函数，永不抛异常）。
 * 兜底链：空→Failed；JsonExtraction 提取→gson 解析；isActionable=false→NotActionable；
 * direction 非法→WATCH；任一异常→Failed(原文)。
 */
object ScreenshotStrategyParser {

    fun parse(rawContent: String): ScreenshotStrategyParseResult {
        if (rawContent.isBlank()) return ScreenshotStrategyParseResult.Failed("")
        val jsonStr = JsonExtraction.extractJsonObject(rawContent) ?: return failed(rawContent)
        return runCatching {
            val obj = JsonParser.parseString(jsonStr).asJsonObject
            if (obj.has("isActionable") && !obj.get("isActionable").asBoolean) {
                ScreenshotStrategyParseResult.NotActionable
            } else {
                ScreenshotStrategyParseResult.Actionable(
                    ScreenshotStrategy(
                        targetText = obj.safeStr("targetText"),
                        direction = parseDirection(obj.safeStr("direction")),
                        reasoning = obj.safeStr("reasoning"),
                        risks = obj.takeIf { it.has("risks") && it.get("risks").isJsonArray }
                            ?.get("risks")?.asJsonArray
                            ?.mapNotNull { runCatching { it.asString }.getOrNull() }
                            ?: emptyList(),
                        validUntil = obj.takeIf { it.has("validUntil") && !it.get("validUntil").isJsonNull }
                            ?.get("validUntil")?.asString
                    )
                )
            }
        }.getOrElse { failed(rawContent) }
    }

    private fun parseDirection(s: String): StrategyDirection = when (s.uppercase()) {
        "BUY" -> StrategyDirection.BUY
        "SELL" -> StrategyDirection.SELL
        "WATCH" -> StrategyDirection.WATCH
        else -> StrategyDirection.WATCH
    }

    private fun failed(raw: String) = ScreenshotStrategyParseResult.Failed(raw)

    private fun com.google.gson.JsonObject.safeStr(key: String): String =
        runCatching { if (has(key) && !get(key).isJsonNull) get(key).asString else "" }.getOrDefault("")
}
