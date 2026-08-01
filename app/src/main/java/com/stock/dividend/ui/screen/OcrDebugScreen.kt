package com.stock.dividend.ui.screen

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stock.dividend.ui.component.AppCard
import com.stock.dividend.ui.component.AppCardDefaults
import com.stock.dividend.ui.component.CompactTopAppBar
import com.stock.dividend.viewmodel.OcrDebugViewModel
import com.stock.dividend.viewmodel.PreprocessMode
import com.stock.dividend.ui.component.AppTextButton
import com.stock.dividend.ui.component.AppOutlinedButton
import com.stock.dividend.ui.component.AppButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OcrDebugScreen(
    onBack: () -> Unit,
    viewModel: OcrDebugViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val pickMedia = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let { viewModel.onImagePicked(it) } }

    fun launchPicker() {
        pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    Scaffold(
        topBar = { CompactTopAppBar(title = "OCR 调试（临时）", onBack = onBack) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = AppCardDefaults.PageHorizontalPadding)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("预处理方式", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                PreprocessMode.values().forEach { mode ->
                    FilterChip(
                        selected = state.mode == mode,
                        onClick = { viewModel.selectMode(mode) },
                        label = { Text(mode.label) }
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                AppButton(
                    onClick = { launchPicker() },
                    modifier = Modifier.weight(1f),
                    text = "选图识别",
                )
                AppOutlinedButton(
                    onClick = { viewModel.rerun() },
                    modifier = Modifier.weight(1f),
                    text = "重跑当前图",
                )
            }

            if (state.processedPreview != null) {
                Text("预处理后图片预览：", style = MaterialTheme.typography.labelMedium)
                androidx.compose.foundation.Image(
                    bitmap = state.processedPreview!!.asImageBitmap(),
                    contentDescription = "预处理后的图片",
                    modifier = Modifier.fillMaxWidth().height(220.dp)
                )
            }

            if (state.isLoading) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.size(8.dp))
                        Text("识别中…")
                    }
                }
            }

            state.error?.let { err -> Text("错误：$err", color = MaterialTheme.colorScheme.error) }

            state.rawText?.let { txt ->
                AppCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "原始识别文本（${txt.length} 字符，${txt.lines().size} 行）",
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.weight(1f)
                            )
                            val context = LocalContext.current
                            AppTextButton(
                                onClick = {
                                val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                                cm?.setPrimaryClip(android.content.ClipData.newPlainText("OCR 原始文本", txt))
                            },
                                text = "复制",
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        SelectionContainer {
                            Text(text = txt, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }

            if (state.parsedRows.isNotEmpty()) {
                Text("解析出 ${state.parsedRows.size} 行持仓：", style = MaterialTheme.typography.labelMedium)
                SelectionContainer {
                    Column {
                        state.parsedRows.forEach { row ->
                            Text(
                                text = "${row.codeOrName} | 股数=${row.shares} | 成本=${row.costPerShare}",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }
    }
}

