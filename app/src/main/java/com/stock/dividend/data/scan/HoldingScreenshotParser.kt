package com.stock.dividend.data.scan

/**
 * 一行 OCR 解析出的持仓数据。所有字段都可能缺失（OCR 不准），由上层 VM 校验/补全。
 */
data class ParsedHoldingRow(
    val rawText: String,
    val codeOrName: String,
    val shares: Int?,
    val costPerShare: Double?
)

/**
 * 解析同花顺持仓截图的 OCR 文本块。
 *
 * 典型格式（同花顺「我的 → 持仓」页）每行形如：
 *   `贵州茅台 600519 100 1500.00`
 *   `平安银行 000001 1,000 12.34`
 *   `600519 100股 ¥1500.00`
 *
 * 解析策略（容错、纯函数、无 Android 依赖，便于单测）：
 * 1. 按换行切行，丢弃表头/合计等无意义行（关键词或字段不足）。
 * 2. 每行抽取：连续汉字（名称）、6 位代码、整数（股数）、带小数的价格。
 * 3. 至少要有「名称或代码」+「股数」才保留；成本价缺失视为 null。
 */
object HoldingScreenshotParser {

    private val headerKeywords = listOf(
        "证券代码", "证券名称", "股票名称", "持股数", "持仓数", "持仓数量",
        "成本价", "成本", "现价", "市值", "盈亏", "浮动盈亏", "持仓市值",
        "持仓明细", "我的持仓", "持仓", "总计", "合计", "资产"
    )

    private val chineseNameRegex = Regex("[\\u4e00-\\u9fa5]{2,8}")
    private val codeRegex = Regex("(?<!\\d)\\d{6}(?!\\d)")
    private val priceRegex = Regex("\\d{1,3}(?:,\\d{3})*\\.\\d{1,3}|\\d+\\.\\d{1,3}")

    /**
     * @param fullText ML Kit 识别出的整段文本
     * @return 可识别出的持仓行（顺序保持）
     */
    fun parse(fullText: String): List<ParsedHoldingRow> {
        if (fullText.isBlank()) return emptyList()
        return fullText.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line -> parseLine(line) }
    }

    private fun parseLine(line: String): ParsedHoldingRow? {
        if (isHeaderOrNoise(line)) return null

        val name = chineseNameRegex.find(line)?.value
        val code = codeRegex.find(line)?.value

        // 代码或名称至少有其一，否则丢弃
        val codeOrName = code ?: name ?: return null

        // 股数与成本价：先剥离价格，再在剩余部分找整数股数
        val price = extractPrice(line)
        val shares = extractShares(line, exclude = price)

        // 没有股数的行无意义（无法建仓）
        if (shares == null) return null

        return ParsedHoldingRow(
            rawText = line,
            codeOrName = codeOrName,
            shares = shares,
            costPerShare = price
        )
    }

    private fun isHeaderOrNoise(line: String): Boolean {
        // 表头行
        if (headerKeywords.any { line.contains(it) }) return true
        // 纯数字/符号噪声行
        if (line.replace(Regex("[\\s\\d,.:%¥元股手+-]"), "").isEmpty() && codeRegex.containsMatchIn(line).not()) {
            // 整行只有数字符号但没有 6 位代码 → 噪声
            return true
        }
        return false
    }

    /** 找到第一个带小数点的金额数字，去除千分位逗号。 */
    private fun extractPrice(line: String): Double? {
        val match = priceRegex.find(line) ?: return null
        // 必须包含小数点，否则可能误把股数当价格
        if (!match.value.contains('.')) return null
        return match.value.replace(",", "").toDoubleOrNull()
    }

    /**
     * 在排除掉价格文本后的剩余内容里，找最后一个纯整数作为股数。
     * 去除千分位逗号和「股/手」单位。
     */
    private fun extractShares(line: String, exclude: Double?): Int? {
        var remaining = line
        if (exclude != null) {
            // 把价格原文（含可能的千分位形式）从行里删掉
            val priceMatch = priceRegex.find(line)
            if (priceMatch != null && priceMatch.value.contains('.')) {
                remaining = line.replace(priceMatch.value, " ")
            }
        }
        // 去掉 6 位代码（避免被当成股数）
        remaining = codeRegex.replace(remaining, " ")
        // 找所有整数候选（支持千分位）
        val candidates = Regex("\\d{1,3}(?:,\\d{3})+|\\d+").findAll(remaining)
            .map { it.value.replace(",", "") }
            .mapNotNull { it.toIntOrNull() }
            .toList()
        // 取最后一个候选作为股数（持仓页中股数通常在代码之后、价格之前或末尾）
        return candidates.lastOrNull { it > 0 }
    }
}
