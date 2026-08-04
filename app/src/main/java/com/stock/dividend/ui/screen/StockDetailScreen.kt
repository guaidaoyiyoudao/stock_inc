package com.stock.dividend.ui.screen

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.stock.dividend.data.local.entity.DividendEntity
import com.stock.dividend.data.repository.Fundamentals
import com.stock.dividend.data.repository.StockLlmAnalysisState
import com.stock.dividend.data.repository.MoneyFormatter
import com.stock.dividend.data.repository.PercentFormatter
import com.stock.dividend.data.repository.QuoteSnapshot
import com.stock.dividend.data.repository.formatFundamentalsPeriod
import com.stock.dividend.ui.component.AppCard
import com.stock.dividend.ui.component.AppCardDefaults
import com.stock.dividend.ui.component.AppCardTone
import com.stock.dividend.ui.component.CompanyIcon
import com.stock.dividend.ui.component.CompactTopAppBar
import com.stock.dividend.ui.component.DividendRateChart
import com.stock.dividend.ui.component.DividendRateFallbackCard
import com.stock.dividend.ui.component.FinanceMetric
import com.stock.dividend.ui.component.FinanceStatusTone
import com.stock.dividend.ui.component.ForecastComparisonCard
import com.stock.dividend.ui.component.PriceVolumeChart
import com.stock.dividend.ui.component.SectionHeader
import com.stock.dividend.ui.component.StatusPill
import com.stock.dividend.viewmodel.ForecastDetail
import com.stock.dividend.viewmodel.StockDetailViewModel
import com.stock.dividend.ui.theme.LocalExtendedColors
import com.stock.dividend.ui.theme.tabularNumberStyle
import com.stock.dividend.ui.component.AppOutlinedButton
import com.stock.dividend.ui.component.AppTextButton
import com.stock.dividend.ui.component.AppTextField

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StockDetailScreen(
    stockCode: String,
    onBack: () -> Unit,
    onEditHolding: (String) -> Unit = {},
    onOpenDividendValuation: (String) -> Unit = {},
    onOpenDripSimulation: (String) -> Unit = {},
    onOpenGridPlan: (String) -> Unit = {},
    onOpenNotificationSettings: (String) -> Unit = {},
    viewModel: StockDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    var showBuyThresholdDialog by remember { mutableStateOf(false) }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
        topBar = {
            CompactTopAppBar(
                title = uiState.stock?.name ?: stockCode,
                onBack = onBack,
                actions = {
                    RefreshButton(
                        isRefreshing = uiState.isRefreshing,
                        onClick = { viewModel.refreshDividends() }
                    )
                    AppTextButton(onClick = { onOpenDividendValuation(stockCode) }, text = "估值")
                    IconButton(onClick = { onOpenNotificationSettings(stockCode) }) {
                        Icon(
                            imageVector = Icons.Filled.Notifications,
                            contentDescription = "通知设置",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { onEditHolding(stockCode) }) {
                        Icon(
                            imageVector = Icons.Filled.Edit,
                            contentDescription = "编辑持仓",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )
        }
    ) { padding ->
        if (uiState.isLoading) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator()
            }
        } else if (uiState.dividends.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "—",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "暂无股息数据",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            PullToRefreshBox(
                isRefreshing = uiState.isRefreshing,
                onRefresh = { viewModel.refreshDividends() },
                modifier = Modifier
                    .padding(padding)
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
            ) {
                LazyColumn(
                    contentPadding = PaddingValues(
                        start = AppCardDefaults.PageHorizontalPadding,
                        top = 12.dp,
                        end = AppCardDefaults.PageHorizontalPadding,
                        bottom = AppCardDefaults.BottomNavigationPadding
                    ),
                    verticalArrangement = Arrangement.spacedBy(AppCardDefaults.SectionSpacing)
                ) {
                    val stock = uiState.stock

                    if (stock != null && stock.shares > 0) {
                        item {
                            HoldingInfoBanner(
                                shares = stock.shares,
                                stockName = stock.name,
                                stockCode = stock.code
                            )
                        }

                        // 实时盘口 + 估值（与持仓横幅紧邻，体现"现价"语义聚合）
                        val quote = uiState.quote
                        if (quote != null) {
                            item {
                                Spacer(modifier = Modifier.height(8.dp))
                                SectionHeader(title = "实时盘口")
                            }
                            item { QuoteBoardCard(quote = quote) }
                            item {
                                Spacer(modifier = Modifier.height(8.dp))
                                SectionHeader(title = "估值")
                            }
                            item { ValuationCard(quote = quote) }
                        }

                        item {
                            Spacer(modifier = Modifier.height(8.dp))
                            SectionHeader(title = "预测股息收入")
                        }

                        val forecast = uiState.forecast
                        if (forecast != null) {
                            item {
                                ForecastMainCard(
                                    forecast = forecast,
                                    selectedPeriod = uiState.selectedPeriod
                                )
                            }
                        }

                        if (uiState.allForecasts.isNotEmpty()) {
                            item {
                                ForecastComparisonCard(
                                    allForecasts = uiState.allForecasts,
                                    selectedPeriod = uiState.selectedPeriod
                                )
                            }
                        }
                    }

                    if (stock != null) {
                        item {
                            DividendValuationEntryCard(
                                onClick = { onOpenDividendValuation(stockCode) }
                            )
                        }
                    }

                    if (stock != null) {
                        item {
                            DripSimulationEntryCard(
                                onClick = { onOpenDripSimulation(stockCode) }
                            )
                        }
                    }

                    if (stock != null) {
                        item {
                            GridPlanEntryCard(
                                onClick = { onOpenGridPlan(stockCode) }
                            )
                        }
                    }

                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        SectionHeader(
                            title = "分红率趋势",
                            actionText = "买入线",
                            onActionClick = { showBuyThresholdDialog = true }
                        )
                    }

                    item {
                        when {
                            uiState.dividendRatePoints.size >= 2 -> {
                                DividendRateChart(
                                    points = uiState.dividendRatePoints,
                                    buyThreshold = uiState.buyThreshold
                                )
                            }
                            uiState.dividendRatePoints.size == 1 -> {
                                DividendRateFallbackCard(point = uiState.dividendRatePoints.first())
                            }
                            else -> {
                                DividendRateFallbackCard(point = null)
                            }
                        }
                    }

                    // 近期走势（收盘价摘要 + 成交量柱），仅 klines 非空时展示
                    if (uiState.klines.isNotEmpty()) {
                        item {
                            Spacer(modifier = Modifier.height(8.dp))
                            SectionHeader(title = "近期走势（${uiState.klines.size} 日）")
                        }
                        item {
                            PriceVolumeChart(bars = uiState.klines)
                        }
                    }

                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        FundamentalsSection(
                            fundamentals = uiState.fundamentals,
                            isLoading = uiState.fundamentalsLoading,
                            onRefresh = { viewModel.refreshFundamentals() }
                        )
                    }

                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        SectionHeader(title = "AI 解读")
                        Spacer(modifier = Modifier.height(4.dp))
                        StockLlmAnalysisSection(
                            state = uiState.llmAnalysis,
                            hasDividends = uiState.dividends.isNotEmpty(),
                            onAnalyze = { viewModel.analyzeWithLlm() },
                            onRetry = { viewModel.analyzeWithLlm() },
                            onReanalyze = { viewModel.analyzeWithLlm(forceRefresh = true) }
                        )
                    }

                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        SectionHeader(title = "分红记录 (${uiState.dividends.size})")
                    }

                    items(
                        count = minOf(uiState.visibleCount, uiState.dividends.size),
                        key = { index -> uiState.dividends[index].id }
                    ) { index ->
                        val dividend = uiState.dividends[index]
                        DividendRecordCard(
                            dividend = dividend,
                            shares = stock?.shares ?: 0
                        )
                    }

                    if (uiState.visibleCount < uiState.dividends.size) {
                        item {
                            AppTextButton(
                                onClick = { viewModel.loadMoreDividends() },
                                modifier = Modifier.fillMaxWidth(),
                                text = "加载更多 (${uiState.dividends.size - uiState.visibleCount} 条)",
                            )
                        }
                    }
                }
            }
        }

        if (showBuyThresholdDialog) {
            BuyThresholdMultiplierDialog(
                currentMultiplier = uiState.stock?.buyThresholdMultiplier
                    ?: com.stock.dividend.data.local.entity.StockEntity.DEFAULT_BUY_THRESHOLD_MULTIPLIER,
                onDismiss = { showBuyThresholdDialog = false },
                onConfirm = { value ->
                    viewModel.updateBuyThresholdMultiplier(value)
                    showBuyThresholdDialog = false
                }
            )
        }
    }
}

@Composable
private fun BuyThresholdMultiplierDialog(
    currentMultiplier: Double,
    onDismiss: () -> Unit,
    onConfirm: (Double) -> Unit
) {
    var input by remember { mutableStateOf("%.1f".format(currentMultiplier)) }
    val parsed = input.toDoubleOrNull()
    val error: String? = when {
        parsed == null || !parsed.isFinite() -> "请输入有效数字"
        parsed !in 0.1..20.0 -> "请输入 0.1 ~ 20.0 之间的数字"
        else -> null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("买入线倍数") },
        text = {
            Column {
                Text(
                    text = "当前股息率达到「10年期国债收益率 × 该倍数」时提示买入。" +
                            "例如 2.5 表示国债 2.6% 时，股息率需达到 6.5%。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                AppTextField(
                    value = input,
                    onValueChange = { input = it.filter { ch -> ch.isDigit() || ch == '.' } },
                    singleLine = true,
                    label = { Text("倍数") },
                    isError = error != null,
                    supportingText = { error?.let { Text(it) } },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal
                    )
                )
            }
        },
        confirmButton = {
            AppTextButton(
                onClick = { input.toDoubleOrNull()?.let(onConfirm) },
                enabled = error == null,
                text = "确定",
            )
        },
        dismissButton = {
            AppTextButton(
                onClick = onDismiss,
                text = "取消",
            )
        }
    )
}

@Composable
private fun DividendValuationEntryCard(onClick: () -> Unit) {
    AppCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppCardDefaults.ListPadding),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "股息折现估值",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "基于近 5 年股息和未来增长假设评估合理价值",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            AppTextButton(
                onClick = onClick,
                text = "查看",
            )
        }
    }
}

@Composable
private fun DripSimulationEntryCard(onClick: () -> Unit) {
    AppCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppCardDefaults.ListPadding),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "分红再投模拟",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "对比「分红再投」与「现金分红」的复利效果",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            AppTextButton(
                onClick = onClick,
                text = "查看",
            )
        }
    }
}

@Composable
private fun GridPlanEntryCard(onClick: () -> Unit) {
    AppCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppCardDefaults.ListPadding),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "网格交易计划",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "日/周/月 BOLL + 目标股息率自动锚定纯买入档位（仅计划，不下单）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            AppTextButton(
                onClick = onClick,
                text = "设置",
            )
        }
    }
}

@Composable
private fun HoldingInfoBanner(shares: Int, stockName: String, stockCode: String) {
    AppCard(
        modifier = Modifier.fillMaxWidth(),
        tone = AppCardTone.Summary,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppCardDefaults.SummaryPadding),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CompanyIcon(
                    stockCode = stockCode,
                    stockName = stockName,
                    size = 40
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stockName,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = stockCode,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                StatusPill(
                    text = "持仓中",
                    tone = FinanceStatusTone.Positive
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                FinanceMetric(
                    label = "持仓股数",
                    value = "$shares 股",
                    modifier = Modifier.weight(1f)
                )
                FinanceMetric(
                    label = "股票代码",
                    value = stockCode,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.End
                )
            }
        }
    }
}

@Composable
private fun ForecastMainCard(forecast: ForecastDetail, selectedPeriod: String) {
    AppCard(
        modifier = Modifier.fillMaxWidth(),
        tone = AppCardTone.Summary,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(AppCardDefaults.SummaryPadding),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                FinanceMetric(
                    label = "${selectedPeriod}年平均预测",
                    value = MoneyFormatter.withSymbol(forecast.forecastIncome),
                    valueColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                StatusPill(
                    text = "${forecast.actualYears} 年数据",
                    tone = if (forecast.actualYears < selectedPeriod.toInt()) {
                        FinanceStatusTone.Warning
                    } else {
                        FinanceStatusTone.Neutral
                    }
                )
            }
            if (forecast.actualYears < selectedPeriod.toInt()) {
                Text(
                    text = "历史分红样本少于选择周期，预测结果会随新数据更新。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun DividendRecordCard(
    dividend: DividendEntity,
    shares: Int
) {
    AppCard(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppCardDefaults.ListPadding),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val year = dividend.reportDate.substringBefore("-")
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusPill(
                    text = year,
                    tone = FinanceStatusTone.Neutral
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = dividend.planStatus ?: "分红记录",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                dividend.dividendYield?.let { yield ->
                    Text(
                        text = PercentFormatter.percent(yield),
                        style = MaterialTheme.typography.titleSmall.merge(tabularNumberStyle),
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                FinanceMetric(
                    label = "每股派息",
                    value = MoneyFormatter.withSymbol(dividend.cashPerShare, decimals = 4),
                    valueColor = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                if (shares > 0) {
                    val total = dividend.cashPerShare * shares
                    FinanceMetric(
                        label = "预计到账",
                        value = MoneyFormatter.withSymbol(total),
                        valueColor = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.End,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            dividend.exDividendDate?.let { exDate ->
                Text(
                    text = "除权除息日 $exDate",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

        }
    }
}

@Composable
private fun RefreshButton(
    isRefreshing: Boolean,
    onClick: () -> Unit
) {
    if (isRefreshing) {
        val infiniteTransition = rememberInfiniteTransition(label = "refresh")
        val rotation by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 800, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "refreshRotation"
        )
        IconButton(onClick = onClick, enabled = false) {
            Icon(
                imageVector = Icons.Filled.Refresh,
                contentDescription = "刷新股息数据",
                modifier = Modifier.rotate(rotation),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    } else {
        IconButton(onClick = onClick) {
            Icon(
                imageVector = Icons.Filled.Refresh,
                contentDescription = "刷新股息数据",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 个股 AI 解读区块，五态渲染（Idle/Loading/NotConfigured/Success/Error）。
 * 渲染模式对齐组合级 [PortfolioEvaluationScreen.LlmAnalysisSection]，但展示个股四字段。
 */
@Composable
private fun StockLlmAnalysisSection(
    state: StockLlmAnalysisState,
    hasDividends: Boolean,
    onAnalyze: () -> Unit,
    onRetry: () -> Unit,
    onReanalyze: () -> Unit
) {
    when (state) {
        is StockLlmAnalysisState.Success -> {
            val a = state.analysis
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            buildString {
                                append("✨ AI 解读")
                                state.analyzedAt?.let {
                                    append(" · ")
                                    append(formatAnalysisTime(it))
                                }
                                if (state.fromCache) append(" · 缓存")
                            },
                            style = MaterialTheme.typography.titleSmall
                        )
                        AppTextButton(
                            onClick = onReanalyze,
                            text = "重新分析",
                        )
                    }
                    if (a.valuation.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(a.valuation, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (a.dividendSustainability.isNotBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(a.dividendSustainability, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (a.action.isNotBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        StatusPill(
                            text = a.action,
                            tone = FinanceStatusTone.Neutral
                        )
                    }
                    if (a.risks.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        a.risks.forEach {
                            Text("• $it", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    state.notice?.let {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "仅供参考，不构成投资建议。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        StockLlmAnalysisState.Loading -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("正在拉取深度数据并分析…")
            }
        }

        is StockLlmAnalysisState.Error -> {
            Column {
                Text(state.message, color = MaterialTheme.colorScheme.error)
                Spacer(modifier = Modifier.height(4.dp))
                AppTextButton(
                    onClick = onRetry,
                    text = "重试",
                )
            }
        }

        StockLlmAnalysisState.NotConfigured -> {
            Column {
                AppOutlinedButton(onClick = onAnalyze, enabled = false) {
                    Icon(Icons.Filled.AutoAwesome, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("AI 解读")
                }
                Text(
                    "需先在设置配置 LLM",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        StockLlmAnalysisState.Idle -> {
            AppOutlinedButton(onClick = onAnalyze, enabled = hasDividends) {
                Icon(Icons.Filled.AutoAwesome, contentDescription = null)
                Spacer(modifier = Modifier.width(4.dp))
                Text("AI 解读")
            }
        }
    }
}

/**
 * 基本面区块（设计文档 §5）：三态——加载中 / 空 / 成功。
 * 位置在「分红率趋势」与「AI 解读」之间，逻辑顺序：过去的分红 → 支撑分红的盈利 → 综合解读。
 */
@Composable
private fun QuoteBoardCard(quote: QuoteSnapshot) {
    val ext = LocalExtendedColors.current
    // 涨跌色：>0 红(A股涨)、<0 绿、null/0 中性
    val changeColor = quote.change?.let {
        when {
            it > 0 -> ext.positive
            it < 0 -> ext.negative
            else -> MaterialTheme.colorScheme.onSurface
        }
    } ?: MaterialTheme.colorScheme.onSurface
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(AppCardDefaults.ListPadding)) {
            // 第一行：现价 + 涨跌额/幅（现价突出）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                FinanceMetric(
                    label = "现价",
                    value = quote.price?.let { MoneyFormatter.withSymbol(it) } ?: "—",
                    valueColor = changeColor
                )
                FinanceMetric(
                    label = "涨跌",
                    value = quote.change?.let { "${if (it > 0) "+" else ""}${"%.2f".format(it)}" } ?: "—",
                    valueColor = changeColor
                )
                FinanceMetric(
                    label = "涨跌幅",
                    value = quote.changePct?.let { "${if (it > 0) "+" else ""}${"%.2f".format(it)}%" } ?: "—",
                    valueColor = changeColor
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            // 第二行：开 / 高 / 低
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                FinanceMetric(label = "今开", value = quote.open?.let { MoneyFormatter.withSymbol(it) } ?: "—")
                FinanceMetric(label = "最高", value = quote.high?.let { MoneyFormatter.withSymbol(it) } ?: "—")
                FinanceMetric(label = "最低", value = quote.low?.let { MoneyFormatter.withSymbol(it) } ?: "—")
            }
            Spacer(modifier = Modifier.height(8.dp))
            // 第三行：换手 / 量比 / 振幅
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                FinanceMetric(label = "换手率", value = quote.turnoverRate?.let { "%.2f%%".format(it) } ?: "—")
                FinanceMetric(label = "量比", value = quote.volumeRatio?.let { "%.2f".format(it) } ?: "—")
                FinanceMetric(label = "振幅", value = quote.amplitude?.let { "%.2f%%".format(it) } ?: "—")
            }
        }
    }
}

@Composable
private fun ValuationCard(quote: QuoteSnapshot) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(AppCardDefaults.ListPadding)) {
            // 第一行：PE / PB
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                FinanceMetric(label = "PE(TTM)", value = quote.pe?.let { "%.2f".format(it) } ?: "—")
                FinanceMetric(label = "PB", value = quote.pb?.let { "%.2f".format(it) } ?: "—")
            }
            Spacer(modifier = Modifier.height(8.dp))
            // 第二行：总市值 / 流通市值（紧凑万亿单位）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                FinanceMetric(label = "总市值", value = quote.totalMarketCap?.let { MoneyFormatter.compact(it) } ?: "—")
                FinanceMetric(label = "流通市值", value = quote.circMarketCap?.let { MoneyFormatter.compact(it) } ?: "—")
            }
        }
    }
}

@Composable
private fun FundamentalsSection(
    fundamentals: Fundamentals?,
    isLoading: Boolean,
    onRefresh: () -> Unit
) {
    SectionHeader(
        title = "基本面（近${fundamentals?.periods?.size ?: 0}期）",
        actionText = "更新",
        actionIcon = Icons.Filled.Refresh,
        onActionClick = onRefresh
    )
    Spacer(modifier = Modifier.height(4.dp))
    when {
        isLoading -> FundamentalsLoadingCard()
        fundamentals == null || fundamentals.periods.isEmpty() -> FundamentalsEmptyCard()
        else -> FundamentalsCard(fundamentals = fundamentals)
    }
}

@Composable
private fun FundamentalsLoadingCard() {
    AppCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .padding(AppCardDefaults.ListPadding)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp
            )
            Text(
                text = "加载基本面数据…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun FundamentalsEmptyCard() {
    AppCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(AppCardDefaults.ListPadding)) {
            Text(
                text = "暂无基本面数据",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "点上方「更新」重试，或稍后再试。",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 基本面卡片：最新期突出行 + 趋势小表（默认最近 3 期，可展开全部）。
 */
@Composable
private fun FundamentalsCard(fundamentals: Fundamentals) {
    val periods = fundamentals.periods
    // 默认展示最近 3 期（设计文档 §5.2）；展开则显示全部
    var expanded by remember { mutableStateOf(false) }
    val visiblePeriods = remember(periods, expanded) {
        if (expanded || periods.size <= 3) periods else periods.takeLast(3)
    }
    val canToggle = periods.size > 3

    AppCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(AppCardDefaults.ListPadding)) {
            // 最新期突出行
            periods.lastOrNull()?.let { latest ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    FinanceMetric(
                        label = "最新期",
                        value = formatFundamentalsPeriod(latest.reportDate)
                    )
                    FinanceMetric(
                        label = "ROE",
                        value = latest.roe?.let { PercentFormatter.percent(it, decimals = 1) } ?: "—"
                    )
                    FinanceMetric(
                        label = "负债率",
                        value = latest.debtToAssetRatio?.let { "%.0f%%".format(it) } ?: "—"
                    )
                    FinanceMetric(
                        label = "派息率",
                        value = latest.payoutRatio?.let { "%.0f%%".format(it) } ?: "—"
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    FinanceMetric(
                        label = "营收同比",
                        value = formatYoy(latest.revenueYoy),
                        valueColor = yoyColor(latest.revenueYoy)
                    )
                    FinanceMetric(
                        label = "净利同比",
                        value = formatYoy(latest.netProfitYoy),
                        valueColor = yoyColor(latest.netProfitYoy)
                    )
                    FinanceMetric(
                        label = "公告股息率",
                        value = latest.announceYield?.let { PercentFormatter.percent(it) } ?: "—"
                    )
                }
                // 最新期分红方案（整行，缺失不展示）
                latest.dividendPlan?.takeIf { it.isNotBlank() }?.let { plan ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "分红方案：$plan",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (periods.size > 1) {
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "趋势",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                // 趋势表头
                FundamentalsTrendHeader()
                visiblePeriods.forEach { p ->
                    FundamentalsTrendRow(period = p, isLatest = p.reportDate == periods.last().reportDate)
                }

                if (canToggle) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        AppTextButton(onClick = { expanded = !expanded }) {
                            Icon(
                                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                contentDescription = null
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(if (expanded) "收起" else "展开全部 (${periods.size}期)")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FundamentalsTrendHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("期次", modifier = Modifier.weight(1.2f), style = trendHeaderStyle())
        Text("ROE", modifier = cellWeight(), style = trendHeaderStyle())
        Text("负债", modifier = cellWeight(), style = trendHeaderStyle())
        Text("营收", modifier = cellWeight(), style = trendHeaderStyle())
        Text("净利", modifier = cellWeight(), style = trendHeaderStyle())
        Text("派息", modifier = cellWeight(), style = trendHeaderStyle())
    }
}

@Composable
private fun FundamentalsTrendRow(period: Fundamentals.Period, isLatest: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = formatFundamentalsPeriod(period.reportDate) + if (isLatest) "·" else "",
            modifier = Modifier.weight(1.2f),
            style = MaterialTheme.typography.labelMedium,
            color = if (isLatest) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (isLatest) FontWeight.SemiBold else FontWeight.Normal
        )
        Text(period.roe?.let { PercentFormatter.percent(it, decimals = 1) } ?: "—", modifier = cellWeight(), style = trendCellStyle())
        Text(period.debtToAssetRatio?.let { "%.0f%%".format(it) } ?: "—", modifier = cellWeight(), style = trendCellStyle())
        Text(
            formatYoy(period.revenueYoy),
            modifier = cellWeight(),
            style = trendCellStyle().copy(color = yoyColor(period.revenueYoy))
        )
        Text(
            formatYoy(period.netProfitYoy),
            modifier = cellWeight(),
            style = trendCellStyle().copy(color = yoyColor(period.netProfitYoy))
        )
        Text(period.payoutRatio?.let { "%.0f%%".format(it) } ?: "—", modifier = cellWeight(), style = trendCellStyle())
    }
}

/** 趋势单元格权重（仅在 RowScope 内可用，weight 是 RowScope 扩展）。 */
private fun androidx.compose.foundation.layout.RowScope.cellWeight() = Modifier.weight(1f)

@Composable
private fun trendHeaderStyle() = MaterialTheme.typography.labelSmall.copy(
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    fontWeight = FontWeight.SemiBold
)

@Composable
private fun trendCellStyle() = MaterialTheme.typography.labelMedium.copy(
    color = MaterialTheme.colorScheme.onSurface
)

/** 同比%渲染：带正负号。 */
private fun formatYoy(value: Double?): String = when {
    value == null || !value.isFinite() -> "—"
    else -> PercentFormatter.withSign(value, decimals = 1)
}

/** 同比正负色：正→绿，负→红，缺失/0→中性。 */
@Composable
private fun yoyColor(value: Double?): androidx.compose.ui.graphics.Color {
    val unspecified = MaterialTheme.colorScheme.onSurface
    if (value == null || !value.isFinite()) return unspecified
    val ext = LocalExtendedColors.current
    return when {
        value > 0 -> ext.positive
        value < 0 -> ext.negative
        else -> unspecified
    }
}

private fun formatAnalysisTime(epochMillis: Long): String =
    DateTimeFormatter.ofPattern("MM-dd HH:mm")
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(epochMillis))
