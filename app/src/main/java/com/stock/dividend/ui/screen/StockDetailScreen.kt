package com.stock.dividend.ui.screen

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.stock.dividend.data.local.entity.DividendEntity
import com.stock.dividend.ui.component.AppCardDefaults
import com.stock.dividend.ui.component.CompanyIcon
import com.stock.dividend.ui.component.CompactTopAppBar
import com.stock.dividend.ui.component.DividendRateChart
import com.stock.dividend.ui.component.DividendRateFallbackCard
import com.stock.dividend.ui.component.ForecastComparisonCard
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
                    TextButton(onClick = { onEditHolding(stockCode) }) {
                        Text(
                            "编辑持仓",
                            style = MaterialTheme.typography.labelLarge
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
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
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
                        SectionHeader(title = "分红记录", count = uiState.dividends.size)
                    }

                    items(
                        count = minOf(uiState.visibleCount, uiState.dividends.size),
                        key = { index -> uiState.dividends[index].id }
                    ) { index ->
                        val dividend = uiState.dividends[index]
                        val visibleLast = minOf(uiState.visibleCount, uiState.dividends.size) - 1
                        val isLast = index == visibleLast
                        DividendRecordCard(
                            dividend = dividend,
                            shares = stock?.shares ?: 0,
                            isLast = isLast
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
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CompanyIcon(
                stockCode = stockCode,
                stockName = stockName,
                size = 36
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "持有 $shares 股 $stockName",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String, count: Int? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(16.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.primary)
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (count != null) {
            Box(
                modifier = Modifier
                    .background(
                        MaterialTheme.colorScheme.secondaryContainer,
                        RoundedCornerShape(6.dp)
                    )
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "$count",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun ForecastMainCard(forecast: ForecastDetail, selectedPeriod: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = "${selectedPeriod}年平均预测",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append("¥")
                    }
                    append("%.2f".format(forecast.forecastIncome))
                },
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            if (forecast.actualYears < selectedPeriod.toInt()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "基于 ${forecast.actualYears} 年数据",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f)
                )
            }
        }
    }
}

@Composable
private fun DividendRecordCard(
    dividend: DividendEntity,
    shares: Int,
    isLast: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val year = dividend.reportDate.substringBefore("-")
            Box(
                modifier = Modifier
                    .background(
                        MaterialTheme.colorScheme.primaryContainer,
                        RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = year,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "每股 ¥${"%.4f".format(dividend.cashPerShare)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )

                val details = buildList {
                    dividend.exDividendDate?.let { add("除息日 $it") }
                    dividend.planStatus?.let { add(it) }
                }
                if (details.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = details.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                dividend.dividendYield?.let { yield ->
                    Text(
                        text = "${"%.2f".format(yield)}%",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                if (shares > 0) {
                    val total = dividend.cashPerShare * shares
                    Text(
                        text = "¥${"%.2f".format(total)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
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
