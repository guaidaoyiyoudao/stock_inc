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
 * 裸值除数规则（实测 2026-08-22，腾讯 qt 同时刻交叉验证；股票 600519/000001、基金 510880/159915/518880）：
 * - **价格类**（f2/f4/f15/f16/f17/f18）：股票 ×100 整数 ÷100；**场内基金（ETF/LOF）报价 3 位小数，
 *   裸值为 ×1000**（510880 f2=3387 → 3.387 元）须 ÷1000——同一接口同一字段，除数随标的类型变；
 * - 百分比类（f3/f7/f8/f9/f10/f23）两类标的均 ×100 ÷100（基金 f3=137 → 1.37%）；
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
    // 价格类字段除数：场内基金 ×1000、其余 ×100（见函数头注释）
    val priceScale: (Double?) -> Double? = { raw ->
        raw.divPriceScaleOrNull(isFund = FundDividendParser.isExchangeTradedFundCode(item.code))
    }
    return QuoteSnapshot(
        stockCode = stockCode,
        price = priceScale(item.price),
        changePct = item.changePct?.div100OrNull(),
        change = priceScale(item.change),
        open = priceScale(item.open),
        prevClose = priceScale(item.prevClose),
        high = priceScale(item.high),
        low = priceScale(item.low),
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

/** 裸值 ÷100；非有限值（NaN/Infinity）降为 null，避免污染下游计算。internal 供同包解析点共用（§4.9.5-2 单点封装）。 */
internal fun Double.div100OrNull(): Double? = takeIf { it.isFinite() }?.div(100.0)

/**
 * 价格类裸值按标的类型选除数：场内基金（ETF/LOF）÷1000、其余 ÷100。
 * 接收者可空（clist/ulist 字段可空，§4.9.5-2 可空接收者约定）；非有限值降 null。
 */
internal fun Double?.divPriceScaleOrNull(isFund: Boolean): Double? =
    takeIf { it != null && it.isFinite() }?.div(if (isFund) 1000.0 else 100.0)
