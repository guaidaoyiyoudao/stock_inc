package com.stock.dividend.data.repository

/** 组合级每股解读（升级版：brief ≤60 字 + 该股风险点）。 */
data class StockLlmComment(
    val brief: String,
    val risks: List<String>,
)

data class LlmAnalysis(
    val overview: String,
    val stockComments: Map<String, StockLlmComment>,
    val risks: List<String>,
)

sealed interface LlmAnalysisResult {
    data class Success(
        val analysis: LlmAnalysis,
        /** epoch ms；null=旧路径未携带。 */
        val analyzedAt: Long? = null,
        val fromCache: Boolean = false,
        /** 如「刷新失败，显示上次分析结果」。 */
        val notice: String? = null,
    ) : LlmAnalysisResult
    data object NotConfigured : LlmAnalysisResult
    data class Error(val message: String) : LlmAnalysisResult
}

sealed interface LlmAnalysisState {
    data object Idle : LlmAnalysisState
    data object Loading : LlmAnalysisState
    data object NotConfigured : LlmAnalysisState
    data class Success(
        val analysis: LlmAnalysis,
        val analyzedAt: Long? = null,
        val fromCache: Boolean = false,
        val notice: String? = null,
    ) : LlmAnalysisState
    data class Error(val message: String) : LlmAnalysisState
}
