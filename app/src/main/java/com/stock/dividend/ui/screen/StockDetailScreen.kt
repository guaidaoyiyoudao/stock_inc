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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import com.stock.dividend.ui.component.AppCardDefaults
import com.stock.dividend.ui.component.CompanyIcon
import com.stock.dividend.ui.component.CompactTopAppBar
import com.stock.dividend.ui.component.DividendRateChart
import com.stock.dividend.ui.component.DividendRateFallbackCard
import com.stock.dividend.ui.component.FinanceMetric
import com.stock.dividend.ui.component.FinanceStatusTone
import com.stock.dividend.ui.component.ForecastComparisonCard
import com.stock.dividend.ui.component.SectionHeader
import com.stock.dividend.ui.component.StatusPill
import com.stock.dividend.viewmodel.ForecastDetail
import com.stock.dividend.viewmodel.StockDetailViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StockDetailScreen(
    stockCode: String,
    onBack: () -> Unit,
    onEditHolding: (String) -> Unit = {},
    onOpenDividendValuation: (String) -> Unit = {},
    viewModel: StockDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

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
                    TextButton(onClick = { onOpenDividendValuation(stockCode) }) {
                        Text(
                            text = "估值",
                            style = MaterialTheme.typography.labelLarge
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

                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        SectionHeader(title = "分红率趋势")
                    }

                    item {
                        when {
                            uiState.dividendRatePoints.size >= 2 -> {
                                DividendRateChart(points = uiState.dividendRatePoints)
                            }
                            uiState.dividendRatePoints.size == 1 -> {
                                DividendRateFallbackCard(point = uiState.dividendRatePoints.first())
                            }
                            else -> {
                                DividendRateFallbackCard(point = null)
                            }
                        }
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
                            TextButton(
                                onClick = { viewModel.loadMoreDividends() },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "加载更多 (${uiState.dividends.size - uiState.visibleCount} 条)",
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DividendValuationEntryCard(onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = AppCardDefaults.listCardColors(),
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
            TextButton(onClick = onClick) {
                Text("查看")
            }
        }
    }
}

@Composable
private fun HoldingInfoBanner(shares: Int, stockName: String, stockCode: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = AppCardDefaults.summaryCardColors(),
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = AppCardDefaults.summaryCardColors(),
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
                    value = "¥%.2f".format(forecast.forecastIncome),
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = AppCardDefaults.listCardColors(),
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
                        text = "${"%.2f".format(yield)}%",
                        style = MaterialTheme.typography.titleSmall,
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
                    value = "¥${"%.4f".format(dividend.cashPerShare)}",
                    valueColor = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                if (shares > 0) {
                    val total = dividend.cashPerShare * shares
                    FinanceMetric(
                        label = "预计到账",
                        value = "¥${"%.2f".format(total)}",
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
