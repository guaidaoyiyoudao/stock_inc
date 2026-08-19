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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stock.dividend.ui.component.AppButton
import com.stock.dividend.ui.component.AppCard
import com.stock.dividend.ui.component.AppTextButton
import com.stock.dividend.ui.component.AppTextField
import com.stock.dividend.ui.component.AppCardDefaults
import com.stock.dividend.ui.component.CompactTopAppBar
import com.stock.dividend.ui.theme.LocalExtendedColors
import com.stock.dividend.ui.theme.tabularNumberStyle
import com.stock.dividend.viewmodel.TransactionHistoryItem
import com.stock.dividend.viewmodel.TransactionHistoryUiState
import com.stock.dividend.viewmodel.TransactionHistoryViewModel
import com.stock.dividend.data.repository.MoneyFormatter

/**
 * 全局交易流水页：跨股票、按日期倒序列出所有买卖记录，附股票名 + 累计买卖金额汇总。
 * 每条记录可点「编辑」按钮写复盘备注（交易笔记）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionHistoryScreen(
    onBack: () -> Unit,
    onImportFromScreenshot: () -> Unit = {},
    viewModel: TransactionHistoryViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            CompactTopAppBar(
                title = "交易流水",
                onBack = onBack,
                actions = {
                    IconButton(onClick = onImportFromScreenshot) {
                        Icon(
                            Icons.Default.PhotoCamera,
                            contentDescription = "从截图导入交易记录",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )
        }
    ) { padding ->
        if (state.items.isEmpty() && !state.isLoading) {
            EmptyTransactionHistory(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            )
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
            item { TransactionHistorySummary(state) }

            items(state.items, key = { it.transaction.id }) { item ->
                TransactionHistoryCard(
                    item = item,
                    onEditNote = { viewModel.showNoteDialog(item.transaction) }
                )
            }
        }
    }

    if (state.showNoteDialog && state.editingTransaction != null) {
        NoteEditDialog(
            input = state.noteInput,
            onInputChange = viewModel::onNoteChanged,
            onConfirm = viewModel::confirmNote,
            onDismiss = viewModel::dismissNoteDialog
        )
    }
}

/** 顶部汇总：累计买入 / 卖出金额（资金流向概览）。 */
@Composable
private fun TransactionHistorySummary(state: TransactionHistoryUiState) {
    val extendedColors = LocalExtendedColors.current
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                text = "资金流水",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "累计买入",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = MoneyFormatter.withSymbol(state.totalBuyAmount),
                        style = MaterialTheme.typography.titleMedium.merge(tabularNumberStyle),
                        fontWeight = FontWeight.Bold,
                        color = extendedColors.negative
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "累计卖出",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = MoneyFormatter.withSymbol(state.totalSellAmount),
                        style = MaterialTheme.typography.titleMedium.merge(tabularNumberStyle),
                        fontWeight = FontWeight.Bold,
                        color = extendedColors.positive
                    )
                }
            }
        }
    }
}

@Composable
private fun TransactionHistoryCard(
    item: TransactionHistoryItem,
    onEditNote: () -> Unit
) {
    val tx = item.transaction
    val isBuy = tx.type == "BUY"
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

            Spacer(modifier = Modifier.size(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.stockName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "${tx.date}  $typeLabel ${tx.shares}股" +
                        (if (tx.price > 0) "  @ ${MoneyFormatter.withSymbol(tx.price)}/股" else ""),
                    style = MaterialTheme.typography.bodySmall.merge(tabularNumberStyle),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                tx.note?.takeIf { it.isNotBlank() }?.let { note ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "📝 $note",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            IconButton(onClick = onEditNote) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = "编辑备注",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun NoteEditDialog(
    input: String,
    onInputChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑备注", fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                AppTextField(
                    value = input,
                    onValueChange = onInputChange,
                    label = { Text("复盘笔记（选填）") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 6,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "记录这笔交易的逻辑、情绪或教训，便于复盘。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        },
        confirmButton = {
            AppButton(onClick = onConfirm, text = "保存")
        },
        dismissButton = {
            AppTextButton(onClick = onDismiss, text = "取消")
        }
    )
}

@Composable
private fun EmptyTransactionHistory(modifier: Modifier = Modifier) {
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
            Icon(
                Icons.Default.ReceiptLong,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = "还没有交易记录",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "进入个股详情添加买入/卖出后，\n所有交易会汇总在这里，可写复盘备注。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}
