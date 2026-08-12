package com.stock.dividend.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stock.dividend.data.repository.MoneyFormatter
import com.stock.dividend.data.repository.PercentFormatter
import com.stock.dividend.data.repository.TodaySignalType
import com.stock.dividend.ui.component.AppCard
import com.stock.dividend.ui.component.AppCardTone
import com.stock.dividend.ui.component.EmptyStateView
import com.stock.dividend.ui.component.FinanceMetricRow
import com.stock.dividend.ui.theme.LocalExtendedColors
import com.stock.dividend.viewmodel.TodayViewModel

/**
 * 今日首页（起始 Tab）。一屏三块：① AI 一句话总结 ② 组合表现+大盘对照 ③ 信号卡。
 *
 * 打开即所见，3 秒消费完。AI 卡仅在 briefing 非空时渲染（未配置/缓存缺失则不显示）。
 * 信号用轻量口径（不拉 BOLL，见 [TodayViewModel]）。
 */
@Composable
fun TodayScreen(
    viewModel: TodayViewModel = hiltViewModel(),
    onOpenPortfolio: () -> Unit = {},
    onOpenStock: (String) -> Unit = {},
    onOpenAddStock: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // 接入悬浮刷新按钮（MainScaffold 的 FAB）
    registerTabRefresh(refresh = { viewModel.refresh() }, isRefreshing = state.isLoading)

    if (!state.hasHoldings) {
        EmptyStateView(onAddClick = onOpenAddStock, modifier = Modifier.fillMaxSize())
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ① AI 一句话总结（仅当 briefing 非空时显示）
        state.briefing?.let { briefing ->
            item(key = "briefing") {
                AppCard(tone = AppCardTone.Summary, modifier = Modifier.fillMaxWidth()) {
                    Text("AI 今日解读", style = MaterialTheme.typography.labelMedium)
                    Text(briefing, style = MaterialTheme.typography.titleMedium)
                }
            }
        }

        // ② 组合表现 + 大盘对照
        item(key = "portfolio") {
            AppCard(onClick = onOpenPortfolio, modifier = Modifier.fillMaxWidth()) {
                Text("组合表现", style = MaterialTheme.typography.labelMedium)
                FinanceMetricRow("总市值", MoneyFormatter.withSymbol(state.marketValue))
                FinanceMetricRow(
                    label = "今日盈亏",
                    value = "${MoneyFormatter.withSign(state.todayPnl)} (${PercentFormatter.withSign(state.todayPnlRate)})",
                    valueColor = pnlColor(state.todayPnl),
                )
                FinanceMetricRow(
                    label = "累计盈亏",
                    value = "${MoneyFormatter.withSign(state.totalPnl)} (${PercentFormatter.withSign(state.totalPnlRate)})",
                    valueColor = pnlColor(state.totalPnl),
                )
                state.indexHs300?.let { hs300 ->
                    FinanceMetricRow("沪深300", PercentFormatter.withSign(hs300), valueColor = pnlColor(hs300))
                }
                state.beatHs300?.let { beat ->
                    FinanceMetricRow("跑赢沪深300", "%+.2fpp".format(beat), valueColor = pnlColor(beat))
                }
                if (state.dataStale) {
                    Text(
                        "数据可能延迟，显示上次缓存",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        // ③ 信号卡
        if (state.signals.isEmpty()) {
            item(key = "no_signals") {
                AppCard(modifier = Modifier.fillMaxWidth()) {
                    Text("今日无信号，组合平静", style = MaterialTheme.typography.bodyMedium)
                }
            }
        } else {
            item(key = "signal_header") {
                Text(
                    "今日信号（${state.signals.size}）",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(items = state.signals, key = { it.stockCode + it.type.name }) { signal ->
                AppCard(
                    onClick = { onOpenStock(signal.stockCode) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(signal.stockName, style = MaterialTheme.typography.titleSmall)
                    Text(
                        signal.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        signal.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** 盈亏正负色：正 positive / 负 negative / 零 onSurface（跟随深浅色）。 */
@Composable
private fun pnlColor(v: Double): Color = when {
    v > 0 -> LocalExtendedColors.current.positive
    v < 0 -> LocalExtendedColors.current.negative
    else -> MaterialTheme.colorScheme.onSurface
}
