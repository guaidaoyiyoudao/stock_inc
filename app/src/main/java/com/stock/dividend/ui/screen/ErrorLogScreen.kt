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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stock.dividend.data.repository.ErrorLogRepository
import com.stock.dividend.ui.component.AppCard
import com.stock.dividend.ui.component.AppCardTone
import com.stock.dividend.ui.component.AppOutlinedButton
import com.stock.dividend.ui.component.AppTextButton
import com.stock.dividend.ui.component.EmptyStateView
import com.stock.dividend.viewmodel.ErrorLogItem
import com.stock.dividend.viewmodel.ErrorLogViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 日志时间格式化（本地时区，精确到秒；展示格式化允许）。 */
internal fun formatLogTimestamp(epochMillis: Long): String =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))

/**
 * 失败日志页（设置 → 数据 → 失败日志）。
 *
 * 收集 App 内关键的「静默失败」（数据获取失败等——网络异常时页面往往只是悄悄降级到
 * 空数据/缓存，用户无从得知），在此集中可见、可清理：
 * - 每条含 时间 / 分类 / 来源模块 / 失败摘要，点击可展开异常详情（等宽堆栈）；
 * - 自动去重防刷屏（同来源同摘要 1 分钟内只记一条）、最多保留最近 [ErrorLogRepository.MAX_LOGS] 条；
 * - 「清理全部日志」一键清空（确认弹窗防误触）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ErrorLogScreen(
    onBack: () -> Unit,
    viewModel: ErrorLogViewModel = hiltViewModel(),
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
                title = { Text("失败日志") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        when {
            state.isLoading -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("加载中…", style = MaterialTheme.typography.bodyMedium)
            }

            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { IntroCard(count = state.logs.size) }
                item {
                    AppOutlinedButton(
                        onClick = viewModel::onClearClicked,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.isClearing && state.logs.isNotEmpty(),
                        text = if (state.isClearing) "清理中…" else "清理全部日志",
                    )
                }
                item { Spacer(modifier = Modifier.height(4.dp)) }
                if (state.logs.isEmpty()) {
                    item {
                        EmptyStateView(
                            modifier = Modifier.fillParentMaxWidth()
                        )
                    }
                } else {
                    items(state.logs, key = { it.id }) { log ->
                        ErrorLogRow(
                            log = log,
                            expanded = state.expandedLogId == log.id,
                            onClick = { viewModel.toggleExpanded(log.id) },
                        )
                    }
                }
            }
        }
    }

    if (state.confirmingClear) {
        ConfirmClearDialog(
            onConfirm = viewModel::confirmClear,
            onDismiss = viewModel::dismissConfirm,
        )
    }
}

/** 收集范围与保留策略说明 + 当前条数。 */
@Composable
private fun IntroCard(count: Int) {
    AppCard(tone = AppCardTone.Summary) {
        Text(
            text = "日志说明",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "自动记录 App 内关键的静默失败（行情/分红/K线/财务等数据获取失败时，" +
                "页面通常悄悄降级为空数据或缓存，不易察觉）。同来源同摘要 1 分钟内只记一条，" +
                "最多保留最近 ${ErrorLogRepository.MAX_LOGS} 条。",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "当前 $count 条记录",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** 单条日志：时间 + 分类/来源 + 摘要；有详情时点击展开异常堆栈（等宽小字）。 */
@Composable
private fun ErrorLogRow(
    log: ErrorLogItem,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    // 有详情才可点击展开；无详情为静态卡片（AppCard 的 onClick=null 走静态分支）
    AppCard(onClick = if (log.detail != null) onClick else null) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = log.source,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = log.categoryLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Text(
                text = log.message,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = formatLogTimestamp(log.timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (log.detail != null) {
                Text(
                    text = if (expanded) "收起详情 ▲" else "查看详情 ▼",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (expanded) {
                    Text(
                        text = log.detail,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            lineHeight = MaterialTheme.typography.bodySmall.lineHeight * 1.2f,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** 清理确认弹窗。 */
@Composable
private fun ConfirmClearDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("清理全部失败日志") },
        text = {
            Text("将删除全部 ${ErrorLogRepository.MAX_LOGS} 条上限内的失败日志记录，" +
                "不影响任何业务数据（自选股/持仓/缓存均保留）。确定清理吗？")
        },
        confirmButton = {
            AppTextButton(onClick = onConfirm, text = "清理")
        },
        dismissButton = {
            AppTextButton(onClick = onDismiss, text = "取消")
        }
    )
}
