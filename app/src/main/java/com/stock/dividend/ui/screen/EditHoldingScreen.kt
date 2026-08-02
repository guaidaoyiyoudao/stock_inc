package com.stock.dividend.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stock.dividend.data.local.entity.TransactionEntity
import com.stock.dividend.data.repository.MoneyFormatter
import com.stock.dividend.ui.component.AppCard
import com.stock.dividend.ui.component.CompactTopAppBar
import com.stock.dividend.ui.theme.LocalExtendedColors
import com.stock.dividend.ui.theme.tabularNumberStyle
import com.stock.dividend.viewmodel.EditHoldingViewModel
import com.stock.dividend.ui.component.AppTextButton
import com.stock.dividend.ui.component.AppButton
import com.stock.dividend.ui.component.AppOutlinedButton
import com.stock.dividend.ui.component.AppTextField

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditHoldingScreen(
    onBack: () -> Unit,
    viewModel: EditHoldingViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
        topBar = {
            CompactTopAppBar(
                title = "编辑持仓",
                onBack = onBack,
                actions = {
                    AppTextButton(
                        onClick = {
                            viewModel.saveHolding()
                            onBack()
                        },
                        text = "保存",
                    )
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val stockName = uiState.stockName
            if (stockName != null) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        ),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(18.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(MaterialTheme.shapes.medium)
                                    .background(MaterialTheme.colorScheme.primary),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = stockName.take(1),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Column {
                                Text(
                                    text = stockName,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                                Text(
                                    text = uiState.stockCode.replace("sh.", "SH ").replace("sz.", "SZ "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.6f)
                                )
                            }
                        }
                    }
                }

                item {
                    AppCard(
                        modifier = Modifier.fillMaxWidth(),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    ) {
                        Column(modifier = Modifier.padding(18.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text(
                                        text = "当前持仓",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "${uiState.totalShares} 股",
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        text = "平均成本",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = MoneyFormatter.withSymbol(uiState.avgCostPerShare),
                                        style = MaterialTheme.typography.titleLarge.merge(tabularNumberStyle),
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }
                }

                item {
                    AppButton(
                        onClick = { viewModel.showTransactionSheet(isBuy = uiState.totalShares <= 0) },
                        modifier = Modifier.fillMaxWidth(),
                        leadingIcon = Icons.Default.Add,
                        text = "添加交易",
                    )
                }
            }

            if (uiState.transactions.isNotEmpty()) {
                item {
                    Text(
                        text = "交易记录",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                items(uiState.transactions.sortedByDescending { it.date }, key = { it.id }) { transaction ->
                    TransactionCard(
                        transaction = transaction,
                        onEdit = { viewModel.showEditTransactionDialog(transaction) },
                        onDelete = { viewModel.deleteTransaction(transaction) }
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "股息率档位",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "计算平均股息时使用的历史年数",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )

                Spacer(modifier = Modifier.height(14.dp))

                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val periods = listOf("1" to "1年", "3" to "3年", "5" to "5年")
                    periods.forEachIndexed { index, (value, label) ->
                        SegmentedButton(
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = periods.size),
                            onClick = { viewModel.onYieldPeriodChanged(value) },
                            selected = uiState.yieldPeriod == value
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (uiState.yieldPeriod == value) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            }

            item {
                StockTagsCard(
                    tags = uiState.tags,
                    onAddClick = { viewModel.showAddTagDialog() },
                    onRemoveClick = { tag -> viewModel.removeTag(tag) }
                )
            }
        }
    }

    if (uiState.showTransactionSheet) {
        TransactionSheet(
            isBuy = uiState.isBuyInput,
            isEditMode = false,
            sharesInput = uiState.addSharesInput,
            priceInput = uiState.addPriceInput,
            dateInput = uiState.addDateInput,
            sharesError = uiState.addSharesError,
            priceError = uiState.addPriceError,
            onTypeChanged = viewModel::onTransactionTypeChanged,
            onSharesChanged = viewModel::onAddSharesChanged,
            onPriceChanged = viewModel::onAddPriceChanged,
            onDateChanged = viewModel::onAddDateChanged,
            onConfirm = { viewModel.confirmAddTransaction() },
            onDismiss = { viewModel.dismissDialog() }
        )
    }

    if (uiState.showEditTransactionSheet) {
        val transaction = uiState.editingTransaction
        if (transaction != null) {
            val isBuy = transaction.type == "BUY"
            TransactionSheet(
                isBuy = isBuy,
                isEditMode = true,
                sharesInput = uiState.editSharesInput,
                priceInput = uiState.editPriceInput,
                dateInput = uiState.editDateInput,
                sharesError = uiState.editSharesError,
                priceError = uiState.editPriceError,
                onTypeChanged = { },
                onSharesChanged = viewModel::onEditSharesChanged,
                onPriceChanged = viewModel::onEditPriceChanged,
                onDateChanged = viewModel::onEditDateChanged,
                onConfirm = { viewModel.confirmEditTransaction() },
                onDismiss = { viewModel.dismissDialog() }
            )
        }
    }

    if (uiState.showAddTagDialog) {
        AddTagDialog(
            input = uiState.addTagInput,
            error = uiState.addTagError,
            suggestions = uiState.allTags.filter { it !in uiState.tags },
            onInputChange = viewModel::onAddTagInputChanged,
            onConfirm = { viewModel.confirmAddTag() },
            onSuggestionClick = { tag ->
                viewModel.onAddTagInputChanged(tag)
                viewModel.confirmAddTag()
            },
            onDismiss = { viewModel.dismissAddTagDialog() }
        )
    }
}

@Composable
private fun TransactionCard(
    transaction: TransactionEntity,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val isBuy = transaction.type == "BUY"
    val typeLabel = if (isBuy) "买入" else "卖出"
    val typeColor = if (isBuy) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error

    AppCard(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(typeColor.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = typeLabel.take(1),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = typeColor
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${transaction.date}  $typeLabel ${transaction.shares}股",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (transaction.price > 0) {
                    Text(
                        text = "@ ${MoneyFormatter.withSymbol(transaction.price)}/股",
                        style = MaterialTheme.typography.bodySmall.merge(tabularNumberStyle),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            }

            IconButton(onClick = onEdit) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = "编辑交易",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }

            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "删除交易",
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/**
 * 交易录入/编辑表单。新增模式顶部可切换买/卖方向；编辑模式方向锁定。
 * 字段级实时校验 + 合计金额预览 + 日历选日期。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TransactionSheet(
    isBuy: Boolean,
    isEditMode: Boolean,
    sharesInput: String,
    priceInput: String,
    dateInput: String,
    sharesError: String?,
    priceError: String?,
    onTypeChanged: (Boolean) -> Unit,
    onSharesChanged: (String) -> Unit,
    onPriceChanged: (String) -> Unit,
    onDateChanged: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    var showDatePicker by remember { mutableStateOf(false) }
    val typeColor = if (isBuy) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    val extendedColors = LocalExtendedColors.current

    // 实时计算合计金额（股数与价格都有效时才显示）
    val sharesValue = sharesInput.toIntOrNull()
    val priceValue = priceInput.toDoubleOrNull()
    val totalAmount = if (sharesValue != null && sharesValue > 0 && priceValue != null && priceValue > 0) {
        sharesValue.toDouble() * priceValue
    } else null

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp)
        ) {
            // 标题 / 方向切换
            if (isEditMode) {
                Text(
                    text = if (isBuy) "编辑买入" else "编辑卖出",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            } else {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        onClick = { onTypeChanged(true) },
                        selected = isBuy,
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            activeContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    ) { Text("买入", fontWeight = if (isBuy) FontWeight.Bold else FontWeight.Normal) }
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        onClick = { onTypeChanged(false) },
                        selected = !isBuy,
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = MaterialTheme.colorScheme.errorContainer,
                            activeContentColor = MaterialTheme.colorScheme.onErrorContainer
                        )
                    ) { Text("卖出", fontWeight = if (!isBuy) FontWeight.Bold else FontWeight.Normal) }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 股数
            AppTextField(
                value = sharesInput,
                onValueChange = onSharesChanged,
                label = { Text("股数") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = sharesError != null,
                supportingText = sharesError?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 价格
            AppTextField(
                value = priceInput,
                onValueChange = onPriceChanged,
                label = {
                    Text(if (isBuy) "买入价格" else "卖出价格（选填）")
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = isBuy && priceError != null,
                supportingText = if (isBuy) priceError?.let { { Text(it) } } else null,
                suffix = { Text("元/股") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 日期（只读，点击弹日历）
            DateField(
                value = dateInput,
                errorText = if (dateInput.isBlank()) "请选择日期" else null,
                onClick = { showDatePicker = true }
            )

            // 合计金额预览
            if (totalAmount != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "合计",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = MoneyFormatter.withSymbol(totalAmount),
                        style = MaterialTheme.typography.titleMedium.merge(tabularNumberStyle),
                        fontWeight = FontWeight.Bold,
                        color = if (isBuy) extendedColors.positive else extendedColors.negative
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 底部按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                AppTextButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    text = "取消",
                )
                AppButton(
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f),
                    text = if (isEditMode) "确认修改" else if (isBuy) "确认买入" else "确认卖出",
                    containerColor = typeColor,
                )
            }
        }
    }

    // 日历选择对话框（复用 AddStockScreen 范式）
    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = try {
                java.time.LocalDate.parse(dateInput)
                    .atStartOfDay(java.time.ZoneId.systemDefault())
                    .toInstant().toEpochMilli()
            } catch (_: Exception) { null }
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                AppTextButton(
                    onClick = {
                        val selectedDate = datePickerState.selectedDateMillis?.let { millis ->
                            java.time.Instant.ofEpochMilli(millis)
                                .atZone(java.time.ZoneId.systemDefault())
                                .toLocalDate()
                                .toString()
                        }
                        if (selectedDate != null) {
                            onDateChanged(selectedDate)
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

/** 只读日期输入框 + 日历图标，点击触发外部 DatePickerDialog。 */
@Composable
private fun DateField(
    value: String,
    errorText: String?,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        AppTextField(
            value = value,
            onValueChange = {},
            modifier = Modifier.fillMaxWidth(),
            readOnly = true,
            enabled = false,
            isError = errorText != null,
            supportingText = errorText?.let { { Text(it) } },
            label = { Text("日期") },
            leadingIcon = {
                Icon(Icons.Default.DateRange, contentDescription = null)
            },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                disabledTextColor = MaterialTheme.colorScheme.onSurface,
                disabledBorderColor = if (errorText != null) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.outlineVariant,
                disabledLeadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                disabledSupportingTextColor = MaterialTheme.colorScheme.error
            )
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StockTagsCard(
    tags: List<String>,
    onAddClick: () -> Unit,
    onRemoveClick: (String) -> Unit
) {
    AppCard(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                text = "标签",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "用于在持仓页按标签筛选",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
            Spacer(modifier = Modifier.height(12.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                tags.forEach { tag ->
                    InputChip(
                        selected = false,
                        onClick = {},
                        label = { Text(tag) },
                        trailingIcon = {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "移除标签 $tag",
                                modifier = Modifier
                                    .size(16.dp)
                                    .clickable { onRemoveClick(tag) }
                            )
                        }
                    )
                }
                AssistChip(
                    onClick = onAddClick,
                    label = { Text("+ 添加标签") },
                    leadingIcon = {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddTagDialog(
    input: String,
    error: String?,
    suggestions: List<String>,
    onInputChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onSuggestionClick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加标签", fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                AppTextField(
                    value = input,
                    onValueChange = onInputChange,
                    label = { Text("标签名（最长 20 字）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                if (suggestions.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "已有标签",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        suggestions.take(20).forEach { s ->
                            AssistChip(
                                onClick = { onSuggestionClick(s) },
                                label = { Text(s) }
                            )
                        }
                    }
                }
                if (error != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            AppButton(
                onClick = onConfirm,
                text = "添加",
            )
        },
        dismissButton = {
            AppTextButton(
                onClick = onDismiss,
                text = "取消",
            )
        }
    )
}
