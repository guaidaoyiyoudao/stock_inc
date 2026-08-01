package com.stock.dividend.data.remote.dto

import com.google.gson.annotations.SerializedName

data class QuoteResponse(
    val data: QuoteData?
)

data class QuoteData(
    val diff: List<QuoteItem>?
)

/**
 * 东方财富行情接口（push2 `api/qt/ulist.np/get`）单条行情。
 *
 * **裸值约定**（实测 2026-08 交叉验证腾讯 qt 同时刻可读值）：
 * - 价格/百分比类字段（[price]/[changePct]/[change]/[amplitude]/[turnoverRate]/[pe]/[pb]/[volumeRatio]/
 *   [high]/[low]/[open]/[prevClose]）均为**真实值 ×100** 的整数（接口省小数点以压缩传输），
 *   解析时由 [com.stock.dividend.data.repository.toQuoteSnapshot] 统一 ÷100。
 * - 绝对量类字段（[volume] 成交量「手」、[amount] 成交额「元」、[totalMarketCap] 总市值「元」、
 *   [circMarketCap] 流通市值「元」）为原值，不除。
 * - 任意字段在停牌/退市/异常时可能为 null 或 "-"，[Double] 可空兼容。
 *
 * 字段编号对应东财 fields：f2 价格 / f3 涨跌幅 / f4 涨跌额 / f5 成交量 / f6 成交额 /
 * f7 振幅 / f8 换手 / f9 PE(TTM) / f10 量比 / f12 代码 / f13 市场 / f15 最高 / f16 最低 /
 * f17 今开 / f18 昨收 / f20 总市值 / f21 流通市值 / f23 PB。
 */
data class QuoteItem(
    @SerializedName("f2")
    val price: Double?,
    @SerializedName("f3")
    val changePct: Double? = null,         // 涨跌幅 %
    @SerializedName("f4")
    val change: Double? = null,            // 涨跌额 元
    @SerializedName("f5")
    val volume: Double? = null,            // 成交量（手）
    @SerializedName("f6")
    val amount: Double? = null,            // 成交额（元）
    @SerializedName("f7")
    val amplitude: Double? = null,         // 振幅 %
    @SerializedName("f8")
    val turnoverRate: Double? = null,      // 换手率 %
    @SerializedName("f9")
    val pe: Double? = null,                // 市盈率(TTM)
    @SerializedName("f10")
    val volumeRatio: Double? = null,       // 量比
    @SerializedName("f12")
    val code: String,
    @SerializedName("f13")
    val market: Int,
    @SerializedName("f15")
    val high: Double? = null,              // 当日最高
    @SerializedName("f16")
    val low: Double? = null,               // 当日最低
    @SerializedName("f17")
    val open: Double? = null,              // 今开
    @SerializedName("f18")
    val prevClose: Double? = null,         // 昨收
    @SerializedName("f20")
    val totalMarketCap: Double? = null,    // 总市值（元）
    @SerializedName("f21")
    val circMarketCap: Double? = null,     // 流通市值（元）
    @SerializedName("f23")
    val pb: Double? = null                 // 市净率
)
