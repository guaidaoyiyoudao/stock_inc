package com.stock.dividend.ui.screen

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.layout.width
import androidx.compose.material3.RadioButton
import com.stock.dividend.data.repository.LlmProviderPreset
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stock.dividend.ui.component.AppCard
import com.stock.dividend.ui.component.AppCardTone
import com.stock.dividend.viewmodel.NotificationSettingsViewModel
import com.stock.dividend.ui.component.AppButton
import com.stock.dividend.ui.component.AppTextButton
import com.stock.dividend.ui.component.AppTextField

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
    onOpenNotificationReliability: () -> Unit,
    onOpenStrategyLibrary: () -> Unit,
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
            .verticalScroll(rememberScrollState())
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
        LlmConfigSettingsContent(viewModel)
        SettingsEntryRow(
            entry = settingsEntries[1],
            onClick = onOpenDataManagement
        )
        SettingsEntryRow(
            entry = SettingsEntry(
                title = "策略库",
                description = "截图经 AI 分析提取的买卖策略，对所有股票通用"
            ),
            onClick = onOpenStrategyLibrary
        )
        SettingsEntryRow(
            entry = SettingsEntry(
                title = "通知可靠性",
                description = "确保股价/股息率提醒按时推送（Vivo 等需开启后台运行）"
            ),
            onClick = onOpenNotificationReliability
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
    AppTextField(
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
    AppButton(
        onClick = onSave,
        modifier = Modifier.fillMaxWidth(),
        text = "保存",
    )
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
    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        tone = AppCardTone.List,
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
        AppTextField(
            value = state.evalMinInput,
            onValueChange = onMinChange,
            label = { Text("最低股息率 (%)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        AppTextField(
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
        AppButton(
            onClick = onSave,
            modifier = Modifier.fillMaxWidth(),
            text = "保存",
        )
        if (state.evalSaved) {
            Text(
                text = "已保存",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun LlmConfigSettingsContent(viewModel: NotificationSettingsViewModel) {
    val config by viewModel.llmConfigState.collectAsStateWithLifecycle()
    var apiKey by remember(config.apiKey) { mutableStateOf(config.apiKey) }
    var baseUrl by remember(config.baseUrl) { mutableStateOf(config.baseUrl) }
    var model by remember(config.model) { mutableStateOf(config.model) }
    var showKey by remember { mutableStateOf(false) }
    val selectedProvider =
        LlmProviderPreset.entries.firstOrNull { it.baseUrl == config.baseUrl } ?: LlmProviderPreset.CUSTOM

    Column(Modifier.fillMaxWidth()) {
        Text(
            text = "LLM 配置",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "用于一键评估的 AI 解读。Key 仅存本机。",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(12.dp))
        LlmProviderPreset.entries.forEach { p ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = selectedProvider == p,
                    onClick = {
                        viewModel.setLlmProvider(p)
                        if (p != LlmProviderPreset.CUSTOM) {
                            baseUrl = p.baseUrl
                            model = p.defaultModel
                        }
                    }
                )
                Text(p.displayName)
            }
        }
        Spacer(Modifier.height(8.dp))
        AppTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = { Text("Base URL") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        AppTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text("API Key") },
            singleLine = true,
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                AppTextButton(
                    onClick = { showKey = !showKey },
                    text = if (showKey) "隐藏" else "显示",
                )
            },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        AppTextField(
            value = model,
            onValueChange = { model = it },
            label = { Text("Model") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        AppButton(
            onClick = { viewModel.saveLlmConfig(baseUrl, apiKey, model) },
            text = "保存",
        )
    }
}
