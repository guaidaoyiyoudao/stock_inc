package com.stock.dividend.ui.screen

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stock.dividend.data.repository.GridLevel
import com.stock.dividend.data.repository.GridResult
import com.stock.dividend.data.repository.MoneyFormatter
import com.stock.dividend.ui.component.AppButton
import com.stock.dividend.ui.component.AppCard
import com.stock.dividend.ui.component.AppCardDefaults
import com.stock.dividend.ui.component.AppTextButton
import com.stock.dividend.ui.component.AppTextField
import com.stock.dividend.ui.component.CompactTopAppBar
import com.stock.dividend.ui.component.FinanceMetric
import com.stock.dividend.ui.theme.LocalExtendedColors
import com.stock.dividend.ui.theme.tabularNumberStyle
import com.stock.dividend.viewmodel.GridPlanItem
import com.stock.dividend.viewmodel.GridPlanUiState
import com.stock.dividend.viewmodel.GridPlanViewModel

/**
 * 网格交易计划页：展示已保存的网格计划（含「下一档」提示），FAB 唤起生成器
 * （参数实时预览档位表），可保存/编辑/删除。**仅计划与提示，不联网下单**。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GridPlanScreen(
    onBack: () -> Unit,
    viewModel: GridPlanViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { CompactTopAppBar(title = "网格交易计划", onBack = onBack) },
        floatingActionButton = {
            FloatingActionButton(onClick = viewModel::showGenerator) {
                Icon(Icons.Default.Add, contentDescription = "新建网格计划")
            }
        }
    ) { padding ->
        if (state.items.isEmpty() && !state.isLoading) {
            EmptyGridPlans(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                onCreate = viewModel::showGenerator
            )
            if (state.showGenerator) {
                GridGeneratorSheet(state, viewModel, onDismiss = viewModel::dismissGenerator)
            }
            return@Scaffold
        }

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
                    text = "以下计划仅用于档位生成与「下一档」提示，不会自动下单。请在券商端手动执行。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
            items(state.items, key = { it.plan.id }) { item ->
                GridPlanCard(
                    item = item,
                    onEdit = { viewModel.editPlan(item.plan) },
                    onDelete = { viewModel.deletePlan(item.plan.id) }
                )
            }
        }

        if (state.showGenerator) {
            GridGeneratorSheet(state, viewModel, onDismiss = viewModel::dismissGenerator)
        }
    }
}

@Composable
private fun GridPlanCard(
    item: GridPlanItem,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val plan = item.plan
    val result = item.result
    val extendedColors = LocalExtendedColors.current

    AppCard(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = plan.stockName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "基准 ${MoneyFormatter.withSymbol(plan.basePrice)} · " +
                            "${plan.lowPrice}–${plan.highPrice} · ${plan.grids} 档 · " +
                            "资金 ${MoneyFormatter.withSymbol(plan.totalCapital)}",
                        style = MaterialTheme.typography.bodySmall.merge(tabularNumberStyle),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, "编辑", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, "删除", tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f), modifier = Modifier.size(20.dp))
                }
            }

            // 「下一档」提示
            if (item.currentPrice != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "现价 ${MoneyFormatter.withSymbol(item.currentPrice)}",
                        style = MaterialTheme.typography.labelMedium.merge(tabularNumberStyle),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    result.nextBuyHint?.let { buy ->
                        Text(
                            text = "下一买 ${MoneyFormatter.withSymbol(buy)}",
                            style = MaterialTheme.typography.labelMedium.merge(tabularNumberStyle),
                            color = extendedColors.positive
                        )
                    }
                    result.nextSellHint?.let { sell ->
                        Text(
                            text = "下一卖 ${MoneyFormatter.withSymbol(sell)}",
                            style = MaterialTheme.typography.labelMedium.merge(tabularNumberStyle),
                            color = extendedColors.negative
                        )
                    }
                }
            }

            // 档位表（紧凑双列：价格 / 方向 / 股数）
            if (result.levels.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "档位表（步长 ${"%.1f".format(result.stepPercent)}%）",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))
                result.levels.forEach { level -> GridLevelRow(level, item.currentPrice) }
            }
        }
    }
}

@Composable
private fun GridLevelRow(level: GridLevel, currentPrice: Double?) {
    // 当前价恰好落在该档附近（误差 < 0.5%）时高亮
    val near = currentPrice != null && kotlin.math.abs(level.price - currentPrice) / level.price < 0.005
    val extendedColors = LocalExtendedColors.current
    val sideColor = if (level.isBuy) extendedColors.positive else extendedColors.negative
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = MoneyFormatter.withSymbol(level.price),
            style = MaterialTheme.typography.bodySmall.merge(tabularNumberStyle),
            fontWeight = if (near) FontWeight.Bold else FontWeight.Normal,
            color = if (near) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = if (level.isBuy) "买" else "卖",
            style = MaterialTheme.typography.labelSmall,
            color = sideColor,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
        )
        Text(
            text = "${level.shares} 股",
            style = MaterialTheme.typography.bodySmall.merge(tabularNumberStyle),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "${if (level.deviation >= 0) "+" else ""}${"%.1f".format(level.deviation)}%",
            style = MaterialTheme.typography.bodySmall.merge(tabularNumberStyle),
            color = sideColor,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GridGeneratorSheet(
    state: GridPlanUiState,
    viewModel: GridPlanViewModel,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                text = if (state.editingId != null) "编辑网格计划" else "新建网格计划",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(12.dp))

            // 标的下拉
            StockPicker(
                stocks = state.stocks,
                selectedCode = state.selectedStockCode,
                onSelect = viewModel::onStockSelected
            )
            Spacer(modifier = Modifier.height(12.dp))

            // ── 智能锚定（BOLL 中轨=基准 / 上轨=上界 / 目标股息率=下界资金用完位）──
            AnchorSection(state, viewModel)
            Spacer(modifier = Modifier.height(12.dp))

            ParamField("基准价（中轴）", state.basePriceInput, viewModel::onBasePriceChanged, "元/股")
            Spacer(modifier = Modifier.height(10.dp))
            ParamField("网格下界（资金用完位）", state.lowPriceInput, viewModel::onLowPriceChanged, "元/股")
            Spacer(modifier = Modifier.height(10.dp))
            ParamField("网格上界", state.highPriceInput, viewModel::onHighPriceChanged, "元/股")
            Spacer(modifier = Modifier.height(10.dp))
            ParamField("网格档数", state.gridsInput, viewModel::onGridsChanged, "档", KeyboardType.Number)
            Spacer(modifier = Modifier.height(10.dp))
            ParamField("投入总资金", state.totalCapitalInput, viewModel::onTotalCapitalChanged, "元")

            // 预览
            state.preview?.let { preview ->
                Spacer(modifier = Modifier.height(16.dp))
                PreviewBlock(preview)
            }

            // 保存失败提示（参数不完整/无效时可见，不再静默无反应）
            state.saveError?.let { err ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = err,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AppTextButton(onClick = onDismiss, modifier = Modifier.weight(1f), text = "取消")
                AppButton(
                    onClick = viewModel::savePlan,
                    modifier = Modifier.weight(1f),
                    // preview 为 null（参数未填全/非法）时禁用，避免「按钮可点但保存静默失败」
                    enabled = state.preview != null &&
                        state.preview?.validationError == null &&
                        state.selectedStockCode.isNotBlank(),
                    text = "保存"
                )
            }
        }
    }
}

@Composable
private fun AnchorSection(
    state: GridPlanUiState,
    viewModel: GridPlanViewModel
) {
    val extendedColors = LocalExtendedColors.current
    Column {
        Text(
            text = "智能锚定（BOLL + 目标股息率）",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            AppTextField(
                value = state.targetYieldInput,
                onValueChange = viewModel::onTargetYieldChanged,
                label = { Text("目标股息率") },
                modifier = Modifier.weight(1f),
                singleLine = true,
                suffix = { Text("%") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
            )
            AppButton(
                onClick = viewModel::autoAnchor,
                modifier = Modifier,
                enabled = !state.isAnchoring && state.selectedStockCode.isNotBlank(),
                text = if (state.isAnchoring) "锁定中…" else "自动锁定"
            )
        }
        // 锚定结果说明：基准=BOLL 中轨、下界来源（技术面/价值底）
        state.anchorInfo?.let { anchor ->
            Spacer(modifier = Modifier.height(8.dp))
            AppCard(elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)) {
                Column(modifier = Modifier.fillMaxWidth().padding(10.dp)) {
                    val source = if (anchor.lowAnchoredByDividend) {
                        "下界由目标股息率 ${"%.1f".format(anchor.targetYieldPercent)}% 锁定（价值底 ${MoneyFormatter.withSymbol(anchor.dividendFloorPrice)}，资金用完位）"
                    } else {
                        "下界由 BOLL 下轨 ${MoneyFormatter.withSymbol(anchor.bollLower)} 锁定（目标股息率底 ${MoneyFormatter.withSymbol(anchor.dividendFloorPrice)} 更高，震荡超卖位优先）"
                    }
                    Text(
                        text = "基准 = BOLL 中轨 ${MoneyFormatter.withSymbol(anchor.basePrice)}\n$source",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "到达下界 ${MoneyFormatter.withSymbol(anchor.lowPrice)} 时股息率达 ${"%.1f".format(anchor.targetYieldPercent)}%，网格资金用完。",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = extendedColors.positive
                    )
                }
            }
        }
        state.anchorError?.let { err ->
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = err,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StockPicker(
    stocks: List<com.stock.dividend.data.local.entity.StockEntity>,
    selectedCode: String,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = stocks.firstOrNull { it.code == selectedCode }?.name
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth()
    ) {
        AppTextField(
            value = selectedName ?: "选择标的",
            onValueChange = {},
            readOnly = true,
            label = { Text("标的股票") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
            colors = OutlinedTextFieldDefaults.colors(
                disabledTextColor = MaterialTheme.colorScheme.onSurface
            )
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            stocks.forEach { stock ->
                DropdownMenuItem(
                    text = { Text("${stock.name} (${stock.code})") },
                    onClick = {
                        onSelect(stock.code)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun ParamField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    suffix: String,
    keyboardType: KeyboardType = KeyboardType.Decimal
) {
    AppTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        suffix = { Text(suffix) },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType)
    )
}

@Composable
private fun PreviewBlock(result: GridResult) {
    AppCard(elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            result.validationError?.let { err ->
                Text(
                    text = err,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                return@AppCard
            }
            Text(
                text = "预览：${result.levels.size} 档（买 ${result.buyLevels.size} / 卖 ${result.sellLevels.size}）",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(6.dp))
            // 买卖资金合计
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FinanceMetric(
                    label = "买入资金",
                    value = MoneyFormatter.withSymbol(result.buyLevels.sumOf { it.amount }),
                    modifier = Modifier.weight(1f)
                )
                FinanceMetric(
                    label = "卖出可挂",
                    value = MoneyFormatter.withSymbol(result.sellLevels.sumOf { it.amount }),
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.End
                )
            }
        }
    }
}

@Composable
private fun EmptyGridPlans(modifier: Modifier = Modifier, onCreate: () -> Unit) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Tune, null, modifier = Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = "还没有网格计划",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "设定基准价/区间/档数/资金，生成买卖档位表，\n震荡市分档套利。仅计划与提示，不自动下单。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(20.dp))
        AppButton(onClick = onCreate, text = "新建网格计划")
    }
}
