package com.stock.dividend.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stock.dividend.data.repository.DividendValuationStatus
import com.stock.dividend.ui.component.AppCardDefaults
import com.stock.dividend.ui.component.CompactTopAppBar
import com.stock.dividend.ui.component.FinanceMetric
import com.stock.dividend.ui.component.FinanceStatusTone
import com.stock.dividend.ui.component.SectionHeader
import com.stock.dividend.ui.component.StatusPill
import com.stock.dividend.viewmodel.DividendValuationPreset
import com.stock.dividend.viewmodel.DividendValuationUiState
import com.stock.dividend.viewmodel.DividendValuationViewModel
import java.util.Locale

data class DividendValuationFieldHelp(
    val title: String,
    val description: String
)

val dividendValuationFieldHelp = listOf(
    DividendValuationFieldHelp(
        title = "股息基准",
        description = "估值的起点股息，默认取最近 5 个可用分红年份的每股现金分红平均值；没有历史分红时可手动输入。"
    ),
    DividendValuationFieldHelp(
        title = "未来股息增长率",
        description = "假设未来每年股息增长的比例。"
    ),
    DividendValuationFieldHelp(
        title = "折现率",
        description = "把未来现金流折算成今天价值时使用的回报率要求，越高则估值越低。"
    ),
    DividendValuationFieldHelp(
        title = "终值增长率",
        description = "预测期结束后，假设股息长期稳定增长的比例；必须低于折现率。"
    ),
    DividendValuationFieldHelp(
        title = "预测年限",
        description = "逐年预测股息现金流的年数。"
    ),
    DividendValuationFieldHelp(
        title = "安全边际",
        description = "在内在价值基础上打折得到更保守的买入价。"
    )
)

@Composable
fun DividendValuationScreen(
    onBack: () -> Unit,
    viewModel: DividendValuationViewModel = hiltViewModel()
) {
    val state = viewModel.uiState.collectAsStateWithLifecycle().value

    DividendValuationContent(
        state = state,
        onBack = onBack,
        onDividendBasisChanged = viewModel::onDividendBasisChanged,
        onGrowthRateChanged = viewModel::onGrowthRateChanged,
        onDiscountRateChanged = viewModel::onDiscountRateChanged,
        onTerminalGrowthRateChanged = viewModel::onTerminalGrowthRateChanged,
        onProjectionYearsChanged = viewModel::onProjectionYearsChanged,
        onMarginOfSafetyChanged = viewModel::onMarginOfSafetyChanged,
        onPreset = viewModel::applyPreset
    )
}

@Composable
private fun DividendValuationContent(
    state: DividendValuationUiState,
    onBack: () -> Unit,
    onDividendBasisChanged: (String) -> Unit,
    onGrowthRateChanged: (String) -> Unit,
    onDiscountRateChanged: (String) -> Unit,
    onTerminalGrowthRateChanged: (String) -> Unit,
    onProjectionYearsChanged: (String) -> Unit,
    onMarginOfSafetyChanged: (String) -> Unit,
    onPreset: (DividendValuationPreset) -> Unit
) {
    Scaffold(
        topBar = {
            CompactTopAppBar(
                title = state.stock?.name ?: "股息折现估值",
                onBack = onBack
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(
                start = AppCardDefaults.PageHorizontalPadding,
                top = 12.dp,
                end = AppCardDefaults.PageHorizontalPadding,
                bottom = AppCardDefaults.BottomNavigationPadding
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { ValuationSummaryCard(state) }
            item {
                SectionHeader(title = "估值假设")
                AssumptionCard(
                    state = state,
                    onDividendBasisChanged = onDividendBasisChanged,
                    onGrowthRateChanged = onGrowthRateChanged,
                    onDiscountRateChanged = onDiscountRateChanged,
                    onTerminalGrowthRateChanged = onTerminalGrowthRateChanged,
                    onProjectionYearsChanged = onProjectionYearsChanged,
                    onMarginOfSafetyChanged = onMarginOfSafetyChanged,
                    onPreset = onPreset
                )
            }
            item { SectionHeader(title = "未来现金流明细") }

            val rows = state.result?.cashFlowRows.orEmpty()
            if (rows.isEmpty()) {
                item { EmptyCashFlowCard() }
            } else {
                items(rows, key = { it.year }) { row ->
                    Card(colors = AppCardDefaults.listCardColors()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(AppCardDefaults.ListPadding),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FinanceMetric(
                                label = "年份",
                                value = "第 ${row.year} 年",
                                modifier = Modifier.weight(1f)
                            )
                            FinanceMetric(
                                label = "预估股息",
                                value = formatCurrency(row.projectedDividend),
                                modifier = Modifier.weight(1f),
                                textAlign = TextAlign.End
                            )
                            FinanceMetric(
                                label = "折现值",
                                value = formatCurrency(row.discountedDividend),
                                modifier = Modifier.weight(1f),
                                textAlign = TextAlign.End
                            )
                        }
                    }
                }
                item { TerminalValueCard(state) }
            }
        }
    }
}

@Composable
private fun ValuationSummaryCard(state: DividendValuationUiState) {
    val result = state.result

    Card(colors = AppCardDefaults.summaryCardColors()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppCardDefaults.SummaryPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "估值结论",
                        style = MaterialTheme.typography.labelLarge
                    )
                    Text(
                        text = result?.let { formatCurrency(it.intrinsicValuePerShare) } ?: "--",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
                result?.let {
                    StatusPill(
                        text = statusText(it.valuationStatus),
                        tone = statusTone(it.valuationStatus)
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FinanceMetric(
                    label = "当前价",
                    value = state.currentPrice?.let(::formatCurrency) ?: "--",
                    modifier = Modifier.weight(1f)
                )
                FinanceMetric(
                    label = "折价/溢价",
                    value = result?.discountOrPremiumPercent?.let(::formatPercent) ?: "--",
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center
                )
                FinanceMetric(
                    label = "安全买入价",
                    value = result?.let { formatCurrency(it.safetyBuyPrice) } ?: "--",
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.End
                )
            }
            if (!state.hasDividendHistory) {
                Text(
                    text = "缺少历史股息数据，请手动输入股息基准。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            state.validationError?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun AssumptionCard(
    state: DividendValuationUiState,
    onDividendBasisChanged: (String) -> Unit,
    onGrowthRateChanged: (String) -> Unit,
    onDiscountRateChanged: (String) -> Unit,
    onTerminalGrowthRateChanged: (String) -> Unit,
    onProjectionYearsChanged: (String) -> Unit,
    onMarginOfSafetyChanged: (String) -> Unit,
    onPreset: (DividendValuationPreset) -> Unit
) {
    var selectedHelp by remember { mutableStateOf<DividendValuationFieldHelp?>(null) }

    Card(colors = AppCardDefaults.listCardColors()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DividendValuationPreset.entries.forEach { preset ->
                    AssistChip(
                        onClick = { onPreset(preset) },
                        label = { Text(preset.label) }
                    )
                }
            }
            AssumptionField(
                label = "股息基准",
                value = state.dividendBasisInput,
                help = helpFor("股息基准"),
                onHelpClick = { selectedHelp = it },
                onValueChange = onDividendBasisChanged
            )
            AssumptionField(
                label = "未来股息增长率 (%)",
                value = state.growthRateInput,
                help = helpFor("未来股息增长率"),
                onHelpClick = { selectedHelp = it },
                onValueChange = onGrowthRateChanged
            )
            AssumptionField(
                label = "折现率 (%)",
                value = state.discountRateInput,
                help = helpFor("折现率"),
                onHelpClick = { selectedHelp = it },
                onValueChange = onDiscountRateChanged
            )
            AssumptionField(
                label = "终值增长率 (%)",
                value = state.terminalGrowthRateInput,
                help = helpFor("终值增长率"),
                onHelpClick = { selectedHelp = it },
                onValueChange = onTerminalGrowthRateChanged
            )
            AssumptionField(
                label = "预测年限",
                value = state.projectionYearsInput,
                help = helpFor("预测年限"),
                onHelpClick = { selectedHelp = it },
                onValueChange = onProjectionYearsChanged
            )
            AssumptionField(
                label = "安全边际 (%)",
                value = state.marginOfSafetyInput,
                help = helpFor("安全边际"),
                onHelpClick = { selectedHelp = it },
                onValueChange = onMarginOfSafetyChanged
            )
            if (state.dividendBasisYears > 0) {
                Text(
                    text = "股息基准来自近 ${state.dividendBasisYears} 年平均每股股息。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    selectedHelp?.let { help ->
        AlertDialog(
            onDismissRequest = { selectedHelp = null },
            title = { Text(help.title) },
            text = { Text(help.description) },
            confirmButton = {
                TextButton(onClick = { selectedHelp = null }) {
                    Text("知道了")
                }
            }
        )
    }
}

@Composable
private fun AssumptionField(
    label: String,
    value: String,
    help: DividendValuationFieldHelp,
    onHelpClick: (DividendValuationFieldHelp) -> Unit,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        trailingIcon = {
            IconButton(onClick = { onHelpClick(help) }) {
                Icon(
                    imageVector = Icons.Filled.Info,
                    contentDescription = "${help.title}说明"
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
    )
}

@Composable
private fun EmptyCashFlowCard() {
    Card(colors = AppCardDefaults.listCardColors()) {
        Text(
            text = "输入有效估值参数后显示现金流明细。",
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppCardDefaults.ListPadding),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun TerminalValueCard(state: DividendValuationUiState) {
    val result = state.result ?: return

    Card(colors = AppCardDefaults.listCardColors()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppCardDefaults.ListPadding),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FinanceMetric(
                label = "终值",
                value = formatCurrency(result.terminalValue),
                modifier = Modifier.weight(1f)
            )
            FinanceMetric(
                label = "终值折现",
                value = formatCurrency(result.discountedTerminalValue),
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center
            )
            FinanceMetric(
                label = "合计",
                value = formatCurrency(result.intrinsicValuePerShare),
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.End
            )
        }
    }
}

private fun formatCurrency(value: Double): String = String.format(Locale.US, "¥%.2f", value)

private fun formatPercent(value: Double): String = String.format(Locale.US, "%.1f%%", value * 100)

private fun helpFor(title: String): DividendValuationFieldHelp =
    dividendValuationFieldHelp.first { it.title == title }

private fun statusText(status: DividendValuationStatus): String = when (status) {
    DividendValuationStatus.UNDERVALUED -> "低估"
    DividendValuationStatus.OVERVALUED -> "高估"
    DividendValuationStatus.FAIR -> "合理"
    DividendValuationStatus.NO_MARKET_PRICE -> "无行情"
    DividendValuationStatus.INVALID -> "参数无效"
}

private fun statusTone(status: DividendValuationStatus): FinanceStatusTone = when (status) {
    DividendValuationStatus.UNDERVALUED -> FinanceStatusTone.Positive
    DividendValuationStatus.OVERVALUED -> FinanceStatusTone.Negative
    DividendValuationStatus.FAIR -> FinanceStatusTone.Neutral
    DividendValuationStatus.NO_MARKET_PRICE -> FinanceStatusTone.Warning
    DividendValuationStatus.INVALID -> FinanceStatusTone.Negative
}
