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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stock.dividend.ui.component.AppCardDefaults
import com.stock.dividend.ui.component.CompactTopAppBar
import com.stock.dividend.viewmodel.ImportPhase
import com.stock.dividend.viewmodel.ImportReviewRow
import com.stock.dividend.viewmodel.PortfolioImportViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortfolioImportScreen(
    onBack: () -> Unit,
    viewModel: PortfolioImportViewModel = hiltViewModel()
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
                title = "截图导入",
                onBack = onBack,
                actions = {
                    if (uiState.phase == ImportPhase.Review && uiState.rows.isNotEmpty()) {
                        TextButton(onClick = { viewModel.confirmImport() }) {
                            Text("全部导入", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            )
        }
    ) { padding ->
        when (uiState.phase) {
            ImportPhase.Done -> DoneContent(
                summary = uiState.importSummary ?: "导入完成",
                onBackToPortfolio = onBack,
                onImportAnother = { viewModel.resetToIdle() },
                modifier = Modifier.fillMaxSize().padding(padding)
            )

            ImportPhase.Error -> ErrorContent(
                message = uiState.errorMessage ?: "发生未知错误",
                rawText = uiState.ocrRawText,
                onRetry = ::launchPicker,
                onBack = onBack,
                modifier = Modifier.fillMaxSize().padding(padding)
            )

            ImportPhase.LoadingImage, ImportPhase.OcrRunning, ImportPhase.Importing -> LoadingContent(
                message = when (uiState.phase) {
                    ImportPhase.LoadingImage -> "正在读取图片…"
                    ImportPhase.OcrRunning -> "正在识别文本…"
                    ImportPhase.Importing -> "正在导入持仓…"
                    else -> "处理中…"
                },
                modifier = Modifier.fillMaxSize().padding(padding)
            )

            ImportPhase.Review -> ReviewContent(
                state = uiState,
                onPickAnother = ::launchPicker,
                onRowCodeOrNameChanged = viewModel::onRowCodeOrNameChanged,
                onRowSharesChanged = viewModel::onRowSharesChanged,
                onRowCostChanged = viewModel::onRowCostChanged,
                onRemoveRow = viewModel::removeRow,
                onAddRow = viewModel::addEmptyRow,
                onConfirm = viewModel::confirmImport,
                modifier = Modifier.fillMaxSize().padding(padding)
            )

            ImportPhase.Idle -> IdleContent(
                onPick = ::launchPicker,
                modifier = Modifier.fillMaxSize().padding(padding)
            )
        }
    }
}

@Composable
private fun IdleContent(onPick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp)
        ) {
            Text(
                text = "从同花顺截图导入持仓",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "在 同花顺 → 我的 → 持仓 页截图，选择图片后本应用会自动识别股票、股数与成本价，并可批量导入。\n识别完全在本地完成，图片不会上传。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onPick) { Text("选择持仓截图") }
        }
    }
}

@Composable
private fun LoadingContent(message: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(16.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ErrorContent(
    message: String,
    rawText: String?,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showRaw by remember { mutableStateOf(false) }
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
            if (rawText != null) {
                Spacer(modifier = Modifier.height(12.dp))
                TextButton(onClick = { showRaw = !showRaw }) {
                    Text(if (showRaw) "隐藏原始文本" else "查看原始识别文本")
                }
                if (showRaw) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = rawText,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Row {
                TextButton(onClick = onBack) { Text("返回") }
                Spacer(modifier = Modifier.size(8.dp))
                Button(onClick = onRetry) { Text("重新选图") }
            }
        }
    }
}

@Composable
private fun DoneContent(
    summary: String,
    onBackToPortfolio: () -> Unit,
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
            Button(onClick = onBackToPortfolio) { Text("返回持仓") }
            Spacer(modifier = Modifier.size(8.dp))
            TextButton(onClick = onImportAnother) { Text("再导入一张") }
        }
    }
}

@Composable
private fun ReviewContent(
    state: com.stock.dividend.viewmodel.PortfolioImportUiState,
    onPickAnother: () -> Unit,
    onRowCodeOrNameChanged: (Long, String) -> Unit,
    onRowSharesChanged: (Long, String) -> Unit,
    onRowCostChanged: (Long, String) -> Unit,
    onRemoveRow: (Long) -> Unit,
    onAddRow: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showRaw by remember { mutableStateOf(false) }

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
                    text = "识别到 ${state.rows.size} 条持仓，请核对后再导入",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onPickAnother) { Text("换一张图") }
            }
        }
        items(items = state.rows, key = { it.id }) { row ->
            ReviewRowCard(
                row = row,
                onCodeOrNameChanged = { onRowCodeOrNameChanged(row.id, it) },
                onSharesChanged = { onRowSharesChanged(row.id, it) },
                onCostChanged = { onRowCostChanged(row.id, it) },
                onRemove = { onRemoveRow(row.id) }
            )
        }
        item {
            OutlinedButton(onClick = onAddRow, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.size(6.dp))
                Text("手动添加一行")
            }
        }
        state.ocrRawText?.let { raw ->
            item {
                TextButton(onClick = { showRaw = !showRaw }) {
                    Text(if (showRaw) "隐藏原始识别文本" else "查看原始识别文本（核对用）")
                }
                if (showRaw) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = raw,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }
        }
        item {
            Button(
                onClick = onConfirm,
                modifier = Modifier.fillMaxWidth(),
                enabled = state.rows.isNotEmpty()
            ) {
                Text("导入 ${state.rows.size} 条")
            }
        }
    }
}

@Composable
private fun ReviewRowCard(
    row: ImportReviewRow,
    onCodeOrNameChanged: (String) -> Unit,
    onSharesChanged: (String) -> Unit,
    onCostChanged: (String) -> Unit,
    onRemove: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "代码 / 名称",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onRemove) {
                    Icon(Icons.Default.Delete, contentDescription = "删除该行")
                }
            }
            OutlinedTextField(
                value = row.codeOrNameInput,
                onValueChange = onCodeOrNameChanged,
                label = { Text("6 位代码或股票名称") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = row.codeOrNameError != null,
                supportingText = row.codeOrNameError?.let {
                    { Text(it, color = MaterialTheme.colorScheme.error) }
                },
                shape = MaterialTheme.shapes.medium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = row.sharesInput,
                    onValueChange = onSharesChanged,
                    label = { Text("股数") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                    isError = row.sharesError != null,
                    supportingText = row.sharesError?.let {
                        { Text(it, color = MaterialTheme.colorScheme.error) }
                    },
                    shape = MaterialTheme.shapes.medium
                )
                OutlinedTextField(
                    value = row.costPerShareInput,
                    onValueChange = onCostChanged,
                    label = { Text("成本价") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                    isError = row.costError != null,
                    supportingText = row.costError?.let {
                        { Text(it, color = MaterialTheme.colorScheme.error) }
                    },
                    shape = MaterialTheme.shapes.medium
                )
            }
        }
    }
}
