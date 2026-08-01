package com.stock.dividend.data.repository

/**
 * 个股 LLM 解读结果（结构化）。schema 与组合级 [LlmAnalysis] 语义不同：
 * 个股无 overview/无多股，故独立定义而非污染组合级模型。
 */
data class StockLlmAnalysis(
    /** ≤120字：结合三周期 BOLL 位置判断当前价格贵/便宜/合理。 */
    val valuation: String,
    /** ≤120字：结合分红率趋势与预测样本判断分红可持续性。 */
    val dividendSustainability: String,
    /** ≤20字：一句话定性结论（如"可逢低关注"/"暂观望"/"持有"），不给具体价。 */
    val action: String,
    /** 具体风险点。 */
    val risks: List<String>,
)

/** 个股级编排返回类型（与 [LlmAnalysisResult] 对称，Success 携带缓存元数据）。 */
sealed interface StockLlmAnalysisResult {
    data class Success(
        val analysis: StockLlmAnalysis,
        val analyzedAt: Long? = null,
        val fromCache: Boolean = false,
        val notice: String? = null,
    ) : StockLlmAnalysisResult
    data object NotConfigured : StockLlmAnalysisResult
    data class Error(val message: String) : StockLlmAnalysisResult
}

/**
 * 个股 LLM 解读的 UI 状态。结构与组合级 [LlmAnalysisState] 完全对称（五态语义一致），
 * 但 Success 的 payload 是 [StockLlmAnalysis]，故独立定义以保持类型清晰。
 */
sealed interface StockLlmAnalysisState {
    data object Idle : StockLlmAnalysisState
    data object Loading : StockLlmAnalysisState
    data object NotConfigured : StockLlmAnalysisState
    data class Success(
        val analysis: StockLlmAnalysis,
        val analyzedAt: Long? = null,
        val fromCache: Boolean = false,
        val notice: String? = null,
    ) : StockLlmAnalysisState
    data class Error(val message: String) : StockLlmAnalysisState
}
