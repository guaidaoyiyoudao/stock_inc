package com.stock.dividend.ui.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stock.dividend.ui.component.AppCard
import com.stock.dividend.ui.component.AppCardTone
import com.stock.dividend.viewmodel.BackupViewModel
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import com.stock.dividend.ui.component.AppTextButton
import com.stock.dividend.ui.component.AppButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupRestoreScreen(
    onBack: () -> Unit,
    viewModel: BackupViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let { viewModel.exportBackup(context, it) }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.selectImportFile(context, it) }
    }

    val exportFileName = "股息追踪_备份_${
        LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
    }.json"

    if (state.showConfirmRestoreDialog && state.backupSummary != null) {
        val summary = state.backupSummary!!
        val metadata = summary.metadata
        val counts = summary.counts
        val exportTime = LocalDateTime.ofInstant(
            Instant.ofEpochMilli(metadata.exportTimestamp),
            ZoneId.systemDefault()
        ).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

        AlertDialog(
            onDismissRequest = { viewModel.dismissConfirmDialog() },
            title = { Text("确认导入备份") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "导入将覆盖当前所有数据。备份信息：",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "应用版本：${metadata.appVersion}\n" +
                            "导出时间：$exportTime\n" +
                            "数据库版本：${metadata.dbVersion}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    androidx.compose.material3.HorizontalDivider()
                    Text(
                        "数据预览（共 ${counts.total} 条）：",
                        style = MaterialTheme.typography.labelLarge
                    )
                    Text(
                        "· 自选股：${counts.stocks} 只\n" +
                            "· 分红记录：${counts.dividends} 条\n" +
                            "· 交易记录：${counts.transactions} 条\n" +
                            "· 股息到账：${counts.dividendIncomeRecords} 条\n" +
                            "· 交易策略：${counts.tradeStrategies} 条\n" +
                            "· 行业配比：${counts.industryTargets} 项",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "此操作不可撤销，确定继续？",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            },
            confirmButton = {
                AppTextButton(
                    onClick = { viewModel.confirmRestore(context) },
                    text = "导入",
                )
            },
            dismissButton = {
                AppTextButton(
                    onClick = { viewModel.dismissConfirmDialog() },
                    text = "取消",
                )
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("数据管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Export section
                AppCard(
                    modifier = Modifier.fillMaxWidth(),
                    tone = AppCardTone.List
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "导出备份",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "将所有股票、股息、交易、生活支出等数据导出为 JSON 文件，可保存到设备任意位置。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        AppButton(
                            onClick = { exportLauncher.launch(exportFileName) },
                            modifier = Modifier.fillMaxWidth(),
                            text = "导出备份文件",
                        )
                    }
                }

                // Import section
                AppCard(
                    modifier = Modifier.fillMaxWidth(),
                    tone = AppCardTone.List
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "导入备份",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "从之前导出的备份文件恢复所有数据。请注意：导入将覆盖当前所有数据，此操作不可撤销！",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        AppButton(
                            onClick = { importLauncher.launch(arrayOf("application/json")) },
                            modifier = Modifier.fillMaxWidth(),
                            text = "选择备份文件",
                        )
                    }
                }

                // Message banner
                AnimatedVisibility(
                    visible = state.message != null,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    state.message?.let { msg ->
                        AppCard(
                            modifier = Modifier.fillMaxWidth(),
                            containerColor = if (state.isError)
                                MaterialTheme.colorScheme.errorContainer
                            else
                                MaterialTheme.colorScheme.primaryContainer,
                            contentColor = if (state.isError)
                                MaterialTheme.colorScheme.onErrorContainer
                            else
                                MaterialTheme.colorScheme.onPrimaryContainer,
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = msg,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (state.isError)
                                        MaterialTheme.colorScheme.onErrorContainer
                                    else
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                AppTextButton(
                                    onClick = { viewModel.clearMessage() },
                                    text = "关闭",
                                )
                            }
                        }
                    }
                }
            }

            // Loading overlay
            if (state.isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }
    }
}
