package com.stock.dividend.ui.screen

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stock.dividend.viewmodel.NotificationSettingsViewModel

internal data class SettingsEntry(
    val title: String,
    val description: String
)

internal val settingsEntries = listOf(
    SettingsEntry(
        title = "通知设置",
        description = "管理全局股息率阈值提醒"
    ),
    SettingsEntry(
        title = "数据管理",
        description = "导入或导出本地备份文件"
    )
)

@Composable
fun SettingsScreen(
    onOpenDataManagement: () -> Unit,
    onOpenOcrDebug: () -> Unit,
    viewModel: NotificationSettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        viewModel.save()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = settingsEntries[0].title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        NotificationSettingsContent(
            state = state,
            onEnabledChange = viewModel::updateEnabled,
            onThresholdChange = viewModel::updateThreshold,
            onSave = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && state.enabled) {
                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    viewModel.save()
                }
            }
        )
        Text(
            text = "评估门槛",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        EvalThresholdSettingsContent(
            state = state,
            onMinChange = viewModel::updateEvalMin,
            onBoostChange = viewModel::updateEvalBoost,
            onSave = viewModel::saveEvalThresholds
        )
        SettingsEntryRow(
            entry = settingsEntries[1],
            onClick = onOpenDataManagement
        )
        // 临时调试入口，定位 OCR 识别问题后删除
        SettingsEntryRow(
            entry = SettingsEntry(
                title = "OCR 调试（临时）",
                description = "测试不同预处理方式对截图识别的影响"
            ),
            onClick = onOpenOcrDebug
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationSettingsScreen(
    onBack: () -> Unit,
    viewModel: NotificationSettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        viewModel.save()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("通知设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "股息率阈值提醒",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            NotificationSettingsContent(
                state = state,
                onEnabledChange = viewModel::updateEnabled,
                onThresholdChange = viewModel::updateThreshold,
                onSave = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && state.enabled) {
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        viewModel.save()
                    }
                }
            )
        }
    }
}

@Composable
private fun NotificationSettingsContent(
    state: com.stock.dividend.viewmodel.NotificationSettingsUiState,
    onEnabledChange: (Boolean) -> Unit,
    onThresholdChange: (String) -> Unit,
    onSave: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("全局提醒")
            Text(
                text = "任意持仓股息率从低于阈值变为达到阈值时通知",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = state.enabled,
            onCheckedChange = onEnabledChange
        )
    }
    OutlinedTextField(
        value = state.thresholdInput,
        onValueChange = onThresholdChange,
        label = { Text("阈值 (%)") },
        singleLine = true,
        isError = state.thresholdError != null,
        supportingText = {
            Text(state.thresholdError ?: "例如：5.0")
        },
        modifier = Modifier.fillMaxWidth()
    )
    Button(
        onClick = onSave,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("保存")
    }
    if (state.saved) {
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "已保存",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun SettingsEntryRow(
    entry: SettingsEntry,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.Settings,
                contentDescription = null
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = entry.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = entry.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null
            )
        }
    }
}

@Composable
private fun EvalThresholdSettingsContent(
    state: com.stock.dividend.viewmodel.NotificationSettingsUiState,
    onMinChange: (String) -> Unit,
    onBoostChange: (String) -> Unit,
    onSave: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "一键评估时：股息率低于「最低」不给买；达到「加分」可把持有上调为买",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = state.evalMinInput,
            onValueChange = onMinChange,
            label = { Text("最低股息率 (%)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = state.evalBoostInput,
            onValueChange = onBoostChange,
            label = { Text("加分股息率 (%)") },
            singleLine = true,
            isError = state.evalError != null,
            supportingText = {
                Text(state.evalError ?: "例如：2.0 / 5.0")
            },
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = onSave,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("保存")
        }
        if (state.evalSaved) {
            Text(
                text = "已保存",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}
