package com.stock.dividend.data.repository

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.stock.dividend.data.scan.ParsedHoldingRow
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 视觉模型解析出的一行交易记录（历史成交）。字段可能缺失，由上层 VM 校验/补全。
 *
 * @property codeOrName 6 位代码优先，缺失时用名称（与 [ParsedHoldingRow] 同口径，走 resolveStock）。
 * @property type 归一化为 "BUY"/"SELL"；无法判定（如银证转账等非交易行）为 null，交由用户在预览里选。
 * @property date 已归一化为 yyyy-MM-dd；无法判定为 null。
 */
data class ParsedTransactionRow(
    val codeOrName: String,
    val name: String? = null,
    val type: String?,
    val shares: Int?,
    val price: Double?,
    val date: String?
)

sealed interface VisionImportParseResult {
    data class Holdings(val rows: List<ParsedHoldingRow>) : VisionImportParseResult
    data class Transactions(val rows: List<ParsedTransactionRow>) : VisionImportParseResult
    data object Empty : VisionImportParseResult
    data object Invalid : VisionImportParseResult
}

/**
 * 解析视觉模型（GLM-4.6V）响应为持仓/交易行（纯函数，永不抛异常）。
 *
 * 兜底链：空→Invalid；JsonExtraction 提取→gson 解析；rows 缺失/非数组→Invalid；
 * 逐行安全读取（字段缺失/类型不符→null），code 与 name 全空的行丢弃；结果为空列表→Empty。
 * screenshotType 缺失时按行内键名推断（含 type/date→交易；其余→持仓）。
 */
object VisionImportParser {

    fun parse(rawContent: String): VisionImportParseResult {
        if (rawContent.isBlank()) return VisionImportParseResult.Invalid
        val jsonStr = JsonExtraction.extractJsonObject(rawContent) ?: return VisionImportParseResult.Invalid
        return runCatching {
            val obj = JsonParser.parseString(jsonStr).asJsonObject
            val rows = obj.takeIf { it.has("rows") && it.get("rows").isJsonArray }
                ?.get("rows")?.asJsonArray ?: return VisionImportParseResult.Invalid
            val isTransactions = when (obj.safeStr("screenshotType").uppercase()) {
                "TRANSACTIONS" -> true
                "HOLDINGS" -> false
                else -> inferType(rows)
            }
            val parsed = rows.mapNotNull { el ->
                runCatching { el.asJsonObject }.getOrNull()
                    ?.let { if (isTransactions) it.toTransactionRow() else it.toHoldingRow() }
            }
            when {
                parsed.isEmpty() -> VisionImportParseResult.Empty
                isTransactions -> VisionImportParseResult.Transactions(parsed.filterIsInstance<ParsedTransactionRow>())
                else -> VisionImportParseResult.Holdings(parsed.filterIsInstance<ParsedHoldingRow>())
            }
        }.getOrElse { VisionImportParseResult.Invalid }
    }

    /** 无 screenshotType 时按首行键名推断：交易行有 type/date，持仓行有 costPerShare/cost。 */
    private fun inferType(rows: JsonArray): Boolean {
        val first = rows.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject ?: return false
        return first.has("type") || first.has("date")
    }

    private fun JsonObject.toHoldingRow(): ParsedHoldingRow? {
        val code = safeCode()
        val name = safeStr("name").ifBlank { null }
        val codeOrName = code.ifBlank { name ?: return null }
        return ParsedHoldingRow(
            rawText = "",
            codeOrName = codeOrName,
            shares = safeInt("shares"),
            costPerShare = safeDouble("costPerShare") ?: safeDouble("cost"),
            name = name
        )
    }

    private fun JsonObject.toTransactionRow(): ParsedTransactionRow? {
        val code = safeCode()
        val name = safeStr("name").ifBlank { null }
        val codeOrName = code.ifBlank { name ?: return null }
        return ParsedTransactionRow(
            codeOrName = codeOrName,
            name = name,
            type = normalizeType(safeStr("type")),
            shares = safeInt("shares"),
            price = safeDouble("price"),
            date = normalizeDate(safeStr("date"))
        )
    }

    /** 买卖方向归一化；非交易行（银证转账/利息归本等）返回 null 交用户在预览里选。 */
    internal fun normalizeType(raw: String): String? {
        val s = raw.trim()
        return when (s.uppercase()) {
            "BUY", "B" -> "BUY"
            "SELL", "S" -> "SELL"
            else -> when (s) {
                "买入", "证券买入", "买", "担保买入" -> "BUY"
                "卖出", "证券卖出", "卖", "担保卖出" -> "SELL"
                else -> null
            }
        }
    }

    /**
     * 日期归一化为 yyyy-MM-dd（解析失败返回 null）。
     * 支持：2026-08-01 / 2026/8/1 / 2026.8.1 / 2026年8月1日 / 20260801 / 0801（补当年）。
     */
    internal fun normalizeDate(raw: String): String? {
        val s = raw.trim().replace("年", "-").replace("月", "-").replace("日", "")
        return runCatching {
            val date = when {
                Regex("""^\d{8}$""").matches(s) ->
                    LocalDate.parse(s, DateTimeFormatter.ofPattern("yyyyMMdd"))
                Regex("""^\d{4}$""").matches(s) ->
                    LocalDate.of(LocalDate.now().year, s.take(2).toInt(), s.takeLast(2).toInt())
                else -> {
                    val parts = s.split("-", "/", ".").mapNotNull { it.takeIf(String::isNotBlank) }
                    when (parts.size) {
                        3 -> LocalDate.of(parts[0].toInt(), parts[1].toInt(), parts[2].toInt())
                        2 -> LocalDate.of(LocalDate.now().year, parts[0].toInt(), parts[1].toInt())
                        else -> return null
                    }
                }
            }
            date.toString()
        }.getOrNull()
    }

    private fun JsonObject.safeStr(key: String): String =
        runCatching {
            if (has(key) && get(key).isJsonPrimitive) get(key).asString.trim() else ""
        }.getOrDefault("")

    /** 代码字段：数字字面量按整型格式化（避免 600519 被读成 "600519.0"）。 */
    private fun JsonObject.safeCode(): String {
        val el = takeIf { has("code") && !get("code").isJsonNull }?.get("code") ?: return ""
        return runCatching {
            if (el.isJsonPrimitive && el.asJsonPrimitive.isNumber) el.asLong.toString() else el.asString.trim()
        }.getOrDefault("")
    }

    /** 整数读取：兼容 number / "100" / "1,500" / 100.0（模型输出类型不稳定）。 */
    private fun JsonObject.safeInt(key: String): Int? {
        val el = takeIf { has(key) && get(key).isJsonPrimitive }?.get(key) ?: return null
        return runCatching {
            if (el.asJsonPrimitive.isNumber) el.asDouble.toInt()
            else el.asString.replace(",", "").trim().toDouble().toInt()
        }.getOrNull()
    }

    /** 小数读取：兼容 number / "15.20" / "1,500.00" / "¥15.2"。 */
    private fun JsonObject.safeDouble(key: String): Double? {
        val el = takeIf { has(key) && get(key).isJsonPrimitive }?.get(key) ?: return null
        return runCatching {
            if (el.asJsonPrimitive.isNumber) el.asDouble
            else el.asString.replace(",", "").replace("¥", "").trim().toDoubleOrNull()
        }.getOrNull()
    }
}
