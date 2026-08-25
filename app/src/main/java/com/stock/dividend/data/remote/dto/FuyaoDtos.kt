package com.stock.dividend.data.remote.dto

import com.google.gson.annotations.SerializedName
import java.time.Instant
import java.time.ZoneId

/**
 * 同花顺扶摇 API 原始 DTO 与纯映射工具。
 *
 * 单位纪律（§4.9，实测 2026-08-23，腾讯 qt 交叉验证）：
 * - **全部真实值**：价格元、百分比 %（如 1.74 = +1.74%）、金额元——无东财 ÷100/÷1000 规则；
 * - 成交量单位**股**（App 内 `QuoteSnapshot.volume` 语义为手，换算 ÷100 在解析函数做并配 fixture 锁定）；
 * - 时间戳毫秒 Unix，时区 Asia/Shanghai（[FUYAO_ZONE]）；
 * - 业务错误也走 HTTP 200：信封 [FuyaoEnvelope.code] != 0 即失败（3001/1002=标的不存在或类型不符、
 *   5003=数据未就绪、2001/2003=认证/权限、4001=频率超限）。
 */
// ── 统一信封 ──────────────────────────────────────────────

/** 扶摇统一响应信封。`code==0` 成功；非 0 时 [data] 为 null（业务失败，调用方降级候补源）。 */
data class FuyaoEnvelope<T>(
    val code: Int? = null,
    val message: String? = null,
    @SerializedName("request_id") val requestId: String? = null,
    val data: T? = null
) {
    val isOk: Boolean get() = code == 0
}

// ── 行情快照（A股 / 指数 / 场内基金共用 item 形状）──────────

data class FuyaoSnapshotData(
    val timestamp: Long? = null,
    val total: Int? = null,
    val item: List<FuyaoPriceItem>? = null
)

data class FuyaoPriceItem(
    @SerializedName("thscode") val thscode: String? = null,
    @SerializedName("ticker") val ticker: String? = null,
    @SerializedName("last_price") val lastPrice: Double? = null,
    @SerializedName("price_change") val priceChange: Double? = null,
    @SerializedName("price_change_ratio_pct") val changePct: Double? = null,
    /** 振幅 %（仅基金快照返回，A 股快照无此字段）。 */
    @SerializedName("price_amplitude_ratio_pct") val amplitudePct: Double? = null,
    @SerializedName("open_price") val open: Double? = null,
    @SerializedName("high_price") val high: Double? = null,
    @SerializedName("low_price") val low: Double? = null,
    @SerializedName("prev_price") val prevClose: Double? = null,
    /** 成交量（股）。 */
    @SerializedName("volume") val volumeShares: Double? = null,
    /** 成交额（元）。 */
    @SerializedName("turnover") val turnover: Double? = null,
    /** 换手率 %（仅基金快照返回）。 */
    @SerializedName("turnover_ratio_pct") val turnoverRatePct: Double? = null
)

// ── 日K ──────────────────────────────────────────────────

data class FuyaoHistoricalData(
    val timestamp: Long? = null,
    val item: List<FuyaoBarItem>? = null
)

data class FuyaoBarItem(
    @SerializedName("date_ms") val dateMs: Long? = null,
    @SerializedName("open_price") val open: Double? = null,
    @SerializedName("close_price") val close: Double? = null,
    @SerializedName("high_price") val high: Double? = null,
    @SerializedName("low_price") val low: Double? = null,
    /** 成交量（**股**）——与腾讯 K 线的手口径不同，换算 ÷100 在 KlineRepository 解析处做（审计 M2）。 */
    @SerializedName("volume") val volume: Double? = null,
    @SerializedName("turnover") val turnover: Double? = null
)

// ── 股票分红事件流（已除权）──────────────────────────────

data class FuyaoAdjustmentFactorsData(
    val thscode: String? = null,
    val ticker: String? = null,
    val item: List<FuyaoAdjustmentItem>? = null
)

data class FuyaoAdjustmentItem(
    /** 除权除息日（Asia/Shanghai 零点毫秒）。 */
    @SerializedName("ex_date_ms") val exDateMs: Long? = null,
    /** 每股现金分红（税前，元）——**已是每股口径，无每10股换算**。 */
    @SerializedName("dividend_per_share") val dividendPerShare: Double? = null,
    /** 每股送转比例：DividendRepository 落库 bonusPerShare（纯送转行现金 0）。 */
    @SerializedName("per_share_bonus") val perShareBonus: Double? = null
)

// ── 场内基金分红记录 ─────────────────────────────────────

data class FuyaoFundDividendsData(
    val timestamp: Long? = null,
    @SerializedName("dividend_count") val dividendCount: Int? = null,
    @SerializedName("dividend_total") val dividendTotal: Double? = null,
    val item: List<FuyaoFundDividendItem>? = null
)

data class FuyaoFundDividendItem(
    /** 每 10 份税前现金（元）——÷10 合规换算在解析函数做。 */
    @SerializedName("per_ten_cash_before_tax") val perTenCashBeforeTax: Double? = null,
    @SerializedName("per_ten_cash_after_tax") val perTenCashAfterTax: Double? = null,
    /** 分红进度（实测值为 "2" 等编码，不依赖该字段过滤，按除息日是否已定判断）。 */
    val progress: String? = null,
    @SerializedName("publish_date_ms") val publishDateMs: Long? = null,
    @SerializedName("registration_date_ms") val registrationDateMs: Long? = null,
    @SerializedName("ex_dividend_date_ms") val exDividendDateMs: Long? = null,
    @SerializedName("payment_date_ms") val paymentDateMs: Long? = null,
    @SerializedName("reinvestment_date_ms") val reinvestmentDateMs: Long? = null
)

// ── 标的检索 ─────────────────────────────────────────────

data class FuyaoTickerSearchData(
    val timestamp: Long? = null,
    val item: List<FuyaoTickerItem>? = null
)

data class FuyaoTickerItem(
    @SerializedName("thscode") val thscode: String? = null,
    @SerializedName("ticker") val ticker: String? = null,
    val name: String? = null,
    /** SH / SZ / BJ（场外基金为 null）。 */
    val exchange: String? = null,
    @SerializedName("asset_type") val assetType: String? = null,
    val currency: String? = null
)

// ── 财务三表（period=quarterly 序列含 Q4 累计口径）────────

data class FuyaoStatementsData<T>(
    val timestamp: Long? = null,
    val item: List<T>? = null
)

data class FuyaoIncomeItem(
    @SerializedName("period_end_ms") val periodEndMs: Long? = null,
    @SerializedName("report_date_ms") val reportDateMs: Long? = null,
    @SerializedName("fiscal_year") val fiscalYear: Int? = null,
    @SerializedName("fiscal_period") val fiscalPeriod: String? = null,
    /** 营业收入（元）——扶摇无「营业总收入」口径（审计 M1），映射产物 totalOperateIncome 恒 null、由东财并行回填。 */
    @SerializedName("operating_income") val operatingIncome: Double? = null,
    /** 营业成本（元）。 */
    @SerializedName("operating_costs") val operatingCosts: Double? = null,
    /** 销售费用（元）。 */
    @SerializedName("sales_fee") val salesFee: Double? = null,
    /** 管理费用（元）。 */
    @SerializedName("manage_fee") val manageFee: Double? = null,
    @SerializedName("research_and_development_expenses") val rdExpense: Double? = null,
    /** 营业利润（元）。 */
    @SerializedName("operating_profit") val operatingProfit: Double? = null,
    @SerializedName("interest_expenses") val interestExpenses: Double? = null,
    /** 利润总额（元）。 */
    @SerializedName("profit_total") val profitTotal: Double? = null,
    /** 所得税（元）。 */
    @SerializedName("income_tax_expense") val incomeTaxExpense: Double? = null,
    @SerializedName("net_profit") val netProfit: Double? = null,
    /** 归母净利润（元）。 */
    @SerializedName("parent_holder_net_profit") val parentNetProfit: Double? = null,
    /** 基本每股收益（元）。 */
    @SerializedName("basic_eps") val basicEps: Double? = null
)

data class FuyaoBalanceSheetItem(
    @SerializedName("period_end_ms") val periodEndMs: Long? = null,
    @SerializedName("report_date_ms") val reportDateMs: Long? = null,
    @SerializedName("fiscal_year") val fiscalYear: Int? = null,
    @SerializedName("fiscal_period") val fiscalPeriod: String? = null,
    /** 资产总计（元）。 */
    @SerializedName("assets_total") val assetsTotal: Double? = null,
    /** 货币资金（元）。 */
    @SerializedName("cash") val cash: Double? = null,
    /** 应收账款（元）。 */
    @SerializedName("accounts_receivable") val accountsReceivable: Double? = null,
    /** 负债合计（元）。 */
    @SerializedName("total_debt") val totalDebt: Double? = null,
    /** 所有者权益合计（元）。 */
    @SerializedName("holder_equity_total") val holderEquityTotal: Double? = null
)

data class FuyaoCashFlowItem(
    @SerializedName("period_end_ms") val periodEndMs: Long? = null,
    @SerializedName("report_date_ms") val reportDateMs: Long? = null,
    @SerializedName("fiscal_year") val fiscalYear: Int? = null,
    @SerializedName("fiscal_period") val fiscalPeriod: String? = null,
    /** 经营活动现金流净额（元）。 */
    @SerializedName("act_cash_flow_net") val operateNetCash: Double? = null,
    /** 投资活动现金流净额（元）。 */
    @SerializedName("invest_cash_flow_net") val investNetCash: Double? = null,
    /** 筹资活动现金流净额（元）。 */
    @SerializedName("financing_cash_flow_net") val financeNetCash: Double? = null,
    /** 现金及等价物净增加额（元，非期末余额——期末余额 App 由东财并行补）。 */
    @SerializedName("cash_equivalents_net_addition") val cashNetAddition: Double? = null
)

// ── 财务指标（单报告期）─────────────────────────────────

data class FuyaoIndicatorsData(
    val thscode: String? = null,
    val report: String? = null,
    val abilities: List<FuyaoAbility>? = null
)

data class FuyaoAbility(
    val ability: String? = null,
    val indicators: List<FuyaoIndicator>? = null
)

data class FuyaoIndicator(
    @SerializedName("index_id") val indexId: String? = null,
    /** 实测为字符串数值（"36.0200"），非 JSON number。 */
    val value: String? = null
)

/** 按指标 id 取值（跨五个能力组查找；缺失/非数值 → null）。 */
fun FuyaoIndicatorsData?.indicatorValueOf(indexId: String): Double? =
    this?.abilities?.asSequence()
        ?.mapNotNull { it.indicators }
        ?.flatten()
        ?.firstOrNull { it.indexId == indexId }
        ?.value?.toDoubleOrNull()

/** 财务指标 index_id（实测 2026-08-23，与东财 RPT_LICO_FN_CPD 对应字段口径核对）。 */
object FuyaoIndicatorIds {
    /** 加权平均净资产收益率 %（≈东财 WEIGHTAVG_ROE；茅台 2024 年报 36.02 与公开值一致）。 */
    const val WEIGHTED_ROE = "index_weighted_avg_roe"

    /** 资产负债率 %（≈东财 DEBT_ASSET_RATIO；茅台 2024 年报 19.04 与公开值一致）。 */
    const val ASSETS_DEBT_RATIO = "assets_debt_ratio"

    /** 营业总收入同比 %（≈东财 YSTZ）。 */
    const val REVENUE_YOY = "calculate_operating_income_yoy_growth_ratio"

    /** 归母净利润同比 %（≈东财 SJLTZ）。 */
    const val PARENT_NET_PROFIT_YOY = "calculate_parent_holder_net_profit_yoy_growth_ratio"
}

// ── 纯映射工具 ───────────────────────────────────────────

/** 扶摇时间戳统一时区。 */
internal val FUYAO_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

/** 毫秒时间戳 → `yyyy-MM-dd`（Asia/Shanghai 日历日；null/非法 → null）。 */
internal fun Long?.fuyaoMsToDateStringOrNull(): String? =
    this?.let {
        runCatching {
            Instant.ofEpochMilli(it).atZone(FUYAO_ZONE).toLocalDate().toString()
        }.getOrNull()
    }

/** App 代码（`sh.600519`/`sz.000001`）→ 扶摇 thscode（`600519.SH`）。北交所等不支持的返回 null。 */
internal fun String.toFuyaoThscodeOrNull(): String? {
    val prefix = substringBefore(".", "").lowercase()
    val bare = substringAfter(".", "")
    if (bare.length != 6) return null
    return when (prefix) {
        "sh" -> "$bare.SH"
        "sz" -> "$bare.SZ"
        else -> null
    }
}

/** 扶摇 thscode（`600519.SH`）→ App 代码（`sh.600519`）。BJ/场外（null 后缀）返回 null。 */
internal fun String.fuyaoThscodeToAppCodeOrNull(): String? {
    val bare = substringBefore(".")
    val exchange = substringAfter(".", "").uppercase()
    if (bare.length != 6) return null
    return when (exchange) {
        "SH" -> "sh.$bare"
        "SZ" -> "sz.$bare"
        else -> null
    }
}

/** 报告期期末日期（`2024-12-31`）→ 扶摇财务指标 report 参数（`2024-4` = 年-季）。非季末返回 null。 */
internal fun String.toFuyaoReportParamOrNull(): String? {
    val parts = split("-")
    if (parts.size != 3) return null
    val year = parts[0].toIntOrNull() ?: return null
    val quarter = when (parts[1].toIntOrNull()) {
        3 -> 1; 6 -> 2; 9 -> 3; 12 -> 4
        else -> return null
    }
    return "$year-$quarter"
}

// ════════════════════════════════════════════════════════════
// 以下为「数据平面全量接入」（2026-08-23）新增 DTO。
// 命名/单位纪律同文件头；**龙虎榜例外**：`change`/`net_rate` 为小数分数
// （-0.10022 = -10.022%，实测 2026-08-23），是全 API 唯一非「百分比原值」的
// 比率字段，域转换 ×100 在 MarketDataRepository 并配 fixture 锁定。
// 未 typed 的端点（连板天梯/异动/竞价/经理等）以 `FuyaoEnvelope<JsonObject>`
// 原始透传——字段直接面向 Agent/后续 UI 消费，无单位换算需求。
// ════════════════════════════════════════════════════════════

// ── 估值快照 ─────────────────────────────────────────────

data class FuyaoValuationData(
    val timestamp: Long? = null,
    val total: Int? = null,
    val item: List<FuyaoValuationItem>? = null
)

data class FuyaoValuationItem(
    @SerializedName("thscode") val thscode: String? = null,
    @SerializedName("ticker") val ticker: String? = null,
    val name: String? = null,
    @SerializedName("pe_ttm") val peTtm: Double? = null,
    @SerializedName("pe_mrq") val peMrq: Double? = null,
    @SerializedName("pb_mrq") val pbMrq: Double? = null,
    @SerializedName("ps_ttm") val psTtm: Double? = null,
    @SerializedName("pcf_ttm") val pcfTtm: Double? = null
)

// ── 交易日历 ─────────────────────────────────────────────

data class FuyaoTradingDaysData(
    val timestamp: Long? = null,
    val item: List<FuyaoTradingDayItem>? = null
)

data class FuyaoTradingDayItem(
    @SerializedName("date_ms") val dateMs: Long? = null,
    /** `yyyyMMdd` 格式可读日期。 */
    val date: String? = null
)

// ── 龙虎榜（change/net_rate 为小数分数，×100 域转换见 MarketDataRepository）──

data class FuyaoDragonTigerData(
    val timestamp: Long? = null,
    @SerializedName("board_type") val boardType: String? = null,
    @SerializedName("trade_date") val tradeDate: String? = null,
    val count: Int? = null,
    @SerializedName("stock_count") val stockCount: Int? = null,
    @SerializedName("stock_items") val stockItems: List<FuyaoDragonTigerItem>? = null
)

data class FuyaoDragonTigerItem(
    @SerializedName("thscode") val thscode: String? = null,
    @SerializedName("ticker") val ticker: String? = null,
    val name: String? = null,
    @SerializedName("concept_list") val conceptList: List<FuyaoConceptRef>? = null,
    /** 涨跌幅（**小数分数**：-0.10022 = -10.022%）。 */
    val change: Double? = null,
    /** 龙虎榜净买入额（元）。 */
    @SerializedName("net_value") val netValue: Double? = null,
    /** 净买入占比（**小数分数**：0.0405 = 4.05%）。 */
    @SerializedName("net_rate") val netRate: Double? = null,
    @SerializedName("hot_rank") val hotRank: Int? = null,
    @SerializedName("buy_value") val buyValue: Double? = null,
    @SerializedName("sell_value") val sellValue: Double? = null,
    @SerializedName("limit_reason") val limitReason: String? = null,
    @SerializedName("range_days") val rangeDays: Int? = null,
    @SerializedName("hot_money_net_value") val hotMoneyNetValue: Double? = null
)

data class FuyaoConceptRef(val name: String? = null)

// ── 涨跌停/炸板股票池 ─────────────────────────────────────

data class FuyaoLimitPoolData(
    val timestamp: Long? = null,
    val pagination: FuyaoPagination? = null,
    val item: List<FuyaoLimitPoolItem>? = null
)

data class FuyaoPagination(
    val total: Int? = null,
    val pages: Int? = null,
    val size: Int? = null,
    val page: Int? = null
)

/**
 * 涨停池条目；跌停池/炸板池复用（各自特有字段缺失为 null，公共字段同名）。
 * `price_change_ratio_pct` 为百分比原值（已 ×100，文档明示）。
 */
data class FuyaoLimitPoolItem(
    @SerializedName("thscode") val thscode: String? = null,
    @SerializedName("ticker") val ticker: String? = null,
    val name: String? = null,
    @SerializedName("is_st") val isSt: Boolean? = null,
    @SerializedName("is_new") val isNew: Boolean? = null,
    @SerializedName("last_price") val lastPrice: Double? = null,
    @SerializedName("price_change_ratio_pct") val changePct: Double? = null,
    @SerializedName("limit_up_time") val limitUpTime: String? = null,
    @SerializedName("limit_up_reason") val limitUpReason: String? = null,
    @SerializedName("continue_day_text") val continueDayText: String? = null,
    @SerializedName("continue_day_cnt") val continueDayCnt: Int? = null,
    /** 封单额（元）。 */
    @SerializedName("seal_money") val sealMoney: Double? = null,
    @SerializedName("max_seal_money") val maxSealMoney: Double? = null
)

// ── 热股榜（飙升榜/热股榜/历史热股共用条目形态）──────────

data class FuyaoHotStockData(
    val timestamp: Long? = null,
    val item: List<FuyaoHotStockItem>? = null
)

data class FuyaoHotStockItem(
    @SerializedName("thscode") val thscode: String? = null,
    @SerializedName("ticker") val ticker: String? = null,
    val name: String? = null,
    val rank: Int? = null,
    /** 热度值（实测为字符串数值）。 */
    val heat: Double? = null,
    @SerializedName("rank_change") val rankChange: Int? = null,
    @SerializedName("rank_trend") val rankTrend: String? = null
)

// ── 同花顺指数目录 / 指数成分 ────────────────────────────

data class FuyaoThsIndexListData(
    val timestamp: Long? = null,
    val item: List<FuyaoThsIndexItem>? = null
)

data class FuyaoThsIndexItem(
    @SerializedName("thscode") val thscode: String? = null,
    val name: String? = null
)

data class FuyaoConstituentsData(
    val timestamp: Long? = null,
    val item: List<FuyaoTickerItem>? = null
)

// ── 基金域（fund_type=exchange 覆盖 ETF/LOF）──────────────

data class FuyaoFundProfileData(
    val timestamp: Long? = null,
    val item: List<FuyaoFundProfileItem>? = null
)

data class FuyaoFundProfileItem(
    @SerializedName("thscode") val thscode: String? = null,
    @SerializedName("ticker") val ticker: String? = null,
    @SerializedName("fund_name") val fundName: String? = null,
    @SerializedName("estab_date") val estabDateMs: Long? = null,
    @SerializedName("company_id") val companyId: String? = null,
    @SerializedName("mgmt_name") val mgmtName: String? = null,
    @SerializedName("manager_name") val managerName: String? = null,
    /** 基金规模（元）。 */
    @SerializedName("fund_scale") val fundScale: Double? = null,
    @SerializedName("unit_nav") val unitNav: Double? = null,
    @SerializedName("manager_info") val managerInfo: List<FuyaoManagerRef>? = null,
    @SerializedName("rate_info") val rateInfo: List<FuyaoRateInfo>? = null
)

/** 现任经理任职信息（红利ETF 实测含任职回报/天数）。 */
data class FuyaoManagerRef(
    @SerializedName("manager_id") val managerId: String? = null,
    @SerializedName("manager_name") val managerName: String? = null,
    /** 任职回报 %。 */
    @SerializedName("tenure_return_pct") val tenureReturnPct: Double? = null,
    @SerializedName("tenure_days") val tenureDays: Int? = null,
    @SerializedName("start_date_ms") val startDateMs: Long? = null
)

/** 费率条目（standard_rate 实测为 "0.50%" 字符串）。 */
data class FuyaoRateInfo(
    @SerializedName("rate_type") val rateType: String? = null,
    @SerializedName("charge_mode") val chargeMode: String? = null,
    @SerializedName("standard_rate") val standardRate: String? = null
)

/** 重仓持仓（定期披露，非实时）。data 级汇总字段 + 明细列表。 */
data class FuyaoFundHoldingsData(
    val timestamp: Long? = null,
    @SerializedName("total_stock_ratio_pct") val totalStockRatioPct: Double? = null,
    @SerializedName("stock_ratio_pct") val stockRatioPct: Double? = null,
    @SerializedName("main_industry") val mainIndustry: String? = null,
    /** 集中度（前十占比，实测为小数分数：0.3135 = 31.35%）。 */
    @SerializedName("concentration_ratio") val concentrationRatio: Double? = null,
    val item: List<FuyaoFundHoldingItem>? = null
)

data class FuyaoFundHoldingItem(
    @SerializedName("thscode") val thscode: String? = null,
    @SerializedName("ticker") val ticker: String? = null,
    @SerializedName("stock_name") val stockName: String? = null,
    /** 占净值比 %。 */
    @SerializedName("hold_ratio") val holdRatio: Double? = null,
    @SerializedName("asset_type") val assetType: String? = null,
    @SerializedName("position_capital") val positionCapital: Double? = null,
    @SerializedName("position_count") val positionCount: Double? = null,
    @SerializedName("security_market_value_rate_pct") val marketValueRatePct: Double? = null,
    /** 报告期增减比例（实测小数分数：-0.04 = -4%）。 */
    @SerializedName("period_increase_rate_pct") val periodIncreasePct: Double? = null,
    @SerializedName("investment_rank") val investmentRank: Int? = null,
    @SerializedName("start_date_ms") val startDateMs: Long? = null,
    @SerializedName("end_date_ms") val endDateMs: Long? = null
)

data class FuyaoFundIndustryData(
    val timestamp: Long? = null,
    val item: List<FuyaoFundIndustryItem>? = null
)

data class FuyaoFundIndustryItem(
    @SerializedName("report_period") val reportPeriod: String? = null,
    @SerializedName("industry_name") val industryName: String? = null,
    /** 配置比例 %。 */
    @SerializedName("ratio_pct") val ratioPct: Double? = null
)

data class FuyaoAssetAllocationData(
    val timestamp: Long? = null,
    val item: List<FuyaoAssetAllocationItem>? = null
)

data class FuyaoAssetAllocationItem(
    @SerializedName("report_date_ms") val reportDateMs: Long? = null,
    @SerializedName("stock_ratio_pct") val stockRatioPct: Double? = null,
    @SerializedName("bond_ratio_pct") val bondRatioPct: Double? = null,
    @SerializedName("deposit_ratio_pct") val depositRatioPct: Double? = null,
    @SerializedName("other_ratio_pct") val otherRatioPct: Double? = null
)

/** 多周期最大回撤矩阵（全为负数百分数原值；ETF 实测可能全 0=无数据）。 */
data class FuyaoFundDrawdownsData(
    val timestamp: Long? = null,
    val item: List<FuyaoFundDrawdownItem>? = null
)

data class FuyaoFundDrawdownItem(
    @SerializedName("thscode") val thscode: String? = null,
    val week: Double? = null,
    val month: Double? = null,
    @SerializedName("tmonth") val threeMonth: Double? = null,
    @SerializedName("hyear") val halfYear: Double? = null,
    val year: Double? = null,
    val twoyear: Double? = null,
    @SerializedName("tyear") val threeYear: Double? = null,
    @SerializedName("fyear") val fiveYear: Double? = null,
    @SerializedName("nowyear") val ytd: Double? = null,
    val now: Double? = null
)

/** 区间收益（含同类平均 peer_average_*）。 */
data class FuyaoFundReturnsData(
    val timestamp: Long? = null,
    val item: List<FuyaoFundReturnItem>? = null
)

data class FuyaoFundReturnItem(
    @SerializedName("return_week") val week: Double? = null,
    @SerializedName("return_month") val month: Double? = null,
    @SerializedName("return_tmonth") val threeMonth: Double? = null,
    @SerializedName("return_hyear") val halfYear: Double? = null,
    @SerializedName("return_year") val year: Double? = null,
    @SerializedName("return_twoyear") val twoYear: Double? = null,
    @SerializedName("return_tyear") val threeYear: Double? = null,
    @SerializedName("return_fyear") val fiveYear: Double? = null,
    @SerializedName("return_nowyear") val ytd: Double? = null,
    /** 成立以来 %。 */
    @SerializedName("return_now") val sinceInception: Double? = null,
    @SerializedName("peer_average_week") val peerWeek: Double? = null,
    @SerializedName("peer_average_month") val peerMonth: Double? = null,
    @SerializedName("peer_average_year") val peerYear: Double? = null
)

data class FuyaoFundNavData(
    val timestamp: Long? = null,
    val item: List<FuyaoFundNavItem>? = null
)

data class FuyaoFundNavItem(
    @SerializedName("nav_date") val navDateMs: Long? = null,
    @SerializedName("unit_nav") val unitNav: Double? = null,
    @SerializedName("adj_nav") val adjNav: Double? = null
)

/** 持仓披露报告期（股票/债券持仓历史共用形态）。 */
data class FuyaoFundReportDatesData(
    val timestamp: Long? = null,
    val item: List<FuyaoFundReportDateItem>? = null
)

data class FuyaoFundReportDateItem(
    @SerializedName("report_type") val reportType: String? = null,
    @SerializedName("report_type_name") val reportTypeName: String? = null,
    @SerializedName("start_date_ms") val startDateMs: Long? = null,
    @SerializedName("end_date_ms") val endDateMs: Long? = null
)

data class FuyaoFundHoldersData(
    val timestamp: Long? = null,
    val item: List<FuyaoFundHolderItem>? = null
)

data class FuyaoFundHolderItem(
    @SerializedName("merge_scope") val mergeScope: String? = null,
    @SerializedName("report_date_ms") val reportDateMs: Long? = null,
    /** 机构持仓占比 %。 */
    @SerializedName("ins_position") val institutionPct: Double? = null,
    @SerializedName("holder_amount") val holderAmount: Double? = null,
    @SerializedName("avg_holder_share") val avgHolderShare: Double? = null,
    /** 个人占比 %。 */
    @SerializedName("psnl_rate") val personalPct: Double? = null,
    @SerializedName("mgmt_staff_hold_rate") val mgmtStaffHoldPct: Double? = null
)
