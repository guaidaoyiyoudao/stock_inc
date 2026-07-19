package com.stock.dividend.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stock.dividend.ui.component.AppCardDefaults
import com.stock.dividend.ui.component.CompanyIcon
import com.stock.dividend.ui.component.IndustryAllocationPieChart
import com.stock.dividend.ui.component.FinanceStatusTone
import com.stock.dividend.ui.component.StatusPill
import com.stock.dividend.ui.theme.FinanceGreen
import com.stock.dividend.ui.theme.FinanceRed
import com.stock.dividend.viewmodel.IndustryGroup
import com.stock.dividend.viewmodel.PortfolioItem
import com.stock.dividend.viewmodel.PortfolioUiState
import com.stock.dividend.viewmodel.PortfolioViewModel
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortfolioScreen(
    onStockClick: (String) -> Unit,
    onImportFromScreenshot: () -> Unit,
    viewModel: PortfolioViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.onResume()
    }

    if (uiState.items.isEmpty()) {
        PortfolioEmptyState(onImportFromScreenshot = onImportFromScreenshot, modifier = Modifier.fillMaxSize())
        return
    }

    PullToRefreshBox(
        isRefreshing = uiState.isLoading,
        onRefresh = { viewModel.refreshQuotes() },
        modifier = Modifier.fillMaxSize()
    ) {
        LazyColumn(
            contentPadding = PaddingValues(
                start = AppCardDefaults.PageHorizontalPadding,
                end = AppCardDefaults.PageHorizontalPadding,
                top = 12.dp,
                bottom = AppCardDefaults.BottomNavigationPadding
            ),
            verticalArrangement = Arrangement.spacedBy(AppCardDefaults.SectionSpacing)
        ) {
            item {
                PortfolioSummaryCard(
                    totalAssets = uiState.totalAssets,
                    holdingsMarketValue = uiState.holdingsMarketValue,
                    totalCost = uiState.totalCost,
                    totalPnl = uiState.totalPnl,
                    totalPnlRate = uiState.totalPnlRate,
                    targetWeightSum = uiState.industryTargetSum,
                    targetWeightLabel = "行业目标合计",
                    onEditTotalAssets = viewModel::showEditTotalAssetsDialog,
                    onImportFromScreenshot = onImportFromScreenshot
                )
            }
            // 行业配置区块
            if (uiState.industryGroups.isNotEmpty()) {
                item {
                    Text(
                        text = "行业配置",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                    )
                }
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                            IndustryAllocationPieChart(groups = uiState.industryGroups)
                        }
                    }
                }
                items(items = uiState.industryGroups, key = { it.name }) { group ->
                    IndustryAllocationCard(
                        group = group,
                        onEditIndustry = {
                            viewModel.showEditIndustryDialog(group.name, group.targetWeight)
                        }
                    )
                }
                item {
                    Text(
                        text = "个股持仓",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                    )
                }
            }
            items(items = uiState.items, key = { it.code }) { item ->
                PortfolioHoldingCard(
                    item = item,
                    onClick = { onStockClick(item.code) },
                    onEditWeight = {
                        viewModel.showEditWeightDialog(item.code, item.targetWeight)
                    }
                )
            }
        }
    }

    val editingCode = uiState.editingCode
    if (editingCode != null) {
        EditWeightDialog(
            weightInput = uiState.editingWeightInput,
            error = uiState.editingWeightError,
            onInputChange = viewModel::onWeightInputChanged,
            onConfirm = viewModel::confirmEditWeight,
            onDismiss = viewModel::dismissDialog
        )
    }

    if (uiState.editingTotalAssets) {
        EditTotalAssetsDialog(
            input = uiState.editingTotalAssetsInput,
            error = uiState.editingTotalAssetsError,
            onInputChange = viewModel::onTotalAssetsInputChanged,
            onConfirm = viewModel::confirmEditTotalAssets,
            onDismiss = viewModel::dismissDialog
        )
    }

    uiState.editingIndustry?.let { industry ->
        EditIndustryDialog(
            industry = industry,
            weightInput = uiState.editingIndustryWeightInput,
            error = uiState.editingIndustryWeightError,
            onInputChange = viewModel::onIndustryWeightInputChanged,
            onConfirm = viewModel::confirmEditIndustry,
            onDismiss = viewModel::dismissDialog
        )
    }
}

@Composable
private fun PortfolioEmptyState(onImportFromScreenshot: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "暂无持仓",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "在自选股中为股票添加买入记录后，\n这里会显示持仓总览",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
            androidx.compose.material3.OutlinedButton(onClick = onImportFromScreenshot) {
                Text("📷 从截图导入持仓")
            }
        }
    }
}

@Composable
private fun PortfolioSummaryCard(
    totalAssets: Double,
    holdingsMarketValue: Double,
    totalCost: Double,
    totalPnl: Double,
    totalPnlRate: Double,
    targetWeightSum: Double,
    targetWeightLabel: String = "目标权重合计",
    onEditTotalAssets: () -> Unit,
    onImportFromScreenshot: () -> Unit,
    modifier: Modifier = Modifier
) {
    val pnlColor = pnlColor(totalPnl)

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "总资产",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Surface(
                    modifier = Modifier.clip(MaterialTheme.shapes.small).clickable(onClick = onEditTotalAssets)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (totalAssets > 0.0) portfolioFormatMoney(totalAssets) else "点击设置",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "编辑总资产",
                            modifier = Modifier.height(14.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "持仓总市值",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = portfolioFormatMoney(holdingsMarketValue),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SummaryMetric(
                    label = "总成本",
                    value = portfolioFormatMoney(totalCost),
                    modifier = Modifier.weight(1f)
                )
                VerticalDivider()
                SummaryMetric(
                    label = "浮盈/浮亏",
                    value = portfolioFormatSignedPnl(totalPnl),
                    valueColor = pnlColor,
                    modifier = Modifier.weight(1f)
                )
                VerticalDivider()
                SummaryMetric(
                    label = "盈亏率",
                    value = portfolioFormatPercent(totalPnlRate * 100.0),
                    valueColor = pnlColor,
                    modifier = Modifier.weight(1f)
                )
            }

            if (targetWeightSum > 0.0 && !targetWeightSum.isApproximately(100.0)) {
                Spacer(modifier = Modifier.height(10.dp))
                StatusPill(
                    text = "$targetWeightLabel ${portfolioFormatPercent(targetWeightSum)}，未达 100%",
                    tone = if (targetWeightSum < 100.0) FinanceStatusTone.Warning else FinanceStatusTone.Neutral
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            androidx.compose.material3.OutlinedButton(
                onClick = onImportFromScreenshot,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("📷 从截图导入持仓")
            }
        }
    }
}

@Composable
private fun PortfolioHoldingCard(
    item: PortfolioItem,
    onClick: () -> Unit,
    onEditWeight: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CompanyIcon(stockCode = item.code, stockName = item.name, modifier = Modifier.padding(end = 10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "${marketPrefix(item.marketCode)} ${codeSuffix(item.code)} · ${item.shares} 股" + item.industry.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = item.marketValue?.let { portfolioFormatMoney(it) } ?: "—",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    val pnl = item.unrealizedPnl
                    val rate = item.unrealizedPnlRate
                    Text(
                        text = if (pnl != null && rate != null) {
                            "${portfolioFormatSignedPnl(pnl)} (${portfolioFormatPercent(rate * 100.0)})"
                        } else "—",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = pnl?.let { pnlColor(it) } ?: MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "成本 ¥${"%.2f".format(Locale.US, item.costPerShare)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "现价 ${item.currentPrice?.let { "¥" + "%.2f".format(Locale.US, it) } ?: "—"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = weightRow(item),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "目标占行业 ${portfolioFormatPercent(item.targetWeight)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onEditWeight)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = "编辑目标权重",
                                    modifier = Modifier.height(12.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "编辑",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                    item.targetValue?.let { tv ->
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "目标金额 ${portfolioFormatMoney(tv)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = valueColor
        )
    }
}

@Composable
private fun VerticalDivider() {
    Box(
        modifier = Modifier
            .padding(horizontal = 6.dp)
            .width(1.dp)
            .height(34.dp)
            .background(MaterialTheme.colorScheme.outlineVariant)
    )
}

@Composable
private fun EditWeightDialog(
    weightInput: String,
    error: String?,
    onInputChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置目标权重") },
        text = {
            Column {
                Text(
                    text = "目标权重代表希望该股票占总资产的百分比，合计建议接近 100%。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = weightInput,
                    onValueChange = onInputChange,
                    label = { Text("目标权重 (%)") },
                    singleLine = true,
                    isError = error != null,
                    supportingText = { Text(error ?: "范围 0 - 100") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun EditTotalAssetsDialog(
    input: String,
    error: String?,
    onInputChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置总资产") },
        text = {
            Column {
                Text(
                    text = "总资产是计算各标的实际占比和目标金额的基准（例如总资产 40 万、目标权重 10%，则目标持仓为 4 万）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = onInputChange,
                    label = { Text("总资产 (元)") },
                    singleLine = true,
                    isError = error != null,
                    supportingText = { Text(error ?: "请输入金额，例如 400000") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

private fun weightRow(item: PortfolioItem): String {
    val actual = item.actualWeight
    return if (actual != null) "实际占比 ${portfolioFormatPercent(actual)}" else "实际占比 —"
}

@Composable
private fun pnlColor(value: Double): androidx.compose.ui.graphics.Color = when {
    value > 0.0 -> FinanceGreen
    value < 0.0 -> FinanceRed
    else -> MaterialTheme.colorScheme.onSurface
}

private fun marketPrefix(marketCode: String): String =
    if (marketCode == "1") "SH" else "SZ"

private fun codeSuffix(code: String): String = code.substringAfter(".")

internal fun portfolioFormatMoney(value: Double): String = "¥${"%,.2f".format(Locale.US, value)}"

internal fun portfolioFormatSignedPnl(value: Double): String {
    val sign = when {
        value > 0.0 -> "+"
        value < 0.0 -> "-"
        else -> ""
    }
    return "$sign¥${"%,.2f".format(Locale.US, kotlin.math.abs(value))}"
}

internal fun portfolioFormatPercent(value: Double): String = "%.1f%%".format(Locale.US, value)

private fun Double.isApproximately(other: Double, epsilon: Double = 0.01): Boolean =
    kotlin.math.abs(this - other) < epsilon


@Composable
private fun IndustryAllocationCard(
    group: IndustryGroup,
    onEditIndustry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = group.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "${group.stocks.size} 只 · ${portfolioFormatMoney(group.holdingsMarketValue)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    val actual = group.actualWeight
                    Text(
                        text = if (actual != null) "实际 ${portfolioFormatPercent(actual)}" else "实际 —",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "目标 ${portfolioFormatPercent(group.targetWeight)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onEditIndustry)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = "编辑行业目标",
                                    modifier = Modifier.height(12.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "编辑",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }

            group.targetValue?.let { tv ->
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "目标金额 ${portfolioFormatMoney(tv)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 行业内个股目标占比和软提示
            if (group.stockTargetSum > 0.0 && !group.stockTargetSum.isApproximately(100.0)) {
                Spacer(modifier = Modifier.height(6.dp))
                StatusPill(
                    text = "个股目标合计 ${portfolioFormatPercent(group.stockTargetSum)}，未达 100%",
                    tone = if (group.stockTargetSum < 100.0) FinanceStatusTone.Warning else FinanceStatusTone.Neutral
                )
            }
        }
    }
}

@Composable
private fun EditIndustryDialog(
    industry: String,
    weightInput: String,
    error: String?,
    onInputChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置「$industry」目标占比") },
        text = {
            Column {
                Text(
                    text = "目标占比代表希望该行业占组合总资产的百分比（行业目标合计建议接近 100%）。行业内的个股目标在个股卡片设置（占该行业的%）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = weightInput,
                    onValueChange = onInputChange,
                    label = { Text("行业目标占比 (%)") },
                    singleLine = true,
                    isError = error != null,
                    supportingText = { Text(error ?: "范围 0 - 100") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
