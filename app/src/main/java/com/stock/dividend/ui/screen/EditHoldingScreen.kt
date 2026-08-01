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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        AppButton(
                            onClick = { viewModel.showAddBuyDialog() },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("添加买入")
                        }
                        AppOutlinedButton(
                            onClick = { viewModel.showAddSellDialog() },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Default.Remove, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("添加卖出")
                        }
                    }
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

    if (uiState.showAddBuyDialog || uiState.showAddSellDialog) {
        val isBuy = uiState.showAddBuyDialog
        AddTransactionDialog(
            isBuy = isBuy,
            sharesInput = uiState.addSharesInput,
            priceInput = uiState.addPriceInput,
            dateInput = uiState.addDateInput,
            error = uiState.addInputError,
            onSharesChanged = viewModel::onAddSharesChanged,
            onPriceChanged = viewModel::onAddPriceChanged,
            onDateChanged = viewModel::onAddDateChanged,
            onConfirm = { viewModel.confirmAddTransaction(isBuy) },
            onDismiss = { viewModel.dismissDialog() }
        )
    }

    if (uiState.showEditTransactionDialog) {
        val transaction = uiState.editingTransaction
        if (transaction != null) {
            val isBuy = transaction.type == "BUY"
            AddTransactionDialog(
                title = if (isBuy) "编辑买入" else "编辑卖出",
                isBuy = isBuy,
                sharesInput = uiState.editSharesInput,
                priceInput = uiState.editPriceInput,
                dateInput = uiState.editDateInput,
                error = uiState.editInputError,
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

@Composable
private fun AddTransactionDialog(
    title: String? = null,
    isBuy: Boolean,
    sharesInput: String,
    priceInput: String,
    dateInput: String,
    error: String?,
    onSharesChanged: (String) -> Unit,
    onPriceChanged: (String) -> Unit,
    onDateChanged: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val dialogTitle = title ?: if (isBuy) "添加买入" else "添加卖出"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = dialogTitle,
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column {
                AppTextField(
                    value = sharesInput,
                    onValueChange = onSharesChanged,
                    label = { Text("股数") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )

                Spacer(modifier = Modifier.height(12.dp))

                AppTextField(
                    value = priceInput,
                    onValueChange = onPriceChanged,
                    label = { Text(if (isBuy) "买入价格（元/股）" else "卖出价格（元/股，选填）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )

                Spacer(modifier = Modifier.height(12.dp))

                AppTextField(
                    value = dateInput,
                    onValueChange = onDateChanged,
                    label = { Text("日期（YYYY-MM-DD）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                )

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
                text = "确认",
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
