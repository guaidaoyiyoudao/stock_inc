package com.stock.dividend.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import nl.dionsegijn.konfetti.compose.KonfettiView
import nl.dionsegijn.konfetti.core.Party
import nl.dionsegijn.konfetti.core.Position
import nl.dionsegijn.konfetti.core.emitter.Emitter
import java.util.concurrent.TimeUnit
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stock.dividend.R
import com.stock.dividend.data.local.entity.StockEntity
import com.stock.dividend.data.repository.MoneyFormatter
import com.stock.dividend.ui.component.AppButton
import com.stock.dividend.ui.component.AppCardDefaults
import com.stock.dividend.ui.component.CategorizedAchievementList
import com.stock.dividend.ui.component.IncomeBreakdownChart
import com.stock.dividend.ui.component.IncomeSummaryCard
import com.stock.dividend.ui.component.IncomeTimelineCard
import com.stock.dividend.ui.component.IncomeTrendChart
import com.stock.dividend.ui.component.SectionHeader
import com.stock.dividend.ui.component.YearSelector
import com.stock.dividend.ui.theme.tabularNumberStyle
import com.stock.dividend.viewmodel.AchievementUiState
import com.stock.dividend.viewmodel.AchievementViewModel
import com.stock.dividend.viewmodel.DividendIncomeRecordWithStock
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
    var showAddIncomeSheet by remember { mutableStateOf(false) }
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
                onAddIncomeClick = { showAddIncomeSheet = true }
            )
        } else {
            // 原独立「日历」tab 内容；其内部的 registerTabRefresh 会在该视图激活时
            // 自动让悬浮刷新按钮显示。
            DividendCalendarScreen()
        }
    }

    if (showAddIncomeSheet) {
            AddIncomeSheet(
                stocks = state.stocks,
                onDismiss = { showAddIncomeSheet = false },
                onConfirm = { date, amount, stockCode, note ->
                    viewModel.addManualRecord(date, amount, stockCode, note)
                    showAddIncomeSheet = false
                }
            )
        }

        if (state.showCorrectDialog) {
            val target = state.records.firstOrNull { it.record.id == state.correctTargetId }
            if (target != null) {
                CorrectAmountSheet(
                    target = target,
                    onDismiss = viewModel::dismissCorrectDialog,
                    onConfirm = { amount, note ->
                        viewModel.correctRecord(state.correctTargetId, amount, note)
                    }
                )
            } else {
                // 目标记录已不在当前列表（理论不可达）：复位状态避免修正弹层悬挂
                LaunchedEffect(state.correctTargetId) { viewModel.dismissCorrectDialog() }
            }
        }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AchievementScreen(
    viewModel: AchievementViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // 本次会话新解锁 → 撒花庆祝一次（粒子自行消亡，4s 后移除覆盖层）
    var celebrate by remember { mutableStateOf(false) }
    LaunchedEffect(state.newlyUnlockedIds) {
        if (state.newlyUnlockedIds.isNotEmpty()) {
            celebrate = true
            kotlinx.coroutines.delay(4000)
            celebrate = false
            // 一次性事件消费（2026-08-24 评审修复）：清空后重进成就页/配置重建不再重复撒花
            viewModel.consumeNewlyUnlocked()
        }
    }

    Scaffold { padding ->
        Box(modifier = Modifier.padding(padding)) {
            AchievementTabContent(
                state = state,
                modifier = Modifier.fillMaxSize()
            )
            if (celebrate) {
                KonfettiView(
                    modifier = Modifier.fillMaxSize(),
                    parties = remember {
                        listOf(
                            Party(
                                speed = 25f,
                                maxSpeed = 50f,
                                damping = 0.9f,
                                spread = 90,
                                angle = 270,
                                position = Position.Relative(0.2, 1.0),
                                emitter = Emitter(duration = 500, TimeUnit.MILLISECONDS).max(60),
                                colors = listOf(0xfce18a, 0xff726d, 0xb48def, 0xf4306d),
                            ),
                            Party(
                                speed = 25f,
                                maxSpeed = 50f,
                                damping = 0.9f,
                                spread = 90,
                                angle = 270,
                                position = Position.Relative(0.8, 1.0),
                                emitter = Emitter(duration = 500, TimeUnit.MILLISECONDS).max(60),
                                colors = listOf(0xfce18a, 0xff726d, 0xb48def, 0xf4306d),
                            ),
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun IncomeTabContent(
    state: DividendIncomeUiState,
    viewModel: DividendIncomeViewModel,
    onAddIncomeClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 整页单列表滚动：头部（年份/趋势/汇总/占比）与收入记录一起下拉，
    // 而非头部固定、仅记录区滚动（用户反馈希望整 tab 页直接下拉）
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 8.dp,
            bottom = AppCardDefaults.BottomNavigationPadding
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item(key = "year") {
            YearSelector(
                years = state.availableYears.ifEmpty { listOf(state.selectedYear) },
                selectedYear = state.selectedYear,
                onYearSelected = { viewModel.selectYear(it) },
            )
        }
        item(key = "trend") {
            IncomeTrendChart(
                yearlyTotals = state.yearlyTotals,
                selectedYear = state.selectedYear,
                onYearClick = { viewModel.selectYear(it) },
            )
        }
        item(key = "summary") {
            IncomeSummaryCard(
                year = state.selectedYear,
                totalAmount = state.yearlyTotal,
                prevYearTotal = state.prevYearTotal,
                manualCount = state.manualCount,
                autoCount = state.autoCount,
            )
        }
        item(key = "breakdown") {
            // 占比图默认收起：饼图+图例约一屏高，展开即占满视口；点标题行按需展开
            // rememberSaveable：条目滚出视口被回收后展开态保留（2026-08-24 评审修复）
            var breakdownExpanded by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
            Column {
                SectionHeader(
                    title = "股票占比",
                    actionText = if (breakdownExpanded) "收起" else "展开",
                    onActionClick = { breakdownExpanded = !breakdownExpanded },
                    modifier = Modifier.padding(horizontal = 0.dp)
                )
                androidx.compose.animation.AnimatedVisibility(visible = breakdownExpanded) {
                    IncomeBreakdownChart(records = state.records)
                }
            }
        }
        item(key = "records_header") {
            SectionHeader(
                title = stringResource(R.string.income_section_records),
                actionText = stringResource(R.string.income_action_add),
                actionIcon = Icons.Default.Add,
                onActionClick = onAddIncomeClick,
                modifier = Modifier.padding(horizontal = 0.dp)
            )
        }
        if (state.records.isEmpty()) {
            item(key = "empty") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
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

/**
 * 添加收入底部弹层（M3 ModalBottomSheet，形态同网格生成器）：可选关联自选股
 * （不关联记作「其他收入」），到账日期默认当天、可点日历补记历史；
 * 金额输入校验与修正弹层一致（数字键盘 + 非法标错 + 无效禁用确认）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddIncomeSheet(
    stocks: List<StockEntity>,
    onDismiss: () -> Unit,
    onConfirm: (date: String, amount: Double, stockCode: String?, note: String?) -> Unit
) {
    var selectedStockCode by remember { mutableStateOf<String?>(null) }
    var amountText by remember { mutableStateOf("") }
    var noteText by remember { mutableStateOf("") }
    var selectedDate by remember {
        mutableStateOf(java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE))
    }
    var showDatePicker by remember { mutableStateOf(false) }
    val parsedAmount = amountText.toDoubleOrNull()
    val amountInvalid = amountText.isNotBlank() && parsedAmount == null

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                text = "添加收入",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(12.dp))

            IncomeStockPicker(
                stocks = stocks,
                selectedCode = selectedStockCode,
                onSelect = { selectedStockCode = it }
            )
            Spacer(modifier = Modifier.height(12.dp))

            AppTextField(
                value = amountText,
                onValueChange = { amountText = it },
                label = { Text("金额") },
                prefix = { Text("¥") },
                singleLine = true,
                isError = amountInvalid,
                supportingText = if (amountInvalid) {
                    { Text("请输入有效金额") }
                } else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(12.dp))

            // 只读日期框 + 日历弹窗（enabled=false 需外层 clickable 接管点击）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showDatePicker = true }
            ) {
                AppTextField(
                    value = selectedDate,
                    onValueChange = {},
                    label = { Text("到账日期") },
                    modifier = Modifier.fillMaxWidth(),
                    readOnly = true,
                    enabled = false,
                    leadingIcon = {
                        Icon(Icons.Default.DateRange, contentDescription = null)
                    },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        disabledTextColor = MaterialTheme.colorScheme.onSurface,
                        disabledBorderColor = MaterialTheme.colorScheme.outlineVariant,
                        disabledLeadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
            }
            Spacer(modifier = Modifier.height(12.dp))

            AppTextField(
                value = noteText,
                onValueChange = { noteText = it },
                label = { Text("备注 (可选)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(20.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AppTextButton(onClick = onDismiss, modifier = Modifier.weight(1f), text = "取消")
                AppButton(
                    onClick = {
                        parsedAmount?.let {
                            onConfirm(selectedDate, it, selectedStockCode, noteText.ifBlank { null })
                        }
                    },
                    enabled = parsedAmount != null && parsedAmount > 0,
                    modifier = Modifier.weight(1f),
                    text = "确认",
                )
            }
        }
    }

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(
            // M3 DatePicker 内部按 UTC 日界换算：用系统时区（东八区）换算会把初始高亮日
            // 推早一天、直接确认即静默回退一天（2026-08-24 评审修复）——两侧统一按 UTC
            initialSelectedDateMillis = try {
                java.time.LocalDate.parse(selectedDate)
                    .atStartOfDay(java.time.ZoneOffset.UTC)
                    .toInstant().toEpochMilli()
            } catch (_: Exception) { null }
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                AppTextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { millis ->
                            selectedDate = java.time.Instant.ofEpochMilli(millis)
                                .atZone(java.time.ZoneOffset.UTC)
                                .toLocalDate()
                                .toString()
                        }
                        showDatePicker = false
                    },
                    text = "确认",
                )
            },
            dismissButton = {
                AppTextButton(
                    onClick = { showDatePicker = false },
                    text = "取消",
                )
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }
}

/** 添加收入的股票选择：下拉选自选股，首项「不关联（其他收入）」可回退为空。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IncomeStockPicker(
    stocks: List<StockEntity>,
    selectedCode: String?,
    onSelect: (String?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = stocks.firstOrNull { it.code == selectedCode }?.name
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth()
    ) {
        AppTextField(
            value = selectedName ?: "不关联（其他收入）",
            onValueChange = {},
            readOnly = true,
            label = { Text("关联股票 (可选)") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            DropdownMenuItem(
                text = { Text("不关联（其他收入）") },
                onClick = {
                    onSelect(null)
                    expanded = false
                }
            )
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

/**
 * 修正金额底部弹层（M3 ModalBottomSheet，形态同网格生成器）：推算记录（auto）
 * 修正为实际到账金额；手动记录（manual）复用为「编辑收入」。展示记录上下文
 * （股票 · 日期）与原金额参考，金额输入限制数字键盘、非法输入标错并禁用确认。
 * 弹层随 showCorrectDialog 离开组合即重置输入。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CorrectAmountSheet(
    target: DividendIncomeRecordWithStock,
    onDismiss: () -> Unit,
    onConfirm: (amount: Double, note: String?) -> Unit
) {
    val isManual = target.record.source == "manual"
    var amountText by remember { mutableStateOf("%.2f".format(target.record.amount)) }
    // 回填原备注（2026-08-24 评审修复）：DAO 是 SET note = :note 全量覆盖，空初始会在
    // 编辑手动记录时把已有备注静默清空
    var noteText by remember { mutableStateOf(target.record.note ?: "") }
    val parsedAmount = amountText.toDoubleOrNull()
    val amountInvalid = amountText.isNotBlank() && parsedAmount == null

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                text = if (isManual) "编辑收入" else "修正金额",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "${target.stockName ?: "其他收入"} · ${target.record.date}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))

            // 原金额参考条：修正时对照推算值，编辑时对照当前值
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
                    text = if (isManual) "当前金额" else "推算金额",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = MoneyFormatter.withSymbol(target.record.amount),
                    style = MaterialTheme.typography.labelMedium.merge(tabularNumberStyle),
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(modifier = Modifier.height(12.dp))

            AppTextField(
                value = amountText,
                onValueChange = { amountText = it },
                label = { Text(if (isManual) "金额" else "实际到账金额") },
                prefix = { Text("¥") },
                singleLine = true,
                isError = amountInvalid,
                supportingText = if (amountInvalid) {
                    { Text("请输入有效金额") }
                } else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(12.dp))
            AppTextField(
                value = noteText,
                onValueChange = { noteText = it },
                label = { Text("备注 (可选)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(20.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AppTextButton(onClick = onDismiss, modifier = Modifier.weight(1f), text = "取消")
                AppButton(
                    onClick = { parsedAmount?.let { onConfirm(it, noteText.ifBlank { null }) } },
                    enabled = parsedAmount != null && parsedAmount > 0,
                    modifier = Modifier.weight(1f),
                    text = "确认",
                )
            }
        }
    }
}
