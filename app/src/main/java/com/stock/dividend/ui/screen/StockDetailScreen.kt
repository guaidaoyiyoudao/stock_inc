package com.stock.dividend.ui.screen

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
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
import com.stock.dividend.ui.component.CompactTopAppBar
import com.stock.dividend.ui.component.ForecastComparisonCard
import com.stock.dividend.ui.theme.GlassColors
import com.stock.dividend.ui.theme.GradientBackground
import com.stock.dividend.viewmodel.ForecastDetail
import com.stock.dividend.viewmodel.StockDetailViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StockDetailScreen(
    stockCode: String,
    onBack: () -> Unit,
    onEditHolding: (String) -> Unit = {},
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
        GradientBackground(
            modifier = Modifier.fillMaxSize()
        ) {
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
                                stockName = stock.name
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
}

@Composable
private fun HoldingInfoBanner(shares: Int, stockName: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = BorderStroke(1.dp, if (isSystemInDarkTheme()) GlassColors.DarkSurfaceBorder else GlassColors.LightSurfaceBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "持",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }
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
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = BorderStroke(1.dp, if (isSystemInDarkTheme()) GlassColors.DarkSurfaceBorder else GlassColors.LightSurfaceBorder)
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
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = BorderStroke(1.dp, if (isSystemInDarkTheme()) GlassColors.DarkSurfaceBorder else GlassColors.LightSurfaceBorder)
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
