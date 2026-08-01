package com.stock.dividend.data.repository

/**
 * 个股 LLM 解读的输入数据快照（纯数据，无 Android 依赖，便于单测构造）。
 * 所有可空字段在数据缺失时由 [StockLlmPromptBuilder] 渲染为"—"。
 */
data class StockLlmInput(
    val code: String,
    val name: String,
    val industry: String?,
    val currentPrice: Double?,
    /** 近年分红率%序列（升序）；可空表示无数据。 */
    val dividendRatePoints: List<Double>?,
    /** 最新一期股息率%。 */
    val latestDividendYield: Double?,
    val forecast: StockLlmForecast?,
    val buyThreshold: StockLlmBuyThreshold?,
    val bollDaily: StockLlmBollPosition?,
    val bollWeekly: StockLlmBollPosition?,
    val bollMonthly: StockLlmBollPosition?,
    /** 近 N 期基本面（ROE/负债率/营收净利同比/派息率）；缺失为 null。 */
    val fundamentals: Fundamentals?,
    /** 市盈率(TTM)；来自实时行情，缺失为 null。 */
    val pe: Double? = null,
    /** 市净率；来自实时行情，缺失为 null。 */
    val pb: Double? = null,
    /** 总市值（元，原值不除）；来自实时行情，缺失为 null。 */
    val totalMarketCap: Double? = null,
) {
    /** 1/3/5 年预测：年均每股派息 + 实际样本年数（年数越少越不可靠）。 */
    data class StockLlmForecast(
        val avgCashPerShare1Y: Double,
        val avgCashPerShare3Y: Double,
        val avgCashPerShare5Y: Double,
        val actualYears: Int,
    )

    /** 买入线：目标股息率% + 现状股息率% + 是否达标（reached=null 表示现价缺失无法判定）。 */
    data class StockLlmBuyThreshold(
        val targetYieldPercent: Double,
        val currentYieldPercent: Double?,
        val reached: Boolean?,
    )

    /** 单周期 BOLL 价格位置：0=在下轨（便宜），100=在上轨（贵），已 clamp 到 0..100。 */
    data class StockLlmBollPosition(
        val priceVsLowerPercent: Int,
    )
}
