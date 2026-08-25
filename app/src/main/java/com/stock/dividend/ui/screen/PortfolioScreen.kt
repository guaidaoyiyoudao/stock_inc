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
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import androidx.compose.material.icons.filled.Paid
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import com.stock.dividend.ui.component.AmountText
import com.stock.dividend.ui.component.AppCard
import com.stock.dividend.ui.component.AppCardDefaults
import com.stock.dividend.ui.component.AppOutlinedButton
import com.stock.dividend.ui.component.BollPriceScale
import com.stock.dividend.ui.component.CompanyIcon
import com.stock.dividend.ui.component.CostPriceScale
import com.stock.dividend.ui.component.DividendPriceScale
import com.stock.dividend.ui.component.DividendSummaryCard
import com.stock.dividend.ui.component.EmptyStateView
import com.stock.dividend.ui.component.FireProgressCard
import com.stock.dividend.ui.component.PercentText
import com.stock.dividend.ui.component.SkeletonList
import com.stock.dividend.ui.component.StockCard
import com.stock.dividend.ui.component.FinanceStatusTone
import com.stock.dividend.ui.component.StatusPill
import com.stock.dividend.ui.theme.LocalExtendedColors
import com.stock.dividend.ui.theme.Motion
import com.stock.dividend.ui.theme.tabularNumberStyle
import nl.dionsegijn.konfetti.compose.KonfettiView
import nl.dionsegijn.konfetti.core.Party
import nl.dionsegijn.konfetti.core.Position
import nl.dionsegijn.konfetti.core.emitter.Emitter
import java.util.concurrent.TimeUnit
import com.stock.dividend.data.local.entity.StockEntity
import com.stock.dividend.data.repository.BollBand
import com.stock.dividend.data.repository.MoneyFormatter
import com.stock.dividend.data.repository.PercentFormatter
import com.stock.dividend.data.repository.QuoteSnapshot
import com.stock.dividend.viewmodel.PortfolioItem
import com.stock.dividend.viewmodel.PortfolioUiState
import com.stock.dividend.viewmodel.PortfolioViewModel
import com.stock.dividend.ui.component.AppTextButton
import com.stock.dividend.ui.component.AppTextField

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortfolioScreen(
    snackbarHostState: SnackbarHostState,
    onAddStockClick: () -> Unit,
    onStockClick: (String) -> Unit,
    onEditStock: (String) -> Unit,
    onImportFromScreenshot: () -> Unit,
    onFireCardClick: () -> Unit = {},
    onNavigateToEvaluation: () -> Unit = {},
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
        // 首次加载（无任何数据 + loading）显示骨架屏；确认为空才显示空状态
        if (uiState.isLoading) {
            SkeletonList(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(AppCardDefaults.PageHorizontalPadding),
                cardCount = 4,
            )
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyStateView(onAddClick = onAddStockClick)
            }
        }
        return
    }

    var holdingsExpanded by remember { mutableStateOf(true) }

    // FIRE 达标撒花：覆盖率从 <100% 跨到 ≥100% 的瞬间庆祝一次（每次会话仅一次，刷新不重复）。
    // celebrated 用 rememberSaveable 跨组合重建/切 Tab 保留（导航返回栈条目内）——
    // 修复此前 plain remember 切 Tab 回来即复位、已达 100% 用户每次进持仓页重复撒花（2026-08-24 评审修复）
    var fireCelebration by remember { mutableStateOf(false) }
    var fireCelebrated by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(uiState.fireProgress) {
        val p = uiState.fireProgress
        if (p != null && p >= 100f && !fireCelebrated) {
            fireCelebrated = true
            fireCelebration = true
        }
    }
    LaunchedEffect(fireCelebration) {
        if (fireCelebration) {
            kotlinx.coroutines.delay(4000)
            fireCelebration = false
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
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
                    totalMarketValue = uiState.holdingsMarketValue,
                    costDividendYield = uiState.costDividendYield
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
                    totalRealizedPnl = uiState.totalRealizedPnl,
                    totalRealizedPnlRate = uiState.totalRealizedPnlRate,
                    targetWeightSum = uiState.industryTargetSum,
                    targetWeightLabel = "行业目标合计",
                    onEditTotalAssets = viewModel::showEditTotalAssetsDialog,
                    onImportFromScreenshot = onImportFromScreenshot
                )
            }
            // 顶部筛选条：行业 + 标签（组内 OR、跨组 AND）
            item {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (uiState.availableIndustries.isNotEmpty()) {
                            IndustryDropdown(
                                options = uiState.availableIndustries,
                                selected = uiState.selectedIndustries.firstOrNull(),
                                onSelect = viewModel::setIndustryFilter,
                                label = "行业",
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (uiState.availableTags.isNotEmpty()) {
                            TagDropdown(
                                options = uiState.availableTags,
                                selected = uiState.selectedTags.firstOrNull(),
                                onSelect = viewModel::setTagFilter,
                                label = "标签",
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
            // 行业配置区块已移除（行业目标数据层仍保留以备复用）
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
                    // 一键评估入口
                    AppTextButton(
                        onClick = {
                            viewModel.evaluateVisibleHoldings()
                            onNavigateToEvaluation()
                        },
                        enabled = !uiState.isEvaluating && uiState.filteredItems.isNotEmpty()
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Analytics,
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "一键评估",
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                    // 添加股票入口（来自原自选 tab）
                    AppTextButton(onClick = onAddStockClick) {
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
                items(items = uiState.filteredItems, key = { it.code }) { item ->
                    SwipeToDismissHoldingItem(
                        item = item,
                        onClick = { onStockClick(item.code) },
                        onEditWeight = { viewModel.showEditWeightDialog(item.code, item.targetWeight) },
                        onEditStock = { onEditStock(item.code) },
                        onDeleteStock = { viewModel.deleteStock(item.code) },
                        latestYearlyDividend = uiState.stockForecasts[item.code]?.latestYearlyDividend,
                        bollBand = uiState.stockBands[item.code],
                        onLoadBoll = { viewModel.loadBoll(item.code) },
                        quote = uiState.stockQuotes[item.code]
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
                items(items = uiState.filteredWatchlist, key = { it.code }) { stock ->
                    SwipeToDismissWatchItem(
                        stock = stock,
                        forecastIncome = uiState.stockForecasts[stock.code]?.forecastIncome,
                        marketValue = uiState.stockForecasts[stock.code]?.marketValue,
                        currentPrice = uiState.stockForecasts[stock.code]?.currentPrice,
                        latestYearlyDividend = uiState.stockForecasts[stock.code]?.latestYearlyDividend,
                        changePct = uiState.stockQuotes[stock.code]?.changePct,
                        bollBand = uiState.stockBands[stock.code],
                        onLoadBoll = { viewModel.loadBoll(stock.code) },
                        onDismiss = { viewModel.deleteStock(stock) },
                        onClick = { onStockClick(stock.code) },
                        onEdit = { onEditStock(stock.code) }
                    )
                }
            }
        }
    }

    // FIRE 达标庆祝：全屏 Konfetti 覆盖层（粒子自行消亡，4s 后移除覆盖层）
    if (fireCelebration) {
        KonfettiView(
            modifier = Modifier.fillMaxSize(),
            parties = remember {
                listOf(
                    Party(
                        speed = 25f,
                        maxSpeed = 50f,
                        damping = 0.9f,
                        spread = 90,
                        angle = 270,
                        position = Position.Relative(0.2, 1.0),
                        emitter = Emitter(duration = 500, TimeUnit.MILLISECONDS).max(60),
                        colors = listOf(0xfce18a, 0xff726d, 0xb48def, 0xf4306d),
                    ),
                    Party(
                        speed = 25f,
                        maxSpeed = 50f,
                        damping = 0.9f,
                        spread = 90,
                        angle = 270,
                        position = Position.Relative(0.8, 1.0),
                        emitter = Emitter(duration = 500, TimeUnit.MILLISECONDS).max(60),
                        colors = listOf(0xfce18a, 0xff726d, 0xb48def, 0xf4306d),
                    ),
                )
            },
        )
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
}

@Composable
private fun PortfolioSummaryCard(
    totalAssets: Double,
    holdingsMarketValue: Double,
    totalCost: Double,
    totalPnl: Double,
    totalPnlRate: Double,
    totalRealizedPnl: Double?,
    totalRealizedPnlRate: Double?,
    targetWeightSum: Double,
    targetWeightLabel: String = "目标权重合计",
    onEditTotalAssets: () -> Unit,
    onImportFromScreenshot: () -> Unit,
    modifier: Modifier = Modifier
) {
    val pnlColor = pnlColor(totalPnl)

    AppCard(
        modifier = modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
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
                        if (totalAssets > 0.0) {
                            AmountText(
                                value = totalAssets,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                colored = false,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        } else {
                            Text(
                                text = "点击设置",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
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
            AmountText(
                value = holdingsMarketValue,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                colored = false,
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
                    valueContent = {
                        AmountText(
                            value = totalCost,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            colored = false,
                        )
                    },
                    modifier = Modifier.weight(1f)
                )
                VerticalDivider()
                SummaryMetric(
                    label = "浮盈/浮亏",
                    valueContent = {
                        AmountText(
                            value = totalPnl,
                            signed = true,
                            colored = false,
                            color = pnlColor,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        )
                    },
                    modifier = Modifier.weight(1f)
                )
                VerticalDivider()
                SummaryMetric(
                    label = "盈亏率",
                    valueContent = {
                        PercentText(
                            value = totalPnlRate * 100.0,
                            signed = true,
                            decimals = 1,
                            colored = false,
                            color = pnlColor,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        )
                    },
                    modifier = Modifier.weight(1f)
                )
            }

            // 已实现盈亏（FIFO）：仅在有卖出记录时展示，区分于上方的浮动盈亏。
            if (totalRealizedPnl != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "累计已实现盈亏",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AmountText(
                            value = totalRealizedPnl,
                            signed = true,
                            colored = false,
                            color = pnlColor(totalRealizedPnl),
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        )
                        totalRealizedPnlRate?.let { rate ->
                            Spacer(modifier = Modifier.width(4.dp))
                            PercentText(
                                value = rate,
                                signed = true,
                                decimals = 1,
                                colored = false,
                                color = pnlColor(totalRealizedPnl),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }

            if (targetWeightSum > 0.0 && !targetWeightSum.isApproximately(100.0)) {
                Spacer(modifier = Modifier.height(10.dp))
                StatusPill(
                    text = "$targetWeightLabel ${portfolioFormatPercent(targetWeightSum)}，未达 100%",
                    tone = if (targetWeightSum < 100.0) FinanceStatusTone.Warning else FinanceStatusTone.Neutral
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            AppOutlinedButton(
                onClick = onImportFromScreenshot,
                modifier = Modifier.fillMaxWidth(),
                text = "📷 从截图导入持仓",
            )
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
    bollBand: BollBand?,
    onLoadBoll: () -> Unit,
    quote: QuoteSnapshot?,
    modifier: Modifier = Modifier
) {
    val haptics = LocalHapticFeedback.current
    // 每张卡片独立循环切换「股息率 ↔ BOLL ↔ 成本现价」，仅内存状态（不持久化）。
    // ⚠️ 不挂 sharedBounds：共享元素的 approachMeasure 会用转场时刻的 bounds 固定约束
    // 钳制内容（Constraints.fixed），BOLL 懒加载两段变高时底部会被锁在外面。
    var axisMode by remember(item.code) { mutableStateOf(0) }
    LaunchedEffect(axisMode) {
        if (axisMode == AXIS_BOLL) onLoadBoll()
    }
    AppCard(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onEditWeight()
                }
            ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        // animateContentSize：BOLL 懒加载二段变高（占位 54dp → 数据 ~150dp）时整卡平滑展开
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(tween(Motion.DurationMedium, easing = Motion.Standard))
                .padding(14.dp)
        ) {
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
                    if (item.marketValue != null) {
                        AmountText(
                            value = item.marketValue,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            colored = false,
                        )
                    } else {
                        Text(
                            text = "—",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    val pnl = item.unrealizedPnl
                    val rate = item.unrealizedPnlRate
                    if (pnl != null && rate != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            AmountText(
                                value = pnl,
                                signed = true,
                                colored = true,
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            )
                            PercentText(
                                value = rate * 100.0,
                                signed = true,
                                decimals = 1,
                                colored = true,
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            )
                        }
                    } else {
                        Text(
                            text = "—",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // 权重/目标信息常驻行（成本/现价/PE/已实现明细已并入第三视图 CostPriceScale）
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = weightRow(item),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "目标占行业 ${portfolioFormatPercent(item.targetWeight)}" +
                        item.targetValue?.let { " · 目标金额 ${portfolioFormatMoney(it)}" }.orEmpty(),
                    style = MaterialTheme.typography.labelSmall.merge(tabularNumberStyle),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 坐标轴切换按钮：循环 股息率 → BOLL → 成本现价
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.End
            ) {
                IconButton(onClick = { axisMode = (axisMode + 1) % 3 }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = when (axisMode) {
                            AXIS_BOLL -> Icons.Default.ShowChart
                            AXIS_COST -> Icons.Default.Paid
                            else -> Icons.Default.TrendingUp
                        },
                        contentDescription = when (axisMode) {
                            AXIS_DIVIDEND -> "切换到 BOLL 横轴"
                            AXIS_BOLL -> "切换到成本现价横轴"
                            else -> "切换到股息率横轴"
                        },
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // 横轴主体三选一（滑动+淡入切换；clip=false 防 BOLL 懒加载两段高度时底部被裁）
            AnimatedContent(
                targetState = axisMode,
                label = "axisSwitch",
                transitionSpec = {
                    ContentTransform(
                        targetContentEnter = slideInHorizontally(
                            animationSpec = tween(Motion.DurationShort, easing = Motion.EmphasizedDecelerate),
                            initialOffsetX = { it / 4 },
                        ) + fadeIn(tween(Motion.DurationShort)),
                        initialContentExit = slideOutHorizontally(
                            animationSpec = tween(Motion.DurationShort, easing = Motion.EmphasizedAccelerate),
                            targetOffsetX = { -it / 4 },
                        ) + fadeOut(tween(Motion.DurationShort)),
                        sizeTransform = SizeTransform(clip = false),
                    )
                },
            ) { mode ->
                when (mode) {
                    AXIS_BOLL -> BollPriceScale(
                        currentPrice = item.currentPrice,
                        band = bollBand,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                    AXIS_COST -> CostPriceScale(
                        costPrice = item.costPerShare,
                        currentPrice = item.currentPrice,
                        unrealizedPnl = item.unrealizedPnl,
                        unrealizedPnlRate = item.unrealizedPnlRate,
                        realizedPnl = item.realizedPnl,
                        quote = quote,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                    else -> DividendPriceScale(
                        currentPrice = item.currentPrice,
                        latestYearlyDividend = latestYearlyDividend,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
    }
}

/** 持仓卡横轴模式：0=股息率 1=周线BOLL 2=成本现价。 */
private const val AXIS_DIVIDEND = 0
private const val AXIS_BOLL = 1
private const val AXIS_COST = 2

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

/** SummaryMetric 的 Composable 值重载（传入 AmountText/PercentText 获得数字滚动效果）。 */
@Composable
private fun SummaryMetric(
    label: String,
    valueContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
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
        valueContent()
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
                AppTextField(
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
            AppTextButton(
                onClick = onConfirm,
                text = "保存",
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
                AppTextField(
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
            AppTextButton(
                onClick = onConfirm,
                text = "保存",
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

private fun weightRow(item: PortfolioItem): String {
    val actual = item.actualWeight
    return if (actual != null) "实际占比 ${portfolioFormatPercent(actual)}" else "实际占比 —"
}

@Composable
private fun pnlColor(value: Double): androidx.compose.ui.graphics.Color {
    val ext = LocalExtendedColors.current
    return when {
        value > 0.0 -> ext.positive
        value < 0.0 -> ext.negative
        else -> MaterialTheme.colorScheme.onSurface
    }
}

private fun marketPrefix(marketCode: String): String =
    if (marketCode == "1") "SH" else "SZ"

private fun codeSuffix(code: String): String = code.substringAfter(".")

internal fun portfolioFormatMoney(value: Double): String = MoneyFormatter.withSymbol(value)

internal fun portfolioFormatSignedPnl(value: Double): String = MoneyFormatter.withSign(value)

internal fun portfolioFormatPercent(value: Double): String = PercentFormatter.percent(value, decimals = 1)

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
    latestYearlyDividend: Double?,
    bollBand: BollBand?,
    onLoadBoll: () -> Unit,
    quote: QuoteSnapshot?,
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
            HoldingActionButton(
                label = "编辑",
                icon = Icons.Default.Edit,
                color = MaterialTheme.colorScheme.primary,
                onClick = {
                    isOpen = false
                    onEditStock()
                }
            )
            HoldingActionButton(
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
            PortfolioHoldingCard(
                item = item,
                onClick = onClick,
                onEditWeight = onEditWeight,
                latestYearlyDividend = latestYearlyDividend,
                bollBand = bollBand,
                onLoadBoll = onLoadBoll,
                quote = quote
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
            text = { Text("确定要从持仓移除 ${item.name} 吗？") },
            confirmButton = {
                AppTextButton(onClick = {
                    showConfirmDialog = false
                    onDeleteStock()
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                AppTextButton(
                    onClick = { showConfirmDialog = false },
                    text = "取消",
                )
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
    changePct: Double?,
    bollBand: BollBand?,
    onLoadBoll: () -> Unit,
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
                forecastIncomeAmount = forecastIncome,
                marketValueAmount = marketValue,
                lastUpdated = stock.lastUpdated,
                currentPrice = currentPrice,
                latestYearlyDividend = latestYearlyDividend,
                changePct = changePct,
                bollBand = bollBand,
                onLoadBoll = onLoadBoll,
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
                AppTextButton(
                    onClick = {
                        showConfirmDialog = false
                        onDismiss()
                    }
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                AppTextButton(
                    onClick = { showConfirmDialog = false },
                    text = "取消",
                )
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

/**
 * 单选下拉框（行业 / 标签筛选共用）。
 * - selected 为 null → 显示「全部」占位。
 * - 下拉首项为「全部」，点击清空；其余项单选。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SingleSelectDropdown(
    options: List<String>,
    selected: String?,
    onSelect: (String?) -> Unit,
    label: String,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        AppTextField(
            value = selected ?: "全部",
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            colors = OutlinedTextFieldDefaults.colors(
                disabledTextColor = MaterialTheme.colorScheme.onSurface
            ),
            textStyle = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            DropdownMenuItem(
                text = { Text("全部") },
                onClick = {
                    onSelect(null)
                    expanded = false
                }
            )
            options.forEach { opt ->
                DropdownMenuItem(
                    text = { Text(opt) },
                    onClick = {
                        onSelect(opt)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun IndustryDropdown(
    options: List<String>,
    selected: String?,
    onSelect: (String?) -> Unit,
    label: String,
    modifier: Modifier = Modifier
) = SingleSelectDropdown(options, selected, onSelect, label, modifier)

@Composable
private fun TagDropdown(
    options: List<String>,
    selected: String?,
    onSelect: (String?) -> Unit,
    label: String,
    modifier: Modifier = Modifier
) = SingleSelectDropdown(options, selected, onSelect, label, modifier)

