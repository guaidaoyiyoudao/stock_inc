package com.stock.dividend.data.scan

/**
 * 一行解析出的持仓数据。所有字段都可能缺失（OCR 不准），由上层 VM 校验/补全。
 */
data class ParsedHoldingRow(
    val rawText: String,
    val codeOrName: String,
    val shares: Int?,
    val costPerShare: Double?
)

/**
 * 通用持仓截图解析器：基于 OCR 元素的**视觉坐标**重建表格，而非依赖固定文本格式。
 *
 * 核心思想：不假设"哪段是表头""列的顺序是什么"，而是：
 * 1. 按 Y 坐标把元素聚类成"视觉行"（centerY 接近的元素视为同一行）。
 * 2. 每行内按 X 坐标排序，得到从左到右的列。
 * 3. 按内容特征判语义：
 *    - 含连续中文（≥2字）且不是已知表头词 → 股票名称
 *    - 纯整数（可能带千分位） → 股数
 *    - 带小数点的数字（可能带¥/千分位） → 价格
 *    - 带% → 占比（忽略）
 * 4. 同一行的名称+股数+价格直接拼成一条记录。
 *
 * 这样无论同花顺/雪球/东方财富、横屏竖屏、不同皮肤版本、只截部分列，都能按"东西在哪"
 * 解析，而非"文本长什么样"。
 *
 * 多个名称挤在同一视觉行（分块布局下常见）时，会用相邻原则把它们与各自的股数/价格配对。
 */
object HoldingScreenshotParser {

    // 已知的表头/噪声词（仅用于剔除，不用于定位）
    private val headerKeywords = setOf(
        "证券代码", "证券名称", "股票名称", "股票代码", "名称", "代码",
        "持股数", "持仓数", "持仓数量", "持仓", "可用", "余额",
        "成本", "成本价", "现价", "成本/现价", "现价/成本",
        "市值", "持仓市值", "市值令", "市偵",
        "盈亏", "浮动盈亏", "浮动", "盈亏额", "盈亏率",
        "个股仓位", "仓位", "个服仓位", "个服仓位令",
        "持股天数", "天数", "持服天数", "持股天教", "持服天教",
        "持仓明细", "我的持仓", "持仓股", "持仓/可用", "持仓/可用令", "持仓/可用+",
        "总计", "合计", "资产", "总资产", "净资产",
        "买入", "卖出", "行情", "交易", "资讯", "查询", "理财",
        "首页", "自选", "目选", "撤单", "撒单", "持仓", "首页",
        "持仓/可用"
    )

    private val chineseNameRegex = Regex("[\\u4e00-\\u9fa5A-Za-z][\\u4e00-\\u9fa5A-Za-z.·]{1,15}")
    private val codeRegex = Regex("(?<!\\d)\\d{6}(?!\\d)")
    private val percentRegex = Regex("^-?\\d+(?:\\.\\d+)?%$")
    // 纯整数（允许千分位），用于股数
    private val integerRegex = Regex("^[¥¥]?(\\d{1,3}(?:,\\d{3})+|\\d+)[股手]?$")
    // 带小数的价格
    private val decimalRegex = Regex("^[¥¥]?(\\d{1,3}(?:,\\d{3})*\\.\\d{1,3}|\\d+\\.\\d{1,3})[元股]?$")

    /**
     * 兼容入口：把纯文本（无坐标）按行解析。无坐标时退化成"逐行扫描"模式。
     * 主要供旧调用方和纯文本单测使用；坐标模式请用 [parseFromElements]。
     */
    fun parse(fullText: String): List<ParsedHoldingRow> {
        if (fullText.isBlank()) return emptyList()
        // 纯文本模式：无法聚类，按行扫描，复用语义判断
        val rows = mutableListOf<ParsedHoldingRow>()
        var pendingName: String? = null
        var pendingShares: Int? = null
        fullText.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
            val cleaned = stripUnits(line)
            when {
                isHeader(line) -> { /* skip */ }
                percentRegex.matches(cleaned) -> { /* skip 占比 */ }
                else -> {
                    val name = extractName(line)
                    val code = codeRegex.find(line)?.value
                    val shares = parseInteger(cleaned)
                    val price = parseDecimal(cleaned)
                    when {
                        // 单行同时含名称和数字（理想情况）
                        name != null && (shares != null || price != null) -> {
                            rows.add(ParsedHoldingRow(line, name, shares, price))
                        }
                        // 仅名称，暂存等后续行补数字
                        name != null && code == null && shares == null && price == null -> {
                            if (pendingName != null) {
                                rows.add(ParsedHoldingRow(pendingName!!, pendingName!!, pendingShares, null))
                            }
                            pendingName = name
                            pendingShares = null
                        }
                        // 仅数字，补给 pendingName
                        (shares != null || price != null) && pendingName != null -> {
                            if (pendingShares == null && shares != null) pendingShares = shares
                            if (price != null) {
                                rows.add(ParsedHoldingRow(pendingName!!, pendingName!!, pendingShares, price))
                                pendingName = null
                                pendingShares = null
                            }
                        }
                    }
                }
            }
        }
        if (pendingName != null) {
            rows.add(ParsedHoldingRow(pendingName!!, pendingName!!, pendingShares, null))
        }
        return rows
    }

    /**
     * 主入口：基于带坐标的 OCR 元素，按视觉位置聚类解析。
     *
     * 策略：券商持仓页通常是「名称一列、数字一列」的分块布局，名称与对应数字的 Y 高度对齐。
     * 因此按 X 聚类成列，再按 Y 在列间对齐配对，比"同行配对"稳健得多——不依赖列的顺序，
     * 也不依赖哪个词是表头。
     */
    fun parseFromElements(elements: List<OcrElement>): List<ParsedHoldingRow> {
        if (elements.isEmpty()) return emptyList()

        // 1. 过滤表头/占比/明显噪声
        val meaningful = elements.filter { el ->
            !isHeader(el.text) && !percentRegex.matches(el.text.trim())
        }
        if (meaningful.isEmpty()) return emptyList()

        // 2. 按 X 聚类成"列"
        val columns = clusterByX(meaningful)

        // 3. 找出名称列、整数列、价格列（按列内多数 token 的语义判定）
        val nameCol = pickColumn(columns) { el -> extractName(el.text) != null }
            ?: return parseBySequentialPairing(meaningful)
        val numCols = columns.filter { it !== nameCol }

        // 4. 名称列按 Y 排序
        val names = nameCol.sortedBy { it.centerY }

        // 5. 为每个名称找 Y 对齐的整数（股数）和价格（成本）
        return names.map { nameEl ->
            val name = extractName(nameEl.text) ?: nameEl.text.trim()
            val shares = findAlignedToken(nameEl, numCols) { classifyShares(it) }
            val price = findAlignedToken(nameEl, numCols) { classifyPrice(it) }
            ParsedHoldingRow(nameEl.text, name, shares, price)
        }.filter { it.codeOrName.length >= 2 }
    }

    // ---------- 列聚类 ----------

    /** 把元素按 centerX 聚类成列：相邻元素水平距离小于阈值则视为同一列。 */
    private fun clusterByX(elements: List<OcrElement>): List<List<OcrElement>> {
        if (elements.isEmpty()) return emptyList()
        val avgWidth = elements.map { it.width }.sorted()[elements.size / 2].coerceAtLeast(1f)
        val tolerance = avgWidth * 1.5f + 20f // 列间距通常比字宽大

        val sorted = elements.sortedBy { it.centerX }
        val columns = mutableListOf<MutableList<OcrElement>>()
        var currentX: Float? = null
        for (el in sorted) {
            if (currentX == null || kotlin.math.abs(el.centerX - currentX!!) > tolerance) {
                columns.add(mutableListOf(el))
                currentX = el.centerX
            } else {
                columns.last().add(el)
            }
        }
        return columns
    }

    /** 选出"谓词命中数最多"的列。 */
    private fun pickColumn(
        columns: List<List<OcrElement>>,
        predicate: (OcrElement) -> Boolean
    ): List<OcrElement>? {
        return columns.maxByOrNull { col -> col.count(predicate) }
            ?.takeIf { col -> col.count(predicate) > 0 }
    }

    /** 在候选列中，找到与目标元素 Y 对齐（高度重叠）的 token，应用转换。 */
    private inline fun <T> findAlignedToken(
        target: OcrElement,
        columns: List<List<OcrElement>>,
        transform: (OcrElement) -> T?
    ): T? {
        val tolerance = target.height * 0.8f + 10f
        for (col in columns) {
            // 找 Y 最接近的元素
            val aligned = col.minByOrNull { kotlin.math.abs(it.centerY - target.centerY) }
            if (aligned != null && kotlin.math.abs(aligned.centerY - target.centerY) <= tolerance) {
                transform(aligned)?.let { return it }
            }
        }
        return null
    }

    private fun classifyShares(el: OcrElement): Int? {
        val cleaned = stripUnits(el.text).trim()
        // 必须是纯整数，不能是小数（避免把价格当股数）
        if (cleaned.contains('.')) return null
        return parseInteger(cleaned)
    }

    private fun classifyPrice(el: OcrElement): Double? {
        val cleaned = stripUnits(el.text).trim()
        // 必须带小数点才算价格
        if (!cleaned.contains('.')) return null
        return parseDecimal(cleaned)
    }

    /**
     * 分块布局的退化策略：把所有元素按 (Y, X) 排序后顺序扫描，
     * 名称与紧跟其后的最近数字配对。这是 [parse] 文本模式的坐标版。
     */
    private fun parseBySequentialPairing(elements: List<OcrElement>): List<ParsedHoldingRow> {
        val ordered = elements.sortedWith(compareBy({ it.centerY }, { it.centerX }))
        val result = mutableListOf<ParsedHoldingRow>()
        var pendingName: String? = null
        var pendingShares: Int? = null
        for (el in ordered) {
            if (isHeader(el.text)) continue
            if (percentRegex.matches(el.text.trim())) continue
            val cleaned = stripUnits(el.text).trim()
            val name = extractName(el.text)
            val shares = parseInteger(cleaned)
            val price = parseDecimal(cleaned)
            when {
                name != null && shares == null && price == null -> {
                    if (pendingName != null) {
                        result.add(ParsedHoldingRow(pendingName!!, pendingName!!, pendingShares, null))
                    }
                    pendingName = name
                    pendingShares = null
                }
                name != null && (shares != null || price != null) -> {
                    result.add(ParsedHoldingRow(el.text, name, shares, price))
                    pendingName = null
                    pendingShares = null
                }
                (shares != null || price != null) && pendingName != null -> {
                    if (pendingShares == null && shares != null) pendingShares = shares
                    if (price != null) {
                        result.add(ParsedHoldingRow(pendingName!!, pendingName!!, pendingShares, price))
                        pendingName = null
                        pendingShares = null
                    }
                }
            }
        }
        if (pendingName != null) {
            result.add(ParsedHoldingRow(pendingName!!, pendingName!!, pendingShares, null))
        }
        return result
    }

    // ---------- 文本工具 ----------

    private fun isHeader(line: String): Boolean =
        headerKeywords.any { line.contains(it) }

    private fun stripUnits(line: String): String =
        line.replace("¥", "").replace("￥", "").replace("元", "")
            .replace("股", "").replace("手", "").trim()

    private fun extractName(line: String): String? {
        val matches = chineseNameRegex.findAll(line).toList()
        if (matches.isEmpty()) return null
        // 取最长片段，并确保不是表头
        return matches.maxByOrNull { it.value.length }?.value?.trim()
            ?.takeIf { it.length >= 2 && !headerKeywords.contains(it) }
    }

    private fun parseInteger(cleaned: String): Int? {
        if (!integerRegex.matches(cleaned)) return null
        return cleaned.replace(",", "").replace("股", "").replace("手", "")
            .replace("¥", "").replace("￥", "").toIntOrNull()?.takeIf { it > 0 }
    }

    private fun parseDecimal(cleaned: String): Double? {
        if (!decimalRegex.matches(cleaned)) return null
        return cleaned.replace(",", "").replace("元", "").replace("股", "")
            .replace("¥", "").replace("￥", "").toDoubleOrNull()
    }
}
