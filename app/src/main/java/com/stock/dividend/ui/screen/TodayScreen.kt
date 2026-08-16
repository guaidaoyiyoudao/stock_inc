package com.stock.dividend.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stock.dividend.data.repository.HealthLevel
import com.stock.dividend.data.repository.IndexQuote
import com.stock.dividend.data.repository.MarketListItem
import com.stock.dividend.data.repository.MarketMood
import com.stock.dividend.data.repository.MoneyFormatter
import com.stock.dividend.data.repository.PercentFormatter
import com.stock.dividend.data.repository.PortfolioRiskDiagnosis
import com.stock.dividend.data.repository.TodaySignalType
import com.stock.dividend.ui.component.AppCard
import com.stock.dividend.ui.component.AppCardTone
import com.stock.dividend.ui.component.EmptyStateView
import com.stock.dividend.ui.component.FinanceMetricRow
import com.stock.dividend.ui.component.FinanceStatusTone
import com.stock.dividend.ui.component.SectionHeader
import com.stock.dividend.ui.component.StatusPill
import com.stock.dividend.ui.theme.LocalExtendedColors
import com.stock.dividend.ui.theme.tabularNumberStyle
import com.stock.dividend.viewmodel.TodayViewModel
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 今日首页（起始 Tab）。金融分析师晨报视角分区：
 * ① AI 一句话总结 ② 组合表现（对照沪深300）③ 市场环境（四指数+板块温度）
 * ④ 组合体检（集中度/股息可持续/估值水位，摘要+展开）⑤ 今年股息现金流进度 ⑥ 信号卡。
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
    onOpenIncome: () -> Unit = {},
    /** 网格信号点击直达该标的的网格计划页（其余信号仍跳个股详情）。 */
    onOpenGridPlan: (String) -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    registerTabRefresh(refresh = { viewModel.refresh() }, isRefreshing = state.isLoading)

    if (!state.hasHoldings) {
        // 无持仓：仍展示市场环境（看大盘不需要持仓）+ 引导添加
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "market_empty") { MarketSection(state.indices, state.marketMood, state.inflowIndustries) }
            item(key = "empty_state") {
                EmptyStateView(onAddClick = onOpenAddStock, modifier = Modifier.fillMaxWidth())
            }
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ⓪ 日期头：锚定「今日」是哪天（周几影响开盘/收盘预期）
        item(key = "date_header") {
            val today = remember { LocalDate.now() }
            Text(
                text = today.format(DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINESE)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }

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

        // ② 组合表现（对标 IncomeSummaryCard：大字主数字 + 箭头 + 财务色）
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
                    Spacer(modifier = Modifier.height(10.dp))

                    // 大字总市值：加粗 ¥ + 千分位金额（tnum 等宽，IncomeSummaryCard 同款）
                    Text(
                        text = buildAnnotatedString {
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("¥ ") }
                            append(MoneyFormatter.amount(state.marketValue))
                        },
                        style = MaterialTheme.typography.headlineMedium.merge(tabularNumberStyle),
                        color = MaterialTheme.colorScheme.onSurface,
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // 今日盈亏：↑↓ 箭头 + 财务色
                    Text(
                        text = "${arrow(state.todayPnl)} ${MoneyFormatter.withSign(state.todayPnl)}" +
                            "（${PercentFormatter.withSign(state.todayPnlRate)}）",
                        style = MaterialTheme.typography.labelLarge.merge(tabularNumberStyle),
                        color = pnlColor(state.todayPnl),
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // 底部对照 Row：累计盈亏% / 跑赢沪深300pp（指数详情移市场环境卡）
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

                    if (state.dataStale) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "数据可能延迟，显示上次缓存",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }

        // ③ 市场环境：四指数 + 领涨领跌板块 + 主力净流入（数据全空时整节隐藏）
        if (state.indices.isNotEmpty() || state.marketMood.topGainers.isNotEmpty() ||
            state.inflowIndustries.isNotEmpty()
        ) {
            item(key = "market") {
                MarketSection(state.indices, state.marketMood, state.inflowIndustries)
            }
        }

        // ④ 组合体检：三维度红绿灯 + 展开（诊断装配失败时隐藏）
        val diagnosis = state.diagnosis
        val grade = state.healthGrade
        if (diagnosis != null && grade != null) {
            item(key = "health_header") { SectionHeader(title = "组合体检") }
            item(key = "health") {
                PortfolioHealthCard(
                    diagnosis = diagnosis,
                    overall = grade.overall,
                    levels = Triple(grade.concentration, grade.sustainability, grade.valuation),
                    summaries = Triple(
                        grade.concentrationSummary,
                        grade.sustainabilitySummary,
                        grade.valuationSummary,
                    ),
                )
            }
        }

        // ⑤ 今年股息：已到账 vs 全年预测（点击跳收入 Tab）
        item(key = "cashflow") {
            DividendCashflowCard(
                received = state.yearDividendReceived,
                forecast = state.yearDividendForecast,
                onClick = onOpenIncome,
            )
        }

        // ⑥ 信号区
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
            items(items = state.signals, key = { it.key }) { signal ->
                AppCard(
                    // 网格信号直达网格计划页（改参数/看档位/记账都在那里），其余跳个股详情
                    onClick = {
                        if (signal.type == TodaySignalType.GRID_NEXT_LEVEL) {
                            onOpenGridPlan(signal.stockCode)
                        } else {
                            onOpenStock(signal.stockCode)
                        }
                    },
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
                            // 股票名（加粗）+ 信号标题（primary 色），单行合并避免三行过高
                            Text(
                                text = buildAnnotatedString {
                                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                                        append(signal.stockName)
                                    }
                                    append(" · ")
                                    withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) {
                                        append(signal.title)
                                    }
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(modifier = Modifier.height(2.dp))
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

/** 市场环境节：SectionHeader + 卡（四指数 2×2 + 领涨/领跌 + 主力净流入）。 */
@Composable
private fun MarketSection(
    indices: List<IndexQuote>,
    mood: MarketMood,
    inflowIndustries: List<MarketListItem>,
) {
    Column {
        SectionHeader(title = "市场环境")
        Spacer(modifier = Modifier.height(4.dp))
        AppCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                // 四大指数 2×2 网格（名称 + 涨跌幅财务色）
                indices.chunked(2).forEach { row ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        row.forEach { q ->
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    q.name ?: q.code,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    PercentFormatter.withSign(q.changePct ?: 0.0),
                                    style = MaterialTheme.typography.titleSmall.merge(tabularNumberStyle),
                                    color = pnlColor(q.changePct ?: 0.0),
                                )
                            }
                        }
                        // 单数行补空占位保持两列对齐
                        if (row.size == 1) Spacer(modifier = Modifier.weight(1f))
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                }

                // 领涨 / 领跌 两列
                if (mood.topGainers.isNotEmpty() || mood.topLosers.isNotEmpty()) {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        IndustryColumn("领涨", mood.topGainers, positive = true, modifier = Modifier.weight(1f))
                        Spacer(modifier = Modifier.width(16.dp))
                        IndustryColumn("领跌", mood.topLosers, positive = false, modifier = Modifier.weight(1f))
                    }
                }

                // 主力净流入板块
                if (inflowIndustries.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "主力净流入板块",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        inflowIndustries.joinToString("　") { item ->
                            "${item.name ?: "—"} ${item.mainNetInflow?.let { MoneyFormatter.compact(it) } ?: "—"}"
                        },
                        style = MaterialTheme.typography.bodySmall.merge(tabularNumberStyle),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

/** 领涨/领跌板块列（板块名 + 涨跌幅）。 */
@Composable
private fun IndustryColumn(
    label: String,
    items: List<MarketListItem>,
    positive: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        items.forEach { item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 1.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    item.name ?: "—",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    PercentFormatter.withSign(item.changePct ?: 0.0),
                    style = MaterialTheme.typography.bodySmall.merge(tabularNumberStyle),
                    color = if (positive) {
                        LocalExtendedColors.current.positive
                    } else {
                        LocalExtendedColors.current.negative
                    },
                )
            }
        }
    }
}

/** 组合体检卡：三维度红绿灯摘要 + 点击展开完整诊断数字与建议。 */
@Composable
private fun PortfolioHealthCard(
    diagnosis: PortfolioRiskDiagnosis,
    overall: HealthLevel,
    levels: Triple<HealthLevel, HealthLevel, HealthLevel>,
    summaries: Triple<String, String, String>,
) {
    var expanded by remember { mutableStateOf(false) }
    AppCard(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "组合体检",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                StatusPill(text = overallLabel(overall), tone = levelTone(overall))
            }
            Spacer(modifier = Modifier.height(12.dp))

            HealthRow("集中度", levels.first, summaries.first)
            Spacer(modifier = Modifier.height(8.dp))
            HealthRow("股息可持续", levels.second, summaries.second)
            Spacer(modifier = Modifier.height(8.dp))
            HealthRow("估值水位", levels.third, summaries.third)

            // 首条建议（摘要态提示，展开后看全部）
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                diagnosis.suggestions.firstOrNull() ?: "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    FinanceMetricRow("持仓数", diagnosis.holdingCount.toString())
                    FinanceMetricRow("行业集中 HHI", diagnosis.industryHhi?.roundToInt()?.toString() ?: "—")
                    FinanceMetricRow("前 3 行业合计", diagnosis.industryCr3?.let { PercentFormatter.percent(it, 0) } ?: "—")
                    FinanceMetricRow("单股最大权重", diagnosis.stockCr1?.let { PercentFormatter.percent(it, 0) } ?: "—")
                    FinanceMetricRow("股息来源前 3", diagnosis.dividendSourceCr3?.let { PercentFormatter.percent(it, 0) } ?: "—")
                    FinanceMetricRow(
                        "派息率>100%",
                        diagnosis.highPayoutCodes.takeIf { it.isNotEmpty() }?.joinToString("、") ?: "无",
                    )
                    FinanceMetricRow(
                        "加权股息率 vs 10Y 国债",
                        listOfNotNull(
                            diagnosis.weightedDividendYieldPct?.let { PercentFormatter.percent(it) },
                            diagnosis.bondYield10yPct?.let { PercentFormatter.percent(it) },
                        ).joinToString(" / ").ifEmpty { "—" },
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    diagnosis.suggestions.forEach { s ->
                        Text(
                            "· $s",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                if (expanded) "收起" else "展开全部",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** 体检单行：色点 + 维度名 + 核心数字。 */
@Composable
private fun HealthRow(label: String, level: HealthLevel, summary: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(levelColor(level))
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            summary,
            style = MaterialTheme.typography.bodySmall.merge(tabularNumberStyle),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 今年股息卡：已到账大字 + 全年预测进度条（点击跳收入 Tab）。 */
@Composable
private fun DividendCashflowCard(received: Double, forecast: Double, onClick: () -> Unit) {
    AppCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            Text(
                "今年股息",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("¥ ") }
                    append(MoneyFormatter.amount(received))
                },
                style = MaterialTheme.typography.headlineMedium.merge(tabularNumberStyle),
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (forecast > 0.0) {
                val progress = (received / forecast).toFloat().coerceIn(0f, 1f)
                Spacer(modifier = Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(6.dp))
                val gap = forecast - received
                Text(
                    if (gap > 0.0) {
                        "全年预测 ${MoneyFormatter.withSymbol(forecast)} · 还差 ${MoneyFormatter.amount(gap)}"
                    } else {
                        "全年预测 ${MoneyFormatter.withSymbol(forecast)} · 已超预测 ${MoneyFormatter.amount(-gap)}"
                    },
                    style = MaterialTheme.typography.labelSmall.merge(tabularNumberStyle),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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

/** 体检分级 → 总体标签。 */
private fun overallLabel(level: HealthLevel): String = when (level) {
    HealthLevel.OK -> "结构均衡"
    HealthLevel.WARN -> "需关注"
    HealthLevel.BAD -> "存在风险"
}

/** 体检分级 → StatusPill tone（与色点同源）。 */
private fun levelTone(level: HealthLevel): FinanceStatusTone = when (level) {
    HealthLevel.OK -> FinanceStatusTone.Positive
    HealthLevel.WARN -> FinanceStatusTone.Warning
    HealthLevel.BAD -> FinanceStatusTone.Negative
}

/** 体检分级 → 红绿灯色点颜色。 */
@Composable
private fun levelColor(level: HealthLevel): Color = when (level) {
    HealthLevel.OK -> LocalExtendedColors.current.positive
    HealthLevel.WARN -> MaterialTheme.colorScheme.tertiary
    HealthLevel.BAD -> LocalExtendedColors.current.negative
}

/** 盈亏正负色：正 positive / 负 negative / 零 onSurface（跟随深浅色）。 */
@Composable
private fun pnlColor(v: Double): Color = when {
    v > 0 -> LocalExtendedColors.current.positive
    v < 0 -> LocalExtendedColors.current.negative
    else -> MaterialTheme.colorScheme.onSurface
}
