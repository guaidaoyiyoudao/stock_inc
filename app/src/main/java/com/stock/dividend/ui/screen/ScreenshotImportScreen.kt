package com.stock.dividend.ui.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stock.dividend.data.repository.ScreenshotStrategy
import com.stock.dividend.viewmodel.ScreenshotImportPhase
import com.stock.dividend.viewmodel.ScreenshotImportUiState
import com.stock.dividend.viewmodel.ScreenshotImportViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenshotImportScreen(
    onBack: () -> Unit,
    onViewList: () -> Unit,
    viewModel: ScreenshotImportViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.onImagePicked(uri)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("截图策略分析") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, null) }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding)
                .verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (state.phase) {
                ScreenshotImportPhase.Idle -> {
                    Button(onClick = {
                        pickMedia.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }) { Text("选择截图") }
                }

                ScreenshotImportPhase.LoadingImage, ScreenshotImportPhase.OcrRunning -> {
                    CircularProgressIndicator()
                    Text("识别中…")
                }

                ScreenshotImportPhase.ReviewOcr -> ReviewOcrContent(state, viewModel) {
                    pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }

                ScreenshotImportPhase.Analyzing -> {
                    CircularProgressIndicator()
                    Text("AI 分析中…")
                }

                ScreenshotImportPhase.ReviewStrategy -> ReviewStrategyContent(state, viewModel)

                ScreenshotImportPhase.Done -> {
                    Text("策略已保存")
                    TextButton(onClick = onViewList) { Text("查看策略库") }
                    TextButton(onClick = viewModel::resetToIdle) { Text("再分析一张") }
                }

                ScreenshotImportPhase.Error -> {
                    Text(state.errorMessage ?: "出错了", color = MaterialTheme.colorScheme.error)
                    Button(onClick = viewModel::resetToIdle) { Text("重新开始") }
                }
            }
        }
    }
}

@Composable
private fun ReviewOcrContent(
    state: ScreenshotImportUiState,
    vm: ScreenshotImportViewModel,
    onRetry: () -> Unit
) {
    state.analysisError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    OutlinedTextField(
        value = state.editableOcrText,
        onValueChange = vm::onOcrTextChanged,
        label = { Text("OCR 文本（可编辑修正）") },
        modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp)
    )
    Row {
        Button(
            onClick = vm::startAnalysis,
            enabled = state.editableOcrText.isNotBlank()
        ) { Text("AI 提取策略") }
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = onRetry) { Text("重选图片") }
    }
}

@Composable
private fun ReviewStrategyContent(
    state: ScreenshotImportUiState,
    vm: ScreenshotImportViewModel
) {
    val s = state.editableStrategy ?: return
    OutlinedTextField(
        value = s.targetText,
        onValueChange = vm::onTargetTextChanged,
        label = { Text("标的/语境") },
        modifier = Modifier.fillMaxWidth()
    )
    Text("方向")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ScreenshotStrategy.StrategyDirection.values().forEach { d ->
            FilterChip(
                selected = s.direction == d,
                onClick = { vm.onDirectionChanged(d) },
                label = { Text(dirZh(d)) }
            )
        }
    }
    OutlinedTextField(
        value = s.reasoning,
        onValueChange = vm::onReasoningChanged,
        label = { Text("核心理由") },
        modifier = Modifier.fillMaxWidth()
    )
    s.risks.forEachIndexed { i, r ->
        OutlinedTextField(
            value = r,
            onValueChange = { vm.onRiskChanged(i, it) },
            label = { Text("风险 ${i + 1}") },
            modifier = Modifier.fillMaxWidth()
        )
    }
    TextButton(onClick = vm::addRisk) { Text("+ 添加风险") }
    OutlinedTextField(
        value = s.validUntil ?: "",
        onValueChange = { vm.onValidUntilChanged(it.ifBlank { null }) },
        label = { Text("有效期 YYYY-MM-DD（空=长期）") },
        modifier = Modifier.fillMaxWidth()
    )
    OutlinedTextField(
        value = state.sourceNote,
        onValueChange = vm::onSourceNoteChanged,
        label = { Text("来源备注（可选）") },
        modifier = Modifier.fillMaxWidth()
    )
    state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Row {
        Button(onClick = vm::confirmSave) { Text("保存策略") }
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = vm::backToOcrReview) { Text("返回重提") }
    }
}

private fun dirZh(d: ScreenshotStrategy.StrategyDirection) = when (d) {
    ScreenshotStrategy.StrategyDirection.BUY -> "买入"
    ScreenshotStrategy.StrategyDirection.SELL -> "卖出"
    ScreenshotStrategy.StrategyDirection.WATCH -> "观望"
}
