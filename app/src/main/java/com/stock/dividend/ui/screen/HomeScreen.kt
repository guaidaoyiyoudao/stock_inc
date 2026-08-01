package com.stock.dividend.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stock.dividend.R
import com.stock.dividend.data.local.entity.StockEntity
import com.stock.dividend.ui.component.AppCardDefaults
import com.stock.dividend.ui.component.CategorizedAchievementList
import com.stock.dividend.ui.component.IncomeBreakdownChart
import com.stock.dividend.ui.component.IncomeSummaryCard
import com.stock.dividend.ui.component.IncomeTimelineCard
import com.stock.dividend.ui.component.IncomeTrendChart
import com.stock.dividend.ui.component.SectionHeader
import com.stock.dividend.ui.component.YearSelector
import com.stock.dividend.viewmodel.AchievementUiState
import com.stock.dividend.viewmodel.AchievementViewModel
import com.stock.dividend.viewmodel.DividendIncomeUiState
import com.stock.dividend.viewmodel.DividendIncomeViewModel
import com.stock.dividend.ui.component.AppTextButton
import com.stock.dividend.ui.component.AppTextField

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
    // 二级 Tab：0 = 收入记录，1 = 分红日历（原「日历」tab 合并至此）
    var selectedTab by remember { mutableIntStateOf(0) }
    val incomeTabs = listOf("收入", "日历")

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedTab) {
            incomeTabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = { Text(title) }
                )
            }
        }

        if (selectedTab == 0) {
            IncomeTabContent(
                state = state,
                viewModel = viewModel,
                onAddIncomeClick = { showAddIncomeDialog = true }
            )
        } else {
            // 原独立「日历」tab 内容；其内部的 registerTabRefresh 会在该视图激活时
            // 自动让悬浮刷新按钮显示。
            DividendCalendarScreen()
        }
    }

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
                        AppTextField(
                            value = correctAmount,
                            onValueChange = { correctAmount = it },
                            label = { Text("金额 (元)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxSize()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        AppTextField(
                            value = correctNote,
                            onValueChange = { correctNote = it },
                            label = { Text("备注 (可选)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                },
                confirmButton = {
                    AppTextButton(
                        onClick = {
                            val amount = correctAmount.toDoubleOrNull() ?: return@AppTextButton
                            viewModel.correctRecord(
                                state.correctTargetId,
                                amount,
                                correctNote.ifBlank { null }
                            )
                            showCorrectDialog = false
                        },
                        text = "确认",
                    )
                },
                dismissButton = {
                    AppTextButton(
                        onClick = {
                            viewModel.dismissCorrectDialog()
                            showCorrectDialog = false
                        },
                        text = "取消",
                    )
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
                AppTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = { Text("金额 (元)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                AppTextField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    label = { Text("备注 (可选)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            AppTextButton(
                onClick = {
                    val amount = amountText.toDoubleOrNull() ?: return@AppTextButton
                    onConfirm(today, amount, null, noteText.ifBlank { null })
                },
                text = "确认",
            )
        },
        dismissButton = {
            AppTextButton(onClick = onDismiss, text = "取消")
        }
    )
}
