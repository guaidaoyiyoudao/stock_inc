package com.stock.dividend.ui.screen

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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stock.dividend.R
import com.stock.dividend.ui.component.CompactTopAppBar
import com.stock.dividend.viewmodel.ExpenseCoverageUiState
import com.stock.dividend.viewmodel.ExpenseCoverageViewModel
import java.text.NumberFormat
import java.util.Locale

@Composable
fun ExpenseCoverageScreen(
    onBack: () -> Unit,
    onGoSetup: () -> Unit,
    viewModel: ExpenseCoverageViewModel = hiltViewModel()
) {
    val state = viewModel.uiState.collectAsStateWithLifecycle().value
    ExpenseCoverageContent(state = state, onBack = onBack, onGoSetup = onGoSetup)
}

@Composable
private fun ExpenseCoverageContent(
    state: ExpenseCoverageUiState,
    onBack: () -> Unit,
    onGoSetup: () -> Unit
) {
    Scaffold(
        topBar = {
            CompactTopAppBar(
                title = stringResource(R.string.expense_coverage_title),
                onBack = onBack
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
            item {
                StatusCard(state)
            }
            if (!state.hasGoal) {
                item {
                    EmptyGoalCard(onGoSetup = onGoSetup)
                }
            }
        }
    }
}

@Composable
private fun StatusCard(state: ExpenseCoverageUiState) {
    val statusColor = when {
        !state.hasGoal -> MaterialTheme.colorScheme.onSurfaceVariant
        state.isGoalReached -> Color(0xFF2E7D32)
        else -> MaterialTheme.colorScheme.primary
    }
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Text(
                text = stringResource(R.string.expense_coverage_status_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = if (state.hasGoal) formatPercent(state.coverageRatio) else "--",
                style = MaterialTheme.typography.headlineMedium,
                color = statusColor,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { if (state.hasGoal) state.coverageRatio.coerceIn(0.0, 1.0).toFloat() else 0f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp),
                color = statusColor,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
            Spacer(modifier = Modifier.height(14.dp))
            MetricRow(stringResource(R.string.expense_coverage_metric_income), formatMoney(state.annualDividendIncome))
            MetricRow(stringResource(R.string.expense_coverage_metric_goal), state.annualExpenseGoal?.let { formatMoney(it) } ?: "--")
            MetricRow(stringResource(R.string.expense_coverage_metric_difference), formatSignedMoney(state.difference))
            Spacer(modifier = Modifier.height(8.dp))
            val progressText = when {
                !state.hasGoal -> stringResource(R.string.expense_coverage_no_goal_status)
                state.isGoalReached -> stringResource(R.string.expense_coverage_reached_status)
                else -> stringResource(R.string.expense_coverage_shortfall_status, formatMoney(state.shortfallAmount))
            }
            Text(
                text = progressText,
                style = MaterialTheme.typography.bodySmall,
                color = statusColor
            )
        }
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    }
    Spacer(modifier = Modifier.height(8.dp))
}

@Composable
private fun EmptyGoalCard(onGoSetup: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(text = stringResource(R.string.expense_coverage_empty_title), style = MaterialTheme.typography.titleSmall)
            Text(text = stringResource(R.string.expense_coverage_empty_message), style = MaterialTheme.typography.bodySmall)
            Button(onClick = onGoSetup) {
                Text(text = stringResource(R.string.expense_coverage_action_setup_goal))
            }
        }
    }
}

private fun formatMoney(value: Double): String {
    val formatter = NumberFormat.getCurrencyInstance(Locale.getDefault()).apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 2
    }
    return formatter.format(value)
}
private fun formatSignedMoney(value: Double): String = if (value >= 0) "+${formatMoney(value)}" else "-${formatMoney(-value)}"
private fun formatPercent(value: Double): String {
    val formatter = NumberFormat.getPercentInstance(Locale.getDefault()).apply {
        minimumFractionDigits = 1
        maximumFractionDigits = 1
    }
    return formatter.format(value)
}

@Preview(showBackground = true)
@Composable
private fun ExpenseCoveragePreviewReached() {
    ExpenseCoverageContent(
        state = ExpenseCoverageUiState(annualDividendIncome = 120000.0, annualExpenseGoal = 100000.0, coverageRatio = 1.2, difference = 20000.0, isGoalReached = true, hasGoal = true),
        onBack = {}, onGoSetup = {}
    )
}

@Preview(showBackground = true)
@Composable
private fun ExpenseCoveragePreviewNotReached() {
    ExpenseCoverageContent(
        state = ExpenseCoverageUiState(annualDividendIncome = 60000.0, annualExpenseGoal = 100000.0, coverageRatio = 0.6, difference = -40000.0, isGoalReached = false, shortfallAmount = 40000.0, hasGoal = true),
        onBack = {}, onGoSetup = {}
    )
}

@Preview(showBackground = true)
@Composable
private fun ExpenseCoveragePreviewNoGoal() {
    ExpenseCoverageContent(
        state = ExpenseCoverageUiState(annualDividendIncome = 52000.0, annualExpenseGoal = null, coverageRatio = 0.0, difference = 52000.0, isGoalReached = false, hasGoal = false),
        onBack = {}, onGoSetup = {}
    )
}
