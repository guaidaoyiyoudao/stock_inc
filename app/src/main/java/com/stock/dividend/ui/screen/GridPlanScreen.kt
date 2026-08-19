package com.stock.dividend.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stock.dividend.data.local.entity.GRID_TYPE_GEOM
import com.stock.dividend.data.local.entity.GRID_TYPE_YIELD
import com.stock.dividend.data.local.entity.GridPlanEntity
import com.stock.dividend.data.repository.GridBacktestResult
import com.stock.dividend.data.repository.GridExecution
import com.stock.dividend.data.repository.GridLevel
import com.stock.dividend.data.repository.GridLevelFill
import com.stock.dividend.data.repository.GridResult
import com.stock.dividend.data.repository.GridType
import com.stock.dividend.data.repository.MoneyFormatter
import com.stock.dividend.data.repository.PercentFormatter
import com.stock.dividend.ui.component.AppButton
import com.stock.dividend.ui.component.AppCard
import com.stock.dividend.ui.component.AppCardDefaults
import com.stock.dividend.ui.component.AppTextButton
import com.stock.dividend.ui.component.AppTextField
import com.stock.dividend.ui.component.CompactTopAppBar
import com.stock.dividend.ui.component.FinanceMetric
import com.stock.dividend.ui.component.GridLevelScale
import com.stock.dividend.ui.theme.LocalExtendedColors
import com.stock.dividend.ui.theme.tabularNumberStyle
import com.stock.dividend.viewmodel.GridPlanItem
import com.stock.dividend.viewmodel.GridPlanUiState
import com.stock.dividend.viewmodel.GridPlanViewModel
import com.stock.dividend.viewmodel.ReanchorDiff

/**
 * 网格交易计划页：展示已保存的网格计划（含「下一档」提示），FAB 唤起生成器
 * （参数实时预览档位表），可保存/编辑/删除。**仅计划与提示，不联网下单**。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GridPlanScreen(
    onBack: () -> Unit,
    onAddTransaction: (stockCode: String, price: Double, shares: Int) -> Unit = { _, _, _ -> },
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
                    text = "纯买入网格：从买入起点分档买入到资金用完位，不设卖出档，不会自动下单。请在券商端手动执行。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
            // 弹药库汇总：全部计划合计（还剩多少子弹）
            state.ammoSummary?.let { ammo ->
                item(key = "ammo") {
                    AmmoSummaryCard(ammo)
                }
            }
            items(state.items, key = { it.plan.id }) { item ->
                GridPlanCard(
                    item = item,
                    state = state,
                    onEdit = { viewModel.editPlan(item.plan) },
                    onDelete = { viewModel.deletePlan(item.plan.id) },
                    onToggleNotify = { viewModel.toggleNotify(item.plan) },
                    onReanchor = { viewModel.reanchorPlan(item.plan) },
                    onBacktest = { viewModel.backtestPlan(item.plan) },
                    onAddTransaction = onAddTransaction
                )
            }
        }

        if (state.showGenerator) {
            GridGeneratorSheet(state, viewModel, onDismiss = viewModel::dismissGenerator)
        }

        // 一键重锚定确认弹窗：新旧三价对比，确认后保存并重置到档提醒状态
        state.reanchorDiff?.let { diff ->
            ReanchorConfirmDialog(
                diff = diff,
                onConfirm = viewModel::confirmReanchor,
                onDismiss = viewModel::dismissReanchor
            )
        }
    }
}

/** 弹药库汇总卡：全部网格计划的合计总资金/已投入/剩余/加权进度。 */
@Composable
private fun AmmoSummaryCard(ammo: com.stock.dividend.data.repository.GridAmmoSummary) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "弹药库 · ${ammo.planCount} 套网格",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "已触发 ${ammo.triggeredLevels}/${ammo.totalLevels}（${ammo.progressPercent}%）",
                    style = MaterialTheme.typography.labelMedium.merge(tabularNumberStyle),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FinanceMetric(
                    label = "总资金",
                    value = MoneyFormatter.withSymbol(ammo.totalCapital),
                    modifier = Modifier.weight(1f)
                )
                FinanceMetric(
                    label = "已投入",
                    value = MoneyFormatter.withSymbol(ammo.investedAmount),
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center
                )
                FinanceMetric(
                    label = "剩余可投",
                    value = MoneyFormatter.withSymbol(ammo.remainingCapital),
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.End
                )
            }
        }
    }
}

/** 重锚定确认弹窗：展示新旧三价对比与采用的目标股息率。 */
@Composable
private fun ReanchorConfirmDialog(
    diff: ReanchorDiff,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重新锁定网格") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "按最新 BOLL + 分红重新锚定（目标股息率 ${"%.1f".format(diff.targetYieldUsed)}%）：",
                    style = MaterialTheme.typography.bodySmall
                )
                ReanchorRow("买入起点", diff.plan.basePrice, diff.newBasePrice)
                ReanchorRow("资金用完位", diff.plan.lowPrice, diff.newLowPrice)
                ReanchorRow("参考上界", diff.plan.highPrice, diff.newHighPrice)
                Text(
                    "确认后档位参数将更新，到档提醒状态重置。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            AppTextButton(onClick = onConfirm, text = "确认更新")
        },
        dismissButton = {
            AppTextButton(onClick = onDismiss, text = "取消")
        }
    )
}

@Composable
private fun ReanchorRow(label: String, old: Double, new: Double) {
    val changed = old != new
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = "%.2f → %.2f".format(old, new) + if (changed) "" else "（不变）",
            style = MaterialTheme.typography.bodySmall.merge(tabularNumberStyle),
            fontWeight = if (changed) FontWeight.SemiBold else FontWeight.Normal,
            color = if (changed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun GridPlanCard(
    item: GridPlanItem,
    state: GridPlanUiState,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggleNotify: () -> Unit,
    onReanchor: () -> Unit,
    onBacktest: () -> Unit,
    onAddTransaction: (stockCode: String, price: Double, shares: Int) -> Unit
) {
    val plan = item.plan
    val result = item.result
    val extendedColors = LocalExtendedColors.current
    // 卡片默认收起：多计划并览时一屏看多只；点名称区/箭头展开全部明细
    var expanded by rememberSaveable(plan.id) { mutableStateOf(false) }

    AppCard(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { expanded = !expanded }
                ) {
                    Text(
                        text = plan.stockName + (if (expanded) "" else "  ▸"),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "基准 ${MoneyFormatter.withSymbol(plan.basePrice)} · " +
                            "${plan.lowPrice}–${plan.highPrice} · ${plan.grids} 档" +
                            gridTypeLabel(plan) +
                            " · 资金 ${MoneyFormatter.withSymbol(plan.totalCapital)}",
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
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        if (expanded) "收起" else "展开明细",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            // ── 收起态摘要行：一行看完核心（现价 · 下一买 · 执行进度）──
            if (!expanded) {
                CollapsedSummaryRow(item, result)
                // 收起态仍要可见的重锚定预警（一键重锚定按钮在展开态）
                item.stalenessHint?.let { hint ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "⚠ $hint",
                        style = MaterialTheme.typography.labelSmall,
                        color = extendedColors.negative
                    )
                }
            }

            if (expanded) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // 到档提醒开关（价格到达下一买入档时推送本地通知；通知检查每小时一次）
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "到档提醒",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Switch(
                        checked = plan.notifyEnabled,
                        onCheckedChange = { onToggleNotify() }
                    )
                }

                // 系统通知权限被关 → 到档提醒会静默失效，给可见提示
                if (state.notificationBlocked == true) {
                    Text(
                        text = "⚠ 系统通知已关闭，到档提醒无法推送（请在系统设置中允许本应用通知）",
                        style = MaterialTheme.typography.labelSmall,
                        color = extendedColors.negative
                    )
                }
                // 一键重锚定失败提示（数据不足等）
                state.reanchorError?.let { err ->
                    Text(
                        text = "⚠ $err",
                        style = MaterialTheme.typography.labelSmall,
                        color = extendedColors.negative
                    )
                }

                // 档位刻度尺：价格轴上一眼看出各档/已触发/下一买与现价距离
                if (result.levels.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    GridLevelScale(
                        currentPrice = item.currentPrice,
                        levels = result.levels,
                        nextBuyHint = result.nextBuyHint
                    )
                }

                // 股息展望：全部打完后的年股息（收息定位的终极答案）
                item.dividendOutlook?.let { outlook ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .background(extendedColors.positive.copy(alpha = 0.08f))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "全部打完后预计年股息",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "${MoneyFormatter.withSymbol(outlook.annualDividend)} · 占资金 " +
                                (outlook.yieldOnCapitalPct?.let { "${"%.1f".format(it)}%" } ?: "—"),
                            style = MaterialTheme.typography.labelMedium.merge(tabularNumberStyle),
                            color = extendedColors.positive,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                // 资金执行跟踪（已投入/剩余/浮盈）—— 有实际买入时才展示
                ExecutionSummary(item.execution, item.holdingShares)

                // 计划过期预警（现价远高于买入起点，行情已偏离当初锚定）
                item.stalenessHint?.let { hint ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .background(extendedColors.negative.copy(alpha = 0.1f))
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "⚠ $hint",
                            style = MaterialTheme.typography.labelMedium,
                            color = extendedColors.negative,
                            modifier = Modifier.weight(1f)
                        )
                        // 一键重锚定：重拉 BOLL+分红 → 弹窗确认新旧三价
                        AppTextButton(
                            onClick = onReanchor,
                            text = if (state.isReanchoring) "锁定中…" else "重新锁定"
                        )
                    }
                }

                // 「下一档」提示 + 一键记账
                if (item.currentPrice != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "现价 ${MoneyFormatter.withSymbol(item.currentPrice)}",
                            style = MaterialTheme.typography.labelMedium.merge(tabularNumberStyle),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        result.nextBuyHint?.let { buy ->
                            // 找到该档对应的建议股数（用于预填交易表单）
                            val level = result.levels.firstOrNull { it.price == buy }
                            Text(
                                text = "下一买 ${MoneyFormatter.withSymbol(buy)}",
                                style = MaterialTheme.typography.labelMedium.merge(tabularNumberStyle),
                                color = extendedColors.positive
                            )
                            // 一键记账：跳到加交易页，预填该档价格/股数
                            AppTextButton(
                                onClick = {
                                    onAddTransaction(plan.stockCode, buy, level?.shares ?: 100)
                                },
                                text = "记账"
                            )
                        }
                        if (result.nextBuyHint == null) {
                            // 区分两种「无下一档」：跌破用完位 vs 下方档全部已买（等更深的未买档）
                            val cp = item.currentPrice
                            val allBought = cp != null && cp > plan.lowPrice &&
                                result.levels.filter { it.price < cp }.all { it.triggered }
                            Text(
                                text = if (allBought) "下方档位已全部买入" else "已到/跌破资金用完位",
                                style = MaterialTheme.typography.labelMedium.merge(tabularNumberStyle),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // 档位表（紧凑双列：价格 / 方向 / 股数）
                if (result.levels.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    // 触发进度：已触发档 / 总档（关联实际交易记录）
                    val triggeredCount = result.levels.count { it.triggered }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        // 按股息率计划：标题展示股息率区间与每档步长；价格步长模式展示步长幅度
                        val firstYield = result.levels.firstOrNull()?.yieldPercent
                        val lastYield = result.levels.lastOrNull()?.yieldPercent
                        Text(
                            text = if (firstYield != null && lastYield != null && result.yieldStepPercent != null) {
                                "档位表（股息率 ${"%.1f".format(lastYield)}%→${"%.1f".format(firstYield)}%" +
                                    "，每档 +${"%.2f".format(result.yieldStepPercent)}）"
                            } else {
                                "档位表（步长 ${"%.1f".format(result.stepPercent)}%）"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = if (triggeredCount > 0) {
                                "已触发 $triggeredCount/${result.levels.size}"
                            } else {
                                "均未触发"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = if (triggeredCount > 0) extendedColors.positive else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    result.levels.forEach { level ->
                        GridLevelRow(level, item.currentPrice, item.fillsByLevel[level.price])
                    }
                }

            // ── 历史回测（按需触发）──
            BacktestSection(
                planId = plan.id,
                state = state,
                onBacktest = onBacktest
            )
            }
            }
        }
    }
}

/** 回测区块：运行按钮 → 触发/均价/一次性对比/资金使用率 + 口径声明。 */
@Composable
private fun BacktestSection(
    planId: String,
    state: GridPlanUiState,
    onBacktest: () -> Unit
) {
    val extendedColors = LocalExtendedColors.current
    Spacer(modifier = Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "历史回测（近 250 交易日）",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        AppTextButton(
            onClick = onBacktest,
            text = if (planId in state.backtestingIds) "回测中…" else
                if (state.backtestResults.containsKey(planId)) "重跑" else "运行"
        )
    }
    state.backtestErrors[planId]?.let { err ->
        Text(
            text = "⚠ $err",
            style = MaterialTheme.typography.labelSmall,
            color = extendedColors.negative
        )
    }
    state.backtestResults[planId]?.let { r -> BacktestResultRows(r) }
}

@Composable
private fun BacktestResultRows(r: GridBacktestResult) {
    val extendedColors = LocalExtendedColors.current
    Spacer(modifier = Modifier.height(4.dp))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FinanceMetric(
            label = "触发",
            value = "${r.triggeredCount}/${r.totalLevels} 档",
            modifier = Modifier.weight(1f)
        )
        FinanceMetric(
            label = "网格均价",
            value = r.avgBuyPrice?.let { MoneyFormatter.withSymbol(it) } ?: "—",
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center
        )
        FinanceMetric(
            label = "一次性对比",
            value = r.costSavingPct?.let { "省 ${"%.1f".format(it)}%" } ?: "—",
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End
        )
    }
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = "窗口 ${r.windowStart} ~ ${r.windowEnd}（${r.tradingDays} 日，最低 ${"%.2f".format(r.minClose)}）" +
            " · 首日 ${MoneyFormatter.withSymbol(r.lumpSumPrice)} 一次性买 ${r.lumpSumShares} 股" +
            " · 资金使用 ${r.capitalUtilizationPct?.let { "${"%.0f".format(it)}%" } ?: "—"}",
        style = MaterialTheme.typography.labelSmall.merge(tabularNumberStyle),
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Text(
        text = "口径：按日收盘价回放（盘中触及未还原），成交按档位价假设。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    )
}

/** 收起态摘要行：一行看完核心——现价 · 下一买（距下一档跌幅）· 执行进度与已投入。 */
@Composable
private fun CollapsedSummaryRow(item: GridPlanItem, result: GridResult) {
    val extendedColors = LocalExtendedColors.current
    Spacer(modifier = Modifier.height(6.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "现价 " + (item.currentPrice?.let { MoneyFormatter.withSymbol(it) } ?: "—"),
            style = MaterialTheme.typography.labelMedium.merge(tabularNumberStyle),
            color = MaterialTheme.colorScheme.onSurface
        )
        val next = result.nextBuyHint
        val price = item.currentPrice
        when {
            // 有待买的下一档：价格 + 距离
            next != null && price != null && next < price -> {
                val gap = (next - price) / price * 100.0
                Text(
                    text = "下一买 ${MoneyFormatter.withSymbol(next)}（${"%.1f".format(gap)}%）",
                    style = MaterialTheme.typography.labelMedium.merge(tabularNumberStyle),
                    color = extendedColors.positive,
                    fontWeight = FontWeight.SemiBold
                )
            }
            // 无下一档且现价已知：区分「跌破用完位」与「下方档全买完」两种语义
            next == null && price != null -> {
                val low = result.levels.firstOrNull()?.price
                val allBought = low != null && price > low &&
                    result.levels.filter { it.price < price }.all { it.triggered }
                Text(
                    text = if (allBought) "下方档位已全部买入" else "已到/跌破资金用完位",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Text(
            text = if (item.execution.totalLevels > 0) {
                "${item.execution.triggeredCount}/${item.execution.totalLevels} 档 · " +
                    MoneyFormatter.withSymbol(item.execution.investedAmount)
            } else "未生效",
            style = MaterialTheme.typography.labelMedium.merge(tabularNumberStyle),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 资金执行跟踪摘要：进度条 + 已投入/剩余 + 浮盈 + 持仓口径。有买入才展示，否则隐藏（返回空）。 */
@Composable
private fun ExecutionSummary(execution: GridExecution, holdingShares: Int = 0) {
    if (execution.triggeredCount == 0) return  // 无买入，不展示，避免噪音
    val extendedColors = LocalExtendedColors.current
    Spacer(modifier = Modifier.height(8.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "执行进度 ${execution.triggeredCount}/${execution.totalLevels}（${execution.progressPercent}%）",
                style = MaterialTheme.typography.labelMedium.merge(tabularNumberStyle),
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(6.dp))
            // 进度条（用 surfaceVariant 槽 + primary 填充）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(MaterialTheme.shapes.extraSmall)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(execution.progressPercent / 100f)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }
    }
    Spacer(modifier = Modifier.height(8.dp))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FinanceMetric(
            label = "已投入",
            value = MoneyFormatter.withSymbol(execution.investedAmount),
            modifier = Modifier.weight(1f)
        )
        FinanceMetric(
            label = "剩余可投",
            value = MoneyFormatter.withSymbol(execution.remainingCapital),
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center
        )
        FinanceMetric(
            label = "浮盈/亏",
            value = execution.unrealizedPnl?.let { pnl ->
                "${if (pnl >= 0) "+" else ""}${MoneyFormatter.amount(pnl)}" +
                    (execution.unrealizedPnlRate?.let { r -> " ${if (r >= 0) "+" else ""}${"%.1f".format(r)}%" } ?: "")
            } ?: "—",
            valueColor = execution.unrealizedPnl?.let { if (it >= 0) extendedColors.positive else extendedColors.negative }
                ?: MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End
        )
    }
    execution.avgBuyPrice?.let { avg ->
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "网格累计买入 ${execution.boughtShares} 股 · 均价 ${MoneyFormatter.withSymbol(avg)}" +
                // 实际持仓与网格累计买入是两个口径：卖出/网格外买入都会造成差异
                (if (holdingShares != execution.boughtShares && holdingShares > 0) {
                    " · 当前实际持仓 ${holdingShares} 股"
                } else ""),
            style = MaterialTheme.typography.bodySmall.merge(tabularNumberStyle),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    // 执行偏差（实际成交价 vs 档位价）：检验手动执行有没有跟上网格（正=买贵了）
    execution.avgDeviationPercent?.let { avg ->
        val worst = execution.worstDeviationPercent
        if (worst != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "执行偏差 平均 ${PercentFormatter.withSign(avg)} · 最差 ${PercentFormatter.withSign(worst)}" +
                    "（正=成交价高于档位价）",
                style = MaterialTheme.typography.bodySmall.merge(tabularNumberStyle),
                color = when {
                    avg > 0.0 -> extendedColors.negative   // 买贵了 → 警示
                    avg < 0.0 -> extendedColors.positive    // 买得更便宜 → 有利
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}

@Composable
private fun GridLevelRow(level: GridLevel, currentPrice: Double?, fill: GridLevelFill? = null) {
    // 当前价恰好落在该档附近（误差 < 0.5%）时高亮
    val near = currentPrice != null && kotlin.math.abs(level.price - currentPrice) / level.price < 0.005
    val extendedColors = LocalExtendedColors.current
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
            // 已触发档位价格淡化（已被实际买入执行）
            color = when {
                level.triggered -> MaterialTheme.colorScheme.outline
                near -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurface
            }
        )
        // 纯买入模型：所有档位均为「买」；已触发档标 ✓ 并尾注最近成交（MM/dd ×累计股数，多笔标笔数）
        Text(
            text = if (level.triggered) {
                "买✓" + (fill?.let { f ->
                    val date = f.lastDate?.takeLast(5)?.replace("-", "/") ?: ""
                    (if (f.fills > 1) "×${f.fills} " else " ") + "$date ×${f.shares}"
                } ?: "")
            } else "买",
            style = MaterialTheme.typography.labelSmall.merge(tabularNumberStyle),
            color = extendedColors.positive,
            modifier = Modifier.weight(1.4f).padding(horizontal = 8.dp)
        )
        Text(
            text = "${level.shares} 股",
            style = MaterialTheme.typography.bodySmall.merge(tabularNumberStyle),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f)
        )
        // 按股息率计划：最后一列展示该档股息率（收息视角的核心数字）；
        // 价格步长模式展示相对买入起点的偏离%
        if (level.yieldPercent != null) {
            Text(
                text = "息 ${"%.2f".format(level.yieldPercent)}%",
                style = MaterialTheme.typography.bodySmall.merge(tabularNumberStyle),
                color = extendedColors.positive,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f)
            )
        } else {
            Text(
                text = "${if (level.deviation >= 0) "+" else ""}${"%.1f".format(level.deviation)}%",
                style = MaterialTheme.typography.bodySmall.merge(tabularNumberStyle),
                color = extendedColors.positive,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/** 计划卡副标题的档位分布标记：等比 / 按股息率 a%→b%（由存档 DPS 快照反推，缺失只标「按股息率」）。 */
private fun gridTypeLabel(plan: GridPlanEntity): String = when {
    plan.gridType == GRID_TYPE_GEOM -> " 等比"
    plan.gridType == GRID_TYPE_YIELD -> {
        val dps = plan.dpsPerShare
        if (dps != null && dps > 0.0 && plan.basePrice > 0.0 && plan.lowPrice > 0.0) {
            " 按股息率 ${"%.1f".format(dps / plan.basePrice * 100.0)}%" +
                "→${"%.1f".format(dps / plan.lowPrice * 100.0)}%"
        } else " 按股息率"
    }
    else -> ""
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
                // 关键：底部弹层内容加滚动，否则自动锚定展开说明/预览后保存按钮被挤出屏幕外（无法滚到）
                .verticalScroll(rememberScrollState())
                .imePadding()
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

            // ── 智能锚定（日/周/月 BOLL + 目标股息率 → 纯买入网格）；按股息率模式直接以股息率定义档位，不走 BOLL ──
            if (state.gridTypeInput != GridType.YIELD) {
                AnchorSection(state, viewModel)
                Spacer(modifier = Modifier.height(12.dp))
            }

            // 档位分布：等差（绝对价差均分）/ 等比（百分比步长）/ 按股息率（收息视角）
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.gridTypeInput == GridType.ARITHMETIC,
                    onClick = { viewModel.onGridTypeChanged(GridType.ARITHMETIC) },
                    label = { Text("等差") }
                )
                FilterChip(
                    selected = state.gridTypeInput == GridType.GEOMETRIC,
                    onClick = { viewModel.onGridTypeChanged(GridType.GEOMETRIC) },
                    label = { Text("等比") }
                )
                FilterChip(
                    selected = state.gridTypeInput == GridType.YIELD,
                    onClick = { viewModel.onGridTypeChanged(GridType.YIELD) },
                    label = { Text("按股息率") }
                )
            }
            Text(
                text = when (state.gridTypeInput) {
                    GridType.GEOMETRIC -> "相邻档按固定百分比递减"
                    GridType.YIELD -> "每档买入价 = 年分红 ÷ 股息率（如 5.5%、6%、6.5% 三档）"
                    GridType.ARITHMETIC -> "相邻档按固定价差均分"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
            Spacer(modifier = Modifier.height(10.dp))

            if (state.gridTypeInput == GridType.YIELD) {
                YieldSection(state, viewModel)
                Spacer(modifier = Modifier.height(10.dp))
            } else {
                ParamField("买入起点（第一档）", state.basePriceInput, viewModel::onBasePriceChanged, "元/股")
                Spacer(modifier = Modifier.height(10.dp))
                ParamField("资金用完位（最后一档）", state.lowPriceInput, viewModel::onLowPriceChanged, "元/股")
                Spacer(modifier = Modifier.height(10.dp))
                ParamField("参考上界（超过不追买）", state.highPriceInput, viewModel::onHighPriceChanged, "元/股")
                Spacer(modifier = Modifier.height(10.dp))
            }
            ParamField("买入档数", state.gridsInput, viewModel::onGridsChanged, "档", KeyboardType.Number)
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

/** 按股息率模式的参数区：起始/结束股息率输入 + 换算基准（每股年分红）信息行。 */
@Composable
private fun YieldSection(state: GridPlanUiState, viewModel: GridPlanViewModel) {
    val extendedColors = LocalExtendedColors.current
    Column {
        ParamField(
            label = "起始股息率（第一档，最贵）",
            value = state.yieldStartInput,
            onChange = viewModel::onYieldStartChanged,
            suffix = "%"
        )
        Spacer(modifier = Modifier.height(10.dp))
        ParamField(
            label = "结束股息率（最后一档，资金用完）",
            value = state.yieldEndInput,
            onChange = viewModel::onYieldEndChanged,
            suffix = "%"
        )
        Spacer(modifier = Modifier.height(8.dp))
        // 换算基准：年度每股分红（Room 本地分红记录；选标的时自动读取）
        when {
            state.selectedStockCode.isBlank() -> Text(
                text = "选择标的后自动读取年度每股分红，作为档位价换算基准",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            state.generatorDps != null -> Text(
                text = "每股年分红 ${MoneyFormatter.withSymbol(state.generatorDps)} · " +
                    "跌到股息率 ${state.yieldStartInput.toDoubleOrNull()?.let { "${"%.1f".format(it)}%" } ?: "—"} 开始买入，" +
                    "${state.yieldEndInput.toDoubleOrNull()?.let { "${"%.1f".format(it)}%" } ?: "—"} 资金用完",
                style = MaterialTheme.typography.labelSmall.merge(tabularNumberStyle),
                color = extendedColors.positive
            )
            else -> Text(
                text = "⚠ 该股暂无分红数据，无法按股息率分档（请先在个股详情页刷新分红）",
                style = MaterialTheme.typography.labelSmall,
                color = extendedColors.negative
            )
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
            text = "智能锚定（日/周/月 BOLL + 目标股息率）",
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
        // 锚定结果说明：买入起点=日/周下轨、月BOLL中轨及以下；资金用完位来源（技术面/价值底）
        state.anchorInfo?.let { anchor ->
            Spacer(modifier = Modifier.height(8.dp))
            AppCard(elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)) {
                Column(modifier = Modifier.fillMaxWidth().padding(10.dp)) {
                    val source = if (anchor.lowAnchoredByDividend) {
                        "资金用完位由目标股息率 ${"%.1f".format(anchor.targetYieldPercent)}% 锁定（价值底 ${MoneyFormatter.withSymbol(anchor.dividendFloorPrice)}）"
                    } else {
                        "资金用完位由 BOLL 下轨 ${MoneyFormatter.withSymbol(anchor.bollLower)} 锁定（目标股息率底 ${MoneyFormatter.withSymbol(anchor.dividendFloorPrice)} 更高，技术超卖位优先）"
                    }
                    Text(
                        text = "买入起点 = 日/周 BOLL 下轨、月 BOLL 中轨及以下 → ${MoneyFormatter.withSymbol(anchor.basePrice)}" +
                            "（月线中枢 ${MoneyFormatter.withSymbol(anchor.monthlyMiddle)}）\n$source",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "纯买入网格：跌到起点分档买入，跌到 ${MoneyFormatter.withSymbol(anchor.lowPrice)}（股息率 ${"%.1f".format(anchor.targetYieldPercent)}%）资金用完，不设卖出档。",
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
                text = "预览：${result.levels.size} 档全部为买入（资金用完位前分批建仓）",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(6.dp))
            // 纯买入：显示总买入资金 + 参考上界
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FinanceMetric(
                    label = "买入资金",
                    value = MoneyFormatter.withSymbol(result.buyLevels.sumOf { it.amount }),
                    modifier = Modifier.weight(1f)
                )
                FinanceMetric(
                    label = "参考上界（不追买）",
                    value = MoneyFormatter.withSymbol(result.highPrice),
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
            text = "用 BOLL + 目标股息率智能锚定买入区间，生成纯买入档位表，\n越跌越买、持有收息，到档自动提醒。仅计划与提示，不自动下单。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(20.dp))
        AppButton(onClick = onCreate, text = "新建网格计划")
    }
}
