package com.stock.dividend.ui.screen

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stock.dividend.data.repository.BollBand
import com.stock.dividend.data.repository.HoldingAction
import com.stock.dividend.ui.component.AppCardDefaults
import com.stock.dividend.ui.component.FinanceStatusTone
import com.stock.dividend.ui.component.StatusPill
import com.stock.dividend.viewmodel.EvaluatedStock
import com.stock.dividend.viewmodel.PortfolioViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortfolioEvaluationScreen(
    onBack: () -> Unit,
    viewModel: PortfolioViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("持仓评估") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        when {
            uiState.isEvaluating -> LoadingState(Modifier.padding(padding))
            uiState.evaluation == null -> NoEvaluationState(
                Modifier.padding(padding),
                onBack = onBack
            )
            uiState.evaluation!!.isEmpty() -> EmptyEvaluationState(
                Modifier.padding(padding),
                onReevaluate = viewModel::evaluateVisibleHoldings
            )
            else -> EvaluationContent(
                Modifier.padding(padding),
                evaluated = uiState.evaluation!!,
                onReevaluate = viewModel::evaluateVisibleHoldings,
                onClear = viewModel::clearEvaluation
            )
        }
    }
}

@Composable
private fun LoadingState(modifier: Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text(
                "正在拉取周线 boll 数据…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun NoEvaluationState(modifier: Modifier, onBack: () -> Unit) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "尚未评估",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "返回持仓页点击「一键评估」",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = onBack) { Text("返回") }
        }
    }
}

@Composable
private fun EmptyEvaluationState(
    modifier: Modifier,
    onReevaluate: () -> Unit
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "当前筛选下无持仓股",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onReevaluate) { Text("重新评估") }
        }
    }
}

@Composable
private fun EvaluationContent(
    modifier: Modifier,
    evaluated: List<EvaluatedStock>,
    onReevaluate: () -> Unit,
    onClear: () -> Unit
) {
    val counts = evaluated.groupBy { it.action }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = AppCardDefaults.PageHorizontalPadding,
            end = AppCardDefaults.PageHorizontalPadding,
            top = 12.dp,
            bottom = AppCardDefaults.BottomNavigationPadding
        ),
        verticalArrangement = Arrangement.spacedBy(AppCardDefaults.SectionSpacing)
    ) {
        item { EvaluationSummary(counts) }
        // 按 action 优先级分组渲染
        listOf(HoldingAction.BUY, HoldingAction.HOLD, HoldingAction.SELL, HoldingAction.INSUFFICIENT_DATA)
            .filter { counts[it]?.isNotEmpty() == true }
            .forEach { action ->
                item {
                    SectionHeader(action)
                }
                items(items = counts[action].orEmpty(), key = { it.code }) { stock ->
                    EvaluationCard(stock)
                }
            }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = onReevaluate,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null)
                    Spacer(Modifier.padding(end = 4.dp))
                    Text("重新评估")
                }
                OutlinedButton(
                    onClick = onClear,
                    modifier = Modifier.weight(1f)
                ) { Text("清除结果") }
            }
        }
    }
}

@Composable
private fun EvaluationSummary(counts: Map<HoldingAction, List<EvaluatedStock>>) {
    fun count(a: HoldingAction) = counts[a]?.size ?: 0
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SummaryPill("买 ${count(HoldingAction.BUY)}", FinanceStatusTone.Positive, Modifier.weight(1f))
        SummaryPill("持有 ${count(HoldingAction.HOLD)}", FinanceStatusTone.Neutral, Modifier.weight(1f))
        SummaryPill("卖 ${count(HoldingAction.SELL)}", FinanceStatusTone.Negative, Modifier.weight(1f))
        if (count(HoldingAction.INSUFFICIENT_DATA) > 0) {
            SummaryPill(
                "数据不足 ${count(HoldingAction.INSUFFICIENT_DATA)}",
                FinanceStatusTone.Warning,
                Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun SummaryPill(text: String, tone: FinanceStatusTone, modifier: Modifier = Modifier) {
    StatusPill(text = text, tone = tone, modifier = modifier)
}

@Composable
private fun SectionHeader(action: HoldingAction) {
    val title = when (action) {
        HoldingAction.BUY -> "买入信号"
        HoldingAction.HOLD -> "持有"
        HoldingAction.SELL -> "卖出信号"
        HoldingAction.INSUFFICIENT_DATA -> "数据不足"
    }
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
    )
}

@Composable
private fun EvaluationCard(stock: EvaluatedStock) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stock.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "${stock.code}${if (stock.industry.isNotBlank()) " · ${stock.industry}" else ""}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                StatusPill(
                    text = actionLabel(stock.action),
                    tone = actionTone(stock.action)
                )
            }
            // 第二行：boll 位置 + 股息率
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = if (stock.bollBand != null && stock.priceVsLower.isFinite()) {
                        "距下轨 ${(stock.priceVsLower * 100).toInt()}%"
                    } else {
                        "boll —"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = if (stock.dividendYield != null) {
                        "股息率 %.1f%%".format(stock.dividendYield)
                    } else {
                        "股息率 —"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // 第三行：理由列表
            stock.reasons.forEach { reason ->
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        "·",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.padding(end = 4.dp))
                    Text(
                        reason,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

private fun actionLabel(a: HoldingAction) = when (a) {
    HoldingAction.BUY -> "买"
    HoldingAction.HOLD -> "持有"
    HoldingAction.SELL -> "卖"
    HoldingAction.INSUFFICIENT_DATA -> "数据不足"
}

private fun actionTone(a: HoldingAction) = when (a) {
    HoldingAction.BUY -> FinanceStatusTone.Positive
    HoldingAction.HOLD -> FinanceStatusTone.Neutral
    HoldingAction.SELL -> FinanceStatusTone.Negative
    HoldingAction.INSUFFICIENT_DATA -> FinanceStatusTone.Warning
}
