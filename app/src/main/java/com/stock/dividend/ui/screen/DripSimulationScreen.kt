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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stock.dividend.data.repository.DripResult
import com.stock.dividend.data.repository.DripYearRow
import com.stock.dividend.data.repository.MoneyFormatter
import com.stock.dividend.ui.component.AppCard
import com.stock.dividend.ui.component.AppCardDefaults
import com.stock.dividend.ui.component.AppCardTone
import com.stock.dividend.ui.component.AppTextButton
import com.stock.dividend.ui.component.AppTextField
import com.stock.dividend.ui.component.CompactTopAppBar
import com.stock.dividend.ui.component.FinanceMetric
import com.stock.dividend.ui.component.SectionHeader
import com.stock.dividend.ui.theme.LocalExtendedColors
import com.stock.dividend.ui.theme.tabularNumberStyle
import com.stock.dividend.viewmodel.DripSimulationUiState
import com.stock.dividend.viewmodel.DripSimulationViewModel

/**
 * 分红再投资（DRIP）模拟页：对比「分红再投」与「现金分红」两条路径的复利效果。
 * 参数可调，即时重算（纯函数 [com.stock.dividend.data.repository.DripCalculator]）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DripSimulationScreen(
    onBack: () -> Unit,
    viewModel: DripSimulationViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            CompactTopAppBar(
                title = "分红再投模拟",
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
            item { DripSummaryCard(state) }

            item { SectionHeader(title = "模拟参数") }
            item {
                DripParamCard(
                    state = state,
                    onInitialAmountChanged = viewModel::onInitialAmountChanged,
                    onInitialPriceChanged = viewModel::onInitialPriceChanged,
                    onReinvestPriceChanged = viewModel::onReinvestPriceChanged,
                    onEndPriceChanged = viewModel::onEndPriceChanged,
                    onStartYearChanged = viewModel::onStartYearChanged,
                    onEndYearChanged = viewModel::onEndYearChanged,
                    onUseCurrentPrice = viewModel::useCurrentPriceForAll
                )
            }

            val rows = state.result?.yearlyRows.orEmpty()
            if (rows.isNotEmpty()) {
                item { SectionHeader(title = "逐年明细（${state.result!!.startYear}–${state.result!!.endYear}）") }
                items(rows, key = { it.year }) { row ->
                    DripYearRowCard(row)
                }
            }
        }
    }
}

@Composable
private fun DripSummaryCard(state: DripSimulationUiState) {
    val result = state.result
    val extendedColors = LocalExtendedColors.current

    AppCard(tone = AppCardTone.Summary) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppCardDefaults.SummaryPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "再投 vs 现金分红",
                style = MaterialTheme.typography.labelLarge
            )
            // 两条路径期末市值对比
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FinanceMetric(
                    label = "分红再投市值",
                    value = result?.let { MoneyFormatter.withSymbol(it.dripPathFinalValue) } ?: "--",
                    modifier = Modifier.weight(1f)
                )
                FinanceMetric(
                    label = "现金分红市值",
                    value = result?.let { MoneyFormatter.withSymbol(it.cashPathFinalValue) } ?: "--",
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center
                )
                FinanceMetric(
                    label = "再投超额",
                    value = result?.let {
                        "${if (it.dripVsCashExcess >= 0) "+" else ""}${MoneyFormatter.amount(it.dripVsCashExcess)}"
                    } ?: "--",
                    valueColor = if (result != null && result.dripVsCashExcess >= 0) extendedColors.positive else extendedColors.negative,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.End
                )
            }
            result?.let {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FinanceMetric(
                        label = "初始股数",
                        value = "%.0f".format(it.initialShares),
                        modifier = Modifier.weight(1f)
                    )
                    FinanceMetric(
                        label = "期末股数",
                        value = "%.0f".format(it.finalShares),
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center
                    )
                    FinanceMetric(
                        label = "再投新增",
                        value = "+%.0f".format(it.reinvestedShares),
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.End
                    )
                }
                it.dripVsCashExcessRate?.let { rate ->
                    Text(
                        text = "再投相对现金路径超额收益率 " +
                            "${if (rate >= 0) "+" else ""}${"%.2f".format(rate)}%",
                        style = MaterialTheme.typography.bodySmall.merge(tabularNumberStyle),
                        fontWeight = FontWeight.SemiBold,
                        color = if (rate >= 0) extendedColors.positive else extendedColors.negative
                    )
                }
            }
            if (!state.hasDividendHistory && !state.isLoading) {
                Text(
                    text = "缺少历史分红数据，无法启动模拟。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            state.validationError?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Text(
                text = "假设：每年分红现金以「再投价格」全额买入。再投价格为单值简化口径，非逐日真实价。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
            )
        }
    }
}

@Composable
private fun DripParamCard(
    state: DripSimulationUiState,
    onInitialAmountChanged: (String) -> Unit,
    onInitialPriceChanged: (String) -> Unit,
    onReinvestPriceChanged: (String) -> Unit,
    onEndPriceChanged: (String) -> Unit,
    onStartYearChanged: (String) -> Unit,
    onEndYearChanged: (String) -> Unit,
    onUseCurrentPrice: () -> Unit
) {
    AppCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppCardDefaults.ListPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AppTextField(
                value = state.initialAmountInput,
                onValueChange = onInitialAmountChanged,
                label = { Text("初始投入金额") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                suffix = { Text("元") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
            )
            AppTextField(
                value = state.initialPriceInput,
                onValueChange = onInitialPriceChanged,
                label = { Text("初始买入价") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                suffix = { Text("元/股") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
            )
            AppTextField(
                value = state.reinvestPriceInput,
                onValueChange = onReinvestPriceChanged,
                label = { Text("分红再投价") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                suffix = { Text("元/股") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
            )
            AppTextField(
                value = state.endPriceInput,
                onValueChange = onEndPriceChanged,
                label = { Text("期末参考价") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                suffix = { Text("元/股") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppTextField(
                    value = state.startYearInput,
                    onValueChange = onStartYearChanged,
                    label = { Text("起始年") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                AppTextField(
                    value = state.endYearInput,
                    onValueChange = onEndYearChanged,
                    label = { Text("结束年") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }
            state.availableStartYear?.let { s ->
                state.availableEndYear?.let { e ->
                    Text(
                        text = "可用分红区间：$s – $e（留空即取全量）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
            AppTextButton(
                onClick = onUseCurrentPrice,
                modifier = Modifier.fillMaxWidth(),
                text = "三个价格统一用当前价"
            )
        }
    }
}

@Composable
private fun DripYearRowCard(row: DripYearRow) {
    AppCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppCardDefaults.ListPadding),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FinanceMetric(
                label = "年份",
                value = row.year,
                modifier = Modifier.weight(1f)
            )
            FinanceMetric(
                label = "每股分红",
                value = MoneyFormatter.withSymbol(row.cashPerShare),
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center
            )
            FinanceMetric(
                label = "当年分红",
                value = MoneyFormatter.withSymbol(row.dividendCash),
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center
            )
            FinanceMetric(
                label = "累计股数",
                value = "%.0f".format(row.cumulativeShares),
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.End
            )
        }
    }
}
