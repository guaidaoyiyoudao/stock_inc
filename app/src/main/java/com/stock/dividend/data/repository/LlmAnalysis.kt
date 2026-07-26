package com.stock.dividend.data.repository

data class LlmAnalysis(
    val overview: String,
    val stockComments: Map<String, String>,
    val risks: List<String>,
)

sealed interface LlmAnalysisResult {
    data class Success(val analysis: LlmAnalysis) : LlmAnalysisResult
    data object NotConfigured : LlmAnalysisResult
    data class Error(val message: String) : LlmAnalysisResult
}

sealed interface LlmAnalysisState {
    data object Idle : LlmAnalysisState
    data object Loading : LlmAnalysisState
    data object NotConfigured : LlmAnalysisState
    data class Success(val analysis: LlmAnalysis) : LlmAnalysisState
    data class Error(val message: String) : LlmAnalysisState
}
