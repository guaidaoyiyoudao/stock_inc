package com.stock.dividend.data.repository

/**
 * 组合级 LLM 解读的完整输入快照（纯数据，无 Android 依赖，便于单测构造）。
 * 包含既有评估参数 + 每股深度数据（基本面/1-3-5年预测/买入线）。
 */
data class PortfolioLlmInput(
    val evaluation: List<EvaluatedStock>,
    val dailyBands: Map<String, BollBand?>,
    val monthlyBands: Map<String, BollBand?>,
    val signals: PortfolioSignals,
    val thresholds: DividendThresholds,
    val userStrategies: List<UserStrategyRef> = emptyList(),
    /** 每股深度数据；缺失的股票无 key（prompt 渲染 "—"）。 */
    val stockDetails: Map<String, PortfolioLlmStockDetail> = emptyMap(),
)

/** 单股深度数据：只放组合级缺的三项；位置/股息率/action 已在 [EvaluatedStock] + bands 中。 */
data class PortfolioLlmStockDetail(
    val fundamentals: Fundamentals? = null,
    val forecast: StockLlmInput.StockLlmForecast? = null,
    val buyThreshold: StockLlmInput.StockLlmBuyThreshold? = null,
)
