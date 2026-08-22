package com.stock.dividend.data.repository

import com.stock.dividend.data.local.entity.DividendEntity

/**
 * 场内基金（ETF/LOF）识别与分红解析（纯函数，无 Android 依赖，§4.4 惯例）。
 *
 * 数据源实测口径（2026-08-22）：
 * - 搜索接口场内基金 `Classify="Fund"` 且 `MktNum` 为 "1"(沪)/"0"(深)——与 A 股同市场规则；
 *   场外基金为 `Classify="OTCFUND"`（MktNum="150"），不可行情交易、不应加自选。
 * - 腾讯 fqkline 第 7 元素分红仅覆盖股票（ETF 640 行实测 0 条分红行）、东财
 *   RPT_SHAREBONUS_DET 对 ETF 返回空——ETF 分红唯一来源是基金 f10「分红送配」页
 *   `fundf10.eastmoney.com/fhsp_{code}.html`（服务端渲染 HTML 表，class='cfxq'）。
 */
object FundDividendParser {

    /** cfxq 分红表（页面同时还有阶段涨幅等其他表，必须先圈定这张） */
    private val CFXQ_TABLE = Regex("class=['\"][^'\"]*cfxq[^'\"]*['\"]", RegexOption.DOT_MATCHES_ALL)
    private val TABLE_END = "</table>"
    private val ROW = Regex("<tr[^>]*>(.*?)</tr>", RegexOption.DOT_MATCHES_ALL)
    private val CELL = Regex("<td[^>]*>(.*?)</td>", RegexOption.DOT_MATCHES_ALL)

    /** 首列年份，如 `2026年` */
    private val YEAR_CELL = Regex("^(\\d{4})年?$")

    /** 分红方式列：`每10份派现金1.4300元`（ETF 分红均为现金；送份额等其他方式跳过） */
    private val CASH_PER_10 = Regex("每10份派现金([0-9.]+)元")

    private val DATE = Regex("\\d{4}-\\d{2}-\\d{2}")

    /**
     * 是否场内基金（ETF/LOF，可像股票一样行情交易）。
     * 沪市 `5` 开头（50x LOF/封基、51x/56x/58x ETF——沪市 5 开头无股票）、
     * 深市 `15`(ETF)/`16`(LOF) 开头。
     */
    fun isExchangeTradedFund(stockCode: String): Boolean {
        val prefix = stockCode.substringBefore(".", missingDelimiterValue = " ").lowercase()
        val code = stockCode.substringAfter(".", missingDelimiterValue = "")
        if (!Regex("\\d{6}").matches(code)) return false
        return when (prefix) {
            "sh" -> code.startsWith("5")
            "sz" -> code.startsWith("15") || code.startsWith("16")
            else -> false
        }
    }

    /**
     * 裸 6 位代码判场内基金（ETF/LOF）：`5` 开头（沪基金，两市无 5 开头股票）、
     * `15`/`16` 开头（深基金，两市无冲突号段）。供行情裸值解析选除数用（×1000 vs ×100）。
     */
    fun isExchangeTradedFundCode(bareCode: String): Boolean {
        if (!Regex("\\d{6}").matches(bareCode)) return false
        return bareCode.startsWith("5") || bareCode.startsWith("15") || bareCode.startsWith("16")
    }

    /**
     * 解析基金 f10 分红送配 HTML → [DividendEntity] 列表。
     *
     * 表列：年份 | 权益登记日 | 除息日 | 每10份分红 | 分红发放日。
     * 单位换算仅「每10份→每份」÷10（宪法原则 III 允许项）；「暂无分红」/结构不符/非现金
     * 分红行一律跳过，绝不臆造。id 采用腾讯方案的 `${stockCode}_${除息日}`。
     */
    fun parseDividendHtml(html: String, stockCode: String): List<DividendEntity> {
        val tableMatch = CFXQ_TABLE.find(html) ?: return emptyList()
        val table = html.substring(tableMatch.range.last + 1, html.indexOf(TABLE_END, tableMatch.range.last).takeIf { it >= 0 } ?: return emptyList())

        return ROW.findAll(table)
            .mapNotNull { row ->
                val cells = CELL.findAll(row.groupValues[1]).map { it.groupValues[1].trim() }.toList()
                val year = cells.getOrNull(0)?.let { YEAR_CELL.matchEntire(it)?.groupValues?.get(1) } ?: return@mapNotNull null
                val cashPer10 = cells.getOrNull(3)?.let { CASH_PER_10.find(it)?.groupValues?.get(1)?.toDoubleOrNull() }
                    ?.let { it / 10.0 } ?: return@mapNotNull null
                if (cashPer10 <= 0.0) return@mapNotNull null
                val exDate = cells.getOrNull(2)?.takeIf { DATE.matches(it) } ?: return@mapNotNull null
                DividendEntity(
                    id = "${stockCode}_$exDate",
                    stockCode = stockCode,
                    reportDate = "$year-12-31",
                    cashPerShare = cashPer10,
                    dividendYield = null, // f10 无历史股息率快照，历史曲线该点留空
                    exDividendDate = exDate,
                    recordDate = cells.getOrNull(1)?.takeIf { DATE.matches(it) },
                    planNoticeDate = null,
                    planStatus = null
                )
            }
            .distinctBy { it.id }
            .toList()
    }
}
