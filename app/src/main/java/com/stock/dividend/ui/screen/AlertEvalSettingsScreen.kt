package com.stock.dividend.ui.screen

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import com.stock.dividend.ui.component.AppButton
import com.stock.dividend.ui.component.AppTextField
import com.stock.dividend.viewmodel.NotificationSettingsViewModel

/**
 * 「提醒与评估」设置页（设置主页的二级页面）。
 *
 * 含三部分：股息率阈值提醒（开关+阈值）、评估门槛（最低/加分股息率）、
 * 以及「通知可靠性」跳转入口（后台推送保障）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertEvalSettingsScreen(
    onBack: () -> Unit,
    onOpenNotificationReliability: () -> Unit,
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
                title = { Text("提醒与评估") },
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
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── 股息率阈值提醒 ──
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

            // ── 评估门槛 ──
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

            // ── 通知可靠性（跳转）──
            SettingsNavRow(
                title = "通知可靠性",
                description = "确保股价/股息率提醒按时推送（Vivo 等需开启后台运行）",
                icon = Icons.Filled.Campaign,
                onClick = onOpenNotificationReliability
            )
        }
    }
}

/**
 * 通知设置表单：全局提醒开关 + 股息率阈值输入 + 保存。
 */
@Composable
internal fun NotificationSettingsContent(
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
internal fun EvalThresholdSettingsContent(
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
