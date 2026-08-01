package com.stock.dividend.data.repository

import com.stock.dividend.data.remote.dto.QuoteItem

/**
 * 单股实时行情快照（已转单位：价格元、百分比 %、量纲见各字段注释）。
 *
 * 由 [QuoteItem]（裸值）经 [toQuoteSnapshot] 解析得到。所有字段可空——停牌/退市/异常时部分字段缺失，
 * 上游（UI/评估/LLM）必须按可空处理，缺失即不展示/不参与计算（红线 #2：绝不让 UI 崩）。
 *
 * @property stockCode App 内统一格式：`sh.600036` / `sz.000001`。
 * @property price      最新价（元）。
 * @property changePct  涨跌幅（%）。
 * @property change     涨跌额（元）。
 * @property open       今开（元）。
 * @property prevClose  昨收（元）。
 * @property high       当日最高（元）。
 * @property low        当日最低（元）。
 * @property volume     成交量（手）。
 * @property amount     成交额（元）。
 * @property amplitude  振幅（%）。
 * @property turnoverRate 换手率（%）。
 * @property volumeRatio  量比。
 * @property pe         市盈率 TTM。
 * @property pb         市净率。
 * @property totalMarketCap 总市值（元）。
 * @property circMarketCap  流通市值（元）。
 */
data class QuoteSnapshot(
    val stockCode: String,
    val price: Double?,
    val changePct: Double? = null,
    val change: Double? = null,
    val open: Double? = null,
    val prevClose: Double? = null,
    val high: Double? = null,
    val low: Double? = null,
    val volume: Double? = null,
    val amount: Double? = null,
    val amplitude: Double? = null,
    val turnoverRate: Double? = null,
    val volumeRatio: Double? = null,
    val pe: Double? = null,
    val pb: Double? = null,
    val totalMarketCap: Double? = null,
    val circMarketCap: Double? = null
)

/**
 * 把 [QuoteItem]（东方财富裸值）转成应用内单位（[QuoteSnapshot]）。纯函数，无 Android 依赖。
 *
 * 裸值 ÷100 规则（实测 2026-08，招行 600036 交叉验证腾讯 qt 同时刻可读值）：
 * - 价格/百分比类（f2/f3/f4/f7/f8/f9/f10/f15/f16/f17/f18/f23）接口省小数点传整数，需 ÷100；
 * - 绝对量类（f5 成交量手、f6 成交额元、f20 总市值元、f21 流通市值元）原值不除。
 *
 * 缺失/异常值（null、非有限、"-"/"")一律降为 null，绝不臆造（宪法原则 III：不换算原始数据，
 * 仅做单位换算与格式化）。
 *
 * @param item 接口裸值
 * @return 解析后的快照；[QuoteItem.price] 无效（null/≤0/非有限）时仍返回（price=null），
 *         调用方按可空处理
 */
fun toQuoteSnapshot(item: QuoteItem): QuoteSnapshot {
    val prefix = if (item.market == 1) "sh" else "sz"
    val stockCode = "$prefix.${item.code}"
    return QuoteSnapshot(
        stockCode = stockCode,
        price = item.price?.div100OrNull(),
        changePct = item.changePct?.div100OrNull(),
        change = item.change?.div100OrNull(),
        open = item.open?.div100OrNull(),
        prevClose = item.prevClose?.div100OrNull(),
        high = item.high?.div100OrNull(),
        low = item.low?.div100OrNull(),
        volume = item.volume?.takeIf { it.isFinite() },
        amount = item.amount?.takeIf { it.isFinite() },
        amplitude = item.amplitude?.div100OrNull(),
        turnoverRate = item.turnoverRate?.div100OrNull(),
        volumeRatio = item.volumeRatio?.div100OrNull(),
        pe = item.pe?.div100OrNull(),
        pb = item.pb?.div100OrNull(),
        totalMarketCap = item.totalMarketCap?.takeIf { it.isFinite() },
        circMarketCap = item.circMarketCap?.takeIf { it.isFinite() }
    )
}

/** 裸值 ÷100；非有限值（NaN/Infinity）降为 null，避免污染下游计算。 */
private fun Double.div100OrNull(): Double? = takeIf { it.isFinite() }?.div(100.0)
