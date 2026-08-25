package com.stock.dividend.data.remote.dto

import com.google.gson.annotations.SerializedName

/**
 * 东方财富 push2 `api/qt/clist/get`（板块列表 / 行业内个股 / 资金流向）通用响应壳。
 *
 * **裸值单位约定（实测 2026-08，汾酒 600809 / 茅台 600519 交叉验证腾讯 qt）**：
 * clist 接口（`fltt=2`）返回的**全部字段均为真实值，不需 ÷100**——
 * 价格类 f2（如 127.24 元）、百分比类 f3/f8/f9/f23/f69/f75/f81/f87/f184（如 -1.67 表示 -1.67%）
 * 都是真实小数；金额类 f6/f20/f21/f62/f66/f72/f78/f84 为「元」原值。
 * ⚠️ 这与 [QuoteResponse]（push2 ulist）的「价格百分比 ÷100」规则**不同**——ulist 省小数点传 ×100 整数，
 *   clist 传真实值。两者解析逻辑独立，切勿混用。
 *
 * 字段值 "-" 表示停牌/退市/无效（2026-08-20 审计实测：退市股全字段 "-"）——**默认 Gson 会抛
 * NumberFormatException 导致整批解析失败**，NetworkModule 已为东财系 Retrofit 注册容错
 * Double 反序列化（"-"→null），单条记录降级而非整批丢弃。
 *
 * 字段含义（东财 f 序列，资金流字段编号经官方文档确认；仅保留实际请求/解析的字段）：
 * 行情：f2 现价 / f3 涨跌幅% / f8 换手率% / f9 PE(TTM) / f12 代码 / f14 名称 /
 *   f20 总市值元 / f23 PB / f100 所属行业 / f128 领涨股名 / f140 领涨股代码 / f136 领涨股涨跌幅%。
 *   f133 股息率%（2026-08-15 实测交叉验证：汇洁股份 f133=14.61，与「近 12 月每股分红 1.10 元 ÷
 *   现价 7.53 元 ≈ 14.6%」吻合；全市场 fs 按 f133 降序返回即为股息率榜）。
 * 资金流净额（元）：f62 主力 / f66 超大单 / f72 大单 / f78 中单 / f84 小单。
 * 资金流净占比（%）：f184 主力 / f69 超大单 / f75 大单 / f81 中单 / f87 小单。
 */
data class MarketClistResponse(
    val data: MarketClistData?
) {
    data class MarketClistData(
        val diff: List<MarketClistItem>? = null,
        val total: Int? = null
    )

    data class MarketClistItem(
        @SerializedName("f2")
        val price: Double? = null,
        @SerializedName("f3")
        val changePct: Double? = null,
        @SerializedName("f8")
        val turnoverRate: Double? = null,
        @SerializedName("f9")
        val pe: Double? = null,
        @SerializedName("f12")
        val code: String? = null,
        @SerializedName("f14")
        val name: String? = null,
        @SerializedName("f20")
        val totalMarketCap: Double? = null,
        @SerializedName("f23")
        val pb: Double? = null,
        @SerializedName("f133")
        val dividendYield: Double? = null,
        @SerializedName("f62")
        val mainNetInflow: Double? = null,
        @SerializedName("f184")
        val mainNetInflowPct: Double? = null,
        @SerializedName("f66")
        val superLargeNetInflow: Double? = null,
        @SerializedName("f69")
        val superLargeNetInflowPct: Double? = null,
        @SerializedName("f72")
        val largeNetInflow: Double? = null,
        @SerializedName("f75")
        val largeNetInflowPct: Double? = null,
        @SerializedName("f78")
        val mediumNetInflow: Double? = null,
        @SerializedName("f81")
        val mediumNetInflowPct: Double? = null,
        @SerializedName("f84")
        val smallNetInflow: Double? = null,
        @SerializedName("f87")
        val smallNetInflowPct: Double? = null,
        @SerializedName("f100")
        val industry: String? = null,
        @SerializedName("f128")
        val leaderName: String? = null,
        @SerializedName("f140")
        val leaderCode: String? = null,
        @SerializedName("f136")
        val leaderChangePct: Double? = null
    )
}

/**
 * 东方财富 push2 `api/qt/ulist.np/get` 按 secid 精确拉取的**个股资金流响应**（2026-08-24 接入）。
 *
 * 实测口径（2026-08-24 push2delay 与 push2 同族同字段语义，中国移动 600941）：
 * - `fltt=2` 时**全部真实值**：净额 f62/f66/f72/f78/f84 为「元」原值，占比 f184/f69/f75/f81/f87
 *   为 % 原值（如 f69=8.62 表示 8.62%）——与 clist(fltt=2) 同口径，无任何 ÷100/÷1000；
 * - ⚠️ 不带 fltt 时占比类为 ×100 整数（f69=862），故本接口固定 fltt=2，勿删；
 * - 恒等式自检：f62 主力净额 = f66 超大单 + f72 大单（实测 122411585 = 107338497 + 15073088 精确成立）。
 */
data class CapitalFlowResponse(
    val data: CapitalFlowData?
) {
    data class CapitalFlowData(
        val diff: List<CapitalFlowItem>? = null
    )

    data class CapitalFlowItem(
        @SerializedName("f12")
        val code: String? = null,
        @SerializedName("f62")
        val mainNetInflow: Double? = null,
        @SerializedName("f184")
        val mainNetInflowPct: Double? = null,
        @SerializedName("f66")
        val superLargeNetInflow: Double? = null,
        @SerializedName("f69")
        val superLargeNetInflowPct: Double? = null,
        @SerializedName("f72")
        val largeNetInflow: Double? = null,
        @SerializedName("f75")
        val largeNetInflowPct: Double? = null,
        @SerializedName("f78")
        val mediumNetInflow: Double? = null,
        @SerializedName("f81")
        val mediumNetInflowPct: Double? = null,
        @SerializedName("f84")
        val smallNetInflow: Double? = null,
        @SerializedName("f87")
        val smallNetInflowPct: Double? = null
    )
}

/**
 * 东方财富 push2 `api/qt/stock/get` 单标的详情响应壳（用于指数 / ETF 行情）。
 *
 * ⚠️ **与 [MarketClistResponse] 单位规则不同**：stock/get 的价格/百分比类字段（f43/f44/f45/f46/f60/f170）
 * 为**真实值 ×100 的整数，解析时必须 ÷100**（实测上证指数 f43=383226 → 3832.26 点）；f48 成交额元
 * 为原值不除。返回 `data: { 字段... }`（单对象，无 diff 数组）。
 */
data class IndexQuoteResponse(
    val data: IndexQuoteData?
) {
    data class IndexQuoteData(
        @SerializedName("f43")
        val price: Double? = null,
        @SerializedName("f44")
        val high: Double? = null,
        @SerializedName("f45")
        val low: Double? = null,
        @SerializedName("f46")
        val open: Double? = null,
        @SerializedName("f48")
        val amount: Double? = null,
        @SerializedName("f57")
        val code: String? = null,
        @SerializedName("f58")
        val name: String? = null,
        @SerializedName("f60")
        val prevClose: Double? = null,
        @SerializedName("f170")
        val changePct: Double? = null
    )
}
