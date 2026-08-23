package com.stock.dividend.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stock.dividend.ui.component.AppButton
import com.stock.dividend.ui.component.AppTextButton
import com.stock.dividend.ui.component.AppTextField
import com.stock.dividend.viewmodel.DataSourceSettingsViewModel

/**
 * 「数据」设置页（设置主页的二级页面）。
 *
 * 含数据源（同花顺扶摇 API Key）、数据管理（备份/恢复）、缓存管理、失败日志与
 * OCR 调试（临时）等入口。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataSettingsScreen(
    onBack: () -> Unit,
    onOpenDataManagement: () -> Unit,
    onOpenCacheManagement: () -> Unit,
    onOpenErrorLogs: () -> Unit,
    onOpenOcrDebug: () -> Unit,
    viewModel: DataSourceSettingsViewModel = hiltViewModel()
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("数据") },
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
            // ── 数据源（同花顺扶摇）──
            Text(
                text = "数据源（同花顺扶摇）",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            FuyaoApiKeyContent(viewModel)
            SettingsNavRow(
                title = "数据管理",
                description = "导入或导出本地备份文件",
                icon = Icons.Filled.CloudSync,
                onClick = onOpenDataManagement
            )
            SettingsNavRow(
                title = "缓存管理",
                description = "查看各类缓存条目数，按需清理；历史不可变数据永久缓存",
                icon = Icons.Filled.CleaningServices,
                onClick = onOpenCacheManagement
            )
            SettingsNavRow(
                title = "失败日志",
                description = "查看数据获取失败等关键失败记录，支持一键清理",
                icon = Icons.Filled.Warning,
                onClick = onOpenErrorLogs
            )
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
 * 同花顺扶摇 API Key 配置（权威第一数据源的认证凭证）。
 * Key 仅存本机（未加密，与 LLM Key 同策略）；保存后立即生效（拦截器每次请求动态读取）。
 * 未配置时同花顺源整体禁用，数据获取全走东财/腾讯候补源，功能完整可用。
 */
@Composable
internal fun FuyaoApiKeyContent(viewModel: DataSourceSettingsViewModel) {
    val savedKey by viewModel.fuyaoApiKeyState.collectAsStateWithLifecycle()
    var apiKey by remember(savedKey) { mutableStateOf(savedKey) }
    var showKey by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        Text(
            text = "同花顺官方金融数据 API（fuyao.aicubes.cn）。配置后行情/分红/K线/财务以同花顺为" +
                "权威主源，东财/腾讯自动候补并补齐缺失字段；不配置则维持东财/腾讯数据源。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
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
        if (savedKey.isNotBlank()) {
            Text(
                text = "✓ 已启用（Key 留空保存即停用并恢复候补源）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(4.dp))
        }
        AppButton(
            onClick = { viewModel.saveFuyaoApiKey(apiKey) },
            text = "保存",
        )
    }
}
