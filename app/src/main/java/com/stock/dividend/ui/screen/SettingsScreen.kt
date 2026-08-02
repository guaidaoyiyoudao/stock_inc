package com.stock.dividend.ui.screen

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoGraph
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stock.dividend.data.repository.LlmProviderPreset
import com.stock.dividend.ui.component.AppButton
import com.stock.dividend.ui.component.AppCard
import com.stock.dividend.ui.component.AppCardTone
import com.stock.dividend.ui.component.AppTextButton
import com.stock.dividend.ui.component.AppTextField
import com.stock.dividend.viewmodel.NotificationSettingsViewModel

/**
 * 设置页分组标题（按渲染顺序）。供 [SettingsScreen] 渲染与单测断言共用，
 * 避免把文案散落在 Composable 内导致测试只能比对渲染树。
 */
internal val settingsGroupTitles = listOf(
    "提醒与评估",
    "AI 与策略",
    "数据",
)

/**
 * 设置页（底部导航「设置」Tab）。
 *
 * 按 3 个功能分组组织：提醒与评估 / AI 与策略 / 数据。
 * 每组用 [SettingsGroupCard]（一张 AppCard）包裹，组内表单与跳转入口混排，
 * 用 [HorizontalDivider] 轻量分隔。表单项保留各自独立的保存逻辑。
 */
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
        // ── 提醒与评估 ───────────────────────────────────────────────
        SettingsGroupCard(title = settingsGroupTitles[0]) {
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
            SettingsGroupDivider()
            EvalThresholdSettingsContent(
                state = state,
                onMinChange = viewModel::updateEvalMin,
                onBoostChange = viewModel::updateEvalBoost,
                onSave = viewModel::saveEvalThresholds
            )
            SettingsGroupDivider()
            SettingsNavRow(
                title = "通知可靠性",
                description = "确保股价/股息率提醒按时推送（Vivo 等需开启后台运行）",
                icon = Icons.Filled.Campaign,
                onClick = onOpenNotificationReliability
            )
        }

        // ── AI 与策略 ────────────────────────────────────────────────
        SettingsGroupCard(title = settingsGroupTitles[1]) {
            LlmConfigSettingsContent(viewModel)
            SettingsGroupDivider()
            SettingsNavRow(
                title = "策略库",
                description = "截图经 AI 分析提取的买卖策略，对所有股票通用",
                icon = Icons.Filled.AutoGraph,
                onClick = onOpenStrategyLibrary
            )
        }

        // ── 数据 ────────────────────────────────────────────────────
        SettingsGroupCard(title = settingsGroupTitles[2]) {
            SettingsNavRow(
                title = "数据管理",
                description = "导入或导出本地备份文件",
                icon = Icons.Filled.CloudSync,
                onClick = onOpenDataManagement
            )
            SettingsGroupDivider()
            // 临时调试入口，定位 OCR 识别问题后删除
            SettingsNavRow(
                title = "OCR 调试（临时）",
                description = "测试不同预处理方式对截图识别的影响",
                icon = Icons.Filled.BugReport,
                onClick = onOpenOcrDebug
            )
        }
    }
}

/**
 * 分组卡片：一张 AppCard，顶部组标题（titleMedium + SemiBold），下方按 12dp 间距排列组内项。
 */
@Composable
private fun SettingsGroupCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    AppCard(
        modifier = modifier.fillMaxWidth(),
        tone = AppCardTone.Surface,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            content()
        }
    }
}

/**
 * 组内项之间的轻量分隔线，弱化分隔、强化同组归属。
 */
@Composable
private fun SettingsGroupDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/**
 * 跳转入口行：[icon] + 标题/描述 + 右箭头，整行可点击。
 *
 * 替代旧的全用齿轮图标的 `SettingsEntryRow`，每组入口用差异化图标区分功能。
 */
@Composable
private fun SettingsNavRow(
    title: String,
    description: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = description,
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
            text = "用于一键评估的 AI 解读。Key 仅存本机。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
