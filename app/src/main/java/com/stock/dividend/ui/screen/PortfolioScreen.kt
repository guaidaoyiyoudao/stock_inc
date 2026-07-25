package com.stock.dividend.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import com.stock.dividend.R
import kotlinx.coroutines.launch
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stock.dividend.ui.component.AppCardDefaults
import com.stock.dividend.ui.component.CompanyIcon
import com.stock.dividend.ui.component.DividendPriceScale
import com.stock.dividend.ui.component.DividendSummaryCard
import com.stock.dividend.ui.component.EmptyStateView
import com.stock.dividend.ui.component.FireProgressCard
import com.stock.dividend.ui.component.IndustryAllocationPieChart
import com.stock.dividend.ui.component.StockCard
import com.stock.dividend.ui.component.FinanceStatusTone
import com.stock.dividend.ui.component.StatusPill
import com.stock.dividend.ui.theme.FinanceGreen
import com.stock.dividend.ui.theme.FinanceRed
import com.stock.dividend.data.local.entity.StockEntity
import com.stock.dividend.viewmodel.IndustryGroup
import com.stock.dividend.viewmodel.PortfolioItem
import com.stock.dividend.viewmodel.PortfolioUiState
import com.stock.dividend.viewmodel.PortfolioViewModel
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortfolioScreen(
    snackbarHostState: SnackbarHostState,
    onAddStockClick: () -> Unit,
    onStockClick: (String) -> Unit,
    onEditStock: (String) -> Unit,
    onImportFromScreenshot: () -> Unit,
    onFireCardClick: () -> Unit = {},
    viewModel: PortfolioViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.onResume()
    }

    // 全局刷新：行情 + 行业一并刷新
    registerTabRefresh(
        refresh = {
            viewModel.refreshQuotes()
            viewModel.refreshIndustries()
        },
        isRefreshing = uiState.isLoading
    )

    // 删除后弹出撤销 Snackbar：ActionPerformed → 恢复；Dismissed → 清除待删状态
    LaunchedEffect(uiState.deletedStock) {
        val deleted = uiState.deletedStock ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = "已删除 ${deleted.name}",
            actionLabel = "撤销",
            duration = SnackbarDuration.Short
        )
        when (result) {
            SnackbarResult.ActionPerformed -> viewModel.undoDelete()
            SnackbarResult.Dismissed -> viewModel.clearDeleted()
        }
    }

    if (uiState.items.isEmpty() && uiState.watchlist.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyStateView(onAddClick = onAddStockClick)
        }
        return
    }

    var industryExpanded by remember { mutableStateOf(true) }
    var holdingsExpanded by remember { mutableStateOf(true) }
    LazyColumn(
        contentPadding = PaddingValues(
            start = AppCardDefaults.PageHorizontalPadding,
            end = AppCardDefaults.PageHorizontalPadding,
            top = 12.dp,
            bottom = AppCardDefaults.BottomNavigationPadding
        ),
            verticalArrangement = Arrangement.spacedBy(AppCardDefaults.SectionSpacing)
        ) {
            // 年股息预测摘要（来自原自选 tab）
            item {
                DividendSummaryCard(
                    totalAmount = uiState.forecastTotal,
                    totalMarketValue = uiState.holdingsMarketValue
                )
            }
            // FIRE 进度（来自原自选 tab）
            item {
                FireProgressCard(
                    targetAmount = uiState.livingExpenseTargetAmount,
                    forecastTotal = uiState.forecastTotal,
                    progress = uiState.fireProgress,
                    onClick = onFireCardClick
                )
            }
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
                    val arrowRotation by animateFloatAsState(
                        targetValue = if (industryExpanded) 0f else -90f,
                        label = "industryArrow"
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .clickable { industryExpanded = !industryExpanded }
                            .padding(top = 4.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = if (industryExpanded) "收起行业配置" else "展开行业配置",
                            modifier = Modifier
                                .size(24.dp)
                                .rotate(arrowRotation),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "行业配置",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        if (uiState.isRefreshingIndustry) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            TextButton(onClick = { viewModel.refreshIndustries() }) {
                                Text("刷新行业", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
                if (industryExpanded) {
                    item {
                        Text(
                            text = "长按卡片可编辑目标权重",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier.padding(bottom = 2.dp)
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
                }
            }
            // 个股持仓区块
            item {
                val arrowRotation by animateFloatAsState(
                    targetValue = if (holdingsExpanded) 0f else -90f,
                    label = "holdingsArrow"
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .clickable { holdingsExpanded = !holdingsExpanded }
                        .padding(top = 4.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = if (holdingsExpanded) "收起个股持仓" else "展开个股持仓",
                        modifier = Modifier
                            .size(24.dp)
                            .rotate(arrowRotation),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "个股持仓",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    // 添加股票入口（来自原自选 tab）
                    TextButton(onClick = onAddStockClick) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null
                        )
                        Text(
                            text = stringResource(R.string.add_stock),
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
            if (holdingsExpanded) {
                items(items = uiState.items, key = { it.code }) { item ->
                    SwipeToDismissHoldingItem(
                        item = item,
                        onClick = { onStockClick(item.code) },
                        onEditWeight = { viewModel.showEditWeightDialog(item.code, item.targetWeight) },
                        onEditStock = { onEditStock(item.code) },
                        onDeleteStock = { viewModel.deleteStock(item.code) },
                        latestYearlyDividend = uiState.stockForecasts[item.code]?.latestYearlyDividend
                    )
                }
            }
            // 自选股区块（shares=0，与持仓股区分样式）
            if (uiState.watchlist.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "自选关注",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                items(items = uiState.watchlist, key = { it.code }) { stock ->
                    SwipeToDismissWatchItem(
                        stock = stock,
                        forecastIncome = uiState.stockForecasts[stock.code]?.forecastIncome,
                        marketValue = uiState.stockForecasts[stock.code]?.marketValue,
                        currentPrice = uiState.stockForecasts[stock.code]?.currentPrice,
                        latestYearlyDividend = uiState.stockForecasts[stock.code]?.latestYearlyDividend,
                        onDismiss = { viewModel.deleteStock(stock) },
                        onClick = { onStockClick(stock.code) },
                        onEdit = { onEditStock(stock.code) }
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PortfolioHoldingCard(
    item: PortfolioItem,
    onClick: () -> Unit,
    onEditWeight: () -> Unit,
    latestYearlyDividend: Double?,
    modifier: Modifier = Modifier
) {
    val haptics = LocalHapticFeedback.current
    Card(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onEditWeight()
                }
            ),
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
                    Text(
                        text = "目标占行业 ${portfolioFormatPercent(item.targetWeight)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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

            // 股息率→目标价横轴（与自选股卡片一致）。
            // 仅当现价与最新年度股息都有效时渲染（DividendPriceScale 内部已做空值/非正数短路）。
            DividendPriceScale(
                currentPrice = item.currentPrice,
                latestYearlyDividend = latestYearlyDividend,
                modifier = Modifier.padding(top = 12.dp)
            )
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


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToDismissHoldingItem(
    item: PortfolioItem,
    onClick: () -> Unit,
    onEditWeight: () -> Unit,
    onEditStock: () -> Unit,
    onDeleteStock: () -> Unit,
    latestYearlyDividend: Double?
) {
    var showConfirmDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val dismissState = rememberSwipeToDismissBoxState(confirmValueChange = { false })

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HoldingActionButton(
                        label = "编辑",
                        icon = Icons.Default.Edit,
                        color = MaterialTheme.colorScheme.primary,
                        onClick = {
                            scope.launch { dismissState.reset() }
                            onEditStock()
                        }
                    )
                    HoldingActionButton(
                        label = "删除",
                        icon = Icons.Default.Delete,
                        color = MaterialTheme.colorScheme.error,
                        onClick = {
                            scope.launch { dismissState.reset() }
                            showConfirmDialog = true
                        }
                    )
                }
            }
        },
        enableDismissFromStartToEnd = false
    ) {
        PortfolioHoldingCard(
            item = item,
            onClick = onClick,
            onEditWeight = onEditWeight,
            latestYearlyDividend = latestYearlyDividend
        )
    }

    if (showConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showConfirmDialog = false },
            title = { Text("确认删除") },
            text = { Text("确定要从持仓移除 ${item.name} 吗？") },
            confirmButton = {
                TextButton(onClick = {
                    showConfirmDialog = false
                    onDeleteStock()
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDialog = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun HoldingActionButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Icon(imageVector = icon, contentDescription = label, tint = color)
        Spacer(modifier = Modifier.height(2.dp))
        Text(text = label, color = color, style = MaterialTheme.typography.labelSmall)
    }
}

/** 滑动锚点：Closed=收起（offset 0），Open=左划露出操作按钮（负偏移）。 */
private enum class SwipeAnchor { Closed, Open }

/** 左划露出后保持的按钮区宽度。 */
private val ActionRevealWidth = 144.dp

/**
 * 自选股卡片（shares=0）。左划露出「编辑 / 删除」按钮，松手吸附后停在打开状态，
 * 直到点击别处或打开另一张卡片才收回。复用 StockCard（自选样式）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToDismissWatchItem(
    stock: StockEntity,
    forecastIncome: Double?,
    marketValue: Double?,
    currentPrice: Double?,
    latestYearlyDividend: Double?,
    onDismiss: () -> Unit,
    onClick: () -> Unit,
    onEdit: () -> Unit
) {
    var showConfirmDialog by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val revealPx = with(density) { ActionRevealWidth.toPx() }

    val state = remember(revealPx) {
        AnchoredDraggableState(
            initialValue = SwipeAnchor.Closed,
            anchors = DraggableAnchors {
                SwipeAnchor.Closed at 0f
                SwipeAnchor.Open at -revealPx
            }
        )
    }

    var isOpen by remember { mutableStateOf(false) }
    LaunchedEffect(isOpen) {
        if (!isOpen && state.settledValue == SwipeAnchor.Open) {
            state.animateTo(SwipeAnchor.Closed)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(end = 16.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            WatchActionButton(
                label = "编辑",
                icon = Icons.Default.Edit,
                color = MaterialTheme.colorScheme.primary,
                onClick = {
                    isOpen = false
                    onEdit()
                }
            )
            WatchActionButton(
                label = "删除",
                icon = Icons.Default.Delete,
                color = MaterialTheme.colorScheme.error,
                onClick = {
                    isOpen = false
                    showConfirmDialog = true
                }
            )
        }

        Box(
            modifier = Modifier
                .offset { IntOffset(state.offset.toInt(), 0) }
                .anchoredDraggable(
                    state = state,
                    orientation = Orientation.Horizontal
                )
        ) {
            StockCard(
                name = stock.name,
                code = stock.code,
                shares = stock.shares,
                forecastIncome = forecastIncome?.let { "¥${"%.2f".format(it)}" },
                marketValue = marketValue?.let { "¥${"%,.2f".format(it)}" },
                lastUpdated = stock.lastUpdated,
                currentPrice = currentPrice,
                latestYearlyDividend = latestYearlyDividend,
                onClick = onClick,
                isWatchOnly = true
            )
        }

        LaunchedEffect(state.settledValue) {
            val nowOpen = state.settledValue == SwipeAnchor.Open
            if (nowOpen != isOpen) isOpen = nowOpen
        }
    }

    if (showConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showConfirmDialog = false },
            title = { Text("确认删除") },
            text = { Text("确定要删除 ${stock.name} 吗？删除后可以撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showConfirmDialog = false
                        onDismiss()
                    }
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun WatchActionButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Icon(imageVector = icon, contentDescription = label, tint = color)
        Spacer(modifier = Modifier.height(2.dp))
        Text(text = label, color = color, style = MaterialTheme.typography.labelSmall)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun IndustryAllocationCard(
    group: IndustryGroup,
    onEditIndustry: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptics = LocalHapticFeedback.current
    Card(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {},
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onEditIndustry()
                }
            ),
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
                    Text(
                        text = "目标 ${portfolioFormatPercent(group.targetWeight)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
