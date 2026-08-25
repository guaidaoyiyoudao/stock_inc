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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import com.stock.dividend.data.repository.StrategyAction
import com.stock.dividend.data.repository.StrategyEvaluator
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
 * 交易策略页：展示已保存的全部类型策略（统一评估卡片），FAB 唤起编辑器
 * （类型选择 + 参数表单 + 实时预览）。**仅信号提示与记账辅助，不联网下单**。
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
                    text = "9 种内置策略可选：年线定投 / 目标止盈 / 股息率带 /\n" +
                        "双均线 / 均线突破 / 均线偏离回归 / 价值平均 / 估值带 / 分红再投\n" +
                        "（仅提示与记账辅助，不下单）",
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
                        text = "买入方向信号只在策略页与今日页展示；卖出方向信号额外推送通知" +
                            "（每档一次、回落复位，可在卡片上关闭）。请结合自身判断，在券商端手动执行。",
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
                text = {
                    Text("确定删除「${plan.stockName}」的${StrategyEvaluator.displayName(plan.strategyType)}策略？")
                },
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

/** 策略动作 → StatusPill 短标签。 */
private fun actionLabel(action: StrategyAction): String = when (action) {
    StrategyAction.BUY -> "买点"
    StrategyAction.HOLD -> "持有"
    StrategyAction.SELL_HALF -> "减仓"
    StrategyAction.SELL_ALL -> "清仓"
}

/** 策略动作 → 财务语义色：买点(绿) / 持有(灰) / 减仓(黄) / 清仓(红)。 */
private fun actionTone(action: StrategyAction): FinanceStatusTone = when (action) {
    StrategyAction.BUY -> FinanceStatusTone.Positive
    StrategyAction.HOLD -> FinanceStatusTone.Neutral
    StrategyAction.SELL_HALF -> FinanceStatusTone.Warning
    StrategyAction.SELL_ALL -> FinanceStatusTone.Negative
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
                        text = StrategyEvaluator.displayName(plan.strategyType),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                when (evaluation) {
                    null -> StatusPill(text = "数据不足", tone = FinanceStatusTone.Neutral)
                    else -> StatusPill(text = actionLabel(evaluation.action), tone = actionTone(evaluation.action))
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (evaluation == null) {
                Text(
                    text = "评估数据不足（现价/日线/估值或分红缺失），下拉刷新或稍后重试",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    text = evaluation.headline,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(6.dp))
                evaluation.metrics.forEach { metric ->
                    FinanceMetricRow(label = metric.label, value = metric.value)
                }

                // 可执行动作（一键记账预填，仅提示不下单）
                when (evaluation.action) {
                    StrategyAction.BUY -> {
                        if (evaluation.buyShares > 0) {
                            Spacer(modifier = Modifier.height(8.dp))
                            val amountText = evaluation.buyAmount?.let {
                                "（${com.stock.dividend.data.repository.MoneyFormatter.withSymbol(it)}）"
                            } ?: ""
                            AppButton(
                                text = "买入 ${evaluation.buyShares} 股$amountText",
                                onClick = {
                                    item.currentPrice?.let { price ->
                                        onAddTransaction(plan.stockCode, price, evaluation.buyShares, true)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                    StrategyAction.SELL_HALF, StrategyAction.SELL_ALL -> {
                        if (evaluation.sellShares > 0) {
                            Spacer(modifier = Modifier.height(8.dp))
                            AppButton(
                                text = "卖出 ${evaluation.sellShares} 股（一键记账）",
                                onClick = {
                                    item.currentPrice?.let { price ->
                                        onAddTransaction(plan.stockCode, price, evaluation.sellShares, false)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            Text(
                                text = "无可卖出的整手股数（持仓不足一手）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    StrategyAction.HOLD -> Unit
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

/** 新建/编辑策略 BottomSheet：类型选择 + 标的 + 参数表单 + 实时预览。 */
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
                text = if (state.editingId == null) "新建策略" else "编辑策略",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(12.dp))

            // 策略类型选择（编辑已有计划时不允许换类型——换类型=删了重建，语义更清晰）
            Text(
                text = "策略类型",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(6.dp))
            FlowChips(
                options = StrategyPlanViewModel.STRATEGY_TYPES,
                selected = state.strategyTypeInput,
                enabled = state.editingId == null,
                onSelect = viewModel::onStrategyTypeChanged
            )

            Spacer(modifier = Modifier.height(12.dp))
            StockPickerSection(
                stocks = state.stocks.map { it.code to it.name },
                selectedCode = state.selectedStockCode,
                onSelect = viewModel::onStockSelected
            )

            Spacer(modifier = Modifier.height(10.dp))
            when (state.strategyTypeInput) {
                com.stock.dividend.data.local.entity.STRATEGY_TYPE_MA_DCA -> {
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
                }
                else -> {
                    // 通用参数表单（按类型字段描述渲染）
                    StrategyPlanViewModel.editorFields(state.strategyTypeInput).forEach { field ->
                        if (field.metricToggle) {
                            Text(
                                text = field.label,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            FlowChips(
                                options = listOf(
                                    com.stock.dividend.data.repository.StrategyParams.VALUATION_METRIC_PE to "市盈率 PE",
                                    com.stock.dividend.data.repository.StrategyParams.VALUATION_METRIC_PB to "市净率 PB"
                                ),
                                selected = state.paramInputs[field.key] ?: "PE",
                                enabled = true,
                                onSelect = { viewModel.onParamChanged(field.key, it) }
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                        } else {
                            AppTextField(
                                value = state.paramInputs[field.key].orEmpty(),
                                onValueChange = { viewModel.onParamChanged(field.key, it) },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text(field.label) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = if (field.numeric) KeyboardType.Decimal else KeyboardType.Text
                                )
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                        }
                    }
                    // 需要买入金额的类型（dcaAmount 专用列）
                    if (StrategyPlanViewModel.editorUsesDcaAmount(state.strategyTypeInput)) {
                        AppTextField(
                            value = state.dcaAmountInput,
                            onValueChange = viewModel::onDcaAmountChanged,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("单次买入金额（元）") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                    }
                }
            }

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
                    text = "卖出信号推送（升级才提醒、回落复位）",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Switch(checked = state.notifyEnabledInput, onCheckedChange = viewModel::onNotifyEnabledChanged)
            }

            // 实时预览：已选标的且数据就绪时展示当前信号与关键指标
            if (state.selectedStockCode.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                val preview = state.previewEvaluation
                if (preview == null) {
                    Text(
                        text = "暂无预览：数据加载中或不足、参数未填完整",
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
                                    preview.headline,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                StatusPill(
                                    text = actionLabel(preview.action),
                                    tone = actionTone(preview.action)
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            preview.metrics.take(3).forEach { metric ->
                                FinanceMetricRow(label = metric.label, value = metric.value)
                            }
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

/** 换行排布的选择 chip 组（策略类型 / PE·PB 切换）。 */
@Composable
private fun FlowChips(
    options: List<Pair<String, String>>,
    selected: String,
    enabled: Boolean = true,
    onSelect: (String) -> Unit
) {
    // 简易两列换行（M3 无内置 FlowRow 时用 Column+Row 分组，选项数少无需复杂布局）
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.chunked(2).forEach { rowOptions ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                rowOptions.forEach { (key, label) ->
                    FilterChip(
                        selected = selected == key,
                        enabled = enabled,
                        onClick = { onSelect(key) },
                        label = { Text(label, style = MaterialTheme.typography.labelMedium) }
                    )
                }
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
            // 普通 Column + 条数上限 + 自身可滚：BottomSheet 本身可滚，嵌 LazyColumn 会手势冲突
            Column(
                modifier = Modifier
                    .heightIn(max = 220.dp)
                    .verticalScroll(rememberScrollState())
            ) {
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
                if (filtered.size > 20) {
                    // 超出上限截断提示：告知还有更多匹配，引导输入更精确的关键词
                    Text(
                        "仅显示前 20 条，请输入更精确的代码/名称",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
