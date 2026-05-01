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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults.SecondaryIndicator
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.stock.dividend.data.local.entity.StockEntity
import com.stock.dividend.ui.component.DividendSummaryCard
import com.stock.dividend.ui.component.EmptyStateView
import com.stock.dividend.ui.component.FireProgressCard
import com.stock.dividend.ui.component.IncomeSummaryCard
import com.stock.dividend.ui.component.IncomeTimelineCard
import com.stock.dividend.ui.component.IncomeTrendChart
import com.stock.dividend.ui.component.StockCard
import com.stock.dividend.ui.component.YearSelector
import com.stock.dividend.viewmodel.DividendIncomeUiState
import com.stock.dividend.viewmodel.DividendIncomeViewModel
import com.stock.dividend.viewmodel.HomeViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onAddStockClick: () -> Unit,
    onStockClick: (String) -> Unit,
    onFireCardClick: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
    incomeViewModel: DividendIncomeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val incomeState by incomeViewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    var selectedTabIndex by remember { mutableStateOf(0) }

    // Dialog state
    var showAddIncomeDialog by remember { mutableStateOf(false) }
    var showCorrectDialog by remember { mutableStateOf(false) }
    var correctAmount by remember { mutableStateOf("") }
    var correctNote by remember { mutableStateOf("") }
    var addAmount by remember { mutableStateOf("") }
    var addNote by remember { mutableStateOf("") }

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "我的股息",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                scrollBehavior = scrollBehavior
            )
        },
        floatingActionButton = {
            AnimatedVisibility(
                visible = when (selectedTabIndex) {
                    0 -> uiState.stocks.isNotEmpty()
                    1 -> true
                    else -> false
                },
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                ExtendedFloatingActionButton(
                    onClick = {
                        if (selectedTabIndex == 0) onAddStockClick()
                        else showAddIncomeDialog = true
                    },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = MaterialTheme.shapes.large,
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = {
                        Text(
                            if (selectedTabIndex == 0) "添加股票" else "添加收入",
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Tab row - only show when stocks exist
            if (uiState.stocks.isNotEmpty()) {
                TabRow(
                    selectedTabIndex = selectedTabIndex,
                    indicator = { tabPositions ->
                        SecondaryIndicator(
                            Modifier.tabIndicatorOffset(tabPositions[selectedTabIndex])
                        )
                    }
                ) {
                    Tab(
                        selected = selectedTabIndex == 0,
                        onClick = { selectedTabIndex = 0 },
                        text = { Text("持仓列表", style = MaterialTheme.typography.labelLarge) }
                    )
                    Tab(
                        selected = selectedTabIndex == 1,
                        onClick = { selectedTabIndex = 1 },
                        text = { Text("股息收入", style = MaterialTheme.typography.labelLarge) }
                    )
                }
            }

            when (selectedTabIndex) {
                0 -> {
                    if (uiState.stocks.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            EmptyStateView(onAddClick = onAddStockClick)
                        }
                    } else {
                        WatchlistContent(
                            uiState = uiState,
                            onStockClick = onStockClick,
                            onFireCardClick = onFireCardClick,
                            onDeleteStock = { viewModel.deleteStock(it) },
                            onRefresh = { viewModel.refreshQuotes() },
                            scrollBehavior = scrollBehavior
                        )
                    }
                }
                1 -> {
                    IncomeTabContent(
                        state = incomeState,
                        viewModel = incomeViewModel
                    )
                }
            }
        }

        // Add Income Dialog
        if (showAddIncomeDialog) {
            AddIncomeDialog(
                stocks = incomeState.stocks,
                onDismiss = {
                    showAddIncomeDialog = false
                    addAmount = ""
                    addNote = ""
                },
                onConfirm = { date, amount, stockCode, note ->
                    incomeViewModel.addManualRecord(date, amount, stockCode, note)
                    showAddIncomeDialog = false
                    addAmount = ""
                    addNote = ""
                }
            )
        }

        // Correct Record Dialog
        if (incomeState.showCorrectDialog) {
            if (!showCorrectDialog) {
                showCorrectDialog = true
                correctAmount = "%.2f".format(incomeState.correctCurrentAmount)
                correctNote = ""
            }
            AlertDialog(
                onDismissRequest = {
                    incomeViewModel.dismissCorrectDialog()
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
                            incomeViewModel.correctRecord(
                                incomeState.correctTargetId,
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
                            incomeViewModel.dismissCorrectDialog()
                            showCorrectDialog = false
                        }
                    ) { Text("取消") }
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WatchlistContent(
    uiState: com.stock.dividend.viewmodel.HomeUiState,
    onStockClick: (String) -> Unit,
    onFireCardClick: () -> Unit,
    onDeleteStock: (StockEntity) -> Unit,
    onRefresh: () -> Unit,
    scrollBehavior: TopAppBarScrollBehavior?
) {
    PullToRefreshBox(
        isRefreshing = uiState.isLoading,
        onRefresh = onRefresh,
        modifier = Modifier.nestedScroll(scrollBehavior?.nestedScrollConnection!!)
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
                FireProgressCard(
                    targetAmount = uiState.fireGoal?.targetAmount,
                    forecastTotal = uiState.forecastTotal,
                    progress = uiState.fireProgress,
                    onClick = onFireCardClick
                )
            }

            item {
                DividendSummaryCard(
                    totalAmount = uiState.forecastTotal,
                    totalMarketValue = uiState.totalMarketValue
                )
            }

            item {
                Text(
                    text = "持仓列表",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
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
private fun IncomeTabContent(
    state: DividendIncomeUiState,
    viewModel: DividendIncomeViewModel
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Year selector
        YearSelector(
            years = state.availableYears.ifEmpty { listOf(state.selectedYear) },
            selectedYear = state.selectedYear,
            onYearSelected = { viewModel.selectYear(it) },
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Trend chart
        IncomeTrendChart(
            yearlyTotals = state.yearlyTotals,
            selectedYear = state.selectedYear,
            onYearClick = { viewModel.selectYear(it) },
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Summary card
        IncomeSummaryCard(
            year = state.selectedYear,
            totalAmount = state.yearlyTotal,
            prevYearTotal = state.prevYearTotal,
            manualCount = state.manualCount,
            autoCount = state.autoCount,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Timeline list or empty state
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
                    modifier = Modifier.fillMaxSize()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    label = { Text("备注 (可选)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxSize()
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToDismissStockItem(
    stock: StockEntity,
    forecastIncome: Double? = null,
    marketValue: Double? = null,
    onDismiss: () -> Unit,
    onClick: () -> Unit
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = {
            if (it == SwipeToDismissBoxValue.EndToStart) {
                onDismiss()
                true
            } else false
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
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
                Text(
                    text = "删除",
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.labelSmall
                )
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
}
