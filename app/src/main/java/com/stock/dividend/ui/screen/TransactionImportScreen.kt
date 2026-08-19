package com.stock.dividend.ui.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stock.dividend.ui.component.AppButton
import com.stock.dividend.ui.component.AppCard
import com.stock.dividend.ui.component.AppCardDefaults
import com.stock.dividend.ui.component.AppOutlinedButton
import com.stock.dividend.ui.component.AppTextButton
import com.stock.dividend.ui.component.AppTextField
import com.stock.dividend.ui.component.CompactTopAppBar
import com.stock.dividend.viewmodel.TransactionImportPhase
import com.stock.dividend.viewmodel.TransactionImportViewModel
import com.stock.dividend.viewmodel.TransactionReviewRow

/**
 * 交易记录截图导入页：同花顺「历史成交」截图 → AI 视觉解析（GLM-4.6V-Flash）
 * → 行级核对（方向/股数/成交价/日期）→ 批量写入交易流水（自动重算持仓成本）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionImportScreen(
    onBack: () -> Unit,
    viewModel: TransactionImportViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val pickMedia = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let { viewModel.onImagePicked(it) } }

    fun launchPicker() {
        pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    Scaffold(
        topBar = {
            CompactTopAppBar(
                title = "截图导入交易记录",
                onBack = onBack,
                actions = {
                    if (uiState.phase == TransactionImportPhase.Review && uiState.rows.isNotEmpty()) {
                        AppTextButton(onClick = { viewModel.confirmImport() }, text = "全部导入")
                    }
                }
            )
        }
    ) { padding ->
        when (uiState.phase) {
            TransactionImportPhase.Done -> DoneContent(
                summary = uiState.importSummary ?: "导入完成",
                onBackToList = onBack,
                onImportAnother = { viewModel.resetToIdle() },
                modifier = Modifier.fillMaxSize().padding(padding)
            )

            TransactionImportPhase.Error -> ErrorContent(
                message = uiState.errorMessage ?: "发生未知错误",
                onRetry = ::launchPicker,
                onBack = onBack,
                modifier = Modifier.fillMaxSize().padding(padding)
            )

            TransactionImportPhase.LoadingImage, TransactionImportPhase.Analyzing, TransactionImportPhase.Importing ->
                LoadingContent(
                    message = when (uiState.phase) {
                        TransactionImportPhase.LoadingImage -> "正在读取图片…"
                        TransactionImportPhase.Importing -> "正在导入交易…"
                        else -> "AI 视觉识别中…"
                    },
                    retryStatus = uiState.visionRetryStatus,
                    modifier = Modifier.fillMaxSize().padding(padding)
                )

            TransactionImportPhase.Review -> ReviewContent(
                state = uiState,
                onPickAnother = ::launchPicker,
                onRowCodeOrNameChanged = viewModel::onRowCodeOrNameChanged,
                onRowTypeChanged = viewModel::onRowTypeChanged,
                onRowSharesChanged = viewModel::onRowSharesChanged,
                onRowPriceChanged = viewModel::onRowPriceChanged,
                onRowDateChanged = viewModel::onRowDateChanged,
                onRemoveRow = viewModel::removeRow,
                onAddRow = viewModel::addEmptyRow,
                onConfirm = viewModel::confirmImport,
                modifier = Modifier.fillMaxSize().padding(padding)
            )

            TransactionImportPhase.Idle -> IdleContent(
                visionConfigured = uiState.visionConfigured,
                onPick = ::launchPicker,
                modifier = Modifier.fillMaxSize().padding(padding)
            )
        }
    }
}

@Composable
private fun IdleContent(
    visionConfigured: Boolean,
    onPick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp)
        ) {
            Text(
                text = "从同花顺截图导入交易记录",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "在 同花顺 → 交易 → 历史成交（成交记录）页截图，AI 自动识别买卖方向、数量、成交价与日期，核对后批量导入交易流水，持仓成本自动重算。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(12.dp))
            if (!visionConfigured) {
                AppCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "尚未配置视觉模型：请先到「设置 → AI 与策略」填写视觉模型 API Key（GLM-4.6V-Flash 免费）。未配置也可手动添加行录入。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
            Text(
                text = "图片将压缩后上传至智谱 BigModel（GLM-4.6V-Flash）识别，识别失败自动重试；手续费不计入，仅按成交价×数量记账。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(24.dp))
            AppButton(
                onClick = onPick,
                text = "选择交易记录截图",
            )
        }
    }
}

@Composable
private fun LoadingContent(message: String, retryStatus: String? = null, modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(16.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium)
            if (retryStatus != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = retryStatus,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ErrorContent(
    message: String,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp)
        ) {
            Text(
                text = "识别失败",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row {
                AppTextButton(onClick = onBack, text = "返回")
                Spacer(modifier = Modifier.size(8.dp))
                AppButton(onClick = onRetry, text = "重新选图")
            }
        }
    }
}

@Composable
private fun DoneContent(
    summary: String,
    onBackToList: () -> Unit,
    onImportAnother: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp)
        ) {
            Text(
                text = "导入完成",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(summary, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            Spacer(modifier = Modifier.height(24.dp))
            AppButton(onClick = onBackToList, text = "返回交易流水")
            Spacer(modifier = Modifier.size(8.dp))
            AppTextButton(onClick = onImportAnother, text = "再导入一张")
        }
    }
}

@Composable
private fun ReviewContent(
    state: com.stock.dividend.viewmodel.TransactionImportUiState,
    onPickAnother: () -> Unit,
    onRowCodeOrNameChanged: (Long, String) -> Unit,
    onRowTypeChanged: (Long, String) -> Unit,
    onRowSharesChanged: (Long, String) -> Unit,
    onRowPriceChanged: (Long, String) -> Unit,
    onRowDateChanged: (Long, String) -> Unit,
    onRemoveRow: (Long) -> Unit,
    onAddRow: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(
            start = AppCardDefaults.PageHorizontalPadding,
            end = AppCardDefaults.PageHorizontalPadding,
            top = 12.dp,
            bottom = AppCardDefaults.BottomNavigationPadding
        ),
        verticalArrangement = Arrangement.spacedBy(AppCardDefaults.SectionSpacing)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "识别到 ${state.rows.size} 笔交易，请核对后再导入",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                AppTextButton(onClick = onPickAnother, text = "换一张图")
            }
        }
        items(items = state.rows, key = { it.id }) { row ->
            TransactionReviewRowCard(
                row = row,
                onCodeOrNameChanged = { onRowCodeOrNameChanged(row.id, it) },
                onTypeChanged = { onRowTypeChanged(row.id, it) },
                onSharesChanged = { onRowSharesChanged(row.id, it) },
                onPriceChanged = { onRowPriceChanged(row.id, it) },
                onDateChanged = { onRowDateChanged(row.id, it) },
                onRemove = { onRemoveRow(row.id) }
            )
        }
        item {
            AppOutlinedButton(
                onClick = onAddRow,
                modifier = Modifier.fillMaxWidth(),
                text = "手动添加一行",
                leadingIcon = Icons.Default.Add,
            )
        }
        item {
            Text(
                text = "说明：同股同日同方向同价同股数的记录会自动跳过（防重复导入）；导入后按摊薄口径重算持仓成本。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        item {
            AppButton(
                onClick = onConfirm,
                enabled = state.rows.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
                text = "导入 ${state.rows.size} 笔",
            )
        }
    }
}

@Composable
private fun TransactionReviewRowCard(
    row: TransactionReviewRow,
    onCodeOrNameChanged: (String) -> Unit,
    onTypeChanged: (String) -> Unit,
    onSharesChanged: (String) -> Unit,
    onPriceChanged: (String) -> Unit,
    onDateChanged: (String) -> Unit,
    onRemove: () -> Unit
) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "代码 / 名称",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                if (row.resolvedName != null) {
                    Text(
                        text = row.resolvedName,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                }
                IconButton(onClick = onRemove) {
                    Icon(Icons.Default.Delete, contentDescription = "删除该行")
                }
            }
            AppTextField(
                value = row.codeOrNameInput,
                onValueChange = onCodeOrNameChanged,
                label = { Text("6 位代码或股票名称") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = row.codeOrNameError != null,
                supportingText = row.codeOrNameError?.let {
                    { Text(it, color = MaterialTheme.colorScheme.error) }
                },
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = row.typeInput == "BUY",
                    onClick = { onTypeChanged("BUY") },
                    label = { Text("买入") },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = row.typeInput == "SELL",
                    onClick = { onTypeChanged("SELL") },
                    label = { Text("卖出") },
                    modifier = Modifier.weight(1f)
                )
            }
            if (row.typeError != null) {
                Text(
                    text = row.typeError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AppTextField(
                    value = row.sharesInput,
                    onValueChange = onSharesChanged,
                    label = { Text("股数") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = row.sharesError != null,
                    supportingText = row.sharesError?.let {
                        { Text(it, color = MaterialTheme.colorScheme.error) }
                    },
                )
                AppTextField(
                    value = row.priceInput,
                    onValueChange = onPriceChanged,
                    label = { Text("成交价") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = row.priceError != null,
                    supportingText = row.priceError?.let {
                        { Text(it, color = MaterialTheme.colorScheme.error) }
                    },
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            AppTextField(
                value = row.dateInput,
                onValueChange = onDateChanged,
                label = { Text("日期（yyyy-MM-dd）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = row.dateError != null,
                supportingText = row.dateError?.let {
                    { Text(it, color = MaterialTheme.colorScheme.error) }
                },
            )
        }
    }
}
