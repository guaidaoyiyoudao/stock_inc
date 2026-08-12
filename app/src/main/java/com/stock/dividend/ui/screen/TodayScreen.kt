package com.stock.dividend.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stock.dividend.data.repository.MoneyFormatter
import com.stock.dividend.data.repository.PercentFormatter
import com.stock.dividend.data.repository.TodaySignalType
import com.stock.dividend.ui.component.AppCard
import com.stock.dividend.ui.component.AppCardTone
import com.stock.dividend.ui.component.EmptyStateView
import com.stock.dividend.ui.component.FinanceStatusTone
import com.stock.dividend.ui.component.SectionHeader
import com.stock.dividend.ui.component.StatusPill
import com.stock.dividend.ui.theme.LocalExtendedColors
import com.stock.dividend.ui.theme.tabularNumberStyle
import com.stock.dividend.viewmodel.TodayViewModel

/**
 * 今日首页（起始 Tab）。一屏三块：① AI 一句话总结 ② 组合表现+大盘对照 ③ 信号卡。
 *
 * 视觉对标项目设计语言（IncomeSummaryCard / StockCard）：大字主数字 + 加粗货币符号 +
 * ↑↓ 箭头 + 财务色 + StatusPill 信号标签 + SectionHeader 分节。
 */
@Composable
fun TodayScreen(
    viewModel: TodayViewModel = hiltViewModel(),
    onOpenPortfolio: () -> Unit = {},
    onOpenStock: (String) -> Unit = {},
    onOpenAddStock: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    registerTabRefresh(refresh = { viewModel.refresh() }, isRefreshing = state.isLoading)

    if (!state.hasHoldings) {
        EmptyStateView(onAddClick = onOpenAddStock, modifier = Modifier.fillMaxSize())
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ① AI 一句话总结（仅当 briefing 非空时渲染）
        state.briefing?.let { briefing ->
            item(key = "briefing") {
                AppCard(tone = AppCardTone.Summary, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.SmartToy,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                "AI 今日解读",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                            )
                            Text(
                                briefing,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                }
            }
        }

        // ② 组合表现 + 大盘对照（对标 IncomeSummaryCard：大字主数字 + 箭头 + 财务色）
        item(key = "portfolio") {
            AppCard(onClick = onOpenPortfolio, modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 20.dp)
                ) {
                    Text(
                        "组合表现",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.width(10.dp).height(10.dp))

                    // 大字总市值：加粗 ¥ + 千分位金额（tnum 等宽，IncomeSummaryCard 同款）
                    Text(
                        text = buildAnnotatedString {
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("¥ ") }
                            append(MoneyFormatter.amount(state.marketValue))
                        },
                        style = MaterialTheme.typography.headlineMedium.merge(tabularNumberStyle),
                        color = MaterialTheme.colorScheme.onSurface,
                    )

                    Spacer(modifier = Modifier.width(8.dp).height(8.dp))

                    // 今日盈亏：↑↓ 箭头 + 财务色
                    Text(
                        text = "${arrow(state.todayPnl)} ${MoneyFormatter.withSign(state.todayPnl)}" +
                            "（${PercentFormatter.withSign(state.todayPnlRate)}）",
                        style = MaterialTheme.typography.labelLarge.merge(tabularNumberStyle),
                        color = pnlColor(state.todayPnl),
                    )

                    Spacer(modifier = Modifier.width(12.dp).height(12.dp))

                    // 底部对照 Row：累计盈亏% / 跑赢沪深300pp
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            "累计 ${PercentFormatter.withSign(state.totalPnlRate)}",
                            style = MaterialTheme.typography.labelSmall.merge(tabularNumberStyle),
                            color = pnlColor(state.totalPnl),
                        )
                        state.beatHs300?.let { beat ->
                            Text(
                                "跑赢沪深300 ${"%+.2fpp".format(beat)}",
                                style = MaterialTheme.typography.labelSmall.merge(tabularNumberStyle),
                                color = pnlColor(beat),
                            )
                        }
                    }

                    state.indexHs300?.let { hs300 ->
                        Spacer(modifier = Modifier.width(4.dp).height(4.dp))
                        Text(
                            "沪深300 ${PercentFormatter.withSign(hs300)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    if (state.dataStale) {
                        Spacer(modifier = Modifier.width(4.dp).height(4.dp))
                        Text(
                            "数据可能延迟，显示上次缓存",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }

        // ③ 信号区
        item(key = "signal_header") {
            SectionHeader(title = "今日信号（${state.signals.size}）")
        }
        if (state.signals.isEmpty()) {
            item(key = "no_signals") {
                AppCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "今日无信号，组合平静",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    )
                }
            }
        } else {
            items(items = state.signals, key = { it.stockCode + it.type.name }) { signal ->
                AppCard(
                    onClick = { onOpenStock(signal.stockCode) },
                    tone = AppCardTone.List,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                signal.title,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(modifier = Modifier.width(2.dp).height(2.dp))
                            Text(
                                signal.detail,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        StatusPill(text = signalLabel(signal.type), tone = signalTone(signal.type))
                    }
                }
            }
        }
    }
}

/** 盈亏箭头：正↑ / 负↓ / 零—。 */
private fun arrow(v: Double): String = when {
    v > 0 -> "↑"
    v < 0 -> "↓"
    else -> "—"
}

/** 信号类型 → StatusPill 短标签。 */
private fun signalLabel(type: TodaySignalType): String = when (type) {
    TodaySignalType.BUY_TRIGGER -> "买入"
    TodaySignalType.GRID_NEXT_LEVEL -> "网格"
    TodaySignalType.DIVIDEND_COUNTDOWN -> "分红"
}

/** 信号类型 → 财务语义色：买入(Positive 绿) / 网格(Warning 黄) / 分红(Neutral 灰)。 */
private fun signalTone(type: TodaySignalType): FinanceStatusTone = when (type) {
    TodaySignalType.BUY_TRIGGER -> FinanceStatusTone.Positive
    TodaySignalType.GRID_NEXT_LEVEL -> FinanceStatusTone.Warning
    TodaySignalType.DIVIDEND_COUNTDOWN -> FinanceStatusTone.Neutral
}

/** 盈亏正负色：正 positive / 负 negative / 零 onSurface（跟随深浅色）。 */
@Composable
private fun pnlColor(v: Double): Color = when {
    v > 0 -> LocalExtendedColors.current.positive
    v < 0 -> LocalExtendedColors.current.negative
    else -> MaterialTheme.colorScheme.onSurface
}
