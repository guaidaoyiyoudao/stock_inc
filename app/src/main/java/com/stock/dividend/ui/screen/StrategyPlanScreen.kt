package com.stock.dividend.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stock.dividend.data.local.entity.StrategyPlanEntity
import com.stock.dividend.data.repository.MaDcaEvaluation
import com.stock.dividend.data.repository.MaDcaSignal
import com.stock.dividend.data.repository.MoneyFormatter
import com.stock.dividend.ui.component.AppButton
import com.stock.dividend.ui.component.AppCard
import com.stock.dividend.ui.component.AppCardDefaults
import com.stock.dividend.ui.component.AppTextButton
import com.stock.dividend.ui.component.AppTextField
import com.stock.dividend.ui.component.CompactTopAppBar
import com.stock.dividend.ui.component.FinanceMetricRow
import com.stock.dividend.ui.component.FinanceStatusTone
import com.stock.dividend.ui.component.StatusPill
import com.stock.dividend.ui.theme.LocalExtendedColors
import com.stock.dividend.ui.theme.tabularNumberStyle
import com.stock.dividend.viewmodel.StrategyPlanItem
import com.stock.dividend.viewmodel.StrategyPlanUiState
import com.stock.dividend.viewmodel.StrategyPlanViewModel

/**
 * 交易策略页：展示已保存的策略计划（首版：年线定投——250 日线下定投买入，
 * 高于年线 7.5% 卖一半、15% 全卖，参数可调），FAB 唤起编辑器（参数实时预览）。
 * **仅信号提示与记账辅助，不联网下单**。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StrategyPlanScreen(
    onBack: () -> Unit,
    onAddTransaction: (stockCode: String, price: Double, shares: Int, isBuy: Boolean) -> Unit = { _, _, _, _ -> },
    viewModel: StrategyPlanViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { CompactTopAppBar(title = "交易策略", onBack = onBack) },
        floatingActionButton = {
            FloatingActionButton(onClick = viewModel::showEditor) {
                Icon(Icons.Default.Add, contentDescription = "新建策略")
            }
        }
    ) { padding ->
        if (state.items.isEmpty() && !state.isLoading) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "还没有交易策略",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "为股票/ETF 配置年线定投策略：\n低于年线定投买入，高于阈值卖出一半/全部\n（仅提示与记账辅助，不下单）",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                AppButton(text = "新建策略", onClick = viewModel::showEditor)
            }
        } else {
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
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    Text(
                        text = "年线定投：现价低于均线开启定投窗口；高于均线「卖出一半阈值」卖一半、" +
                            "「清仓阈值」全部卖出。仅在 App 内提示与辅助记账，请在券商端手动执行。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
                items(state.items, key = { it.plan.id }) { item ->
                    StrategyPlanCard(
                        item = item,
                        onEdit = { viewModel.editPlan(item.plan) },
                        onDelete = { viewModel.requestDelete(item.plan) },
                        onToggleNotify = { viewModel.toggleNotify(item.plan) },
                        onAddTransaction = onAddTransaction
                    )
                }
            }
        }

        if (state.showEditor) {
            StrategyEditorSheet(
                state = state,
                viewModel = viewModel,
                onDismiss = viewModel::dismissEditor
            )
        }

        state.deletingPlan?.let { plan ->
            AlertDialog(
                onDismissRequest = viewModel::dismissDelete,
                title = { Text("删除策略") },
                text = { Text("确定删除「${plan.stockName}」的年线定投策略？") },
                confirmButton = {
                    AppTextButton(text = "删除", onClick = viewModel::confirmDelete)
                },
                dismissButton = {
                    AppTextButton(text = "取消", onClick = viewModel::dismissDelete)
                }
            )
        }
    }
}

/** 策略信号 → StatusPill 短标签。 */
private fun signalLabel(signal: MaDcaSignal): String = when (signal) {
    MaDcaSignal.DCA_WINDOW -> "定投窗口"
    MaDcaSignal.HOLD -> "持有"
    MaDcaSignal.SELL_HALF -> "卖出一半"
    MaDcaSignal.SELL_ALL -> "全部卖出"
}

/** 策略信号 → 财务语义色：定投窗口(绿) / 持有(灰) / 卖一半(黄) / 清仓(红)。 */
private fun signalTone(signal: MaDcaSignal): FinanceStatusTone = when (signal) {
    MaDcaSignal.DCA_WINDOW -> FinanceStatusTone.Positive
    MaDcaSignal.HOLD -> FinanceStatusTone.Neutral
    MaDcaSignal.SELL_HALF -> FinanceStatusTone.Warning
    MaDcaSignal.SELL_ALL -> FinanceStatusTone.Negative
}

@Composable
private fun StrategyPlanCard(
    item: StrategyPlanItem,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggleNotify: () -> Unit,
    onAddTransaction: (stockCode: String, price: Double, shares: Int, isBuy: Boolean) -> Unit
) {
    val plan = item.plan
    val evaluation = item.evaluation
    val ext = LocalExtendedColors.current

    AppCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = plan.stockName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "MA${plan.maPeriod} 年线定投 · 每期 ${MoneyFormatter.withSymbol(plan.dcaAmount)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                when (evaluation) {
                    null -> StatusPill(text = "数据不足", tone = FinanceStatusTone.Neutral)
                    else -> StatusPill(text = signalLabel(evaluation.signal), tone = signalTone(evaluation.signal))
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (evaluation == null) {
                Text(
                    text = "日线数据不足 ${plan.maPeriod} 根（上市不足周期长度或未缓存），下拉刷新重试",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                FinanceMetricRow(label = "现价", value = MoneyFormatter.amount(item.currentPrice ?: 0.0))
                FinanceMetricRow(label = "年线 MA${plan.maPeriod}", value = MoneyFormatter.amount(evaluation.maValue))
                FinanceMetricRow(
                    label = "偏离度",
                    value = (if (evaluation.deviationPercent >= 0) "+" else "") +
                        MoneyFormatter.amount(evaluation.deviationPercent) + "%",
                    valueColor = if (evaluation.deviationPercent >= 0) ext.negative else ext.positive
                )
                FinanceMetricRow(
                    label = "卖出一半触发价（+${trimPercent(plan.sellHalfPercent)}%）",
                    value = MoneyFormatter.amount(evaluation.sellHalfTriggerPrice)
                )
                FinanceMetricRow(
                    label = "清仓触发价（+${trimPercent(plan.sellAllPercent)}%）",
                    value = MoneyFormatter.amount(evaluation.sellAllTriggerPrice)
                )
                FinanceMetricRow(label = "当前持仓", value = "${item.holdingShares} 股")

                // 信号对应的可执行动作（一键记账预填，仅提示不下单）
                when (evaluation.signal) {
                    MaDcaSignal.DCA_WINDOW -> {
                        if (item.dcaBuyShares > 0) {
                            Spacer(modifier = Modifier.height(8.dp))
                            AppButton(
                                text = "定投买入 ${item.dcaBuyShares} 股（${MoneyFormatter.withSymbol(plan.dcaAmount)}）",
                                onClick = {
                                    item.currentPrice?.let { price ->
                                        onAddTransaction(plan.stockCode, price, item.dcaBuyShares, true)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            Text(
                                text = "定投金额不足一手（100 股），可调大每期金额",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    MaDcaSignal.SELL_HALF, MaDcaSignal.SELL_ALL -> {
                        Spacer(modifier = Modifier.height(8.dp))
                        if (item.sellTargetShares > 0) {
                            AppButton(
                                text = "卖出 ${item.sellTargetShares} 股（一键记账）",
                                onClick = {
                                    item.currentPrice?.let { price ->
                                        onAddTransaction(plan.stockCode, price, item.sellTargetShares, false)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            Text(
                                text = if (evaluation.signal == MaDcaSignal.SELL_HALF) {
                                    "持仓不足一手，无可卖出的整手股数"
                                } else {
                                    "当前无持仓，清仓信号仅作提示"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    MaDcaSignal.HOLD -> Unit
                }
            }

            plan.note?.takeIf { it.isNotBlank() }?.let { note ->
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "备注：$note",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row {
                    AppTextButton(text = "编辑", onClick = onEdit)
                    AppTextButton(text = "删除", onClick = onDelete)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "卖出推送",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Switch(checked = plan.notifyEnabled, onCheckedChange = { onToggleNotify() })
                }
            }
        }
    }
}

/** 7.5 → "7.5"；15.0 → "15"（展示用，去掉无意义尾零）。 */
private fun trimPercent(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

/** 新建/编辑策略 BottomSheet：选标的 + 参数输入 + 实时预览（年线/触发价/当前信号）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StrategyEditorSheet(
    state: StrategyPlanUiState,
    viewModel: StrategyPlanViewModel,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                text = if (state.editingId == null) "新建策略 · 年线定投" else "编辑策略 · 年线定投",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(12.dp))

            // 标的选择：搜索框 + 过滤列表
            StockPickerSection(
                stocks = state.stocks.map { it.code to it.name },
                selectedCode = state.selectedStockCode,
                onSelect = viewModel::onStockSelected
            )

            Spacer(modifier = Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AppTextField(
                    value = state.maPeriodInput,
                    onValueChange = viewModel::onMaPeriodChanged,
                    modifier = Modifier.weight(1f),
                    label = { Text("均线周期（日）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                AppTextField(
                    value = state.dcaAmountInput,
                    onValueChange = viewModel::onDcaAmountChanged,
                    modifier = Modifier.weight(1f),
                    label = { Text("每期定投金额（元）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AppTextField(
                    value = state.sellHalfInput,
                    onValueChange = viewModel::onSellHalfChanged,
                    modifier = Modifier.weight(1f),
                    label = { Text("卖出一半阈值（%）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                AppTextField(
                    value = state.sellAllInput,
                    onValueChange = viewModel::onSellAllChanged,
                    modifier = Modifier.weight(1f),
                    label = { Text("清仓阈值（%）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            AppTextField(
                value = state.noteInput,
                onValueChange = viewModel::onNoteChanged,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("备注（可选）") }
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "卖出阈值推送（每档一次，回落后可再提醒）",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Switch(checked = state.notifyEnabledInput, onCheckedChange = viewModel::onNotifyEnabledChanged)
            }

            // 实时预览：已选标的且有日线数据时展示年线/触发价/信号
            if (state.selectedStockCode.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                val preview = state.previewEvaluation
                if (preview == null) {
                    Text(
                        text = "暂无预览：日线数据加载中或不足周期数、参数未填完整",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    AppCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "按当前参数预估",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                StatusPill(
                                    text = signalLabel(preview.signal),
                                    tone = signalTone(preview.signal)
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            FinanceMetricRow(
                                label = "年线 MA${state.maPeriodInput.trim()}",
                                value = MoneyFormatter.amount(preview.maValue)
                            )
                            FinanceMetricRow(
                                label = "卖出一半触发价",
                                value = MoneyFormatter.amount(preview.sellHalfTriggerPrice)
                            )
                            FinanceMetricRow(
                                label = "清仓触发价",
                                value = MoneyFormatter.amount(preview.sellAllTriggerPrice)
                            )
                        }
                    }
                }
            }

            state.saveError?.let { error ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalExtendedColors.current.negative
                )
            }

            Spacer(modifier = Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AppButton(
                    text = if (state.editingId == null) "创建策略" else "保存修改",
                    onClick = viewModel::savePlan,
                    modifier = Modifier.weight(1f)
                )
                AppTextButton(text = "取消", onClick = onDismiss, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** 标的选择：搜索过滤 + 列表点选（自选股内选，允许未持仓）。 */
@Composable
private fun StockPickerSection(
    stocks: List<Pair<String, String>>,
    selectedCode: String,
    onSelect: (String) -> Unit
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(query, stocks) {
        if (query.isBlank()) stocks
        else stocks.filter { (code, name) ->
            code.contains(query.trim(), ignoreCase = true) || name.contains(query.trim())
        }
    }

    Column {
        AppTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = {
                Text(
                    if (selectedCode.isBlank()) "选择标的（搜索代码/名称）"
                    else "已选：${stocks.firstOrNull { it.first == selectedCode }?.second ?: selectedCode}"
                )
            }
        )
        if (query.isNotBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            // 普通 Column + 条数上限：BottomSheet 本身可滚，嵌 LazyColumn 会手势冲突
            Column(modifier = Modifier.heightIn(max = 220.dp)) {
                filtered.take(20).forEach { (code, name) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSelect(code)
                                query = ""
                            }
                            .padding(horizontal = 4.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            code,
                            style = MaterialTheme.typography.labelSmall.merge(tabularNumberStyle),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            if (filtered.isEmpty()) {
                Text(
                    "无匹配标的，请先在持仓页添加自选",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
