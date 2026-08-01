package com.stock.dividend.data.repository

/** LLM 从截图文本提取的半结构化策略（纯数据，无 Android 依赖）。 */
data class ScreenshotStrategy(
    val targetText: String,
    val direction: StrategyDirection,
    val reasoning: String,
    val risks: List<String>,
    val validUntil: String?
) {
    enum class StrategyDirection { BUY, SELL, WATCH }
}

/**
 * 截图策略分析的 UI/编排状态：五态 + NoStrategy（泛化截图特有，无策略可提取时）。
 */
sealed interface ScreenshotStrategyState {
    data object Idle : ScreenshotStrategyState
    data object Loading : ScreenshotStrategyState
    data object NotConfigured : ScreenshotStrategyState
    data class Success(val strategy: ScreenshotStrategy) : ScreenshotStrategyState
    data class NoStrategy(val message: String) : ScreenshotStrategyState
    data class Error(val message: String) : ScreenshotStrategyState
}
