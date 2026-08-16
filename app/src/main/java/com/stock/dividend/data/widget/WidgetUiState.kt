package com.stock.dividend.data.widget

import androidx.compose.runtime.Stable

/** 网格下一档提示行（纯缓存计算，最多展示 2 条）。 */
@Stable
data class GridNextHint(
    val stockCode: String,
    val stockName: String,
    val nextBuyPrice: Double
)

/** Widget 渲染用的纯数据快照。所有字段已聚合，渲染层不再做计算。 */
@Stable
data class WidgetUiState(
    val totalMarketValue: Double,        // Σ(持仓股数 × 现价)，现价缺失的股按 0 计入
    val pricedCount: Int,                // 有现价的持仓股数
    val holdingCount: Int,               // 持仓总数（shares > 0）
    val costBasisPnl: Double,            // 成本基准盈亏 = Σ((现价 - 成本) × 股数)
    val costBasisPnlPercent: Double,     // 盈亏百分比 = costBasisPnl / Σ(成本 × 股数)
    val fireGoalAmount: Double,          // FIRE 目标金额（0 表未设，UI 隐藏）
    val fireProgress: Double,            // 0..1，市值/目标金额 进度代理
    val lastPriceUpdatedAt: Long,        // price_cache 中最新一条 updatedAt（新鲜度）
    val isRefreshing: Boolean,           // 手动刷新中（Glance 状态，非 DB）
    val refreshFailed: Boolean,          // 上次手动刷新是否失败
    val gridNextHints: List<GridNextHint> = emptyList(),  // 网格下一买档（价缓存口径，无则空）
) {
    companion object {
        /** 空快照（无持仓且无网格计划、或读取异常时用） */
        val EMPTY = WidgetUiState(
            totalMarketValue = 0.0,
            pricedCount = 0,
            holdingCount = 0,
            costBasisPnl = 0.0,
            costBasisPnlPercent = 0.0,
            fireGoalAmount = 0.0,
            fireProgress = 0.0,
            lastPriceUpdatedAt = 0L,
            isRefreshing = false,
            refreshFailed = false,
        )
    }
}
