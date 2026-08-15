package com.stock.dividend.data.repository

/**
 * 当日市场情绪快照（纯数据）：领涨/领跌板块两端榜单。
 */
data class MarketMood(
    val topGainers: List<MarketListItem> = emptyList(),
    val topLosers: List<MarketListItem> = emptyList(),
)

/**
 * 市场情绪分组纯函数（无 Android 依赖，配 [MarketMoodCalculatorTest]）。
 *
 * 口径与 Agent 工具 `get_market_sentiment` 一致：一次 clist（CHANGE 排序，多取 30 条），
 * 本地按涨跌幅降序后取两端 TopN——领涨=头部，领跌=尾部（接口 po=1 仅支持降序，升序需本地排）。
 * 涨跌幅/名称缺失的板块剔除（停牌占位「-」，红线 #2 不臆造）。
 */
object MarketMoodCalculator {

    fun splitGainersLosers(list: List<MarketListItem>, topN: Int = 3): MarketMood {
        val sorted = list
            .filter { it.name != null && it.changePct != null }
            .sortedByDescending { it.changePct!! }
        return MarketMood(
            topGainers = sorted.take(topN),
            topLosers = sorted.takeLast(topN).reversed(), // 由跌最深到浅
        )
    }
}
