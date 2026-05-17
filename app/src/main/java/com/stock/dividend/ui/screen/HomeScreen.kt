package com.stock.dividend.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.stock.dividend.R
import com.stock.dividend.data.local.entity.StockEntity
import com.stock.dividend.ui.component.AppCardDefaults
import com.stock.dividend.ui.component.CategorizedAchievementList
import com.stock.dividend.ui.component.DividendSummaryCard
import com.stock.dividend.ui.component.EmptyStateView
import com.stock.dividend.ui.component.FireProgressCard
import com.stock.dividend.ui.component.IncomeBreakdownChart
import com.stock.dividend.ui.component.IncomeSummaryCard
import com.stock.dividend.ui.component.IncomeTimelineCard
import com.stock.dividend.ui.component.IncomeTrendChart
import com.stock.dividend.ui.component.SectionHeader
import com.stock.dividend.ui.component.StockCard
import com.stock.dividend.ui.component.YearSelector
import com.stock.dividend.viewmodel.AchievementUiState
import com.stock.dividend.viewmodel.AchievementViewModel
import com.stock.dividend.viewmodel.DividendIncomeUiState
import com.stock.dividend.viewmodel.DividendIncomeViewModel
import com.stock.dividend.viewmodel.HomeViewModel
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchlistScreen(
    snackbarHostState: SnackbarHostState,
    onAddStockClick: () -> Unit,
    onStockClick: (String) -> Unit,
    onFireCardClick: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.onResume()
    }

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

    if (uiState.stocks.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            EmptyStateView(onAddClick = onAddStockClick)
        }
    } else {
        WatchlistContent(
            uiState = uiState,
            onAddStockClick = onAddStockClick,
            onStockClick = onStockClick,
            onFireCardClick = onFireCardClick,
            onDeleteStock = { viewModel.deleteStock(it) },
            onRefresh = { viewModel.refreshQuotes() }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IncomeScreen(
    viewModel: DividendIncomeViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showAddIncomeDialog by remember { mutableStateOf(false) }
    var showCorrectDialog by remember { mutableStateOf(false) }
    var correctAmount by remember { mutableStateOf("") }
    var correctNote by remember { mutableStateOf("") }

    IncomeTabContent(
        state = state,
        viewModel = viewModel,
        onAddIncomeClick = { showAddIncomeDialog = true }
    )

    if (showAddIncomeDialog) {
            AddIncomeDialog(
                stocks = state.stocks,
                onDismiss = { showAddIncomeDialog = false },
                onConfirm = { date, amount, stockCode, note ->
                    viewModel.addManualRecord(date, amount, stockCode, note)
                    showAddIncomeDialog = false
                }
            )
        }

        if (state.showCorrectDialog) {
            if (!showCorrectDialog) {
                showCorrectDialog = true
                correctAmount = "%.2f".format(state.correctCurrentAmount)
                correctNote = ""
            }
            AlertDialog(
                onDismissRequest = {
                    viewModel.dismissCorrectDialog()
                    showCorrectDialog = false
                },
                title = { Text("修正金额") },
                text = {
                    Column {
                        OutlinedTextField(
                            value = correctAmount,
                            onValueChange = { correctAmount = it },
                            label = { Text("金额 (元)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxSize()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = correctNote,
                            onValueChange = { correctNote = it },
                            label = { Text("备注 (可选)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val amount = correctAmount.toDoubleOrNull() ?: return@TextButton
                            viewModel.correctRecord(
                                state.correctTargetId,
                                amount,
                                correctNote.ifBlank { null }
                            )
                            showCorrectDialog = false
                        }
                    ) { Text("确认") }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            viewModel.dismissCorrectDialog()
                            showCorrectDialog = false
                        }
                    ) { Text("取消") }
                }
            )
        }
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AchievementScreen(
    viewModel: AchievementViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold { padding ->
            AchievementTabContent(
                state = state,
                modifier = Modifier.padding(padding)
            )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WatchlistContent(
    uiState: com.stock.dividend.viewmodel.HomeUiState,
    onAddStockClick: () -> Unit,
    onStockClick: (String) -> Unit,
    onFireCardClick: () -> Unit,
    onDeleteStock: (StockEntity) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    PullToRefreshBox(
        isRefreshing = uiState.isLoading,
        onRefresh = onRefresh,
        modifier = modifier
    ) {
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 12.dp,
                bottom = 88.dp
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                DividendSummaryCard(
                    totalAmount = uiState.forecastTotal,
                    totalMarketValue = uiState.totalMarketValue
                )
            }

            item {
                FireProgressCard(
                    targetAmount = uiState.livingExpenseTargetAmount,
                    forecastTotal = uiState.forecastTotal,
                    progress = uiState.fireProgress,
                    onClick = onFireCardClick
                )
            }

            item {
                HoldingsSectionHeader(
                    totalMarketValue = uiState.totalMarketValue,
                    onAddStockClick = onAddStockClick
                )
            }

            items(
                items = uiState.stocks,
                key = { it.code }
            ) { stock ->
                SwipeToDismissStockItem(
                    stock = stock,
                    forecastIncome = uiState.stockForecasts[stock.code]?.forecastIncome,
                    marketValue = uiState.stockForecasts[stock.code]?.marketValue,
                    onDismiss = { onDeleteStock(stock) },
                    onClick = { onStockClick(stock.code) }
                )
            }
        }
    }
}

@Composable
private fun HoldingsSectionHeader(
    totalMarketValue: Double?,
    onAddStockClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 6.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.watchlist_section_holdings),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold
        )

        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = formatHoldingsTotalMarketValue(totalMarketValue),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
}

@Composable
private fun IncomeTabContent(
    state: DividendIncomeUiState,
    viewModel: DividendIncomeViewModel,
    onAddIncomeClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        YearSelector(
            years = state.availableYears.ifEmpty { listOf(state.selectedYear) },
            selectedYear = state.selectedYear,
            onYearSelected = { viewModel.selectYear(it) },
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        Spacer(modifier = Modifier.height(8.dp))

        IncomeTrendChart(
            yearlyTotals = state.yearlyTotals,
            selectedYear = state.selectedYear,
            onYearClick = { viewModel.selectYear(it) },
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        Spacer(modifier = Modifier.height(8.dp))

        IncomeSummaryCard(
            year = state.selectedYear,
            totalAmount = state.yearlyTotal,
            prevYearTotal = state.prevYearTotal,
            manualCount = state.manualCount,
            autoCount = state.autoCount,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        Spacer(modifier = Modifier.height(8.dp))

        IncomeBreakdownChart(
            records = state.records,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        Spacer(modifier = Modifier.height(8.dp))

        SectionHeader(
            title = stringResource(R.string.income_section_records),
            actionText = stringResource(R.string.income_action_add),
            actionIcon = Icons.Default.Add,
            onActionClick = onAddIncomeClick,
            modifier = Modifier.padding(horizontal = AppCardDefaults.PageHorizontalPadding)
        )

        Spacer(modifier = Modifier.height(4.dp))

        if (state.records.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "暂无股息收入记录",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "分红到账后会自动记录",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(items = state.records, key = { it.record.id }) { item ->
                    IncomeTimelineCard(
                        date = item.record.date,
                        stockName = item.stockName,
                        amount = item.record.amount,
                        source = item.record.source,
                        exDividendDate = item.record.exDividendDate,
                        note = item.record.note,
                        onCorrect = {
                            viewModel.showCorrectDialog(item.record.id, item.record.amount)
                        },
                        onEdit = {
                            viewModel.showCorrectDialog(item.record.id, item.record.amount)
                        },
                        onDelete = {
                            viewModel.deleteManualRecord(item.record.id)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun AchievementTabContent(
    state: AchievementUiState,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            text = "${state.unlockedCount}/${state.totalCount} 已达成",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        CategorizedAchievementList(
            achievements = state.achievements,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun AddIncomeDialog(
    stocks: List<StockEntity>,
    onDismiss: () -> Unit,
    onConfirm: (date: String, amount: Double, stockCode: String?, note: String?) -> Unit
) {
    var amountText by remember { mutableStateOf("") }
    var noteText by remember { mutableStateOf("") }
    val today = remember {
        java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加收入") },
        text = {
            Column {
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = { Text("金额 (元)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    label = { Text("备注 (可选)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val amount = amountText.toDoubleOrNull() ?: return@TextButton
                    onConfirm(today, amount, null, noteText.ifBlank { null })
                }
            ) { Text("确认") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

internal fun formatHoldingsTotalMarketValue(value: Double?): String {
    return "总市值 ¥${"%,.2f".format(Locale.US, value ?: 0.0)}"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToDismissStockItem(
    stock: StockEntity,
    forecastIncome: Double? = null,
    marketValue: Double? = null,
    onDismiss: () -> Unit,
    onClick: () -> Unit
) {
    var showConfirmDialog by remember { mutableStateOf(false) }
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = {
            if (it == SwipeToDismissBoxValue.EndToStart) {
                showConfirmDialog = true
                false
            } else false
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            val isSwiping = dismissState.currentValue != SwipeToDismissBoxValue.Settled
            val color by animateColorAsState(
                when (dismissState.targetValue) {
                    SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.errorContainer
                    else -> Color.Transparent
                }, label = "bg"
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                if (isSwiping) {
                    Text(
                        text = "删除",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        },
        enableDismissFromStartToEnd = false
    ) {
        StockCard(
            name = stock.name,
            code = stock.code,
            shares = stock.shares,
            forecastIncome = forecastIncome?.let { "¥${"%.2f".format(it)}" },
            marketValue = marketValue?.let { "¥${"%,.2f".format(it)}" },
            lastUpdated = stock.lastUpdated,
            onClick = onClick
        )
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
