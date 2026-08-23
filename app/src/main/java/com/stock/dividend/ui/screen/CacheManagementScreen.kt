package com.stock.dividend.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CandlestickChart
import androidx.compose.material.icons.outlined.Paid
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stock.dividend.data.repository.CacheKind
import com.stock.dividend.ui.component.AppCard
import com.stock.dividend.ui.component.AppOutlinedButton
import com.stock.dividend.ui.component.AppTextButton
import com.stock.dividend.ui.theme.LocalExtendedColors
import com.stock.dividend.ui.theme.tabularNumberStyle
import com.stock.dividend.viewmodel.CacheEntry
import com.stock.dividend.viewmodel.CacheManagementViewModel
import java.util.Locale

/** 缓存条目数千分位格式化（Locale.US 稳定输出，展示格式化允许）。 */
internal fun formatEntryCount(count: Long): String = String.format(Locale.US, "%,d", count)

/** 用量条分段：缓存种类 + 条目数 + 占总量比例（0..1，全部分段合计 = 1）。 */
internal data class CacheSegment(
    val kind: CacheKind,
    val entries: Long,
    val fraction: Double,
)

/**
 * 条目统计 → 用量条分段（纯函数，配单测）：
 * 按 [CacheKind] 声明序保留条目数 > 0 的段，fraction = 条目数 / 总条目数；
 * 总量为 0 返回空列表（调用方渲染空态条）。
 */
internal fun cacheSegmentFractions(entries: List<CacheEntry>): List<CacheSegment> {
    val total = entries.sumOf { it.entries }
    if (total <= 0L) return emptyList()
    return entries.asSequence()
        .filter { it.entries > 0L }
        .map { CacheSegment(kind = it.kind, entries = it.entries, fraction = it.entries.toDouble() / total) }
        .toList()
}

/**
 * 缓存管理页（设置 → 数据 → 缓存管理），布局仿系统「存储空间」页三段式：
 * 1. **用量总览**：总量大字 + 按类着色的分段占比条 + 永久/短期分组小计 + 缓存策略说明；
 * 2. **分类明细**：单卡多行（彩色圆形图标 + 名称 + 永久/短期徽标 + 条目数 + 清理），
 *    各类说明移至确认弹窗展示，行内保持系统设置列表般的紧凑；
 * 3. **一键清理**：清理全部缓存（确认弹窗防误触），清理联动内存会话缓存（VM 编排）。
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
        if (state.isLoading && state.entries.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("加载中…", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                UsageSummaryCard(entries = state.entries)

                CategoryListCard(
                    entries = state.entries,
                    enabled = !state.isClearing,
                    onClear = viewModel::onClearClicked,
                )

                Spacer(modifier = Modifier.height(8.dp))
                AppOutlinedButton(
                    onClick = viewModel::onClearAllClicked,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.isClearing && state.entries.any { it.entries > 0 },
                    text = if (state.isClearing) "清理中…" else "清理全部缓存",
                )
            }
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

/** 顶部用量总览（系统存储页同款）：总量大字 + 分段占比条 + 永久/短期分组小计 + 策略说明。 */
@Composable
private fun UsageSummaryCard(entries: List<CacheEntry>) {
    val segments = remember(entries) { cacheSegmentFractions(entries) }
    val total = entries.sumOf { it.entries }
    val permanentTotal = entries.filter { it.permanent }.sumOf { it.entries }

    AppCard(elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "缓存占用",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "${formatEntryCount(total)} 条",
                    style = MaterialTheme.typography.headlineMedium.merge(tabularNumberStyle),
                    fontWeight = FontWeight.SemiBold,
                )
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    GroupSubtotal(label = "永久缓存", count = permanentTotal)
                    GroupSubtotal(label = "短期缓存", count = total - permanentTotal)
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            CacheSegmentBar(segments = segments)
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "「永久缓存」为历史不可变数据（已收盘 K 线、已披露财报与分红），本地永续保留、断网可用，" +
                    "只增量追加、不因过期删除；「短期缓存」联网时自动重建。清理不影响自选股、持仓与交易记录。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 分组小计行（右侧小字，如「永久缓存 12,345 条」）。 */
@Composable
private fun GroupSubtotal(label: String, count: Long) {
    Text(
        text = "$label ${formatEntryCount(count)} 条",
        style = MaterialTheme.typography.labelMedium.merge(tabularNumberStyle),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * 分段占比条：每类缓存一段、宽度按条目数占比着色；空数据渲染灰底空态条
 * （总量为 0 时系统存储页同款「无数据」语义）。
 */
@Composable
private fun CacheSegmentBar(segments: List<CacheSegment>) {
    if (segments.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(12.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        return
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(12.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        segments.forEach { segment ->
            Box(
                modifier = Modifier
                    .weight(segment.fraction.toFloat(), fill = true)
                    .fillMaxHeight()
                    .background(cacheKindColor(segment.kind)),
            )
        }
    }
}

/** 分类明细（系统存储页分类列表同款）：单卡多行 + 缩进分隔线。 */
@Composable
private fun CategoryListCard(
    entries: List<CacheEntry>,
    enabled: Boolean,
    onClear: (CacheKind) -> Unit,
) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        entries.forEachIndexed { index, entry ->
            if (index > 0) {
                HorizontalDivider(
                    modifier = Modifier.padding(start = 64.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
            CacheEntryRow(
                entry = entry,
                enabled = enabled,
                onClear = { onClear(entry.kind) },
            )
        }
    }
}

/**
 * 单类缓存行：彩色圆形图标 + 名称 + 永久/短期徽标 + 条目数 + 清理按钮。
 * 保持系统设置列表般的紧凑（说明文案移至清理确认弹窗）。
 */
@Composable
private fun CacheEntryRow(
    entry: CacheEntry,
    enabled: Boolean,
    onClear: () -> Unit,
) {
    val kindColor = cacheKindColor(entry.kind)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(kindColor.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = cacheKindIcon(entry.kind),
                contentDescription = null,
                tint = kindColor,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = entry.kind.label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            CacheGroupBadge(permanent = entry.permanent)
        }
        Text(
            text = "${formatEntryCount(entry.entries)} 条",
            style = MaterialTheme.typography.titleMedium.merge(tabularNumberStyle),
            color = if (entry.entries > 0L) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AppTextButton(
            onClick = onClear,
            enabled = enabled && entry.entries > 0,
            text = "清理",
        )
    }
}

/** 永久/短期缓存徽标（小圆角 pill；永久沿用财务正色，短期中性灰）。 */
@Composable
private fun CacheGroupBadge(permanent: Boolean) {
    val color = if (permanent) LocalExtendedColors.current.positive
    else MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        text = if (permanent) "永久" else "短期",
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier
            .clip(MaterialTheme.shapes.extraSmall)
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** 分类图标：七类缓存各配一个 Material 图标（系统存储分类列表同款视觉锚点）。 */
private fun cacheKindIcon(kind: CacheKind): ImageVector = when (kind) {
    CacheKind.PRICE -> Icons.AutoMirrored.Outlined.ShowChart
    CacheKind.SEARCH -> Icons.Outlined.Search
    CacheKind.KLINE -> Icons.Outlined.CandlestickChart
    CacheKind.FUNDAMENTALS -> Icons.Outlined.QueryStats
    CacheKind.STATEMENTS -> Icons.AutoMirrored.Outlined.ReceiptLong
    CacheKind.DIVIDENDS -> Icons.Outlined.Paid
    CacheKind.LLM_ANALYSIS -> Icons.Outlined.AutoAwesome
    CacheKind.FUYAO -> Icons.Filled.CloudSync
}

/**
 * 分类颜色（仅作分类可视化用，无财务涨跌语义）：取主题色角色，自动跟随深浅色。
 */
@Composable
private fun cacheKindColor(kind: CacheKind): Color {
    val scheme = MaterialTheme.colorScheme
    val extended = LocalExtendedColors.current
    return when (kind) {
        CacheKind.PRICE -> scheme.primary
        CacheKind.SEARCH -> scheme.secondary
        CacheKind.KLINE -> scheme.tertiary
        CacheKind.FUNDAMENTALS -> extended.positive
        CacheKind.STATEMENTS -> extended.negative
        CacheKind.DIVIDENDS -> scheme.error
        CacheKind.LLM_ANALYSIS -> scheme.outline
        CacheKind.FUYAO -> scheme.primaryContainer
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
