package com.stock.dividend.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stock.dividend.data.local.entity.STRATEGY_DIRECTION_BUY
import com.stock.dividend.data.local.entity.STRATEGY_DIRECTION_SELL
import com.stock.dividend.data.local.entity.STRATEGY_DIRECTION_WATCH
import com.stock.dividend.viewmodel.StrategyListItem
import com.stock.dividend.viewmodel.TradeStrategyListViewModel
import com.stock.dividend.ui.component.AppTextButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TradeStrategyListScreen(
    onBack: () -> Unit,
    onAddFromScreenshot: () -> Unit,
    viewModel: TradeStrategyListViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("策略库") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, null) }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddFromScreenshot) { Icon(Icons.Filled.Add, null) }
        }
    ) { padding ->
        if (state.items.isEmpty()) {
            // 不复用持仓页的 EmptyStateView（文案是「添加股票」，与策略库语义不符）；
            // 用贴合场景的本地空态，引导用右下 FAB「+」从截图添加。
            Box(
                Modifier.fillMaxSize().padding(padding).padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "暂无策略\n点右下 + 从截图分析添加",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(state.items, key = { it.id }) { item ->
                    StrategyCard(
                        item,
                        onArchive = viewModel::archive,
                        onDelete = viewModel::delete
                    )
                }
            }
        }
    }
}

@Composable
private fun StrategyCard(
    item: StrategyListItem,
    onArchive: (String) -> Unit,
    onDelete: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.targetText, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.fillMaxWidth(0.5f))
                AssistChip(onClick = {}, label = { Text(directionZh(item.direction)) })
            }
            Text(
                item.reasoning,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (expanded) 10 else 2
            )
            if (expanded && item.risks.isNotEmpty()) {
                Text("风险：", style = MaterialTheme.typography.labelMedium)
                item.risks.forEach {
                    Text("· $it", style = MaterialTheme.typography.bodySmall)
                }
            }
            Row {
                AppTextButton(
                    onClick = { expanded = !expanded },
                    text = if (expanded) "收起" else "展开",
                )
                Spacer(Modifier.fillMaxWidth(0.5f))
                AppTextButton(
                    onClick = { onArchive(item.id) },
                    text = "归档",
                )
                AppTextButton(
                    onClick = { onDelete(item.id) },
                    text = "删除",
                )
            }
        }
    }
}

private fun directionZh(d: String) = when (d) {
    STRATEGY_DIRECTION_BUY -> "买入"
    STRATEGY_DIRECTION_SELL -> "卖出"
    STRATEGY_DIRECTION_WATCH -> "观望"
    else -> "—"
}
