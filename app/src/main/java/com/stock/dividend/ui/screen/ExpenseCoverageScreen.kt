package com.stock.dividend.ui.screen

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stock.dividend.R
import com.stock.dividend.ui.component.CompactTopAppBar
import com.stock.dividend.viewmodel.CoverageStatus
import com.stock.dividend.viewmodel.ExpenseCoverageRow
import com.stock.dividend.viewmodel.ExpenseCoverageUiState
import com.stock.dividend.viewmodel.ExpenseCoverageViewModel
import com.stock.dividend.viewmodel.ExpensePeriod

@Composable
fun ExpenseCoverageScreen(
    onBack: () -> Unit,
    onGoSetup: () -> Unit,
    viewModel: ExpenseCoverageViewModel = hiltViewModel()
) {
    @Suppress("UNUSED_VARIABLE")
    val retainedForRouteCompatibility = onGoSetup
    val state = viewModel.uiState.collectAsStateWithLifecycle().value
    ExpenseCoverageContent(
        state = state,
        onBack = onBack,
        onAddExpense = viewModel::showAddDialog,
        onMoveUp = viewModel::moveExpenseUp,
        onMoveDown = viewModel::moveExpenseDown,
        onEdit = viewModel::showEditDialog,
        onDelete = viewModel::deleteExpense,
        onDismissDialog = viewModel::dismissDialog,
        onNameChanged = viewModel::onExpenseNameChanged,
        onAmountChanged = viewModel::onExpenseAmountChanged,
        onPeriodChanged = viewModel::onExpensePeriodChanged,
        onSaveExpense = viewModel::saveExpense
    )
}

@Composable
private fun ExpenseCoverageContent(
    state: ExpenseCoverageUiState,
    onBack: () -> Unit,
    onAddExpense: () -> Unit,
    onMoveUp: (Long) -> Unit,
    onMoveDown: (Long) -> Unit,
    onEdit: (ExpenseCoverageRow) -> Unit,
    onDelete: (Long) -> Unit,
    onDismissDialog: () -> Unit,
    onNameChanged: (String) -> Unit,
    onAmountChanged: (String) -> Unit,
    onPeriodChanged: (ExpensePeriod) -> Unit,
    onSaveExpense: () -> Unit
) {
    Scaffold(
        topBar = {
            CompactTopAppBar(
                title = stringResource(R.string.expense_coverage_title),
                onBack = onBack
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddExpense,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.expense_coverage_action_add_expense)) }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    text = stringResource(R.string.expense_coverage_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            item { SummaryCard(state) }
            if (state.rows.isEmpty()) {
                item { EmptyExpenseCard(onAddExpense = onAddExpense) }
            } else {
                itemsIndexed(state.rows, key = { _, row -> row.id }) { index, row ->
                    ExpenseRowCard(
                        row = row,
                        canMoveUp = index > 0,
                        canMoveDown = index < state.rows.lastIndex,
                        onMoveUp = { onMoveUp(row.id) },
                        onMoveDown = { onMoveDown(row.id) },
                        onEdit = { onEdit(row) },
                        onDelete = { onDelete(row.id) }
                    )
                }
            }
        }
    }

    if (state.showExpenseDialog) {
        ExpenseDialog(
            state = state,
            onDismiss = onDismissDialog,
            onNameChanged = onNameChanged,
            onAmountChanged = onAmountChanged,
            onPeriodChanged = onPeriodChanged,
            onSave = onSaveExpense
        )
    }
}

@Composable
private fun SummaryCard(state: ExpenseCoverageUiState) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = stringResource(R.string.expense_coverage_status_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "%.1f%%".format(state.coverageRatio * 100),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            LinearProgressIndicator(
                progress = { state.coverageRatio.coerceIn(0.0, 1.0).toFloat() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
            MetricRow(
                stringResource(R.string.expense_coverage_metric_forecast_income),
                formatMoney(state.forecastAnnualDividendIncome)
            )
            MetricRow(
                stringResource(R.string.expense_coverage_metric_total_expense),
                formatMoney(state.totalAnnualExpense)
            )
            MetricRow(
                stringResource(R.string.expense_coverage_metric_covered_items),
                "${state.coveredItemCount}/${state.rows.size}"
            )
            Text(
                text = coverageStatusText(state),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun coverageStatusText(state: ExpenseCoverageUiState): String =
    when {
        state.forecastAnnualDividendIncome <= 0.0 -> stringResource(R.string.expense_coverage_no_income)
        state.currentCoveringItemName != null -> {
            stringResource(R.string.expense_coverage_current_item, state.currentCoveringItemName)
        }
        state.rows.isNotEmpty() -> stringResource(R.string.expense_coverage_all_covered)
        else -> stringResource(R.string.expense_coverage_no_income)
    }

@Composable
private fun MetricRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun EmptyExpenseCard(onAddExpense: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.expense_coverage_empty_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(R.string.expense_coverage_empty_message),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(onClick = onAddExpense) {
                Text(text = stringResource(R.string.expense_coverage_action_add_expense))
            }
        }
    }
}

@Composable
private fun ExpenseRowCard(
    row: ExpenseCoverageRow,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = row.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = formatPeriodAmount(row.amount, row.period),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(R.string.expense_coverage_annual_amount, row.annualAmount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row {
                    IconButton(onClick = onMoveUp, enabled = canMoveUp) {
                        Icon(
                            Icons.Default.KeyboardArrowUp,
                            contentDescription = stringResource(R.string.expense_coverage_action_move_up)
                        )
                    }
                    IconButton(onClick = onMoveDown, enabled = canMoveDown) {
                        Icon(
                            Icons.Default.KeyboardArrowDown,
                            contentDescription = stringResource(R.string.expense_coverage_action_move_down)
                        )
                    }
                }
            }
            Text(
                text = stringResource(statusStringRes(row.status)),
                style = MaterialTheme.typography.labelLarge,
                color = statusColor(row.status),
                fontWeight = FontWeight.SemiBold
            )
            MetricRow(
                stringResource(R.string.expense_coverage_covered_amount, row.coveredAmount),
                stringResource(R.string.expense_coverage_gap_amount, row.gapAmount)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = null)
                    Text(stringResource(R.string.expense_coverage_action_edit))
                }
                TextButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = null)
                    Text(stringResource(R.string.expense_coverage_action_delete))
                }
            }
        }
    }
}

@Composable
private fun statusColor(status: CoverageStatus) =
    when (status) {
        CoverageStatus.COVERED -> MaterialTheme.colorScheme.primary
        CoverageStatus.PARTIAL -> MaterialTheme.colorScheme.tertiary
        CoverageStatus.UNCOVERED -> MaterialTheme.colorScheme.error
    }

@StringRes
private fun statusStringRes(status: CoverageStatus): Int =
    when (status) {
        CoverageStatus.COVERED -> R.string.expense_coverage_status_covered
        CoverageStatus.PARTIAL -> R.string.expense_coverage_status_partial
        CoverageStatus.UNCOVERED -> R.string.expense_coverage_status_uncovered
    }

@Composable
private fun ExpenseDialog(
    state: ExpenseCoverageUiState,
    onDismiss: () -> Unit,
    onNameChanged: (String) -> Unit,
    onAmountChanged: (String) -> Unit,
    onPeriodChanged: (ExpensePeriod) -> Unit,
    onSave: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(
                    if (state.editingExpenseId == null) {
                        R.string.expense_coverage_dialog_add_title
                    } else {
                        R.string.expense_coverage_dialog_edit_title
                    }
                )
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = state.expenseNameInput,
                    onValueChange = onNameChanged,
                    label = { Text(stringResource(R.string.expense_coverage_name_label)) },
                    singleLine = true
                )
                OutlinedTextField(
                    value = state.expenseAmountInput,
                    onValueChange = onAmountChanged,
                    label = { Text(stringResource(R.string.expense_coverage_amount_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = state.expensePeriodInput == ExpensePeriod.MONTHLY,
                        onClick = { onPeriodChanged(ExpensePeriod.MONTHLY) },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                    ) {
                        Text(stringResource(R.string.expense_coverage_period_monthly))
                    }
                    SegmentedButton(
                        selected = state.expensePeriodInput == ExpensePeriod.YEARLY,
                        onClick = { onPeriodChanged(ExpensePeriod.YEARLY) },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                    ) {
                        Text(stringResource(R.string.expense_coverage_period_yearly))
                    }
                }
                state.dialogError?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onSave) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

private fun formatMoney(value: Double): String = "¥%.2f".format(value)

private fun formatPeriodAmount(amount: Double, period: ExpensePeriod): String =
    when (period) {
        ExpensePeriod.MONTHLY -> "${formatMoney(amount)} / 月"
        ExpensePeriod.YEARLY -> "${formatMoney(amount)} / 年"
    }

@Preview(showBackground = true)
@Composable
private fun ExpenseCoveragePreview() {
    ExpenseCoverageContent(
        state = ExpenseCoverageUiState(
            forecastAnnualDividendIncome = 45000.0,
            totalAnnualExpense = 54000.0,
            coverageRatio = 45_000.0 / 54_000.0,
            coveredItemCount = 1,
            currentCoveringItemName = "餐饮",
            rows = listOf(
                ExpenseCoverageRow(
                    id = 1L,
                    name = "房租",
                    amount = 3000.0,
                    period = ExpensePeriod.MONTHLY,
                    annualAmount = 36000.0,
                    coveredAmount = 36000.0,
                    gapAmount = 0.0,
                    status = CoverageStatus.COVERED,
                    sortOrder = 0
                ),
                ExpenseCoverageRow(
                    id = 2L,
                    name = "餐饮",
                    amount = 1500.0,
                    period = ExpensePeriod.MONTHLY,
                    annualAmount = 18000.0,
                    coveredAmount = 9000.0,
                    gapAmount = 9000.0,
                    status = CoverageStatus.PARTIAL,
                    sortOrder = 1
                )
            )
        ),
        onBack = {},
        onAddExpense = {},
        onMoveUp = {},
        onMoveDown = {},
        onEdit = {},
        onDelete = {},
        onDismissDialog = {},
        onNameChanged = {},
        onAmountChanged = {},
        onPeriodChanged = {},
        onSaveExpense = {}
    )
}
