package com.stock.dividend.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stock.dividend.data.repository.CacheKind
import com.stock.dividend.ui.component.AppCard
import com.stock.dividend.ui.component.AppCardTone
import com.stock.dividend.ui.component.AppOutlinedButton
import com.stock.dividend.ui.component.AppTextButton
import com.stock.dividend.ui.theme.LocalExtendedColors
import com.stock.dividend.viewmodel.CacheEntry
import com.stock.dividend.viewmodel.CacheManagementViewModel
import java.util.Locale

/** 缓存条目数千分位格式化（Locale.US 稳定输出，展示格式化允许）。 */
internal fun formatEntryCount(count: Long): String = String.format(Locale.US, "%,d", count)

/**
 * 缓存管理页（设置 → 数据 → 缓存管理）。
 *
 * 两件事：
 * 1. **可见**：各持久缓存（价格/搜索/K线/财报/分红/AI 解读）条目数 + 「永久缓存/短期缓存」标记——
 *    历史不可变数据（已收盘 K 线、已披露财报期次、已实施分红）本地永久保留的策略在此明示给用户；
 * 2. **可清**：单类清理 + 一键全部清理（确认弹窗防误触），清理联动内存会话缓存（VM 编排）。
 *
 * 只清可再生缓存，自选股/持仓/交易记录等用户数据不在此列。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CacheManagementScreen(
    onBack: () -> Unit,
    viewModel: CacheManagementViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("缓存管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            PolicyCard(totalEntries = state.entries.sumOf { it.entries })

            state.entries.forEach { entry ->
                CacheEntryRow(
                    entry = entry,
                    enabled = !state.isClearing,
                    onClear = { viewModel.onClearClicked(entry.kind) },
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            AppOutlinedButton(
                onClick = viewModel::onClearAllClicked,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isClearing && state.entries.any { it.entries > 0 },
                text = if (state.isClearing) "清理中…" else "清理全部缓存",
            )
        }
    }

    state.confirming?.let { kind ->
        ConfirmClearDialog(
            kind = kind,
            onConfirm = viewModel::confirmClear,
            onDismiss = viewModel::dismissConfirm,
        )
    }
    if (state.confirmingAll) {
        ConfirmClearAllDialog(
            onConfirm = viewModel::confirmClearAll,
            onDismiss = viewModel::dismissConfirm,
        )
    }
}

/** 永久缓存策略说明 + 合计条目数。 */
@Composable
private fun PolicyCard(totalEntries: Long) {
    AppCard(tone = AppCardTone.Summary) {
        Text(
            text = "缓存说明",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "标记「永久缓存」的是历史不可变数据（已收盘 K 线、已披露财报与分红）：" +
                "本地永续保留、断网可用，只增量追加、不因过期删除；" +
                "其余为实时/派生缓存，联网时自动重建。清理不影响自选股、持仓与交易记录。",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "共 ${formatEntryCount(totalEntries)} 条缓存记录",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** 单类缓存行：名称 + 永久/短期标记 + 说明 + 条目数 + 清理按钮。 */
@Composable
private fun CacheEntryRow(
    entry: CacheEntry,
    enabled: Boolean,
    onClear: () -> Unit,
) {
    val extended = LocalExtendedColors.current
    AppCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = entry.kind.label,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = if (entry.permanent) "永久缓存" else "短期缓存",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (entry.permanent) extended.positive
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = entry.kind.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "${formatEntryCount(entry.entries)} 条",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            AppTextButton(
                onClick = onClear,
                enabled = enabled && entry.entries > 0,
                text = "清理",
            )
        }
    }
}

/** 单类清理确认弹窗。 */
@Composable
private fun ConfirmClearDialog(
    kind: CacheKind,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("清理「${kind.label}」") },
        text = {
            Text("${kind.description}。确定要清理吗？")
        },
        confirmButton = {
            AppTextButton(onClick = onConfirm, text = "清理")
        },
        dismissButton = {
            AppTextButton(onClick = onDismiss, text = "取消")
        }
    )
}

/** 全部清理确认弹窗（含永久缓存的历史数据，措辞更重）。 */
@Composable
private fun ConfirmClearAllDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("清理全部缓存") },
        text = {
            Text("将删除以上全部缓存，包括永久缓存的历史数据（K 线/财报/分红）。" +
                "自选股、持仓与交易记录不受影响；历史数据会在联网使用时按需重新下载。")
        },
        confirmButton = {
            AppTextButton(onClick = onConfirm, text = "全部清理")
        },
        dismissButton = {
            AppTextButton(onClick = onDismiss, text = "取消")
        }
    )
}
